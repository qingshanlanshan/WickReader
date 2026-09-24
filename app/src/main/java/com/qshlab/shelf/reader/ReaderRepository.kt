package com.qshlab.shelf.reader

import com.qshlab.shelf.data.Book
import com.qshlab.shelf.data.LibraryRepository
import java.io.File
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
)

/**
 * Opens publications before the reader starts. ReaderActivity needs the navigator factory
 * synchronously in onCreate, so the (async) opening happens here first.
 */
class ReaderRepository(private val library: LibraryRepository) {
    private val sessions = mutableMapOf<Long, ReaderSession>()
    private val mutex = Mutex()

    operator fun get(bookId: Long): ReaderSession? = sessions[bookId]

    suspend fun open(bookId: Long): Result<ReaderSession> = mutex.withLock {
        sessions[bookId]?.let { return Result.success(it) }
        val book = library.get(bookId) ?: return Result.failure(IllegalStateException("书不存在"))
        val file = File(book.filePath)
        if (!file.exists()) return Result.failure(IllegalStateException("书籍文件丢失"))
        val publication = library.openPublication(file)
            ?: return Result.failure(IllegalStateException("无法打开这本书"))
        val locator = book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        val session = ReaderSession(book, publication, EpubNavigatorFactory(publication), locator)
        sessions[bookId] = session
        library.touch(bookId)
        Result.success(session)
    }

    fun close(bookId: Long) {
        sessions.remove(bookId)?.publication?.close()
    }
}
