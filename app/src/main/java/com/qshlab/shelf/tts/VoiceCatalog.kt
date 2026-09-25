package com.qshlab.shelf.tts

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.qshlab.shelf.R

enum class ModelKind { KOKORO, VITS, MATCHA }

/** [name] is a string resource; when [nameArg] is set it is a pattern filled with it (e.g. "女声 %1$s"). */
data class Speaker(val sid: Int, @StringRes val name: Int, val language: String, val nameArg: String? = null)

fun Speaker.displayName(context: Context): String =
    if (nameArg == null) context.getString(name) else context.getString(name, nameArg)

@Composable
fun Speaker.displayName(): String =
    if (nameArg == null) stringResource(name) else stringResource(name, nameArg)

/** The model's name without the note in brackets, e.g. "Kokoro 中英文". */
fun VoiceModel.shortTitle(context: Context): String = context.getString(shortName)

/** An on-device neural voice model (sherpa-onnx), downloaded on demand. */
data class VoiceModel(
    val id: String,
    @StringRes val title: Int,
    /** [title] without the note in brackets. */
    @StringRes val shortName: Int,
    @StringRes val description: Int,
    /** Base language codes it can read: "zh", "en". */
    val languages: Set<String>,
    val downloadMb: Int,
    val kind: ModelKind,
    val speakers: List<Speaker>,
    /** Default speaker per language. */
    val defaults: Map<String, Int>,
    /** Model file inside the unpacked folder. */
    val modelFile: String,
    @StringRes val license: Int,
    /** Hugging Face mirror with the files unpacked (csukuangfj/<id>): no slow bzip2 step on the phone. */
    val huggingFace: Boolean = false,
    /** Files fetched separately, e.g. a vocoder: file name → URL. */
    val extraFiles: Map<String, String> = emptyMap(),
) {
    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$id.tar.bz2"

    fun speakersFor(language: String) = speakers.filter { it.language == language }
}

object VoiceCatalog {
    /** Kokoro v1.1-zh speaker order (sid = index): 2 American + 1 British English, then Chinese. */
    private val kokoroNames = listOf("af_maple", "af_sol", "bf_vale") +
        listOf(1, 2, 3, 4, 5, 6, 7, 8, 17, 18, 19, 21, 22, 23, 24, 26, 27, 28, 32, 36, 38, 39, 40, 42, 43, 44, 46, 47, 48, 49, 51, 59, 60, 67, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 83, 84, 85, 86, 87, 88, 90, 92, 93, 94, 99)
            .map { "zf_%03d".format(it) } +
        listOf(9, 10, 11, 12, 13, 14, 15, 16, 20, 25, 29, 30, 31, 33, 34, 35, 37, 41, 45, 50, 52, 53, 54, 55, 56, 57, 58, 61, 62, 63, 64, 65, 66, 68, 69, 80, 81, 82, 89, 91, 95, 96, 97, 98, 100)
            .map { "zm_%03d".format(it) }

    private fun kokoroSpeaker(sid: Int, name: String, language: String): Speaker {
        val (prefix, rest) = name.split('_', limit = 2)
        return when (prefix) {
            "zf" -> Speaker(sid, R.string.tts_speaker_female_n, language, rest)
            "zm" -> Speaker(sid, R.string.tts_speaker_male_n, language, rest)
            "af" -> Speaker(sid, R.string.tts_speaker_us_female, language, rest.replaceFirstChar { it.uppercase() })
            else -> Speaker(sid, R.string.tts_speaker_uk_female, language, rest.replaceFirstChar { it.uppercase() })
        }
    }

    val models = listOf(
        VoiceModel(
            id = "kokoro-multi-lang-v1_1",
            title = R.string.tts_kokoro_title,
            shortName = R.string.tts_kokoro_short,
            description = R.string.tts_kokoro_desc,
            languages = setOf("zh", "en"),
            downloadMb = 406,
            kind = ModelKind.KOKORO,
            speakers = kokoroNames.mapIndexed { i, n -> kokoroSpeaker(i, n, if (n.startsWith("z")) "zh" else "en") },
            defaults = mapOf("zh" to 3, "en" to 0),
            modelFile = "model.onnx",
            license = R.string.tts_kokoro_license,
            huggingFace = true,
        ),
        VoiceModel(
            id = "matcha-icefall-zh-baker",
            title = R.string.tts_baker_title,
            shortName = R.string.tts_baker_short,
            description = R.string.tts_baker_desc,
            languages = setOf("zh"),
            downloadMb = 131,
            kind = ModelKind.MATCHA,
            speakers = listOf(Speaker(0, R.string.tts_speaker_baker, "zh")),
            defaults = mapOf("zh" to 0),
            modelFile = "model-steps-3.onnx",
            license = R.string.tts_baker_license,
            huggingFace = true,
            extraFiles = mapOf("vocos-22khz-univ.onnx" to "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos-22khz-univ.onnx"),
        ),
        VoiceModel(
            id = "vits-piper-en_US-amy-medium-int8",
            title = R.string.tts_amy_title,
            shortName = R.string.tts_amy_short,
            description = R.string.tts_amy_desc,
            languages = setOf("en"),
            downloadMb = 21,
            kind = ModelKind.VITS,
            speakers = listOf(Speaker(0, R.string.tts_speaker_amy, "en")),
            defaults = mapOf("en" to 0),
            modelFile = "en_US-amy-medium.onnx",
            license = R.string.tts_amy_license,
        ),
    )

    fun get(id: String) = models.firstOrNull { it.id == id }
}

/** A chosen AI voice, stored in settings as "ai:<modelId>:<sid>". */
data class AiVoiceId(val modelId: String, val sid: Int) {
    override fun toString() = "ai:$modelId:$sid"

    companion object {
        fun parse(s: String?): AiVoiceId? {
            if (s == null || !s.startsWith("ai:")) return null
            val parts = s.split(':')
            if (parts.size != 3) return null
            return parts[2].toIntOrNull()?.let { AiVoiceId(parts[1], it) }
        }
    }
}
