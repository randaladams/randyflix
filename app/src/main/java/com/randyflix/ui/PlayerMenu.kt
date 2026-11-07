package com.randyflix.ui

import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.C
import androidx.media3.common.Format as MediaFormat
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.randyflix.R
import java.util.Locale

/**
 * The ☰ panel shown on the right of the player (movies/TV and Live TV). Built from a list of
 * [Item]s; sub-lists (bookmarks, languages) open in the same panel and Back returns. The
 * activity forwards keys with [dispatchKey] so D-pad moves within the panel while it's open.
 */
class PlayerMenu(private val activity: FragmentActivity, private val onClosed: () -> Unit) {
    enum class After { REFRESH, BACK, CLOSE, STAY }

    /** [action] runs on Select; then the panel refreshes, goes back a level, or closes. */
    class Item(val label: String, val after: After = After.REFRESH, val action: () -> Unit)

    /** A panel page: its title and items, rebuilt each time it's shown so labels stay current. */
    private class Page(val build: () -> Pair<String, List<Item>>)

    private val pages = ArrayDeque<Page>()
    private val root: FrameLayout
    private val title: TextView
    private val list: LinearLayout
    private val scroll: ScrollView

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    init {
        val ctx = activity
        root = FrameLayout(ctx).apply { visibility = View.GONE }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 16, 19, 24))
            val pad = Format.dp(ctx, 24)
            setPadding(pad, pad, pad, pad)
        }
        title = TextView(ctx).apply {
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            textSize = 18f
            maxLines = 2
            setPadding(0, 0, 0, Format.dp(ctx, 14))
        }
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(ctx).apply { isFillViewport = false; addView(list) }
        panel.addView(title)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(panel, FrameLayout.LayoutParams(Format.dp(ctx, 380), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        activity.findViewById<ViewGroup>(android.R.id.content)
            .addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun open(build: () -> Pair<String, List<Item>>) {
        pages.clear()
        pages.addLast(Page(build))
        root.visibility = View.VISIBLE
        render(0)
    }

    /** Opens a sub-list on top of the current page. */
    fun push(build: () -> Pair<String, List<Item>>) {
        pages.addLast(Page(build))
        render(0)
    }

    fun back() {
        pages.removeLastOrNull()
        if (pages.isEmpty()) close() else render(0)
    }

    fun close() {
        if (!isOpen) return
        pages.clear()
        list.removeAllViews()
        root.visibility = View.GONE
        onClosed()
    }

    private fun render(focus: Int) {
        val page = pages.lastOrNull() ?: return close()
        val (heading, items) = page.build()
        title.text = heading
        list.removeAllViews()
        if (pages.size > 1) {
            list.addView(button("‹  Back") { back() })
        }
        for (item in items) {
            list.addView(button(item.label) {
                val depth = pages.size
                item.action()
                if (!isOpen || pages.size != depth) return@button   // the action opened/closed something itself
                when (item.after) {
                    After.REFRESH -> render(currentFocus())
                    After.BACK -> back()
                    After.CLOSE -> close()
                    After.STAY -> {}
                }
                // Track changes land a moment later; redraw so labels like "CC: On · English" catch up.
                if (item.after == After.REFRESH || item.after == After.BACK) {
                    list.postDelayed({ if (isOpen) render(currentFocus()) }, 400)
                }
            })
        }
        // Keep focus inside the panel: Left/Right stay put, Up/Down wrap around the ends.
        val n = list.childCount
        val ids = IntArray(n) { View.generateViewId().also { id -> list.getChildAt(it).id = id } }
        for (i in 0 until n) list.getChildAt(i).apply {
            nextFocusLeftId = ids[i]
            nextFocusRightId = ids[i]
            nextFocusUpId = ids[(i - 1 + n) % n]
            nextFocusDownId = ids[(i + 1) % n]
        }
        val target = list.getChildAt(focus.coerceIn(0, maxOf(0, n - 1)))
        target?.post { target.requestFocus() }
    }

    private fun currentFocus(): Int {
        for (i in 0 until list.childCount) if (list.getChildAt(i).hasFocus()) return i
        return 0
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(activity, null, 0, R.style.TvButton).apply {
            text = label
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = Format.dp(activity, 8) }
        }

    /** What the activity should do with a key after [dispatchKey]. */
    enum class Key {
        /** Handled here (☰, or Back while open). */
        CONSUMED,
        /**
         * The panel is open: skip the player's own key handling and let the window handle it
         * normally. This must not be marked as handled, because Android only moves D-pad focus
         * between buttons for keys nobody consumed.
         */
        TO_PANEL,
        /** The panel is closed: handle as usual. */
        NOT_MINE,
    }

    /** ☰ opens/closes on key-up, so one press does one thing. */
    fun dispatchKey(event: KeyEvent, openMenu: () -> Unit): Key {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) { if (isOpen) close() else openMenu() }
            return Key.CONSUMED
        }
        if (!isOpen) return Key.NOT_MINE
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) back()
            return Key.CONSUMED
        }
        return Key.TO_PANEL
    }
}

