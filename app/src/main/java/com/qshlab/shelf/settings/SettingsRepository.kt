package com.qshlab.shelf.settings

import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import com.qshlab.shelf.R
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ViewMode { GRID, LIST, COMPACT }

/** How the bookshelf is organized. */
enum class GroupBy(@StringRes val label: Int) {
    SERIES(R.string.group_series),
    BOOKS(R.string.group_books),
    AUTHORS(R.string.group_authors),
}

enum class SortOrder(@StringRes val label: Int) {
    RECENT_READ(R.string.sort_recent_read),
    RECENT_ADDED(R.string.sort_recent_added),
    TITLE(R.string.sort_title),
    AUTHOR(R.string.sort_author),
    PROGRESS(R.string.sort_progress),
}

enum class StatusFilter(@StringRes val label: Int) {
    ALL(R.string.status_all),
    READING(R.string.status_reading),
    WANT(R.string.status_want),
    UNREAD(R.string.status_unread),
    FINISHED(R.string.status_finished),
}

data class LibrarySettings(
    val viewMode: ViewMode = ViewMode.GRID,
    val groupBy: GroupBy = GroupBy.SERIES,
    val sort: SortOrder = SortOrder.RECENT_READ,
    val status: StatusFilter = StatusFilter.ALL,
    /** Language key from [com.qshlab.shelf.data.languageKey]; null = all. */
    val language: String? = null,
    /** Original file format ("epub", "txt"); null = all. */
    val format: String? = null,
) {
    val filtering get() = status != StatusFilter.ALL || language != null || format != null
}

enum class ReaderTheme(@StringRes val label: Int) { LIGHT(R.string.theme_light), SEPIA(R.string.theme_sepia), DARK(R.string.theme_dark) }

enum class ReaderFont(@StringRes val label: Int) { SANS(R.string.font_sans), SERIF(R.string.font_serif), ORIGINAL(R.string.font_original) }

/** Text direction and page order, set per book. */
enum class PageLayout(@StringRes val label: Int) {
    HORIZONTAL_LTR(R.string.layout_horizontal_ltr),
    HORIZONTAL_RTL(R.string.layout_horizontal_rtl),
    VERTICAL_RTL(R.string.layout_vertical_rtl),
}

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontSize: Double = 1.0,
    val lineHeight: Double = 1.6,
    val pageMargins: Double = 1.0,
    val font: ReaderFont = ReaderFont.SANS,
    val scroll: Boolean = false,
    val publisherStyles: Boolean = false,
    /** Global default for showing 繁體 books in 简体; books can override it. */
    val toSimplified: Boolean = false,
    val volumeKeysTurnPages: Boolean = true,
    val ttsSpeed: Double = 1.0,
    /**
     * Chosen voice per base language ("zh", "en"): an AI voice ("ai:<model>:<sid>") or a system
     * TTS voice id.
     */
    val ttsVoices: Map<String, String> = emptyMap(),
    /** PDF: scroll pages continuously instead of one at a time. */
    val pdfScroll: Boolean = false,
    /** PDF: fit the page width (else the whole page). */
    val pdfFitWidth: Boolean = true,
)

data class AppSettings(
    /** Format keys ([com.qshlab.shelf.data.BookScanner.FORMATS]) looked for when scanning the phone. */
    val scanFormats: Set<String> = com.qshlab.shelf.data.BookScanner.DEFAULT_FORMATS,
    /** TXT files smaller than this are skipped by the scan (logs, readmes…). */
    val scanTxtMinKb: Int = 30,
    /** Series suggestions the user dismissed (sorted book ids joined by ","). */
    val dismissedSuggestions: Set<String> = emptySet(),
    /** Prefer on-device AI voices for reading aloud when one is installed. */
    val preferAiVoice: Boolean = true,
)

class SettingsRepository(private val store: DataStore<Preferences>) {
    private object K {
        val viewMode = stringPreferencesKey("library.viewMode")
        val groupBy = stringPreferencesKey("library.groupBy")
        val sort = stringPreferencesKey("library.sort")
        val status = stringPreferencesKey("library.status")
        val language = stringPreferencesKey("library.language")
        val format = stringPreferencesKey("library.format")

