package com.qshlab.shelf.tts

enum class ModelKind { KOKORO, VITS }

data class Speaker(val sid: Int, val name: String, val language: String)

/** An on-device neural voice model (sherpa-onnx), downloaded on demand. */
data class VoiceModel(
    val id: String,
    val title: String,
    val description: String,
    /** Base language codes it can read: "zh", "en". */
    val languages: Set<String>,
    val downloadMb: Int,
    val kind: ModelKind,
    val speakers: List<Speaker>,
    /** Default speaker per language. */
    val defaults: Map<String, Int>,
    /** Model file inside the unpacked folder. */
    val modelFile: String,
    val license: String,
    /** Hugging Face mirror with the files unpacked (csukuangfj/<id>): no slow bzip2 step on the phone. */
    val huggingFace: Boolean = false,
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

    private fun kokoroLabel(name: String): String {
        val (prefix, rest) = name.split('_', limit = 2)
        return when (prefix) {
            "zf" -> "女声 $rest"
            "zm" -> "男声 $rest"
            "af" -> "美式女声 ${rest.replaceFirstChar { it.uppercase() }}"
            else -> "英式女声 ${rest.replaceFirstChar { it.uppercase() }}"
        }
    }

    val models = listOf(
        VoiceModel(
            id = "kokoro-int8-multi-lang-v1_1",
            title = "Kokoro 中英文（推荐）",
            description = "最自然的声音，55 个女声 + 45 个男声，也能读英文。需要较新的手机。",
            languages = setOf("zh", "en"),
            downloadMb = 195,
            kind = ModelKind.KOKORO,
            speakers = kokoroNames.mapIndexed { i, n -> Speaker(i, kokoroLabel(n), if (n.startsWith("z")) "zh" else "en") },
            defaults = mapOf("zh" to 3, "en" to 0),
            modelFile = "model.int8.onnx",
            license = "Apache-2.0（hexgrad/Kokoro-82M-v1.1-zh）",
            huggingFace = true,
        ),
        VoiceModel(
            id = "vits-piper-zh_CN-xiao_ya-medium-int8",
            title = "小雅 中文（轻量）",
            description = "体积小、速度快，适合旧手机。一个女声。",
            languages = setOf("zh"),
            downloadMb = 14,
            kind = ModelKind.VITS,
            speakers = listOf(Speaker(0, "小雅", "zh")),
            defaults = mapOf("zh" to 0),
            modelFile = "zh_CN-xiao_ya-medium.onnx",
            license = "Piper 模型；训练数据（标贝 BZNSYP）仅限非商业使用",
        ),
        VoiceModel(
            id = "vits-piper-en_US-amy-medium-int8",
            title = "Amy 英文（轻量）",
            description = "体积小、速度快的美式英语女声。",
            languages = setOf("en"),
            downloadMb = 21,
            kind = ModelKind.VITS,
            speakers = listOf(Speaker(0, "Amy", "en")),
            defaults = mapOf("en" to 0),
            modelFile = "en_US-amy-medium.onnx",
            license = "Piper 模型（MIT / 公开数据集）",
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
