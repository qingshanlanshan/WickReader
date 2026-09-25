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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.qshlab.shelf.R
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

@Composable
private fun percent(p: Float): String = if (p <= 0f) stringResource(R.string.status_unread) else if (p >= 0.995f) stringResource(R.string.status_finished) else "${(p * 100).roundToInt()}%"

@Composable
private fun Book.status(): String = when {
    finished -> stringResource(R.string.status_finished)
    progress > 0f -> percent(progress)
    wantToRead -> stringResource(R.string.status_want)
    else -> stringResource(R.string.status_unread)
}

/** Check mark shown on items while selecting several books. */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Icon(
        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
        if (selected) stringResource(R.string.lib_selected) else stringResource(R.string.lib_not_selected),
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = modifier.background(MaterialTheme.colorScheme.surface, CircleShape),
    )
}

@Composable
private fun ShelfItem.Stack.subtitle() = when (kind) {
    GroupKind.SERIES -> stringResource(R.string.lib_series_count_author, books.size, books.first().author ?: "")
    GroupKind.AUTHOR -> stringResource(R.string.lib_author_count, books.size)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridBookCell(book: Book, onClick: () -> Unit, onLongClick: () -> Unit, badge: String? = null, selected: Boolean? = null) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(6.dp)) {
        Box {
            Cover(book, Modifier.fillMaxWidth())
            if (selected != null) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(4.dp))
            if (badge != null) {
                Surface(
                    shape = RoundedCornerShape(topStart = 6.dp, bottomEnd = 8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Text(badge, Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(book.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        if (book.progress > 0f) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Progress(book.progress, Modifier.weight(1f))
                Text(percent(book.progress), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(listOfNotNull(if (book.wantToRead) stringResource(R.string.status_want) else null, book.author).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridStackCell(stack: ShelfItem.Stack, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, selected: Boolean? = null) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(6.dp)) {
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
                Text(stringResource(R.string.lib_book_count, stack.books.size), Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
            }
            if (selected != null) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(stack.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(if (stack.kind == GroupKind.SERIES) stringResource(R.string.lib_series_by, stack.books.first().author ?: "") else stringResource(R.string.lib_author), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListBookRow(book: Book, onClick: () -> Unit, onLongClick: () -> Unit, selected: Boolean? = null) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) SelectionMark(selected, Modifier.padding(end = 10.dp))
        Cover(book, Modifier.width(52.dp), corner = 4.dp, small = true)
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(book.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Progress(book.progress, Modifier.width(96.dp))
                Text(book.status(), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListStackRow(stack: ShelfItem.Stack, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, selected: Boolean? = null) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) SelectionMark(selected, Modifier.padding(end = 10.dp))
        Box(Modifier.width(60.dp)) {
            if (stack.books.size > 1) Cover(stack.books[1], Modifier.width(48.dp).padding(start = 10.dp).offset(y = (-4).dp), corner = 4.dp, small = true)
            Cover(stack.books.first(), Modifier.width(48.dp), corner = 4.dp, small = true)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stack.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(stack.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Progress(stack.progress, Modifier.width(96.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactBookRow(book: Book, onClick: () -> Unit, onLongClick: () -> Unit, selected: Boolean? = null) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) SelectionMark(selected, Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(book.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(book.status(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactStackRow(stack: ShelfItem.Stack, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, selected: Boolean? = null) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selected != null) SelectionMark(selected, Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.lib_stack_name_count, stack.name, stack.books.size), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stack.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box(Modifier.size(8.dp))
    }
}

/** "继续阅读" card for the most recently read book. */
@Composable
fun ContinueCard(book: Book, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(book, Modifier.width(54.dp), corner = 4.dp, small = true)
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.lib_continue_reading), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val chapter = remember(book.locator) {
                    book.locator?.let { runCatching { org.json.JSONObject(it).optString("title") }.getOrNull() }?.takeIf { it.isNotBlank() }
                }
                if (chapter != null) {
                    Text(chapter, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Progress(book.progress, Modifier.width(120.dp))
                    Text(
                        when {
                            book.finished -> stringResource(R.string.status_finished)
                            book.progress <= 0f -> stringResource(R.string.lib_just_started)
                            else -> percent(book.progress)
                        },
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
