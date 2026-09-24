package com.qshlab.shelf.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.data.Book
import com.qshlab.shelf.settings.LibraryTab
import com.qshlab.shelf.settings.SortOrder
import com.qshlab.shelf.settings.ViewMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Book?>(null) }
    val s = state.settings

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importFolder(uri)
    }
    val importFiles = { pickFiles.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream", "*/*")) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        if (searching) {
                            TextField(
                                value = state.query,
                                onValueChange = { vm.query.value = it },
                                placeholder = { Text("搜索书名、作者、系列") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text("书架", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) vm.query.value = "" }) {
                            Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "搜索")
                        }
                        IconButton(onClick = {
                            val next = ViewMode.entries[(s.viewMode.ordinal + 1) % ViewMode.entries.size]
                            vm.updateSettings { it.copy(viewMode = next) }
                        }) {
                            Icon(
                                when (s.viewMode) {
                                    ViewMode.GRID -> Icons.Filled.GridView
                                    ViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
                                    ViewMode.COMPACT -> Icons.Filled.ViewHeadline
                                },
                                "切换视图",
                            )
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("导入文件") }, onClick = { menu = false; importFiles() })
                                DropdownMenuItem(text = { Text("导入文件夹") }, onClick = { menu = false; pickFolder.launch(null) })
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (s.stackSeries) "系列：叠放显示 ✓" else "系列：逐本显示") },
                                    onClick = { vm.updateSettings { it.copy(stackSeries = !it.stackSeries) } },
                                )
                            }
                        }
                    },
                )
                PrimaryScrollableTabRow(selectedTabIndex = s.tab.ordinal, edgePadding = 12.dp, divider = {}) {
                    LibraryTab.entries.forEach { tab ->
                        Tab(selected = s.tab == tab, onClick = { vm.updateSettings { it.copy(tab = tab) } }, text = { Text(tab.label) })
                    }
                }
            }
        },
        floatingActionButton = {
            if (state.loaded && state.allBooks.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = importFiles, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("导入") })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> Unit
                state.allBooks.isEmpty() -> EmptyShelf(importFiles) { pickFolder.launch(null) }
                else -> Column {
                    if (s.tab == LibraryTab.SHELF) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${state.allBooks.size} 本书", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 4.dp))
                            Box {
                                TextButton(onClick = { sortMenu = true }) {
                                    Icon(Icons.Filled.Sort, null, Modifier.padding(end = 4.dp))
                                    Text(s.sort.label)
                                }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    SortOrder.entries.forEach { o ->
                                        DropdownMenuItem(text = { Text(o.label + if (o == s.sort) " ✓" else "") }, onClick = {
                                            sortMenu = false
                                            vm.updateSettings { it.copy(sort = o) }
                                        })
                                    }
                                }
                            }
                        }
                    }
                    when (s.tab) {
                        LibraryTab.SHELF -> ShelfItems(state.shelf, s.viewMode, onOpenBook, onOpenSeries) { actionsFor = it }
                        LibraryTab.AUTHORS -> GroupList(state.groups, "位作者", onOpenAuthor, onOpenBook)
                        LibraryTab.SERIES -> GroupList(state.groups, "个系列", onOpenSeries, onOpenBook)
                        else -> if (state.flat.isEmpty()) {
                            Text(
                                when (s.tab) {
                                    LibraryTab.RECENT_READ -> "还没有读过的书"
                                    LibraryTab.FINISHED -> "还没有读完的书"
                                    else -> "没有书"
                                },
                                Modifier.fillMaxWidth().padding(40.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            ShelfItems(state.flat.map { ShelfItem.Single(it) }, s.viewMode, onOpenBook, onOpenSeries) { actionsFor = it }
                        }
                    }
                }
            }
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

@Composable
fun ShelfItems(
    items: List<ShelfItem>,
    mode: ViewMode,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (String) -> Unit,
    onLongPress: (Book) -> Unit,
) {
    val bottom = PaddingValues(bottom = 96.dp)
    when (mode) {
        ViewMode.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.key }) { item ->
                when (item) {
                    is ShelfItem.Single -> GridBookCell(item.book, { onOpenBook(item.book) }, { onLongPress(item.book) })
                    is ShelfItem.Stack -> GridStackCell(item) { onOpenSeries(item.name) }
                }
            }
        }
        ViewMode.LIST, ViewMode.COMPACT -> LazyColumn(contentPadding = bottom, modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.key }) { item ->
                when (item) {
                    is ShelfItem.Single ->
                        if (mode == ViewMode.LIST) ListBookRow(item.book, { onOpenBook(item.book) }, { onLongPress(item.book) })
                        else CompactBookRow(item.book, { onOpenBook(item.book) }, { onLongPress(item.book) })
                    is ShelfItem.Stack ->
                        if (mode == ViewMode.LIST) ListStackRow(item) { onOpenSeries(item.name) }
                        else CompactStackRow(item) { onOpenSeries(item.name) }
                }
            }
        }
    }
}

/** Authors / series overview: a header per group with a horizontal strip of covers. */
@Composable
private fun GroupList(groups: List<Group>, unit: String, onOpenGroup: (String) -> Unit, onOpenBook: (Book) -> Unit) {
    if (groups.isEmpty()) {
        Text("没有$unit".replace("没有个", "没有").replace("没有位", "没有"), Modifier.padding(40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text("${groups.size} $unit", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(groups, key = { it.name }) { g ->
            Column(Modifier.fillMaxWidth().clickable { onOpenGroup(g.name) }.padding(vertical = 10.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("${g.books.size} 本 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    items(g.books, key = { it.id }) { b ->
                        Cover(b, Modifier.width(64.dp).clickable { onOpenBook(b) }, corner = 4.dp, small = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyShelf(onImportFiles: () -> Unit, onImportFolder: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("书架是空的", style = MaterialTheme.typography.titleLarge)
        Text("导入 EPUB 或 TXT 电子书开始阅读", Modifier.padding(top = 8.dp, bottom = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        ExtendedFloatingActionButton(onClick = onImportFiles, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("导入文件") })
        TextButton(onClick = onImportFolder, modifier = Modifier.padding(top = 8.dp)) { Text("导入整个文件夹") }
    }
}
