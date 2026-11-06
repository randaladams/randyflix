package com.randyflix.data

import android.content.Context
import com.randyflix.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.Normalizer

sealed class ScanState {
    object Idle : ScanState()
    data class Running(val message: String) : ScanState()
    data class Done(val message: String) : ScanState()
    data class Failed(val message: String) : ScanState()
}

/** The in-memory library index, persisted to library.json on the Firestick. */
object Library {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File

    @Volatile
    var data = LibraryData(emptyList(), emptyList(), 0L)
        private set

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state

    @Volatile private var movieIndex: Map<String, Movie> = emptyMap()
    @Volatile private var showIndex: Map<String, Show> = emptyMap()
    @Volatile private var episodeIndex: Map<String, EpisodeRef> = emptyMap()
    private var job: Job? = null

    fun load(context: Context) {
        file = File(context.filesDir, "library.json")
        if (file.exists()) {
            runCatching { publish(LibraryJson.decode(file.readText())) }
        }
    }

    private fun publish(d: LibraryData) {
        movieIndex = d.movies.associateBy { it.id }
        showIndex = d.shows.associateBy { it.id }
        val eps = HashMap<String, EpisodeRef>()
        d.shows.forEach { s -> s.episodes.forEach { e -> eps[e.id] = EpisodeRef(s, e) } }
        episodeIndex = eps
        data = d
        _version.update { it + 1 }
    }

    fun movie(id: String?): Movie? = id?.let { movieIndex[it] }
    fun show(id: String?): Show? = id?.let { showIndex[it] }
    fun episode(id: String?): EpisodeRef? = id?.let { episodeIndex[it] }

    val isScanning: Boolean get() = job?.isActive == true

    fun scan(full: Boolean = false) {
        if (isScanning) return
        job = scope.launch {
            _state.value = ScanState.Running("Connecting to ${Settings.host}…")
            try {
                val result = Scanner(data, full) { _state.value = ScanState.Running(it) }.run()
                publish(result.data)
                runCatching {
                    val tmp = File(file.parentFile, "library.json.tmp")
                    tmp.writeText(LibraryJson.encode(result.data))
                    tmp.renameTo(file)
                }
                val summary = "${result.data.movies.size} movies, ${result.data.shows.size} shows" +
                    if (result.warnings.isNotEmpty()) " (${result.warnings.size} folders skipped)" else ""
                _state.value = if (result.errors.isEmpty()) ScanState.Done(summary)
                else ScanState.Failed(result.errors.joinToString("\n"))
            } catch (e: Exception) {
                _state.value = ScanState.Failed(Scanner.describe(e))
            }
        }
    }

    /** Daily automatic refresh (and the very first scan). */
    fun scanIfStale() {
        val age = System.currentTimeMillis() - data.lastScan
        if (data.lastScan == 0L || age > 20L * 60 * 60 * 1000) scan(false)
    }

    // ------------------------------------------------------------------ queries

    fun search(query: String): List<Any> {
        val q = normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        val movies = data.movies.filter { normalize(it.title).contains(q) || it.collection?.let { c -> normalize(c).contains(q) } == true }
        val shows = data.shows.filter { normalize(it.title).contains(q) }
        return (shows.sortedBy { it.sortTitle } + movies.sortedBy { it.sortTitle })
    }

    fun collection(name: String): List<Movie> =
        data.movies.filter { it.collection == name }.sortedWith(compareBy({ it.year ?: 9999 }, { it.sortTitle }))

    fun recentMovies(limit: Int = 30): List<Movie> = data.movies.sortedByDescending { it.added }.take(limit)

    fun recentShows(limit: Int = 30): List<Show> = data.shows.sortedByDescending { it.lastAdded }.take(limit)

    /** In-progress movies/episodes plus the next episode of shows you're watching, most recent first. */
    fun continueWatching(limit: Int = 20): List<ContinueItem> {
        val out = ArrayList<Pair<Long, ContinueItem>>()
        for (m in data.movies) {
            val r = Playback.resume(m.id) ?: continue
            out += r.time to ContinueItem(m, m.id, r)
        }
        for (s in data.shows) {
            val eps = s.ordered
            var bestTime = 0L
            var bestIdx = -1
            eps.forEachIndexed { i, e ->
                val t = Playback.lastActivity(e.id)
                if (t > bestTime) { bestTime = t; bestIdx = i }
            }
            if (bestIdx < 0) continue
            val last = eps[bestIdx]
            val target = if (Playback.resume(last.id) != null) last
            else eps.drop(bestIdx + 1).firstOrNull { it.season != 0 && !Playback.isWatched(it.id) } ?: continue
            out += bestTime to ContinueItem(EpisodeRef(s, target), target.id, Playback.resume(target.id))
        }
        return out.sortedByDescending { it.first }.map { it.second }.take(limit)
    }

    /** First unwatched episode after the last one you watched (or the first episode). */
    fun nextUp(show: Show): Episode? {
        val eps = show.ordered
        eps.firstOrNull { Playback.resume(it.id) != null }?.let { inProgress ->
            val latest = eps.filter { Playback.resume(it.id) != null }.maxByOrNull { Playback.resume(it.id)!!.time }
            return latest ?: inProgress
        }
        val lastWatched = eps.indices.filter { Playback.isWatched(eps[it].id) }.maxByOrNull { Playback.watchedAt(eps[it].id) }
        if (lastWatched != null) {
            eps.drop(lastWatched + 1).firstOrNull { !Playback.isWatched(it.id) }?.let { return it }
        }
        return eps.firstOrNull { !Playback.isWatched(it.id) && it.season != 0 } ?: eps.firstOrNull()
    }

    fun unwatchedCount(show: Show): Int = show.episodes.count { !Playback.isWatched(it.id) }

    private fun normalize(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}
