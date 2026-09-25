package com.qshlab.shelf.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.data.Book
import com.qshlab.shelf.data.languageLabel
import com.qshlab.shelf.settings.GroupBy
import com.qshlab.shelf.settings.SortOrder
import com.qshlab.shelf.settings.StatusFilter
import com.qshlab.shelf.settings.ViewMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpenBook: (Book) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onOpenCatalogs: () -> Unit,
    onOpenScan: () -> Unit,
    onOpenSuggestions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Book?>(null) }
    /** Non-null while selecting several books. */
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    var seriesDialogFor by remember { mutableStateOf<List<Book>?>(null) }
    var confirmDelete by remember { mutableStateOf<Set<Long>?>(null) }
    val s = state.settings

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = selection != null) { selection = null }
    // Drop ids of books that disappeared (deleted elsewhere).
    LaunchedEffect(state.allBooks) {
        selection = selection?.let { sel -> sel.filter { id -> state.allBooks.any { it.id == id } }.toSet().ifEmpty { null } }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importFolder(uri)
    }
    val importFiles = { pickFiles.launch(arrayOf("*/*")) }
    val openGroup = { stack: ShelfItem.Stack -> if (stack.kind == GroupKind.SERIES) onOpenSeries(stack.name) else onOpenAuthor(stack.name) }
    val toggle = { ids: List<Long> ->
        val sel = selection.orEmpty()
        val next = if (sel.containsAll(ids)) sel - ids.toSet() else sel + ids
        selection = next.ifEmpty { null }
    }
    val selectedBooks = { state.allBooks.filter { it.id in selection.orEmpty() } }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val sel = selection
            if (sel != null) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { selection = null }) { Icon(Icons.Filled.Close, "退出选择") } },
                    title = { Text(if (sel.isEmpty()) "点选书籍" else "已选 ${sel.size} 本") },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    actions = {
                        TextButton(onClick = {
                            selection = state.items.flatMap { item -> if (item is ShelfItem.Single) listOf(item.book.id) else (item as ShelfItem.Stack).books.map { it.id } }.toSet()
                        }) { Text("全选") }
                    },
                )
            } else {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        if (searching) {
                            val query by vm.query.collectAsState()
                            TextField(
                                value = query,
                                onValueChange = { vm.query.value = it },
                                placeholder = { Text("搜索书名、作者、系列") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
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
                                "切换显示方式",
                            )
                        }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "设置") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("扫描手机中的电子书") }, onClick = { menu = false; onOpenScan() })
                                DropdownMenuItem(text = { Text("导入文件（可多选）") }, onClick = { menu = false; importFiles() })
                                DropdownMenuItem(text = { Text("导入文件夹") }, onClick = { menu = false; pickFolder.launch(null) })
                                DropdownMenuItem(text = { Text("在线书库（OPDS）") }, onClick = { menu = false; onOpenCatalogs() })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("选择多本书") }, onClick = { menu = false; selection = emptySet() })
                                DropdownMenuItem(text = { Text("整理系列建议") }, onClick = { menu = false; onOpenSuggestions() })
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            val sel = selection
            if (sel != null) {
                val books = selectedBooks()
                val allWant = books.isNotEmpty() && books.all { it.wantToRead }
                val allFinished = books.isNotEmpty() && books.all { it.finished }
                SelectionActions(
                    enabled = sel.isNotEmpty(),
                    single = sel.size == 1,
                    allWant = allWant,
                    allFinished = allFinished,
                    onSeries = { seriesDialogFor = books },
                    onWant = { vm.setWantToRead(sel, !allWant); selection = null },
                    onFinished = { vm.setFinished(sel, !allFinished); selection = null },
                    onEdit = { actionsFor = books.firstOrNull(); selection = null },
                    onDelete = { confirmDelete = sel },
                )
            }
        },
        floatingActionButton = {
            if (state.loaded && state.allBooks.isNotEmpty() && selection == null) {
                ExtendedFloatingActionButton(onClick = onOpenScan, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("添加书") })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> Unit
                state.allBooks.isEmpty() -> EmptyShelf(onOpenScan, importFiles, onOpenCatalogs)
                else -> Column {
                    ShelfControls(state, vm)
                    val current = state.current
                    val showHeader = state.query.isBlank() && !s.filtering
                    val header: (@Composable () -> Unit)? = if (!showHeader) null else ({
                        Column {
                            if (state.suggestions.isNotEmpty()) SuggestionBanner(state.suggestions.size, onOpenSuggestions)
                            if (current != null) ContinueCard(current) { onOpenBook(current) }
                        }
                    })
                    if (state.items.isEmpty()) {
                        Text("没有符合条件的书", Modifier.fillMaxWidth().padding(40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        ShelfItems(
                            state.items, s.viewMode, onOpenBook, openGroup,
                            header = header,
                            onLongPress = { b -> selection = selection.orEmpty() + b.id },
                            onLongPressStack = { st -> selection = selection.orEmpty() + st.books.map { it.id } },
                            selection = selection,
                            onToggle = toggle,
                        )
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

    seriesDialogFor?.let { books ->
        SeriesDialog(
            books = books,
            existingSeries = state.allBooks.mapNotNull { it.series }.distinctBy { vm.seriesKey(it) },
            onDismiss = { seriesDialogFor = null },
            onConfirm = { name, ordered, numbers ->
                vm.assignSeries(ordered.map { it.id }, name, numbers)
                seriesDialogFor = null
                selection = null
            },
        )
    }

    confirmDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除 ${ids.size} 本书？") },
            text = { Text("这些书会从书架和应用存储中删除，阅读进度和笔记也会一起删除。手机上的原文件不受影响。") },
            confirmButton = { TextButton(onClick = { vm.deleteAll(ids); confirmDelete = null; selection = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

/** Labeled actions for the selected books (each action appears once). */
@Composable
private fun SelectionActions(
    enabled: Boolean,
    single: Boolean,
    allWant: Boolean,
    allFinished: Boolean,
    onSeries: () -> Unit,
    onWant: () -> Unit,
    onFinished: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    NavigationBar {
        @Composable
        fun action(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) =
            NavigationBarItem(selected = false, enabled = on, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label, maxLines = 1) })
        action(Icons.Filled.CollectionsBookmark, "设为系列", enabled, onSeries)
        action(if (allWant) Icons.Filled.BookmarkRemove else Icons.Filled.BookmarkAdd, if (allWant) "取消想读" else "想读", enabled, onWant)
        action(if (allFinished) Icons.Filled.RemoveDone else Icons.Filled.DoneAll, if (allFinished) "未读完" else "已读完", enabled, onFinished)
        action(Icons.Filled.Edit, "编辑", single, onEdit)
        action(Icons.Filled.Delete, "删除", enabled, onDelete)
    }
}

/** 分组 · 排序 · 筛选 chips under the top bar. */
@Composable
private fun ShelfControls(state: LibraryState, vm: LibraryViewModel) {
    val s = state.settings
    var open by remember { mutableStateOf<String?>(null) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Box {
                MenuChip(stringResource(s.groupBy.label)) { open = "group" }
                DropdownMenu(expanded = open == "group", onDismissRequest = { open = null }) {
                    GroupBy.entries.forEach { g ->
                        DropdownMenuItem(text = { Text(stringResource(g.label) + if (g == s.groupBy) "  ✓" else "") }, onClick = { open = null; vm.updateSettings { it.copy(groupBy = g) } })
                    }
                }
            }
        }
        item {
            Box {
                MenuChip(stringResource(s.sort.label)) { open = "sort" }
                DropdownMenu(expanded = open == "sort", onDismissRequest = { open = null }) {
                    SortOrder.entries.forEach { o ->
                        DropdownMenuItem(text = { Text(stringResource(o.label) + if (o == s.sort) "  ✓" else "") }, onClick = { open = null; vm.updateSettings { it.copy(sort = o) } })
                    }
                }
            }
        }
        item {
            Box {
                val active = listOfNotNull(
                    s.status.takeIf { it != StatusFilter.ALL }?.let { stringResource(it.label) },
                    s.language?.let(::languageLabel),
                    s.format?.uppercase(),
                )
                FilterChip(
                    selected = active.isNotEmpty(),
                    onClick = { open = "filter" },
                    label = { Text(if (active.isEmpty()) "筛选" else active.joinToString(" · ")) },
                    leadingIcon = { Icon(Icons.Filled.FilterList, null) },
                )
                DropdownMenu(expanded = open == "filter", onDismissRequest = { open = null }) {
                    MenuHeader("阅读状态")
                    StatusFilter.entries.forEach { f ->
                        DropdownMenuItem(text = { Text(stringResource(f.label) + if (f == s.status) "  ✓" else "") }, onClick = { vm.updateSettings { it.copy(status = f) } })
                    }
                    if (state.languages.size > 1) {
                        HorizontalDivider()
                        MenuHeader("语言")
                        DropdownMenuItem(text = { Text("全部" + if (s.language == null) "  ✓" else "") }, onClick = { vm.updateSettings { it.copy(language = null) } })
                        state.languages.forEach { l ->
                            DropdownMenuItem(text = { Text(languageLabel(l) + if (l == s.language) "  ✓" else "") }, onClick = { vm.updateSettings { it.copy(language = l) } })
                        }
                    }
                    if (state.formats.size > 1) {
                        HorizontalDivider()
                        MenuHeader("格式")
                        DropdownMenuItem(text = { Text("全部" + if (s.format == null) "  ✓" else "") }, onClick = { vm.updateSettings { it.copy(format = null) } })
                        state.formats.forEach { f ->
                            DropdownMenuItem(text = { Text(f.uppercase() + if (f == s.format) "  ✓" else "") }, onClick = { vm.updateSettings { it.copy(format = f) } })
                        }
                    }
                    if (s.filtering) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("清除筛选") }, onClick = {
                            open = null
                            vm.updateSettings { it.copy(status = StatusFilter.ALL, language = null, format = null) }
                        })
                    }
                }
            }
        }
        item {
            Text(
                "${state.shownCount} 本",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun MenuChip(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) }, trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) })
}

@Composable
private fun MenuHeader(text: String) {
    Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SuggestionBanner(count: Int, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                "发现 $count 组可能是同一系列的书，点此确认",
                Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
fun ShelfItems(
    items: List<ShelfItem>,
    mode: ViewMode,
    onOpenBook: (Book) -> Unit,
    onOpenGroup: (ShelfItem.Stack) -> Unit,
    header: (@Composable () -> Unit)? = null,
    badge: (Book) -> String? = { null },
    onLongPress: (Book) -> Unit,
    onLongPressStack: ((ShelfItem.Stack) -> Unit)? = null,
    /** Non-null while selecting: taps toggle instead of opening. */
    selection: Set<Long>? = null,
    onToggle: (List<Long>) -> Unit = {},
) {
    fun clickBook(b: Book) = if (selection != null) onToggle(listOf(b.id)) else onOpenBook(b)
    fun clickStack(st: ShelfItem.Stack) = if (selection != null) onToggle(st.books.map { it.id }) else onOpenGroup(st)
    fun stackSelected(st: ShelfItem.Stack) = selection?.let { sel -> st.books.all { it.id in sel } }
    val longStack: (ShelfItem.Stack) -> (() -> Unit)? = { st -> onLongPressStack?.let { f -> { f(st) } } }
    when (mode) {
        ViewMode.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(items, key = { it.key }) { item ->
                when (item) {
                    is ShelfItem.Single -> GridBookCell(item.book, { clickBook(item.book) }, { onLongPress(item.book) }, badge(item.book), selection?.contains(item.book.id))
                    is ShelfItem.Stack -> GridStackCell(item, { clickStack(item) }, longStack(item), stackSelected(item))
                }
            }
        }
        ViewMode.LIST, ViewMode.COMPACT -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
            if (header != null) item { Box(Modifier.padding(horizontal = 10.dp)) { header() } }
            items(items, key = { it.key }) { item ->
                when (item) {
                    is ShelfItem.Single ->
                        if (mode == ViewMode.LIST) ListBookRow(item.book, { clickBook(item.book) }, { onLongPress(item.book) }, selection?.contains(item.book.id))
                        else CompactBookRow(item.book, { clickBook(item.book) }, { onLongPress(item.book) }, selection?.contains(item.book.id))
                    is ShelfItem.Stack ->
                        if (mode == ViewMode.LIST) ListStackRow(item, { clickStack(item) }, longStack(item), stackSelected(item))
                        else CompactStackRow(item, { clickStack(item) }, longStack(item), stackSelected(item))
                }
            }
        }
    }
}

@Composable
private fun EmptyShelf(onScan: () -> Unit, onImportFiles: () -> Unit, onOpenCatalogs: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("书架是空的", style = MaterialTheme.typography.titleLarge)
        Text("支持 EPUB、PDF、TXT、FB2、Word、漫画 CBZ", Modifier.padding(top = 8.dp, bottom = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        ExtendedFloatingActionButton(onClick = onScan, icon = { Icon(Icons.Filled.Search, null) }, text = { Text("扫描手机中的电子书") })
        TextButton(onClick = onImportFiles, modifier = Modifier.padding(top = 8.dp)) { Text("选择文件导入") }
        TextButton(onClick = onOpenCatalogs) { Text("从在线书库下载公版书") }
    }
}
