package com.qshlab.shelf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.qshlab.shelf.data.Book

enum class GroupKind { SERIES, AUTHOR }

/** All volumes of a series (in series order) or all books by an author. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(vm: LibraryViewModel, kind: GroupKind, name: String, onBack: () -> Unit, onOpenBook: (Book) -> Unit) {
    val state by vm.state.collectAsState()
    var actionsFor by remember { mutableStateOf<Book?>(null) }
    val books = when (kind) {
        GroupKind.SERIES -> vm.seriesKey(name).let { key -> seriesOrder(state.allBooks.filter { vm.seriesKey(it.series) == key }) }
        GroupKind.AUTHOR -> state.allBooks.filter { (it.author ?: "未知作者") == name }.sortedBy { it.title }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                title = {
                    Column {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (kind == GroupKind.SERIES) "系列" else "作者") + " · ${books.size} 本",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ShelfItems(books.map { ShelfItem.Single(it) }, state.settings.viewMode, onOpenBook, {}) { actionsFor = it }
        }
    }
    actionsFor?.let { book ->
        BookActionsSheet(
            book = book,
            existingSeries = state.allBooks.mapNotNull { it.series }.distinct(),
            onDismiss = { actionsFor = null },
            onOpen = { actionsFor = null; onOpenBook(book) },
            onSave = { vm.update(it); actionsFor = null },
            onDelete = { vm.delete(book); actionsFor = null },
        )
    }
}
