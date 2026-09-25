package com.qshlab.shelf.data

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertersTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun epubEntries(b: EpubBuilder): Map<String, String> {
        val out = ByteArrayOutputStream()
        b.write(out)
        val map = LinkedHashMap<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                map[e.name] = String(z.readBytes())
            }
        }
        return map
    }

    // 1×1 PNG
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")

    @Test fun fb2() {
        val fb2 = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info><author><first-name>明</first-name><last-name>李</last-name></author>
<book-title>雪夜</book-title><lang>zh</lang><sequence name="四季" number="4"/>
<coverpage><image l:href="#cover.png"/></coverpage></title-info></description>
<body><title><p>雪夜</p></title>
<section><title><p>第一章 初雪</p></title><p>雪下了一整夜，<emphasis>很安静</emphasis>。</p><empty-line/><p>第二段。</p></section>
<section><title><p>第二章</p></title><p>天亮了。</p><image l:href="#cover.png"/></section>
</body>
<body name="notes"><section id="n1"><title><p>1</p></title><p>注释内容</p></section></body>
<binary id="cover.png" content-type="image/png">${Base64.getEncoder().encodeToString(png)}</binary>
</FictionBook>"""
        val b = Converters.convert("fb2", fb2.toByteArray(), "x.fb2")
        assertEquals("雪夜", b.title)
        assertEquals("明 李", b.author)
        assertEquals("四季", b.series)
        assertEquals(4.0, b.seriesIndex)
        assertEquals("images/img1.png", b.cover)
        assertEquals(listOf("封面", "第一章 初雪", "第二章", "1"), b.docs.map { it.title })
        assertTrue(b.docs[1].body.contains("<em>很安静</em>"))
        assertTrue(b.docs[2].body.contains("images/img1.png"))
        val files = epubEntries(b)
        assertTrue(files["OEBPS/content.opf"]!!.contains("belongs-to-collection"))
        // A zipped FB2 works too.
        assertEquals("雪夜", Converters.convert("fb2", zip("book.fb2" to fb2.toByteArray()), "x.fb2.zip").title)
    }

    @Test fun docx() {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val doc = """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="$w"><w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第一章</w:t></w:r></w:p>
<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>粗体</w:t></w:r><w:r><w:t xml:space="preserve"> 正文一</w:t></w:r></w:p>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第二章</w:t></w:r></w:p>
<w:p><w:r><w:t>正文二</w:t></w:r></w:p></w:body></w:document>"""
        val styles = """<?xml version="1.0"?><w:styles xmlns:w="$w"><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style></w:styles>"""
        val core = """<?xml version="1.0"?><cp:coreProperties xmlns:cp="x" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>我的文档</dc:title><dc:creator>作者甲</dc:creator></cp:coreProperties>"""
        val b = Converters.convert("docx", zip("word/document.xml" to doc.toByteArray(), "word/styles.xml" to styles.toByteArray(), "docProps/core.xml" to core.toByteArray()), "a.docx")
        assertEquals("我的文档", b.title)
        assertEquals("作者甲", b.author)
        assertEquals(listOf("第一章", "第二章"), b.docs.map { it.title })
        assertTrue(b.docs[0].body.contains("<strong>粗体</strong>"))
        assertEquals("zh", b.language)
    }

    @Test fun cbzOrderAndSizes() {
        val b = Converters.convert("cbz", zip("p10.png" to png, "p2.png" to png, "p1.png" to png, "__MACOSX/.x" to ByteArray(3)), "漫画 第3卷.cbz")
        assertTrue(b.fixedLayout)
        assertEquals(3, b.docs.size)
        assertEquals(listOf("images/p1.png", "images/p2.png", "images/p3.png"), b.resources.map { it.path })
        assertEquals(1, b.docs[0].width)
        assertEquals("漫画", b.series)
        assertEquals(3.0, b.seriesIndex)
        assertTrue(Converters.Cbz.naturalCompare("page2.jpg", "page10.jpg") < 0)
        assertEquals(1 to 1, ImageSize.of(png))
    }

    @Test fun html() {
        val html = """<html><head><meta charset="gbk"><title>网页小说</title><script>alert(1)</script></head><body><p>内容</p></body></html>"""
        val b = Converters.convert("html", html.toByteArray(charset("GBK")), "page.html")
        assertEquals("网页小说", b.title)
        assertTrue(b.docs.single().rawHtml)
        assertTrue(!b.docs.single().body.contains("alert"))
        assertTrue(b.docs.single().body.contains("<p>内容</p>"))
    }
}
