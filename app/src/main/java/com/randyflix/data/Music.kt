package com.randyflix.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.RequiresApi
import com.randyflix.Settings
import com.randyflix.smb.Smb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.Normalizer

// ---------------------------------------------------------------- models

/** One song. [id] is its path on the share. [art] is a cover on the share or a file on the Firestick. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val albumArtist: String?,
    val album: String,
    val genres: List<String>,
    val track: Int,
    val disc: Int,
    val year: Int?,
    val duration: Long,
    val art: String?,
    val folder: String,
    val size: Long,
) {
    /** Who the album is filed under (album artist, so compilations stay together). */
    val artistName: String get() = albumArtist?.takeIf { it.isNotBlank() } ?: artist

    /** Albums are per folder, so two albums with the same name never merge. */
    val albumKey: String get() = "$folder|$album"
}

data class Album(val key: String, val title: String, val artist: String, val year: Int?, val art: String?, val tracks: List<Track>)

data class Artist(val name: String, val albums: List<Album>, val tracks: List<Track>) {
    val art: String? get() = albums.firstNotNullOfOrNull { it.art }
}

data class Genre(val name: String, val tracks: List<Track>) {
    val albums: List<Album> get() = MusicLibrary.albumsOf(tracks)
}

/**
 * A playlist: an .m3u file found in the music folder ([custom] false, read-only, [folder] is
 * where it was found) or one made with the remote ([custom] true, stored on the Firestick).
 */
data class MusicPlaylist(val id: String, val name: String, val folder: String?, val trackIds: List<String>, val custom: Boolean) {
    val tracks: List<Track> get() = trackIds.mapNotNull { MusicLibrary.track(it) }
}

data class MusicData(val root: String, val tracks: List<Track>, val playlistFiles: List<MusicPlaylist>, val lastScan: Long)

enum class SongSort(val label: String) { TITLE("Title"), ARTIST("Artist"), ALBUM("Album"), GENRE("Genre") }

// ---------------------------------------------------------------- library

/**
 * The music index (Settings → Connection → Music folder), scanned with the movie and TV library
 * and saved as music.json on the Firestick. Song details come from the files' tags; files
 * without tags fall back to the folders: Artist\Album\01 Title.mp3.
 */
