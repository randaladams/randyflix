package com.randyflix.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Resume positions and watched flags, stored on the Firestick. Keyed by video path
 * (disc 1's path for multi-disc movies, with [Resume.part] saying which disc to continue).
 */
object Playback {
    data class Resume(val position: Long, val duration: Long, val time: Long, val part: Int = 0) {
        val fraction: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val remaining: Long get() = (duration - position).coerceAtLeast(0)
    }

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    }

    fun resume(id: String): Resume? {
        val parts = prefs.getString("p:$id", null)?.split(',') ?: return null
        if (parts.size != 3 && parts.size != 4) return null
        val pos = parts[0].toLongOrNull() ?: return null
        val dur = parts[1].toLongOrNull() ?: return null
        val time = parts[2].toLongOrNull() ?: return null
        val part = parts.getOrNull(3)?.toIntOrNull() ?: 0
        return Resume(pos, dur, time, part)
    }

    fun watchedAt(id: String): Long = prefs.getLong("w:$id", 0L)
    fun isWatched(id: String): Boolean = watchedAt(id) > 0L

    /**
     * Called periodically during playback and when the player closes. For a multi-disc movie,
     * [part] is the disc being played and [lastPart] says whether it's the final one.
     */
    fun save(id: String, position: Long, duration: Long, part: Int = 0, lastPart: Boolean = true) {
        val nearEnd = duration > 0 && position >= duration * 0.92
        if (nearEnd && lastPart) {
            setWatched(id, true)
            return
        }
        // Stopping near the end of a disc picks up at the start of the next one.
        val (p, pos) = if (nearEnd) (part + 1) to 0L else part to position
        val e = prefs.edit()
        if (p == 0 && pos < 30_000) e.remove("p:$id")
        else e.putString("p:$id", "$pos,$duration,${System.currentTimeMillis()},$p")
        e.apply()
    }

    fun setWatched(id: String, watched: Boolean) {
        val e = prefs.edit().remove("p:$id")
        if (watched) e.putLong("w:$id", System.currentTimeMillis()) else e.remove("w:$id")
        e.apply()
    }

    /**
     * Takes a movie or show off Continue Watching (its resume point is kept). It comes back by
     * itself once it's played again. [key] is the movie's id, or the show's id.
     */
    fun hideFromContinue(key: String) {
        prefs.edit().putLong("h:$key", System.currentTimeMillis()).apply()
    }

    fun hiddenAt(key: String): Long = prefs.getLong("h:$key", 0L)

    fun lastActivity(id: String): Long = maxOf(resume(id)?.time ?: 0L, watchedAt(id))

    /** A saved spot to jump back to. [part] is the disc, for multi-disc movies. */
    data class Bookmark(val position: Long, val part: Int = 0)

    /** Bookmarks for a video, in order (disc, then time). Stored as "part:position;…". */
    fun bookmarks(id: String): List<Bookmark> =
        prefs.getString("b:$id", null)?.split(';')?.mapNotNull { s ->
            val bits = s.split(':')
            val part = bits.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val pos = bits.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            Bookmark(pos, part)
        }?.sortedWith(compareBy({ it.part }, { it.position })) ?: emptyList()

    /** Adds a bookmark; one within 5 seconds of an existing one replaces it. Keeps at most 30. */
    fun addBookmark(id: String, b: Bookmark) {
        val list = bookmarks(id).filterNot { it.part == b.part && kotlin.math.abs(it.position - b.position) < 5_000 } + b
        writeBookmarks(id, list.sortedWith(compareBy({ it.part }, { it.position })).takeLast(30))
    }

    fun removeBookmark(id: String, b: Bookmark) = writeBookmarks(id, bookmarks(id) - b)

    private fun writeBookmarks(id: String, list: List<Bookmark>) {
        val e = prefs.edit()
        if (list.isEmpty()) e.remove("b:$id") else e.putString("b:$id", list.joinToString(";") { "${it.part}:${it.position}" })
        e.apply()
    }
}
