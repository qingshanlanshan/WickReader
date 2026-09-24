package com.qshlab.shelf.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.speech.tts.TextToSpeech
import android.view.ActionMode
import android.view.WindowManager
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
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
import com.qshlab.shelf.translate.Translation
import com.qshlab.shelf.translate.TranslationMode
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.getOrElse

@OptIn(ExperimentalReadiumApi::class, FlowPreview::class)
class ReaderActivity : FragmentActivity() {

    private var bookId: Long = -1
    private lateinit var session: ReaderSession
    private lateinit var navigator: EpubNavigatorFragment

    private var overlayVisible by mutableStateOf(false)
    private var settings by mutableStateOf(ReaderSettings())
    private var locator by mutableStateOf<Locator?>(null)
    private var lookupText by mutableStateOf<String?>(null)

    // Read-aloud (TTS) state.
    private var tts: AndroidTtsNavigator? = null
    private var ttsJob: Job? = null
    private var ttsActive by mutableStateOf(false)
    private var ttsPlaying by mutableStateOf(false)
    private var ttsVoices by mutableStateOf<List<VoiceOption>>(emptyList())

    /** Adds 查词/翻译 to the text selection menu (copy/share stay). */
    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, MENU_LOOKUP, 0, "查词/翻译").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, MENU_COPY, 1, "复制").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != MENU_LOOKUP && item.itemId != MENU_COPY) return false
            val id = item.itemId
            lifecycleScope.launch {
                val text = navigator.currentSelection()?.locator?.text?.highlight?.trim()
                navigator.clearSelection()
                if (text.isNullOrEmpty()) return@launch
                if (id == MENU_LOOKUP) {
                    lookupText = text
                } else {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text))
                    Toast.makeText(this@ReaderActivity, "已复制", Toast.LENGTH_SHORT).show()
                }
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {}
    }

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
            configuration = EpubNavigatorFragment.Configuration {
                selectionActionModeCallback = selectionCallback
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
                    translation = session.translation,
                    translationLabel = "${Translation.nameOf(session.translationPair.first)} → ${Translation.nameOf(session.translationPair.second)}",
                    onTranslationChange = ::setTranslation,
                    onReadAloud = ::startTts,
                )
                lookupText?.let { LookupSheet(it, session.book.language) { lookupText = null } }
                if (ttsActive) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                        TtsBar(
                            playing = ttsPlaying,
                            speed = settings.ttsSpeed,
                            voices = ttsVoices,
                            onPlayPause = { tts?.let { if (ttsPlaying) it.pause() else it.play() } },
                            onPrevious = { tts?.skipToPreviousUtterance() },
                            onNext = { tts?.skipToNextUtterance() },
                            onSpeed = { v -> lifecycleScope.launch { shelfApp.settings.updateReader(settings.copy(ttsSpeed = v)) } },
                            onVoice = ::chooseVoice,
                            onStop = ::stopTts,
                        )
                    }
                }
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
                        tts?.submitPreferences(s.toTtsPreferences())
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

    private fun ReaderSettings.toTtsPreferences() = AndroidTtsPreferences(
        speed = ttsSpeed,
        voices = ttsVoices.mapNotNull { (lang, id) -> Language(lang) to AndroidTtsEngine.Voice.Id(id) }.toMap().ifEmpty { null },
    )

    /** Starts reading aloud from the first sentence on screen. */
    private fun startTts() {
        setOverlay(false)
        if (tts != null) { tts?.play(); return }
        lifecycleScope.launch {
            val factory = TtsNavigatorFactory(application, session.publication)
            if (factory == null) {
                Toast.makeText(this@ReaderActivity, "这本书不支持朗读", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val start = navigator.firstVisibleElementLocator()
            val nav = factory.createNavigator(
                listener = object : TtsNavigator.Listener {
                    override fun onStopRequested() = stopTts()
                },
                initialLocator = start,
                initialPreferences = settings.toTtsPreferences(),
            ).getOrElse {
                Toast.makeText(this@ReaderActivity, "无法启动朗读：${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            tts = nav
            ttsActive = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ttsJob = lifecycleScope.launch {
                launch {
                    nav.location.collect { loc ->
                        // Highlight the sentence being read and keep it on screen.
                        navigator.applyDecorations(
                            listOf(Decoration("tts", loc.utteranceLocator, Decoration.Style.Highlight(tint = Color.argb(255, 255, 214, 102)))),
                            "tts",
                        )
                        navigator.go(loc.utteranceLocator, animated = false)
                    }
                }
                launch {
                    nav.playback.collect { pb ->
                        ttsPlaying = pb.playWhenReady
                        val failure = pb.state as? TtsNavigator.State.Failure
                        if (failure != null) onTtsError(failure.error)
                        if (pb.state is TtsNavigator.State.Ended) stopTts()
                    }
                }
            }
            refreshVoices()
            nav.play()
        }
    }

    private fun refreshVoices() {
        val nav = tts ?: return
        val bookLang = session.book.language?.substringBefore('-')?.lowercase()
        val chosen = settings.ttsVoices.values.toSet()
        ttsVoices = nav.voices
            .filter { bookLang == null || it.language.code.substringBefore('-').lowercase() == bookLang || (bookLang == "zh" && it.language.code.startsWith("cmn")) }
            .sortedWith(compareByDescending<AndroidTtsEngine.Voice> { it.quality.ordinal }.thenBy { it.id.value })
            .map { v ->
                val quality = when (v.quality) {
                    AndroidTtsEngine.Voice.Quality.Highest, AndroidTtsEngine.Voice.Quality.High -> "高质量"
                    AndroidTtsEngine.Voice.Quality.Normal -> "普通"
                    else -> "较低"
                }
                VoiceOption(v.id.value, "${v.language.locale.displayName} · $quality${if (v.requiresNetwork) " · 需联网" else ""}\n${v.id.value}", v.id.value in chosen)
            }
    }

    private fun chooseVoice(id: String) {
        val voice = tts?.voices?.firstOrNull { it.id.value == id } ?: return
        lifecycleScope.launch {
            shelfApp.settings.updateReader(settings.copy(ttsVoices = settings.ttsVoices + (voice.language.code to id)))
            refreshVoices()
        }
    }

    private fun onTtsError(error: TtsNavigator.Error) {
        val engineError = (error as? TtsNavigator.Error.EngineError<*>)?.cause as? AndroidTtsEngine.Error
        if (engineError is AndroidTtsEngine.Error.LanguageMissingData) {
            Toast.makeText(this, "手机缺少这种语言的语音数据，请在系统中下载", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
        } else {
            Toast.makeText(this, "朗读出错：${error.message}", Toast.LENGTH_LONG).show()
        }
        stopTts()
    }

    private fun stopTts() {
        ttsJob?.cancel()
        ttsJob = null
        tts?.close()
        tts = null
        ttsActive = false
        ttsPlaying = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch { navigator.applyDecorations(emptyList(), "tts") }
    }

    private fun setTranslation(mode: TranslationMode) {
        lifecycleScope.launch {
            val book = shelfApp.library.get(bookId) ?: return@launch
            if (mode != TranslationMode.OFF) {
                val (from, to) = book.translationPair()
                if (!Translation.isDownloaded(from) || !Translation.isDownloaded(to)) {
                    Toast.makeText(this@ReaderActivity, "正在下载翻译模型（约 30 MB，只需一次）…", Toast.LENGTH_LONG).show()
                }
                val ready = runCatching { Translation.prepare(from, to) }
                if (ready.isFailure) {
                    Toast.makeText(this@ReaderActivity, "模型下载失败，请联网后重试", Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            shelfApp.library.update(book.copy(translationMode = mode.takeIf { it != TranslationMode.OFF }?.name))
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
        tts?.close()
        super.onDestroy()
        if (isFinishing && ::session.isInitialized) shelfApp.readers.close(bookId, only = session)
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"
        private const val NAVIGATOR_TAG = "navigator"
        private const val MENU_LOOKUP = 1001
        private const val MENU_COPY = 1002

        fun intent(context: Context, bookId: Long) =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
