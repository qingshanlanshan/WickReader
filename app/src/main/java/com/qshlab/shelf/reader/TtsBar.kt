package com.qshlab.shelf.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class VoiceOption(val id: String, val label: String, val selected: Boolean)

val TTS_SPEEDS = listOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.75, 2.0, 2.5, 3.0)

private fun speedLabel(s: Double) = "%.2f".format(s).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it } + "×"

/** Floating read-aloud controls, shown while TTS is active. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsBar(
    playing: Boolean,
    speed: Double,
    voices: List<VoiceOption>,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: (Double) -> Unit,
    onVoice: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickVoice by remember { mutableStateOf(false) }
    var pickSpeed by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.navigationBarsPadding().padding(12.dp),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { pickVoice = true }) { Icon(Icons.Filled.RecordVoiceOver, "选择音色") }
            Box {
                TextButton(onClick = { pickSpeed = true }) {
                    Text(speedLabel(speed), fontWeight = FontWeight.SemiBold)
                }
                DropdownMenu(expanded = pickSpeed, onDismissRequest = { pickSpeed = false }) {
                    Text("朗读速度", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    TTS_SPEEDS.forEach { v ->
                        val selected = kotlin.math.abs(v - speed) < 0.001
                        DropdownMenuItem(
                            text = { Text(speedLabel(v) + if (v == 1.0) "（正常）" else "", fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                            trailingIcon = { if (selected) Icon(Icons.Filled.Check, null) },
                            onClick = { pickSpeed = false; onSpeed(v) },
                        )
                    }
                }
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Filled.SkipPrevious, "上一句") }
            FilledIconButton(onClick = onPlayPause) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "暂停" else "播放") }
            IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, "下一句") }
            IconButton(onClick = onStop) { Icon(Icons.Filled.Close, "停止朗读") }
        }
    }
    if (pickVoice) {
        ModalBottomSheet(onDismissRequest = { pickVoice = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("选择音色", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                Text(
                    "「AI」开头的是离线 AI 语音（在设置里下载）；「系统」开头的是手机自带的语音引擎。",
                    Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (voices.isEmpty()) Text("没有可选的声音", Modifier.padding(20.dp))
                LazyColumn {
                    items(voices, key = { it.id }) { v ->
                        Text(
                            (if (v.selected) "✓ " else "   ") + v.label,
                            Modifier.fillMaxWidth().clickable { onVoice(v.id); pickVoice = false }.padding(horizontal = 20.dp, vertical = 12.dp),
                            fontWeight = if (v.selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
