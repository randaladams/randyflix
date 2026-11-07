package com.randyflix.data

import android.content.Context
import android.content.SharedPreferences
import com.randyflix.Settings
import com.randyflix.smb.Smb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** A live channel from the M3U playlist. [url] is an http(s) stream or a path on the share. */
data class Channel(
    val url: String,
    val name: String,
    val logo: String?,
    val groups: List<String>,
    val userAgent: String?,
    val referrer: String?,
    /** Listed under #SECTION:Favorites in the playlist. */
    val inFavorites: Boolean = false,
) {
    val isRemote: Boolean get() = url.startsWith("http://", true) || url.startsWith("https://", true)
}

/**
 * Reads #EXTM3U playlists: #EXTINF attributes (tvg-logo, group-title, http-*), #EXTVLCOPT headers,
 * and RandyFlix's own `#SECTION:<name>` lines (channels after `#SECTION:Favorites` are favorites
 * until the next #SECTION). Other players ignore #SECTION, so the file still works elsewhere.
 */
object M3u {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /** [folder] is the playlist's folder on the share, for stream lines that are relative file paths. */
    fun parse(text: String, folder: String): List<Channel> {
        val out = ArrayList<Channel>()
        var info: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        var favorites = false
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF", true) -> {
                    info = line
                    userAgent = null
                    referrer = null
                }
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val opt = line.substringAfter(':')
                    when (opt.substringBefore('=').trim().lowercase()) {
                        "http-user-agent" -> userAgent = opt.substringAfter('=').trim()
                        "http-referrer" -> referrer = opt.substringAfter('=').trim()
                    }
                }
                line.startsWith("#SECTION:", true) ->
                    favorites = line.substringAfter(':').trim().equals("favorites", true)
                line.startsWith("#") -> {}
                else -> {
                    val inf = info ?: "#EXTINF:-1,${line.substringAfterLast('/')}"
                    val attrs = ATTR.findAll(infoAttrs(inf))
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val url = if (line.contains("://")) line
                    else "${folder.trimEnd('/')}/${line.replace('\\', '/').trimStart('/')}"
                    out += Channel(
                        url = url,
                        name = infoName(inf).ifBlank { line.substringAfterLast('/') },
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        groups = attrs["group-title"]?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                        userAgent = userAgent ?: attrs["http-user-agent"]?.takeIf { it.isNotBlank() },
                        referrer = referrer ?: attrs["http-referrer"]?.takeIf { it.isNotBlank() },
                        inFavorites = favorites,
                    )
                    info = null
                }
            }
        }
        return out
    }

    /** Index of the comma that ends the attributes (the first one outside quotes). */
    private fun nameComma(inf: String): Int {
        var quoted = false
        for (i in inf.indices) {
            when (inf[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return i
            }
        }
        return -1
    }

    private fun infoAttrs(inf: String): String = nameComma(inf).let { if (it < 0) inf else inf.substring(0, it) }

    private fun infoName(inf: String): String = nameComma(inf).let { if (it < 0) "" else inf.substring(it + 1).trim() }
}

/**
 * Live TV channels, read from the share and cached on the Firestick for offline starts:
 * - [channels]: your own playlist (channels.m3u).
 * - [catalog]: the channel guide's catalog (catalog.m3u), one category per group-title.
 * My channels = the playlist's channels (minus any removed with the remote) plus catalog
 * channels added with the remote. Favorites (up to [MAX_FAVORITES], shown on the home screen)
 * start as the playlist's Favorites section; once changed with the remote, the Firestick's
 * own list is used instead. Both can hold any known channel.
 */
object LiveTv {
    const val MAX_FAVORITES = 5
    const val LIST_FAVORITES = "favorites"
    /** My channels. */
    const val LIST_ALL = "all"
    /** Prefix for a guide category's list, e.g. "cat:News". */
    const val LIST_CATEGORY = "cat:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cache: File
    private lateinit var catalogCache: File
    private lateinit var prefs: SharedPreferences

    /** Your playlist, in file order. */
    @Volatile
    var channels: List<Channel> = emptyList()
        private set

    /** The guide's catalog, in file order. */
    @Volatile
    var catalog: List<Channel> = emptyList()
        private set

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** Why the last refresh failed, or null. */
    @Volatile
    var error: String? = null
        private set

    fun load(context: Context) {
        cache = File(context.filesDir, "live.m3u")
        catalogCache = File(context.filesDir, "catalog.m3u")
        prefs = context.getSharedPreferences("live", Context.MODE_PRIVATE)
        if (cache.exists()) runCatching { channels = M3u.parse(cache.readText(), folder()) }
        if (catalogCache.exists()) runCatching { catalog = M3u.parse(catalogCache.readText(), folder()) }
        _version.update { it + 1 }
    }

