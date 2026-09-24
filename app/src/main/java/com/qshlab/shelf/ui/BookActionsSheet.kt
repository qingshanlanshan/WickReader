package com.qshlab.shelf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.data.Book

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookActionsSheet(
    book: Book,
    existingSeries: List<String>,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onSave: (Book) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(book, Modifier.width(48.dp), corner = 4.dp, small = true)
            Column(Modifier.padding(start = 14.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(book.author, book.format.uppercase(), "%.1f MB".format(book.fileSize / 1e6)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ListItem(headlineContent = { Text("阅读") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) }, modifier = Modifier.clickable(onClick = onOpen))
        ListItem(headlineContent = { Text("编辑信息与系列") }, leadingContent = { Icon(Icons.Filled.Edit, null) }, modifier = Modifier.clickable { editing = true })
        ListItem(
            headlineContent = { Text(if (book.finished) "标记为未读完" else "标记为已读完") },
            leadingContent = { Icon(if (book.finished) Icons.Filled.RadioButtonUnchecked else Icons.Filled.CheckCircle, null) },
            modifier = Modifier.clickable { onSave(book.copy(finished = !book.finished)) },
        )
        ListItem(headlineContent = { Text("删除") }, leadingContent = { Icon(Icons.Filled.Delete, null) }, modifier = Modifier.clickable { confirmDelete = true })
        Column(Modifier.padding(bottom = 24.dp)) {}
    }

    if (editing) EditBookDialog(book, existingSeries, onDismiss = { editing = false }, onSave = onSave)

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这本书？") },
            text = { Text("《${book.title}》会从书架和应用存储中删除，阅读进度也会一起删除。") },
            confirmButton = { TextButton(onClick = onDelete) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun EditBookDialog(book: Book, existingSeries: List<String>, onDismiss: () -> Unit, onSave: (Book) -> Unit) {
    var title by remember { mutableStateOf(book.title) }
    var author by remember { mutableStateOf(book.author ?: "") }
    var series by remember { mutableStateOf(book.series ?: "") }
    var index by remember { mutableStateOf(book.seriesIndex?.let { formatIndex(it) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("书名") }, singleLine = true)
                OutlinedTextField(author, { author = it }, label = { Text("作者") }, singleLine = true)
                OutlinedTextField(series, { series = it }, label = { Text("系列（留空 = 不属于系列）") }, singleLine = true)
                val suggestions = existingSeries.filter { it != series && (series.isBlank() || it.contains(series, ignoreCase = true)) }.take(8)
                if (suggestions.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(suggestions) { s -> SuggestionChip(onClick = { series = s }, label = { Text(s, maxLines = 1) }) }
                    }
                }
                OutlinedTextField(
                    index, { index = it }, label = { Text("第几卷") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    book.copy(
                        title = title.trim().ifBlank { book.title },
                        author = author.trim().ifBlank { null },
                        series = series.trim().ifBlank { null },
                        seriesIndex = index.trim().toDoubleOrNull(),
                        seriesManual = true,
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
