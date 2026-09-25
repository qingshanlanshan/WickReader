package com.qshlab.shelf

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.qshlab.shelf.catalog.CatalogRepository
import com.qshlab.shelf.data.AnnotationDao
import com.qshlab.shelf.data.LibraryRepository
import com.qshlab.shelf.data.ShelfDatabase
import com.qshlab.shelf.reader.ReaderRepository
import com.qshlab.shelf.settings.SettingsRepository
import com.qshlab.shelf.tts.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Holds the app-wide singletons (no DI framework needed at this size). */
class ShelfApp : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.qshlab.shelf.AppLocale.wrap(base))
    }

    lateinit var library: LibraryRepository
        private set
    lateinit var readers: ReaderRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var annotations: AnnotationDao
        private set
    lateinit var catalogs: CatalogRepository
        private set
    lateinit var voices: VoiceManager
        private set

    /** For work that outlives a screen (voice downloads). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(contentResolver, httpClient)
        val publicationOpener = PublicationOpener(
            publicationParser = DefaultPublicationParser(
                this,
                assetRetriever = assetRetriever,
                httpClient = httpClient,
                pdfFactory = PdfiumDocumentFactory(this),
            ),
        )
        val db = Room.databaseBuilder(this, ShelfDatabase::class.java, "shelf.db").build()
        annotations = db.annotations()
        library = LibraryRepository(this, db.books(), assetRetriever, publicationOpener, annotations)
        settings = SettingsRepository(dataStore)
        catalogs = CatalogRepository(dataStore)
        readers = ReaderRepository(library, settings)
        voices = VoiceManager(this, appScope)
    }
}

val Context.shelfApp: ShelfApp get() = applicationContext as ShelfApp
