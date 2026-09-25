package com.qshlab.shelf.data

import com.qshlab.shelf.data.EpubBuilder.Companion.esc
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Formats converted to EPUB at import, keyed by extension. */
object Converters {
    /** Extensions handled by [convert] (TXT and EPUB / PDF are handled elsewhere). */
    val EXTENSIONS = setOf("fb2", "docx", "html", "htm", "xhtml", "cbz")

    /** "book.fb2.zip" counts as fb2. */
    fun extensionOf(name: String): String {
        val lower = name.lowercase()
        return if (lower.endsWith(".fb2.zip")) "fb2" else lower.substringAfterLast('.', "")
    }

    fun convert(ext: String, bytes: ByteArray, fileName: String): EpubBuilder = when (ext) {
        "fb2" -> Fb2.convert(if (isZip(bytes)) firstEntry(bytes, ".fb2") else bytes, fileName)
        "docx" -> Docx.convert(bytes, fileName)
        "html", "htm", "xhtml" -> Html.convert(bytes, fileName)
        "cbz" -> Cbz.convert(bytes, fileName)
        else -> throw IllegalArgumentException("不支持 .$ext")
    }

    fun convert(ext: String, source: File, fileName: String, dest: File) {
        val builder = convert(ext, source.readBytes(), fileName)
        check(builder.docs.isNotEmpty()) { "没有内容" }
        dest.outputStream().use { builder.write(it) }
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = zip.readBytes()
            }
        }
        return out
    }

    private fun firstEntry(bytes: ByteArray, suffix: String): ByteArray =
        zipEntries(bytes).entries.firstOrNull { it.key.lowercase().endsWith(suffix) }?.value ?: throw IllegalArgumentException("压缩包里没有 $suffix")

    /** DOM parsing with external entities off (untrusted files). */
    private fun parseXml(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        f.isExpandEntityReferences = false
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun Element.children(tag: String? = null): List<Element> {
        val out = mutableListOf<Element>()
        var n = firstChild
        while (n != null) {
            if (n is Element && (tag == null || n.localTag() == tag)) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun Element.child(tag: String) = children(tag).firstOrNull()

    /** Tag without a namespace prefix ("l:image" → "image"). */
    private fun Element.localTag() = tagName.substringAfter(':')

    private fun Element.hrefAttr(): String? {
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.nodeName.substringAfter(':') == "href") return a.nodeValue
        }
        return null
    }

    private fun baseTitle(fileName: String) = fileName.substringBeforeLast('.').removeSuffix(".fb2")

    // ---------------------------------------------------------------- FB2

    /** FictionBook 2: sections become chapters; binaries become images; <sequence> becomes the series. */
    object Fb2 {
        fun convert(bytes: ByteArray, fileName: String): EpubBuilder {
            val root = parseXml(bytes).documentElement
            val info = root.child("description")?.child("title-info")
            val title = info?.child("book-title")?.textContent?.trim()?.ifEmpty { null } ?: baseTitle(fileName)
            val authors = info?.children("author").orEmpty().map { a ->
                listOf("first-name", "middle-name", "last-name").mapNotNull { a.child(it)?.textContent?.trim()?.ifEmpty { null } }
                    .joinToString(" ").ifEmpty { a.child("nickname")?.textContent?.trim().orEmpty() }
            }.filter { it.isNotBlank() }
            val bodies = root.children("body")
            val sample = bodies.firstOrNull()?.textContent?.take(5000).orEmpty()
            val lang = info?.child("lang")?.textContent?.trim()?.ifEmpty { null } ?: EpubBuilder.guessLanguage(sample)
            val book = EpubBuilder(title, authors.joinToString("、").ifEmpty { null }, lang)
            info?.child("sequence")?.let { s ->
                book.series = s.getAttribute("name").ifBlank { null }
                book.seriesIndex = s.getAttribute("number").toDoubleOrNull()
            }

            val images = mutableMapOf<String, String>()
            for (bin in root.children("binary")) {
                val id = bin.getAttribute("id")
                val type = bin.getAttribute("content-type").ifBlank { EpubBuilder.mediaTypeOf(id) ?: "image/jpeg" }
                val data = runCatching { Base64.getMimeDecoder().decode(bin.textContent.trim()) }.getOrNull() ?: continue
                val ext = when (type) { "image/png" -> "png"; "image/gif" -> "gif"; else -> "jpg" }
                val path = "images/img${images.size + 1}.$ext"
                images[id] = path
                book.resources += EpubBuilder.Res(path, type, data)
            }
            info?.child("coverpage")?.child("image")?.hrefAttr()?.removePrefix("#")?.let { images[it] }?.let { book.cover = it }
            val labels = ContentLabels.of(lang)
            book.cover?.let { book.docs += EpubBuilder.Doc(labels.cover, """<div style="text-align:center"><img src="$it" alt="" style="max-width:100%;max-height:95vh"/></div>""", inToc = false) }

            for (body in bodies) {
                val notes = body.getAttribute("name") == "notes"
                val sections = body.children("section")
                if (sections.isEmpty()) {
                    val html = render(body, images, 2)
                    if (html.isNotBlank()) book.docs += EpubBuilder.Doc(if (notes) labels.notes else title, html)
                    continue
                }
                // Text before the first section (epigraphs, the book title) goes with it.
                val intro = body.children().takeWhile { it.localTag() != "section" }.joinToString("") { renderNode(it, images, 2) }
                sections.forEachIndexed { i, sec ->
                    val secTitle = sec.child("title")?.let(::plain)?.ifBlank { null } ?: if (notes) labels.notes else labels.part(i + 1)
                    val html = (if (i == 0 && !notes) intro else "") + render(sec, images, 2)
                    book.docs += EpubBuilder.Doc(secTitle, html)
                }
            }
            return book
        }

        private fun plain(e: Element) = e.textContent.replace(Regex("\\s+"), " ").trim()

        private fun render(e: Element, images: Map<String, String>, level: Int): String =
            e.children().joinToString("") { renderNode(it, images, level) }

        private fun renderNode(e: Element, images: Map<String, String>, level: Int): String = when (e.localTag()) {
            "title" -> "<h$level>${e.children().joinToString("<br/>") { inline(it, images) }.ifBlank { esc(plain(e)) }}</h$level>"
            "section" -> render(e, images, minOf(level + 1, 6))
            "p" -> "<p>${inline(e, images)}</p>"
            "subtitle" -> "<h${minOf(level + 1, 6)}>${inline(e, images)}</h${minOf(level + 1, 6)}>"
            "empty-line" -> "<p>&#160;</p>"
            "image" -> e.hrefAttr()?.removePrefix("#")?.let { images[it] }?.let { """<div style="text-align:center"><img src="$it" alt="" style="max-width:100%"/></div>""" }.orEmpty()
            "poem", "stanza" -> "<div style=\"margin:1em 2em\">${render(e, images, level)}</div>"
            "v" -> "<p style=\"text-indent:0\">${inline(e, images)}</p>"
            "epigraph", "cite" -> "<blockquote>${render(e, images, level)}</blockquote>"
            "text-author" -> "<p style=\"text-align:right\">${inline(e, images)}</p>"
            "table" -> "<table>${e.children("tr").joinToString("") { tr -> "<tr>" + tr.children().joinToString("") { "<td>${inline(it, images)}</td>" } + "</tr>" }}</table>"
            else -> render(e, images, level)
        }

        private fun inline(e: Element, images: Map<String, String>): String {
            val sb = StringBuilder()
            var n = e.firstChild
            while (n != null) {
                when {
                    n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE -> sb.append(esc(n.nodeValue))
                    n is Element -> when (n.localTag()) {
                        "emphasis" -> sb.append("<em>").append(inline(n, images)).append("</em>")
                        "strong" -> sb.append("<strong>").append(inline(n, images)).append("</strong>")
                        "strikethrough" -> sb.append("<del>").append(inline(n, images)).append("</del>")
                        "sup" -> sb.append("<sup>").append(inline(n, images)).append("</sup>")
                        "sub" -> sb.append("<sub>").append(inline(n, images)).append("</sub>")
                        "image" -> n.hrefAttr()?.removePrefix("#")?.let { images[it] }?.let { sb.append("""<img src="$it" alt=""/>""") }
                        "a" -> sb.append(if (n.getAttribute("type") == "note") "<sup>${inline(n, images)}</sup>" else inline(n, images))
                        else -> sb.append(inline(n, images))
                    }
                }
                n = n.nextSibling
            }
            return sb.toString()
        }
    }

    // ---------------------------------------------------------------- DOCX

    /** Word documents: paragraphs, bold / italic, images; chapters start at the top heading level. */
    object Docx {
        private class Para(val level: Int, val html: String, val text: String)

        fun convert(bytes: ByteArray, fileName: String): EpubBuilder {
            val files = zipEntries(bytes)
            val doc = parseXml(files["word/document.xml"] ?: throw IllegalArgumentException("不是 Word 文档")).documentElement
            val styleLevels = files["word/styles.xml"]?.let { headingStyles(parseXml(it).documentElement) }.orEmpty()
            val rels = files["word/_rels/document.xml.rels"]?.let { parseXml(it).documentElement.children("Relationship") }.orEmpty()
                .associate { it.getAttribute("Id") to it.getAttribute("Target") }
            val core = files["docProps/core.xml"]?.let { parseXml(it).documentElement }
            val title = core?.children()?.firstOrNull { it.localTag() == "title" }?.textContent?.trim()?.ifEmpty { null } ?: baseTitle(fileName)
            val author = core?.children()?.firstOrNull { it.localTag() == "creator" }?.textContent?.trim()?.ifEmpty { null }

            val images = mutableMapOf<String, String>()
            val book = EpubBuilder(title, author, "zh")
            fun image(relId: String): String? {
                images[relId]?.let { return it }
                val target = rels[relId] ?: return null
                val data = files["word/" + target.removePrefix("/word/").removePrefix("./")] ?: files[target.removePrefix("/")] ?: return null
                val type = EpubBuilder.mediaTypeOf(target) ?: return null
                val path = "images/img${images.size + 1}.${target.substringAfterLast('.')}"
                book.resources += EpubBuilder.Res(path, type, data)
                images[relId] = path
                return path
            }

            val paras = mutableListOf<Para>()
            val body = doc.child("body") ?: throw IllegalArgumentException("空文档")
            fun paragraph(p: Element): Para {
                val style = p.child("pPr")?.child("pStyle")?.getAttribute("w:val")
                val outline = p.child("pPr")?.child("outlineLvl")?.getAttribute("w:val")?.toIntOrNull()?.plus(1)
                val level = styleLevels[style] ?: outline ?: 0
                val html = StringBuilder()
                val text = StringBuilder()
                fun runs(parent: Element) {
                    for (r in parent.children()) when (r.localTag()) {
                        "r" -> {
                            val props = r.child("rPr")
                            val bold = props?.child("b")?.let { it.getAttribute("w:val") != "0" && it.getAttribute("w:val") != "false" } == true
                            val italic = props?.child("i")?.let { it.getAttribute("w:val") != "0" && it.getAttribute("w:val") != "false" } == true
                            val sb = StringBuilder()
                            for (c in r.children()) when (c.localTag()) {
                                "t" -> { sb.append(esc(c.textContent)); text.append(c.textContent) }
                                "tab" -> sb.append("&#160;&#160;&#160;&#160;")
                                "br", "cr" -> sb.append("<br/>")
                                "drawing", "pict" -> {
                                    val blip = c.getElementsByTagName("a:blip").item(0) as? Element
                                    val id = blip?.getAttribute("r:embed")?.ifEmpty { null }
                                        ?: (c.getElementsByTagName("v:imagedata").item(0) as? Element)?.getAttribute("r:id")
                                    id?.let(::image)?.let { sb.append("""<img src="$it" alt="" style="max-width:100%"/>""") }
                                }
                            }
                            var s = sb.toString()
                            if (italic) s = "<em>$s</em>"
                            if (bold && level == 0) s = "<strong>$s</strong>"
                            html.append(s)
                        }
                        "hyperlink", "smartTag", "ins", "fldSimple" -> runs(r)
                    }
                }
                runs(p)
                return Para(level, html.toString(), text.toString())
            }
            for (block in body.children()) when (block.localTag()) {
                "p" -> paras += paragraph(block)
                "tbl" -> for (row in block.getElementsByTagName("w:tr").let { l -> (0 until l.length).map { l.item(it) as Element } }) {
                    val cells = row.getElementsByTagName("w:p").let { l -> (0 until l.length).map { paragraph(l.item(it) as Element) } }
                    paras += Para(0, cells.joinToString("　") { it.html }, cells.joinToString(" ") { it.text })
                }
                "sdt" -> block.getElementsByTagName("w:p").let { l -> (0 until l.length).forEach { paras += paragraph(l.item(it) as Element) } }
            }
            val lang = EpubBuilder.guessLanguage(paras.take(200).joinToString("") { it.text })
            book.docs += chapters(paras, title, ContentLabels.of(lang))
            return EpubBuilder(title, author, lang).also { b ->
                b.docs += book.docs
                b.resources += book.resources
            }
        }

        /** Style id → heading level, from names like "heading 1", "标题 1", "Title". */
        private fun headingStyles(styles: Element): Map<String, Int> = styles.children("style").mapNotNull { s ->
            val id = s.getAttribute("w:styleId")
            val name = s.child("name")?.getAttribute("w:val")?.lowercase().orEmpty()
            val level = when {
                name == "title" || name == "标题" -> 1
                else -> Regex("^(?:heading|标题)\\s*([1-6])$").find(name)?.groupValues?.get(1)?.toInt()
                    ?: s.child("pPr")?.child("outlineLvl")?.getAttribute("w:val")?.toIntOrNull()?.plus(1)
            }
            level?.let { id to it }
        }.toMap()

        private fun chapters(input: List<Para>, title: String, labels: ContentLabels): List<EpubBuilder.Doc> {
            // No heading styles: treat lines like "第三章 …" as chapter titles, as for TXT.
            val paras = if (input.any { it.level > 0 }) input else input.map { p ->
                if (p.text.length <= 40 && TxtToEpub.CHAPTER.matches(p.text.trim())) Para(1, p.html, p.text) else p
            }
            val top = paras.filter { it.level > 0 && it.text.isNotBlank() }.minOfOrNull { it.level }
            val docs = mutableListOf<EpubBuilder.Doc>()
            var chTitle = title
            val sb = StringBuilder()
            fun flush() {
                if (sb.isNotBlank()) docs += EpubBuilder.Doc(chTitle, sb.toString())
                sb.clear()
            }
            for (p in paras) {
                if (top != null && p.level == top && p.text.isNotBlank()) {
                    flush()
                    chTitle = p.text.trim().take(60)
                }
                when {
                    p.level > 0 && p.text.isNotBlank() -> sb.append("<h${minOf(p.level + 1, 6)}>${p.html}</h${minOf(p.level + 1, 6)}>")
                    p.html.isNotBlank() -> sb.append("<p>${p.html}</p>")
                }
                // Without headings, keep chapters to a readable size.
                if (top == null && sb.length > 60_000) { flush(); chTitle = labels.part(docs.size + 1) }
            }
            flush()
            return docs
        }
    }

    // ---------------------------------------------------------------- HTML

    /** A saved web page or HTML book, kept as HTML (browsers' parser is forgiving; XHTML is not). */
    object Html {
        fun convert(bytes: ByteArray, fileName: String): EpubBuilder {
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            val declared = Regex("charset=[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
            val text = declared?.let { runCatching { String(bytes, charset(it)) }.getOrNull() } ?: TxtToEpub.decode(bytes).first
            val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)
                ?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()?.ifEmpty { null } ?: baseTitle(fileName)
            // Scripts don't belong in a book; external stylesheets and images aren't there anyway.
            val cleaned = text.replace(Regex("<script\\b.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                .replace(Regex("<meta[^>]+charset[^>]*>", RegexOption.IGNORE_CASE), "")
            val headTag = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(cleaned)
            val html = if (headTag != null) {
                cleaned.substring(0, headTag.range.last + 1) + "<meta charset=\"utf-8\"/>" + cleaned.substring(headTag.range.last + 1)
            } else {
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><title>${esc(title)}</title></head><body>$cleaned</body></html>"
            }
            val plain = cleaned.replace(Regex("<[^>]+>"), " ").take(5000)
            return EpubBuilder(title, null, EpubBuilder.guessLanguage(plain)).apply { docs += EpubBuilder.Doc(title, html, rawHtml = true) }
        }
    }

    // ---------------------------------------------------------------- CBZ

    /** Comic archives: one fixed-size page per image, in natural file-name order. */
    object Cbz {
        fun convert(bytes: ByteArray, fileName: String): EpubBuilder {
            val pages = zipEntries(bytes).filter { (name, _) -> EpubBuilder.mediaTypeOf(name) != null && !name.substringAfterLast('/').startsWith(".") }
                .entries.sortedWith { a, b -> naturalCompare(a.key, b.key) }
            require(pages.isNotEmpty()) { "压缩包里没有图片" }
            val title = baseTitle(fileName)
            val book = EpubBuilder(title, null, "zh")
            book.fixedLayout = true
            SeriesDetector.guess(title)?.let { book.series = it.series; book.seriesIndex = it.index }
            pages.forEachIndexed { i, (name, data) ->
                val ext = name.substringAfterLast('.').lowercase()
                val path = "images/p${i + 1}.$ext"
                book.resources += EpubBuilder.Res(path, EpubBuilder.mediaTypeOf(name)!!, data)
                val (w, h) = ImageSize.of(data) ?: (1000 to 1500)
                book.docs += EpubBuilder.Doc(ContentLabels.of(book.language).page(i + 1), """<img src="$path" alt=""/>""", width = w, height = h, inToc = i == 0)
                if (i == 0) book.cover = path
            }
            return book
        }

        /** "p2.jpg" before "p10.jpg". */
        fun naturalCompare(a: String, b: String): Int {
            val re = Regex("\\d+|\\D+")
            val pa = re.findAll(a.lowercase()).map { it.value }.toList()
            val pb = re.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
                else x.compareTo(y)
                if (c != 0) return c
            }
            return pa.size.compareTo(pb.size)
        }
    }
}

/** Image width / height from the file header (PNG, JPEG, GIF, WebP), without decoding it. */
object ImageSize {
    fun of(b: ByteArray): Pair<Int, Int>? {
        fun u16be(i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
        fun u16le(i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
        fun u32be(i: Int) = (u16be(i) shl 16) or u16be(i + 2)
        if (b.size < 30) return null
        return when {
            b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> u32be(16) to u32be(20)
            b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> u16le(6) to u16le(8)
            b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() -> when (String(b, 12, 4, Charsets.ISO_8859_1)) {
                "VP8X" -> (1 + ((b[24].toInt() and 0xff) or ((b[25].toInt() and 0xff) shl 8) or ((b[26].toInt() and 0xff) shl 16))) to
                    (1 + ((b[27].toInt() and 0xff) or ((b[28].toInt() and 0xff) shl 8) or ((b[29].toInt() and 0xff) shl 16)))
                "VP8 " -> (u16le(26) and 0x3fff) to (u16le(28) and 0x3fff)
                "VP8L" -> {
                    val bits = (b[21].toInt() and 0xff) or ((b[22].toInt() and 0xff) shl 8) or ((b[23].toInt() and 0xff) shl 16) or ((b[24].toInt() and 0xff) shl 24)
                    ((bits and 0x3fff) + 1) to (((bits shr 14) and 0x3fff) + 1)
                }
                else -> null
            }
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> {
                var i = 2
                while (i + 9 < b.size) {
                    if (b[i] != 0xFF.toByte()) { i++; continue }
                    val marker = b[i + 1].toInt() and 0xff
                    if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) return u16be(i + 7) to u16be(i + 5)
                    i += 2 + u16be(i + 2)
                }
                null
            }
            else -> null
        }?.takeIf { (w, h) -> w > 0 && h > 0 }
    }
}
