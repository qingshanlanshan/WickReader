package com.qshlab.shelf.reader

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.data.Annotation
import com.qshlab.shelf.settings.ReaderFont
import com.qshlab.shelf.settings.ReaderSettings
import com.qshlab.shelf.settings.ReaderTheme
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
    translationLabel: String,
    onTranslationChange: (TranslationMode) -> Unit,
    onReadAloud: () -> Unit,
    annotations: List<Annotation>,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onGoToAnnotation: (Annotation) -> Unit,
    onDeleteAnnotation: (Annotation) -> Unit,
    onSearch: suspend (String) -> List<SearchHit>,
    onGoToSearchHit: (SearchHit) -> Unit,
) {
    val dark = settings.theme == ReaderTheme.DARK
    val colors = if (dark) darkColorScheme() else lightColorScheme(surface = settings.theme.paper())
    var sheet by remember { mutableStateOf<String?>(null) }
    val toc = remember(publication) { flattenToc(publication.tableOfContents) }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = colors) {
        Box(Modifier.fillMaxSize()) {
            // Tap outside the bars to close them.
            if (visible) Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { onDismiss() })

            AnimatedVisibility(visible, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = onReadAloud) { Icon(Icons.Filled.Headphones, "朗读") }
                        IconButton(onClick = { sheet = "search" }) { Icon(Icons.Filled.Search, "搜索") }
                        IconButton(onClick = onToggleBookmark) {
                            Icon(if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (bookmarked) "删除书签" else "添加书签")
                        }
                        IconButton(onClick = { sheet = "toc" }) { Icon(Icons.AutoMirrored.Filled.List, "目录") }
                        IconButton(onClick = { sheet = "settings" }) { Icon(Icons.Filled.TextFields, "阅读设置") }
                    }
                }
            }

            AnimatedVisibility(
                visible,
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
                            Text("${((dragging ?: progress) * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
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

        if (sheet == "settings") {
            ModalBottomSheet(onDismissRequest = { sheet = null }) {
                ReaderSettingsPanel(settings, onSettingsChange, isTraditional, bookSimplified, onBookSimplifiedChange, translation, translationLabel, onTranslationChange)
            }
        }
    }
}

@Composable
private fun ReaderSettingsPanel(
    s: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    isTraditional: Boolean,
    bookSimplified: Boolean,
    onBookSimplifiedChange: (Boolean) -> Unit,
    translation: TranslationMode,
    translationLabel: String,
    onTranslationChange: (TranslationMode) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("阅读设置", style = MaterialTheme.typography.titleMedium)

        Label("繁简转换")
        SwitchRow(if (isTraditional) "本书显示为简体（检测到繁体）" else "本书显示为简体", bookSimplified) { onBookSimplifiedChange(it) }
        SwitchRow("繁体书默认显示为简体", s.toSimplified) { onChange(s.copy(toSimplified = it)) }

        Label("整本翻译（$translationLabel，离线）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TranslationMode.entries.forEach { m ->
                FilterChip(selected = translation == m, onClick = { if (m != translation) onTranslationChange(m) }, label = { Text(m.label) })
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        Label("主题")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderTheme.entries.forEach { t ->
                FilterChip(selected = s.theme == t, onClick = { onChange(s.copy(theme = t)) }, label = { Text(t.label) })
            }
        }

        SliderRow("字号", s.fontSize, 0.6..2.5, { "${(it * 100).roundToInt()}%" }) { onChange(s.copy(fontSize = it)) }

        Label("字体")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderFont.entries.forEach { f ->
                FilterChip(selected = s.font == f, onClick = { onChange(s.copy(font = f)) }, label = { Text(f.label) })
            }
        }

        SliderRow("行距", s.lineHeight, 1.0..2.4, { "%.1f".format(it) }, enabled = !s.publisherStyles) { onChange(s.copy(lineHeight = it)) }
        SliderRow("页边距", s.pageMargins, 0.3..2.5, { "%.1f".format(it) }, enabled = !s.publisherStyles) { onChange(s.copy(pageMargins = it)) }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        SwitchRow("滚动阅读（关闭为翻页）", s.scroll) { onChange(s.copy(scroll = it)) }
        SwitchRow("竖排（中文 / 日文书）", s.verticalText) { onChange(s.copy(verticalText = it)) }
        SwitchRow("使用书籍自带排版", s.publisherStyles) { onChange(s.copy(publisherStyles = it)) }
        SwitchRow("音量键翻页", s.volumeKeysTurnPages) { onChange(s.copy(volumeKeysTurnPages = it)) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SliderRow(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    format: (Double) -> String,
    enabled: Boolean = true,
    onChange: (Double) -> Unit,
) {
    // Local state while dragging; the setting is saved when the finger lifts.
    var local by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) { local = value.toFloat() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(format(local.toDouble()), Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
