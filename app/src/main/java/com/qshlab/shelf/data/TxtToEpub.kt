package com.qshlab.shelf.data

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Turns a plain-text novel into an EPUB so it gets the same reader, TOC, 繁→简 and progress as
 * any other book. Handles UTF-8/16 and the common Chinese legacy encodings (GB18030/GBK, Big5).
 */
object TxtToEpub {
    data class Chapter(val title: String, val lines: List<String>)

    data class Result(val title: String, val author: String?, val charset: String, val chapters: List<Chapter>, val language: String = "zh")

    // Frequent characters in each script: whichever decoding produces more of them wins.
    private const val COMMON_SIMPLIFIED = "的一是不了人我在有他这中大来上个国到说们为子和你地出道也时年得就那要下以生会自着去之过家学对可她里后小么心多天而能好都然没日于起还发成事只作当想看文无开手十用"
    private const val COMMON_TRADITIONAL = "的一是不了人我在有他這中大來上個國到說們為子和你地出道也時年得就那要下以生會自著去之過家學對可她裡後小麼心多天而能好都然沒日於起還發成事只作當想看文無開手十用"

    fun decode(bytes: ByteArray): Pair<String, String> {
        // Byte order marks first.
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to "UTF-16BE"
        strict(bytes, Charsets.UTF_8)?.let { return it to "UTF-8" }

        // Legacy Chinese: decode a sample both ways and keep the one that reads like Chinese.
        val sample = bytes.copyOf(minOf(bytes.size, 64 * 1024))
        val candidates = listOf("GB18030", "Big5").filter { Charset.isSupported(it) }
        val best = candidates.maxByOrNull { name ->
            val text = String(sample, Charset.forName(name))
            val pool = if (name == "Big5") COMMON_TRADITIONAL else COMMON_SIMPLIFIED
            text.count { it in pool } - 3 * text.count { it == '�' }
        } ?: "GB18030"
        return String(bytes, Charset.forName(best)) to best
    }

    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        null
    }

    // \s doesn't cover the full-width space (U+3000) that Chinese TXT files use a lot.
    private const val SP = "[\\s\u3000]"
    internal val CHAPTER = Regex(
        "^$SP*(" +
            "第[0-9０-９零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[章节節回卷集部篇幕话話]($SP.*|[:：、.．].*|)" +
            "|序[章言幕]?($SP.*|[:：].*|)|楔子.*|引子.*|前言|尾[声聲].*|后记|後記|番外.*" +
            "|chapter\\s+[0-9ivxlc]+\\b.*|prologue|epilogue" +
            ")$SP*$",
        RegexOption.IGNORE_CASE,
    )
    private val AUTHOR = Regex("^\\s*(作者|作\\s+者|著)[:：\\s]\\s*(.{1,30}?)\\s*$")

    fun parse(text: String, fileName: String): Result {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trimEnd() }
        val author = lines.take(40).firstNotNullOfOrNull { AUTHOR.matchEntire(it)?.groupValues?.get(2) }?.trim()
        val title = fileName.substringBeforeLast('.').replace(Regex("[《》]"), "").trim()

        val language = EpubBuilder.guessLanguage(text.take(5000))
        val labels = ContentLabels.of(language)
        val chapters = mutableListOf<Chapter>()
        var currentTitle = labels.frontMatter
        var current = mutableListOf<String>()
        fun flush() {
            val body = current.filter { it.isNotBlank() }
            if (body.isNotEmpty() || chapters.isNotEmpty() || currentTitle != labels.frontMatter) chapters += Chapter(currentTitle, body)
            current = mutableListOf()
        }
        for (line in lines) {
            val t = line.trim()
            if (t.length in 1..40 && CHAPTER.matches(t)) {
                flush()
                currentTitle = t
            } else {
                current += t
            }
        }
        flush()
        // A leading front-matter chunk with nothing but blank lines is dropped by flush(); an intro stays.

        val real = chapters.count { it.title != labels.frontMatter }
        val result = if (real >= 2) chapters else splitBySize(lines.map { it.trim() }.filter { it.isNotBlank() }, labels)
        return Result(title, author, "", result, language)
    }

    /** No chapter headings found: cut into parts of ~6000 characters at paragraph boundaries. */
    private fun splitBySize(paragraphs: List<String>, labels: ContentLabels): List<Chapter> {
        val parts = mutableListOf<Chapter>()
        var buf = mutableListOf<String>()
        var size = 0
        for (p in paragraphs) {
            buf += p
            size += p.length
            if (size >= 6000) {
                parts += Chapter(labels.part(parts.size + 1), buf)
                buf = mutableListOf()
                size = 0
            }
        }
        if (buf.isNotEmpty() || parts.isEmpty()) parts += Chapter(labels.part(parts.size + 1), buf)
        return parts
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun convert(source: File, fileName: String, dest: File): Result {
        val (text, charset) = decode(source.readBytes())
        val parsed = parse(text, fileName).copy(charset = charset)
        dest.outputStream().use { write(parsed, it) }
        return parsed
    }

    fun write(book: Result, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            // mimetype must be first and stored uncompressed.
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype)
            zip.closeEntry()

            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            val manifest = StringBuilder()
            val spine = StringBuilder()
            val nav = StringBuilder()
            book.chapters.forEachIndexed { i, ch ->
                val id = "c${i + 1}"
                val body = ch.lines.filter { it.isNotBlank() }.joinToString("") { "<p>${esc(it)}</p>" }
                put(
                    "OEBPS/$id.xhtml",
                    """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>${esc(ch.title)}</title></head><body><h2>${esc(ch.title)}</h2>$body</body></html>""",
                )
                manifest.append("""<item id="$id" href="$id.xhtml" media-type="application/xhtml+xml"/>""")
                spine.append("""<itemref idref="$id"/>""")
                nav.append("""<li><a href="$id.xhtml">${esc(ch.title)}</a></li>""")
            }
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${ContentLabels.of(book.language).toc}</title></head><body><nav epub:type="toc"><ol>$nav</ol></nav></body></html>""",
            )
            val authorTag = book.author?.let { "<dc:creator>${esc(it)}</dc:creator>" } ?: ""
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:${UUID.randomUUID()}</dc:identifier><dc:title>${esc(book.title)}</dc:title>$authorTag<dc:language>${book.language}</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>""",
            )
        }
    }
}
