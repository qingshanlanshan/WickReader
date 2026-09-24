package com.qshlab.shelf.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.translate.Translation

/** Installed apps that accept selected text (dictionaries, translators): Android's PROCESS_TEXT. */
private data class TextApp(val label: String, val packageName: String, val activity: String)

private fun textApps(context: Context): List<TextApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { TextApp(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
        .distinctBy { it.packageName to it.label }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LookupSheet(text: String, bookLanguage: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val (from, to) = remember(text) { Translation.pairFor(text, bookLanguage) }
    var result by remember(text) { mutableStateOf<String?>(null) }
    var status by remember(text) { mutableStateOf("翻译中…") }
    val apps = remember { textApps(context) }

    LaunchedEffect(text) {
        if (!Translation.isDownloaded(from) || !Translation.isDownloaded(to)) status = "首次使用：正在下载${Translation.nameOf(from)}→${Translation.nameOf(to)}翻译模型（约 30 MB，只需一次）…"
        result = runCatching { Translation.translate(text, from, to) }
            .getOrElse { status = "翻译失败：${it.message ?: "请检查网络后重试（首次需下载模型）"}"; null }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
                )
            }
            HorizontalDivider()
            Text("${Translation.nameOf(from)} → ${Translation.nameOf(to)}（离线翻译）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (result != null) {
                SelectionContainer {
                    Text(result!!, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!status.startsWith("翻译失败")) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text))
                    onDismiss()
                }, label = { Text("复制") })
                apps.forEach { app ->
                    AssistChip(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                                    .setClassName(app.packageName, app.activity)
                                    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                            )
                        }
                    }, label = { Text(app.label) })
                }
                AssistChip(onClick = {
                    val url = "https://www.bing.com/dict/search?q=" + Uri.encode(text)
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }, label = { Text("必应词典") })
            }
            if (apps.isEmpty()) {
                Text("提示：安装词典或翻译 App（如欧路词典、Google 翻译）后，会出现在这里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
