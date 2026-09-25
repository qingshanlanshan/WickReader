package com.qshlab.shelf.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.qshlab.shelf.settings.PageLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.R
import com.qshlab.shelf.data.Annotation
import com.qshlab.shelf.settings.ReaderFont
import com.qshlab.shelf.settings.ReaderSettings
import com.qshlab.shelf.settings.ReaderTheme
import com.qshlab.shelf.translate.Translation
import com.qshlab.shelf.translate.TranslationMode
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression

private fun ReaderTheme.paper() = when (this) {
    ReaderTheme.LIGHT -> Color(0xFFFFFFFF)
    ReaderTheme.SEPIA -> Color(0xFFFAF4E8)
    ReaderTheme.DARK -> Color(0xFF1E1E1E)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderOverlay(
    title: String,
    publication: Publication,
    locator: Locator?,
    settings: ReaderSettings,
    visible: Boolean,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onGoToLink: (Link) -> Unit,
    onGoToLocator: (Locator) -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit,
    isTraditional: Boolean,
    bookSimplified: Boolean,
    onBookSimplifiedChange: (Boolean) -> Unit,
    translation: TranslationMode,
    translationPair: Pair<String, String>,
    onTranslationChange: (TranslationMode) -> Unit,
    onTranslationTargetChange: (String) -> Unit,
    onReadAloud: () -> Unit,
    annotations: List<Annotation>,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onGoToAnnotation: (Annotation) -> Unit,
    onDeleteAnnotation: (Annotation) -> Unit,
    onSearch: suspend (String) -> List<SearchHit>,
    onGoToSearchHit: (SearchHit) -> Unit,
    layout: PageLayout,
    layoutChoices: List<PageLayout>,
    onLayoutChange: (PageLayout) -> Unit,
    settingsOpen: Boolean,
    onSettingsOpenChange: (Boolean) -> Unit,
    /** Readium "pages" (positions) in the book, once counted. */
    pageCount: Int?,
) {
    val dark = settings.theme == ReaderTheme.DARK
    val colors = if (dark) darkColorScheme() else lightColorScheme(surface = settings.theme.paper())
    var sheet by remember { mutableStateOf<String?>(null) }
    val toc = remember(publication) { flattenToc(publication.tableOfContents) }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = colors) {
        Box(Modifier.fillMaxSize()) {
            // Tap outside the bars to close them.
            if (visible || settingsOpen) Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { onSettingsOpenChange(false); onDismiss() })

            AnimatedVisibility(visible && !settingsOpen, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.rd_back)) }
                        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = onReadAloud) { Icon(Icons.Filled.Headphones, stringResource(R.string.rd_read_aloud)) }
                        IconButton(onClick = { sheet = "search" }) { Icon(Icons.Filled.Search, stringResource(R.string.rd_search)) }
                        IconButton(onClick = onToggleBookmark) {
                            Icon(if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, stringResource(if (bookmarked) R.string.rd_bookmark_remove else R.string.rd_bookmark_add))
                        }
                        IconButton(onClick = { sheet = "toc" }) { Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.rd_contents)) }
                        IconButton(onClick = { onSettingsOpenChange(true) }) { Icon(Icons.Filled.TextFields, stringResource(R.string.rd_reading_settings)) }
                    }
                }
            }

            AnimatedVisibility(
                visible && !settingsOpen,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        val progress = (locator?.locations?.totalProgression ?: 0.0).toFloat()
                        var dragging by remember { mutableStateOf<Float?>(null) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(locator?.title ?: "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            val page = if (pageCount != null && pageCount > 0) {
                                val p = dragging?.let { (it * (pageCount - 1)).roundToInt() + 1 } ?: locator?.locations?.position
                                p?.coerceIn(1, pageCount)
                            } else null
                            val percent = ((dragging ?: progress) * 100).roundToInt()
                            Text(
                                if (page != null && pageCount != null) stringResource(R.string.rd_page_progress, page, pageCount, percent) else "$percent%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        // With the next page on the left, the book runs right-to-left along the slider too.
                        CompositionLocalProvider(LocalLayoutDirection provides if (layout == PageLayout.HORIZONTAL_LTR) LayoutDirection.Ltr else LayoutDirection.Rtl) {
                        Slider(
                            value = dragging ?: progress,
                            onValueChange = { dragging = it },
                            onValueChangeFinished = {
                                val target = dragging
                                dragging = null
                                if (target != null) scope.launch {
                                    publication.locateProgression(target.toDouble())?.let(onGoToLocator)
                                }
                            },
                        )
                        }
                    }
                }
            }

            // Settings as a side panel: the page stays visible on the left while you adjust it.
            AnimatedVisibility(
                settingsOpen,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
            ) {
                BoxWithConstraints {
                    val width = (maxWidth * 0.5f).coerceIn(200.dp, 380.dp)
                    Surface(Modifier.width(width).fillMaxHeight(), tonalElevation = 2.dp, shadowElevation = 8.dp) {
                        ReaderSettingsPanel(
                            settings, onSettingsChange, isTraditional, bookSimplified, onBookSimplifiedChange,
                            translation, translationPair, onTranslationChange, onTranslationTargetChange, layout, layoutChoices, onLayoutChange,
                            onClose = { onSettingsOpenChange(false) },
                        )
                    }
                }
            }
        }

        if (sheet == "toc") {
            NavigationSheet(
                toc = toc,
                currentHref = locator?.href?.toString()?.substringBefore('#'),
                annotations = annotations,
                onGoToLink = { sheet = null; onGoToLink(it) },
                onGoToAnnotation = { sheet = null; onGoToAnnotation(it) },
                onDeleteAnnotation = onDeleteAnnotation,
                onDismiss = { sheet = null },
            )
        }

        if (sheet == "search") {
            SearchSheet(onSearch = onSearch, onGo = { sheet = null; onGoToSearchHit(it) }, onDismiss = { sheet = null })
        }

    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderSettingsPanel(
    s: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    isTraditional: Boolean,
    bookSimplified: Boolean,
    onBookSimplifiedChange: (Boolean) -> Unit,
    translation: TranslationMode,
    translationPair: Pair<String, String>,
    onTranslationChange: (TranslationMode) -> Unit,
    onTranslationTargetChange: (String) -> Unit,
    layout: PageLayout,
    layoutChoices: List<PageLayout>,
    onLayoutChange: (PageLayout) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rd_reading_settings), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.rd_close)) }
        }

        Label(stringResource(R.string.rd_theme))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReaderTheme.entries.forEach { t ->
                val selected = s.theme == t
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(t.paper())
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { onChange(s.copy(theme = t)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(t.label).take(1), color = if (t == ReaderTheme.DARK) Color(0xFFDDDDDD) else Color(0xFF333333), style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Stepper(stringResource(R.string.rd_font_size), s.fontSize, 0.6..2.5, 0.1, { "${(it * 100).roundToInt()}%" }) { onChange(s.copy(fontSize = it)) }

        Label(stringResource(R.string.rd_font))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReaderFont.entries.forEach { f ->
                FilterChip(selected = s.font == f, onClick = { onChange(s.copy(font = f)) }, label = { Text(stringResource(f.label)) })
            }
        }

        Stepper(stringResource(R.string.rd_line_spacing), s.lineHeight, 1.0..2.4, 0.1, { "%.1f".format(it) }, enabled = !s.publisherStyles) { onChange(s.copy(lineHeight = it)) }
        Stepper(stringResource(R.string.rd_margins), s.pageMargins, 0.2..2.5, 0.1, { "%.1f".format(it) }, enabled = !s.publisherStyles) { onChange(s.copy(pageMargins = it)) }

        if (layoutChoices.size > 1) Label(stringResource(R.string.rd_layout_this_book))
        if (layoutChoices.size > 1) layoutChoices.forEach { l ->
            Row(Modifier.fillMaxWidth().clickable { if (l != layout) onLayoutChange(l) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = l == layout, onClick = { if (l != layout) onLayoutChange(l) })
                Text(stringResource(l.label), style = MaterialTheme.typography.bodyMedium)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        SwitchRow(stringResource(R.string.rd_scroll), s.scroll) { onChange(s.copy(scroll = it)) }
        SwitchRow(stringResource(R.string.rd_publisher_styles), s.publisherStyles) { onChange(s.copy(publisherStyles = it)) }
        SwitchRow(stringResource(R.string.rd_volume_keys), s.volumeKeysTurnPages) { onChange(s.copy(volumeKeysTurnPages = it)) }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        // 繁简 only matters for Chinese books.
        if (translationPair.first == "zh") {
            Label(stringResource(R.string.rd_chinese_conversion))
            SwitchRow(stringResource(if (isTraditional) R.string.rd_book_simplified_detected else R.string.rd_book_simplified), bookSimplified) { onBookSimplifiedChange(it) }
            SwitchRow(stringResource(R.string.rd_default_simplified), s.toSimplified) { onChange(s.copy(toSimplified = it)) }
        }

        Label(stringResource(R.string.rd_translate_book))
        val (from, to) = translationPair
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.tr_translate_from, Translation.nameOf(from)), Modifier.align(Alignment.CenterVertically).padding(end = 2.dp), style = MaterialTheme.typography.bodyMedium)
            Translation.TARGETS.filter { it != from }.forEach { lang ->
                FilterChip(selected = to == lang, onClick = { if (lang != to) onTranslationTargetChange(lang) }, label = { Text(Translation.nameOf(lang)) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TranslationMode.entries.forEach { m ->
                FilterChip(selected = translation == m, onClick = { if (m != translation) onTranslationChange(m) }, label = { Text(stringResource(m.label)) })
            }
        }
    }
}

/** A value with −/+ buttons: each tap applies at once, so the page reflows while you watch. */
@Composable
private fun Stepper(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    format: (Double) -> String,
    enabled: Boolean = true,
    onChange: (Double) -> Unit,
) {
    fun snap(v: Double) = (Math.round(v / step) * step).coerceIn(range.start, range.endInclusive)
    Column(Modifier.padding(top = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onChange(snap(value - step)) }, enabled = enabled && value > range.start + 1e-6) { Icon(Icons.Filled.Remove, stringResource(R.string.rd_decrease)) }
            Text(format(value), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
            FilledTonalIconButton(onClick = { onChange(snap(value + step)) }, enabled = enabled && value < range.endInclusive - 1e-6) { Icon(Icons.Filled.Add, stringResource(R.string.rd_increase)) }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 6.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
