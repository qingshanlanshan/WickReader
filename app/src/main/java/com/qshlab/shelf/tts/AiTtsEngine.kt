package com.qshlab.shelf.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.qshlab.shelf.chinese.ChineseConverter
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.TextContentTokenizer
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.tokenizer.TextUnit
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

/** "zh-TW" → "zh", "cmn" → "zh". */
fun baseLanguage(code: String?): String? {
    val base = code?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return null
    return if (base == "cmn" || base == "yue") "zh" else base
}

@OptIn(ExperimentalReadiumApi::class)
data class AiTtsPreferences(
    override val language: Language? = null,
    val speed: Double? = null,
    /** Chosen voice per base language ("zh", "en"). */
    val voices: Map<String, AiVoiceId>? = null,
) : TtsEngine.Preferences<AiTtsPreferences> {
    override fun plus(other: AiTtsPreferences) = AiTtsPreferences(
        language = other.language ?: language,
        speed = other.speed ?: speed,
        voices = other.voices ?: voices,
    )
}

@OptIn(ExperimentalReadiumApi::class)
data class AiTtsSettings(
    override val language: Language?,
    override val overrideContentLanguage: Boolean,
    val speed: Double,
    val voices: Map<String, AiVoiceId>,
) : TtsEngine.Settings

@OptIn(ExperimentalReadiumApi::class)
data class AiVoice(val id: AiVoiceId, val name: String, override val language: Language) : TtsEngine.Voice

@OptIn(ExperimentalReadiumApi::class)
sealed class AiTtsError(override val message: String) : TtsEngine.Error {
    override val cause: Error? = null

    class NoVoice(language: String?) : AiTtsError("没有可读${language ?: "这种语言"}的 AI 语音")
    class Synthesis(detail: String?) : AiTtsError("AI 语音合成失败：${detail ?: "未知错误"}")
}

/**
 * Readium TTS engine backed by sherpa-onnx neural voices.
 *
 * Each sentence is synthesized whole (splitting it breaks the intonation) and only played once its
 * audio is complete, so playback never stalls mid-sentence. To avoid pauses between sentences,
 * the next few sentences are synthesized ahead while the current one plays: the reader reports
 * where it is ([onUtterance]) and a lookahead walks the book from there with the same sentence
 * tokenizer Readium uses.
 */
