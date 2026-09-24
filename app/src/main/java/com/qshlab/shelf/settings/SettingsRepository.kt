package com.qshlab.shelf.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ViewMode { GRID, LIST, COMPACT }

enum class LibraryTab(val label: String) {
    SHELF("书架"),
    RECENT_READ("最近阅读"),
    RECENT_ADDED("最近添加"),
    AUTHORS("作者"),
    SERIES("系列"),
    FINISHED("已读完"),
}

enum class SortOrder(val label: String) {
    RECENT_READ("最近阅读"),
    RECENT_ADDED("最近添加"),
    TITLE("书名"),
    AUTHOR("作者"),
    PROGRESS("阅读进度"),
}

data class LibrarySettings(
    val viewMode: ViewMode = ViewMode.GRID,
    val tab: LibraryTab = LibraryTab.SHELF,
    val sort: SortOrder = SortOrder.RECENT_READ,
    /** Show each series as one stack on the 书架 tab. */
    val stackSeries: Boolean = true,
)

enum class ReaderTheme(val label: String) { LIGHT("日间"), SEPIA("护眼"), DARK("夜间") }

enum class ReaderFont(val label: String) { DEFAULT("默认"), SERIF("衬线"), SANS("无衬线") }

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontSize: Double = 1.0,
    val lineHeight: Double = 1.6,
    val pageMargins: Double = 1.0,
    val font: ReaderFont = ReaderFont.DEFAULT,
    val scroll: Boolean = false,
    val publisherStyles: Boolean = false,
    /** Global default for showing 繁體 books in 简体; books can override it. */
    val toSimplified: Boolean = false,
    val volumeKeysTurnPages: Boolean = true,
    /** Readium lays out zh-TW / ja books vertically by default; most readers expect horizontal. */
    val verticalText: Boolean = false,
    val ttsSpeed: Double = 1.0,
    /** Chosen TTS voice id per language code, e.g. "zh" -> "cmn-cn-x-ccc-local". */
    val ttsVoices: Map<String, String> = emptyMap(),
)

class SettingsRepository(private val store: DataStore<Preferences>) {
    private object K {
        val viewMode = stringPreferencesKey("library.viewMode")
        val tab = stringPreferencesKey("library.tab")
        val sort = stringPreferencesKey("library.sort")
        val stackSeries = booleanPreferencesKey("library.stackSeries")

        val theme = stringPreferencesKey("reader.theme")
        val fontSize = doublePreferencesKey("reader.fontSize")
        val lineHeight = doublePreferencesKey("reader.lineHeight")
        val pageMargins = doublePreferencesKey("reader.pageMargins")
        val font = stringPreferencesKey("reader.font")
        val scroll = booleanPreferencesKey("reader.scroll")
        val publisherStyles = booleanPreferencesKey("reader.publisherStyles")
        val toSimplified = booleanPreferencesKey("reader.toSimplified")
        val volumeKeys = booleanPreferencesKey("reader.volumeKeys")
        val verticalText = booleanPreferencesKey("reader.verticalText")
        val ttsSpeed = doublePreferencesKey("reader.ttsSpeed")
        val ttsVoices = stringPreferencesKey("reader.ttsVoices")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    val library: Flow<LibrarySettings> = store.data.map { p ->
        val d = LibrarySettings()
        LibrarySettings(
            viewMode = p.enum(K.viewMode, d.viewMode),
            tab = p.enum(K.tab, d.tab),
            sort = p.enum(K.sort, d.sort),
            stackSeries = p[K.stackSeries] ?: d.stackSeries,
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
            verticalText = p[K.verticalText] ?: d.verticalText,
            ttsSpeed = p[K.ttsSpeed] ?: d.ttsSpeed,
            ttsVoices = p[K.ttsVoices]?.split('\n')?.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap() ?: d.ttsVoices,
        )
    }

    suspend fun updateLibrary(s: LibrarySettings) = store.edit { p ->
        p[K.viewMode] = s.viewMode.name
        p[K.tab] = s.tab.name
        p[K.sort] = s.sort.name
        p[K.stackSeries] = s.stackSeries
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
        p[K.verticalText] = s.verticalText
        p[K.ttsSpeed] = s.ttsSpeed
        p[K.ttsVoices] = s.ttsVoices.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }
}
