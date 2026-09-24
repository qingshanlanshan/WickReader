package com.qshlab.shelf.reader

import com.qshlab.shelf.settings.ReaderFont
import com.qshlab.shelf.settings.ReaderSettings
import com.qshlab.shelf.settings.ReaderTheme
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

@OptIn(ExperimentalReadiumApi::class)
fun ReaderSettings.toEpubPreferences(): EpubPreferences = EpubPreferences(
    theme = when (theme) {
        ReaderTheme.LIGHT -> Theme.LIGHT
        ReaderTheme.SEPIA -> Theme.SEPIA
        ReaderTheme.DARK -> Theme.DARK
    },
    fontSize = fontSize,
    fontFamily = when (font) {
        ReaderFont.DEFAULT -> null
        ReaderFont.SERIF -> FontFamily.SERIF
        ReaderFont.SANS -> FontFamily.SANS_SERIF
    },
    // Line height and margins only apply when the publisher's own CSS is off.
    publisherStyles = publisherStyles,
    lineHeight = lineHeight,
    pageMargins = pageMargins,
    scroll = scroll,
    verticalText = verticalText,
    // zh-TW / ja books default to right-to-left page order (for vertical text). With horizontal
    // text that makes "start of chapter" land on the wrong page, so force left-to-right.
    readingProgression = if (verticalText) null else ReadingProgression.LTR,
)
