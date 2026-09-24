package com.qshlab.shelf.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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

val TTS_SPEEDS = listOf(0.8, 1.0, 1.25, 1.5, 2.0)

private fun speedLabel(s: Double) = if (s == s.toInt().toDouble()) "${s.toInt()}.0×" else "$s×"

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
    Surface(
        modifier = modifier.navigationBarsPadding().padding(12.dp),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { pickVoice = true }) { Icon(Icons.Filled.RecordVoiceOver, "选择音色") }
            TextButton(onClick = { onSpeed(TTS_SPEEDS[(TTS_SPEEDS.indexOf(speed).coerceAtLeast(0) + 1) % TTS_SPEEDS.size]) }) {
                Text(speedLabel(speed), fontWeight = FontWeight.SemiBold)
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
                    "列出手机语音引擎里这本书语言的全部声音。标「高质量」的通常更自然；想要更好的声音，可以在系统设置里安装或切换语音引擎。",
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
