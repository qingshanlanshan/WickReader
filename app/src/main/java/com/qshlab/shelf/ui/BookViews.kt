package com.qshlab.shelf.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qshlab.shelf.data.Book
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private val placeholderPalettes = listOf(
    Color(0xFF3E5C76) to Color(0xFF748CAB),
    Color(0xFF2E5E4E) to Color(0xFF6A9C89),
    Color(0xFF7B4B3A) to Color(0xFFC08A6B),
    Color(0xFF5B4B8A) to Color(0xFF9A8FC7),
    Color(0xFF6B5E3C) to Color(0xFFB9A56E),
    Color(0xFF3F4A5A) to Color(0xFF8E9AAF),
)

/** Book cover, or a generated one (title on a colored gradient) when the book has none. */
@Composable
fun Cover(book: Book, modifier: Modifier = Modifier, corner: Dp = 6.dp, small: Boolean = false) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.aspectRatio(2f / 3f).shadow(if (small) 1.dp else 3.dp, shape).clip(shape)) {
        if (book.coverPath != null) {
            AsyncImage(model = File(book.coverPath), contentDescription = book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val (a, b) = placeholderPalettes[abs(book.title.hashCode()) % placeholderPalettes.size]
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(a, b))).padding(if (small) 4.dp else 10.dp)) {
                Text(
                    book.title,
                    color = Color.White,
                    fontSize = if (small) 9.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = if (small) 11.sp else 19.sp,
                    maxLines = if (small) 4 else 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun Progress(progress: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.height(3.dp),
        strokeCap = StrokeCap.Round,
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

private fun Book.subtitle(): String = listOfNotNull(
    author,
    series?.let { s -> seriesIndex?.let { "$s #${formatIndex(it)}" } ?: s },
).joinToString(" · ")

fun formatIndex(i: Double): String = if (i == i.roundToInt().toDouble()) i.roundToInt().toString() else i.toString()

private fun percent(p: Float): String = if (p <= 0f) "未读" else if (p >= 0.995f) "已读完" else "${(p * 100).roundToInt()}%"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridBookCell(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(6.dp)) {
        Cover(book, Modifier.fillMaxWidth())
        Text(book.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        if (book.progress > 0f) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Progress(book.progress, Modifier.weight(1f))
                Text(percent(book.progress), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(book.author ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridStackCell(stack: ShelfItem.Stack, onClick: () -> Unit) {
    Column(Modifier.combinedClickable(onClick = onClick).padding(6.dp)) {
        Box(Modifier.fillMaxWidth()) {
            // Two books peeking out behind the first volume.
            stack.books.drop(1).take(2).reversed().forEachIndexed { i, b ->
                val shift = (2 - i) * 5
                Cover(b, Modifier.fillMaxWidth().padding(start = shift.dp).offset(x = 0.dp, y = (-shift).dp), small = true)
            }
            Cover(stack.books.first(), Modifier.fillMaxWidth().padding(end = 10.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp),
            ) {
                Text("${stack.books.size} 本", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(stack.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text("系列 · ${stack.books.first().author ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListBookRow(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(book, Modifier.width(52.dp), corner = 4.dp, small = true)
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(book.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Progress(book.progress, Modifier.width(96.dp))
                Text(percent(book.progress), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListStackRow(stack: ShelfItem.Stack, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(60.dp)) {
            if (stack.books.size > 1) Cover(stack.books[1], Modifier.width(48.dp).padding(start = 10.dp).offset(y = (-4).dp), corner = 4.dp, small = true)
            Cover(stack.books.first(), Modifier.width(48.dp), corner = 4.dp, small = true)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stack.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text("系列 · ${stack.books.size} 本 · ${stack.books.first().author ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Progress(stack.progress, Modifier.width(96.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactBookRow(book: Book, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(book.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(percent(book.progress), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactStackRow(stack: ShelfItem.Stack, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${stack.name}（${stack.books.size} 本）", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("系列 · ${stack.books.first().author ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box(Modifier.size(8.dp))
    }
}
