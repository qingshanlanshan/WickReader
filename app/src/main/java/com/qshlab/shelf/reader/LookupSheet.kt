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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.R
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
fun LookupSheet(text: String, bookSource: String, target: String, onTargetChange: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val from = remember(text) { Translation.sourceOf(text, bookSource) }
    val to = target.takeIf { it != from } ?: Translation.defaultTarget(from)
    var result by remember(text, to) { mutableStateOf<String?>(null) }
    val translating = stringResource(R.string.tr_translating)
    var status by remember(text, to) { mutableStateOf(translating) }
    var failed by remember(text, to) { mutableStateOf(false) }
    val apps = remember { textApps(context) }

    LaunchedEffect(text, to) {
        if (!Translation.isDownloaded(from) || !Translation.isDownloaded(to)) {
            status = context.getString(R.string.tr_first_download, Translation.nameOf(context, from), Translation.nameOf(context, to))
        }
        result = runCatching { Translation.translate(text, from, to) }
            .getOrElse {
                status = context.getString(R.string.tr_failed, it.message ?: context.getString(R.string.tr_failed_hint))
                failed = true
                null
            }
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.tr_translate_from, Translation.nameOf(from)), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Translation.TARGETS.filter { it != from }.forEach { lang ->
                    FilterChip(selected = lang == to, onClick = { if (lang != to) onTargetChange(lang) }, label = { Text(Translation.nameOf(lang)) })
                }
            }
            if (result != null) {
                SelectionContainer {
                    Text(result!!, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!failed) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text))
                    onDismiss()
                }, label = { Text(stringResource(R.string.rd_copy)) })
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
                }, label = { Text(stringResource(R.string.tr_bing_dict)) })
            }
            if (apps.isEmpty()) {
                Text(stringResource(R.string.tr_apps_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
