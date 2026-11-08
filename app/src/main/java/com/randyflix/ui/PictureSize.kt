package com.randyflix.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.randyflix.Settings
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Picture size (☰ → Picture), like a TV's aspect button, remembered per movie, per show and
 * per live channel (so stretching an old 4:3 show doesn't also stretch widescreen movies).
 *
 * Older shows on DVD are often stored as 16:9 with the black side bars part of the picture, so
 * the player sees a 16:9 video. For a video that's already the screen's shape, the modes act the
 * way a TV's do: they assume a 4:3 picture inside it.
 */
@OptIn(UnstableApi::class)
object PictureSize {
    enum class Mode(val label: String) {
        NORMAL("Normal"),
        STRETCH("Stretch 16:9"),
        WIDE_ZOOM("Wide zoom"),
        ZOOM("Zoom"),
    }

    /** 4:3 inside a screen-shaped (16:9) video. */
    private const val INNER = 4f / 3f

    /** Which title each player is showing, for remembering its mode. */
    private val keys = WeakHashMap<PlayerView, () -> String>()
    private val layoutWatchers = WeakHashMap<PlayerView, View.OnLayoutChangeListener>()

    private fun modeFor(view: PlayerView): Mode {
        val key = keys[view]?.invoke() ?: return Mode.NORMAL
        val name = Settings.pictureSizeFor(key)
        return Mode.values().firstOrNull { it.name == name } ?: Mode.NORMAL
    }

    /**
     * Keeps [view] showing [player] in the remembered mode for [key] (the movie, show or channel
     * playing): now, when the video's shape changes, and when the screen is laid out.
     */
    fun attach(view: PlayerView, player: Player, key: () -> String) {
        keys[view] = key
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = update(view, player)
        })
        // The player is rebuilt when the screen comes back, so replace (don't add) the watcher.
        layoutWatchers.remove(view)?.let { view.removeOnLayoutChangeListener(it) }
        val watcher = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) apply(view, player)
        }
        layoutWatchers[view] = watcher
        view.addOnLayoutChangeListener(watcher)
        apply(view, player)
    }

    /** Re-applies after the title changed (next episode, another channel). */
    fun update(view: PlayerView, player: Player) {
        if (apply(view, player)) refreshSurface(view)
    }

    /** The ☰ item: shows the mode and opens the list, which stays open to try another. */
    fun menuItems(view: PlayerView, player: Player, menu: PlayerMenu): List<PlayerMenu.Item> = listOf(
        PlayerMenu.Item("Picture: ${modeFor(view).label}…", PlayerMenu.After.STAY) {
            menu.push {
                "Picture  ·  ${shapeOf(player)}" to Mode.values().map { m ->
                    PlayerMenu.Item((if (m == modeFor(view)) "✓  " else "     ") + m.label) {
                        keys[view]?.invoke()?.let { Settings.setPictureSizeFor(it, m.name) }
                        update(view, player)
                    }
                }
            }
        },
    )

    /** "video 4:3", "video 16:9", "video 2.39:1" — so it's clear what the modes have to work with. */
    private fun shapeOf(player: Player): String {
        val s = player.videoSize
        if (s.width <= 0 || s.height <= 0) return "video size not known yet"
        val a = s.width * s.pixelWidthHeightRatio / s.height
        val name = when {
            abs(a - 4f / 3f) < 0.04f -> "4:3"
            abs(a - 16f / 9f) < 0.05f -> "16:9"
            abs(a - 1.85f) < 0.04f -> "1.85:1"
            else -> "%.2f:1".format(a)
        }
        return "video $name"
    }

    /**
     * The Fire TV draws video on a hardware layer that may keep the size it had when the video
     * started. Rebuilding the video surface (hidden for a moment, then shown) makes it take the
     * new size. ExoPlayer reattaches by itself.
     */
    private fun refreshSurface(view: PlayerView) {
        val surface = view.videoSurfaceView ?: return
        surface.visibility = View.INVISIBLE
        surface.postDelayed({ surface.visibility = View.VISIBLE }, 50)
    }

    /**
     * Sizes the picture area for the mode: it may be bigger than the screen (the player clips
     * it), which is how Zoom and Wide zoom crop. Returns whether anything changed.
     */
    fun apply(view: PlayerView, player: Player): Boolean {
        val frame = view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return false
        val lp = frame.layoutParams as? FrameLayout.LayoutParams ?: return false
        val size = player.videoSize
        val sw = view.width
        val sh = view.height
        val mode = modeFor(view)
        var w = ViewGroup.LayoutParams.MATCH_PARENT
        var h = ViewGroup.LayoutParams.MATCH_PARENT
        var resize = AspectRatioFrameLayout.RESIZE_MODE_FIT

        if (mode != Mode.NORMAL && size.width > 0 && size.height > 0 && sw > 0 && sh > 0) {
            resize = AspectRatioFrameLayout.RESIZE_MODE_FILL
            val video = size.width * size.pixelWidthHeightRatio / size.height
            val screen = sw.toFloat() / sh
            when {
                // Already the screen's shape: assume a 4:3 picture with bars built in.
                abs(video / screen - 1f) < 0.03f -> {
                    val f = screen / INNER   // 1.33 on a 16:9 screen
                    when (mode) {
                        Mode.STRETCH -> { w = (sw * f).toInt(); h = sh }
                        Mode.ZOOM -> { w = (sw * f).toInt(); h = (sh * f).toInt() }
                        Mode.WIDE_ZOOM -> { w = (sw * f).toInt(); h = (sh * sqrt(f)).toInt() }
                        Mode.NORMAL -> {}
                    }
                }
                // Narrower than the screen (4:3): fill the width.
                video < screen -> {
                    val f = screen / video
                    when (mode) {
                        Mode.STRETCH -> { w = sw; h = sh }
                        Mode.ZOOM -> { w = sw; h = (sh * f).toInt() }
                        Mode.WIDE_ZOOM -> { w = sw; h = (sh * sqrt(f)).toInt() }
                        Mode.NORMAL -> {}
                    }
                }
                // Wider than the screen (2.39:1): fill the height.
                else -> {
                    val f = video / screen
                    when (mode) {
                        Mode.STRETCH -> { w = sw; h = sh }
                        Mode.ZOOM -> { w = (sw * f).toInt(); h = sh }
                        Mode.WIDE_ZOOM -> { w = (sw * sqrt(f)).toInt(); h = sh }
                        Mode.NORMAL -> {}
                    }
                }
            }
        }

        var changed = false
        if (view.resizeMode != resize) {
            view.resizeMode = resize
            changed = true
        }
        if (lp.width != w || lp.height != h || lp.gravity != Gravity.CENTER) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            frame.layoutParams = lp
            changed = true
        }
        return changed
    }
}
