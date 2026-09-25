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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
import org.readium.r2.shared.publication.Publication
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
 * Latency is hidden two ways: long sentences are split at commas so audio starts after the first
 * clause is synthesized, and an utterance reports "done" shortly *before* its audio finishes, so
 * Readium asks for the next sentence while the tail is still playing.
 */
@OptIn(ExperimentalReadiumApi::class)
class AiTtsEngine(
    private val manager: VoiceManager,
    private val converter: ChineseConverter,
    private val publicationLanguage: Language?,
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
        language = p.language ?: publicationLanguage,
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

    /** Loads the model for the book's language before the first sentence. */
    suspend fun preload() {
        val lang = baseLanguage(settings.value.language?.code)
        val (model, _) = pick(lang) ?: throw IllegalStateException(AiTtsError.NoVoice(lang).message)
        manager.load(model)
    }

    private var current: Job? = null
    private var currentId: TtsEngine.RequestId? = null

    /** Rolling estimate of how long the first clause of a sentence takes to synthesize. */
    private var firstChunkMs = 400.0

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        current?.cancel()
        currentId = requestId
        current = scope.launch {
            val lang = baseLanguage(language?.code) ?: baseLanguage(settings.value.language?.code)
            val voice = pick(lang) ?: pick(baseLanguage(settings.value.language?.code))
            if (voice == null) {
                notify { onError(requestId, AiTtsError.NoVoice(lang)) }
                return@launch
            }
            val (model, sid) = voice
            try {
                val tts = manager.load(model)
                val spoken = if (lang == "zh") converter.toSimplified(text) else text
                val config = GenerationConfig(sid = sid, speed = settings.value.speed.toFloat())
                val audio = Channel<FloatArray>(capacity = 2)
                launch(synthDispatcher) {
                    try {
                        splitForSpeech(spoken).forEachIndexed { i, chunk ->
                            val started = System.nanoTime()
                            val samples = tts.generateWithConfig(chunk, config).samples
                            val ms = (System.nanoTime() - started) / 1e6
                            if (i == 0) firstChunkMs = firstChunkMs * 0.7 + ms * 0.3
                            if (samples.isNotEmpty()) audio.send(samples)
                        }
                        audio.close()
                    } catch (e: Throwable) {
                        audio.close(e)
                    }
                }
                notify { onStart(requestId) }
                withContext(Dispatchers.IO) {
                    player.prepare(tts.sampleRate())
                    for (samples in audio) player.write(samples)
                }
                // Ask for the next sentence while the end of this one is still playing.
                val leadMs = (firstChunkMs + 150).coerceIn(200.0, 1500.0)
                player.awaitPendingBelow((leadMs / 1000 * tts.sampleRate()).toLong())
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
        scope.cancel()
        synthDispatcher.close()
        player.release()
    }

    private fun notify(block: TtsEngine.Listener<AiTtsError>.() -> Unit) {
        scope.launch(Dispatchers.Main) { listener?.block() }
    }

    companion object {
        private val CLAUSE_END = setOf('，', ',', '；', ';', '：', ':', '、', '—')

        /** Splits a sentence at clause punctuation into pieces worth synthesizing separately. */
        fun splitForSpeech(text: String): List<String> {
            val cjk = text.count { it.code in 0x3000..0x9fff }
            val minLen = if (cjk * 2 > text.length) 8 else 40
            if (text.length <= minLen * 2) return listOf(text)
            val pieces = mutableListOf<String>()
            val buf = StringBuilder()
            for (c in text) {
                buf.append(c)
                if (c in CLAUSE_END && buf.length >= minLen) {
                    pieces += buf.toString()
                    buf.clear()
                }
            }
            if (buf.isNotBlank()) {
                if (pieces.isNotEmpty() && buf.length < minLen / 2) pieces[pieces.lastIndex] += buf else pieces += buf.toString()
            }
            return pieces
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

    override suspend fun createEngine(publication: Publication, initialPreferences: AiTtsPreferences): Try<AiTtsEngine, Error> {
        val engine = AiTtsEngine(manager, converter, publication.metadata.language, initialPreferences)
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
