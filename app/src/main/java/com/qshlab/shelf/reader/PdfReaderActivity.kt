package com.qshlab.shelf.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qshlab.shelf.R
import com.qshlab.shelf.ShelfTheme
import com.qshlab.shelf.data.Annotation
import com.qshlab.shelf.data.AnnotationType
import com.qshlab.shelf.settings.ReaderSettings
import com.qshlab.shelf.shelfApp
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.adapter.pdfium.navigator.PdfiumSettings
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positions

/**
 * PDF reader (Readium + pdfium). PDFs are fixed pages, so it offers page navigation, 目录,
 * bookmarks, and page / scroll modes — no fonts, 繁简 or read-aloud.
 */
@OptIn(ExperimentalReadiumApi::class, FlowPreview::class, ExperimentalMaterial3Api::class)
class PdfReaderActivity : FragmentActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.qshlab.shelf.AppLocale.wrap(base))
    }


    private var bookId: Long = -1
    private lateinit var session: ReaderSession
    private lateinit var navigator: PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>

    private var overlayVisible by mutableStateOf(false)
    private var settings by mutableStateOf(ReaderSettings())
    private var locator by mutableStateOf<Locator?>(null)
    private var annotations by mutableStateOf<List<Annotation>>(emptyList())

    private fun ReaderSettings.toPdfPreferences() = PdfiumPreferences(
        fit = if (pdfFitWidth) Fit.WIDTH else Fit.CONTAIN,
        scroll = pdfScroll,
        scrollAxis = Axis.VERTICAL,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1)
        val opened = shelfApp.readers[bookId]?.takeIf { it.isPdf }
        if (opened == null) {
            supportFragmentManager.fragmentFactory = PdfNavigatorFragment.createDummyFactory(org.readium.adapter.pdfium.navigator.PdfiumEngineProvider())
            super.onCreate(null)
            finish()
            return
        }
        session = opened
        settings = runBlocking { shelfApp.settings.reader.first() }
        supportFragmentManager.fragmentFactory = session.pdfFactory!!.createFragmentFactory(
            initialLocator = session.initialLocator,
            initialPreferences = settings.toPdfPreferences(),
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = FragmentContainerView(this).apply { id = R.id.reader_container }
        val overlay = ComposeView(this).apply { setContent { ShelfTheme { Overlay() } } }
        setContentView(
            FrameLayout(this).apply {
                addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        )
        if (savedInstanceState == null) {
            supportFragmentManager.commitNow { add(R.id.reader_container, PdfNavigatorFragment::class.java, Bundle(), TAG) }
        }
        @Suppress("UNCHECKED_CAST")
        navigator = supportFragmentManager.findFragmentByTag(TAG) as PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>

        navigator.addInputListener(DirectionalNavigationAdapter(navigator))
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                setOverlay(!overlayVisible)
                return true
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { shelfApp.annotations.observe(bookId).collect { annotations = it } }
                launch {
                    shelfApp.settings.reader.collect { s ->
                        settings = s
                        navigator.submitPreferences(s.toPdfPreferences())
                    }
                }
                launch { navigator.currentLocator.collect { locator = it } }
                launch { navigator.currentLocator.debounce(800).collect { saveProgress(it) } }
            }
        }
        applySystemBars()
    }

    @androidx.compose.runtime.Composable
    private fun Overlay() {
        var sheet by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val pageCount = remember { runBlocking { session.publication.positions().size } }
        Box(Modifier.fillMaxSize()) {
            if (overlayVisible) Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { setOverlay(false) })
            AnimatedVisibility(overlayVisible, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                        Text(session.book.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        val marked = currentBookmarks().isNotEmpty()
                        IconButton(onClick = ::toggleBookmark) { Icon(if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (marked) "删除书签" else "添加书签") }
                        IconButton(onClick = { sheet = "toc" }) { Icon(Icons.AutoMirrored.Filled.List, "目录") }
                        IconButton(onClick = { sheet = "settings" }) { Icon(Icons.Filled.Tune, "显示设置") }
                    }
                }
            }
            AnimatedVisibility(
                overlayVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        val progress = (locator?.locations?.totalProgression ?: 0.0).toFloat()
                        var dragging by remember { mutableStateOf<Float?>(null) }
                        val page = dragging?.let { (it * (pageCount - 1)).roundToInt() + 1 } ?: locator?.locations?.position ?: 1
                        Row {
                            Text(locator?.title ?: "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Text("$page / $pageCount", style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = dragging ?: progress,
                            onValueChange = { dragging = it },
                            onValueChangeFinished = {
                                val target = dragging
                                dragging = null
                                if (target != null) scope.launch { session.publication.locateProgression(target.toDouble())?.let { navigator.go(it, animated = false) } }
                            },
                        )
                    }
                }
            }
        }
        if (sheet == "toc") {
            NavigationSheet(
                toc = remember { flattenToc(session.publication.tableOfContents) },
                currentHref = null,
                annotations = annotations,
                onGoToLink = { sheet = null; navigator.go(it, animated = false); setOverlay(false) },
                onGoToAnnotation = { a -> sheet = null; Locator.fromJSON(JSONObject(a.locator))?.let { navigator.go(it, animated = false) }; setOverlay(false) },
                onDeleteAnnotation = { a -> lifecycleScope.launch { shelfApp.annotations.delete(a.id) } },
                onDismiss = { sheet = null },
            )
        }
        if (sheet == "settings") {
            ModalBottomSheet(onDismissRequest = { sheet = null }) {
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("翻页方式", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!settings.pdfScroll, { update(settings.copy(pdfScroll = false)) }, label = { Text("一页一页翻") })
                        FilterChip(settings.pdfScroll, { update(settings.copy(pdfScroll = true)) }, label = { Text("上下滚动") })
                    }
                    Text("页面大小", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(settings.pdfFitWidth, { update(settings.copy(pdfFitWidth = true)) }, label = { Text("适应宽度") })
                        FilterChip(!settings.pdfFitWidth, { update(settings.copy(pdfFitWidth = false)) }, label = { Text("整页") })
                    }
                    Text("双指可以放大；PDF 不支持改字体、繁简转换和朗读。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    private fun update(s: ReaderSettings) {
        lifecycleScope.launch { shelfApp.settings.updateReader(s) }
    }

    private fun currentBookmarks(): List<Annotation> {
        val page = locator?.locations?.position ?: return emptyList()
        return annotations.filter { a ->
            a.type == AnnotationType.BOOKMARK &&
                runCatching { Locator.fromJSON(JSONObject(a.locator))?.locations?.position }.getOrNull() == page
        }
    }

    private fun toggleBookmark() {
        val existing = currentBookmarks()
        lifecycleScope.launch {
            if (existing.isNotEmpty()) {
                existing.forEach { shelfApp.annotations.delete(it.id) }
                Toast.makeText(this@PdfReaderActivity, "已删除书签", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val here = navigator.currentLocator.value
            shelfApp.annotations.insert(
                Annotation(
                    bookId = bookId,
                    type = AnnotationType.BOOKMARK,
                    locator = here.toJSON().toString(),
                    text = "第 ${here.locations.position ?: "?"} 页",
                    chapter = here.title,
                    progress = (here.locations.totalProgression ?: 0.0).toFloat(),
                    createdAt = System.currentTimeMillis(),
                )
            )
            Toast.makeText(this@PdfReaderActivity, "已添加书签", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveProgress(l: Locator) {
        lifecycleScope.launch { shelfApp.library.saveProgress(bookId, l.toJSON().toString(), (l.locations.totalProgression ?: 0.0).toFloat()) }
    }

    private fun setOverlay(visible: Boolean) {
        overlayVisible = visible
        applySystemBars()
    }

    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (overlayVisible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (::navigator.isInitialized && settings.volumeKeysTurnPages && !overlayVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> return true.also { navigator.goForward(animated = true) }
                KeyEvent.KEYCODE_VOLUME_UP -> return true.also { navigator.goBackward(animated = true) }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        if (::navigator.isInitialized) saveProgress(navigator.currentLocator.value)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && ::session.isInitialized) shelfApp.readers.close(bookId, only = session)
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"
        private const val TAG = "pdf"

        fun intent(context: Context, bookId: Long) = Intent(context, PdfReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
