package com.randyflix.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import com.randyflix.data.ActionItem
import com.randyflix.data.ContinueItem
import com.randyflix.data.EpisodeRef
import com.randyflix.data.Library
import com.randyflix.data.Movie
import com.randyflix.data.Playback
import com.randyflix.data.Show

/** Where clicking a card goes. */
object Nav {
    fun open(activity: Activity, item: Any?) {
        when (item) {
            is Movie -> activity.startActivity(Intent(activity, MovieActivity::class.java).putExtra(EXTRA_ID, item.id))
            is Show -> activity.startActivity(Intent(activity, ShowActivity::class.java).putExtra(EXTRA_ID, item.id))
            is EpisodeRef -> playWithPrompt(activity, item.episode.id, item.episode.title)
            is ContinueItem -> play(activity, item.id, item.resume?.position ?: 0L, item.resume?.part ?: 0)
            is ActionItem -> when (item.id) {
                ACTION_MOVIES -> activity.startActivity(GridActivity.intent(activity, GridFragment.MODE_MOVIES, "Movies"))
                ACTION_SHOWS -> activity.startActivity(GridActivity.intent(activity, GridFragment.MODE_SHOWS, "TV Shows"))
                ACTION_SEARCH -> activity.startActivity(Intent(activity, SearchActivity::class.java))
                ACTION_SETTINGS -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
                ACTION_REFRESH -> {
                    if (Library.isScanning) {
                        Toast.makeText(activity, "Already scanning…", Toast.LENGTH_SHORT).show()
                    } else {
                        Library.scan(false)
                        Toast.makeText(activity, "Refreshing library…", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** [part] is the disc to start on, for multi-disc movies. */
    fun play(activity: Activity, id: String, startMs: Long, part: Int = 0) {
        activity.startActivity(
            Intent(activity, PlayerActivity::class.java).putExtra(EXTRA_ID, id)
                .putExtra(PlayerActivity.EXTRA_START, startMs).putExtra(PlayerActivity.EXTRA_PART, part)
        )
    }

    /** Ask "Resume / Start over" when there is a saved position, otherwise just play. */
    fun playWithPrompt(activity: Activity, id: String, title: String) {
        val r = Playback.resume(id)
        if (r == null) {
            play(activity, id, 0L)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(arrayOf("Resume from ${Format.resumePoint(r)}", "Start from the beginning")) { _, which ->
                if (which == 0) play(activity, id, r.position, r.part) else play(activity, id, 0L)
            }
            .show()
    }

    const val EXTRA_ID = "id"
    const val ACTION_MOVIES = "movies"
    const val ACTION_SHOWS = "shows"
    const val ACTION_SEARCH = "search"
    const val ACTION_REFRESH = "refresh"
    const val ACTION_SETTINGS = "settings"
}
