package com.randyflix.data

import android.content.Context
import android.content.SharedPreferences
import com.randyflix.Settings
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
    /** Reading music.json at start-up; a scan waits for it (it needs last time's songs). */
    private var loadJob: Job? = null

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
        // In the background: with thousands of songs, reading and sorting them would hold up
        // the app's start long enough for Fire OS to give up on it.
        if (file.exists()) loadJob = scope.launch {
            try {
                publish(MusicJson.decode(file.readText()))
            } catch (e: Throwable) {
                file.delete()   // unreadable: the next scan rebuilds it
            }
        }
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
            loadJob?.join()
            try {
                val old = if (data.root == root && !full) data else MusicData(root, emptyList(), emptyList(), 0L)
                val result = MusicScanner(root, old, artDir,
                    progress = { _state.value = ScanState.Running(it) },
                    checkpoint = { save(it) },   // music appears while scanning; a stopped scan resumes
                ).run()
                save(result)
                _state.value = ScanState.Done("${result.tracks.size} songs, ${albumList.size} albums")
            } catch (e: Throwable) {
                // Anything at all (even running out of memory) ends the scan, never the app.
                _state.value = ScanState.Failed("Music (${Settings.musicPath}): " +
                    if (e is Exception) Scanner.describe(e) else e.toString())
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
        sortedSongs.clear()
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

    /** Each order is worked out once per scan, not every time the list is shown. */
    private val sortedSongs = java.util.concurrent.ConcurrentHashMap<SongSort, List<Track>>()

    fun songs(sort: SongSort = songSort): List<Track> = sortedSongs.getOrPut(sort) {
        when (sort) {
            SongSort.TITLE -> data.tracks.sortedBy { sortKey(it.title) }
            SongSort.ARTIST -> data.tracks.sortedWith(albumOrder)
            SongSort.ALBUM -> data.tracks.sortedWith(compareBy({ sortKey(it.album) }, { it.disc }, { it.track }))
            SongSort.GENRE -> data.tracks.sortedWith(compareBy<Track>({ sortKey(it.genres.firstOrNull() ?: "~") }).then(albumOrder))
        }
    }

    class SearchResult(val artists: List<Artist>, val albums: List<Album>, val songs: List<Track>)

    fun search(query: String): SearchResult {
        val q = normalize(query.trim())
        if (q.isEmpty()) return SearchResult(emptyList(), emptyList(), emptyList())
        return SearchResult(
            artistList.filter { plain(it.name).contains(q) },
            albumList.filter { plain(it.title).contains(q) || plain(it.artist).contains(q) },
            data.tracks.filter { plain(it.title).contains(q) }.sortedWith(albumOrder),
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

    // Each name is cleaned up once and remembered: sorting 10,000 songs compares names hundreds
    // of thousands of times, and redoing the clean-up each time made start-up far too slow.
    private val plainCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sortCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val MARKS = Regex("\\p{M}+")

    /** "The Beatles" files under B. */
    fun sortKey(s: String): String = sortCache.getOrPut(s) { plain(s).removePrefix("the ").removePrefix("a ").trim() }

    /** Lower case, accents removed: "Beyoncé" → "beyonce". */
    private fun plain(s: String): String = plainCache.getOrPut(s) { normalize(s) }

    private fun normalize(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
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
