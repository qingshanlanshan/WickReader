package com.qshlab.shelf.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import com.qshlab.shelf.chinese.ChineseConverter
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.map
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener

/** Book formats the reader can open right now. */
val SUPPORTED_EXTENSIONS = setOf("epub")

sealed interface ImportResult {
    data class Added(val book: Book) : ImportResult
    data class Skipped(val name: String, val reason: String) : ImportResult
}

/**
 * Owns the book files and their database rows. Imported files are copied into app storage
 * (filesDir/books), so they stay readable regardless of storage permissions.
 */
class LibraryRepository(
    private val context: Context,
    private val dao: BookDao,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
) {
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    /** Drop folder: files put here (e.g. with `adb push`) are imported on launch. */
    val inboxDir: File? = context.getExternalFilesDir("inbox")?.apply { mkdirs() }

    fun observeBooks(): Flow<List<Book>> = dao.observeAll()

    fun observeBook(id: Long): Flow<Book?> = dao.observe(id)

    suspend fun get(id: Long): Book? = dao.get(id)

    suspend fun importUris(uris: List<Uri>): List<ImportResult> = uris.map { importUri(it) }

    /** Imports every supported file in a folder tree picked with OpenDocumentTree. */
    suspend fun importTree(treeUri: Uri): List<ImportResult> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val files = mutableListOf<DocumentFile>()
        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > 6) return
            for (f in dir.listFiles()) {
                if (f.isDirectory) walk(f, depth + 1)
                else if (f.name?.substringAfterLast('.', "")?.lowercase() in SUPPORTED_EXTENSIONS) files += f
            }
        }
        walk(root, 0)
        files.map { importUri(it.uri, it.name, it.length()) }
    }

    suspend fun importInbox(): List<ImportResult> = withContext(Dispatchers.IO) {
        val files = inboxDir?.listFiles()?.filter { it.isFile } ?: return@withContext emptyList()
        files.map { f ->
            importFile(f, f.name, move = true)
        }
    }

    private suspend fun importUri(uri: Uri, knownName: String? = null, knownSize: Long? = null): ImportResult =
        withContext(Dispatchers.IO) {
            val (name, size) = if (knownName != null && knownSize != null) knownName to knownSize else queryNameAndSize(uri)
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED_EXTENSIONS) return@withContext ImportResult.Skipped(name, "暂不支持 .$ext")
            if (dao.countDuplicates(name, size) > 0) return@withContext ImportResult.Skipped(name, "已在书架中")
            val temp = File(context.cacheDir, "import-${UUID.randomUUID()}.$ext")
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { input.copyTo(it) } }
            }
            if (copied.isFailure) return@withContext ImportResult.Skipped(name, "无法读取文件")
            importFile(temp, name, move = true)
        }

    private suspend fun importFile(source: File, name: String, move: Boolean): ImportResult {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) {
            if (move) source.delete()
            return ImportResult.Skipped(name, "暂不支持 .$ext")
        }
        val size = source.length()
        if (dao.countDuplicates(name, size) > 0) {
            if (move) source.delete()
            return ImportResult.Skipped(name, "已在书架中")
        }
        val dest = File(booksDir, "${UUID.randomUUID()}.$ext")
        if (move && source.renameTo(dest).not()) {
            source.copyTo(dest)
            source.delete()
        } else if (!move) {
            source.copyTo(dest)
        }

        val publication = openPublication(dest) ?: run {
            dest.delete()
            return ImportResult.Skipped(name, "文件损坏或格式不支持")
        }
        try {
            val book = describe(publication, dest, name, size).copy(isTraditional = detectTraditional(publication))
            val id = dao.insert(book)
            val coverPath = publication.cover()?.let { saveCover(id, it) }
            val saved = book.copy(id = id, coverPath = coverPath)
            if (coverPath != null) dao.update(saved)
            return ImportResult.Added(saved)
        } finally {
            publication.close()
        }
    }

    /**
     * Opens a book file. With [toSimplified], every (X)HTML resource and the table of contents are
     * converted 繁→简 as they are read; the file itself is never modified.
     */
    suspend fun openPublication(file: File, toSimplified: Boolean = false): Publication? {
        val asset = assetRetriever.retrieve(file.toUrl(isDirectory = false)).getOrElse { return null }
        val converter = if (toSimplified) ChineseConverter.get(context) else null
        return publicationOpener.open(asset, allowUserInteraction = false, onCreatePublication = {
            if (converter != null) {
                container = TransformingContainer(container) { url, resource ->
                    val path = url.toString().substringBefore('?').lowercase()
                    if (path.endsWith(".xhtml") || path.endsWith(".html") || path.endsWith(".htm")) {
                        resource.map { bytes -> Try.success(converter.htmlToSimplified(bytes.decodeToString()).encodeToByteArray()) }
                    } else {
                        resource
                    }
                }
                fun convert(links: List<Link>): List<Link> =
                    links.map { it.copy(title = it.title?.let(converter::toSimplified), children = convert(it.children)) }
                manifest = manifest.copy(tableOfContents = convert(manifest.tableOfContents))
            }
        }).getOrElse {
            asset.close()
            return null
        }
    }

    /** 繁體 if the metadata says so, or if the opening chapters use Traditional-only characters. */
    private suspend fun detectTraditional(publication: Publication): Boolean {
        val lang = publication.metadata.languages.joinToString(",").lowercase()
        if (Regex("zh-(tw|hk|mo|hant)").containsMatchIn(lang)) return true
        if (lang.isNotEmpty() && !lang.startsWith("zh") && !lang.contains(",zh")) return false
        val sample = StringBuilder()
        for (link in publication.readingOrder.take(6)) {
            val html = publication.get(link)?.read()?.getOrNull()?.decodeToString() ?: continue
            sample.append(html.replace(Regex("<[^>]*>"), ""))
            if (sample.length > 20_000) break
        }
        return ChineseConverter.get(context).traditionalRatio(sample.toString()) > 0.03
    }

    private fun describe(publication: Publication, file: File, fileName: String, size: Long): Book {
        val m = publication.metadata
        val baseName = fileName.substringBeforeLast('.')
        val title = m.title?.takeIf { it.isNotBlank() } ?: baseName
        val author = m.authors.map { it.name }.filter { it.isNotBlank() }.joinToString("、").ifBlank { null }

        // Prefer real metadata (calibre:series / EPUB3 collections), then title and file name patterns.
        val metaSeries = (m.belongsToSeries + m.belongsToCollections).firstOrNull { it.name.isNotBlank() }
        val guess = SeriesDetector.guess(title) ?: SeriesDetector.guess(baseName)
        val series = metaSeries?.name ?: guess?.series
        val index = metaSeries?.position ?: guess?.index

        return Book(
            filePath = file.absolutePath,
            fileName = fileName,
            format = fileName.substringAfterLast('.', "").lowercase(),
            title = title,
            author = author,
            series = series,
            seriesIndex = index,
            language = m.languages.firstOrNull(),
            coverPath = null,
            addedAt = System.currentTimeMillis(),
            fileSize = size,
        )
    }

    private fun saveCover(id: Long, bitmap: Bitmap): String? = runCatching {
        val maxH = 720
        val scaled = if (bitmap.height > maxH) {
            Bitmap.createScaledBitmap(bitmap, bitmap.width * maxH / bitmap.height, maxH, true)
        } else {
            bitmap
        }
        val f = File(coversDir, "$id.jpg")
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        f.absolutePath
    }.getOrNull()

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "book"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return name to size
    }

    suspend fun update(book: Book) = dao.update(book)

    suspend fun saveProgress(id: Long, locatorJson: String, progress: Float) {
        dao.updateProgress(id, locatorJson, progress, System.currentTimeMillis(), finished = progress >= 0.995f)
    }

    suspend fun touch(id: Long) = dao.touch(id, System.currentTimeMillis())

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        dao.delete(book.id)
        File(book.filePath).delete()
        book.coverPath?.let { File(it).delete() }
    }
}
