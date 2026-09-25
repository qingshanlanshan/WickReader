package com.qshlab.shelf.data

/**
 * Names the converters give to generated parts of a book (untitled parts, the cover page, the
 * table of contents). They belong to the book, so they follow the book's language, not the app's.
 */
class ContentLabels private constructor(
    val frontMatter: String,
    val cover: String,
    val notes: String,
    val toc: String,
    private val partPattern: String,
    private val pagePattern: String,
) {
    fun part(n: Int) = partPattern.format(n)
    fun page(n: Int) = pagePattern.format(n)

    companion object {
        private val ZH = ContentLabels("卷首", "封面", "注释", "目录", "第%d部分", "第 %d 页")
        private val JA = ContentLabels("前書き", "表紙", "注", "目次", "第%d部", "%dページ")
        private val EN = ContentLabels("Front matter", "Cover", "Notes", "Contents", "Part %d", "Page %d")

        fun of(language: String?): ContentLabels = when (language?.substringBefore('-')?.lowercase()) {
            "zh", null, "" -> ZH
            "ja" -> JA
            else -> EN
        }
    }
}
