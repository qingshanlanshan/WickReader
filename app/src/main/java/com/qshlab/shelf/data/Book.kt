package com.qshlab.shelf.data

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The book file inside app storage. */
    val filePath: String,
    /** Original file name, used for display fallbacks and series detection. */
    val fileName: String,
    /** Lower-case extension: epub, pdf, … */
    val format: String,
    val title: String,
    val author: String?,
    val series: String?,
    val seriesIndex: Double?,
    /** True once the user edited the series by hand; detection won't touch it again. */
    val seriesManual: Boolean = false,
    val language: String?,
    val coverPath: String?,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    /** Readium locator JSON of the last reading position. */
    val locator: String? = null,
    /** 0..1 across the whole book. */
    val progress: Float = 0f,
    val finished: Boolean = false,
    val fileSize: Long = 0,
    /** Detected at import from language metadata or the text itself. */
    val isTraditional: Boolean = false,
    /** Per-book override of 繁→简 conversion: null = follow the global setting. */
    val convertToSimplified: Boolean? = null,
    /** TranslationMode name (BILINGUAL / TRANSLATED); null = off. Added in schema v2. */
    val translationMode: String? = null,
    /** PageLayout name; null = decide from the book (vertical for Japanese RTL books). Schema v4. */
    val pageLayout: String? = null,
    /** Marked 想读 by the user. Schema v4. */
    @ColumnInfo(defaultValue = "0")
    val wantToRead: Boolean = false,
    /** ML Kit language to translate this book into ("zh", "en", "ja"); null = automatic. Schema v5. */
    val translationTarget: String? = null,
) {
    val started: Boolean get() = lastOpenedAt != null && progress > 0.001f
}

/** Groups books by language for filtering: "zh", "zh-Hant", "en", "ja", …; null if unknown. */
fun Book.languageKey(): String? {
    if (isTraditional) return "zh-Hant"
    val base = language?.substringBefore('-')?.substringBefore('_')?.lowercase()?.takeIf { it.isNotBlank() && it != "und" }
    if (base != null) return if (base == "cmn") "zh" else base
    return if (title.any { it in '\u4e00'..'\u9fff' }) "zh" else null
}

fun languageLabel(key: String): String = when (key) {
    "zh" -> "中文"
    "zh-Hant" -> "繁体中文"
    "en" -> "英文"
    "ja" -> "日文"
    else -> java.util.Locale.forLanguageTag(key).getDisplayLanguage(java.util.Locale.CHINESE).ifBlank { key }
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): Book?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<Book?>

    @Query("SELECT * FROM books WHERE fileName = :fileName ORDER BY addedAt DESC LIMIT 1")
    suspend fun findByName(fileName: String): Book?

    @Query("SELECT COUNT(*) FROM books WHERE fileName = :fileName AND fileSize = :fileSize")
    suspend fun countDuplicates(fileName: String, fileSize: Long): Int

    @Insert
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Query("UPDATE books SET locator = :locator, progress = :progress, lastOpenedAt = :openedAt, finished = finished OR :finished WHERE id = :id")
    suspend fun updateProgress(id: Long, locator: String, progress: Float, openedAt: Long, finished: Boolean)

    @Query("UPDATE books SET lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun touch(id: Long, openedAt: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT fileName || ':' || fileSize FROM books")
    suspend fun fileKeys(): List<String>
}

enum class AnnotationType { BOOKMARK, HIGHLIGHT }

/** A bookmark, or a highlight (optionally with a note). Added in schema v3. */
@Entity(tableName = "annotations", indices = [Index("bookId")])
data class Annotation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val type: AnnotationType,
    /** Readium locator JSON (for highlights it covers the selected text). */
    val locator: String,
    /** Selected text, or a snippet of the page for bookmarks. */
    val text: String,
    val chapter: String?,
    val note: String? = null,
    /** ARGB highlight color. */
    val color: Int = 0xFFFFD666.toInt(),
    val progress: Float,
    val createdAt: Long,
)

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY progress, createdAt")
    fun observe(bookId: Long): Flow<List<Annotation>>

    @Insert
    suspend fun insert(a: Annotation): Long

    @Update
    suspend fun update(a: Annotation)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM annotations WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}

@Database(
    entities = [Book::class, Annotation::class],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class ShelfDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun annotations(): AnnotationDao
}
