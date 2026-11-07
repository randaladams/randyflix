package com.randyflix.ui

import android.content.Context
import com.randyflix.data.Playback
import kotlin.math.roundToInt

object Format {
    /** 137 -> "2h 17m" */
    fun minutes(min: Int?): String? {
        if (min == null || min <= 0) return null
        val h = min / 60
        val m = min % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** milliseconds -> "1:02:03" / "12:34" */
    fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun left(r: Playback.Resume): String {
        val min = (r.remaining / 60000.0).roundToInt()
        val left = if (min >= 60) "${min / 60}h ${min % 60}m left" else "${min}m left"
        return if (r.part > 0) "Disc ${r.part + 1} · $left" else left
    }

    /** "1:02:03", or "disc 2, 0:45:12" past the first disc of a multi-disc movie. */
    fun resumePoint(r: Playback.Resume): String =
        if (r.part > 0) "disc ${r.part + 1}, ${clock(r.position)}" else clock(r.position)

    /** "0:42:10", or "Disc 2 · 0:42:10" for a multi-disc movie. */
    fun bookmark(b: Playback.Bookmark, multiDisc: Boolean): String =
        if (multiDisc) "Disc ${b.part + 1} · ${clock(b.position)}" else clock(b.position)

    /** Playback status of a single video, for cards and lists. */
    fun status(id: String): String? {
        if (Playback.isWatched(id)) return "✓ Watched"
        Playback.resume(id)?.let { return left(it) }
        return null
    }

    fun episodeCode(season: Int, episode: Int): String =
        if (season == 0) "Special $episode" else "S${season}E$episode"

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    fun videoInfo(codec: String?, height: Int?, audio: String?): String? {
        val parts = ArrayList<String>()
        height?.let {
            parts += when {
                it >= 2000 -> "4K"
                it >= 1000 -> "1080p"
                it >= 700 -> "720p"
                else -> "SD"
            }
        }
        codec?.let { parts += it.uppercase() }
        audio?.let { parts += it.uppercase() }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
