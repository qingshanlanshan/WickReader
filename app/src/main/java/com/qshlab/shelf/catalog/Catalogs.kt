package com.qshlab.shelf.catalog

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import android.content.Context
import com.qshlab.shelf.chinese.ChineseConverter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
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
    data class Loaded(
        val feed: Feed,
        val searchTemplate: String?,
        /** Quick links shown above the feed, e.g. Gutenberg's Chinese / Japanese books. */
        val shortcuts: List<Pair<String, String>> = emptyList(),
    ) : FeedResult
    data class Failed(val message: String) : FeedResult
}

object Opds {
    private const val GUTENBERG_SEARCH = "https://www.gutenberg.org/ebooks/search.opds/?query="

    suspend fun load(context: Context, url: String): FeedResult {
        val first = loadOnce(url)
        // Gutenberg catalogs Chinese books in 繁體 (sometimes 简体): searchUrl() sent the 繁體 form,
        // so if that finds nothing, try the 简体 one.
        val query = gutenbergQuery(url)
        if (query != null && query.any(::isCjk) && (first as? FeedResult.Loaded)?.isEmpty() != false) {
            val simplified = ChineseConverter.get(context).toSimplified(query)
            if (simplified != query) (loadOnce(GUTENBERG_SEARCH + URLEncoder.encode(simplified, "UTF-8")) as? FeedResult.Loaded)
                ?.takeIf { !it.isEmpty() }?.let { return it }
        }
        if (query != null && (first as? FeedResult.Loaded)?.isEmpty() != false) {
            return FeedResult.Failed("没有找到「$query」。古腾堡的书名多为英文或繁体中文，可以试试作者名、拼音或英文书名。")
        }
        return first
    }

    private suspend fun loadOnce(url: String): FeedResult {
        val parsed = OPDS1Parser.parseUrlString(url).getOrNull()
            ?: return FeedResult.Failed("无法打开这个书库（请检查网络或地址）")
        // Gutenberg answers an empty search with one "No records found." pseudo-entry.
        val feed = parsed.feed?.let { f ->
            f.copy(
                navigation = f.navigation.filter { it.title != "No records found." },
                publications = f.publications.filter { it.metadata.title != "No records found." },
            )
        } ?: return FeedResult.Failed("这个地址不是 OPDS 书库")
        val template = runCatching { OPDS1Parser.retrieveOpenSearchTemplate(feed).getOrNull() }.getOrNull()
            ?: gutenbergSearch(url)
        val shortcuts = if (url.trimEnd('/').endsWith("gutenberg.org/ebooks.opds")) {
            listOf("中文书" to GUTENBERG_SEARCH + "l.zh", "日文书" to GUTENBERG_SEARCH + "l.ja")
        } else {
            emptyList()
        }
        return FeedResult.Loaded(feed, template, shortcuts)
    }

    private fun FeedResult.Loaded.isEmpty() =
        feed.publications.isEmpty() && feed.navigation.isEmpty() && feed.groups.all { it.publications.isEmpty() && it.navigation.isEmpty() }

    private fun isCjk(c: Char) = c.code in 0x3400..0x9fff

    private fun gutenbergQuery(url: String): String? =
        if (url.startsWith(GUTENBERG_SEARCH)) URLDecoder.decode(url.removePrefix(GUTENBERG_SEARCH).substringBefore('&'), "UTF-8") else null

    /** Gutenberg's OpenSearch description is sometimes slow; its search URL is stable. */
    private fun gutenbergSearch(url: String) =
        if ("gutenberg.org" in url) "$GUTENBERG_SEARCH{searchTerms}" else null

    fun searchUrl(context: Context, template: String, query: String): String {
        // Gutenberg's Chinese titles are mostly 繁體 and its search doesn't fold scripts.
        val q = if ("gutenberg.org" in template && query.any(::isCjk)) ChineseConverter.simplifiedToTraditional(context).toSimplified(query) else query
        return template.replace("{searchTerms}", URLEncoder.encode(q, "UTF-8"))
            // Drop optional OpenSearch parameters such as {startPage?}. (Android's regex engine
            // needs the closing brace escaped.)
            .replace(Regex("\\{[^\\}]+\\?\\}"), "")
    }

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
