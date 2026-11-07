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

    fun lastActivity(id: String): Long = maxOf(resume(id)?.time ?: 0L, watchedAt(id))
}
