package com.qshlab.shelf.data

import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a minimal EPUB 3 from converted content (FB2, DOCX, HTML, comics), so the reader only
 * ever deals with EPUB.
 */
class EpubBuilder(val title: String, val author: String?, val language: String) {
    var series: String? = null
    var seriesIndex: Double? = null
    /** Comics: one image per page, laid out as fixed pages. */
    var fixedLayout = false
    /** Path (under OEBPS/) of the cover image. */
    var cover: String? = null

    /**
     * One reading-order document. [body] is XHTML body content (or, with [rawHtml], a complete HTML
     * document kept as is). [width]/[height] set the page size for fixed layout.
     */
    class Doc(val title: String, val body: String, val rawHtml: Boolean = false, val width: Int? = null, val height: Int? = null, val inToc: Boolean = true)

    class Res(val path: String, val mediaType: String, val bytes: ByteArray)

    val docs = mutableListOf<Doc>()
    val resources = mutableListOf<Res>()

    fun write(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype)
            zip.closeEntry()

            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            )
            val manifest = StringBuilder()
            val spine = StringBuilder()
            val nav = StringBuilder()
            docs.forEachIndexed { i, doc ->
                val id = "d${i + 1}"
                val href = if (doc.rawHtml) "$id.html" else "$id.xhtml"
                val content = if (doc.rawHtml) doc.body else {
                    val viewport = if (doc.width != null && doc.height != null) """<meta name="viewport" content="width=${doc.width}, height=${doc.height}"/>""" else ""
                    val style = if (fixedLayout) "<style>html,body{margin:0;padding:0}img{display:block;width:100%;height:100%;object-fit:contain}</style>" else ""
                    """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$language"><head><title>${esc(doc.title)}</title>$viewport$style</head><body>${doc.body}</body></html>"""
                }
                put("OEBPS/$href", content.toByteArray())
                manifest.append("""<item id="$id" href="$href" media-type="${if (doc.rawHtml) "text/html" else "application/xhtml+xml"}"/>""")
                spine.append("""<itemref idref="$id"/>""")
                if (doc.inToc) nav.append("""<li><a href="$href">${esc(doc.title)}</a></li>""")
            }
            resources.forEachIndexed { i, r ->
                put("OEBPS/${r.path}", r.bytes)
                val props = if (r.path == cover) """ properties="cover-image"""" else ""
                manifest.append("""<item id="r${i + 1}" href="${esc(r.path)}" media-type="${r.mediaType}"$props/>""")
            }
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol>$nav</ol></nav></body></html>""".toByteArray(),
            )
            val meta = StringBuilder()
            author?.let { meta.append("<dc:creator>${esc(it)}</dc:creator>") }
            series?.let {
                meta.append("""<meta property="belongs-to-collection" id="series">${esc(it)}</meta><meta refines="#series" property="collection-type">series</meta>""")
                seriesIndex?.let { n -> meta.append("""<meta refines="#series" property="group-position">${formatNumber(n)}</meta>""") }
            }
            if (fixedLayout) meta.append("""<meta property="rendition:layout">pre-paginated</meta><meta property="rendition:spread">none</meta>""")
            cover?.let { c -> resources.indexOfFirst { it.path == c }.takeIf { it >= 0 }?.let { meta.append("""<meta name="cover" content="r${it + 1}"/>""") } }
            put(
                "OEBPS/content.opf",
                ("""<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""" +
                    """<dc:identifier id="uid">urn:uuid:${UUID.randomUUID()}</dc:identifier><dc:title>${esc(title)}</dc:title><dc:language>$language</dc:language>""" +
                    """<meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>$meta</metadata>""" +
                    """<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>""").toByteArray(),
            )
        }
    }

    companion object {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun formatNumber(n: Double) = if (n == Math.floor(n)) n.toLong().toString() else n.toString()

        /** "zh" if the text is mostly Chinese / Japanese characters, else "en". */
        fun guessLanguage(sample: String): String {
            val letters = sample.count { it.isLetter() }
            if (letters == 0) return "zh"
            val kana = sample.count { it.code in 0x3040..0x30ff }
            val cjk = sample.count { it.code in 0x4e00..0x9fff }
            return when {
                kana * 20 > letters -> "ja"
                cjk * 3 > letters -> "zh"
                else -> "en"
            }
        }

        fun mediaTypeOf(name: String): String? = when (name.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            else -> null
        }
    }
}
