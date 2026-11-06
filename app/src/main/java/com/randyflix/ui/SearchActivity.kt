package com.randyflix.ui

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.randyflix.R
import com.randyflix.data.Library

class SearchActivity : FragmentActivity() {
    private lateinit var grid: GridFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        val existing = supportFragmentManager.findFragmentById(R.id.container) as? GridFragment
        grid = existing ?: GridFragment.newInstance(GridFragment.MODE_SEARCH, "Results").also {
            supportFragmentManager.beginTransaction().replace(R.id.container, it).commitNow()
        }

        val query = findViewById<EditText>(R.id.query)
        query.doAfterTextChanged { grid.setItems(Library.search(it?.toString().orEmpty())) }
        query.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                grid.view?.requestFocus()
                true
            } else false
        }
        query.requestFocus()
    }
}
