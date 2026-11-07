package com.randyflix.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.fragment.app.FragmentActivity
import com.randyflix.Settings
import com.randyflix.data.Backgrounds
import kotlin.random.Random

/**
 * The screen saver: after [Settings.screenSaverMinutes] without a button press, a slideshow of
 * the background pictures with the clock drifting around the screen. Any button wakes it, and
 * that press does nothing else.
 *
 * [allowed] says whether it may start right now (the player allows it only while paused).
 * With [holdScreenOn], the screen is kept on so the Fire TV's own screen saver doesn't start
 * first; after [MAX_RUN_MS] of slideshow the hold is released so the TV can still go to sleep.
 */
class ScreenSaver(
    private val activity: FragmentActivity,
    private val holdScreenOn: Boolean,
    private val allowed: () -> Boolean = { true },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val root = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        isClickable = true
    }
    private val images = arrayOf(newImage(), newImage())
    private var front = 0
    private val clock = ClockView(activity).apply { setBackgroundColor(Color.TRANSPARENT); textSize = 22f }
    private var lastInput = SystemClock.uptimeMillis()
    private var shownAt = 0L
    private var pictureIndex = 0
    private var swallowUp = -1
    private var resumed = false

    val isShowing: Boolean get() = root.visibility == View.VISIBLE

    init {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        images.forEach { root.addView(it, FrameLayout.LayoutParams(match, match)) }
        root.addView(clock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        activity.findViewById<ViewGroup>(android.R.id.content).addView(root, FrameLayout.LayoutParams(match, match))
    }

    private fun newImage() = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
    }

    /** Checks every few seconds whether it's time to start. */
    private val watch = object : Runnable {
        override fun run() {
            val minutes = Settings.screenSaverMinutes
            if (!isShowing && minutes > 0 && allowed() && SystemClock.uptimeMillis() - lastInput >= minutes * 60_000L) show()
            if (isShowing && holdScreenOn && SystemClock.uptimeMillis() - shownAt > MAX_RUN_MS) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            handler.postDelayed(this, CHECK_MS)
        }
    }

    private val nextPicture = object : Runnable {
        override fun run() {
            showPicture(pictureIndex + 1)
            handler.postDelayed(this, PICTURE_MS)
        }
    }

    private val moveClock = object : Runnable {
        override fun run() {
            placeClock()
            handler.postDelayed(this, CLOCK_MOVE_MS)
        }
    }

    fun onResume() {
        resumed = true
        lastInput = SystemClock.uptimeMillis()
        applyScreenOn()
        handler.removeCallbacks(watch)
        handler.postDelayed(watch, CHECK_MS)
    }

    fun onPause() {
        resumed = false
        handler.removeCallbacks(watch)
        hide()
    }

    /** Something happened that counts as activity (e.g. the player was paused): restart the countdown. */
    fun poke() {
        lastInput = SystemClock.uptimeMillis()
    }

    /** The setting changed. */
    fun settingsChanged() {
        poke()
        applyScreenOn()
    }

    private fun applyScreenOn() {
        if (!holdScreenOn) return
        if (Settings.screenSaverMinutes > 0) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Call first thing in the activity's dispatchKeyEvent. Returns true if the key was used to
     * wake the screen saver (and must go no further).
     */
    fun onKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowUp) {
            swallowUp = -1
            return true
        }
        poke()
        if (!isShowing) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            swallowUp = event.keyCode
            hide()
            applyScreenOn()
        }
        return true
    }

    private fun show() {
        val list = Backgrounds.all()
        shownAt = SystemClock.uptimeMillis()
        root.visibility = View.VISIBLE
        root.bringToFront()
        images.forEach { it.alpha = 0f }
        clock.update()
        root.post { placeClock() }
        // Start from today's menu background and step through the rest.
        pictureIndex = (Backgrounds.today.value?.let { list.indexOf(it) } ?: 0).coerceAtLeast(0) - 1
        if (list.isNotEmpty()) {
            showPicture(pictureIndex + 1)
            handler.postDelayed(nextPicture, PICTURE_MS)
        }
        handler.postDelayed(moveClock, CLOCK_MOVE_MS)
    }

    private fun hide() {
        handler.removeCallbacks(nextPicture)
        handler.removeCallbacks(moveClock)
        if (!isShowing) return
        root.visibility = View.GONE
        images.forEach { ImageLoader.cancel(it); it.animate().cancel(); it.setImageDrawable(null); it.alpha = 0f }
        lastInput = SystemClock.uptimeMillis()
    }

    /** Loads the next picture behind the current one, then cross-fades to it. */
    private fun showPicture(index: Int) {
        val list = Backgrounds.all()
        if (list.isEmpty()) return
        pictureIndex = Math.floorMod(index, list.size)
        val back = images[1 - front]
        val old = images[front]
        ImageLoader.load(back, list[pictureIndex], 1280, 0)
        back.bringToFront()
        clock.bringToFront()
        back.animate().cancel()
        back.alpha = 0f
        // Give the picture a moment to load from the share before fading it in.
        back.animate().alpha(1f).setStartDelay(800).setDuration(1500).withEndAction { old.alpha = 0f }.start()
        front = 1 - front
    }

    /** Somewhere new each minute, so nothing sits in one place on the TV. */
    private fun placeClock() {
        val w = root.width - clock.width
        val h = root.height - clock.height
        if (w <= 0 || h <= 0) return
        val margin = Format.dp(activity, 32)
        val lp = clock.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = margin + Random.nextInt(maxOf(1, w - 2 * margin))
        lp.topMargin = margin + Random.nextInt(maxOf(1, h - 2 * margin))
        clock.layoutParams = lp
    }

    companion object {
        private const val CHECK_MS = 5_000L
        private const val PICTURE_MS = 20_000L
        private const val CLOCK_MOVE_MS = 60_000L
        private const val MAX_RUN_MS = 2 * 60 * 60_000L   // then let the Fire TV sleep as usual
    }
}

