package com.randyflix.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
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

/** Shared screen furniture: the clock and the daily background picture. */
object Screen {
    /** Adds the date/time clock at the top centre of the screen. */
    fun clock(activity: FragmentActivity): ClockView {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val clock = ClockView(activity)
        root.addView(clock, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = Format.dp(activity, 18) })
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

/** "Sat, Nov 7 · 7:42 PM", updated on the minute. 12 or 24 hour per Settings. */
@SuppressLint("AppCompatCustomView")
class ClockView(context: Context) : TextView(context) {
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
        val pattern = if (Settings.clock24) "EEE, MMM d  ·  HH:mm" else "EEE, MMM d  ·  h:mm a"
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
