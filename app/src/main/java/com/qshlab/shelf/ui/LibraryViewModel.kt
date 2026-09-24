package com.qshlab.shelf.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qshlab.shelf.data.Book
import com.qshlab.shelf.chinese.ChineseConverter
import com.qshlab.shelf.data.ImportResult
import com.qshlab.shelf.reader.showsSimplified
import com.qshlab.shelf.settings.LibrarySettings
import com.qshlab.shelf.settings.LibraryTab
import com.qshlab.shelf.settings.SortOrder
import com.qshlab.shelf.shelfApp
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One cell on the bookshelf: a single book or a stacked series. */
sealed interface ShelfItem {
    val key: String

    data class Single(val book: Book) : ShelfItem {
        override val key = "b${book.id}"
    }

    data class Stack(val name: String, val books: List<Book>) : ShelfItem {
        override val key = "s$name"
        val progress: Float get() = books.map { it.progress }.average().toFloat()
        val lastOpenedAt: Long get() = books.maxOf { it.lastOpenedAt ?: 0L }
        val addedAt: Long get() = books.maxOf { it.addedAt }
    }
}

data class Group(val name: String, val books: List<Book>)

data class LibraryState(
    val loaded: Boolean = false,
    val allBooks: List<Book> = emptyList(),
    val settings: LibrarySettings = LibrarySettings(),
    val query: String = "",
    val shelf: List<ShelfItem> = emptyList(),
    val flat: List<Book> = emptyList(),
    val groups: List<Group> = emptyList(),
)

/** Sorts Chinese by pinyin order and everything else alphabetically. */
private val collator: Collator = Collator.getInstance(Locale.CHINA)

fun seriesOrder(books: List<Book>): List<Book> =
    books.sortedWith(compareBy<Book> { it.seriesIndex ?: Double.MAX_VALUE }.thenComparator { a, b -> collator.compare(a.title, b.title) })

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val shelf = app.shelfApp
    val query = MutableStateFlow("")

    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    private val converter by lazy { ChineseConverter.get(app) }

    val state: StateFlow<LibraryState> = combine(
        shelf.library.observeBooks(), shelf.settings.library, shelf.settings.reader, query,
    ) { books, settings, reader, q ->
        build(books.map { it.forDisplay(reader.toSimplified) }, settings, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryState())

    /**
     * Series are matched on their 简体 form, so volumes shown in different scripts (one switched to
     * 简体, one not) still group together.
     */
    fun seriesKey(series: String?): String? = series?.let(converter::toSimplified)

    /** Books read in 简体 are also listed in 简体 (title, author, series). */
    private fun Book.forDisplay(globalSimplified: Boolean): Book =
        if (!showsSimplified(globalSimplified)) this
        else copy(title = converter.toSimplified(title), author = author?.let(converter::toSimplified), series = series?.let(converter::toSimplified))

    init {
        viewModelScope.launch { report(shelf.library.importInbox(), quietIfEmpty = true) }
    }

    fun updateSettings(transform: (LibrarySettings) -> LibrarySettings) {
        viewModelScope.launch { shelf.settings.updateLibrary(transform(state.value.settings)) }
    }

    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch { events.send("正在导入 ${uris.size} 个文件…"); report(shelf.library.importUris(uris)) }
    }

    fun importFolder(uri: Uri) {
        viewModelScope.launch { events.send("正在扫描文件夹…"); report(shelf.library.importTree(uri)) }
    }

    fun update(book: Book) {
        viewModelScope.launch { shelf.library.update(book) }
    }

    fun delete(book: Book) {
        viewModelScope.launch { shelf.library.delete(book); events.send("已删除《${book.title}》") }
    }

    /** Opens the publication first; [onReady] then starts the reader. */
    fun open(book: Book, onReady: (Long) -> Unit) {
        viewModelScope.launch {
            shelf.readers.open(book.id)
                .onSuccess { onReady(book.id) }
                .onFailure { events.send(it.message ?: "无法打开") }
        }
    }

    private suspend fun report(results: List<ImportResult>, quietIfEmpty: Boolean = false) {
        if (results.isEmpty()) {
            if (!quietIfEmpty) events.send("没有找到可导入的书（目前支持 EPUB、TXT）")
            return
        }
        val added = results.count { it is ImportResult.Added }
        val skipped = results.filterIsInstance<ImportResult.Skipped>()
        val msg = buildString {
            append("导入了 $added 本")
            if (skipped.isNotEmpty()) {
                append("，跳过 ${skipped.size} 个")
                skipped.groupBy { it.reason }.keys.firstOrNull()?.let { append("（$it）") }
            }
        }
        events.send(msg)
    }

    private fun build(books: List<Book>, settings: LibrarySettings, q: String): LibraryState {
        val filtered = if (q.isBlank()) books else books.filter { b ->
            listOfNotNull(b.title, b.author, b.series, b.fileName).any { it.contains(q.trim(), ignoreCase = true) }
        }
        val sorted = sort(filtered, settings.sort)
        val base = LibraryState(loaded = true, allBooks = books, settings = settings, query = q)
        return when (settings.tab) {
            LibraryTab.SHELF -> base.copy(shelf = stack(sorted, settings))
            LibraryTab.RECENT_READ -> base.copy(flat = filtered.filter { it.lastOpenedAt != null }.sortedByDescending { it.lastOpenedAt })
            LibraryTab.RECENT_ADDED -> base.copy(flat = filtered.sortedByDescending { it.addedAt })
            LibraryTab.FINISHED -> base.copy(flat = sorted.filter { it.finished })
            LibraryTab.AUTHORS -> base.copy(
                groups = filtered.groupBy { it.author ?: "未知作者" }
                    .map { (name, list) -> Group(name, sort(list, SortOrder.TITLE)) }
                    .sortedWith { a, b -> collator.compare(a.name, b.name) },
            )
            LibraryTab.SERIES -> base.copy(
                groups = filtered.filter { it.series != null }.groupBy { seriesKey(it.series)!! }
                    .map { (_, list) -> seriesOrder(list).let { Group(it.first().series!!, it) } }
                    .sortedWith { a, b -> collator.compare(a.name, b.name) },
            )
        }
    }

    private fun sort(books: List<Book>, order: SortOrder): List<Book> = when (order) {
        SortOrder.RECENT_READ -> books.sortedWith(compareByDescending<Book> { it.lastOpenedAt ?: 0L }.thenByDescending { it.addedAt })
        SortOrder.RECENT_ADDED -> books.sortedByDescending { it.addedAt }
        SortOrder.TITLE -> books.sortedWith { a, b -> collator.compare(a.title, b.title) }
        SortOrder.AUTHOR -> books.sortedWith { a, b -> collator.compare(a.author ?: "", b.author ?: "") }
        SortOrder.PROGRESS -> books.sortedByDescending { it.progress }
    }

    /** Series with 2+ books become one stack, placed where its best-ranked volume would be. */
    private fun stack(sorted: List<Book>, settings: LibrarySettings): List<ShelfItem> {
        if (!settings.stackSeries) return sorted.map { ShelfItem.Single(it) }
        val bySeries = sorted.filter { it.series != null }.groupBy { seriesKey(it.series)!! }
        val placed = mutableSetOf<String>()
        return sorted.mapNotNull { book ->
            val key = seriesKey(book.series)
            val members = key?.let { bySeries[it] }
            when {
                key == null || members == null || members.size < 2 -> ShelfItem.Single(book)
                placed.add(key) -> seriesOrder(members).let { ShelfItem.Stack(it.first().series!!, it) }
                else -> null
            }
        }
    }
}
