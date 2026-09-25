package com.qshlab.shelf.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.R
import com.qshlab.shelf.update.Updates
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Free feedback: posted to wick.qshlab.com, which emails it to the author. Nothing is stored. */
@Composable
fun FeedbackDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var withDevice by rememberSaveable { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.fb_title)) },
        text = {
            if (result == R.string.fb_sent) {
                Text(stringResource(R.string.fb_sent))
                return@AlertDialog
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.fb_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    message, { message = it.take(4000) },
                    label = { Text(stringResource(R.string.fb_message)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                )
                OutlinedTextField(
                    contact, { contact = it.take(200) },
                    label = { Text(stringResource(R.string.fb_contact)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().clickable { withDevice = !withDevice }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withDevice, onCheckedChange = { withDevice = it })
                    Text(stringResource(R.string.fb_device), style = MaterialTheme.typography.bodySmall)
                }
                result?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (result == R.string.fb_sent) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.fb_close)) }
            } else {
                Button(enabled = message.isNotBlank() && !sending, onClick = {
                    sending = true
                    result = null
                    scope.launch {
                        result = send(context, message.trim(), contact.trim(), withDevice)
                        if (result == R.string.fb_sent) { message = ""; contact = "" }
                        sending = false
                    }
                }) {
                    if (sending) CircularProgressIndicator(Modifier.heightIn(max = 18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.fb_send))
                }
            }
        },
        dismissButton = {
            if (result != R.string.fb_sent) TextButton(onClick = onDismiss, enabled = !sending) { Text(stringResource(R.string.fb_cancel)) }
        },
    )
}

/** Returns the string to show: sent, or why not. */
private suspend fun send(context: Context, message: String, contact: String, withDevice: Boolean): Int = withContext(Dispatchers.IO) {
    runCatching {
        val body = JSONObject().apply {
            put("message", message)
            put("contact", contact)
            put("source", "app")
            put("language", context.resources.configuration.locales[0].toLanguageTag())
            if (withDevice) {
                put("app", Updates.installedVersionName(context))
                put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            }
        }.toString()
        val conn = URL("${Updates.SITE}/api/feedback").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            when (conn.responseCode) {
                in 200..299 -> R.string.fb_sent
                429 -> R.string.fb_rate
                else -> R.string.fb_failed
            }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(R.string.fb_offline)
}
