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
import kotlin.math.sqrt

/**
 * Picture size (☰ → Picture), like a TV's aspect button. The choice is remembered, the way a
 * TV remembers it, and applies to movies, shows and live TV.
 */
@OptIn(UnstableApi::class)
object PictureSize {
    enum class Mode(val label: String) {
        NORMAL("Normal"),
        STRETCH("Stretch 16:9"),
        WIDE_ZOOM("Wide zoom"),
        ZOOM("Zoom"),
    }

    val current: Mode get() = Mode.values().firstOrNull { it.name == Settings.pictureSize } ?: Mode.NORMAL

    /**
     * Keeps [view] showing [player] in the remembered mode: now, when the video's shape changes
     * (a new episode or channel) and when the screen is laid out.
     */
    fun attach(view: PlayerView, player: Player) {
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = apply(view, player)
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

    private val layoutWatchers = java.util.WeakHashMap<PlayerView, View.OnLayoutChangeListener>()

    /** The ☰ items: one line showing the mode, opening the list of modes. */
    fun menuItems(view: PlayerView, player: Player, menu: PlayerMenu): List<PlayerMenu.Item> = listOf(
        PlayerMenu.Item("Picture: ${current.label}…", PlayerMenu.After.STAY) {
            menu.push {
                "Picture" to Mode.values().map { m ->
                    PlayerMenu.Item((if (m == current) "✓  " else "     ") + m.label, PlayerMenu.After.BACK) {
                        Settings.pictureSize = m.name
                        apply(view, player)
                    }
                }
            }
        },
    )

    fun apply(view: PlayerView, player: Player) {
        val frame = view.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        var width = ViewGroup.LayoutParams.MATCH_PARENT
        var height = ViewGroup.LayoutParams.MATCH_PARENT
        when (current) {
            // Whole picture, black bars where the shapes differ.
            Mode.NORMAL -> view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            // Fills the screen by stretching (a 4:3 picture gets wider).
            Mode.STRETCH -> view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            // Enlarged evenly until the bars are gone; the overflow is cut off.
            Mode.ZOOM -> view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            // Half of each: the picture is stretched part of the way and enlarged the rest, so it
            // fills the screen with half the distortion of Stretch and a smaller crop than Zoom.
            Mode.WIDE_ZOOM -> {
                val size = player.videoSize
                val w = view.width
                val h = view.height
                if (size.width <= 0 || size.height <= 0 || w <= 0 || h <= 0) {
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                } else {
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                    val video = size.width * size.pixelWidthHeightRatio / size.height
                    val screen = w.toFloat() / h
                    if (video < screen) {
                        // Narrower than the screen (e.g. 4:3): full width, top and bottom trimmed.
                        width = w
                        height = (h * sqrt(screen / video)).toInt()
                    } else {
                        // Wider than the screen (e.g. 2.39:1): full height, sides trimmed.
                        height = h
                        width = (w * sqrt(video / screen)).toInt()
                    }
                }
            }
        }
        // The picture area may be bigger than the screen (Wide zoom); the player clips it.
        val lp = frame.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != width || lp.height != height || lp.gravity != Gravity.CENTER) {
            lp.width = width
            lp.height = height
            lp.gravity = Gravity.CENTER
            frame.layoutParams = lp
        }
    }
}
