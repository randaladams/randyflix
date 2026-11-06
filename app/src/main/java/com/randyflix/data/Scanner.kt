package com.randyflix.data

import com.randyflix.Settings
import com.randyflix.smb.Smb
import com.randyflix.smb.SmbEntry
import java.text.Normalizer
import java.util.Locale

class ScanResult(val data: LibraryData, val errors: List<String>, val warnings: List<String>)

/**
 * Walks the movie and TV shares and builds the library index.
 * Incremental: a folder whose modified time hasn't changed since the last scan is reused as-is.
 */
class Scanner(
    private val old: LibraryData,
    private val full: Boolean,
    private val progress: (String) -> Unit,
) {
    private val errors = mutableListOf<String>()
    private val warnings = mutableListOf<String>()

    fun run(): ScanResult {
        val moviesRoot = Settings.moviesPath
        val tvRoot = Settings.tvPath
        val movies = try {
            scanMovies(moviesRoot)
        } catch (e: Exception) {
            errors += "Movies ($moviesRoot): ${describe(e)}"
            old.movies
        }
        val shows = try {
            scanShows(tvRoot)
        } catch (e: Exception) {
            errors += "TV ($tvRoot): ${describe(e)}"
            old.shows
        }
        val lastScan = if (errors.isEmpty()) System.currentTimeMillis() else old.lastScan
        return ScanResult(LibraryData(movies, shows, lastScan), errors, warnings)
    }

    // ------------------------------------------------------------------ movies

    private fun scanMovies(root: String): List<Movie> {
        if (root.isBlank()) return emptyList()
        val byFolder = old.movies.groupBy { it.folder }
        val byId = old.movies.associateBy { it.id }
        val entries = Smb.list(root).filter { !isJunk(it.name) }.sortedBy { it.name.lowercase() }
        val result = ArrayList<Movie>()
        entries.forEachIndexed { i, e ->
            if (e.isDir) {
                val folder = "$root/${e.name}"
                val prev = byFolder[folder]
                if (!full && prev != null && prev.all { it.stamp == e.modified }) {
                    result += prev
                    return@forEachIndexed
                }
                progress("Movies: ${e.name} (${i + 1}/${entries.size})")
                try {
                    result += scanMovieFolder(folder, e.name, e.modified, 0, byId)
                } catch (ex: Exception) {
                    warnings += "${e.name}: ${describe(ex)}"
                    prev?.let { result += it }
                }
            } else if (isVideo(e.name)) {
                // A loose movie file directly in the movies folder
                val id = "$root/${e.name}"
                val prev = byId[id]
                if (!full && prev != null && prev.stamp == e.modified) {
                    result += prev
                } else {
                    try {
                        result += buildMovie(root, null, e.modified, e, entries, prev?.added)
                    } catch (ex: Exception) {
                        warnings += "${e.name}: ${describe(ex)}"
                    }
                }
            }
        }
        return result
    }

    private fun scanMovieFolder(
        folder: String, name: String, stamp: Long, depth: Int, byId: Map<String, Movie>,
    ): List<Movie> {
        val files = Smb.list(folder)
        var videos = files.filter { !it.isDir && isVideo(it.name) }
        if (videos.size > 1) {
            val main = videos.filterNot { isExtra(it.name) }
            if (main.isNotEmpty()) videos = main
        }
        if (videos.isEmpty()) {
            // Possibly a collection folder ("Alien Collection/Aliens (1986)/...") - look one level down.
            if (depth >= 1) return emptyList()
            return files
                .filter { it.isDir && !isJunk(it.name) && !isSubsFolder(it.name) }
                .flatMap { scanMovieFolder("$folder/${it.name}", it.name, stamp, depth + 1, byId) }
        }
        val main = videos.maxByOrNull { it.size }!!
        return listOf(buildMovie(folder, name, stamp, main, files, byId["$folder/${main.name}"]?.added))
    }

    private fun buildMovie(
        folder: String, folderName: String?, stamp: Long, video: SmbEntry, files: List<SmbEntry>, prevAdded: Long?,
    ): Movie {
        val base = video.name.substringBeforeLast('.')
        val own = folderName != null      // movie has its own folder
        val find = finder(folder, files)

        val nfoPath = if (own) {
            find("$base.nfo", "movie.nfo") ?: files.firstOrNull { !it.isDir && it.name.endsWith(".nfo", true) }?.let { "$folder/${it.name}" }
        } else find("$base.nfo")
        val nfo = nfoPath?.let { readNfo(it) }

        val poster = (if (own) find(
            "$base-poster.jpg", "$base-poster.png", "poster.jpg", "poster.png", "folder.jpg", "folder.png", "cover.jpg", "$base.jpg",
        ) else find("$base-poster.jpg", "$base-poster.png", "$base.jpg")) ?: nfo?.let { Nfo.remotePoster(it) }
        val fanart = (if (own) find(
            "$base-fanart.jpg", "$base-fanart.png", "fanart.jpg", "fanart.png", "backdrop.jpg",
        ) else find("$base-fanart.jpg", "$base-fanart.png")) ?: nfo?.let { Nfo.remoteFanart(it) }

        val videoCount = files.count { !it.isDir && isVideo(it.name) && !isExtra(it.name) }
        val subtitles = findSubtitles(folder, base, files, anyInFolder = own && videoCount <= 1)

        val (parsedTitle, parsedYear) = parseName(folderName ?: base)
        val title = nfo?.str("title") ?: parsedTitle
        val streams = nfo?.path("fileinfo", "streamdetails")

        return Movie(
            id = "$folder/${video.name}",
            folder = folder,
            stamp = stamp,
            added = prevAdded ?: maxOf(video.created, video.modified, video.changed),
            title = title,
            sortTitle = sortKey(nfo?.str("sorttitle") ?: title),
            year = nfo?.let { Nfo.year(it) } ?: parsedYear,
            plot = nfo?.str("plot") ?: nfo?.str("outline"),
            tagline = nfo?.str("tagline"),
            runtime = nfo?.int("runtime")?.takeIf { it > 0 },
            rating = nfo?.let { Nfo.rating(it) },
            mpaa = nfo?.let { Nfo.mpaa(it) },
            genres = nfo?.let { Nfo.genres(it) } ?: emptyList(),
            collection = nfo?.child("set")?.let { it.str("name") ?: it.value },
            poster = poster,
            fanart = fanart,
            subtitles = subtitles,
            size = video.size,
            videoCodec = streams?.child("video")?.str("codec"),
            audioCodec = streams?.child("audio")?.str("codec"),
            height = streams?.child("video")?.int("height"),
        )
    }

    // ------------------------------------------------------------------ TV

    private fun scanShows(root: String): List<Show> {
        if (root.isBlank()) return emptyList()
        val byId = old.shows.associateBy { it.id }
        val dirs = Smb.list(root).filter { it.isDir && !isJunk(it.name) }.sortedBy { it.name.lowercase() }
        val result = ArrayList<Show>()
        dirs.forEachIndexed { i, d ->
            val folder = "$root/${d.name}"
            progress("TV: ${d.name} (${i + 1}/${dirs.size})")
            try {
                scanShow(folder, d, byId[folder])?.let { result += it }
            } catch (ex: Exception) {
                warnings += "${d.name}: ${describe(ex)}"
                byId[folder]?.let { result += it }
            }
        }
        return result
    }

    private fun scanShow(folder: String, entry: SmbEntry, prev: Show?): Show? {
        val files = Smb.list(folder)
        val seasonDirs = files.filter { it.isDir && !isJunk(it.name) }
            .mapNotNull { d -> seasonNumber(d.name)?.let { d to it } }
        val stamps = HashMap<String, Long>()
        stamps[""] = entry.modified
        seasonDirs.forEach { (d, _) -> stamps[d.name] = d.modified }
        if (!full && prev != null && prev.stamps == stamps) return prev

        val prevBySeasonDir = prev?.episodes?.groupBy { it.seasonDir }.orEmpty()
        val prevAdded = prev?.episodes?.associate { it.id to it.added }.orEmpty()
        val episodes = ArrayList<Episode>()
        episodes += scanEpisodes(folder, "", files, null, prevAdded)
        for ((d, num) in seasonDirs) {
            val prevEps = prevBySeasonDir[d.name]
            if (!full && prevEps != null && prev?.stamps?.get(d.name) == d.modified) {
                episodes += prevEps
                continue
            }
            val seasonFiles = Smb.list("$folder/${d.name}")
            episodes += scanEpisodes("$folder/${d.name}", d.name, seasonFiles, num, prevAdded)
        }
        if (episodes.isEmpty()) return null

        val find = finder(folder, files)
        val nfo = find("tvshow.nfo")?.let { readNfo(it) }
        val (parsedTitle, parsedYear) = parseName(entry.name)
        val title = nfo?.str("title") ?: parsedTitle

        val seasonPosters = HashMap<Int, String>()
        val posterRegex = Regex("""(?i)^season(\d+)-poster\.(jpg|png)$""")
        for (f in files) {
            if (f.isDir) continue
            posterRegex.find(f.name)?.let { seasonPosters[it.groupValues[1].toInt()] = "$folder/${f.name}" }
            if (f.name.startsWith("season-specials-poster.", ignoreCase = true)) seasonPosters[0] = "$folder/${f.name}"
        }

        return Show(
            id = folder,
            stamps = stamps,
            title = title,
            sortTitle = sortKey(nfo?.str("sorttitle") ?: title),
            year = nfo?.let { Nfo.year(it) } ?: parsedYear,
            plot = nfo?.str("plot") ?: nfo?.str("outline"),
            rating = nfo?.let { Nfo.rating(it) },
            mpaa = nfo?.let { Nfo.mpaa(it) },
            genres = nfo?.let { Nfo.genres(it) } ?: emptyList(),
            poster = find("poster.jpg", "poster.png", "folder.jpg", "folder.png", "cover.jpg")
                ?: nfo?.let { Nfo.remotePoster(it) },
            fanart = find("fanart.jpg", "fanart.png", "backdrop.jpg") ?: nfo?.let { Nfo.remoteFanart(it) },
            seasonPosters = seasonPosters,
            episodes = episodes,
        )
    }

    private fun scanEpisodes(
        dir: String, seasonDir: String, files: List<SmbEntry>, folderSeason: Int?, prevAdded: Map<String, Long>,
    ): List<Episode> {
        var videos = files.filter { !it.isDir && isVideo(it.name) }.sortedBy { it.name.lowercase() }
        if (videos.size > 1) videos = videos.filterNot { isExtra(it.name) }
        if (seasonDir.isEmpty()) {
            // Only treat loose files in a show folder as episodes if they look like episodes.
            videos = videos.filter { parseEpisode(it.name.substringBeforeLast('.')) != null }
        }
        val find = finder(dir, files)
        return videos.mapIndexed { idx, v ->
            val base = v.name.substringBeforeLast('.')
            val parsed = parseEpisode(base)
            val nfo = find("$base.nfo")?.let { readNfo(it) }
            val season = nfo?.int("season")?.takeIf { it >= 0 } ?: parsed?.first ?: folderSeason ?: 1
            val number = nfo?.int("episode")?.takeIf { it >= 0 } ?: parsed?.second
                ?: Regex("""^(\d{1,3})\b""").find(base)?.groupValues?.get(1)?.toInt() ?: (idx + 1)
            val id = "$dir/${v.name}"
            Episode(
                id = id,
                seasonDir = seasonDir,
                season = season,
                episode = number,
                title = nfo?.str("title") ?: episodeTitleFromName(base),
                plot = nfo?.str("plot"),
                aired = nfo?.str("aired"),
                runtime = nfo?.int("runtime")?.takeIf { it > 0 },
                thumb = find("$base-thumb.jpg", "$base-thumb.png", "$base.jpg", "$base.tbn")
                    ?: nfo?.str("thumb")?.takeIf { it.startsWith("http") },
                subtitles = findSubtitles(dir, base, files, anyInFolder = false),
                added = prevAdded[id] ?: maxOf(v.created, v.modified, v.changed),
                size = v.size,
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Case-insensitive lookup of sibling files; returns the full path of the first name that exists. */
    private class Finder(private val folder: String, files: List<SmbEntry>) {
        private val byName = files.filter { !it.isDir }.associateBy { it.name.lowercase() }
        operator fun invoke(vararg names: String): String? =
            names.firstNotNullOfOrNull { n -> byName[n.lowercase()]?.let { "$folder/${it.name}" } }
    }

    private fun finder(folder: String, files: List<SmbEntry>) = Finder(folder, files)

    private fun readNfo(path: String): XNode? = try {
        Nfo.parse(Smb.readBytes(path))
    } catch (e: Exception) {
        null
    }

    private fun findSubtitles(folder: String, base: String, files: List<SmbEntry>, anyInFolder: Boolean): List<Subtitle> {
        val result = ArrayList<Subtitle>()
        for (f in files) {
            if (f.isDir || !isSubtitle(f.name)) continue
            val matches = f.name.startsWith(base, ignoreCase = true)
            if (matches || anyInFolder) result += subtitle("$folder/${f.name}", f.name, if (matches) base else null)
        }
        // Subtitles kept in a "Subs" / "Subtitles" subfolder
        if (anyInFolder || result.isEmpty()) {
            files.firstOrNull { it.isDir && isSubsFolder(it.name) }?.let { dir ->
                runCatching { Smb.list("$folder/${dir.name}") }.getOrNull()?.forEach { f ->
                    if (!f.isDir && isSubtitle(f.name) && (anyInFolder || f.name.startsWith(base, true))) {
                        result += subtitle("$folder/${dir.name}/${f.name}", f.name, if (f.name.startsWith(base, true)) base else null)
                    }
                }
            }
        }
        return result
    }

    private fun subtitle(path: String, fileName: String, base: String?): Subtitle {
        val stem = fileName.substringBeforeLast('.')
        val rest = if (base != null) stem.substring(minOf(base.length, stem.length)) else stem
        val tokens = rest.split('.', '_', ' ', '-', '[', ']', '(', ')').map { it.trim() }.filter { it.isNotEmpty() }
        var lang: String? = null
        var label: String? = null
        val flags = ArrayList<String>()
        for (t in tokens) {
            val lower = t.lowercase()
            when {
                lower == "forced" -> flags += "Forced"
                lower == "sdh" || lower == "cc" || lower == "hi" -> flags += "SDH"
                lang == null && (t.length == 2 || t.length == 3) && t.all { it.isLetter() } -> {
                    val name = Locale(lower).getDisplayLanguage(Locale.ENGLISH)
                    if (name.isNotEmpty() && !name.equals(lower, ignoreCase = true)) {
                        lang = lower
                        label = name
                    }
                }
                lang == null && t.length > 3 && languageCode(t) != null -> {
                    lang = languageCode(t)
                    label = t.replaceFirstChar { it.uppercase() }
                }
            }
        }
        val text = listOfNotNull(label ?: if (base != null && rest.isBlank()) "Subtitles" else if (base == null) stem else "Subtitles")
            .plus(flags).joinToString(" ")
        return Subtitle(path, text, lang)
    }

    private fun languageCode(name: String): String? =
        Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(Locale.ENGLISH).equals(name, ignoreCase = true) }?.language

    companion object {
        private val VIDEO_EXT = setOf(
            "mkv", "mp4", "m4v", "avi", "mov", "wmv", "mpg", "mpeg", "ts", "m2ts", "mts", "webm", "flv", "divx", "3gp", "ogv",
        )
        private val SUB_EXT = setOf("srt", "ass", "ssa", "vtt")

        fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT
        fun isSubtitle(name: String) = name.substringAfterLast('.', "").lowercase() in SUB_EXT
        fun isSubsFolder(name: String) = name.equals("subs", true) || name.equals("subtitles", true)

        private val EXTRA = Regex("""(?i)(^|[ ._\-\[(])(sample|trailer)([ ._\-\])]|$)""")
        fun isExtra(name: String) = EXTRA.containsMatchIn(name.substringBeforeLast('.'))

        fun isJunk(name: String) = name.startsWith(".") || name.startsWith("@") || name.startsWith("$") ||
            name.startsWith("#") || name.equals("System Volume Information", true) || name.equals("lost+found", true) ||
            name.equals("extras", true) || name.equals("featurettes", true) || name.equals("VIDEO_TS", true) ||
            name.equals("BDMV", true)

        fun seasonNumber(name: String): Int? {
            Regex("""(?i)^season[ ._-]*(\d{1,3})""").find(name)?.let { return it.groupValues[1].toInt() }
            Regex("""(?i)^s(\d{1,3})$""").find(name)?.let { return it.groupValues[1].toInt() }
            if (name.equals("specials", true) || name.equals("special", true)) return 0
            Regex("""^(\d{1,3})$""").find(name)?.let { return it.groupValues[1].toInt() }
            return null
        }

        fun parseEpisode(base: String): Pair<Int, Int>? {
            Regex("""(?i)s(\d{1,3})[ ._-]*e(\d{1,4})""").find(base)?.let {
                return it.groupValues[1].toInt() to it.groupValues[2].toInt()
            }
            Regex("""(?i)(?:^|[ ._\-\[])(\d{1,2})x(\d{2,3})(?:[ ._\-\]]|$)""").find(base)?.let {
                return it.groupValues[1].toInt() to it.groupValues[2].toInt()
            }
            return null
        }

        fun episodeTitleFromName(base: String): String {
            val m = Regex("""(?i)(?:s\d{1,3}[ ._-]*e\d{1,4}(?:[-_ ]?e\d{1,4})*|\d{1,2}x\d{2,3})[ ._-]*(.*)$""").find(base)
            val t = m?.groupValues?.get(1)?.trim(' ', '-', '.', '_')
            return if (!t.isNullOrEmpty()) cleanName(t) else cleanName(base)
        }

        /** "Aliens (1986)" -> ("Aliens", 1986); "Some.Movie.2010.1080p" -> ("Some Movie", 2010) */
        fun parseName(raw: String): Pair<String, Int?> {
            Regex("""^(.+?)\s*[(\[](\d{4})[)\]]""").find(raw)?.let {
                return cleanName(it.groupValues[1]) to it.groupValues[2].toInt()
            }
            Regex("""^(.+?)[ ._-]+((?:19|20)\d{2})(?:[ ._-]|$)""").find(raw)?.let {
                return cleanName(it.groupValues[1]) to it.groupValues[2].toInt()
            }
            return cleanName(raw) to null
        }

        private fun cleanName(s: String): String {
            val t = if (!s.contains(' ')) s.replace('.', ' ').replace('_', ' ') else s
            return t.trim()
        }

        /** Sort key: ignore leading articles, punctuation and accents. */
        fun sortKey(title: String): String {
            var t = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            t = t.trimStart { !it.isLetterOrDigit() }
            for (a in listOf("the ", "a ", "an ")) if (t.startsWith(a)) { t = t.removePrefix(a); break }
            return t
        }

        fun describe(e: Throwable): String {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            return msg.lineSequence().first().take(200)
        }
    }
}
