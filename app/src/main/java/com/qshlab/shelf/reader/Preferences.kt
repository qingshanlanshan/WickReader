package com.qshlab.shelf.reader

import com.qshlab.shelf.settings.PageLayout
import com.qshlab.shelf.settings.ReaderFont
import com.qshlab.shelf.settings.ReaderSettings
import com.qshlab.shelf.settings.ReaderTheme
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

@OptIn(ExperimentalReadiumApi::class)
fun ReaderSettings.toEpubPreferences(layout: PageLayout): EpubPreferences = EpubPreferences(
    theme = when (theme) {
        ReaderTheme.LIGHT -> Theme.LIGHT
        ReaderTheme.SEPIA -> Theme.SEPIA
        ReaderTheme.DARK -> Theme.DARK
    },
    fontSize = fontSize,
    // A concrete family makes every book use the same font (Readium only overrides the book's
    // fonts when one is set).
    fontFamily = when (font) {
        ReaderFont.SANS -> FontFamily.SANS_SERIF
        ReaderFont.SERIF -> FontFamily.SERIF
        ReaderFont.ORIGINAL -> null
    },
    // Line height and margins only apply when the publisher's own CSS is off.
    publisherStyles = publisherStyles,
    lineHeight = lineHeight,
    pageMargins = pageMargins,
    scroll = scroll,
    verticalText = layout == PageLayout.VERTICAL_RTL,
    // Always explicit: zh-TW / ja books otherwise default to right-to-left even with horizontal text.
    readingProgression = if (layout == PageLayout.HORIZONTAL_LTR) ReadingProgression.LTR else ReadingProgression.RTL,
)
