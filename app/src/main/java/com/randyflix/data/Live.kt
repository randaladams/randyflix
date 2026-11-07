package com.randyflix.data

import android.content.Context
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
) {
    val isRemote: Boolean get() = url.startsWith("http://", true) || url.startsWith("https://", true)
}

/** Reads #EXTM3U playlists: #EXTINF attributes (tvg-logo, group-title, http-*) and #EXTVLCOPT headers. */
object M3u {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /** [folder] is the playlist's folder on the share, for stream lines that are relative file paths. */
    fun parse(text: String, folder: String): List<Channel> {
        val out = ArrayList<Channel>()
        var info: String? = null
        var userAgent: String? = null
        var referrer: String? = null
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

/** The channel list, read from the playlist on the share and cached on the Firestick for offline starts. */
object LiveTv {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cache: File

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
        if (cache.exists()) runCatching { publish(M3u.parse(cache.readText(), folder())) }
    }

    /** Re-reads the playlist from the share. Cheap, so it runs on every start and library refresh. */
    fun refresh() {
        scope.launch {
            val path = Settings.livePath
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

    fun indexOf(url: String?): Int = channels.indexOfFirst { it.url == url }

    private fun folder() = Settings.livePath.replace('\\', '/').substringBeforeLast('/', "")

    private fun publish(list: List<Channel>) {
        if (list == channels) return
        channels = list
        _version.update { it + 1 }
    }
}
