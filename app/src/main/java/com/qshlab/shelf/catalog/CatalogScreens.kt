package com.qshlab.shelf.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import com.qshlab.shelf.R
import com.qshlab.shelf.data.ImportResult
import com.qshlab.shelf.shelfApp
import java.io.File
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.opds.images

/** List of OPDS catalogs, with add / remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogsScreen(onBack: () -> Unit, onOpen: (name: String, url: String) -> Unit) {
    val repo = LocalContext.current.shelfApp.catalogs
    val catalogs by repo.catalogs.collectAsState(initial = repo.builtIn)
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                title = { Text(stringResource(R.string.cat_title)) },
                actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, stringResource(R.string.cat_add)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.cat_explain),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(catalogs, key = { it.url }) { c ->
                val name = c.nameRes?.let { stringResource(it) } ?: c.name
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text(c.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        if (!c.builtIn) IconButton(onClick = { scope.launch { repo.remove(c.url) } }) { Icon(Icons.Filled.Delete, stringResource(R.string.common_delete)) }
                        else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    },
                    modifier = Modifier.clickable { onOpen(name, c.url) },
                )
                HorizontalDivider()
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.cat_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.cat_name)) }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.cat_url)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
            },
            confirmButton = {
                Button(enabled = url.startsWith("http") && url.length > 10, onClick = {
                    scope.launch { repo.add(name.ifBlank { url }, url) }
                    adding = false
                }) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** One OPDS feed: search box (if the catalog supports it), sub-catalogs, and books. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(title: String, url: String, onBack: () -> Unit, onOpenFeed: (title: String, url: String) -> Unit) {
    val context = LocalContext.current
    var result by remember(url) { mutableStateOf<FeedResult?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Publication?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(url) { result = Opds.load(context, url) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val r = result) {
                null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is FeedResult.Failed -> Text(r.message, Modifier.padding(24.dp))
                is FeedResult.Loaded -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    if (r.searchTemplate != null) item {
                        val search = {
                            if (query.isNotBlank()) onOpenFeed(context.getString(R.string.cat_search_title, query.trim()), Opds.searchUrl(context, r.searchTemplate, query.trim()))
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.cat_search_hint)) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                    items(r.shortcuts, key = { "s" + it.second }) { (label, link) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            modifier = Modifier.clickable { onOpenFeed(label, link) },
                        )
                    }
                    val feed = r.feed
                    val folders = feed.navigation + feed.groups.flatMap { it.navigation }
                    items(folders, key = { "n" + it.href.toString() }) { link ->
                        ListItem(
                            headlineContent = { Text(link.title ?: link.href.toString()) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            modifier = Modifier.clickable { Opds.resolve(link, feed.href)?.let { onOpenFeed(link.title ?: title, it) } },
                        )
                    }
                    val pubs = feed.publications + feed.groups.flatMap { it.publications }
                    items(pubs) { pub -> PublicationRow(pub, feed.href) { selected = pub } }
                    // "Next page" link, if the feed is paginated.
                    feed.links.firstOrNull { "next" in it.rels }?.let { next ->
                        item {
                            TextButton(onClick = { Opds.resolve(next, feed.href)?.let { onOpenFeed(title, it) } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cat_next_page)) }
                        }
                    }
                    if (folders.isEmpty() && pubs.isEmpty()) item { Text(stringResource(R.string.cat_empty), Modifier.padding(24.dp)) }
                }
            }
        }
    }

    selected?.let { pub ->
        val scope = rememberCoroutineScope()
        PublicationSheet(
            pub = pub,
            baseUrl = (result as? FeedResult.Loaded)?.feed?.href,
            onDownload = { link, linkUrl ->
                scope.launch {
                    snackbar.showSnackbar(context.getString(R.string.cat_downloading, pub.metadata.title ?: ""))
                    val app = context.shelfApp
                    val name = (pub.metadata.title ?: "book").replace(Regex("[\\\\/:*?\"<>|]"), "_") + when {
                        Opds.isEpub(link) -> ".epub"
                        Opds.isPdf(link) -> ".pdf"
                        else -> ".txt"
                    }
                    val msg = runCatching {
                        val file = Opds.download(context, linkUrl, File(context.cacheDir, "downloads"), name)
                        when (val res = app.library.importDownloaded(file, name)) {
                            is ImportResult.Added -> context.getString(R.string.cat_added, res.book.title)
                            is ImportResult.Skipped -> context.getString(R.string.cat_not_added, res.reason)
                        }
                    }.getOrElse { context.getString(R.string.cat_download_failed, it.message ?: "") }
                    selected = null
                    snackbar.showSnackbar(msg)
                }
            },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun PublicationRow(pub: Publication, base: org.readium.r2.shared.util.Url, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Cover links are often relative to the feed.
        val cover = pub.images.firstOrNull()?.let { Opds.resolve(it, base) }
        Box(Modifier.width(48.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp))) {
            if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(pub.metadata.title ?: stringResource(R.string.cat_untitled), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(pub.metadata.authors.joinToString("、") { it.name }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PublicationSheet(pub: Publication, baseUrl: org.readium.r2.shared.util.Url?, onDownload: (Link, String) -> Unit, onDismiss: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(pub.metadata.title ?: "", style = MaterialTheme.typography.titleLarge)
            Text(pub.metadata.authors.joinToString("、") { it.name }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            pub.metadata.description?.let {
                Text(it.replace(Regex("<[^>]*>"), ""), Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
            }
            val links = Opds.acquisitions(pub).sortedWith(compareByDescending<Link> { Opds.isEpub(it) }.thenByDescending { Opds.isPdf(it) })
            if (links.isEmpty()) Text(stringResource(R.string.cat_no_files))
            links.forEach { link ->
                val supported = Opds.isEpub(link) || Opds.isPdf(link) || link.mediaType?.toString()?.startsWith("text/plain") == true
                val url = baseUrl?.let { Opds.resolve(link, it) } ?: link.href.toString()
                OutlinedButton(enabled = supported && !busy, onClick = { busy = true; onDownload(link, url) }, modifier = Modifier.fillMaxWidth()) {
                    if (busy && supported) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.cat_download_format, Opds.label(LocalContext.current, link)))
                }
            }
        }
    }
}
