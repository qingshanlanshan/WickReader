package com.qshlab.shelf.data

/**
 * Finds books that probably belong together when their files carry no series label, from their
 * titles alone. Unlike [SeriesDetector] (used automatically at import), these are guesses: the
 * user confirms each suggestion before anything changes.
 *
 * Two rules:
 * - Same stem: titles that become equal once volume markers are removed ("三体", "三体Ⅱ：黑暗森林",
 *   "三体3 死神永生" → 三体), or that start with an existing series' name.
 * - Same author and a long shared beginning ("哈利·波特与魔法石", "哈利·波特与密室" → 哈利·波特).
 */
object SeriesSuggester {
    data class Input(val id: Long, val title: String, val author: String?, val series: String?, val seriesManual: Boolean)

    data class Member(val id: Long, val title: String, val index: Double?)

    data class Suggestion(val name: String, val members: List<Member>, val joinsExisting: Boolean) {
        /** Identifies the suggestion so a dismissed one doesn't come back. */
        val key: String get() = members.map { it.id }.sorted().joinToString(",")
    }

    private val ROMAN = mapOf(
        "Ⅰ" to 1, "Ⅱ" to 2, "Ⅲ" to 3, "Ⅳ" to 4, "Ⅴ" to 5, "Ⅵ" to 6, "Ⅶ" to 7, "Ⅷ" to 8, "Ⅸ" to 9, "Ⅹ" to 10,
        "Ⅺ" to 11, "Ⅻ" to 12,
        "I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6, "VII" to 7, "VIII" to 8, "IX" to 9, "X" to 10,
    )

