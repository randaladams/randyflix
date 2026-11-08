package com.randyflix.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.randyflix.R
import com.randyflix.data.Library

class SearchActivity : TvActivity() {
    private lateinit var grid: GridFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        val existing = supportFragmentManager.findFragmentById(R.id.container) as? GridFragment
        grid = existing ?: GridFragment.newInstance(GridFragment.MODE_SEARCH, "Results").also {
            supportFragmentManager.beginTransaction().replace(R.id.container, it).commitNow()
        }

        Screen.background(this)
        val bar = Screen.topBar(this, Tab.SEARCH)

        val query = findViewById<EditText>(R.id.query)
        bar.downTo(query)
        noResults(true)
        // Landing on the field opens the keyboard (no need to press Select first).
        query.setOnFocusChangeListener { v, has ->
            if (has) v.postDelayed({
                if (v.hasFocus()) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
            }, 200)
        }
        // Search only when Enter/Search is pressed; querying on every keystroke is too slow on the stick.
        query.setOnEditorActionListener { v, actionId, event ->
            val enterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER || event?.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            if (enterKey && event?.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enterKey) {
                val results = Library.search(v.text.toString())
                grid.setItems(results)
                noResults(results.isEmpty())
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                if (results.isNotEmpty()) grid.view?.requestFocus()
                true
            } else false
        }
        query.requestFocus()
    }

    /**
     * With no results the (empty) grid could take the highlight with nothing to show for it, and
     * the field above would seem unreachable. It only takes the highlight when it has results.
     */
    private fun noResults(empty: Boolean) {
        val root = grid.view as? ViewGroup ?: return
        root.descendantFocusability = if (empty) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        root.findViewById<View>(androidx.leanback.R.id.grid_frame)?.isFocusable = !empty
    }
}
