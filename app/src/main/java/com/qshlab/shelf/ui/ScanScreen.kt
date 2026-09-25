package com.qshlab.shelf.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.qshlab.shelf.data.BookScanner
import com.qshlab.shelf.settings.AppSettings
import com.qshlab.shelf.shelfApp
import kotlinx.coroutines.launch

/** 扫描手机: finds e-books in shared storage and lets the user tick which ones to import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(vm: LibraryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.shelfApp
    val scope = rememberCoroutineScope()
    val appSettings by app.settings.app.collectAsState(AppSettings())
    var access by remember { mutableStateOf(BookScanner.hasAccess(context)) }
    var results by remember { mutableStateOf<List<BookScanner.Found>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = BookScanner.hasAccess(context) }
    val requestLegacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { access = it }

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            val found = BookScanner.scan(appSettings.scanFormats, appSettings.scanTxtMinKb * 1024L, app.library.fileKeys())
            results = found
            // New books start ticked; ones already on the shelf can't be picked.
            selected = found.filter { !it.inLibrary && it.supported }.map { it.file.path }.toSet()
            scanning = false
        }
    }

    LaunchedEffect(access, appSettings.scanFormats, appSettings.scanTxtMinKb) { if (access) scan() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                title = { Text("扫描手机中的电子书") },
                actions = { if (access) IconButton(onClick = ::scan) { Icon(Icons.Filled.Refresh, "重新扫描") } },
            )
        },
        bottomBar = {
            val list = results
            if (access && list != null && list.any { !it.inLibrary && it.supported }) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val selectable = list.filter { !it.inLibrary && it.supported }.map { it.file.path }.toSet()
                        val all = selected.containsAll(selectable)
                        TextButton(onClick = { selected = if (all) emptySet() else selectable }) { Text(if (all) "全不选" else "全选") }
                        Box(Modifier.weight(1f))
                        Button(enabled = selected.isNotEmpty(), onClick = {
                            vm.importScanned(list.filter { it.file.path in selected }.map { it.file })
                            onBack()
                        }) { Text("导入 ${selected.size} 本") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!access) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("需要「所有文件访问权限」", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "扫描手机里的电子书需要读取存储空间的权限。书只会被复制到本应用里，不会上传到任何地方。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                            runCatching { context.startActivity(intent) }
                                .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        } else {
                            requestLegacy.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    }) { Text("去授权") }
                }
                return@Column
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                items(BookScanner.FORMATS, key = { it.key }) { f ->
                    val on = f.key in appSettings.scanFormats
                    FilterChip(selected = on, onClick = {
                        scope.launch { app.settings.updateApp(appSettings.copy(scanFormats = if (on) appSettings.scanFormats - f.key else appSettings.scanFormats + f.key)) }
                    }, label = { Text(f.label) })
                }
            }
            val list = results
            when {
                scanning || list == null -> Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 16.dp))
                    Text("正在扫描…")
                }
                list.isEmpty() -> Text("没有找到电子书", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text(
                        "找到 ${list.size} 个文件，其中 ${list.count { it.inLibrary }} 个已在书架" +
                            if (appSettings.scanTxtMinKb > 0 && "txt" in appSettings.scanFormats) "（已忽略小于 ${appSettings.scanTxtMinKb} KB 的 TXT / 网页）" else "",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        list.groupBy { it.folder }.forEach { (folder, files) ->
                            item(key = "d$folder") {
                                Column {
                                    HorizontalDivider()
                                    Text(folder, Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            items(files, key = { it.file.path }) { f ->
                                val checked = f.file.path in selected
                                val pickable = !f.inLibrary && f.supported
                                Row(
                                    Modifier.fillMaxWidth().clickable(enabled = pickable) {
                                        selected = if (checked) selected - f.file.path else selected + f.file.path
                                    }.padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = checked || f.inLibrary, enabled = pickable, onCheckedChange = {
                                        selected = if (checked) selected - f.file.path else selected + f.file.path
                                    })
                                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                        Text(f.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            Formatter.formatShortFileSize(context, f.size) + when {
                                                f.inLibrary -> " · 已在书架"
                                                !f.supported -> " · 暂不支持，可用 Calibre 转成 EPUB"
                                                else -> ""
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
