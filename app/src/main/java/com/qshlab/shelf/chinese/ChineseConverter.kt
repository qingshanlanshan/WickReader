package com.qshlab.shelf.chinese

import android.content.Context
import java.io.InputStream

/**
 * Traditional → Simplified conversion with the OpenCC t2s dictionaries (assets/opencc, Apache-2.0):
 * longest phrase match first (TSPhrases), then single characters (TSCharacters).
 */
class ChineseConverter private constructor(
    private val chars: Map<Char, String>,
    /** First char → phrase lengths starting with it (longest first), so only real candidates are tried. */
    private val phraseLengths: Map<Char, IntArray>,
    private val phrases: Map<String, String>,
) {
    fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val lengths = phraseLengths[c]
            var matched = false
            if (lengths != null) {
                for (len in lengths) {
                    if (i + len > text.length) continue
                    val hit = phrases[text.substring(i, i + len)] ?: continue
                    out.append(hit)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                out.append(if (c == '著') zhuoOrZhe(text, i) else chars[c] ?: c.toString())
                i++
            }
        }
        return out.toString()
    }

    /**
     * 著 is both zhù (著作, 名著: stays 著) and the particle zhe/zhuó (看著, 著急: 着 in 简体).
     * OpenCC leaves it alone, so Taiwanese text would read "看著"; decide from the neighbours.
     */
    private fun zhuoOrZhe(text: String, i: Int): String {
        val next = text.getOrNull(i + 1)
        val prev = text.getOrNull(i - 1)
        return if ((next != null && next in ZHU_NEXT) || (prev != null && prev in ZHU_PREV)) "著" else "着"
    }

    /** Converts the text of an (X)HTML document, leaving tags, <script> and <style> untouched. */
    fun htmlToSimplified(html: String): String {
        val out = StringBuilder(html.length)
        var i = 0
        var skipUntil: String? = null
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            val textEnd = if (lt < 0) html.length else lt
            if (textEnd > i) {
                val text = html.substring(i, textEnd)
                out.append(if (skipUntil == null) toSimplified(text) else text)
            }
            if (lt < 0) break
            val gt = html.indexOf('>', lt)
            if (gt < 0) {
                out.append(html, lt, html.length)
                break
            }
            val tag = html.substring(lt, gt + 1)
            out.append(tag)
            val name = tag.drop(1).takeWhile { !it.isWhitespace() && it != '>' && it != '/' }.lowercase()
            if (skipUntil == null && (name == "script" || name == "style") && !tag.endsWith("/>")) skipUntil = "</$name"
            else if (skipUntil != null && tag.lowercase().startsWith(skipUntil)) skipUntil = null
            i = gt + 1
        }
        return out.toString()
    }

    /** Share of CJK characters that only exist in Traditional script (0..1). */
    fun traditionalRatio(sample: String): Double {
        var cjk = 0
        var trad = 0
        for (c in sample) {
            if (c in '一'..'鿿') {
                cjk++
                val s = chars[c]
                if (s != null && s != c.toString()) trad++
            }
        }
        return if (cjk < 50) 0.0 else trad.toDouble() / cjk
    }

    companion object {
        /** 著作 著名 著者 著書 著述 著稱 著錄 … and 名著 原著 顯著 土著 巨著 專著 論著 編著 遺著 … */
        private const val ZHU_NEXT = "作名者书書述称稱录錄"
        private const val ZHU_PREV = "名原显顯土巨专專合论論编編昭卓遗遺新拙译譯"

        @Volatile private var instance: ChineseConverter? = null

        fun get(context: Context): ChineseConverter =
            instance ?: synchronized(this) {
                instance ?: load { name -> context.applicationContext.assets.open("opencc/$name") }.also { instance = it }
            }

        private fun readDict(stream: InputStream): List<Pair<String, String>> =
            stream.bufferedReader().use { it.readLines() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1).substringBefore(' ')
                }

        /** [open] returns the named OpenCC dictionary file. */
        fun load(open: (String) -> InputStream): ChineseConverter {
            val chars = HashMap<Char, String>(6000)
            for ((k, v) in readDict(open("TSCharacters.txt"))) if (k.length == 1) chars[k[0]] = v
            val phrases = HashMap<String, String>(1000)
            for ((k, v) in readDict(open("TSPhrases.txt"))) if (k.length > 1) phrases[k] = v
            val lengths = phrases.keys.groupBy { it[0] }
                .mapValues { (_, ks) -> ks.map { it.length }.distinct().sortedDescending().toIntArray() }
            return ChineseConverter(chars, lengths, phrases)
        }
    }
}
