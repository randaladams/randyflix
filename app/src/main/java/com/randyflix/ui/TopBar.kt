package com.randyflix.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
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
                isClickable = true
                isActivated = tab == current
                if (tab == current) setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { go(tab) }
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

    /** Moves the highlight to this screen's tab. */
    fun focusCurrent() {
        tabs[current]?.requestFocus()
    }

    /** Coming from the screen below, only the current tab can take focus. */
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (hasFocus()) super.addFocusables(views, direction, focusableMode)
        else tabs[current]?.takeIf { it.isShown }?.let { views += it }
    }

    private fun go(tab: Tab) {
        if (tab == current && isTabRoot) return
        if (activity is MainActivity) {
            MainActivity.openTab(activity, tab)
        } else {
            // Every tab sits directly on top of Home, so Back from any of them goes Home.
            activity.startActivity(
                Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_TAB, tab.name)
            )
            activity.overridePendingTransition(0, 0)
        }
    }

    companion object {
        /** Height of the bar; screens keep their content below this. */
        const val HEIGHT_DP = 72

        fun add(activity: FragmentActivity, current: Tab, isTabRoot: Boolean): TopBar {
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val bar = TopBar(activity, current, isTabRoot)
            root.addView(bar, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Format.dp(activity, HEIGHT_DP), Gravity.TOP,
            ))
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