/** Captions (subtitles) and audio language for a player, shared by both players. */
@OptIn(UnstableApi::class)
object TrackChoice {
    class Track(val group: Tracks.Group, val index: Int, val name: String, val selected: Boolean)

    fun list(player: Player?, type: Int): List<Track> {
        val out = ArrayList<Track>()
        for (g in player?.currentTracks?.groups ?: return out) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out += Track(g, i, name(g.getTrackFormat(i), type, out.size + 1), g.isTrackSelected(i))
            }
        }
        return out
    }

    fun captionsOn(player: Player): Boolean =
        !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

    /** The caption track playing, or null. */
    fun currentCaption(player: Player): Track? =
        if (captionsOn(player)) list(player, C.TRACK_TYPE_TEXT).firstOrNull { it.selected } else null

    /** Every player starts with captions off. */
    fun startOff(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    /** Turns captions on (picking the TV's language, else the first track) or off. */
    fun setCaptions(player: Player, on: Boolean) {
        if (!on) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            return
        }
        val tracks = list(player, C.TRACK_TYPE_TEXT)
        val lang = Locale.getDefault().language
        val pick = tracks.firstOrNull { it.group.getTrackFormat(it.index).language?.startsWith(lang) == true && !isForced(it) }
            ?: tracks.firstOrNull { !isForced(it) } ?: tracks.firstOrNull()
        select(player, C.TRACK_TYPE_TEXT, pick)
    }

    /**
     * After a new episode or channel loads, the old caption choice no longer points at a real
     * track; if captions are on but nothing is showing, pick again.
     */
    fun reapplyCaptions(player: Player) {
        if (!captionsOn(player)) return
        val tracks = list(player, C.TRACK_TYPE_TEXT)
        if (tracks.isNotEmpty() && tracks.none { it.selected }) setCaptions(player, true)
    }

    fun select(player: Player, type: Int, track: Track?) {
        val params = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
        if (track != null) params.setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.index))
        player.trackSelectionParameters = params.build()
    }

    private fun isForced(t: Track) = (t.group.getTrackFormat(t.index).selectionFlags and C.SELECTION_FLAG_FORCED) != 0

    /** "English", "English · Forced", "Spanish · 5.1" … */
    private fun name(f: MediaFormat, type: Int, n: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { code -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code } }
        val label = f.label?.takeIf { it.isNotBlank() }
        val parts = ArrayList<String>()
        parts += when {
            label != null && lang != null && !label.contains(lang, true) -> "$label ($lang)"
            label != null -> label
            lang != null -> lang
            type == C.TRACK_TYPE_TEXT && f.sampleMimeType?.contains("608") == true -> "Closed captions"
            else -> "Track $n"
        }
        val forced = (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
        if (type == C.TRACK_TYPE_TEXT && forced && label?.contains("Forced", true) != true) parts += "Forced"
        if (type == C.TRACK_TYPE_AUDIO) when (f.channelCount) {
            1 -> parts += "Mono"
            2 -> parts += "Stereo"
            6 -> parts += "5.1"
            8 -> parts += "7.1"
        }
        return parts.joinToString(" · ")
    }

    /**
     * The shared part of both menus: CC on/off, then caption and audio language lists when
     * there's a choice.
     */
    fun menuItems(player: Player, menu: PlayerMenu, toast: (String) -> Unit): List<PlayerMenu.Item> {
        val items = ArrayList<PlayerMenu.Item>()
        val text = list(player, C.TRACK_TYPE_TEXT)
        val audio = list(player, C.TRACK_TYPE_AUDIO)
        val current = currentCaption(player)
        items += when {
            text.isEmpty() -> PlayerMenu.Item("CC: none available", PlayerMenu.After.STAY) {
                toast("No captions on this one")
            }
            captionsOn(player) -> PlayerMenu.Item("CC: On" + (current?.let { " · ${it.name}" } ?: "")) { setCaptions(player, false) }
            else -> PlayerMenu.Item("CC: Off") { setCaptions(player, true) }
        }
        if (text.size > 1) {
            items += PlayerMenu.Item("Caption language…", PlayerMenu.After.STAY) {
                menu.push {
                    "Caption language" to list(player, C.TRACK_TYPE_TEXT).map { t ->
                        PlayerMenu.Item((if (t.selected && captionsOn(player)) "✓  " else "     ") + t.name, PlayerMenu.After.BACK) {
                            select(player, C.TRACK_TYPE_TEXT, t)
                        }
                    }
                }
            }
        }
        if (audio.size > 1) {
            val playing = audio.firstOrNull { it.selected }?.name
            items += PlayerMenu.Item("Audio: ${playing ?: "Default"}…", PlayerMenu.After.STAY) {
                menu.push {
                    "Audio language" to list(player, C.TRACK_TYPE_AUDIO).map { t ->
                        PlayerMenu.Item((if (t.selected) "✓  " else "     ") + t.name, PlayerMenu.After.BACK) {
                            select(player, C.TRACK_TYPE_AUDIO, t)
                        }
                    }
                }
            }
        }
        return items
    }
}
