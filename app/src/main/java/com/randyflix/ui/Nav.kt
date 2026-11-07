package com.randyflix.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import com.randyflix.data.ActionItem
import com.randyflix.data.Channel
import com.randyflix.data.ContinueItem
import com.randyflix.data.EpisodeRef
import com.randyflix.data.Library
import com.randyflix.data.LiveTv
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
            is Channel -> watch(activity, item, LiveTv.LIST_ALL)
            is ContinueItem -> play(activity, item.id, item.resume?.position ?: 0L, item.resume?.part ?: 0)
            is ActionItem -> when (item.id) {
                ACTION_MOVIES -> activity.startActivity(GridActivity.intent(activity, GridFragment.MODE_MOVIES, "Movies"))
                ACTION_SHOWS -> activity.startActivity(GridActivity.intent(activity, GridFragment.MODE_SHOWS, "TV Shows"))
                ACTION_CHANNELS -> activity.startActivity(GridActivity.intent(activity, GridFragment.MODE_CHANNELS, "My channels"))
                ACTION_GUIDE -> activity.startActivity(Intent(activity, GuideActivity::class.java))
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

    /** Live TV, stepping through [list] (favorites or all channels) with Up/Down. */
    fun watch(activity: Activity, channel: Channel, list: String) {
        activity.startActivity(
            Intent(activity, LiveActivity::class.java).putExtra(EXTRA_ID, channel.url).putExtra(LiveActivity.EXTRA_LIST, list)
        )
    }

    /** The ☰ menu on a channel tile or guide row: watch it, or change My channels / Favorites. */
    fun channelMenu(activity: Activity, channel: Channel, list: String) {
        val mine = LiveTv.isMine(channel)
        val favorite = LiveTv.isFavorite(channel)
        AlertDialog.Builder(activity)
            .setTitle(channel.name)
            .setItems(arrayOf(
                "Watch",
                if (mine) "Remove from My channels" else "Add to My channels",
                if (favorite) "Remove from Favorites (home screen)" else "Add to Favorites (home screen)",
            )) { _, which ->
                when (which) {
                    0 -> watch(activity, channel, list)
                    1 -> Toast.makeText(activity,
                        if (LiveTv.toggleMine(channel)) "Added ${channel.name} to My channels" else "Removed ${channel.name} from My channels",
                        Toast.LENGTH_SHORT).show()
                    else -> toggleFavorite(activity, channel)
                }
            }
            .show()
    }

    /** The ☰ menu on a Continue Watching tile. [changed] redraws the home screen. */
    fun continueMenu(activity: Activity, item: ContinueItem, changed: () -> Unit) {
        val (title, key) = when (val t = item.target) {
            is Movie -> t.title to t.id
            is EpisodeRef -> t.show.title to t.show.id
            else -> return
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(arrayOf("Remove from Continue Watching", "Mark as watched", "Cancel")) { _, which ->
                when (which) {
                    0 -> {
                        Playback.hideFromContinue(key)
                        Toast.makeText(activity, "Removed $title from Continue Watching", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        Playback.setWatched(item.id, true)
                        Toast.makeText(activity, "Marked as watched", Toast.LENGTH_SHORT).show()
                    }
                }
                changed()
            }
            .show()
    }

    fun toggleFavorite(activity: Activity, channel: Channel) {
        val wasFavorite = LiveTv.isFavorite(channel)
        val message = when {
            !LiveTv.toggleFavorite(channel) -> "Favorites are full (${LiveTv.MAX_FAVORITES}). Remove one first."
            wasFavorite -> "Removed ${channel.name} from favorites"
            else -> "Added ${channel.name} to favorites"
        }
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Ask "Resume / Start over / bookmarks" when there is a saved position or bookmark, otherwise
     * just play. [multiDisc] labels bookmarks with their disc.
     */
    fun playWithPrompt(activity: Activity, id: String, title: String, multiDisc: Boolean = false) {
        val r = Playback.resume(id)
        val marks = Playback.bookmarks(id)
        if (r == null && marks.isEmpty()) {
            play(activity, id, 0L)
            return
        }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (r != null) {
            labels += "Resume from ${Format.resumePoint(r)}"
            actions += { play(activity, id, r.position, r.part) }
        }
        labels += "Start from the beginning"
        actions += { play(activity, id, 0L) }
        for (b in marks) {
            labels += "🔖 Bookmark ${Format.bookmark(b, multiDisc)}"
            actions += { play(activity, id, b.position, b.part) }
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    const val EXTRA_ID = "id"
    const val ACTION_MOVIES = "movies"
    const val ACTION_SHOWS = "shows"
    const val ACTION_SEARCH = "search"
    const val ACTION_CHANNELS = "channels"
    const val ACTION_GUIDE = "guide"
    const val ACTION_REFRESH = "refresh"
    const val ACTION_SETTINGS = "settings"
}