@OptIn(ExperimentalReadiumApi::class)
class AiTtsEngine(
    private val manager: VoiceManager,
    private val converter: ChineseConverter,
    private val publication: Publication,
    initialPreferences: AiTtsPreferences,
) : TtsEngine<AiTtsSettings, AiTtsPreferences, AiTtsError, AiVoice> {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val synthDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val player = PcmPlayer()

    private val _settings = MutableStateFlow(resolve(initialPreferences))
    override val settings: StateFlow<AiTtsSettings> = _settings.asStateFlow()

    override fun submitPreferences(preferences: AiTtsPreferences) {
        _settings.value = resolve(preferences)
    }

    private fun resolve(p: AiTtsPreferences) = AiTtsSettings(
        language = p.language ?: publication.metadata.language,
        overrideContentLanguage = false,
        speed = p.speed ?: 1.0,
        voices = p.voices ?: emptyMap(),
    )

    override val voices: Set<AiVoice>
        get() = VoiceCatalog.models.filter { it.id in manager.installed.value }.flatMap { m ->
            m.speakers.map { s -> AiVoice(AiVoiceId(m.id, s.sid), s.name, Language(s.language)) }
        }.toSet()

    private var listener: TtsEngine.Listener<AiTtsError>? = null

    override fun setListener(listener: TtsEngine.Listener<AiTtsError>?) {
        this.listener = listener
    }

    /** The voice for [language], falling back to the first installed model that reads it. */
    fun pick(language: String?): Pair<VoiceModel, Int>? {
        val lang = language ?: return null
        settings.value.voices[lang]?.let { v ->
            val m = VoiceCatalog.get(v.modelId)
            if (m != null && lang in m.languages && m.id in manager.installed.value && m.speakers.any { it.sid == v.sid }) return m to v.sid
        }
        val m = manager.modelsFor(lang).firstOrNull() ?: return null
        return m to (m.defaults[lang] ?: m.speakersFor(lang).first().sid)
    }

    private val bookLanguage get() = baseLanguage(settings.value.language?.code)

    /** Loads the model for the book's language before the first sentence. */
    suspend fun preload() {
        val (model, _) = pick(bookLanguage) ?: throw IllegalStateException(AiTtsError.NoVoice(bookLanguage).message)
        manager.load(model)
    }

    // ---- Synthesis: one worker, the sentence being read first, then the next few ----

    private class Audio(val samples: FloatArray, val sampleRate: Int)

    /** A sentence to synthesize, keyed by voice, speed and text. */
    private inner class Job(text: String, language: String?) {
        val lang = language ?: bookLanguage
        val voice = pick(lang) ?: pick(bookLanguage)
        val speed = settings.value.speed.toFloat()
        val spoken = if (lang == "zh") converter.toSimplified(text) else text
        val key = "${voice?.first?.id}:${voice?.second}:$speed:$spoken"
    }

    private val lock = Any()

    /** Synthesis time ÷ audio length, smoothed; above 1 the phone can't keep up with this voice. */
    @Volatile var speedRatio = 0.5
        private set
    private val ready = object : LinkedHashMap<String, Audio>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Audio>) = size > 16
    }
    private val waiting = mutableMapOf<String, CompletableDeferred<Audio>>()
    private var wanted: Job? = null
    private var upcoming: List<Job> = emptyList()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val worker = scope.launch(synthDispatcher) {
        while (true) {
            // The sentence being read comes first; work ahead only once it's ready.
            val job = synchronized(lock) {
                val w = wanted
                when {
                    w == null -> null
                    w.key !in ready -> w
                    else -> upcoming.firstOrNull { it.key !in ready }
                }
            }
            if (job == null) {
                wake.receive()
                continue
            }
            val result = runCatching {
                val (model, sid) = job.voice ?: throw IllegalStateException(AiTtsError.NoVoice(job.lang).message)
                val tts = manager.load(model)
                val started = System.nanoTime()
                val parts = splitLong(job.spoken).map { tts.generateWithConfig(it, GenerationConfig(sid = sid, speed = job.speed)) }
                val samples = if (parts.size == 1) parts[0].samples else FloatArray(parts.sumOf { it.samples.size }).also { all ->
                    var at = 0
                    for (p in parts) { p.samples.copyInto(all, at); at += p.samples.size }
                }
                val rate = parts[0].sampleRate
                val ms = (System.nanoTime() - started) / 1e6
                val audioMs = samples.size * 1000.0 / rate
                speedRatio = speedRatio * 0.7 + ms / audioMs.coerceAtLeast(1.0) * 0.3
                android.util.Log.d("ShelfTts", "synth ${ms.toInt()} ms for ${audioMs.toInt()} ms audio (${job.spoken.length} chars)")
                Audio(fade(samples, rate), rate)
            }
            synchronized(lock) {
                result.onSuccess { ready[job.key] = it }
                val waiter = waiting.remove(job.key)
                result.onSuccess { waiter?.complete(it) }.onFailure { e ->
                    waiter?.completeExceptionally(e)
                    // Don't retry a sentence that can't be spoken; move past it.
                    if (wanted?.key == job.key) wanted = null
                    upcoming = upcoming.filter { it.key != job.key }
                }
            }
        }
    }

    private fun audioFor(job: Job): Deferred<Audio> = synchronized(lock) {
        ready[job.key]?.let { return CompletableDeferred(it) }
        if (upcoming.none { it.key == job.key }) android.util.Log.d("ShelfTts", "not predicted: ${job.spoken.take(30)}")
        wanted = job
        waiting.getOrPut(job.key) { CompletableDeferred() }.also { wake.trySend(Unit) }
    }

    private var lookahead: kotlinx.coroutines.Job? = null
    private var lookaheadFrom: String? = null

    /**
     * Called with the sentence the reader is on: lines up the next few sentences so the worker
     * synthesizes them while this one plays.
     */
    fun onUtterance(locator: Locator) {
        val here = locator.text.highlight ?: return
        if (here == lookaheadFrom) return
        lookaheadFrom = here
        lookahead?.cancel()
        lookahead = scope.launch {
            val tokenizer = TextContentTokenizer(settings.value.language, TextUnit.Sentence, false)
            val iterator = publication.content(locator)?.iterator() ?: return@launch
            // Enough sentences to cover ~AHEAD_CHARS of text (a long sentence takes long to synthesize).
            val next = mutableListOf<Job>()
            fun enough() = next.size >= MAX_AHEAD || (next.size >= MIN_AHEAD && next.sumOf { it.spoken.length } >= AHEAD_CHARS)
            fun norm(t: String) = t.filterNot { it.isWhitespace() }
            val current = norm(here)
            var first = true
            while (!enough()) {
                val element = iterator.nextOrNull() ?: break
                val sentences = tokenizer.tokenize(element)
                    .flatMap { (it as? Content.TextElement)?.segments.orEmpty() }
                    .filter { seg -> seg.text.any { it.isLetterOrDigit() } }
                // In the paragraph being read, start after the current sentence (all of it if not found).
                val start = if (first) sentences.indexOfFirst { norm(it.text) == current } + 1 else 0
                first = false
                for (seg in sentences.drop(start)) {
                    if (enough()) break
                    next += Job(seg.text, baseLanguage(seg.language?.code))
                }
            }
            synchronized(lock) { upcoming = next }
            wake.trySend(Unit)
        }
    }

    private var current: kotlinx.coroutines.Job? = null
    private var currentId: TtsEngine.RequestId? = null

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        current?.cancel()
        currentId = requestId
        val job = Job(text, baseLanguage(language?.code))
        if (job.voice == null) {
            notify { onError(requestId, AiTtsError.NoVoice(job.lang)) }
            return
        }
        current = scope.launch {
            try {
                val asked = System.nanoTime()
                val a = audioFor(job).await()
                android.util.Log.d("ShelfTts", "waited ${(System.nanoTime() - asked) / 1_000_000} ms for ${a.samples.size * 1000L / a.sampleRate} ms of audio")
                notify { onStart(requestId) }
                withContext(Dispatchers.IO) {
                    player.prepare(a.sampleRate)
                    player.write(a.samples)
                }
                player.awaitPendingBelow(a.sampleRate / 50L)
                notify { onDone(requestId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notify { onError(requestId, AiTtsError.Synthesis(e.message)) }
            }
        }
    }

    override fun stop() {
        current?.cancel()
        current = null
        player.stop()
        currentId?.let { id -> notify { onInterrupted(id) } }
        currentId = null
    }

    override fun close() {
        stop()
        worker.cancel()
        scope.cancel()
        synthDispatcher.close()
        player.release()
    }

    private fun notify(block: TtsEngine.Listener<AiTtsError>.() -> Unit) {
        scope.launch(Dispatchers.Main) { listener?.block() }
    }

    companion object {
        private val CLAUSE_END = setOf('；', ';', '：', ':', '，', ',', '、')

        /**
         * Very long sentences (common in English novels) are slow to synthesize in one go and can
         * exceed the model's input limit: split them at clause punctuation. Normal sentences stay
         * whole so their intonation is kept.
         */
        fun splitLong(text: String): List<String> {
            val cjk = text.count { it.code in 0x3000..0x9fff } * 2 > text.length
            val max = if (cjk) 80 else 150
            if (text.length <= max) return listOf(text)
            val pieces = mutableListOf<String>()
            val buf = StringBuilder()
            for (c in text) {
                buf.append(c)
                if (c in CLAUSE_END && buf.length >= max / 2) {
                    pieces += buf.toString()
                    buf.clear()
                }
            }
            if (buf.isNotBlank()) {
                if (pieces.isNotEmpty() && buf.length < max / 4) pieces[pieces.lastIndex] += buf else pieces += buf.toString()
            }
            return pieces
        }

        /** Sentences synthesized ahead of the one being read. */
        private const val MIN_AHEAD = 3
        private const val MAX_AHEAD = 10
        private const val AHEAD_CHARS = 600

        /** 8 ms fade in / out: sentences start and end at silence, without pops. */
        private fun fade(samples: FloatArray, sampleRate: Int): FloatArray {
            val n = minOf(sampleRate / 125, samples.size / 2)
            for (i in 0 until n) {
                val g = i.toFloat() / n
                samples[i] *= g
                samples[samples.size - 1 - i] *= g
            }
            return samples
        }
    }
}

