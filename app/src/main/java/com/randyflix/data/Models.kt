package com.randyflix.data

import org.json.JSONArray
import org.json.JSONObject

data class Subtitle(
    val path: String,
    val label: String,
    val lang: String?,
)

/** One disc of a multi-disc movie. */
data class Part(
    val path: String,
    val size: Long,
    val subtitles: List<Subtitle>,
)

data class Movie(
    val id: String,              // path of the video file (disc 1 for multi-disc movies)
    val folder: String,
    val stamp: Long,             // folder modified time when scanned (for incremental rescans)
    val added: Long,
    val title: String,
    val sortTitle: String,
    val year: Int?,
    val plot: String?,
    val tagline: String?,
    val runtime: Int?,           // minutes
    val rating: Double?,
    val mpaa: String?,
    val genres: List<String>,
    val collection: String?,
    val poster: String?,         // smb path or http url
    val fanart: String?,
    val subtitles: List<Subtitle>,
    val size: Long,              // total of all discs
    val videoCodec: String?,
    val audioCodec: String?,
    val height: Int?,
    val parts: List<Part> = emptyList(), // discs in order; empty for a single-file movie
) {
    /** The files to play, in order: every disc, or just the one video. */
    val files: List<Part> get() = parts.ifEmpty { listOf(Part(id, size, subtitles)) }
}

data class Episode(
    val id: String,              // path of the video file
    val seasonDir: String,       // "" when the file sits in the show folder itself
    val season: Int,
    val episode: Int,
    val title: String,
    val plot: String?,
    val aired: String?,
    val runtime: Int?,
    val thumb: String?,
    val subtitles: List<Subtitle>,
    val added: Long,
    val size: Long,
)

data class Show(
    val id: String,              // show folder path
    val stamps: Map<String, Long>, // "" -> show folder mtime, season folder name -> mtime
    val title: String,
    val sortTitle: String,
    val year: Int?,
    val plot: String?,
    val rating: Double?,
    val mpaa: String?,
    val genres: List<String>,
    val poster: String?,
    val fanart: String?,
    val seasonPosters: Map<Int, String>,
    val episodes: List<Episode>,
) {
    /** Episodes in watch order, specials (season 0) last. */
    val ordered: List<Episode> by lazy {
        episodes.sortedWith(compareBy<Episode>({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }, { it.title }))
    }
    val lastAdded: Long by lazy { episodes.maxOfOrNull { it.added } ?: 0L }
}

/** Card wrappers used by the UI. */
data class EpisodeRef(val show: Show, val episode: Episode)
data class ContinueItem(val target: Any, val id: String, val resume: Playback.Resume?)
data class ActionItem(val id: String, val title: String, val subtitle: String?, val icon: Int)

data class LibraryData(val movies: List<Movie>, val shows: List<Show>, val lastScan: Long)

// ---------------------------------------------------------------- JSON

object LibraryJson {
    /** Bump when the scanner learns something new, so existing installs do one full rescan. */
    const val VERSION = 2

    fun version(text: String): Int = runCatching { JSONObject(text).optInt("version", 1) }.getOrDefault(1)

    fun encode(d: LibraryData): String = JSONObject().apply {
        put("version", VERSION)
        put("lastScan", d.lastScan)
        put("movies", JSONArray().apply { d.movies.forEach { put(movie(it)) } })
        put("shows", JSONArray().apply { d.shows.forEach { put(show(it)) } })
    }.toString()

    fun decode(text: String): LibraryData {
        val o = JSONObject(text)
        return LibraryData(
            movies = o.objects("movies").map { movie(it) },
            shows = o.objects("shows").map { show(it) },
            lastScan = o.optLong("lastScan", 0L),
        )
    }

