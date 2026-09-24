package com.qshlab.shelf.catalog

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.readium.r2.opds.OPDS1Parser
import org.readium.r2.shared.opds.Feed
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url

data class Catalog(val name: String, val url: String, val builtIn: Boolean = false)

/**
 * OPDS catalogs: open book-catalog feeds (the standard KOReader, Moon+ Reader, Calibre use).
 * Ships with public-domain sources; users can add any OPDS URL (e.g. their own Calibre server).
 */
class CatalogRepository(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("catalogs.user")

    val builtIn = listOf(
        Catalog("Project Gutenberg（7 万多本公版书，含中文古典）", "https://www.gutenberg.org/ebooks.opds/", builtIn = true),
    )

    val catalogs: Flow<List<Catalog>> = store.data.map { p ->
        builtIn + (p[key] ?: "").lines().filter { '\t' in it }.map { it.split('\t', limit = 2).let { (n, u) -> Catalog(n, u) } }
    }

    suspend fun add(name: String, url: String) = store.edit { p ->
        p[key] = ((p[key] ?: "").lines().filter { it.isNotBlank() } + "${name.replace('\t', ' ')}\t${url.trim()}").joinToString("\n")
    }

    suspend fun remove(url: String) = store.edit { p ->
        p[key] = (p[key] ?: "").lines().filter { it.isNotBlank() && it.substringAfter('\t') != url }.joinToString("\n")
    }
}

sealed interface FeedResult {
    data class Loaded(val feed: Feed, val searchTemplate: String?) : FeedResult
    data class Failed(val message: String) : FeedResult
}

object Opds {
    suspend fun load(url: String): FeedResult {
        val parsed = OPDS1Parser.parseUrlString(url).getOrNull()
            ?: return FeedResult.Failed("无法打开这个书库（请检查网络或地址）")
        val feed = parsed.feed ?: return FeedResult.Failed("这个地址不是 OPDS 书库")
        val template = runCatching { OPDS1Parser.retrieveOpenSearchTemplate(feed).getOrNull() }.getOrNull()
            ?: gutenbergSearch(url)
        return FeedResult.Loaded(feed, template)
    }

    /** Gutenberg's OpenSearch description is sometimes slow; its search URL is stable. */
    private fun gutenbergSearch(url: String) =
        if ("gutenberg.org" in url) "https://www.gutenberg.org/ebooks/search.opds/?query={searchTerms}" else null

    fun searchUrl(template: String, query: String): String =
        template.replace("{searchTerms}", URLEncoder.encode(query, "UTF-8"))
            // Drop optional OpenSearch parameters such as {startPage?}. (Android's regex engine
            // needs the closing brace escaped.)
            .replace(Regex("\\{[^\\}]+\\?\\}"), "")

    /** Resolves a feed link against the feed's own URL. */
    fun resolve(link: Link, base: Url): String? {
        val href = link.href.resolve()
        return (href as? AbsoluteUrl)?.toString() ?: (base as? AbsoluteUrl)?.resolve(href)?.toString()
    }

    fun acquisitions(pub: Publication): List<Link> =
        pub.links.filter { l -> l.rels.any { it.startsWith("http://opds-spec.org/acquisition") } }

    fun isEpub(link: Link) = link.mediaType?.toString()?.contains("epub") == true

    fun label(link: Link): String {
        val type = link.mediaType?.toString().orEmpty()
        val href = link.href.toString()
        return when {
            "epub" in type && ("images" in href || "epub3" in href) -> "EPUB（含插图）"
            "epub" in type -> "EPUB"
            "pdf" in type -> "PDF（暂不支持）"
            "kindle" in type || "mobi" in type -> "Kindle（暂不支持）"
            "text/plain" in type -> "TXT"
            else -> type.ifBlank { "文件" }
        }
    }

    /** Downloads [url] into [dir], following redirects. */
    suspend fun download(url: String, dir: File, fileName: String): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        var current = URL(url)
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Shelf-Reader/0.1 (Android)")
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code in 300..399) {
                current = URL(current, conn.getHeaderField("Location"))
                conn.disconnect()
                return@repeat
            }
            if (code !in 200..299) throw IllegalStateException("下载失败（HTTP $code）")
            val out = File(dir, fileName)
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            return@withContext out
        }
        throw IllegalStateException("重定向次数过多")
    }
}
