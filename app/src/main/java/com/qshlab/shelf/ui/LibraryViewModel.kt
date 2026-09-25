package com.qshlab.shelf.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qshlab.shelf.R
import com.qshlab.shelf.data.Book
import com.qshlab.shelf.chinese.ChineseConverter
import com.qshlab.shelf.data.ImportResult
import com.qshlab.shelf.reader.showsSimplified
import com.qshlab.shelf.settings.LibrarySettings
import com.qshlab.shelf.data.SeriesSuggester
import com.qshlab.shelf.data.languageKey
import com.qshlab.shelf.settings.GroupBy
import com.qshlab.shelf.settings.StatusFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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

    data class Stack(val name: String, val books: List<Book>, val kind: GroupKind = GroupKind.SERIES) : ShelfItem {
        override val key = (if (kind == GroupKind.SERIES) "s" else "a") + name
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
    /** What the shelf shows after grouping, filtering and sorting. */
    val items: List<ShelfItem> = emptyList(),
    /** Number of books behind [items]. */
    val shownCount: Int = 0,
    /** Language keys and formats present in the library (for the filter menu). */
    val languages: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val suggestions: List<SeriesSuggester.Suggestion> = emptyList(),
    /** The book to offer under 继续阅读. */
    val current: Book? = null,
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

    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    val state: StateFlow<LibraryState> = combine(
        shelf.library.observeBooks(), shelf.settings.library, shelf.settings.reader, query, shelf.settings.app,
    ) { books, settings, reader, q, app ->
        build(books.map { it.forDisplay(reader.toSimplified) }, settings, q, app.dismissedSuggestions)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryState())

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
        viewModelScope.launch { events.send(str(R.string.lib_msg_importing_files, uris.size)); report(shelf.library.importUris(uris)) }
    }

    /** A file opened with / shared to the app: import it, then open it (or the existing copy). */
    fun importAndOpen(uri: Uri, onReady: (Long) -> Unit) {
        viewModelScope.launch {
            val result = shelf.library.importUris(listOf(uri)).firstOrNull()
            val book = when (result) {
                is ImportResult.Added -> result.book
                is ImportResult.Skipped -> if (result.duplicate) shelf.library.findByName(result.name) else null
                null -> null
            }
            if (book == null) {
                events.send((result as? ImportResult.Skipped)?.let { str(R.string.lib_msg_cannot_open_reason, it.reason) } ?: str(R.string.lib_msg_cannot_open_file))
                return@launch
            }
            open(book, onReady)
        }
    }

    fun importFolder(uri: Uri) {
        viewModelScope.launch { events.send(str(R.string.lib_msg_scanning_folder)); report(shelf.library.importTree(uri)) }
    }

    fun update(book: Book) {
        viewModelScope.launch { shelf.library.update(book) }
    }

    fun delete(book: Book) {
        viewModelScope.launch { shelf.library.delete(book); events.send(str(R.string.lib_msg_deleted_book, book.title)) }
    }

    /** Opens the publication first; [onReady] then starts the reader. */
    fun open(book: Book, onReady: (Long) -> Unit) {
        viewModelScope.launch {
            shelf.readers.open(book.id)
                .onSuccess { onReady(book.id) }
                .onFailure { events.send(it.message ?: str(R.string.lib_msg_cannot_open)) }
        }
    }

    private suspend fun report(results: List<ImportResult>, quietIfEmpty: Boolean = false) {
        if (results.isEmpty()) {
            if (!quietIfEmpty) events.send(str(R.string.lib_msg_nothing_to_import))
            return
        }
        val added = results.count { it is ImportResult.Added }
        val skipped = results.filterIsInstance<ImportResult.Skipped>()
        val reason = skipped.groupBy { it.reason }.keys.firstOrNull()
        val msg = when {
            skipped.isEmpty() -> str(R.string.lib_msg_imported, added)
            reason == null -> str(R.string.lib_msg_imported_skipped, added, skipped.size)
            else -> str(R.string.lib_msg_imported_skipped_reason, added, skipped.size, reason)
        }
        events.send(msg)
    }

    private fun build(books: List<Book>, settings: LibrarySettings, q: String, dismissed: Set<String>): LibraryState {
        val searched = if (q.isBlank()) books else books.filter { b ->
            listOfNotNull(b.title, b.author, b.series, b.fileName).any { it.contains(q.trim(), ignoreCase = true) }
        }
        val filtered = searched.filter { b ->
            val statusOk = when (settings.status) {
                StatusFilter.ALL -> true
                StatusFilter.READING -> b.started && !b.finished
                StatusFilter.WANT -> b.wantToRead && !b.finished
                StatusFilter.UNREAD -> !b.started && !b.finished
                StatusFilter.FINISHED -> b.finished
            }
            statusOk && (settings.language == null || b.languageKey() == settings.language) &&
                (settings.format == null || b.format == settings.format)
        }
        val sorted = sort(filtered, settings.sort)
        val items = when (settings.groupBy) {
            GroupBy.SERIES -> stackSeries(sorted)
            GroupBy.BOOKS -> sorted.map { ShelfItem.Single(it) }
            GroupBy.AUTHORS -> {
                // One stack per author, in the order of their best-ranked book.
                // Keyed on the 简体 form so 沈書雲 and 沈书云 are one author.
                val byAuthor = sorted.groupBy { seriesKey(it.author ?: unknownAuthor)!! }
                byAuthor.values.map { list -> ShelfItem.Stack(list.first().author ?: unknownAuthor, list, GroupKind.AUTHOR) }
                    .let { stacks -> if (settings.sort == SortOrder.TITLE || settings.sort == SortOrder.AUTHOR) stacks.sortedWith { a, b -> collator.compare(a.name, b.name) } else stacks }
            }
        }
        val suggestions = SeriesSuggester.suggest(
            books.map { SeriesSuggester.Input(it.id, it.title, it.author, it.series, it.seriesManual) },
            key = { converter.toSimplified(it) },
        ).filter { it.key !in dismissed }
        return LibraryState(
            loaded = true,
            allBooks = books,
            settings = settings,
            query = q,
            items = items,
            shownCount = filtered.size,
            languages = books.mapNotNull { it.languageKey() }.distinct().sorted(),
            formats = books.map { it.format }.distinct().sorted(),
            suggestions = suggestions,
            // The last book opened, finished or not (reaching the last page marks a book finished).
            current = books.filter { it.lastOpenedAt != null }.maxByOrNull { it.lastOpenedAt!! },
        )
    }

    private fun sort(books: List<Book>, order: SortOrder): List<Book> = when (order) {
        SortOrder.RECENT_READ -> books.sortedWith(compareByDescending<Book> { it.lastOpenedAt ?: 0L }.thenByDescending { it.addedAt })
        SortOrder.RECENT_ADDED -> books.sortedByDescending { it.addedAt }
        SortOrder.TITLE -> books.sortedWith { a, b -> collator.compare(a.title, b.title) }
        SortOrder.AUTHOR -> books.sortedWith { a, b -> collator.compare(a.author ?: "", b.author ?: "") }
        SortOrder.PROGRESS -> books.sortedByDescending { it.progress }
    }

    /** Series with 2+ books become one stack, placed where its best-ranked volume would be. */
    private fun stackSeries(sorted: List<Book>): List<ShelfItem> {
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

    // ---- Bulk actions (selection mode) ----

    private suspend fun booksById(ids: Collection<Long>) = ids.mapNotNull { shelf.library.get(it) }

    /**
     * Puts [ids] (in that order) into series [name]; volumes are numbered 1, 2, 3… in that order
     * unless [keepNumbers] and a book already has a number.
     */
    fun assignSeries(ids: List<Long>, name: String?, numbers: List<Double?>) {
        viewModelScope.launch {
            val books = booksById(ids)
            books.forEachIndexed { i, b ->
                shelf.library.update(b.copy(series = name?.trim()?.ifBlank { null }, seriesIndex = if (name.isNullOrBlank()) null else numbers.getOrNull(i), seriesManual = true))
            }
            events.send(if (name.isNullOrBlank()) str(R.string.lib_msg_removed_from_series) else str(R.string.lib_msg_added_to_series, books.size, name.trim()))
        }
    }

    fun acceptSuggestion(s: SeriesSuggester.Suggestion, ids: List<Long>, name: String) {
        viewModelScope.launch {
            val existing = if (s.joinsExisting) shelf.library.observeBooks().first().filter { seriesKey(it.series) == seriesKey(s.name) } else emptyList()
            val chosen = s.members.filter { it.id in ids }
            // Keep detected volume numbers; unnumbered books take the lowest free numbers ("三体" → 1).
            val used = (existing.mapNotNull { it.seriesIndex } + chosen.mapNotNull { it.index }).toMutableSet()
            val numbers = chosen.associate { m ->
                m.id to (m.index ?: generateSequence(1.0) { it + 1 }.first { it !in used }.also { used += it })
            }
            booksById(chosen.map { it.id }).forEach { b ->
                shelf.library.update(b.copy(series = name.trim(), seriesIndex = numbers[b.id], seriesManual = true))
            }
            events.send(str(R.string.lib_msg_series_created, name.trim()))
        }
    }

    fun dismissSuggestion(s: SeriesSuggester.Suggestion) {
        viewModelScope.launch {
            val app = shelf.settings.app.first()
            shelf.settings.updateApp(app.copy(dismissedSuggestions = app.dismissedSuggestions + s.key))
        }
    }

    fun setWantToRead(ids: Collection<Long>, want: Boolean) {
        viewModelScope.launch { booksById(ids).forEach { shelf.library.update(it.copy(wantToRead = want)) } }
    }

    fun setFinished(ids: Collection<Long>, finished: Boolean) {
        viewModelScope.launch {
            booksById(ids).forEach { shelf.library.update(it.copy(finished = finished, wantToRead = if (finished) false else it.wantToRead)) }
        }
    }

    fun deleteAll(ids: Collection<Long>) {
        viewModelScope.launch {
            val books = booksById(ids)
            books.forEach { shelf.library.delete(it) }
            events.send(str(R.string.lib_msg_deleted_books, books.size))
        }
    }

    fun importScanned(files: List<java.io.File>) {
        viewModelScope.launch {
            events.send(str(R.string.lib_msg_importing_books, files.size))
            report(shelf.library.importFiles(files))
        }
    }

    /** Name (and grouping key) for books without an author, in the app's language. */
    val unknownAuthor: String get() = getApplication<Application>().getString(R.string.lib_unknown_author)
}
