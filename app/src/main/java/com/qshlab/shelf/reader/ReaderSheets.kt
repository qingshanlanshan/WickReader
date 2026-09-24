package com.qshlab.shelf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.data.Annotation
import com.qshlab.shelf.data.AnnotationType
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Link

data class TocEntry(val link: Link, val depth: Int)

fun flattenToc(links: List<Link>, depth: Int = 0): List<TocEntry> =
    links.flatMap { listOf(TocEntry(it, depth)) + flattenToc(it.children, depth + 1) }

val HIGHLIGHT_COLORS = listOf(0xFFFFD666.toInt(), 0xFF9BE39B.toInt(), 0xFF8EC5FF.toInt(), 0xFFFFA8C5.toInt())

/** 目录 / 书签 / 笔记 in one sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationSheet(
    toc: List<TocEntry>,
    currentHref: String?,
    annotations: List<Annotation>,
    onGoToLink: (Link) -> Unit,
    onGoToAnnotation: (Annotation) -> Unit,
    onDeleteAnnotation: (Annotation) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val bookmarks = annotations.filter { it.type == AnnotationType.BOOKMARK }
    val highlights = annotations.filter { it.type == AnnotationType.HIGHLIGHT }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("目录", "书签 ${bookmarks.size}", "笔记 ${highlights.size}").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> {
                val currentIndex = toc.indexOfFirst { it.link.href.toString().substringBefore('#') == currentHref }
                val state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, currentIndex - 3))
                if (toc.isEmpty()) Text("这本书没有目录", Modifier.padding(20.dp))
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(toc) { i, entry ->
                        val isCurrent = i == currentIndex
                        Text(
                            entry.link.title ?: entry.link.href.toString(),
                            Modifier
                                .fillMaxWidth()
                                .clickable { onGoToLink(entry.link) }
                                .background(if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .padding(start = (20 + 16 * entry.depth).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            else -> {
                val list = if (tab == 1) bookmarks else highlights
                if (list.isEmpty()) {
                    Text(
                        if (tab == 1) "还没有书签。阅读时点顶部的书签按钮添加。" else "还没有笔记。选中文字后点「高亮」或「笔记」。",
                        Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    items(list, key = { it.id }) { a -> AnnotationRow(a, { onGoToAnnotation(a) }, { onDeleteAnnotation(a) }) }
                }
            }
        }
    }
}

@Composable
private fun AnnotationRow(a: Annotation, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (a.type == AnnotationType.HIGHLIGHT) Box(Modifier.size(10.dp).background(Color(a.color), CircleShape))
        Column(Modifier.weight(1f).padding(start = if (a.type == AnnotationType.HIGHLIGHT) 12.dp else 0.dp)) {
            Text(
                listOfNotNull(a.chapter, "${(a.progress * 100).roundToInt()}%").joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(a.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            a.note?.takeIf { it.isNotBlank() }?.let {
                Text("📝 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

data class SearchHit(val chapter: String?, val before: String, val match: String, val after: String, val locator: org.readium.r2.shared.publication.Locator)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    onSearch: suspend (String) -> List<SearchHit>,
    onGo: (SearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val run = {
        if (query.isNotBlank()) {
            busy = true
            scope.launch {
                results = onSearch(query.trim())
                busy = false
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("在书中搜索") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { run() }),
                trailingIcon = { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else TextButton(onClick = { run() }) { Text("搜索") } },
                modifier = Modifier.fillMaxWidth(),
            )
            results?.let { Text(if (it.isEmpty()) "没有找到" else "找到 ${it.size} 处${if (it.size >= 300) "（只显示前 300 处）" else ""}", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelMedium) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(bottom = 16.dp)) {
            items(results.orEmpty()) { hit ->
                Column(Modifier.fillMaxWidth().clickable { onGo(hit) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    hit.chapter?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        buildAnnotatedString {
                            append(hit.before.takeLast(30))
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x55FFD666))) { append(hit.match) }
                            append(hit.after.take(40))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Edit a tapped highlight: note, color, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightSheet(a: Annotation, onSave: (Annotation) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var note by remember(a.id) { mutableStateOf(a.note ?: "") }
    var color by remember(a.id) { mutableIntStateOf(a.color) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(a.text, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HIGHLIGHT_COLORS.forEach { c ->
                    Box(
                        Modifier.size(32.dp).background(Color(c), CircleShape)
                            .then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable { color = c },
                    )
                }
            }
            OutlinedTextField(note, { note = it }, label = { Text("笔记") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete) { Text("删除高亮") }
                Box(Modifier.weight(1f))
                Button(onClick = { onSave(a.copy(note = note.ifBlank { null }, color = color)) }) { Text("保存") }
            }
        }
    }
}
