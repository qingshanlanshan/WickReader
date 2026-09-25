package com.qshlab.shelf.ui

import android.app.Activity
import android.content.ContextWrapper
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.AppLocale
import com.qshlab.shelf.R
import com.qshlab.shelf.ShelfTheme
import com.qshlab.shelf.settings.AppSettings
import com.qshlab.shelf.settings.ReaderSettings
import com.qshlab.shelf.shelfApp
import com.qshlab.shelf.tts.AiVoiceId
import com.qshlab.shelf.tts.DownloadState
import com.qshlab.shelf.tts.VoiceCatalog
import com.qshlab.shelf.tts.VoiceModel
import com.qshlab.shelf.tts.displayName
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-level settings; its own activity so the reader can open it without leaving the book. */
class SettingsActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.qshlab.shelf.AppLocale.wrap(base))
    }

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
    var sponsoring by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<VoiceModel?>(null) }
    var storageVersion by remember { mutableStateOf(0) }
    var pickingLanguage by remember { mutableStateOf(false) }
    val appLanguage = remember { AppLocale.get(context) }

    fun updateApp(t: (AppSettings) -> AppSettings) = scope.launch { app.settings.updateApp(t(appSettings)) }
    fun updateReader(t: (ReaderSettings) -> ReaderSettings) = scope.launch { app.settings.updateReader(t(reader)) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.set_back)) } },
                title = { Text(stringResource(R.string.set_title)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item { Section(stringResource(R.string.set_section_general)) }
            item {
                SettingRow(stringResource(R.string.app_language), languageName(appLanguage)) { pickingLanguage = true }
            }

            item { Section(stringResource(R.string.set_section_ai_voice)) }
            item {
                Text(
                    stringResource(R.string.set_ai_voice_intro),
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
            listOf("zh" to R.string.set_voice_zh, "en" to R.string.set_voice_en).forEach { (lang, label) ->
                val models = VoiceCatalog.models.filter { it.id in installed && lang in it.languages }
                if (models.isNotEmpty()) item(key = "voice-$lang") {
                    val chosen = AiVoiceId.parse(reader.ttsVoices[lang])?.takeIf { v -> models.any { it.id == v.modelId } }
                    val current = chosen ?: AiVoiceId(models.first().id, models.first().defaults[lang] ?: 0)
                    val model = VoiceCatalog.get(current.modelId)
                    val name = model?.speakers?.firstOrNull { it.sid == current.sid }?.displayName()
                    val modelName = model?.let { stringResource(it.shortName) }
                    SettingRow(stringResource(label), "$modelName · $name") { picking = lang }
                }
            }
            item {
                SwitchSetting(stringResource(R.string.set_prefer_ai), stringResource(R.string.set_prefer_ai_desc), appSettings.preferAiVoice) { v -> updateApp { it.copy(preferAiVoice = v) } }
            }

            item { Section(stringResource(R.string.set_section_import)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.set_scan_formats), style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.qshlab.shelf.data.BookScanner.FORMATS.forEach { f ->
                            val on = f.key in appSettings.scanFormats
                            FilterChip(selected = on, onClick = {
                                updateApp { s -> s.copy(scanFormats = if (on) s.scanFormats - f.key else s.scanFormats + f.key) }
                            }, label = { Text(stringResource(f.label)) })
                        }
                    }
                    Text(stringResource(R.string.set_scan_mobi_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.set_scan_txt_min), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 10, 30, 100).forEach { kb ->
                            FilterChip(selected = appSettings.scanTxtMinKb == kb, onClick = { updateApp { it.copy(scanTxtMinKb = kb) } }, label = { Text(if (kb == 0) stringResource(R.string.set_no_limit) else "$kb KB") })
                        }
                    }
                }
            }

            item { Section(stringResource(R.string.set_section_reading)) }
            item { SwitchSetting(stringResource(R.string.set_volume_keys), stringResource(R.string.set_volume_keys_desc), reader.volumeKeysTurnPages) { v -> updateReader { it.copy(volumeKeysTurnPages = v) } } }
            item { SwitchSetting(stringResource(R.string.set_to_simplified), stringResource(R.string.set_to_simplified_desc), reader.toSimplified) { v -> updateReader { it.copy(toSimplified = v) } } }
            item { SwitchSetting(stringResource(R.string.set_publisher_styles), stringResource(R.string.set_publisher_styles_desc), reader.publisherStyles) { v -> updateReader { it.copy(publisherStyles = v) } } }

            item { Section(stringResource(R.string.set_section_storage)) }
            item {
                val sizes by produceState(emptyList<Pair<Int, Long>>(), storageVersion) {
                    value = withContext(Dispatchers.IO) {
                        val f = context.filesDir
                        listOf(
                            R.string.set_storage_books to dirSize(File(f, "books")) + dirSize(File(f, "covers")),
                            R.string.set_storage_voices to dirSize(File(f, "voices")),
                            R.string.set_storage_translations to dirSize(File(f, "translations")),
                        )
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    sizes.forEach { (label, bytes) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(stringResource(label), Modifier.weight(1f))
                            Text(Formatter.formatShortFileSize(context, bytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { File(context.filesDir, "translations").deleteRecursively() }
                            storageVersion++
                            Toast.makeText(context, context.getString(R.string.set_translations_cleared), Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.set_clear_translations)) }
                }
            }

            item { Section(stringResource(R.string.set_section_about)) }
            item { SettingRow(stringResource(R.string.sponsor_menu), stringResource(R.string.sponsor_settings_summary)) { sponsoring = true } }
            item {
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
                Text(
                    stringResource(R.string.set_about, version.toString()),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (sponsoring) SponsorDialog { sponsoring = false }

    picking?.let { lang ->
        val models = VoiceCatalog.models.filter { it.id in installed && lang in it.languages }
        var previewing by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { app.voices.stopPreview(); picking = null },
            title = { Text(stringResource(if (lang == "zh") R.string.set_voice_zh else R.string.set_voice_en)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    models.forEach { m ->
                        item(key = "h" + m.id) { Text(stringResource(m.title), Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
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
                                Text((if (selected) "✓ " else "") + sp.displayName(), Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                IconButton(onClick = {
                                    previewing = id
                                    app.voices.preview(m, sp.sid) { err ->
                                        if (previewing == id) previewing = null
                                        if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    }
                                }) {
                                    if (previewing == id) Text("…") else Icon(Icons.Filled.PlayArrow, stringResource(R.string.set_preview))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { app.voices.stopPreview(); picking = null }) { Text(stringResource(R.string.set_done)) } },
        )
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.set_delete_voice_title, stringResource(m.title))) },
            text = { Text(stringResource(R.string.set_delete_voice_message, m.downloadMb)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.voices.delete(m.id); storageVersion++ }
                    confirmDelete = null
                }) { Text(stringResource(R.string.set_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }

    if (pickingLanguage) {
        AlertDialog(
            onDismissRequest = { pickingLanguage = false },
            title = { Text(stringResource(R.string.app_language)) },
            text = {
                Column {
                    listOf(null, "zh", "en").forEach { lang ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                pickingLanguage = false
                                if (lang != appLanguage) context.findActivity()?.let { AppLocale.set(it, lang) }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = lang == appLanguage, onClick = null)
                            Text(languageName(lang), Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickingLanguage = false }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
}

/** An interface language choice (null = follow the system); languages are named in their own language. */
@Composable
private fun languageName(lang: String?): String = when (lang) {
    "zh" -> stringResource(R.string.set_lang_native_zh)
    "en" -> stringResource(R.string.set_lang_native_en)
    else -> stringResource(R.string.app_language_system)
}

private fun android.content.Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()

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
            Text(stringResource(model.title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(model.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                state is DownloadState.Downloading -> {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.set_downloading, (state.fraction * 100).toInt(), model.downloadMb), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.set_cancel)) }
                    }
                }
                state is DownloadState.Unpacking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.set_unpacking), style = MaterialTheme.typography.bodySmall)
                }
                installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.set_downloaded), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.set_delete)) }
                }
                else -> {
                    if (state is DownloadState.Failed) Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    val label = stringResource(R.string.set_download_size, model.downloadMb)
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
