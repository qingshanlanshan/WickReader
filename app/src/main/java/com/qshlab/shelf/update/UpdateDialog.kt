package com.qshlab.shelf.update

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** "New version available": notes, then download (with progress) and hand over to the installer. */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val chinese = context.resources.configuration.locales[0].language == "zh"
    val notes = (if (chinese) info.notesZh else info.notesEn) ?: info.notesEn ?: info.notesZh

    AlertDialog(
        onDismissRequest = { job?.cancel(); onDismiss() },
        title = { Text(stringResource(R.string.upd_title, info.version)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.upd_current, Updates.installedVersionName(context), Formatter.formatShortFileSize(context, info.size)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (notes != null) Text(notes, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.upd_keeps_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.upd_downloading, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                }
                if (failed) Text(stringResource(R.string.upd_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = progress == null, onClick = {
                failed = false
                progress = 0f
                job = scope.launch {
                    runCatching { Updates.download(context, info) { progress = it } }
                        .onSuccess { apk -> progress = null; Updates.install(context, apk) }
                        .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) { progress = null; failed = true } }
                }
            }) { Text(stringResource(R.string.upd_update)) }
        },
        dismissButton = {
            if (failed) {
                // Fallback: the download page in the browser.
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updates.SITE))) } }) {
                    Text(stringResource(R.string.upd_open_site))
                }
            } else {
                TextButton(onClick = { job?.cancel(); onDismiss() }) { Text(stringResource(R.string.upd_later)) }
            }
        },
    )
}
