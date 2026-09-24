package com.qshlab.shelf.translate

import java.io.File
import java.security.MessageDigest

enum class TranslationMode(val label: String) { OFF("关闭"), BILINGUAL("双语对照"), TRANSLATED("仅译文") }

/**
 * Translates the paragraphs and headings of one (X)HTML chapter. Results are cached on disk per
 * book + chapter + mode, so each chapter is translated once.
 */
class ChapterTranslator(
    private val cacheDir: File,
    private val from: String,
    private val to: String,
    private val mode: TranslationMode,
) {
    // Block elements whose text is translated as a unit. Nested blocks are rare in books.
    private val block = Regex("<(p|h[1-6]|li|blockquote|dd|dt)(\\s[^>]*)?>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val tag = Regex("<[^>]+>")

    private fun cacheFile(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest("$key|$from|$to|$mode|v1".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$hash.html")
    }

    private fun decodeEntities(s: String) = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    suspend fun translate(key: String, html: String): String {
        val cached = cacheFile(key)
        if (cached.exists()) return cached.readText()

        val out = StringBuilder(html.length * 2)
        var last = 0
        for (m in block.findAll(html)) {
            out.append(html, last, m.range.first)
            last = m.range.last + 1
            val (name, attrs, inner) = m.destructured
            val plain = decodeEntities(inner.replace(tag, "")).trim()
            if (plain.isEmpty() || plain.none { it.isLetter() }) {
                out.append(m.value)
                continue
            }
            val translated = esc(Translation.translate(plain, from, to))
            when (mode) {
                TranslationMode.BILINGUAL -> {
                    out.append(m.value)
                    out.append("<$name$attrs class=\"shelf-translation\" style=\"opacity:0.72;font-size:0.94em;margin-top:0.2em\">$translated</$name>")
                }
                else -> out.append("<$name$attrs>$translated</$name>")
            }
        }
        out.append(html, last, html.length)
        val result = out.toString()
        runCatching {
            cacheDir.mkdirs()
            cached.writeText(result)
        }
        return result
    }
}
