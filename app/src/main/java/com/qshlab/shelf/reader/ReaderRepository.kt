package com.qshlab.shelf.reader

import android.content.Context
import com.qshlab.shelf.R
import com.qshlab.shelf.data.Book
import com.qshlab.shelf.data.LibraryRepository
import com.google.mlkit.nl.translate.TranslateLanguage
import com.qshlab.shelf.data.languageKey
import com.qshlab.shelf.settings.PageLayout
import com.qshlab.shelf.settings.SettingsRepository
import com.qshlab.shelf.translate.ChapterTranslator
import com.qshlab.shelf.translate.Translation
import com.qshlab.shelf.translate.TranslationMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.adapter.pdfium.navigator.PdfiumPreferencesEditor
import org.readium.adapter.pdfium.navigator.PdfiumSettings
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

class ReaderSession(
    val book: Book,
    val publication: Publication,
    /** EPUB books (null for PDF). */
    private val epubFactory: EpubNavigatorFactory?,
    val initialLocator: Locator?,
    /** Whether this session shows the book converted 繁→简. */
    val simplified: Boolean,
    val translation: TranslationMode,
    /** ML Kit source/target languages used for translation mode. */
    val translationPair: Pair<String, String>,
    /** Layout the book opens with (the reader can change it live). */
    val layout: PageLayout,
    val pdfFactory: PdfNavigatorFactory<PdfiumSettings, PdfiumPreferences, PdfiumPreferencesEditor>? = null,
) {
    val isPdf get() = pdfFactory != null
    val navigatorFactory: EpubNavigatorFactory get() = epubFactory ?: error("not an EPUB session")
}

/** The book's own language as an ML Kit code (Chinese, Japanese, English, …). */
fun Book.translationSource(): String = when (languageKey()) {
    "zh", "zh-Hant" -> TranslateLanguage.CHINESE
    "ja" -> TranslateLanguage.JAPANESE
    null -> TranslateLanguage.ENGLISH
    else -> Translation.languageOf(language) ?: TranslateLanguage.ENGLISH
}

/** Source and target for this book: the user's chosen target, else the default for its language. */
fun Book.translationPair(): Pair<String, String> {
    val from = translationSource()
    val to = translationTarget?.takeIf { it != from } ?: Translation.defaultTarget(from)
    return from to to
}

fun Book.translationModeValue(): TranslationMode =
    translationMode?.let { runCatching { TranslationMode.valueOf(it) }.getOrNull() } ?: TranslationMode.OFF

/** The book's own layout choice, else vertical right-to-left for Japanese books published that way. */
fun Book.pageLayoutValue(publication: Publication): PageLayout {
    pageLayout?.let { v -> PageLayout.entries.firstOrNull { it.name == v }?.let { return it } }
    val japanese = (language ?: publication.metadata.language?.code)?.lowercase()?.startsWith("ja") == true
    val rtl = publication.metadata.readingProgression == org.readium.r2.shared.publication.ReadingProgression.RTL
    return if (japanese && rtl) PageLayout.VERTICAL_RTL else PageLayout.HORIZONTAL_LTR
}

/** Vertical text is only offered for Chinese / Japanese / Korean books. */
fun Book.isCjk(): Boolean = languageKey()?.let { it.startsWith("zh") || it == "ja" || it == "ko" } == true

/** 繁→简 applies when the book says so, else when it's a 繁體 book and the global switch is on. */
fun Book.showsSimplified(globalDefault: Boolean): Boolean = convertToSimplified ?: (globalDefault && isTraditional)

/**
 * Opens publications before the reader starts. ReaderActivity needs the navigator factory
 * synchronously in onCreate, so the (async) opening happens here first.
 */
class ReaderRepository(
    private val context: Context,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
) {
    private val sessions = mutableMapOf<Long, ReaderSession>()
    private val mutex = Mutex()

    operator fun get(bookId: Long): ReaderSession? = sessions[bookId]

    suspend fun open(bookId: Long): Result<ReaderSession> = mutex.withLock {
        sessions[bookId]?.let { return Result.success(it) }
        val book = library.get(bookId) ?: return Result.failure(IllegalStateException(context.getString(R.string.rd_err_book_missing)))
        val file = File(book.filePath)
        if (!file.exists()) return Result.failure(IllegalStateException(context.getString(R.string.rd_err_file_missing)))
        if (book.format == "pdf") {
            val publication = library.openPublication(file) ?: return Result.failure(IllegalStateException(context.getString(R.string.rd_err_pdf_open)))
            val locator = book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            val session = ReaderSession(
                book, publication, null, locator, simplified = false, translation = TranslationMode.OFF,
                translationPair = book.translationPair(), layout = PageLayout.HORIZONTAL_LTR,
                pdfFactory = PdfNavigatorFactory(publication, PdfiumEngineProvider()),
            )
            sessions[bookId] = session
            library.touch(bookId)
            return Result.success(session)
        }
        val simplified = book.showsSimplified(settings.reader.first().toSimplified)
        val mode = book.translationModeValue()
        val pair = book.translationPair()
        val translator = if (mode == TranslationMode.OFF) null
        else ChapterTranslator(library.translationCacheDir(book.id), pair.first, pair.second, mode)
        // The layout depends on the opened publication; chapters are only read later.
        var layout = PageLayout.HORIZONTAL_LTR
        val publication = library.openPublication(file, toSimplified = simplified, translator = translator, layout = { layout })
            ?: return Result.failure(IllegalStateException(context.getString(R.string.rd_err_book_open)))
        layout = book.pageLayoutValue(publication)
        val locator = book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        val session = ReaderSession(book, publication, EpubNavigatorFactory(publication), locator, simplified, mode, pair, layout)
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
