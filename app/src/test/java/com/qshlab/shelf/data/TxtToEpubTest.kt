package com.qshlab.shelf.data

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtToEpubTest {
    private val novel = """
        书名：山海行记
        作者：林远

        简介：一段很长的路。

        第一章 启程
        清晨的雾还没有散开，山路像一条灰白的丝带。
        他背着旧布包，走得不快。

        第二章　过江
        第二天，他们在渡口等船。

        第十二章：归来
        她说，路走得远了，就会忘记为什么出发。
        番外 月下
        夜里风大。
    """.trimIndent()

    @Test fun detectsEncodings() {
        for (cs in listOf("UTF-8", "GB18030", "Big5")) {
            val text = if (cs == "Big5") "第一章 開始\n雲從山後慢慢升起來，這是一個很長的故事，我們在這裡等他回來。" else novel
            val (decoded, name) = TxtToEpub.decode(text.toByteArray(Charset.forName(cs)))
            assertEquals("encoding $cs", text, decoded)
            assertEquals(cs, name)
        }
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好".toByteArray()
        assertEquals("你好", TxtToEpub.decode(bom).first)
    }

    @Test fun splitsChaptersAndFindsAuthor() {
        val r = TxtToEpub.parse(novel, "《山海行记》.txt")
        assertEquals("山海行记", r.title)
        assertEquals("林远", r.author)
        assertEquals(listOf("卷首", "第一章 启程", "第二章　过江", "第十二章：归来", "番外 月下"), r.chapters.map { it.title })
        assertEquals(listOf("清晨的雾还没有散开，山路像一条灰白的丝带。", "他背着旧布包，走得不快。"), r.chapters[1].lines.filter { it.isNotBlank() })
    }

    @Test fun longProseLineIsNotAChapter() {
        val r = TxtToEpub.parse("第一章 A\n正文\n第二章 B\n第三天他们终于到了那座传说中的城，城门口站着很多人，每个人都在等待着什么，没有人说话。\n", "x.txt")
        assertEquals(2, r.chapters.size)
    }

    @Test fun noHeadingsSplitsBySize() {
        val text = (1..400).joinToString("\n") { "这是第${it}段，内容大约二十个字左右，用来测试分段。" }
        val r = TxtToEpub.parse(text, "无章节.txt")
        assertTrue(r.chapters.size >= 2)
        assertTrue(r.chapters.all { it.title.startsWith("第") && it.title.endsWith("部分") })
    }

    @Test fun writesValidEpubStructure() {
        val out = ByteArrayOutputStream()
        TxtToEpub.write(TxtToEpub.parse(novel, "山海行记.txt"), out)
        val names = mutableListOf<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { names += it.name } }
        assertEquals("mimetype", names.first())
        assertTrue(names.containsAll(listOf("META-INF/container.xml", "OEBPS/content.opf", "OEBPS/nav.xhtml", "OEBPS/c1.xhtml")))
    }
}