object MusicLibrary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File
    private lateinit var artDir: File
    private lateinit var prefs: SharedPreferences
    private var job: Job? = null

    @Volatile var data = MusicData("", emptyList(), emptyList(), 0L)
        private set

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state

    // Worked out once per scan.
    @Volatile private var byId: Map<String, Track> = emptyMap()
    @Volatile private var albumList: List<Album> = emptyList()
    @Volatile private var artistList: List<Artist> = emptyList()
    @Volatile private var genreList: List<Genre> = emptyList()

    fun load(context: Context) {
        file = File(context.filesDir, "music.json")
        artDir = File(context.filesDir, "music_art").apply { mkdirs() }
        prefs = context.getSharedPreferences("music", Context.MODE_PRIVATE)
        if (file.exists()) runCatching { publish(MusicJson.decode(file.readText())) }
    }

    val isEnabled: Boolean get() = Settings.musicPath.isNotBlank()
    val isScanning: Boolean get() = job?.isActive == true

    fun scan(full: Boolean = false) {
        if (isScanning) return
        val root = Settings.musicPath
        if (root.isBlank()) {
            if (data.tracks.isNotEmpty() || data.playlistFiles.isNotEmpty()) save(MusicData("", emptyList(), emptyList(), 0L))
            _state.value = ScanState.Idle
            return
        }
        job = scope.launch {
            _state.value = ScanState.Running("Music: connecting…")
            try {
                val old = if (data.root == root && !full) data else MusicData(root, emptyList(), emptyList(), 0L)
                val result = MusicScanner(root, old, artDir) { _state.value = ScanState.Running(it) }.run()
                save(result)
                _state.value = ScanState.Done("${result.tracks.size} songs, ${albumList.size} albums")
            } catch (e: Exception) {
                _state.value = ScanState.Failed("Music (${Settings.musicPath}): ${Scanner.describe(e)}")
            }
        }
    }

    private fun save(d: MusicData) {
        publish(d)
        runCatching {
            val tmp = File(file.parentFile, "music.json.tmp")
            tmp.writeText(MusicJson.encode(d))
            tmp.renameTo(file)
        }
    }

    private fun publish(d: MusicData) {
        byId = d.tracks.associateBy { it.id }
        albumList = albumsOf(d.tracks).sortedWith(compareBy({ sortKey(it.artist) }, { it.year ?: 0 }, { sortKey(it.title) }))
        artistList = d.tracks.groupBy { it.artistName }.map { (name, tracks) ->
            Artist(name, albumsOf(tracks).sortedWith(compareBy({ it.year ?: 0 }, { sortKey(it.title) })), tracks)
        }.sortedBy { sortKey(it.name) }
        val byGenre = HashMap<String, MutableList<Track>>()
        for (t in d.tracks) for (g in t.genres) byGenre.getOrPut(g) { ArrayList() } += t
        genreList = byGenre.map { (g, tracks) -> Genre(g, tracks.sortedWith(albumOrder)) }.sortedBy { sortKey(it.name) }
        data = d
        _version.update { it + 1 }
    }

    // ---- queries

    fun track(id: String): Track? = byId[id]
    fun albums(): List<Album> = albumList
    fun artists(): List<Artist> = artistList
    fun genres(): List<Genre> = genreList
    fun album(key: String): Album? = albumList.firstOrNull { it.key == key }
    fun artist(name: String): Artist? = artistList.firstOrNull { it.name == name }

    private val albumOrder = compareBy<Track>({ sortKey(it.artistName) }, { sortKey(it.album) }, { it.disc }, { it.track }, { sortKey(it.title) })

    fun albumsOf(tracks: List<Track>): List<Album> =
        tracks.groupBy { it.albumKey }.map { (key, list) ->
            val sorted = list.sortedWith(compareBy({ it.disc }, { it.track }, { sortKey(it.title) }))
            val first = sorted.first()
            Album(key, first.album, first.artistName, sorted.firstNotNullOfOrNull { it.year }, sorted.firstNotNullOfOrNull { it.art }, sorted)
        }

    var songSort: SongSort
        get() = SongSort.values().firstOrNull { it.name == prefs.getString("sort", null) } ?: SongSort.ARTIST
        set(v) = prefs.edit().putString("sort", v.name).apply()

    fun songs(sort: SongSort = songSort): List<Track> = when (sort) {
        SongSort.TITLE -> data.tracks.sortedBy { sortKey(it.title) }
        SongSort.ARTIST -> data.tracks.sortedWith(albumOrder)
        SongSort.ALBUM -> data.tracks.sortedWith(compareBy({ sortKey(it.album) }, { it.disc }, { it.track }))
        SongSort.GENRE -> data.tracks.sortedWith(compareBy<Track>({ sortKey(it.genres.firstOrNull() ?: "~") }).then(albumOrder))
    }

    class SearchResult(val artists: List<Artist>, val albums: List<Album>, val songs: List<Track>)

    fun search(query: String): SearchResult {
        val q = normalize(query.trim())
        if (q.isEmpty()) return SearchResult(emptyList(), emptyList(), emptyList())
        return SearchResult(
            artistList.filter { normalize(it.name).contains(q) },
            albumList.filter { normalize(it.title).contains(q) || normalize(it.artist).contains(q) },
            data.tracks.filter { normalize(it.title).contains(q) }.sortedWith(albumOrder),
        )
    }

    // ---- playlists

    /** Yours first (by name), then the .m3u files found in the music folder. */
    fun playlists(): List<MusicPlaylist> =
        customPlaylists().sortedBy { sortKey(it.name) } + data.playlistFiles.sortedBy { sortKey(it.name) }

    fun playlist(id: String): MusicPlaylist? = playlists().firstOrNull { it.id == id }

    private fun customPlaylists(): List<MusicPlaylist> = runCatching {
        val arr = JSONArray(prefs.getString("playlists", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ids = o.getJSONArray("t")
            MusicPlaylist("my:" + o.getString("n"), o.getString("n"), null, (0 until ids.length()).map { ids.getString(it) }, true)
        }
    }.getOrDefault(emptyList())

    private fun saveCustom(list: List<MusicPlaylist>) {
        val arr = JSONArray()
        list.forEach { p -> arr.put(JSONObject().put("n", p.name).put("t", JSONArray(p.trackIds))) }
        prefs.edit().putString("playlists", arr.toString()).apply()
        _version.update { it + 1 }
    }

    /** False if a playlist of yours already has that name. */
    fun createPlaylist(name: String, trackIds: List<String> = emptyList()): Boolean {
        val n = name.trim()
        val list = customPlaylists()
        if (n.isEmpty() || list.any { it.name.equals(n, true) }) return false
        saveCustom(list + MusicPlaylist("my:$n", n, null, trackIds, true))
        return true
    }

    fun addToPlaylist(id: String, trackIds: List<String>) =
        saveCustom(customPlaylists().map { if (it.id == id) it.copy(trackIds = it.trackIds + trackIds) else it })

    fun removeFromPlaylist(id: String, index: Int) =
        saveCustom(customPlaylists().map { p ->
            if (p.id == id && index in p.trackIds.indices) p.copy(trackIds = p.trackIds.filterIndexed { i, _ -> i != index }) else p
        })

    fun renamePlaylist(id: String, name: String): Boolean {
        val n = name.trim()
        val list = customPlaylists()
        if (n.isEmpty() || list.any { it.id != id && it.name.equals(n, true) }) return false
        saveCustom(list.map { if (it.id == id) it.copy(id = "my:$n", name = n) else it })
        return true
    }

    fun deletePlaylist(id: String) = saveCustom(customPlaylists().filterNot { it.id == id })

    // ---- helpers

    /** "The Beatles" files under B. */
    fun sortKey(s: String): String = normalize(s).removePrefix("the ").removePrefix("a ").trim()

    private fun normalize(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}

// ---------------------------------------------------------------- scanning

/**
 * Walks the music folder. A folder whose audio files (names and sizes) are unchanged keeps its
 * songs from the last scan; only new or changed folders have their tags read.
 */
class MusicScanner(
    private val root: String,
    private val old: MusicData,
    private val artDir: File,
    private val progress: (String) -> Unit,
) {
    private val oldByFolder = old.tracks.groupBy { it.folder }
    private val tracks = ArrayList<Track>()
    private val playlistFiles = ArrayList<Pair<String, ByteArray>>()   // path, contents
    private var folders = 0

    fun run(): MusicData {
        walk(root.trimEnd('/'), depth = 0)
        val known = tracks.associateBy { it.id.lowercase() }
        val lists = playlistFiles.mapNotNull { (path, bytes) -> parsePlaylist(path, bytes, known) }
        return MusicData(root, tracks, lists, System.currentTimeMillis())
    }

    private fun walk(folder: String, depth: Int) {
        if (depth > 12) return
        val entries = Smb.list(folder).filter { !it.name.startsWith(".") && !it.name.startsWith("@") && !it.name.startsWith("#") }
        folders++
        if (folders % 10 == 0) progress("Music: ${folder.substringAfterLast('/')} ($folders folders)")
        val files = entries.filter { !it.isDir }
        val audio = files.filter { it.name.substringAfterLast('.').lowercase() in AUDIO }.sortedBy { it.name.lowercase() }
        files.filter { it.name.substringAfterLast('.').lowercase() in PLAYLIST }.forEach { f ->
            runCatching { playlistFiles += "$folder/${f.name}" to Smb.readBytes("$folder/${f.name}") }
        }
        if (audio.isNotEmpty()) {
            val folderArt = COVER_NAMES.firstNotNullOfOrNull { n -> files.firstOrNull { it.name.equals(n, true) } }
                ?: files.firstOrNull { it.name.substringAfterLast('.').lowercase() in IMAGE }
            val artPath = folderArt?.let { "$folder/${it.name}" }
            val prev = oldByFolder[folder].orEmpty()
            val unchanged = prev.size == audio.size &&
                prev.map { it.id.substringAfterLast('/') to it.size }.toSet() == audio.map { it.name to it.size }.toSet()
            if (unchanged) {
                // Keep the tags; a cover added to the folder since still takes over.
                tracks += if (artPath != null) prev.map { it.copy(art = artPath) } else prev
            } else {
                progress("Music: reading ${folder.substringAfterLast('/')}")
                val read = audio.map { readTrack(folder, it.name, it.size, depth) }
                tracks += withArt(read, artPath)
            }
        }
        for (d in entries.filter { it.isDir }.sortedBy { it.name.lowercase() }) {
            runCatching { walk("$folder/${d.name}", depth + 1) }
        }
    }

    /** Folder cover if there is one; otherwise the picture inside the first song that has one. */
    private fun withArt(list: List<Track>, folderArt: String?): List<Track> {
        if (folderArt != null) return list.map { it.copy(art = folderArt) }
        return list.groupBy { it.albumKey }.flatMap { (key, songs) ->
            val art = embeddedArt(key, songs)
            songs.map { it.copy(art = art) }
        }
    }

    private fun embeddedArt(albumKey: String, songs: List<Track>): String? {
        val out = File(artDir, md5(albumKey) + ".jpg")
        if (out.exists()) return out.path
        if (Build.VERSION.SDK_INT < 23) return null
        for (s in songs.take(3)) {
            val bytes = runCatching { withRetriever(s.id) { it.embeddedPicture } }.getOrNull() ?: continue
            val bmp = decodeScaled(bytes, 600) ?: continue
            runCatching { FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
            bmp.recycle()
            if (out.exists()) return out.path
        }
        return null
    }

    private fun readTrack(folder: String, name: String, size: Long, depth: Int): Track {
        val path = "$folder/$name"
        val base = name.substringBeforeLast('.')
        // Folder-name fallbacks: …/Artist/Album/01 - Title.mp3
        val fallbackAlbum = if (depth >= 1) folder.substringAfterLast('/') else "Unknown album"
        val fallbackArtist = if (depth >= 2) folder.substringBeforeLast('/').substringAfterLast('/') else "Unknown artist"
        val num = Regex("""^(\d{1,3})[ ._\-]+""").find(base)
        val fallbackTitle = (if (num != null) base.substring(num.range.last + 1) else base).trim(' ', '-', '_', '.')
        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var genre: String? = null
        var trackNo: Int? = null
        var disc: Int? = null
        var year: Int? = null
        var duration = 0L
        if (Build.VERSION.SDK_INT >= 23) runCatching {
            withRetriever(path) { r ->
                fun s(k: Int) = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
                title = s(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                albumArtist = s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                genre = s(MediaMetadataRetriever.METADATA_KEY_GENRE)
                trackNo = s(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let { firstInt(it) }
                disc = s(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.let { firstInt(it) }
                year = (s(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: s(MediaMetadataRetriever.METADATA_KEY_DATE))
                    ?.let { Regex("""(1[89]|20)\d\d""").find(it)?.value?.toInt() }
                duration = s(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }
        return Track(
            id = path,
            title = title ?: fallbackTitle.ifBlank { base },
            artist = artist ?: albumArtist ?: fallbackArtist,
            albumArtist = albumArtist,
            album = album ?: fallbackAlbum,
            genres = genres(genre),
            track = trackNo ?: num?.groupValues?.get(1)?.toInt() ?: 0,
            disc = disc ?: 1,
            year = year,
            duration = duration,
            art = null,
            folder = folder,
            size = size,
        )
    }

    @RequiresApi(23)
    private fun <T> withRetriever(path: String, block: (MediaMetadataRetriever) -> T): T {
        val f = Smb.open(path)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(SmbMediaSource(f))
            return block(r)
        } finally {
            runCatching { r.release() }
            runCatching { f.close() }
        }
    }

    // ---- playlists (.m3u / .m3u8)

    private fun parsePlaylist(path: String, bytes: ByteArray, known: Map<String, Track>): MusicPlaylist? {
        val text = decode(bytes)
        val folder = path.substringBeforeLast('/')
        val ids = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { resolve(it, folder, known) }.toList()
        if (ids.isEmpty()) return null
        val relFolder = folder.removePrefix(root.trimEnd('/')).trim('/').ifEmpty { null }
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        return MusicPlaylist("file:$path", name, relFolder, ids, false)
    }

    /** A playlist line: relative to the playlist, or a full path from another computer (matched by its end). */
    private fun resolve(line: String, folder: String, known: Map<String, Track>): String? {
        val p = line.replace('\\', '/')
        val absolute = p.startsWith("/") || Regex("""^[A-Za-z]:/""").containsMatchIn(p) || p.contains("://")
        if (!absolute) {
            val parts = ArrayList(folder.split('/'))
            for (seg in p.split('/')) when (seg) {
                "", "." -> {}
                ".." -> if (parts.size > 1) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
            known[parts.joinToString("/").lowercase()]?.let { return it.id }
        }
        // Match by the last folders and file name, longest first.
        val segs = p.trimEnd('/').split('/').filter { it.isNotEmpty() }
        for (n in minOf(3, segs.size) downTo 1) {
            val tail = "/" + segs.takeLast(n).joinToString("/").lowercase()
            val hits = known.entries.filter { it.key.endsWith(tail) }
            if (hits.size == 1) return hits.first().value.id
        }
        return null
    }

    private fun decode(bytes: ByteArray): String {
        val utf = bytes.toString(Charsets.UTF_8)
        return if (utf.contains('�')) bytes.toString(Charsets.ISO_8859_1) else utf.removePrefix("﻿")
    }

    companion object {
        val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "alac", "mka")
        val PLAYLIST = setOf("m3u", "m3u8")
        private val IMAGE = setOf("jpg", "jpeg", "png", "webp")
        private val COVER_NAMES = listOf(
            "cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "front.png",
            "album.jpg", "album.png", "albumart.jpg", "albumartlarge.jpg",
        )

        private fun firstInt(s: String) = Regex("""\d+""").find(s)?.value?.toIntOrNull()

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun decodeScaled(bytes: ByteArray, max: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= max) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            if (bmp.width <= max) return bmp
            val scaled = Bitmap.createScaledBitmap(bmp, max, maxOf(1, bmp.height * max / bmp.width), true)
            if (scaled !== bmp) bmp.recycle()
            return scaled
        }

        /** "Rock; Pop" → [Rock, Pop]; old numeric ID3 genres like "(17)" → "Rock". */
        fun genres(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(';', '/', '\u0000', ',').map { it.trim() }.filter { it.isNotEmpty() }.map { g ->
                Regex("""^\(?(\d{1,3})\)?$""").find(g)?.groupValues?.get(1)?.toInt()?.let { ID3_GENRES.getOrNull(it) } ?: g
            }.distinctBy { it.lowercase() }
        }

        private val ID3_GENRES = listOf(
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
            "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
            "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
            "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
            "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
            "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
            "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
            "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
        )
    }
}

/** Lets Android's tag reader read a file straight from the share. */
@RequiresApi(23)
private class SmbMediaSource(private val f: com.hierynomus.smbj.share.File) : MediaDataSource() {
    private val length = f.fileInformation.standardInformation.endOfFile

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        var done = 0
        val want = minOf(size.toLong(), length - position).toInt()
        while (done < want) {
            val n = f.read(buffer, position + done, offset + done, minOf(64 * 1024, want - done))
            if (n <= 0) break
            done += n
        }
        return if (done == 0) -1 else done
    }

    override fun getSize(): Long = length

    override fun close() {}
}

// ---------------------------------------------------------------- JSON

private object MusicJson {
    fun encode(d: MusicData): String = JSONObject().apply {
        put("root", d.root)
        put("lastScan", d.lastScan)
        put("tracks", JSONArray().apply {
            d.tracks.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id); put("t", t.title); put("a", t.artist); putOpt("aa", t.albumArtist)
                    put("al", t.album); put("g", JSONArray(t.genres)); put("n", t.track); put("d", t.disc)
                    putOpt("y", t.year); put("ms", t.duration); putOpt("art", t.art); put("f", t.folder); put("sz", t.size)
                })
            }
        })
        put("lists", JSONArray().apply {
            d.playlistFiles.forEach { p ->
                put(JSONObject().put("id", p.id).put("n", p.name).putOpt("f", p.folder).put("t", JSONArray(p.trackIds)))
            }
        })
    }.toString()

    fun decode(text: String): MusicData {
        val o = JSONObject(text)
        val tracks = o.getJSONArray("tracks").let { a ->
            (0 until a.length()).map { i ->
                val t = a.getJSONObject(i)
                val g = t.getJSONArray("g")
                Track(
                    id = t.getString("id"), title = t.getString("t"), artist = t.getString("a"),
                    albumArtist = t.optString("aa").takeIf { t.has("aa") }, album = t.getString("al"),
                    genres = (0 until g.length()).map { g.getString(it) }, track = t.optInt("n"), disc = t.optInt("d", 1),
                    year = if (t.has("y")) t.getInt("y") else null, duration = t.optLong("ms"),
                    art = t.optString("art").takeIf { t.has("art") }, folder = t.getString("f"), size = t.optLong("sz"),
                )
            }
        }
        val lists = o.optJSONArray("lists")?.let { a ->
            (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                val ids = p.getJSONArray("t")
                MusicPlaylist(p.getString("id"), p.getString("n"), p.optString("f").takeIf { p.has("f") },
                    (0 until ids.length()).map { ids.getString(it) }, false)
            }
        } ?: emptyList()
        return MusicData(o.optString("root"), tracks, lists, o.optLong("lastScan"))
    }
}
