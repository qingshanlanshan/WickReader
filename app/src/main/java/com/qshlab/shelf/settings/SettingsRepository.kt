package com.qshlab.shelf.settings

import androidx.datastore.core.DataStore
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
enum class GroupBy(val label: String) {
    SERIES("按系列"),
    BOOKS("按书"),
    AUTHORS("按作者"),
}

enum class SortOrder(val label: String) {
    RECENT_READ("最近阅读"),
    RECENT_ADDED("最近添加"),
    TITLE("书名"),
    AUTHOR("作者"),
    PROGRESS("阅读进度"),
}

enum class StatusFilter(val label: String) {
    ALL("全部"),
    READING("在读"),
    WANT("想读"),
    UNREAD("未读"),
    FINISHED("已读完"),
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

enum class ReaderTheme(val label: String) { LIGHT("日间"), SEPIA("护眼"), DARK("夜间") }

enum class ReaderFont(val label: String) { SANS("黑体"), SERIF("宋体"), ORIGINAL("书籍原字体") }

/** Text direction and page order, set per book. */
enum class PageLayout(val label: String) {
    HORIZONTAL_LTR("横排（下一页在右）"),
    VERTICAL_RTL("竖排 · 日式（从右往左读，下一页在左）"),
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
)

data class AppSettings(
    /** File formats looked for when scanning the phone. */
    val scanFormats: Set<String> = setOf("epub", "txt"),
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

        val scanFormats = stringPreferencesKey("app.scanFormats")
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
    }

    suspend fun updateApp(s: AppSettings) = store.edit { p ->
        p[K.scanFormats] = s.scanFormats.joinToString(",")
        p[K.scanTxtMinKb] = s.scanTxtMinKb
        p[K.dismissed] = s.dismissedSuggestions.joinToString("\n")
        p[K.preferAi] = s.preferAiVoice
    }
}
