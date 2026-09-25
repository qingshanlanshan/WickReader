package com.qshlab.shelf.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

sealed interface DownloadState {
    data class Downloading(val fraction: Float) : DownloadState
    data object Unpacking : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * Downloads, unpacks and loads the neural voice models. Models live in filesDir/voices/<id>; a
 * model counts as installed once its ".ready" marker exists.
 */
class VoiceManager(private val context: Context, private val scope: CoroutineScope) {
    private val root = File(context.filesDir, "voices").apply { mkdirs() }

    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    init {
        // Leftovers of an interrupted download or unpack, and models no longer offered.
        val known = VoiceCatalog.models.map { it.id }.toSet()
        root.listFiles()?.filter { it.name.endsWith(".part") || it.name.endsWith(".tmp") || (it.isDirectory && it.name !in known) }
            ?.forEach { it.deleteRecursively() }
    }

    private fun scanInstalled(): Set<String> =
        VoiceCatalog.models.filter { File(dir(it.id), ".ready").exists() }.map { it.id }.toSet()

    fun dir(id: String) = File(root, id)

    fun sizeOnDisk(id: String): Long = dir(id).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Installed models that can read [language] ("zh", "en", …), best first. */
    fun modelsFor(language: String): List<VoiceModel> =
        VoiceCatalog.models.filter { language in it.languages && it.id in _installed.value }

