package com.randyflix.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import com.randyflix.R
import com.randyflix.data.Library

/** A poster grid: all movies, all shows, a collection, or search results. */
class GridFragment : VerticalGridSupportFragment() {
    private val items = ArrayObjectAdapter(CardPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = arguments?.getString(ARG_MODE) ?: MODE_MOVIES
        title = arguments?.getString(ARG_TITLE) ?: getString(R.string.app_name)
        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply { numberOfColumns = 6 }
        adapter = items
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> Nav.open(requireActivity(), item) }
        when {
            mode == MODE_MOVIES -> setItems(Library.data.movies.sortedBy { it.sortTitle })
            mode == MODE_SHOWS -> setItems(Library.data.shows.sortedBy { it.sortTitle })
            mode.startsWith(MODE_COLLECTION) -> setItems(Library.collection(mode.removePrefix(MODE_COLLECTION)))
        }
    }

    override fun onResume() {
        super.onResume()
        if (items.size() > 0) items.notifyArrayItemRangeChanged(0, items.size()) // refresh watched marks
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
        const val MODE_COLLECTION = "collection:"

        fun newInstance(mode: String, title: String) = GridFragment().apply {
            arguments = bundleOf(ARG_MODE to mode, ARG_TITLE to title)
        }
    }
}

class GridActivity : FragmentActivity() {
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
    }

    companion object {
        fun intent(context: Context, mode: String, title: String): Intent =
            Intent(context, GridActivity::class.java)
                .putExtra(GridFragment.ARG_MODE, mode)
                .putExtra(GridFragment.ARG_TITLE, title)
    }
}
