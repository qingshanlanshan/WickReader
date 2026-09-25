package com.qshlab.shelf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.data.Book
import com.qshlab.shelf.data.SeriesSuggester

/** The longest title beginning shared by all [books], cleaned up — a starting point for a name. */
private fun guessName(books: List<Book>): String {
    books.mapNotNull { it.series }.distinct().singleOrNull()?.let { return it }
    val stems = books.map { SeriesSuggester.stem(it.title).first }
    if (stems.distinct().size == 1) return stems.first()
    var prefix = books.first().title
    for (b in books.drop(1)) prefix = prefix.commonPrefixWith(b.title)
    return prefix.trim(' ', '·', '：', ':', '-', '—', '（', '(', '与', '之', '的')
}

/**
 * Puts several books into one series. The list order becomes the volume order; arrows move a
 * book up or down.
 */
@Composable
fun SeriesDialog(
    books: List<Book>,
    existingSeries: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String?, ordered: List<Book>, numbers: List<Double?>) -> Unit,
) {
    var name by remember { mutableStateOf(guessName(books)) }
    var order by remember {
        mutableStateOf(books.sortedWith(compareBy<Book> { it.seriesIndex ?: SeriesSuggester.stem(it.title).second ?: Double.MAX_VALUE }.thenBy { it.title }))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设为系列") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("系列名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val chips = existingSeries.filter { it != name && (name.isBlank() || it.contains(name.take(2))) }.take(8)
                if (chips.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(chips) { s -> SuggestionChip(onClick = { name = s }, label = { Text(s, maxLines = 1) }) }
                    }
                }
                Text("按顺序编号（用箭头调整）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(order, key = { _, b -> b.id }) { i, b ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", Modifier.width(28.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(b.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            IconButton(enabled = i > 0, onClick = { order = order.toMutableList().apply { add(i - 1, removeAt(i)) } }) {
                                Icon(Icons.Filled.KeyboardArrowUp, "上移")
                            }
                            IconButton(enabled = i < order.lastIndex, onClick = { order = order.toMutableList().apply { add(i + 1, removeAt(i)) } }) {
                                Icon(Icons.Filled.KeyboardArrowDown, "下移")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { onConfirm(name, order, order.indices.map { it + 1.0 }) }) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (books.any { it.series != null }) TextButton(onClick = { onConfirm(null, order, order.map { null }) }) { Text("移出系列") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** Title-based series guesses; nothing changes until the user confirms one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsScreen(vm: LibraryViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                title = { Text("整理系列") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "根据书名猜出的系列。确认前可以改名、去掉不属于的书；也可以在书架上长按选中几本书，手动「设为系列」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.suggestions.isEmpty()) item {
                Text("暂时没有新的建议", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.suggestions, key = { it.key }) { s -> SuggestionCard(s, vm) }
        }
    }
}

@Composable
private fun SuggestionCard(s: SeriesSuggester.Suggestion, vm: LibraryViewModel) {
    var name by remember(s.key) { mutableStateOf(s.name) }
    var checked by remember(s.key) { mutableStateOf(s.members.map { it.id }.toSet()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s.joinsExisting) {
                Text("加入已有系列「${s.name}」", style = MaterialTheme.typography.titleSmall)
            } else {
                OutlinedTextField(name, { name = it }, label = { Text("系列名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            s.members.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = m.id in checked, onCheckedChange = { on -> checked = if (on) checked + m.id else checked - m.id })
                    Text(
                        (m.index?.let { "第${formatIndex(it)}卷 · " } ?: "") + m.title,
                        Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { vm.dismissSuggestion(s) }) { Text("忽略") }
                Button(
                    enabled = name.isNotBlank() && (checked.size >= 2 || (s.joinsExisting && checked.isNotEmpty())),
                    onClick = { vm.acceptSuggestion(s, s.members.map { it.id }.filter { it in checked }, name) },
                ) { Text("确认") }
            }
        }
    }
}
