package com.qshlab.shelf.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qshlab.shelf.R

private const val WECHAT = "com.tencent.mm"

/**
 * "Support the author": the WeChat tip code, and a way to use it from the same phone (a code on
 * your own screen can't be scanned, so it's saved to the album for WeChat's scan-from-album).
 */
@Composable
fun SponsorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val hasWeChat = remember(context) { context.packageManager.getLaunchIntentForPackage(WECHAT) != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sponsor_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.sponsor_body), style = MaterialTheme.typography.bodyMedium)
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.sponsor_note), Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
                Image(painterResource(R.drawable.sponsor_wechat), stringResource(R.string.sponsor_qr_desc), Modifier.size(220.dp))
                Text(
                    stringResource(R.string.sponsor_how),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Row {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    TextButton(onClick = {
                        val saved = saveCode(context)
                        Toast.makeText(context, if (saved) R.string.sponsor_saved else R.string.sponsor_save_failed, Toast.LENGTH_LONG).show()
                        if (saved && hasWeChat) context.packageManager.getLaunchIntentForPackage(WECHAT)?.let { runCatching { context.startActivity(it) } }
                    }) { Text(stringResource(if (hasWeChat) R.string.sponsor_save_and_open else R.string.sponsor_save)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.sponsor_close)) }
            }
        },
    )
}

/** Saves the code to Pictures/WickReader (no permission needed on Android 10+). */
private fun saveCode(context: Context): Boolean = runCatching {
    val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.sponsor_wechat)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "wickreader-sponsor.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WickReader")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    true
}.getOrDefault(false)
