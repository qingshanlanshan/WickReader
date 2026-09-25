package com.qshlab.shelf.reader

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
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
import com.qshlab.shelf.chinese.ChineseConverter
import com.qshlab.shelf.data.languageKey
import com.qshlab.shelf.settings.PageLayout
import com.qshlab.shelf.tts.AiTtsEngineProvider
import com.qshlab.shelf.tts.AiTtsPreferences
import com.qshlab.shelf.tts.AiVoiceId
import com.qshlab.shelf.tts.VoiceCatalog
import com.qshlab.shelf.tts.baseLanguage
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.services.search.search
import org.json.JSONObject
import com.qshlab.shelf.data.Annotation
import com.qshlab.shelf.data.AnnotationType
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

    private var layout by mutableStateOf(PageLayout.HORIZONTAL_LTR)
    private var settingsOpen by mutableStateOf(false)

    /** The active read-aloud navigator: AI voices (sherpa-onnx) or the phone's TTS engine. */
    private class ReadAloud(
        val navigator: TtsNavigator<*, *, *, *>,
        val ai: Boolean,
        val applySettings: (ReaderSettings) -> Unit,
    )

    // Read-aloud (TTS) state.
    private var tts: ReadAloud? = null
    private var ttsLocator: Locator? = null
    private var ttsJob: Job? = null
    private var ttsActive by mutableStateOf(false)
    private var ttsPlaying by mutableStateOf(false)
    private var ttsVoices by mutableStateOf<List<VoiceOption>>(emptyList())

    // Bookmarks and highlights of this book.
    private var annotations by mutableStateOf<List<Annotation>>(emptyList())
    private var editingHighlight by mutableStateOf<Annotation?>(null)

    /** Adds 查词/翻译 to the text selection menu (copy/share stay). */
    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, MENU_LOOKUP, 0, "查词/翻译").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, MENU_HIGHLIGHT, 1, "高亮").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, MENU_NOTE, 2, "笔记").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, MENU_COPY, 3, "复制").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId !in listOf(MENU_LOOKUP, MENU_COPY, MENU_HIGHLIGHT, MENU_NOTE)) return false
            val id = item.itemId
            lifecycleScope.launch {
                val selection = navigator.currentSelection()
                val text = selection?.locator?.text?.highlight?.trim()
                navigator.clearSelection()
                if (text.isNullOrEmpty() || selection == null) return@launch
                if (id == MENU_HIGHLIGHT || id == MENU_NOTE) {
                    val a = Annotation(
                        bookId = bookId,
                        type = AnnotationType.HIGHLIGHT,
                        locator = selection.locator.toJSON().toString(),
                        text = text,
                        chapter = selection.locator.title ?: locator?.title,
                        progress = (selection.locator.locations.totalProgression ?: locator?.locations?.totalProgression ?: 0.0).toFloat(),
                        createdAt = System.currentTimeMillis(),
                    )
                    val newId = shelfApp.annotations.insert(a)
                    if (id == MENU_NOTE) editingHighlight = a.copy(id = newId)
                } else if (id == MENU_LOOKUP) {
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
        layout = opened.layout
        settingsOpen = intent.getBooleanExtra(EXTRA_SETTINGS_OPEN, false)
        // One small blocking read so the first page renders with the user's settings.
        settings = runBlocking { shelfApp.settings.reader.first() }

        supportFragmentManager.fragmentFactory = session.navigatorFactory.createFragmentFactory(
            initialLocator = session.initialLocator,
            initialPreferences = settings.toEpubPreferences(layout),
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
                    annotations = annotations,
                    bookmarked = currentBookmarks().isNotEmpty(),
                    onToggleBookmark = ::toggleBookmark,
                    onGoToAnnotation = { a -> Locator.fromJSON(JSONObject(a.locator))?.let { navigator.go(it, animated = false) } },
                    onDeleteAnnotation = { a -> lifecycleScope.launch { shelfApp.annotations.delete(a.id) } },
                    onSearch = ::search,
                    onGoToSearchHit = ::goToSearchHit,
                    layout = layout,
                    layoutChoices = if (session.book.isCjk()) PageLayout.entries else listOf(PageLayout.HORIZONTAL_LTR),
                    onLayoutChange = ::changeLayout,
                    settingsOpen = settingsOpen,
                    onSettingsOpenChange = { settingsOpen = it; if (it) setOverlay(false) },
                )
                editingHighlight?.let { a ->
                    HighlightSheet(
                        a,
                        onSave = { updated -> lifecycleScope.launch { shelfApp.annotations.update(updated) }; editingHighlight = null },
                        onDelete = { lifecycleScope.launch { shelfApp.annotations.delete(a.id) }; editingHighlight = null },
                        onDismiss = { editingHighlight = null },
                    )
                }
                lookupText?.let { LookupSheet(it, session.book.language) { lookupText = null } }
                if (ttsActive) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                        TtsBar(
                            playing = ttsPlaying,
                            speed = settings.ttsSpeed,
                            voices = ttsVoices,
                            onPlayPause = { tts?.navigator?.let { if (ttsPlaying) it.pause() else it.play() } },
                            onPrevious = { tts?.navigator?.skipToPreviousUtterance() },
                            onNext = { tts?.navigator?.skipToNextUtterance() },
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
        val pageTaps = DirectionalNavigationAdapter(navigator, animatedTransition = true)
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                if (layout != PageLayout.VERTICAL_RTL) return pageTaps.onTap(event)
                // Readium lays vertical text out as one sideways scroll: the left edge moves on.
                val width = navigator.publicationView.width
                return when {
                    event.point.x < width * 0.3f -> true.also { turnVertical(forward = true) }
                    event.point.x > width * 0.7f -> true.also { turnVertical(forward = false) }
                    else -> false
                }
            }

            override fun onKey(event: org.readium.r2.navigator.input.KeyEvent) =
                if (layout != PageLayout.VERTICAL_RTL) pageTaps.onKey(event) else false
        })
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                if (settingsOpen) { settingsOpen = false; return true }
                setOverlay(!overlayVisible)
                return true
            }
        })

        navigator.addDecorationListener("highlights", object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                val id = event.decoration.id.removePrefix("h").toLongOrNull() ?: return false
                editingHighlight = annotations.firstOrNull { it.id == id } ?: return false
                return true
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    shelfApp.annotations.observe(bookId).collect { list ->
                        annotations = list
                        navigator.applyDecorations(
                            list.filter { it.type == AnnotationType.HIGHLIGHT }.mapNotNull { a ->
                                val loc = runCatching { Locator.fromJSON(JSONObject(a.locator)) }.getOrNull() ?: return@mapNotNull null
                                Decoration("h${a.id}", loc, Decoration.Style.Highlight(tint = a.color))
                            },
                            "highlights",
                        )
                    }
                }
                launch {
                    shelfApp.settings.reader.collect { s ->
                        settings = s
                        navigator.submitPreferences(s.toEpubPreferences(layout))
                        tts?.applySettings?.invoke(s)
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

    /** Bookmarks on the page being shown (same chapter, within a position or so). */
    private fun currentBookmarks(): List<Annotation> {
        val here = locator ?: return emptyList()
        val href = here.href.toString().substringBefore('#')
        return annotations.filter { a ->
            if (a.type != AnnotationType.BOOKMARK) return@filter false
            val l = runCatching { Locator.fromJSON(JSONObject(a.locator)) }.getOrNull() ?: return@filter false
            if (l.href.toString().substringBefore('#') != href) return@filter false
            val p1 = l.locations.position
            val p2 = here.locations.position
            if (p1 != null && p2 != null) kotlin.math.abs(p1 - p2) <= 1
            else kotlin.math.abs((l.locations.progression ?: 0.0) - (here.locations.progression ?: 0.0)) < 0.01
        }
    }

    private fun toggleBookmark() {
        val existing = currentBookmarks()
        lifecycleScope.launch {
            if (existing.isNotEmpty()) {
                existing.forEach { shelfApp.annotations.delete(it.id) }
                Toast.makeText(this@ReaderActivity, "已删除书签", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val here = navigator.currentLocator.value
            val snippet = navigator.firstVisibleElementLocator()?.text?.highlight?.trim()?.take(80)
            shelfApp.annotations.insert(
                Annotation(
                    bookId = bookId,
                    type = AnnotationType.BOOKMARK,
                    locator = here.toJSON().toString(),
                    text = snippet ?: here.title ?: "书签",
                    chapter = here.title,
                    progress = (here.locations.totalProgression ?: 0.0).toFloat(),
                    createdAt = System.currentTimeMillis(),
                )
            )
            Toast.makeText(this@ReaderActivity, "已添加书签", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun search(query: String): List<SearchHit> {
        val iterator = session.publication.search(query) ?: return emptyList()
        val hits = mutableListOf<SearchHit>()
        try {
            while (hits.size < 300) {
                val page = iterator.next().getOrNull() ?: break
                page.locators.forEach { l ->
                    hits += SearchHit(l.title, l.text.before.orEmpty(), l.text.highlight.orEmpty(), l.text.after.orEmpty(), l)
                }
            }
        } finally {
            iterator.close()
        }
        return hits.take(300)
    }

    private fun goToSearchHit(hit: SearchHit) {
        setOverlay(false)
        navigator.go(hit.locator, animated = false)
        lifecycleScope.launch {
            navigator.applyDecorations(listOf(Decoration("search", hit.locator, Decoration.Style.Underline(tint = Color.rgb(255, 140, 0)))), "search")
        }
    }

    /** The book's language for speech: "zh", "en", "ja", … */
    private fun speechLanguage(): String = baseLanguage(session.book.languageKey()) ?: "en"

    private fun ReaderSettings.toTtsPreferences() = AndroidTtsPreferences(
        speed = ttsSpeed,
        voices = ttsVoices.filterValues { !it.startsWith("ai:") && it != SYSTEM_VOICE }
            .map { (lang, id) -> Language(lang) to AndroidTtsEngine.Voice.Id(id) }.toMap().ifEmpty { null },
    )

    private fun ReaderSettings.toAiPreferences() = AiTtsPreferences(
        language = Language(speechLanguage()),
        speed = ttsSpeed,
        voices = ttsVoices.mapNotNull { (lang, id) -> AiVoiceId.parse(id)?.let { lang to it } }.toMap(),
    )

    /** AI voice unless the user picked a system voice, or no AI model reads this language. */
    private suspend fun wantsAiVoice(): Boolean {
        val lang = speechLanguage()
        val voices = shelfApp.voices
        val chosen = settings.ttsVoices[lang]
        if (chosen != null) return AiVoiceId.parse(chosen)?.let { it.modelId in voices.installed.value } ?: false
        return shelfApp.settings.app.first().preferAiVoice && voices.modelsFor(lang).isNotEmpty()
    }

    /** Starts reading aloud from [from], or from the first sentence on screen. */
    private fun startTts(from: Locator? = null) {
        setOverlay(false)
        if (tts != null) { tts?.navigator?.play(); return }
        lifecycleScope.launch {
            val start = from ?: navigator.firstVisibleElementLocator()
            val listener = object : TtsNavigator.Listener {
                override fun onStopRequested() = stopTts()
            }
            var handle: ReadAloud? = null
            if (wantsAiVoice()) {
                Toast.makeText(this@ReaderActivity, "正在加载 AI 语音…", Toast.LENGTH_SHORT).show()
                val provider = AiTtsEngineProvider(shelfApp.voices, ChineseConverter.get(this@ReaderActivity))
                val nav = TtsNavigatorFactory(application, session.publication, provider)
                    ?.createNavigator(listener, start, settings.toAiPreferences())?.getOrNull()
                if (nav != null) {
                    handle = ReadAloud(nav, ai = true) { s -> nav.submitPreferences(s.toAiPreferences()) }
                } else {
                    Toast.makeText(this@ReaderActivity, "AI 语音加载失败，改用手机自带语音", Toast.LENGTH_LONG).show()
                }
            }
            if (handle == null) {
                val factory = TtsNavigatorFactory(application, session.publication)
                if (factory == null) {
                    Toast.makeText(this@ReaderActivity, "这本书不支持朗读", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val nav = factory.createNavigator(listener, start, settings.toTtsPreferences()).getOrElse {
                    Toast.makeText(this@ReaderActivity, "无法启动朗读：${it.message}", Toast.LENGTH_LONG).show()
                    return@launch
                }
                handle = ReadAloud(nav, ai = false) { s -> nav.submitPreferences(s.toTtsPreferences()) }
            }
            val nav = handle.navigator
            tts = handle
            ttsActive = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Keeps reading with the screen off / in the background, with lock-screen controls.
            ReadAloudService.start(this@ReaderActivity, nav.asMedia3Player()) { runOnUiThread { stopTts() } }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            ttsJob = lifecycleScope.launch {
                launch {
                    nav.location.collect { loc ->
                        ttsLocator = loc.utteranceLocator
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
        val handle = tts ?: return
        val lang = speechLanguage()
        val chosen = settings.ttsVoices[lang]
        val options = mutableListOf<VoiceOption>()
        val installed = shelfApp.voices.installed.value
        VoiceCatalog.models.filter { it.id in installed && lang in it.languages }.forEach { m ->
            val defaultSid = m.defaults[lang]
            m.speakersFor(lang).forEach { sp ->
                val id = AiVoiceId(m.id, sp.sid).toString()
                val selected = handle.ai && (id == chosen || (chosen == null && sp.sid == defaultSid && m == shelfApp.voices.modelsFor(lang).first()))
                options += VoiceOption(id, "AI · ${m.title.substringBefore('（')} · ${sp.name}", selected)
            }
        }
        if (handle.ai) {
            options += VoiceOption(SYSTEM_VOICE, "使用手机自带语音", false)
        } else {
            @Suppress("UNCHECKED_CAST")
            val system = (handle.navigator.voices as Set<AndroidTtsEngine.Voice>)
                .filter { baseLanguage(it.language.code) == lang }
                .sortedWith(compareByDescending<AndroidTtsEngine.Voice> { it.quality.ordinal }.thenBy { it.id.value })
            options += system.map { v ->
                val quality = when (v.quality) {
                    AndroidTtsEngine.Voice.Quality.Highest, AndroidTtsEngine.Voice.Quality.High -> "高质量"
                    AndroidTtsEngine.Voice.Quality.Normal -> "普通"
                    else -> "较低"
                }
                VoiceOption(v.id.value, "系统 · ${v.language.locale.displayName} · $quality${if (v.requiresNetwork) " · 需联网" else ""}\n${v.id.value}", v.id.value == chosen)
            }
        }
        if (VoiceCatalog.models.none { it.id in installed && lang in it.languages }) {
            options.add(0, VoiceOption(DOWNLOAD_VOICES, if (VoiceCatalog.models.any { lang in it.languages }) "⬇ 下载更自然的 AI 语音…" else "⬇ AI 语音暂不支持这种语言", false))
        }
        ttsVoices = options
    }

    private fun chooseVoice(id: String) {
        if (id == DOWNLOAD_VOICES) {
            startActivity(Intent(this, com.qshlab.shelf.ui.SettingsActivity::class.java))
            return
        }
        val handle = tts ?: return
        val lang = speechLanguage()
        val switchesEngine = (AiVoiceId.parse(id) != null) != handle.ai
        lifecycleScope.launch {
            val systemLang = if (!handle.ai && id != SYSTEM_VOICE) {
                @Suppress("UNCHECKED_CAST")
                (handle.navigator.voices as Set<AndroidTtsEngine.Voice>).firstOrNull { it.id.value == id }?.language?.code
            } else null
            val updated = settings.copy(ttsVoices = settings.ttsVoices + (lang to id) + listOfNotNull(systemLang?.let { it to id }))
            shelfApp.settings.updateReader(updated)
            settings = updated
            if (switchesEngine) {
                val from = ttsLocator
                stopTts()
                startTts(from)
            } else {
                refreshVoices()
            }
        }
    }

    private fun onTtsError(error: TtsNavigator.Error) {
        val cause = (error as? TtsNavigator.Error.EngineError<*>)?.cause
        if (cause is AndroidTtsEngine.Error.LanguageMissingData) {
            Toast.makeText(this, "手机缺少这种语言的语音数据，请在系统中下载", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
        } else {
            Toast.makeText(this, "朗读出错：${cause?.message ?: error.message}", Toast.LENGTH_LONG).show()
        }
        stopTts()
    }

    private fun stopTts() {
        if (tts != null) ReadAloudService.stop(this)
        ttsJob?.cancel()
        ttsJob = null
        tts?.navigator?.close()
        tts = null
        ttsActive = false
        ttsPlaying = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch { navigator.applyDecorations(emptyList(), "tts") }
    }

    /**
     * Vertical text: scrolls one screen within the chapter (forward is leftwards), and only moves
     * to the next / previous chapter at its edge. (Readium's goForward would skip to the next chapter.)
     */
    private fun turnVertical(forward: Boolean) {
        lifecycleScope.launch {
            val result = runCatching { navigator.evaluateJavascript(VERTICAL_SCROLL_JS.replace("DIR", if (forward) "1" else "-1")) }.getOrNull()
            if (result == null || "edge" in result) {
                if (forward) navigator.goForward(animated = false) else navigator.goBackward(animated = false)
            }
        }
    }

    /** Reopens the book: horizontal vs vertical text is applied as chapters are read. */
    private fun changeLayout(l: PageLayout) {
        lifecycleScope.launch {
            val book = shelfApp.library.get(bookId) ?: return@launch
            shelfApp.library.update(book.copy(pageLayout = l.name))
            reload()
        }
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
                    // Keep the settings panel open if the change came from it.
                    startActivity(intent(this@ReaderActivity, bookId).putExtra(EXTRA_SETTINGS_OPEN, settingsOpen))
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
        // While reading aloud the volume keys change the volume.
        if (::navigator.isInitialized && settings.volumeKeysTurnPages && !overlayVisible && tts == null) {
            if (layout == PageLayout.VERTICAL_RTL && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
                turnVertical(forward = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                return true
            }
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
        if (tts != null) ReadAloudService.stop(this)
        tts?.navigator?.close()
        // Frees the voice model's memory once the book is closed (not on a 繁简 / translation reload).
        if (isFinishing && !reloading) shelfApp.voices.release()
        super.onDestroy()
        if (isFinishing && ::session.isInitialized) shelfApp.readers.close(bookId, only = session)
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_SETTINGS_OPEN = "settingsOpen"
        private const val NAVIGATOR_TAG = "navigator"
        private const val MENU_LOOKUP = 1001
        private const val MENU_COPY = 1002
        private const val MENU_HIGHLIGHT = 1003
        private const val MENU_NOTE = 1004
        private const val SYSTEM_VOICE = "system"

        /** Scrolls a vertical-rl chapter by one screen; answers "moved" or "edge". */
        private const val VERTICAL_SCROLL_JS = """(function(dir) {
            var se = document.scrollingElement || document.documentElement;
            var before = se.scrollLeft;
            se.scrollLeft = before - dir * Math.round(window.innerWidth * 0.94);
            return Math.abs(se.scrollLeft - before) > 1 ? "moved" : "edge";
        })(DIR)"""
        private const val DOWNLOAD_VOICES = "download"

        fun intent(context: Context, bookId: Long) =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
