package com.randyflix.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.randyflix.R
import com.randyflix.data.ActionItem
import com.randyflix.data.Channel
import com.randyflix.data.ContinueItem
import com.randyflix.data.Library
import com.randyflix.data.LiveTv
import kotlinx.coroutines.launch

/** Home screen: Continue Watching, Live TV and Recently Added rows. Movies, TV Shows, Search and Settings are in the top bar. */
class MainFragment : BrowseSupportFragment() {
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val rowIds = ArrayList<String>()
    private var selected: Any? = null

    /** The ☰ button: a channel tile's menu, or a Continue Watching tile's. Returns true if handled. */
    fun onMenuKey(): Boolean = when (val item = selected) {
        is Channel -> { Nav.channelMenu(requireActivity(), item, LiveTv.LIST_FAVORITES); true }
        is ContinueItem -> { Nav.continueMenu(requireActivity(), item) { buildRows() }; true }
        else -> false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = false
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand)
        adapter = rowsAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Channel) Nav.watch(requireActivity(), item, LiveTv.LIST_FAVORITES) else Nav.open(requireActivity(), item)
        }
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ -> selected = item }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Library.version.collect { buildRows() } }
                launch { LiveTv.version.collect { buildRows() } }
            }
        }
    }

    /** No Leanback title or search button: the top bar replaces them, and Up from the first row goes there. */
    override fun onInflateTitleView(inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?): View? = null

    override fun onResume() {
        super.onResume()
        buildRows()
    }

    private fun buildRows() {
        val data = Library.data
        val rows = ArrayList<Triple<String, String, List<Any>>>()

        val cont = Library.continueWatching()
        if (cont.isNotEmpty()) rows += Triple("continue", "Continue Watching", cont)

        // Live TV: the favorites, then My channels and the channel guide.
        if (LiveTv.channels.isNotEmpty() || LiveTv.catalog.isNotEmpty()) {
            val mine = ActionItem(Nav.ACTION_CHANNELS, "My channels", "${LiveTv.all().size} channels", R.drawable.ic_tv)
            val guide = ActionItem(Nav.ACTION_GUIDE, "Channel guide", "${LiveTv.catalog.size} channels", R.drawable.ic_tv)
            rows += Triple("live", "Live TV", LiveTv.favorites() + mine + guide)
        }

        if (data.movies.isNotEmpty()) rows += Triple("recent_movies", "Recently Added Movies", Library.recentMovies())
        if (data.shows.isNotEmpty()) rows += Triple("recent_shows", "Recently Updated Shows", Library.recentShows())

        val ids = rows.map { it.first }
        if (ids == rowIds) {
            // Same rows: update contents in place so the focused card doesn't jump.
            rows.forEachIndexed { i, (_, _, items) ->
                val rowAdapter = (rowsAdapter.get(i) as ListRow).adapter as ArrayObjectAdapter
                if (rowAdapter.unmodifiableList<Any>() == items) {
                    rowAdapter.notifyArrayItemRangeChanged(0, rowAdapter.size())
                } else {
                    rowAdapter.clear()
                    rowAdapter.addAll(0, items)
                }
            }
        } else {
            rowsAdapter.clear()
            rows.forEachIndexed { i, (_, label, items) ->
                val rowAdapter = ArrayObjectAdapter(CardPresenter())
                rowAdapter.addAll(0, items)
                rowsAdapter.add(ListRow(HeaderItem(i.toLong(), label), rowAdapter))
            }
            rowIds.clear()
            rowIds.addAll(ids)
        }
    }
}