/** Streams float PCM to an AudioTrack and tracks how much is still queued. */
internal class PcmPlayer {
    private var track: AudioTrack? = null
    private var rate = 0
    private var written = 0L

    @Synchronized
    fun prepare(sampleRate: Int) {
        if (track != null && rate == sampleRate) {
            if (track?.playState != AudioTrack.PLAYSTATE_PLAYING) track?.play()
            return
        }
        release()
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, sampleRate * 4 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        rate = sampleRate
        written = 0
        track?.play()
    }

    fun write(samples: FloatArray) {
        val t = track ?: return
        var offset = 0
        while (offset < samples.size) {
            val n = t.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) return
            offset += n
        }
        written += samples.size
    }

    private fun pending(): Long {
        val t = track ?: return 0
        return written - (t.playbackHeadPosition.toLong() and 0xffffffffL)
    }

    suspend fun awaitPendingBelow(frames: Long) {
        while (pending() > frames) delay(15)
    }

    /** Drops queued audio immediately (pause, skip). */
    @Synchronized
    fun stop() = release()

    @Synchronized
    fun release() {
        track?.runCatching { pause(); flush(); release() }
        track = null
        written = 0
    }
}

@OptIn(ExperimentalReadiumApi::class)
class AiTtsPreferencesEditor(initial: AiTtsPreferences) : PreferencesEditor<AiTtsPreferences> {
    override var preferences: AiTtsPreferences = initial
        private set