        val theme = stringPreferencesKey("reader.theme")
        val fontSize = doublePreferencesKey("reader.fontSize")
        val lineHeight = doublePreferencesKey("reader.lineHeight")
        val pageMargins = doublePreferencesKey("reader.pageMargins")
        val font = stringPreferencesKey("reader.font")
        val scroll = booleanPreferencesKey("reader.scroll")
        val publisherStyles = booleanPreferencesKey("reader.publisherStyles")
        val toSimplified = booleanPreferencesKey("reader.toSimplified")
        val volumeKeys = booleanPreferencesKey("reader.volumeKeys")
        val ttsSpeed = doublePreferencesKey("reader.ttsSpeed")
        val ttsVoices = stringPreferencesKey("reader.ttsVoices")
        val pdfScroll = booleanPreferencesKey("reader.pdfScroll")
        val pdfFitWidth = booleanPreferencesKey("reader.pdfFitWidth")

        val scanFormats = stringPreferencesKey("app.scanFormatGroups")
        val scanTxtMinKb = intPreferencesKey("app.scanTxtMinKb")
        val dismissed = stringPreferencesKey("app.dismissedSuggestions")
        val preferAi = booleanPreferencesKey("app.preferAiVoice")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    val library: Flow<LibrarySettings> = store.data.map { p ->
        val d = LibrarySettings()
        LibrarySettings(
            viewMode = p.enum(K.viewMode, d.viewMode),
            groupBy = p.enum(K.groupBy, d.groupBy),
            sort = p.enum(K.sort, d.sort),
            status = p.enum(K.status, d.status),
            language = p[K.language]?.ifEmpty { null },
            format = p[K.format]?.ifEmpty { null },
        )
    }

    val reader: Flow<ReaderSettings> = store.data.map { p ->
        val d = ReaderSettings()
        ReaderSettings(
            theme = p.enum(K.theme, d.theme),
            fontSize = p[K.fontSize] ?: d.fontSize,
            lineHeight = p[K.lineHeight] ?: d.lineHeight,
            pageMargins = p[K.pageMargins] ?: d.pageMargins,
            font = p.enum(K.font, d.font),
            scroll = p[K.scroll] ?: d.scroll,
            publisherStyles = p[K.publisherStyles] ?: d.publisherStyles,
            toSimplified = p[K.toSimplified] ?: d.toSimplified,
            volumeKeysTurnPages = p[K.volumeKeys] ?: d.volumeKeysTurnPages,
            ttsSpeed = p[K.ttsSpeed] ?: d.ttsSpeed,
            ttsVoices = p[K.ttsVoices]?.split('\n')?.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap() ?: d.ttsVoices,
            pdfScroll = p[K.pdfScroll] ?: d.pdfScroll,
            pdfFitWidth = p[K.pdfFitWidth] ?: d.pdfFitWidth,
        )
    }

    val app: Flow<AppSettings> = store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            scanFormats = p[K.scanFormats]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: d.scanFormats,
            scanTxtMinKb = p[K.scanTxtMinKb] ?: d.scanTxtMinKb,
            dismissedSuggestions = p[K.dismissed]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: d.dismissedSuggestions,
            preferAiVoice = p[K.preferAi] ?: d.preferAiVoice,
        )
    }

    suspend fun updateLibrary(s: LibrarySettings) = store.edit { p ->
        p[K.viewMode] = s.viewMode.name
        p[K.groupBy] = s.groupBy.name
        p[K.sort] = s.sort.name
        p[K.status] = s.status.name
        p[K.language] = s.language ?: ""
        p[K.format] = s.format ?: ""
    }

    suspend fun updateReader(s: ReaderSettings) = store.edit { p ->
        p[K.theme] = s.theme.name
        p[K.fontSize] = s.fontSize
        p[K.lineHeight] = s.lineHeight
        p[K.pageMargins] = s.pageMargins
        p[K.font] = s.font.name
        p[K.scroll] = s.scroll
        p[K.publisherStyles] = s.publisherStyles
        p[K.toSimplified] = s.toSimplified
        p[K.volumeKeys] = s.volumeKeysTurnPages
        p[K.ttsSpeed] = s.ttsSpeed
        p[K.ttsVoices] = s.ttsVoices.entries.joinToString("\n") { "${it.key}=${it.value}" }
        p[K.pdfScroll] = s.pdfScroll
        p[K.pdfFitWidth] = s.pdfFitWidth
    }

    suspend fun updateApp(s: AppSettings) = store.edit { p ->
        p[K.scanFormats] = s.scanFormats.joinToString(",")
        p[K.scanTxtMinKb] = s.scanTxtMinKb
        p[K.dismissed] = s.dismissedSuggestions.joinToString("\n")
        p[K.preferAi] = s.preferAiVoice
    }
}
