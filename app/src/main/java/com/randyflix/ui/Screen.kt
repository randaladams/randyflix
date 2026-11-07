package com.randyflix.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.randyflix.R
import com.randyflix.Settings
import com.randyflix.data.Backgrounds
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared screen furniture: the top bar, the pause-screen time and the daily background picture. */
object Screen {
    /** Adds the top bar (tabs and time). [isTabRoot]: this screen is [current] itself, not a page inside it. */
    fun topBar(activity: FragmentActivity, current: Tab, isTabRoot: Boolean = true): TopBar =
        TopBar.add(activity, current, isTabRoot)

    /** The large time in the upper right, with no bar behind it (the pause screen). */
    fun bigClock(activity: FragmentActivity): ClockView {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val clock = ClockView(activity, withDate = false).apply {
            background = null
            textSize = 34f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        root.addView(clock, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            topMargin = Format.dp(activity, 14)
            marginEnd = Format.dp(activity, 40)
        })
        return clock
    }

    /** Puts today's background picture (dimmed) behind the screen's content. */
    fun background(activity: FragmentActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val scrim = View(activity).apply {
            setBackgroundColor(Color.argb(190, 10, 12, 16))
            visibility = View.GONE
        }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(image, 0, FrameLayout.LayoutParams(match, match))
        root.addView(scrim, 1, FrameLayout.LayoutParams(match, match))
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Backgrounds.check()
                Backgrounds.today.collect { path ->
                    if (path == null) {
                        ImageLoader.cancel(image)
                        image.setImageDrawable(null)
                        scrim.visibility = View.GONE
                    } else {
                        ImageLoader.load(image, path, 1280, 0)
                        scrim.visibility = View.VISIBLE
                    }
                }
            }
        }
    }
}

/**
 * "Sat, Nov 7 · 7:42 PM" (the screen saver), or just "7:42 PM" with [withDate] false (the top
 * bar and pause screen). Updated on the minute; 12 or 24 hour per Settings.
 */
@SuppressLint("AppCompatCustomView")
class ClockView(context: Context, private val withDate: Boolean = true) : TextView(context) {
    private val tick = object : Runnable {
        override fun run() {
            update()
            val now = System.currentTimeMillis()
            postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }

    init {
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        textSize = 16f
        setBackgroundResource(R.drawable.status_bg)
        val h = Format.dp(context, 14)
        val v = Format.dp(context, 5)
        setPadding(h, v, h, v)
        isFocusable = false
        isSingleLine = true
    }

    fun update() {
        val time = if (Settings.clock24) "HH:mm" else "h:mm a"
        val pattern = if (withDate) "EEE, MMM d  ·  $time" else time
        text = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        tick.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}

/** Key clicks (Settings → Sound): views made after this inherit the theme's sound setting. */
object KeyClicks {
    /** Call before super.onCreate. Returns the setting it applied. */
    fun apply(activity: android.app.Activity): Boolean {
        val on = Settings.keyClicks
        if (!on) activity.theme.applyStyle(R.style.QuietOverlay, true)
        return on
    }

    /** Switches every view already on screen, for the screen where the setting was changed. */
    fun applyTo(view: View, on: Boolean) {
        view.isSoundEffectsEnabled = on
        if (view is ViewGroup) for (i in 0 until view.childCount) applyTo(view.getChildAt(i), on)
    }
}
