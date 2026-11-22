package com.randyflix.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.RequiresApi
import com.randyflix.smb.Smb
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Walks the music folder. A folder whose songs (names and sizes) are unchanged keeps them from the
 * last scan; only new or changed folders are read. mp3 tags are read directly (a few small reads
 * per song); other formats use Android's tag reader. Progress is saved every so often, so music
 * shows up while scanning and an interrupted scan carries on where it stopped.
 */
class MusicScanner(
    private val root: String,
    private val old: MusicData,
    private val artDir: File,
    private val progress: (String) -> Unit,
    private val checkpoint: (MusicData) -> Unit,
) {
    private val oldByFolder = old.tracks.groupBy { it.folder }
    private val tracks = ArrayList<Track>()
    private val playlistFiles = ArrayList<Pair<String, ByteArray>>()   // path, contents
    private val visited = HashSet<String>()
    private var folders = 0
    private var lastCheckpoint = 0

    fun run(): MusicData {
        walk(root.trimEnd('/'), depth = 0)
        progress("Music: reading playlists")
        val byName = HashMap<String, MutableList<Track>>()
        for (t in tracks) byName.getOrPut(t.id.substringAfterLast('/').lowercase()) { ArrayList() } += t
        val byPath = tracks.associateBy { it.id.lowercase() }
        val lists = playlistFiles.mapNotNull { (path, bytes) -> runCatching { parsePlaylist(path, bytes, byPath, byName) }.getOrNull() }
        return MusicData(root, ArrayList(tracks), lists, System.currentTimeMillis())
    }

    private fun walk(folder: String, depth: Int) {
        if (depth > 12) return
        val entries = Smb.list(folder).filter { !skip(it.name) }
        folders++
        visited += folder
        progress("Music: ${folder.substringAfterLast('/')}  ($folders folders, ${tracks.size} songs)")
        val files = entries.filter { !it.isDir }
        val audio = files.filter { ext(it.name) in AUDIO }.sortedBy { it.name.lowercase() }
        files.filter { ext(it.name) in PLAYLIST }.forEach { f ->
            runCatching { playlistFiles += "$folder/${f.name}" to Smb.readBytes("$folder/${f.name}") }
        }
        if (audio.isNotEmpty()) {
            val folderArt = COVER_NAMES.firstNotNullOfOrNull { n -> files.firstOrNull { it.name.equals(n, true) } }
                ?: files.firstOrNull { ext(it.name) in IMAGE }
            val artPath = folderArt?.let { "$folder/${it.name}" }
            val prev = oldByFolder[folder].orEmpty()
            val unchanged = prev.size == audio.size &&
                prev.map { it.id.substringAfterLast('/') to it.size }.toSet() == audio.map { it.name to it.size }.toSet()
            if (unchanged) {
                // Keep the tags; a cover added to the folder since still takes over.
                tracks += if (artPath != null) prev.map { it.copy(art = artPath) } else prev
            } else {
                var needPicture = artPath == null
                val read = audio.map { a ->
                    val r = readTrack(folder, a.name, a.size, depth, needPicture)
                    if (r.picture != null) needPicture = false
                    r
                }
                tracks += withArt(read, artPath)
            }
        }
        if (folders - lastCheckpoint >= 50) {
            lastCheckpoint = folders
            // What's been read so far, plus last scan's songs from folders not reached yet.
            runCatching { checkpoint(MusicData(root, tracks + old.tracks.filter { it.folder !in visited }, old.playlistFiles, old.lastScan)) }
        }
        for (d in entries.filter { it.isDir }.sortedBy { it.name.lowercase() }) {
            try {
                walk("$folder/${d.name}", depth + 1)
            } catch (e: Exception) {
                // One unreadable folder doesn't stop the rest.
            }
        }
    }

    private class Read(val track: Track, val picture: ByteArray?)

    /** Folder cover if there is one; otherwise the picture inside the songs (saved on the Firestick). */
    private fun withArt(list: List<Read>, folderArt: String?): List<Track> {
        if (folderArt != null) return list.map { it.track.copy(art = folderArt) }
        return list.groupBy { it.track.albumKey }.flatMap { (key, reads) ->
            val out = File(artDir, md5(key) + ".jpg")
            if (!out.exists()) {
                reads.firstNotNullOfOrNull { it.picture }?.let { bytes ->
                    try {
                        decodeScaled(bytes, 600)?.let { bmp ->
                            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                            bmp.recycle()
                        }
                    } catch (e: Throwable) {
                        out.delete()
                    }
                }
            }
            val art = out.path.takeIf { out.exists() }
            reads.map { it.track.copy(art = art) }
        }
    }

    private fun readTrack(folder: String, name: String, size: Long, depth: Int, wantPicture: Boolean): Read {
        val path = "$folder/$name"
        val base = name.substringBeforeLast('.')
        val tags: Tags? = try {
            if (ext(name) == "mp3") {
                val f = Smb.open(path)
                try { Mp3Tags.read(f, size, wantPicture) } finally { runCatching { f.close() } }
            }
            else if (Build.VERSION.SDK_INT >= 23) retrieverTags(path, wantPicture)
            else null
        } catch (e: Throwable) {
            null   // unreadable tags: fall back to the folder and file names
        }
        // Folder-name fallbacks: …/Artist/Album/01 - Title.mp3
        val fallbackAlbum = if (depth >= 1) folder.substringAfterLast('/') else "Unknown album"
        val fallbackArtist = if (depth >= 2) folder.substringBeforeLast('/').substringAfterLast('/') else "Unknown artist"
        val num = Regex("""^(\d{1,3})[ ._\-]+""").find(base)
        val fallbackTitle = (if (num != null) base.substring(num.range.last + 1) else base).trim(' ', '-', '_', '.')
        val track = Track(
            id = path,
            title = tags?.title ?: fallbackTitle.ifBlank { base },
            artist = tags?.artist ?: tags?.albumArtist ?: fallbackArtist,
            albumArtist = tags?.albumArtist,
            album = tags?.album ?: fallbackAlbum,
            genres = genres(tags?.genre),
            track = tags?.track ?: num?.groupValues?.get(1)?.toInt() ?: 0,
            disc = tags?.disc ?: 1,
            year = tags?.year,
            duration = tags?.duration ?: 0L,
            art = null,
            folder = folder,
            size = size,
        )
        return Read(track, tags?.picture)
    }

    @RequiresApi(23)
    private fun retrieverTags(path: String, wantPicture: Boolean): Tags {
        val f = Smb.open(path)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(SmbMediaSource(f))
            fun s(k: Int) = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            return Tags(
                title = s(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                genre = s(MediaMetadataRetriever.METADATA_KEY_GENRE),
                track = s(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let { firstInt(it) },
                disc = s(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.let { firstInt(it) },
                year = year(s(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: s(MediaMetadataRetriever.METADATA_KEY_DATE)),
                duration = s(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                picture = if (wantPicture) r.embeddedPicture else null,
            )
        } finally {
            runCatching { r.release() }
            runCatching { f.close() }
        }
    }

    // ---- playlists (.m3u / .m3u8)

    private fun parsePlaylist(path: String, bytes: ByteArray, byPath: Map<String, Track>, byName: Map<String, List<Track>>): MusicPlaylist? {
        val text = decode(bytes)
        val folder = path.substringBeforeLast('/')
        val ids = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { resolve(it, folder, byPath, byName) }.toList()
        if (ids.isEmpty()) return null
        val relFolder = folder.removePrefix(root.trimEnd('/')).trim('/').ifEmpty { null }
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        return MusicPlaylist("file:$path", name, relFolder, ids, false)
    }

    /** A playlist line: relative to the playlist, or a full path from another computer (matched by its end). */
    private fun resolve(line: String, folder: String, byPath: Map<String, Track>, byName: Map<String, List<Track>>): String? {
        val p = line.replace('\\', '/')
        val absolute = p.startsWith("/") || Regex("""^[A-Za-z]:/""").containsMatchIn(p) || p.contains("://")
        if (!absolute) {
            val parts = ArrayList(folder.split('/'))
            for (seg in p.split('/')) when (seg) {
                "", "." -> {}
                ".." -> if (parts.size > 1) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
            byPath[parts.joinToString("/").lowercase()]?.let { return it.id }
        }
        // Same file name; if several, the one whose folders match the line's best.
        val segs = p.trimEnd('/').split('/').filter { it.isNotEmpty() }
        val candidates = byName[segs.lastOrNull()?.lowercase() ?: return null] ?: return null
        if (candidates.size == 1) return candidates[0].id
        for (n in minOf(3, segs.size) downTo 2) {
            val tail = "/" + segs.takeLast(n).joinToString("/").lowercase()
            val hits = candidates.filter { it.id.lowercase().endsWith(tail) }
            if (hits.size == 1) return hits[0].id
        }
        return null
    }

    private fun decode(bytes: ByteArray): String {
        val utf = bytes.toString(Charsets.UTF_8)
        return if (utf.contains('�')) bytes.toString(Charsets.ISO_8859_1) else utf.removePrefix("﻿")
    }

    companion object {
        /** Formats the Firestick can play. (WMA isn't one: ExoPlayer can't open it.) */
        val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "mka")
        val PLAYLIST = setOf("m3u", "m3u8")
        private val IMAGE = setOf("jpg", "jpeg", "png", "webp")
        private val COVER_NAMES = listOf(
            "cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "front.png",
            "album.jpg", "album.png", "albumart.jpg", "albumartlarge.jpg",
        )

        /** Hidden, system and set-aside folders (names starting with _, like _cleanup) are skipped. */
        private fun skip(name: String) = name.startsWith(".") || name.startsWith("@") || name.startsWith("#") ||
            name.startsWith("_") || name.startsWith("$") || name.equals("System Volume Information", true)

        private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

        fun firstInt(s: String) = Regex("""\d+""").find(s)?.value?.toIntOrNull()

        fun year(s: String?) = s?.let { Regex("""(1[89]|20)\d\d""").find(it)?.value?.toInt() }

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

/** What a song's tags say (any of it may be missing). */
class Tags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
    val duration: Long = 0L,
    val picture: ByteArray? = null,
)

/** Random-access reads of a file on the share, through a small cache (each miss is one network read). */
private class ShareReader(private val f: com.hierynomus.smbj.share.File, val size: Long) {
    private var start = -1L
    private var buf = ByteArray(CHUNK)
    private var len = 0

    fun read(pos: Long, n: Int): ByteArray {
        if (pos < 0 || pos >= size || n <= 0) return ByteArray(0)
        val out = ByteArray(minOf(n.toLong(), size - pos).toInt())
        var done = 0
        var p = pos
        while (done < out.size) {
            if (start < 0 || p < start || p >= start + len) {
                val want = minOf(maxOf(CHUNK, out.size - done).toLong(), size - p, MAX_READ.toLong()).toInt()
                if (buf.size < want) buf = ByteArray(want)
                start = p
                len = 0
                while (len < want) {
                    val r = f.read(buf, p + len, len, minOf(64 * 1024, want - len))
                    if (r <= 0) break
                    len += r
                }
                if (len == 0) break
            }
            val off = (p - start).toInt()
            val take = minOf(len - off, out.size - done)
            System.arraycopy(buf, off, out, done, take)
            done += take
            p += take
        }
        return if (done == out.size) out else out.copyOf(done)
    }

    companion object {
        const val CHUNK = 16 * 1024
        const val MAX_READ = 4 * 1024 * 1024
    }
}

/**
 * Reads an mp3's ID3 tags (v2.2–2.4, then v1 at the end for anything missing) and works out its
 * length from the first audio frame. Only the tag block and the start of the audio are read;
 * the cover picture inside the tags is skipped unless [wantPicture].
 */
object Mp3Tags {
    private val V22 = mapOf("TT2" to "title", "TP1" to "artist", "TP2" to "albumartist", "TAL" to "album", "TCO" to "genre",
        "TRK" to "track", "TPA" to "disc", "TYE" to "year", "PIC" to "pic")
    private val V23 = mapOf("TIT2" to "title", "TPE1" to "artist", "TPE2" to "albumartist", "TALB" to "album", "TCON" to "genre",
        "TRCK" to "track", "TPOS" to "disc", "TYER" to "year", "TDRC" to "year", "APIC" to "pic")
    private val BITRATE_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BITRATE_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

    fun read(f: com.hierynomus.smbj.share.File, size: Long, wantPicture: Boolean): Tags {
        val r = ShareReader(f, size)
        val t = HashMap<String, String>()
        var picture: ByteArray? = null
        var audioStart = 0L
        val head = r.read(0, 10)
        if (head.size == 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val ver = u(head[3])
            val flags = u(head[5])
            val tagSize = syncsafe(head, 6)
            audioStart = 10L + tagSize + (if (flags and 0x10 != 0) 10 else 0)
            var pos = 10L
            val end = 10L + tagSize
            if (flags and 0x40 != 0 && ver >= 3) {   // extended header
                val eh = r.read(pos, 4)
                if (eh.size == 4) pos += if (ver == 4) syncsafe(eh, 0) else (be(eh, 0, 4) + 4)
            }
            val hl = if (ver == 2) 6 else 10
            val names = if (ver == 2) V22 else V23
            while (pos + hl <= end) {
                val fh = r.read(pos, hl)
                if (fh.size < hl || fh[0].toInt() == 0) break
                val id: String
                val fsize: Long
                if (ver == 2) {
                    id = String(fh, 0, 3, Charsets.ISO_8859_1)
                    fsize = be(fh, 3, 3)
                } else {
                    id = String(fh, 0, 4, Charsets.ISO_8859_1)
                    fsize = if (ver == 4) syncsafe(fh, 4) else be(fh, 4, 4)
                }
                if (fsize <= 0 || pos + hl + fsize > end + 16) break
                when (val key = names[id]) {
                    null -> {}
                    "pic" -> if (wantPicture && picture == null && fsize < ShareReader.MAX_READ) {
                        picture = pictureData(r.read(pos + hl, fsize.toInt()), ver == 2)
                    }
                    else -> if (key !in t && fsize < 4096) text(r.read(pos + hl, fsize.toInt()))?.let { t[key] = it }
                }
                pos += hl + fsize
            }
        }
        if ((t["title"] == null || t["artist"] == null || t["album"] == null) && size > 128) {
            val v1 = r.read(size - 128, 128)
            if (v1.size == 128 && v1[0] == 'T'.code.toByte() && v1[1] == 'A'.code.toByte() && v1[2] == 'G'.code.toByte()) {
                fun f(a: Int, b: Int) = String(v1, a, b - a, Charsets.ISO_8859_1).substringBefore('\u0000').trim().ifEmpty { null }
                f(3, 33)?.let { t.putIfAbsent("title", it) }
                f(33, 63)?.let { t.putIfAbsent("artist", it) }
                f(63, 93)?.let { t.putIfAbsent("album", it) }
                f(93, 97)?.let { t.putIfAbsent("year", it) }
            }
        }
        return Tags(
            title = t["title"], artist = t["artist"], albumArtist = t["albumartist"], album = t["album"], genre = t["genre"],
            track = t["track"]?.let { MusicScanner.firstInt(it) }, disc = t["disc"]?.let { MusicScanner.firstInt(it) },
            year = MusicScanner.year(t["year"]), duration = duration(r, audioStart, size), picture = picture,
        )
    }

    /** From the Xing/Info or VBRI header if there is one, otherwise the first frame's bitrate. */
    private fun duration(r: ShareReader, audioStart: Long, size: Long): Long {
        val look = r.read(audioStart, 4096)
        var i = 0
        while (i < look.size - 4) {
            val b1 = u(look[i + 1])
            if (u(look[i]) == 0xFF && b1 and 0xE0 == 0xE0) {
                val verBits = (b1 shr 3) and 3
                val layer = (b1 shr 1) and 3
                val b2 = u(look[i + 2])
                val bi = b2 shr 4
                val si = (b2 shr 2) and 3
                if (verBits != 1 && layer == 1 && bi != 0 && bi != 15 && si != 3) {
                    val rate = when (verBits) { 3 -> intArrayOf(44100, 48000, 32000); 2 -> intArrayOf(22050, 24000, 16000); else -> intArrayOf(11025, 12000, 8000) }[si]
                    val kbps = (if (verBits == 3) BITRATE_V1 else BITRATE_V2)[bi]
                    val spf = if (verBits == 3) 1152 else 576
                    val mono = (u(look[i + 3]) shr 6) == 3
                    val side = if (verBits == 3) (if (mono) 17 else 32) else (if (mono) 9 else 17)
                    val x = i + 4 + side
                    if (x + 12 <= look.size) {
                        val tag = String(look, x, 4, Charsets.ISO_8859_1)
                        if ((tag == "Xing" || tag == "Info") && u(look[x + 7]) and 1 != 0) {
                            return be(look, x + 8, 4) * spf * 1000 / rate
                        }
                    }
                    if (i + 54 <= look.size && String(look, i + 36, 4, Charsets.ISO_8859_1) == "VBRI") {
                        return be(look, i + 50, 4) * spf * 1000 / rate
                    }
                    return if (kbps > 0) (size - audioStart) * 8 / kbps else 0L
                }
            }
            i++
        }
        return 0L
    }

    private fun text(d: ByteArray): String? {
        if (d.isEmpty()) return null
        val body = d.copyOfRange(1, d.size)
        val s = when (u(d[0])) {
            0 -> String(body, Charsets.ISO_8859_1)
            1 -> String(body, Charsets.UTF_16)     // with a byte-order mark
            2 -> String(body, Charsets.UTF_16BE)
            else -> String(body, Charsets.UTF_8)
        }
        return s.replace("﻿", "").substringBefore('\u0000').trim().ifEmpty { null }
    }

    /** The image bytes inside an APIC (or v2.2 PIC) frame. */
    private fun pictureData(d: ByteArray, v22: Boolean): ByteArray? {
        if (d.size < 4) return null
        val enc = u(d[0])
        var i = 1
        if (v22) i += 3 else { while (i < d.size && d[i].toInt() != 0) i++; i++ }   // image format / MIME type
        i++   // picture type
        if (enc == 1 || enc == 2) {
            while (i + 1 < d.size && !(d[i].toInt() == 0 && d[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < d.size && d[i].toInt() != 0) i++
            i++
        }
        return if (i < d.size) d.copyOfRange(i, d.size) else null
    }

    private fun u(b: Byte) = b.toInt() and 0xFF
    private fun be(b: ByteArray, off: Int, n: Int): Long { var v = 0L; for (k in 0 until n) v = (v shl 8) or u(b[off + k]).toLong(); return v }
    private fun syncsafe(b: ByteArray, off: Int): Long =
        ((u(b[off]) shl 21) or (u(b[off + 1]) shl 14) or (u(b[off + 2]) shl 7) or u(b[off + 3])).toLong()
}

/** Lets Android's tag reader read a file straight from the share (formats other than mp3). */
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
