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
    data class Found(val file: File, val size: Long, val modified: Long, val inLibrary: Boolean) {
        val folder: String get() = file.parentFile?.path?.removePrefix(root.path)?.ifEmpty { "/" } ?: "/"
    }

    val root: File get() = Environment.getExternalStorageDirectory()

    /** Android 11+ needs "all files access"; older versions the storage permission. */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    suspend fun scan(formats: Set<String>, txtMinBytes: Long, libraryKeys: Set<String>, limit: Int = 5000): List<Found> =
        withContext(Dispatchers.IO) {
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
                        val ext = f.extension.lowercase()
                        if (ext !in formats) continue
                        val size = f.length()
                        if (ext == "txt" && size < txtMinBytes) continue
                        out += Found(f, size, f.lastModified(), "${f.name}:$size" in libraryKeys)
                    }
                }
            }
            walk(root, 0)
            out.sortedWith(compareBy<Found> { it.folder }.thenBy { it.file.name })
        }
}
