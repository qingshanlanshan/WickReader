package com.qshlab.shelf.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Walks shared storage for e-book files ("扫描手机"). */
object BookScanner {
    /** A format choice in the scan, with the extensions it covers. */
    data class Format(val key: String, val label: String, val extensions: Set<String>, val supported: Boolean = true)

    val FORMATS = listOf(
        Format("epub", "EPUB", setOf("epub")),
        Format("pdf", "PDF", setOf("pdf")),
        Format("txt", "TXT", setOf("txt", "md")),
        Format("fb2", "FB2", setOf("fb2")),
        Format("docx", "Word", setOf("docx")),
        Format("cbz", "漫画 CBZ", setOf("cbz")),
        Format("html", "网页 HTML", setOf("html", "htm", "xhtml")),
        Format("mobi", "MOBI / AZW3", setOf("mobi", "azw", "azw3", "prc"), supported = false),
    )

    val DEFAULT_FORMATS = FORMATS.map { it.key }.toSet() - "html"

    data class Found(val file: File, val size: Long, val modified: Long, val inLibrary: Boolean, val supported: Boolean) {
        val folder: String get() = file.parentFile?.path?.removePrefix(root.path)?.ifEmpty { "/" } ?: "/"
    }

    val root: File get() = Environment.getExternalStorageDirectory()

    /** Android 11+ needs "all files access"; older versions the storage permission. */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    suspend fun scan(formatKeys: Set<String>, txtMinBytes: Long, libraryKeys: Set<String>, limit: Int = 5000): List<Found> =
        withContext(Dispatchers.IO) {
            val chosen = FORMATS.filter { it.key in formatKeys }
            val formats = chosen.flatMap { it.extensions }.toSet()
            val unsupported = chosen.filter { !it.supported }.flatMap { it.extensions }.toSet()
            val out = mutableListOf<Found>()
            suspend fun walk(dir: File, depth: Int) {
                ensureActive()
                if (depth > 10 || out.size >= limit) return
                val children = dir.listFiles() ?: return
                for (f in children) {
                    if (f.name.startsWith(".")) continue
                    if (f.isDirectory) {
                        // App-private folders are unreadable on modern Android, and full of caches on old ones.
                        if (depth == 0 && f.name == "Android") continue
                        walk(f, depth + 1)
                    } else {
                        val ext = Converters.extensionOf(f.name)
                        if (ext !in formats) continue
                        val size = f.length()
                        if ((ext == "txt" || ext == "md" || ext == "html" || ext == "htm") && size < txtMinBytes) continue
                        out += Found(f, size, f.lastModified(), "${f.name}:$size" in libraryKeys, ext !in unsupported)
                    }
                }
            }
            walk(root, 0)
            out.sortedWith(compareBy<Found> { it.folder }.thenBy { it.file.name })
        }
}