/**
 * Base for the menu screens: adds the screen saver and wakes it on any button.
 * Subclasses that override dispatchKeyEvent must call [saverKey] first.
 */
abstract class TvActivity : FragmentActivity() {
    protected lateinit var screenSaver: ScreenSaver

    /** Whether the screen saver may start right now. */
    protected open fun saverAllowed(): Boolean = true

    /** Menu screens hold the screen on so this saver, not the Fire TV's, comes up. */
    protected open val saverHoldsScreenOn: Boolean = true

    /** The key-click setting this screen was built with; it's rebuilt if that changes. */
    private var clicksApplied = true

    override fun onCreate(savedInstanceState: Bundle?) {
        clicksApplied = KeyClicks.apply(this)
        super.onCreate(savedInstanceState)
    }

    /** The screen where the setting was just changed updates itself in place instead. */
    protected fun clicksChangedHere() {
        clicksApplied = Settings.keyClicks
        KeyClicks.applyTo(window.decorView, clicksApplied)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!isFinishing) screenSaver = ScreenSaver(this, saverHoldsScreenOn) { saverAllowed() }
    }

    override fun onResume() {
        super.onResume()
        if (clicksApplied != Settings.keyClicks) {
            recreate()   // key clicks changed in Settings while this screen was underneath
            return
        }
        if (::screenSaver.isInitialized) screenSaver.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::screenSaver.isInitialized) screenSaver.onPause()
    }

    protected fun screenSaverShowing(): Boolean = ::screenSaver.isInitialized && screenSaver.isShowing

    protected fun saverKey(event: KeyEvent): Boolean = ::screenSaver.isInitialized && screenSaver.onKey(event)

    /** Restart the countdown (e.g. playback was just paused). */
    protected fun saverPoke() {
        if (::screenSaver.isInitialized) screenSaver.poke()
    }

    /** The screen saver setting changed. */
    protected fun saverSettingsChanged() {
        if (::screenSaver.isInitialized) screenSaver.settingsChanged()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        saverKey(event) || KeyClicks.dispatch(this, event) { super.dispatchKeyEvent(it) }
}