    override fun clear() {
        preferences = AiTtsPreferences()
    }
}

@OptIn(ExperimentalReadiumApi::class)
class AiTtsEngineProvider(
    private val manager: VoiceManager,
    private val converter: ChineseConverter,
) : TtsEngineProvider<AiTtsSettings, AiTtsPreferences, AiTtsPreferencesEditor, AiTtsError, AiVoice> {

    /** The engine created last, so the reader can feed it its position for the lookahead. */
    var engine: AiTtsEngine? = null
        private set

    override suspend fun createEngine(publication: Publication, initialPreferences: AiTtsPreferences): Try<AiTtsEngine, Error> {
        val engine = AiTtsEngine(manager, converter, publication, initialPreferences)
        this.engine = engine
        return try {
            engine.preload()
            Try.success(engine)
        } catch (e: Throwable) {
            engine.close()
            Try.failure(DebugError(e.message ?: "无法加载 AI 语音"))
        }
    }

    override fun createPreferencesEditor(publication: Publication, initialPreferences: AiTtsPreferences) =
        AiTtsPreferencesEditor(initialPreferences)

    override fun createEmptyPreferences() = AiTtsPreferences()

    override fun getPlaybackParameters(settings: AiTtsSettings) = PlaybackParameters(settings.speed.toFloat())

    override fun updatePlaybackParameters(previousPreferences: AiTtsPreferences, playbackParameters: PlaybackParameters) =
        previousPreferences.copy(speed = playbackParameters.speed.toDouble())

    override fun mapEngineError(error: AiTtsError) =
        PlaybackException(error.message, null, PlaybackException.ERROR_CODE_UNSPECIFIED)
}
