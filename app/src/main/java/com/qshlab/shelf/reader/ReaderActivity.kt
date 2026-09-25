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
import com.qshlab.shelf.tts.displayName
import com.qshlab.shelf.tts.shortTitle
import com.qshlab.shelf.tts.baseLanguage
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.publication.services.positions
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
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.qshlab.shelf.AppLocale.wrap(base))
    }


    private var bookId: Long = -1
    private lateinit var session: ReaderSession
    private lateinit var navigator: EpubNavigatorFragment

    private var overlayVisible by mutableStateOf(false)
    private var settings by mutableStateOf(ReaderSettings())
    private var locator by mutableStateOf<Locator?>(null)
    private var lookupText by mutableStateOf<String?>(null)
    /** Target chosen in the lookup sheet (saved for the book; no reload needed). */
    private var lookupTarget by mutableStateOf<String?>(null)
    private var pageCount by mutableStateOf<Int?>(null)

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
    private var aiEngine: com.qshlab.shelf.tts.AiTtsEngine? = null
    private var utterancesRead = 0
    private var slowVoiceWarned = false
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
            menu.add(Menu.NONE, MENU_LOOKUP, 0, getString(R.string.rd_look_up)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, MENU_HIGHLIGHT, 1, getString(R.string.rd_highlight)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, MENU_NOTE, 2, getString(R.string.rd_note)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, MENU_COPY, 3, getString(R.string.rd_copy)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
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
                    Toast.makeText(this@ReaderActivity, getString(R.string.rd_copied), Toast.LENGTH_SHORT).show()
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
                    translationPair = session.translationPair.first to (lookupTarget ?: session.translationPair.second),
                    onTranslationChange = { setTranslation(it, lookupTarget ?: session.translationPair.second) },
                    onTranslationTargetChange = { setTranslation(session.translation, it) },
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
                    pageCount = pageCount,
                )
                editingHighlight?.let { a ->
                    HighlightSheet(
                        a,
                        onSave = { updated -> lifecycleScope.launch { shelfApp.annotations.update(updated) }; editingHighlight = null },
                        onDelete = { lifecycleScope.launch { shelfApp.annotations.delete(a.id) }; editingHighlight = null },
                        onDismiss = { editingHighlight = null },
                    )
                }
                lookupText?.let {
                    LookupSheet(
                        it,
                        bookSource = session.translationPair.first,
                        target = lookupTarget ?: session.translationPair.second,
                        onTargetChange = ::chooseLookupTarget,
                    ) { lookupText = null }
                }
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
                addView(
                    ReversedSwipeFrame(this@ReaderActivity, active = { layout == PageLayout.HORIZONTAL_RTL }, onSwipe = ::turnPage).apply {
                        addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
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
                if (layout == PageLayout.HORIZONTAL_LTR) return pageTaps.onTap(event)
                // Next page on the left: the left edge moves on.
                val width = navigator.publicationView.width
                return when {
                    event.point.x < width * 0.3f -> true.also { turnPage(forward = true) }
                    event.point.x > width * 0.7f -> true.also { turnPage(forward = false) }
                    else -> false
                }
            }

            override fun onKey(event: org.readium.r2.navigator.input.KeyEvent) =
                if (layout == PageLayout.HORIZONTAL_LTR) pageTaps.onKey(event) else false
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
                    if (pageCount == null) pageCount = runCatching { session.publication.positions().size }.getOrNull()
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
                Toast.makeText(this@ReaderActivity, getString(R.string.rd_bookmark_removed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val here = navigator.currentLocator.value
            val snippet = navigator.firstVisibleElementLocator()?.text?.highlight?.trim()?.take(80)
            shelfApp.annotations.insert(
                Annotation(
                    bookId = bookId,
                    type = AnnotationType.BOOKMARK,
                    locator = here.toJSON().toString(),
                    text = snippet ?: here.title ?: getString(R.string.rd_bookmark),
                    chapter = here.title,
                    progress = (here.locations.totalProgression ?: 0.0).toFloat(),
                    createdAt = System.currentTimeMillis(),
                )
            )
            Toast.makeText(this@ReaderActivity, getString(R.string.rd_bookmark_added), Toast.LENGTH_SHORT).show()
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
            val start = from ?: navigator.firstVisibleElementLocator()?.let { ttsStartFor(it) ?: it }
            val listener = object : TtsNavigator.Listener {
                override fun onStopRequested() = stopTts()
            }
            var handle: ReadAloud? = null
            if (wantsAiVoice()) {
                Toast.makeText(this@ReaderActivity, getString(R.string.rd_tts_loading_ai), Toast.LENGTH_SHORT).show()
                val provider = AiTtsEngineProvider(shelfApp.voices, ChineseConverter.get(this@ReaderActivity))
                val nav = TtsNavigatorFactory(application, session.publication, provider)
                    ?.createNavigator(listener, start, settings.toAiPreferences())?.getOrNull()
                if (nav != null) {
                    aiEngine = provider.engine
                    handle = ReadAloud(nav, ai = true) { s -> nav.submitPreferences(s.toAiPreferences()) }
                } else {
                    Toast.makeText(this@ReaderActivity, getString(R.string.rd_tts_ai_failed), Toast.LENGTH_LONG).show()
                }
            }
            if (handle == null) {
                val factory = TtsNavigatorFactory(application, session.publication)
                if (factory == null) {
                    Toast.makeText(this@ReaderActivity, getString(R.string.rd_tts_unsupported), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val nav = factory.createNavigator(listener, start, settings.toTtsPreferences()).getOrElse {
                    Toast.makeText(this@ReaderActivity, getString(R.string.rd_tts_start_failed, it.message.toString()), Toast.LENGTH_LONG).show()
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
                        aiEngine?.let { engine ->
                            engine.onUtterance(loc.utteranceLocator)
                            if (!slowVoiceWarned && ++utterancesRead > 6 && engine.speedRatio > 1.05) {
                                slowVoiceWarned = true
                                Toast.makeText(this@ReaderActivity, getString(R.string.rd_tts_slow), Toast.LENGTH_LONG).show()
                            }
                        }
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

    /**
     * Readium's read-aloud only starts mid-chapter when the element it gets holds text itself; the
     * first visible element is often a wrapper, and then it silently starts at the top of the file
     * (for one-file books, the start of the book). Find the paragraph on screen by its text in
     * Readium's own content walk and start from that element's locator instead.
     */
    private suspend fun ttsStartFor(visible: Locator): Locator? {
        fun norm(s: String) = s.filterNot { it.isWhitespace() }
        val needle = visible.text.highlight?.let(::norm)?.take(40)?.takeIf { it.length >= 4 } ?: return null
        val fromTop = visible.copy(locations = Locator.Locations(), text = Locator.Text())
        val iterator = session.publication.content(fromTop)?.iterator() ?: return null
        var seen = 0
        while (seen++ < 20_000) {
            val element = iterator.nextOrNull() ?: return null
            if (element.locator.href != visible.href) return null
            val text = (element as? org.readium.r2.shared.publication.services.content.Content.TextualElement)?.text ?: continue
            val t = norm(text)
            if (t.isNotEmpty() && (t.contains(needle) || needle.contains(t.take(40)))) return element.locator
        }
        return null
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
                options += VoiceOption(id, getString(R.string.rd_voice_ai, m.shortTitle(this@ReaderActivity), sp.displayName(this@ReaderActivity)), selected)
            }
        }
        if (handle.ai) {
            options += VoiceOption(SYSTEM_VOICE, getString(R.string.rd_voice_use_system), false)
        } else {
            @Suppress("UNCHECKED_CAST")
            val system = (handle.navigator.voices as Set<AndroidTtsEngine.Voice>)
                .filter { baseLanguage(it.language.code) == lang }
                .sortedWith(compareByDescending<AndroidTtsEngine.Voice> { it.quality.ordinal }.thenBy { it.id.value })
            options += system.map { v ->
                val quality = when (v.quality) {
                    AndroidTtsEngine.Voice.Quality.Highest, AndroidTtsEngine.Voice.Quality.High -> getString(R.string.rd_voice_quality_high)
                    AndroidTtsEngine.Voice.Quality.Normal -> getString(R.string.rd_voice_quality_normal)
                    else -> getString(R.string.rd_voice_quality_low)
                }
                val network = if (v.requiresNetwork) " · " + getString(R.string.rd_voice_needs_network) else ""
                VoiceOption(v.id.value, getString(R.string.rd_voice_system, v.language.locale.displayName, quality) + network + "\n" + v.id.value, v.id.value == chosen)
            }
        }
        if (VoiceCatalog.models.none { it.id in installed && lang in it.languages }) {
            options.add(0, VoiceOption(DOWNLOAD_VOICES, getString(if (VoiceCatalog.models.any { lang in it.languages }) R.string.rd_voice_download else R.string.rd_voice_ai_unsupported), false))
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
            Toast.makeText(this, getString(R.string.rd_tts_missing_data), Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
        } else {
            Toast.makeText(this, getString(R.string.rd_tts_error, cause?.message ?: error.message), Toast.LENGTH_LONG).show()
        }
        stopTts()
    }

    private fun stopTts() {
        if (tts != null) ReadAloudService.stop(this)
        ttsJob?.cancel()
        ttsJob = null
        tts?.navigator?.close()
        tts = null
        aiEngine = null
        ttsActive = false
        ttsPlaying = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch { navigator.applyDecorations(emptyList(), "tts") }
    }

    /** Next / previous page in any layout (volume keys, taps, reversed swipes). */
    private fun turnPage(forward: Boolean) {
        when {
            layout == PageLayout.VERTICAL_RTL -> turnVertical(forward)
            forward -> navigator.goForward(animated = layout == PageLayout.HORIZONTAL_LTR)
            else -> navigator.goBackward(animated = layout == PageLayout.HORIZONTAL_LTR)
        }
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

    /** Whole-book translation: [mode] into [target]; reopens the book when something changed. */
    private fun setTranslation(mode: TranslationMode, target: String) {
        lifecycleScope.launch {
            val book = shelfApp.library.get(bookId) ?: return@launch
            val updated = book.copy(translationMode = mode.takeIf { it != TranslationMode.OFF }?.name, translationTarget = target)
            if (mode != TranslationMode.OFF) {
                val (from, to) = updated.translationPair()
                if (!Translation.isDownloaded(from) || !Translation.isDownloaded(to)) {
                    Toast.makeText(this@ReaderActivity, getString(R.string.tr_downloading_models), Toast.LENGTH_LONG).show()
                }
                val ready = runCatching { Translation.prepare(from, to) }
                if (ready.isFailure) {
                    Toast.makeText(this@ReaderActivity, getString(R.string.tr_download_failed), Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            shelfApp.library.update(updated)
            // Only the target changed while translation is off: nothing on the page changes.
            if (mode == TranslationMode.OFF && session.translation == TranslationMode.OFF) {
                lookupTarget = target
                return@launch
            }
            reload()
        }
    }

    private fun chooseLookupTarget(target: String) {
        lookupTarget = target
        lifecycleScope.launch {
            val book = shelfApp.library.get(bookId) ?: return@launch
            shelfApp.library.update(book.copy(translationTarget = target))
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
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                turnPage(forward = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
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

        /** Opens an already-opened session ([ReaderRepository.open]) in the matching reader. */
        fun intent(context: Context, bookId: Long) =
            Intent(context, if (context.shelfApp.readers[bookId]?.isPdf == true) PdfReaderActivity::class.java else ReaderActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId)
    }
}

/**
 * For horizontal text with the next page on the left. Readium pages left-to-right there, so
 * horizontal swipes are taken over and turned around: swiping right moves forward. Handled in
 * dispatchTouchEvent because Readium's pager forbids its parents from intercepting. A press held
 * past the long-press delay is left alone (text selection).
 */
private class ReversedSwipeFrame(
    context: Context,
    private val active: () -> Boolean,
    private val onSwipe: (forward: Boolean) -> Unit,
) : FrameLayout(context) {
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var swiping = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!active()) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downTime = ev.eventTime; swiping = false }
            android.view.MotionEvent.ACTION_MOVE -> if (!swiping) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val early = ev.eventTime - downTime < android.view.ViewConfiguration.getLongPressTimeout()
                if (early && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                    swiping = true
                    // The page underneath stops following the finger.
                    val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
            android.view.MotionEvent.ACTION_UP -> if (swiping) {
                swiping = false
                onSwipe(ev.x - downX > 0)
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return if (swiping) true else super.dispatchTouchEvent(ev)
    }
}
