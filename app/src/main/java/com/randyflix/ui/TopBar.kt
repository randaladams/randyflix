package com.randyflix.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.randyflix.R

/** The places in the top bar, left to right. */
enum class Tab(val label: String) {
    HOME("Home"), MOVIES("Movies"), SHOWS("TV Shows"), LIVE("Live TV"), SEARCH("Search"), SETTINGS("Settings")
}

/**
 * The bar across the top of every menu screen: the tabs on the left (the current one marked)
 * and the time, large, on the right. It stays put while the screen scrolls under it.
 *
 * Up from the top of a screen lands on the current tab (not whichever tab is nearest), Left/Right
 * move between tabs without wrapping off the bar, and Down goes back into the screen.
 */
@SuppressLint("ViewConstructor")
class TopBar(
    private val activity: FragmentActivity,
    private val current: Tab,
    /** True when this screen *is* the tab (e.g. the Movies grid), so selecting it does nothing. */
    private val isTabRoot: Boolean,
) : LinearLayout(activity) {
    val clock = ClockView(activity, withDate = false)
    private val tabs = LinkedHashMap<Tab, TextView>()
    private var pressed: Tab? = null

    /** Opens whichever tab is highlighted, if it's not this screen's. */
    private val openFocused = Runnable {
        val tab = tabs.entries.firstOrNull { it.value.isFocused }?.key
        if (tab != null && tab != current) go(tab, focusBar = true)
    }

    private fun isConfirm(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
        keyCode == KeyEvent.KEYCODE_BUTTON_A

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.topbar_bg)
        val side = Format.dp(activity, 40)
        setPadding(side, 0, side, 0)
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        for (tab in Tab.values()) {
            val view = TextView(activity).apply {
                text = tab.label
                textSize = 18f
                setTextColor(ContextCompat.getColorStateList(activity, R.color.tab_text))
                setBackgroundResource(R.drawable.tab_bg)
                val h = Format.dp(activity, 16)
                val v = Format.dp(activity, 6)
                setPadding(h, v, h, v)
                isFocusable = true
                isActivated = tab == current
                if (tab == current) setTypeface(typeface, Typeface.BOLD)
                // Moving onto another tab opens it after a short pause (so holding Right can skip
                // past tabs). The current tab is where you land coming up from the screen, so
                // landing there never opens anything.
                setOnFocusChangeListener { _, has ->
                    removeCallbacks(openFocused)
                    if (has && tab != current) postDelayed(openFocused, OPEN_DELAY_MS)
                }
                // Select opens a tab straight away (only a whole press, down and up, on this tab).
                setOnKeyListener { _, keyCode, event ->
                    if (!isConfirm(keyCode)) return@setOnKeyListener false
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) pressed = tab
                        KeyEvent.ACTION_UP -> {
                            if (pressed == tab && !event.isCanceled) go(tab)
                            pressed = null
                        }
                    }
                    true
                }
            }
            tabs[tab] = view
            addView(view, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = Format.dp(activity, 6) })
        }
        // Left/Right stop at the ends instead of jumping into the screen below.
        val views = tabs.values.toList()
        views.forEach { it.id = View.generateViewId() }
        views.first().nextFocusLeftId = views.first().id
        views.last().nextFocusRightId = views.last().id

        addView(View(activity), LayoutParams(0, 1, 1f))   // pushes the clock to the right
        clock.apply {
            background = null
            textSize = 34f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(0, 0, 0, 0)
        }
        addView(clock, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Down from any tab goes straight to [view] (e.g. the search field), not the nearest thing below. */
    fun downTo(view: View) {
        tabs.values.forEach { it.nextFocusDownId = view.id }
    }

    /** Moves the highlight to this screen's tab. */
    fun focusCurrent() {
        tabs[current]?.requestFocus()
    }

    /** Coming from the screen below, only the current tab can take focus. */
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (hasFocus()) super.addFocusables(views, direction, focusableMode)
        else tabs[current]?.takeIf { it.isShown }?.let { views += it }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(openFocused)
        super.onDetachedFromWindow()
    }

    /** [focusBar]: the new screen starts with the highlight on its tab, so you can keep moving along the bar. */
    private fun go(tab: Tab, focusBar: Boolean = false) {
        if (tab == current && isTabRoot) return
        if (activity is MainActivity) {
            MainActivity.openTab(activity, tab, focusBar)
        } else {
            // Every tab sits directly on top of Home, so Back from any of them goes Home.
            activity.startActivity(
                Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_TAB, tab.name)
                    .putExtra(EXTRA_FOCUS_BAR, focusBar)
            )
            activity.overridePendingTransition(0, 0)
        }
    }

    companion object {
        /** Height of the bar; screens keep their content below this. */
        const val HEIGHT_DP = 72
        const val EXTRA_FOCUS_BAR = "focus_bar"
        private const val OPEN_DELAY_MS = 450L

        fun add(activity: FragmentActivity, current: Tab, isTabRoot: Boolean): TopBar {
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val bar = TopBar(activity, current, isTabRoot)
            // On a tab's own screen (Movies, TV Shows, Live TV, Search, Settings), Back goes up to
            // the bar first. Home has its own version (Back there asks to exit).
            if (isTabRoot && activity is TvActivity && activity !is MainActivity) activity.backToTopBar(bar)
            root.addView(bar, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Format.dp(activity, HEIGHT_DP), Gravity.TOP,
            ))
            // Opened by moving along the bar: keep the highlight on the bar. A short wait lets
            // the screen finish putting its own first highlight in place.
            if (activity.intent.getBooleanExtra(EXTRA_FOCUS_BAR, false)) {
                activity.intent.removeExtra(EXTRA_FOCUS_BAR)
                bar.postDelayed({ bar.focusCurrent() }, 150)
            }
            return bar
        }

        fun intentFor(context: Context, tab: Tab): Intent? = when (tab) {
            Tab.HOME -> null
            Tab.MOVIES -> GridActivity.intent(context, GridFragment.MODE_MOVIES, "Movies")
            Tab.SHOWS -> GridActivity.intent(context, GridFragment.MODE_SHOWS, "TV Shows")
            Tab.LIVE -> Intent(context, GuideActivity::class.java)
            Tab.SEARCH -> Intent(context, SearchActivity::class.java)
            Tab.SETTINGS -> Intent(context, SettingsActivity::class.java)
        }
    }
}
