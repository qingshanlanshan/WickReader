package com.qshlab.shelf.data

/**
 * Guesses a series name and volume number from a title or file name, e.g.
 * "山海行记 第二卷", "雲中書（三）", "The Lighthouse Keeper, Vol. 2", "Dune Book 3", "三体 上".
 *
 * Only "strong" patterns are used here (explicit volume words or brackets), so standalone titles
 * that merely end in a number ("1984", "Catch-22") are not split.
 */
object SeriesDetector {
    data class Guess(val series: String, val index: Double)

    private val CN_DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9, '壹' to 1, '贰' to 2, '貳' to 2, '叁' to 3,
        '參' to 3, '肆' to 4, '伍' to 5, '陆' to 6, '陸' to 6, '柒' to 7, '捌' to 8, '玖' to 9,
    )

    /** "十二" -> 12, "二十" -> 20, "一百零三" -> 103, "12" -> 12. */
    fun parseNumber(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        if (s.isEmpty() || s.any { it !in CN_DIGITS && it !in "十拾百佰" }) return null
        var total = 0
        var current = 0
        for (c in s) {
            when (c) {
                '十', '拾' -> { total += (if (current == 0) 1 else current) * 10; current = 0 }
                '百', '佰' -> { total += (if (current == 0) 1 else current) * 100; current = 0 }
                else -> current = CN_DIGITS.getValue(c)
            }
        }
        return total + current
    }

    private const val NUM = "([0-9]{1,3}|[零〇一二两三四五六七八九十百壹贰貳叁參肆伍陆陸柒捌玖拾佰]{1,5})"
    private const val SEP = "[\\s:：,，_\\-—·.]*"

    private val PATTERNS: List<Regex> = listOf(
        // 书名 第二卷 / 第3部 / 第十册 (+ optional subtitle)
        Regex("^(.+?)${SEP}第${NUM}[卷部册冊集季本篇](?:$SEP.*)?$"),
        // 书名 卷二 / 卷3
        Regex("^(.+?)${SEP}[卷冊册]${NUM}(?:$SEP.*)?$"),
        // 书名（二）/ (3) / 【3】
        Regex("^(.+?)$SEP[（(【\\[]${NUM}[）)】\\]](?:$SEP.*)?$"),
        // Title, Vol. 2 / Volume 2 / Book 3 / Part 4 / #5
        Regex("^(.+?)$SEP(?:vol(?:ume)?\\.?|book|part|tome|no\\.?|#)\\s*([0-9]{1,3})(?:$SEP.*)?$", RegexOption.IGNORE_CASE),
    )

    private val UPPER_MIDDLE_LOWER = Regex("^(.+?)$SEP[（(]?([上中下])[）)]?(?:[卷册冊部])?$")

    private val BOOK_EXTENSION = Regex("\\.(epub|pdf|txt|fb2|mobi|azw3|cbz|zip)$", RegexOption.IGNORE_CASE)

    fun guess(rawTitle: String): Guess? {
        val t = rawTitle.replace(BOOK_EXTENSION, "").trim()
        for (p in PATTERNS) {
            val m = p.matchEntire(t) ?: continue
            val name = m.groupValues[1].trim().trimEnd(',', '，', '-', ':', '：', '·')
            val n = parseNumber(m.groupValues[2]) ?: continue
            if (name.isNotEmpty() && n in 0..999) return Guess(name, n.toDouble())
        }
        UPPER_MIDDLE_LOWER.matchEntire(t)?.let { m ->
            val index = when (m.groupValues[2]) { "上" -> 1.0; "中" -> 2.0; else -> 3.0 }
            return Guess(m.groupValues[1].trim(), index)
        }
        return null
    }
}
