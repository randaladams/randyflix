package com.randyflix.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.BrowseFrameLayout
import androidx.leanback.widget.TitleViewAdapter
import androidx.core.os.bundleOf
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import com.randyflix.R
import com.randyflix.data.Channel
import com.randyflix.data.Library
import com.randyflix.data.LiveTv

/** A poster grid: all movies, all shows, a collection, or search results. */
class GridFragment : VerticalGridSupportFragment() {
    private val items = ArrayObjectAdapter(CardPresenter())
    private var selected: Any? = null

    /** The ☰ button: favorites menu on a channel tile. Returns true if handled. */
    fun onMenuKey(): Boolean {
        val ch = selected as? Channel ?: return false
        Nav.channelMenu(requireActivity(), ch, LiveTv.LIST_ALL)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = arguments?.getString(ARG_MODE) ?: MODE_MOVIES
        title = arguments?.getString(ARG_TITLE) ?: getString(R.string.app_name)
        // Channel tiles are wide, so fewer per row.
        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_SMALL)
            .apply { numberOfColumns = if (mode == MODE_CHANNELS) 3 else 6 }
        adapter = items
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> Nav.open(requireActivity(), item) }
        setOnItemViewSelectedListener { _, item, _, _ -> selected = item }
        when {
            mode == MODE_CHANNELS -> setItems(LiveTv.all())
            mode == MODE_MOVIES -> setItems(Library.data.movies.sortedBy { it.sortTitle })
            mode == MODE_SHOWS -> setItems(Library.data.shows.sortedBy { it.sortTitle })
            mode.startsWith(MODE_COLLECTION) -> setItems(Library.collection(mode.removePrefix(MODE_COLLECTION)))
        }
    }

    /** No Leanback title: the screen's top bar shows where you are. */
    override fun onInflateTitleView(inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?): View =
        NoTitle(inflater.context)

    override fun onStart() {
        super.onStart()
        // Leanback sends Up to its (empty) title; let it go to the top bar instead.
        view?.findViewById<BrowseFrameLayout>(androidx.leanback.R.id.grid_frame)?.setOnFocusSearchListener(null)
    }

    override fun onResume() {
        super.onResume()
        if (arguments?.getString(ARG_MODE) == MODE_CHANNELS) {
            // Re-sort: "most watched" order may have changed after watching.
            val list = LiveTv.all()
            if (list != items.unmodifiableList<Any>()) setItems(list)
        } else if (items.size() > 0) {
            items.notifyArrayItemRangeChanged(0, items.size()) // refresh watched marks
        }
    }

    fun setItems(list: List<Any>) {
        items.clear()
        items.addAll(0, list)
    }

    companion object {
        const val ARG_MODE = "mode"
        const val ARG_TITLE = "title"
        const val MODE_MOVIES = "movies"
        const val MODE_SHOWS = "shows"
        const val MODE_SEARCH = "search"
        const val MODE_CHANNELS = "channels"
        const val MODE_COLLECTION = "collection:"

        fun newInstance(mode: String, title: String) = GridFragment().apply {
            arguments = bundleOf(ARG_MODE to mode, ARG_TITLE to title)
        }
    }
}

class GridActivity : TvActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_container)
        if (savedInstanceState == null) {
            val mode = intent.getStringExtra(GridFragment.ARG_MODE) ?: GridFragment.MODE_MOVIES
            val title = intent.getStringExtra(GridFragment.ARG_TITLE) ?: ""
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, GridFragment.newInstance(mode, title))
                .commit()
        }
        Screen.background(this)
        val mode = intent.getStringExtra(GridFragment.ARG_MODE) ?: GridFragment.MODE_MOVIES
        when {
            mode == GridFragment.MODE_MOVIES -> Screen.topBar(this, Tab.MOVIES)
            mode == GridFragment.MODE_SHOWS -> Screen.topBar(this, Tab.SHOWS)
            mode == GridFragment.MODE_CHANNELS -> Screen.topBar(this, Tab.LIVE, isTabRoot = false)
            else -> Screen.topBar(this, Tab.MOVIES, isTabRoot = false)   // a collection
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val grid = supportFragmentManager.findFragmentById(R.id.container) as? GridFragment
            if (grid?.onMenuKey() == true) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        fun intent(context: Context, mode: String, title: String): Intent =
            Intent(context, GridActivity::class.java)
                .putExtra(GridFragment.ARG_MODE, mode)
                .putExtra(GridFragment.ARG_TITLE, title)
    }
}

/** An empty stand-in for the Leanback grid's title (the grid needs one to exist). */
@android.annotation.SuppressLint("ViewConstructor")
private class NoTitle(context: Context) : View(context), TitleViewAdapter.Provider {
    private val adapter = object : TitleViewAdapter() {
        override fun getSearchAffordanceView(): View? = null
    }

    init {
        id = androidx.leanback.R.id.browse_title_group
        isFocusable = false
    }

    override fun getTitleViewAdapter(): TitleViewAdapter = adapter
}
