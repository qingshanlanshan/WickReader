package com.qshlab.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesDetectorTest {
    private fun g(s: String) = SeriesDetector.guess(s)

    @Test fun chineseVolumes() {
        assertEquals(SeriesDetector.Guess("雲中書", 2.0), g("雲中書 第二卷"))
        assertEquals(SeriesDetector.Guess("三体", 3.0), g("三体 第三部 死神永生"))
        assertEquals(SeriesDetector.Guess("某某传", 12.0), g("某某传 卷十二"))
        assertEquals(SeriesDetector.Guess("诡秘之主", 3.0), g("诡秘之主（三）.epub"))
        assertEquals(SeriesDetector.Guess("明朝那些事儿", 1.0), g("明朝那些事儿 上"))
        assertEquals(SeriesDetector.Guess("某书", 21.0), g("某书第二十一册"))
    }

    @Test fun englishVolumes() {
        assertEquals(SeriesDetector.Guess("The Lighthouse Keeper", 2.0), g("The Lighthouse Keeper, Vol. 2"))
        assertEquals(SeriesDetector.Guess("Dune", 3.0), g("Dune Book 3"))
        assertEquals(SeriesDetector.Guess("Saga", 5.0), g("Saga #5"))
    }

    @Test fun standaloneTitlesAreNotSeries() {
        assertNull(g("1984"))
        assertNull(g("Catch-22"))
        assertNull(g("活着"))
        assertNull(g("百年孤独"))
    }

    @Test fun chineseNumbers() {
        assertEquals(12, SeriesDetector.parseNumber("十二"))
        assertEquals(20, SeriesDetector.parseNumber("二十"))
        assertEquals(103, SeriesDetector.parseNumber("一百零三"))
        assertEquals(7, SeriesDetector.parseNumber("7"))
    }
}
