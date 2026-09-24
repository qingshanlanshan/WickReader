package com.qshlab.shelf.chinese

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseConverterTest {
    private val conv = ChineseConverter.load { name -> File("src/main/assets/opencc/$name").inputStream() }

    @Test fun characters() {
        assertEquals("雲从山后慢慢升起来".replace("雲", "云"), conv.toSimplified("雲從山後慢慢升起來"))
        assertEquals("书里记载着一座城", conv.toSimplified("書裡記載著一座城"))
        assertEquals("灯也一盏一盏地熄灭了", conv.toSimplified("燈也一盞一盞地熄滅了"))
    }

    @Test fun phrasesBeatCharacters() {
        // 乾 alone could be 干 or 乾 (as in 乾隆); the phrase table keeps 乾隆.
        assertEquals("乾隆", conv.toSimplified("乾隆"))
        assertEquals("头发", conv.toSimplified("頭髮"))
    }

    @Test fun zhuVersusZhe() {
        assertEquals("他笑着看着我", conv.toSimplified("他笑著看著我"))
        assertEquals("着急", conv.toSimplified("著急"))
        assertEquals("这部著作很著名", conv.toSimplified("這部著作很著名"))
        assertEquals("原著和名著都很显著", conv.toSimplified("原著和名著都很顯著"))
    }

    @Test fun leavesSimplifiedAndLatinAlone() {
        val s = "这是简体中文 with English, 123。"
        assertEquals(s, conv.toSimplified(s))
    }

    @Test fun htmlKeepsMarkup() {
        val html = """<html><head><title>雲中書</title><style>p{font-family:"標楷體"}</style></head>""" +
            """<body class="書"><h2>第一回</h2><p>他翻開那本舊書<a href="c2.xhtml#書">註</a></p></body></html>"""
        val out = conv.htmlToSimplified(html)
        assertTrue(out.contains("<title>云中书</title>"))
        assertTrue("style content untouched", out.contains("""font-family:"標楷體""""))
        assertTrue("attributes untouched", out.contains("""class="書"""") && out.contains("""href="c2.xhtml#書""""))
        assertTrue(out.contains("<p>他翻开那本旧书"))
        assertTrue(out.contains(">注</a>"))
    }

    @Test fun detection() {
        assertTrue(conv.traditionalRatio("雲從山後慢慢升起來，把整個山谷蓋得嚴嚴實實，連鳥叫聲都變得遙遠。".repeat(4)) > 0.1)
        assertTrue(conv.traditionalRatio("云从山后慢慢升起来，把整个山谷盖得严严实实，连鸟叫声都变得遥远。".repeat(4)) < 0.02)
    }
}