    /** Re-reads the playlist and the catalog from the share. Cheap, so it runs on every start and library refresh. */
    fun refresh() {
        scope.launch {
            val path = Settings.livePlaylist
            if (path.isBlank()) {
                error = null
                cache.delete()
                catalogCache.delete()
                publish(emptyList(), emptyList())
                return@launch
            }
            var list = channels
            var cat = catalog
            try {
                val text = Smb.readBytes(path).toString(Charsets.UTF_8)
                error = null
                cache.writeText(text)
                list = M3u.parse(text, folder())
            } catch (e: Exception) {
                error = "Live TV playlist ($path): ${Scanner.describe(e)}"
            }
            // The catalog is optional: no file just means an empty guide.
            try {
                val text = Smb.readBytes(Settings.catalogPath).toString(Charsets.UTF_8)
                catalogCache.writeText(text)
                cat = M3u.parse(text, folder())
            } catch (e: Exception) {
                if (error == null && Scanner.describe(e).contains("not found", true)) {
                    catalogCache.delete()
                    cat = emptyList()
                }
            }
            publish(list, cat)
        }
    }

    /** Every channel the app knows, by URL (playlist first). */
    private fun known(): Map<String, Channel> {
        val map = LinkedHashMap<String, Channel>()
        for (c in channels) map.putIfAbsent(c.url, c)
        for (c in catalog) map.putIfAbsent(c.url, c)
        return map
    }

    private fun urls(key: String): List<String> =
        prefs.getString(key, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    private fun putUrls(key: String, list: List<String>) {
        prefs.edit().putString(key, list.distinct().joinToString("\n")).apply()
        _version.update { it + 1 }
    }

    // ---- Favorites (home screen) ----

    /** The home-screen channels, in order. */
    fun favorites(): List<Channel> {
        if (!prefs.contains("favorites")) return channels.filter { it.inFavorites }.distinctBy { it.url }.take(MAX_FAVORITES)
        val byUrl = known()
        return urls("favorites").mapNotNull { byUrl[it] }.distinctBy { it.url }.take(MAX_FAVORITES)
    }

    fun isFavorite(ch: Channel): Boolean = favorites().any { it.url == ch.url }

    /** Adds or removes [ch]. Returns false (and changes nothing) when adding to a full list. */
    fun toggleFavorite(ch: Channel): Boolean {
        val current = favorites().map { it.url }
        val next = if (ch.url in current) current - ch.url
        else if (current.size >= MAX_FAVORITES) return false
        else current + ch.url
        putUrls("favorites", next)
        return true
    }

    /** Goes back to the playlist's Favorites section. */
    fun resetFavorites() {
        prefs.edit().remove("favorites").apply()
        _version.update { it + 1 }
    }

    val hasCustomFavorites: Boolean get() = prefs.contains("favorites")

    // ---- My channels ----

    /** My channels, sorted by name or by how often each has been watched (Settings). */
    fun all(): List<Channel> {
        val removed = urls("mine_removed").toSet()
        val byUrl = known()
        val list = (channels.filter { it.url !in removed } + urls("mine_added").mapNotNull { byUrl[it] }).distinctBy { it.url }
        val byName = compareBy<Channel, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        return if (Settings.channelsByUse) list.sortedWith(compareByDescending<Channel> { watchCount(it) }.then(byName))
        else list.sortedWith(byName)
    }

    fun isMine(ch: Channel): Boolean = all().any { it.url == ch.url }

    /** Adds [ch] to My channels, or takes it off. Returns whether it's in My channels now. */
    fun toggleMine(ch: Channel): Boolean {
        val inPlaylist = channels.any { it.url == ch.url }
        return if (isMine(ch)) {
            if (inPlaylist) putUrls("mine_removed", urls("mine_removed") + ch.url)
            putUrls("mine_added", urls("mine_added") - ch.url)
            false
        } else {
            if (inPlaylist) putUrls("mine_removed", urls("mine_removed") - ch.url)
            else putUrls("mine_added", urls("mine_added") + ch.url)
            true
        }
    }

    // ---- Guide ----

    /** The catalog's categories, in file order. */
    fun categories(): List<String> = catalog.mapNotNull { it.groups.firstOrNull() }.distinct()

    fun category(name: String): List<Channel> = catalog.filter { it.groups.firstOrNull() == name }.distinctBy { it.url }

    /** Favorites, My channels, or one guide category. */
    fun list(kind: String?): List<Channel> = when {
        kind == LIST_FAVORITES -> favorites()
        kind != null && kind.startsWith(LIST_CATEGORY) -> category(kind.removePrefix(LIST_CATEGORY))
        else -> all()
    }

    /** "Favorites", "My channels" or the category name, for the channel banner. */
    fun listName(kind: String?): String = when {
        kind == LIST_FAVORITES -> "Favorites"
        kind != null && kind.startsWith(LIST_CATEGORY) -> kind.removePrefix(LIST_CATEGORY)
        else -> "My channels"
    }

    fun watchCount(ch: Channel): Int = prefs.getInt("n:${ch.url}", 0)

    /** Counted once a channel has stayed on for a minute, so flipping past doesn't count. */
    fun countWatch(ch: Channel) {
        prefs.edit().putInt("n:${ch.url}", watchCount(ch) + 1).apply()
    }

    private fun folder() = Settings.livePlaylist.substringBeforeLast('/', "")

    private fun publish(list: List<Channel>, cat: List<Channel>) {
        if (list == channels && cat == catalog) return
        channels = list
        catalog = cat
        _version.update { it + 1 }
    }
}