    private val BRACKETED = Regex("[【\\[《〈「『][^】\\]》〉」』]*[】\\]》〉」』]")
    private val SUBTITLE_SPLIT = Regex("[:：—–]|\\s+-\\s+|\\s{2,}")
    private val TRAILING_VOLUME = Regex(
        "[\\s_·.,，、-]*(?:第?\\s*([0-9]{1,3}|[零〇一二两三四五六七八九十百]{1,4})\\s*[卷部册冊集季本篇]?|([ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ])|\\b([IVX]{1,4})\\b|([上中下])[卷册冊部篇]?|(?:vol(?:ume)?\\.?|book|part)\\s*([0-9]{1,3}))\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private const val TRIM = " \t·.,，、:：-—–_(（与和及之的&"

    private fun isCjk(c: Char) = c.code in 0x3400..0x9fff

    /** Visual length: a CJK character counts as two latin letters. */
    private fun weight(s: String) = s.sumOf { if (isCjk(it)) 2 else 1 as Int }

    private fun normalizeAuthor(a: String?) = a?.lowercase()?.filter { it.isLetterOrDigit() }?.ifEmpty { null }

    /** The title without brackets, subtitle and trailing volume marker; plus the volume if found. */
    fun stem(title: String): Pair<String, Double?> {
        var t = title.replace(BRACKETED, " ").replace(Regex("[（(][^）)]*[）)]\\s*$"), "").trim()
        val bracketNumber = Regex("[（(]([0-9一二三四五六七八九十]{1,3}|[上中下])[）)]").find(title)?.groupValues?.get(1)
        t = t.split(SUBTITLE_SPLIT).first().trim()
        // "三体3 死神永生": in Chinese titles a space also starts a subtitle, unless what follows is
        // the volume itself ("盗墓笔记 五").
        if (t.any(::isCjk) && TRAILING_VOLUME.find(t) == null) t = t.split(Regex("\\s+")).first()
        var index: Double? = null
        TRAILING_VOLUME.find(t)?.let { m ->
            val g = m.groupValues
            index = when {
                g[1].isNotEmpty() -> SeriesDetector.parseNumber(g[1])?.toDouble()
                g[2].isNotEmpty() -> ROMAN[g[2]]?.toDouble()
                g[3].isNotEmpty() -> ROMAN[g[3].uppercase()]?.toDouble()
                g[4].isNotEmpty() -> when (g[4]) { "上" -> 1.0; "中" -> 2.0; else -> 3.0 }
                g[5].isNotEmpty() -> g[5].toDoubleOrNull()
                else -> null
            }
            val stripped = t.substring(0, m.range.first).trim()
            if (index != null && weight(stripped) >= 4) t = stripped else index = null
        }
        if (index == null && bracketNumber != null) {
            index = SeriesDetector.parseNumber(bracketNumber)?.toDouble()
                ?: when (bracketNumber) { "上" -> 1.0; "中" -> 2.0; "下" -> 3.0; else -> null }
        }
        return t.trim(*TRIM.toCharArray()) to index
    }

    private fun commonPrefix(a: String, b: String): String {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return a.substring(0, i)
    }

    fun suggest(books: List<Input>, key: (String) -> String = { it }): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        // Books whose series the user removed on purpose are left alone.
        val free = books.filter { it.series == null && !it.seriesManual }
        val used = mutableSetOf<Long>()

        // 1. Titles starting with an existing series' name → join it.
        val existing = books.filter { it.series != null }.groupBy { key(it.series!!) }
        for ((seriesKey, members) in existing.entries.sortedByDescending { weight(it.key) }) {
            if (weight(seriesKey) < 4) continue
            val authors = members.mapNotNull { normalizeAuthor(it.author) }.toSet()
            val joining = free.filter { b ->
                b.id !in used && key(b.title).startsWith(seriesKey) &&
                    (authors.isEmpty() || normalizeAuthor(b.author) == null || normalizeAuthor(b.author) in authors)
            }
            if (joining.isNotEmpty()) {
                used += joining.map { it.id }
                out += Suggestion(members.first().series!!, joining.map { Member(it.id, it.title, stem(it.title).second) }, joinsExisting = true)
            }
        }

        // 2. Same stem (and no conflicting authors).
        val rest = free.filter { it.id !in used }
        val byStem = rest.groupBy { key(stem(it.title).first) }
        for ((s, members) in byStem) {
            if (members.size < 2 || weight(s) < 4) continue
            for (group in members.groupBy { normalizeAuthor(it.author) }.let(::mergeUnknownAuthors)) {
                if (group.size < 2) continue
                used += group.map { it.id }
                out += Suggestion(stem(group.first().title).first, group.map { Member(it.id, it.title, stem(it.title).second) }, joinsExisting = false)
            }
        }

        // 3. Same author, long shared beginning.
        val byAuthor = free.filter { it.id !in used && normalizeAuthor(it.author) != null }.groupBy { normalizeAuthor(it.author)!! }
        for ((_, list) in byAuthor) {
            val sorted = list.sortedBy { key(it.title) }
            var i = 0
            while (i < sorted.size) {
                var j = i + 1
                var prefix = key(sorted[i].title)
                while (j < sorted.size) {
                    val p = commonPrefix(prefix, key(sorted[j].title))
                    val shorter = minOf(weight(key(sorted[i].title)), weight(key(sorted[j].title)))
                    if (weight(p.trimEnd(*TRIM.toCharArray())) < 4 || weight(p) * 10 < shorter * 4) break
                    prefix = p
                    j++
                }
                if (j - i >= 2) {
                    val group = sorted.subList(i, j)
                    out += Suggestion(prefix.trim(*TRIM.toCharArray()), group.map { Member(it.id, it.title, stem(it.title).second) }, joinsExisting = false)
                }
                i = maxOf(j, i + 1)
            }
        }
        return out
            // Two copies of the same book are duplicates, not a series.
            .filter { it.joinsExisting || it.members.map { m -> key(m.title) }.distinct().size >= 2 }
            .map { s -> s.copy(members = s.members.sortedWith(compareBy<Member> { it.index ?: Double.MAX_VALUE }.thenBy { it.title })) }
    }

    /** Books with an unknown author join the only named group, if there is exactly one. */
    private fun mergeUnknownAuthors(groups: Map<String?, List<Input>>): List<List<Input>> {
        val named = groups.filterKeys { it != null }
        val unknown = groups[null].orEmpty()
        return when (named.size) {
            0 -> listOf(unknown)
            1 -> listOf(named.values.first() + unknown)
            else -> named.values.toList()
        }
    }
}
