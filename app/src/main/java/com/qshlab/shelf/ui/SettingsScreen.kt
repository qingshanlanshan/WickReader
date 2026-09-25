package com.qshlab.shelf.ui

import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.ShelfTheme
import com.qshlab.shelf.settings.AppSettings
import com.qshlab.shelf.settings.ReaderSettings
import com.qshlab.shelf.shelfApp
import com.qshlab.shelf.tts.AiVoiceId
import com.qshlab.shelf.tts.DownloadState
import com.qshlab.shelf.tts.VoiceCatalog
import com.qshlab.shelf.tts.VoiceModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-level settings; its own activity so the reader can open it without leaving the book. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ShelfTheme { SettingsScreen(onBack = { finish() }) } }
    }

    override fun onStop() {
        super.onStop()
        shelfApp.voices.stopPreview()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.shelfApp
    val scope = rememberCoroutineScope()
    val appSettings by app.settings.app.collectAsState(AppSettings())
    val reader by app.settings.reader.collectAsState(ReaderSettings())
    val installed by app.voices.installed.collectAsState()
    val downloads by app.voices.downloads.collectAsState()
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<VoiceModel?>(null) }
    var storageVersion by remember { mutableStateOf(0) }

    fun updateApp(t: (AppSettings) -> AppSettings) = scope.launch { app.settings.updateApp(t(appSettings)) }
    fun updateReader(t: (ReaderSettings) -> ReaderSettings) = scope.launch { app.settings.updateReader(t(reader)) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                title = { Text("设置") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item { Section("朗读 · AI 语音") }
            item {
                Text(
                    "AI 语音在手机上离线合成，比系统语音自然得多。下载一次即可，不联网也能用。",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(VoiceCatalog.models, key = { it.id }) { m ->
                VoiceModelCard(
                    model = m,
                    installed = m.id in installed,
                    state = downloads[m.id],
                    onDownload = { app.voices.download(m) },
                    onCancel = { app.voices.cancel(m.id) },
                    onDelete = { confirmDelete = m },
                )
            }
            listOf("zh" to "中文朗读声音", "en" to "英文朗读声音").forEach { (lang, label) ->
                val models = VoiceCatalog.models.filter { it.id in installed && lang in it.languages }
                if (models.isNotEmpty()) item(key = "voice-$lang") {
                    val chosen = AiVoiceId.parse(reader.ttsVoices[lang])?.takeIf { v -> models.any { it.id == v.modelId } }
                    val current = chosen ?: AiVoiceId(models.first().id, models.first().defaults[lang] ?: 0)
                    val model = VoiceCatalog.get(current.modelId)
                    val name = model?.speakers?.firstOrNull { it.sid == current.sid }?.name
                    SettingRow(label, "${model?.title?.substringBefore('（')} · $name") { picking = lang }
                }
            }
            item {
                SwitchSetting("优先使用 AI 语音", "关闭后默认用手机自带的语音引擎", appSettings.preferAiVoice) { v -> updateApp { it.copy(preferAiVoice = v) } }
            }

            item { Section("导入") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("扫描手机时查找的格式", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("epub" to "EPUB", "txt" to "TXT").forEach { (ext, label) ->
                            val on = ext in appSettings.scanFormats
                            FilterChip(selected = on, onClick = {
                                updateApp { s -> s.copy(scanFormats = if (on) s.scanFormats - ext else s.scanFormats + ext) }
                            }, label = { Text(label) })
                        }
                    }
                    Text("忽略小于此大小的 TXT（排除日志、说明文件）", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 10, 30, 100).forEach { kb ->
                            FilterChip(selected = appSettings.scanTxtMinKb == kb, onClick = { updateApp { it.copy(scanTxtMinKb = kb) } }, label = { Text(if (kb == 0) "不限" else "$kb KB") })
                        }
                    }
                }
            }

            item { Section("阅读") }
            item { SwitchSetting("音量键翻页", "朗读时音量键仍用来调音量", reader.volumeKeysTurnPages) { v -> updateReader { it.copy(volumeKeysTurnPages = v) } } }
            item { SwitchSetting("繁体书默认显示为简体", "每本书也可以在阅读设置里单独切换", reader.toSimplified) { v -> updateReader { it.copy(toSimplified = v) } } }
            item { SwitchSetting("使用书籍自带排版", "关闭时所有书统一使用你的字体、字号和行距", reader.publisherStyles) { v -> updateReader { it.copy(publisherStyles = v) } } }

            item { Section("存储") }
            item {
                val sizes by produceState(emptyList<Pair<String, Long>>(), storageVersion) {
                    value = withContext(Dispatchers.IO) {
                        val f = context.filesDir
                        listOf(
                            "书籍" to dirSize(File(f, "books")) + dirSize(File(f, "covers")),
                            "AI 语音" to dirSize(File(f, "voices")),
                            "翻译缓存" to dirSize(File(f, "translations")),
                        )
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    sizes.forEach { (label, bytes) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(label, Modifier.weight(1f))
                            Text(Formatter.formatShortFileSize(context, bytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { File(context.filesDir, "translations").deleteRecursively() }
                            storageVersion++
                            Toast.makeText(context, "已清除翻译缓存", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("清除翻译缓存") }
                }
            }

            item { Section("关于") }
            item {
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
                Text(
                    "Shelf $version · 所有数据只保存在这台手机上。\n\n" +
                        "使用的开源项目：Readium Kotlin Toolkit（BSD-3）、OpenCC 词库（Apache-2.0）、" +
                        "sherpa-onnx（Apache-2.0）、Kokoro-82M v1.1-zh（Apache-2.0）、Piper 语音模型、" +
                        "Apache Commons Compress、Google ML Kit 翻译。",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    picking?.let { lang ->
        val models = VoiceCatalog.models.filter { it.id in installed && lang in it.languages }
        var previewing by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { app.voices.stopPreview(); picking = null },
            title = { Text(if (lang == "zh") "中文朗读声音" else "英文朗读声音") },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    models.forEach { m ->
                        item(key = "h" + m.id) { Text(m.title, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                        items(m.speakersFor(lang), key = { m.id + it.sid }) { sp ->
                            val id = AiVoiceId(m.id, sp.sid).toString()
                            val chosen = AiVoiceId.parse(reader.ttsVoices[lang])?.takeIf { v -> models.any { it.id == v.modelId } }
                                ?: AiVoiceId(models.first().id, models.first().defaults[lang] ?: 0)
                            val selected = chosen.toString() == id
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    updateReader { it.copy(ttsVoices = it.ttsVoices + (lang to id)) }
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text((if (selected) "✓ " else "") + sp.name, Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                IconButton(onClick = {
                                    previewing = id
                                    app.voices.preview(m, sp.sid) { err ->
                                        if (previewing == id) previewing = null
                                        if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    }
                                }) {
                                    if (previewing == id) Text("…") else Icon(Icons.Filled.PlayArrow, "试听")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { app.voices.stopPreview(); picking = null }) { Text("完成") } },
        )
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除「${m.title}」？") },
            text = { Text("以后需要时可以重新下载（约 ${m.downloadMb} MB）。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.voices.delete(m.id); storageVersion++ }
                    confirmDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

private fun dirSize(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

@Composable
private fun VoiceModelCard(
    model: VoiceModel,
    installed: Boolean,
    state: DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(model.title, style = MaterialTheme.typography.titleSmall)
            Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                state is DownloadState.Downloading -> {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("下载中 ${(state.fraction * 100).toInt()}%（${model.downloadMb} MB）", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                }
                state is DownloadState.Unpacking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在解压…", style = MaterialTheme.typography.bodySmall)
                }
                installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已下载", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = onDelete) { Text("删除") }
                }
                else -> {
                    if (state is DownloadState.Failed) Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    val label = "下载（${model.downloadMb} MB）"
                    if (model.id == VoiceCatalog.models.first().id) Button(onClick = onDownload) { Text(label) }
                    else OutlinedButton(onClick = onDownload) { Text(label) }
                }
            }
        }
    }
}

@Composable
fun Section(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Text(title, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchSetting(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