    fun download(model: VoiceModel) {
        if (jobs[model.id]?.isActive == true) return
        jobs[model.id] = scope.launch(Dispatchers.IO) {
            val part = File(root, "${model.id}.tar.bz2.part")
            val tmp = File(root, "${model.id}.tmp")
            try {
                setState(model.id, DownloadState.Downloading(0f))
                tmp.deleteRecursively()
                val unpacked = model.huggingFace && runCatching {
                    fetchUnpacked(model, tmp) { setState(model.id, DownloadState.Downloading(it)) }
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.isSuccess
                if (!unpacked) {
                    tmp.deleteRecursively()
                    setState(model.id, DownloadState.Downloading(0f))
                    fetch(model.url, part) { setState(model.id, DownloadState.Downloading(it)) }
                    setState(model.id, DownloadState.Unpacking)
                    unpack(part, tmp)
                    part.delete()
                }
                for ((name, url) in model.extraFiles) {
                    setState(model.id, DownloadState.Downloading(0f))
                    fetch(url, File(tmp, name)) { setState(model.id, DownloadState.Downloading(it)) }
                }
                val dest = dir(model.id)
                dest.deleteRecursively()
                check(tmp.renameTo(dest)) { "无法保存语音文件" }
                File(dest, ".ready").writeText("1")
                _installed.update { it + model.id }
                setState(model.id, null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                part.delete(); tmp.deleteRecursively()
                setState(model.id, null)
                throw e
            } catch (e: Exception) {
                part.delete(); tmp.deleteRecursively()
                setState(model.id, DownloadState.Failed(e.message ?: "下载失败"))
            }
        }
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        loadedLock.withLock {
            if (loaded?.first == id) { loaded?.second?.release(); loaded = null }
        }
        _installed.update { it - id }
        dir(id).deleteRecursively()
    }

    private fun setState(id: String, state: DownloadState?) =
        _downloads.update { if (state == null) it - id else it + (id to state) }

    private suspend fun fetch(url: String, out: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "Shelf-Reader/0.2 (Android)")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("下载失败（HTTP ${conn.responseCode}）")
            val total = conn.contentLengthLong
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 512 * 1024) {
                            lastReport = done
                            onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IllegalStateException("下载中断，请重试")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads the model's files one by one from its Hugging Face mirror (4 at a time), leaving
     * out what the app doesn't use (jieba dict, British lexicon).
     */
    private suspend fun fetchUnpacked(model: VoiceModel, dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val repo = "https://huggingface.co/csukuangfj/${model.id}"
        val listing = URL("https://huggingface.co/api/models/csukuangfj/${model.id}/tree/main?recursive=true").readText()
        val files = org.json.JSONArray(listing).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            .filter { it.getString("type") == "file" }
            .map { it.getString("path") to it.optLong("size") }
            .filter { (path, _) -> !path.startsWith("dict/") && !path.startsWith(".") && path != "lexicon-gb-en.txt" && path != "README.md" }
        check(files.any { it.first == model.modelFile }) { "镜像缺少模型文件" }
        val total = files.sumOf { it.second }.coerceAtLeast(1)
        val done = java.util.concurrent.atomic.AtomicLong()
        val queue = java.util.concurrent.ConcurrentLinkedQueue(files)
        kotlinx.coroutines.coroutineScope {
            repeat(4) {
                launch {
                    while (true) {
                        val (path, size) = queue.poll() ?: break
                        val target = File(dest, path).apply { parentFile?.mkdirs() }
                        var last = 0L
                        fetch("$repo/resolve/main/$path", target) { f ->
                            val now = (f * size).toLong()
                            onProgress(done.addAndGet(now - last).toFloat() / total)
                            last = now
                        }
                        onProgress(done.addAndGet(size - last).toFloat() / total)
                        check(target.length() == size) { "文件不完整：$path" }
                    }
                }
            }
        }
    }

    /** Unpacks the .tar.bz2, dropping the archive's top-level folder. */
    private suspend fun unpack(archive: File, dest: File) = withContext(Dispatchers.IO) {
        dest.mkdirs()
        val canonicalDest = dest.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
            while (true) {
                ensureActive()
                val entry = tar.nextEntry ?: break
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) continue
                val target = File(dest, relative)
                if (!target.canonicalPath.startsWith(canonicalDest)) continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else if (entry.isFile) {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    // ---- Loaded engine (one model in memory at a time) ----

    private val loadedLock = Mutex()
    private var loaded: Pair<String, OfflineTts>? = null

    /** Loads [model] (a few seconds for Kokoro), reusing the one already in memory. */
    suspend fun load(model: VoiceModel): OfflineTts = loadedLock.withLock {
        loaded?.let { (id, tts) -> if (id == model.id) return@withLock tts }
        withContext(Dispatchers.IO) {
            loaded?.second?.release()
            loaded = null
            val started = System.currentTimeMillis()
            val tts = OfflineTts(config = config(model, dir(model.id)))
            android.util.Log.d("ShelfTts", "loaded ${model.id} in ${System.currentTimeMillis() - started} ms")
            loaded = model.id to tts
            tts
        }
    }

    private val previewPlayer = PcmPlayer()
    private var previewJob: Job? = null

    /** Plays a short sample of a voice (loading the model if needed). */
    fun preview(model: VoiceModel, sid: Int, onDone: (String?) -> Unit = {}) {
        previewJob?.cancel()
        previewPlayer.stop()
        previewJob = scope.launch(Dispatchers.IO) {
            val error = runCatching {
                val tts = load(model)
                val lang = model.speakers.firstOrNull { it.sid == sid }?.language ?: "zh"
                val text = if (lang == "zh") "你好，这是一段试听。窗外下起了小雨，我们一起读书吧。"
                else "Hello! This is a short sample. Shall we read a book together?"
                val audio = tts.generateWithConfig(text, com.k2fsa.sherpa.onnx.GenerationConfig(sid = sid))
                previewPlayer.prepare(audio.sampleRate)
                previewPlayer.write(audio.samples)
                previewPlayer.awaitPendingBelow(0)
            }.exceptionOrNull()
            if (error is kotlinx.coroutines.CancellationException) throw error
            withContext(Dispatchers.Main) { onDone(error?.let { it.message ?: "试听失败" }) }
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewPlayer.stop()
    }

    /** Frees the model's memory (it is reloaded on the next use). */
    fun release() {
        scope.launch {
            loadedLock.withLock {
                loaded?.second?.release()
                loaded = null
            }
        }
    }

    private fun config(model: VoiceModel, d: File): OfflineTtsConfig {
        val p = d.absolutePath
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        return when (model.kind) {
            ModelKind.KOKORO -> OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "$p/${model.modelFile}",
                        voices = "$p/voices.bin",
                        tokens = "$p/tokens.txt",
                        dataDir = "$p/espeak-ng-data",
                        lexicon = "$p/lexicon-us-en.txt,$p/lexicon-zh.txt",
                    ),
                    numThreads = threads,
                ),
                ruleFsts = "$p/phone-zh.fst,$p/date-zh.fst,$p/number-zh.fst",
            )
            ModelKind.MATCHA -> OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    matcha = com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig(
                        acousticModel = "$p/${model.modelFile}",
                        vocoder = "$p/vocos-22khz-univ.onnx",
                        lexicon = "$p/lexicon.txt",
                        tokens = "$p/tokens.txt",
                    ),
                    numThreads = threads,
                ),
                ruleFsts = listOf("phone.fst", "date.fst", "number.fst").map { File(d, it) }.filter { it.exists() }
                    .joinToString(",") { it.absolutePath },
            )
            ModelKind.VITS -> OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "$p/${model.modelFile}",
                        tokens = "$p/tokens.txt",
                        lexicon = File(d, "lexicon.txt").takeIf { it.exists() }?.absolutePath ?: "",
                        dataDir = File(d, "espeak-ng-data").takeIf { it.exists() }?.absolutePath ?: "",
                    ),
                    numThreads = threads,
                ),
                ruleFsts = listOf("phone.fst", "date.fst", "number.fst").map { File(d, it) }.filter { it.exists() }
                    .joinToString(",") { it.absolutePath },
            )
        }
    }
}
