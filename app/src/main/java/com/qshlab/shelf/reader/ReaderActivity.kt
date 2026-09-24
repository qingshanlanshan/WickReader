package com.qshlab.shelf.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
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
import com.qshlab.shelf.settings.ReaderSettings
import com.qshlab.shelf.shelfApp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl

@OptIn(ExperimentalReadiumApi::class, FlowPreview::class)
class ReaderActivity : FragmentActivity() {

    private var bookId: Long = -1
    private lateinit var session: ReaderSession
    private lateinit var navigator: EpubNavigatorFragment

    private var overlayVisible by mutableStateOf(false)
    private var settings by mutableStateOf(ReaderSettings())
    private var locator by mutableStateOf<Locator?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1)
        val opened = shelfApp.readers[bookId]
        if (opened == null) {
            // Process was recreated and the publication is gone: go back to the bookshelf.
            supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
            super.onCreate(null)
            finish()
            return
        }
        session = opened
        // One small blocking read so the first page renders with the user's settings.
        settings = runBlocking { shelfApp.settings.reader.first() }

        supportFragmentManager.fragmentFactory = session.navigatorFactory.createFragmentFactory(
            initialLocator = session.initialLocator,
            initialPreferences = settings.toEpubPreferences(),
            listener = object : EpubNavigatorFragment.Listener {
                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))) }
                }
            },
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = FragmentContainerView(this).apply { id = R.id.reader_container }
        val overlay = ComposeView(this).apply {
            setContent {
                ReaderOverlay(
                    title = session.book.title,
                    publication = session.publication,
                    locator = locator,
                    settings = settings,
                    visible = overlayVisible,
                    onDismiss = { setOverlay(false) },
                    onBack = { finish() },
                    onGoToLink = ::goTo,
                    onGoToLocator = { navigator.go(it, animated = false) },
                    onSettingsChange = { s -> lifecycleScope.launch { shelfApp.settings.updateReader(s) } },
                    isTraditional = session.book.isTraditional,
                    bookSimplified = session.simplified,
                    onBookSimplifiedChange = ::setBookSimplified,
                )
            }
        }
        setContentView(
            FrameLayout(this).apply {
                addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        )

        if (savedInstanceState == null) {
            supportFragmentManager.commitNow {
                add(R.id.reader_container, EpubNavigatorFragment::class.java, Bundle(), NAVIGATOR_TAG)
            }
        }
        navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) as EpubNavigatorFragment

        // Tapping the left/right edges turns pages; tapping the middle toggles the menus.
        navigator.addInputListener(DirectionalNavigationAdapter(navigator, animatedTransition = true))
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                setOverlay(!overlayVisible)
                return true
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    shelfApp.settings.reader.collect { s ->
                        settings = s
                        navigator.submitPreferences(s.toEpubPreferences())
                        applySystemBars()
                        // The global 繁→简 default changed in a way that affects this book.
                        val book = shelfApp.library.get(bookId)
                        if (book != null && book.showsSimplified(s.toSimplified) != session.simplified) reload()
                    }
                }
                launch {
                    navigator.currentLocator.collect { locator = it }
                }
                launch {
                    navigator.currentLocator.debounce(800).collect { saveProgress(it) }
                }
            }
        }
        applySystemBars()
    }

    private fun setBookSimplified(on: Boolean) {
        lifecycleScope.launch {
            val book = shelfApp.library.get(bookId) ?: return@launch
            shelfApp.library.update(book.copy(convertToSimplified = on))
            reload()
        }
    }

    /** Reopens the book (e.g. with 繁→简 switched) at the current position. */
    private var reloading = false

    private fun reload() {
        if (reloading) return
        reloading = true
        lifecycleScope.launch {
            val loc = navigator.currentLocator.value
            shelfApp.library.saveProgress(bookId, loc.toJSON().toString(), (loc.locations.totalProgression ?: 0.0).toFloat())
            shelfApp.readers.close(bookId)
            shelfApp.readers.open(bookId)
                .onSuccess {
                    startActivity(intent(this@ReaderActivity, bookId))
                    finish()
                    @Suppress("DEPRECATION") overridePendingTransition(0, 0)
                }
                .onFailure { reloading = false }
        }
    }

    private fun goTo(link: Link) {
        navigator.go(link, animated = false)
        setOverlay(false)
    }

    private fun saveProgress(l: Locator) {
        val progress = (l.locations.totalProgression ?: 0.0).toFloat()
        lifecycleScope.launch { shelfApp.library.saveProgress(bookId, l.toJSON().toString(), progress) }
    }

    private fun setOverlay(visible: Boolean) {
        overlayVisible = visible
        applySystemBars()
    }

    /** Immersive while reading; system bars come back with the menus. */
    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = settings.theme != com.qshlab.shelf.settings.ReaderTheme.DARK
        if (overlayVisible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
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
        private const val NAVIGATOR_TAG = "navigator"

        fun intent(context: Context, bookId: Long) =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
