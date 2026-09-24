package com.qshlab.shelf.reader

import com.qshlab.shelf.data.Book
import com.qshlab.shelf.data.LibraryRepository
import com.qshlab.shelf.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

class ReaderSession(
    val book: Book,
    val publication: Publication,
    val navigatorFactory: EpubNavigatorFactory,
    val initialLocator: Locator?,
    /** Whether this session shows the book converted 繁→简. */
    val simplified: Boolean,
)

/** 繁→简 applies when the book says so, else when it's a 繁體 book and the global switch is on. */
fun Book.showsSimplified(globalDefault: Boolean): Boolean = convertToSimplified ?: (globalDefault && isTraditional)

/**
 * Opens publications before the reader starts. ReaderActivity needs the navigator factory
 * synchronously in onCreate, so the (async) opening happens here first.
 */
class ReaderRepository(private val library: LibraryRepository, private val settings: SettingsRepository) {
    private val sessions = mutableMapOf<Long, ReaderSession>()
    private val mutex = Mutex()

    operator fun get(bookId: Long): ReaderSession? = sessions[bookId]

    suspend fun open(bookId: Long): Result<ReaderSession> = mutex.withLock {
        sessions[bookId]?.let { return Result.success(it) }
        val book = library.get(bookId) ?: return Result.failure(IllegalStateException("书不存在"))
        val file = File(book.filePath)
        if (!file.exists()) return Result.failure(IllegalStateException("书籍文件丢失"))
        val simplified = book.showsSimplified(settings.reader.first().toSimplified)
        val publication = library.openPublication(file, toSimplified = simplified)
            ?: return Result.failure(IllegalStateException("无法打开这本书"))
        val locator = book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        val session = ReaderSession(book, publication, EpubNavigatorFactory(publication), locator, simplified)
        sessions[bookId] = session
        library.touch(bookId)
        Result.success(session)
    }

    /** Closes [bookId], unless a newer session already replaced [only]. */
    fun close(bookId: Long, only: ReaderSession? = null) {
        if (only != null && sessions[bookId] !== only) return
        sessions.remove(bookId)?.publication?.close()
    }
}
