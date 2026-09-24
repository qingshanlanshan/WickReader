package com.qshlab.shelf

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.qshlab.shelf.data.AnnotationDao
import com.qshlab.shelf.data.LibraryRepository
import com.qshlab.shelf.data.ShelfDatabase
import com.qshlab.shelf.reader.ReaderRepository
import com.qshlab.shelf.settings.SettingsRepository
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Holds the app-wide singletons (no DI framework needed at this size). */
class ShelfApp : Application() {
    lateinit var library: LibraryRepository
        private set
    lateinit var readers: ReaderRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var annotations: AnnotationDao
        private set

    override fun onCreate() {
        super.onCreate()
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(contentResolver, httpClient)
        val publicationOpener = PublicationOpener(
            publicationParser = DefaultPublicationParser(
                this,
                assetRetriever = assetRetriever,
                httpClient = httpClient,
                pdfFactory = null,
            ),
        )
        val db = Room.databaseBuilder(this, ShelfDatabase::class.java, "shelf.db").build()
        annotations = db.annotations()
        library = LibraryRepository(this, db.books(), assetRetriever, publicationOpener, annotations)
        settings = SettingsRepository(dataStore)
        readers = ReaderRepository(library, settings)
    }
}

val Context.shelfApp: ShelfApp get() = applicationContext as ShelfApp
