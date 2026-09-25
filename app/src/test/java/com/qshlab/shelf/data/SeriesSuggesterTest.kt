package com.qshlab.shelf.data

import com.qshlab.shelf.data.SeriesSuggester.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesSuggesterTest {
    private fun book(id: Long, title: String, author: String? = null, series: String? = null, manual: Boolean = false) =
        Input(id, title, author, series, manual)

    @Test fun stems() {
        assertEquals("三体" to null, SeriesSuggester.stem("三体"))
        assertEquals("三体" to 2.0, SeriesSuggester.stem("三体Ⅱ：黑暗森林"))
        assertEquals("三体" to 3.0, SeriesSuggester.stem("三体3 死神永生"))
        assertEquals("盗墓笔记" to 5.0, SeriesSuggester.stem("盗墓笔记5"))
        assertEquals("盗墓笔记" to 5.0, SeriesSuggester.stem("【精校】盗墓笔记 五"))
        assertEquals("The Expanse" to 2.0, SeriesSuggester.stem("The Expanse Book 2"))
        assertEquals("Foundation" to null, SeriesSuggester.stem("Foundation"))
        assertEquals("明朝那些事儿" to 3.0, SeriesSuggester.stem("明朝那些事儿（3）"))
    }

    @Test fun sameStem() {
        val s = SeriesSuggester.suggest(
            listOf(
                book(1, "三体", "刘慈欣"),
                book(2, "三体Ⅱ：黑暗森林", "刘慈欣"),
                book(3, "三体Ⅲ：死神永生"),
                book(4, "球状闪电", "刘慈欣"),
            )
        )
        assertEquals(1, s.size)
        assertEquals("三体", s[0].name)
        assertEquals(listOf(2L, 3L, 1L), s[0].members.map { it.id }) // numbered first, unnumbered last
    }

    @Test fun sharedPrefixSameAuthor() {
        val s = SeriesSuggester.suggest(
            listOf(
                book(1, "哈利·波特与魔法石", "J.K.罗琳"),
                book(2, "哈利·波特与密室", "J.K.罗琳"),
                book(3, "哈利·波特与阿兹卡班的囚徒", "J.K.罗琳"),
                book(4, "哈利路亚", "别人"),
            )
        )
        assertEquals(1, s.size)
        assertEquals("哈利·波特", s[0].name)
        assertEquals(setOf(1L, 2L, 3L), s[0].members.map { it.id }.toSet())
    }

    @Test fun joinsExistingSeries() {
        val s = SeriesSuggester.suggest(
            listOf(
                book(1, "山海行记 第一卷", "林", series = "山海行记"),
                book(2, "山海行记 第二卷", "林", series = "山海行记"),
                book(3, "山海行记外传", "林"),
            )
        )
        assertEquals(1, s.size)
        assertTrue(s[0].joinsExisting)
        assertEquals("山海行记", s[0].name)
    }

    @Test fun leavesUnrelatedAndManualAlone() {
        val s = SeriesSuggester.suggest(
            listOf(
                book(1, "活着", "余华"),
                book(2, "活动变人形", "王蒙"),
                book(3, "围城", "钱钟书"),
                book(6, "一本书", "某人"),
                book(7, "一本书", "某人"),
                book(4, "三体", "刘慈欣", manual = true),
                book(5, "三体Ⅱ", "刘慈欣", manual = true),
            )
        )
        assertTrue(s.isEmpty())
    }
}
