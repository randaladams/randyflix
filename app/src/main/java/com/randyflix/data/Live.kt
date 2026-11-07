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
 * The channel list, read from the playlist on the share and cached on the Firestick for offline starts.
 * Favorites (up to [MAX_FAVORITES], shown on the home screen) start as the playlist's Favorites
 * section; once changed with the remote, the Firestick's own list is used instead.
 */
object LiveTv {
    const val MAX_FAVORITES = 5
    const val LIST_FAVORITES = "favorites"
    const val LIST_ALL = "all"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cache: File
    private lateinit var prefs: SharedPreferences

    /** Every channel, in playlist order. */
    @Volatile
    var channels: List<Channel> = emptyList()
        private set

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** Why the last refresh failed, or null. */
    @Volatile
    var error: String? = null
        private set

    fun load(context: Context) {
        cache = File(context.filesDir, "live.m3u")
        prefs = context.getSharedPreferences("live", Context.MODE_PRIVATE)
        if (cache.exists()) runCatching { publish(M3u.parse(cache.readText(), folder())) }
    }

    /** Re-reads the playlist from the share. Cheap, so it runs on every start and library refresh. */
    fun refresh() {
        scope.launch {
            val path = Settings.livePlaylist
            if (path.isBlank()) {
                error = null
                cache.delete()
                publish(emptyList())
                return@launch
            }
            try {
                val text = Smb.readBytes(path).toString(Charsets.UTF_8)
                error = null
                cache.writeText(text)
                publish(M3u.parse(text, folder()))
            } catch (e: Exception) {
                error = "Live TV playlist ($path): ${Scanner.describe(e)}"
            }
        }
    }

    /** The home-screen channels, in order. */
    fun favorites(): List<Channel> {
        val all = channels
        val custom = prefs.getString("favorites", null)
            ?: return all.filter { it.inFavorites }.distinctBy { it.url }.take(MAX_FAVORITES)
        val byUrl = all.associateBy { it.url }
        return custom.split('\n').mapNotNull { byUrl[it] }.distinctBy { it.url }.take(MAX_FAVORITES)
    }

    /** Every channel, sorted by name or by how often it's been watched (Settings). */
    fun all(): List<Channel> {
        val list = channels.distinctBy { it.url }
        val byName = compareBy<Channel, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        return if (Settings.channelsByUse) list.sortedWith(compareByDescending<Channel> { watchCount(it) }.then(byName))
        else list.sortedWith(byName)
    }

    fun list(kind: String?): List<Channel> = if (kind == LIST_FAVORITES) favorites() else all()

    fun isFavorite(ch: Channel): Boolean = favorites().any { it.url == ch.url }

    /** Adds or removes [ch]. Returns false (and changes nothing) when adding to a full list. */
    fun toggleFavorite(ch: Channel): Boolean {
        val current = favorites().map { it.url }
        val next = if (ch.url in current) current - ch.url
        else if (current.size >= MAX_FAVORITES) return false
        else current + ch.url
        prefs.edit().putString("favorites", next.joinToString("\n")).apply()
        _version.update { it + 1 }
        return true
    }

    /** Goes back to the playlist's Favorites section. */
    fun resetFavorites() {
        prefs.edit().remove("favorites").apply()
        _version.update { it + 1 }
    }

    val hasCustomFavorites: Boolean get() = prefs.contains("favorites")

    fun watchCount(ch: Channel): Int = prefs.getInt("n:${ch.url}", 0)

    /** Counted once a channel has stayed on for a minute, so flipping past doesn't count. */
    fun countWatch(ch: Channel) {
        prefs.edit().putInt("n:${ch.url}", watchCount(ch) + 1).apply()
    }

    private fun folder() = Settings.livePlaylist.substringBeforeLast('/', "")

    private fun publish(list: List<Channel>) {
        if (list == channels) return
        channels = list
        _version.update { it + 1 }
    }
}