    private fun subs(list: List<Subtitle>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("p", it.path).put("l", it.label).putOpt("g", it.lang)) }
    }

    private fun subs(o: JSONObject) = o.objects("subs").map { Subtitle(it.getString("p"), it.getString("l"), it.s("g")) }

    private fun movie(m: Movie) = JSONObject().apply {
        put("id", m.id); put("folder", m.folder); put("stamp", m.stamp); put("added", m.added)
        put("title", m.title); put("sort", m.sortTitle); putOpt("year", m.year); putOpt("plot", m.plot)
        putOpt("tagline", m.tagline); putOpt("runtime", m.runtime); putOpt("rating", m.rating); putOpt("mpaa", m.mpaa)
        put("genres", JSONArray(m.genres)); putOpt("set", m.collection); putOpt("poster", m.poster)
        putOpt("fanart", m.fanart); put("subs", subs(m.subtitles)); put("size", m.size)
        putOpt("vcodec", m.videoCodec); putOpt("acodec", m.audioCodec); putOpt("height", m.height)
        if (m.parts.isNotEmpty()) put("parts", JSONArray().apply {
            m.parts.forEach { put(JSONObject().put("p", it.path).put("size", it.size).put("subs", subs(it.subtitles))) }
        })
    }

    private fun movie(o: JSONObject) = Movie(
        id = o.getString("id"), folder = o.getString("folder"), stamp = o.getLong("stamp"), added = o.getLong("added"),
        title = o.getString("title"), sortTitle = o.getString("sort"), year = o.i("year"), plot = o.s("plot"),
        tagline = o.s("tagline"), runtime = o.i("runtime"), rating = o.d("rating"), mpaa = o.s("mpaa"),
        genres = o.strings("genres"), collection = o.s("set"), poster = o.s("poster"), fanart = o.s("fanart"),
        subtitles = subs(o), size = o.optLong("size", 0L), videoCodec = o.s("vcodec"), audioCodec = o.s("acodec"),
        height = o.i("height"),
        parts = o.objects("parts").map { Part(it.getString("p"), it.optLong("size", 0L), subs(it)) },
    )

    private fun episode(e: Episode) = JSONObject().apply {
        put("id", e.id); put("dir", e.seasonDir); put("s", e.season); put("e", e.episode); put("title", e.title)
        putOpt("plot", e.plot); putOpt("aired", e.aired); putOpt("runtime", e.runtime); putOpt("thumb", e.thumb)
        put("subs", subs(e.subtitles)); put("added", e.added); put("size", e.size)
    }

    private fun episode(o: JSONObject) = Episode(
        id = o.getString("id"), seasonDir = o.getString("dir"), season = o.getInt("s"), episode = o.getInt("e"),
        title = o.getString("title"), plot = o.s("plot"), aired = o.s("aired"), runtime = o.i("runtime"),
        thumb = o.s("thumb"), subtitles = subs(o), added = o.getLong("added"), size = o.optLong("size", 0L),
    )

    private fun show(s: Show) = JSONObject().apply {
        put("id", s.id)
        put("stamps", JSONObject().apply { s.stamps.forEach { (k, v) -> put(k, v) } })
        put("title", s.title); put("sort", s.sortTitle); putOpt("year", s.year); putOpt("plot", s.plot)
        putOpt("rating", s.rating); putOpt("mpaa", s.mpaa); put("genres", JSONArray(s.genres))
        putOpt("poster", s.poster); putOpt("fanart", s.fanart)
        put("seasonPosters", JSONObject().apply { s.seasonPosters.forEach { (k, v) -> put(k.toString(), v) } })
        put("episodes", JSONArray().apply { s.episodes.forEach { put(episode(it)) } })
    }

    private fun show(o: JSONObject): Show {
        val stamps = HashMap<String, Long>()
        o.optJSONObject("stamps")?.let { j -> j.keys().forEach { k -> stamps[k] = j.getLong(k) } }
        val posters = HashMap<Int, String>()
        o.optJSONObject("seasonPosters")?.let { j -> j.keys().forEach { k -> k.toIntOrNull()?.let { posters[it] = j.getString(k) } } }
        return Show(
            id = o.getString("id"), stamps = stamps, title = o.getString("title"), sortTitle = o.getString("sort"),
            year = o.i("year"), plot = o.s("plot"), rating = o.d("rating"), mpaa = o.s("mpaa"),
            genres = o.strings("genres"), poster = o.s("poster"), fanart = o.s("fanart"),
            seasonPosters = posters, episodes = o.objects("episodes").map { episode(it) },
        )
    }

    private fun JSONObject.s(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONObject.i(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
    private fun JSONObject.d(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun JSONObject.strings(k: String): List<String> =
        optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    private fun JSONObject.objects(k: String): List<JSONObject> =
        optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
}
