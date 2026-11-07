package com.randyflix.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
        // Search only when Enter/Search is pressed; querying on every keystroke is too slow on the stick.
        query.setOnEditorActionListener { v, actionId, event ->
            val enterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER || event?.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            if (enterKey && event?.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enterKey) {
                grid.setItems(Library.search(v.text.toString()))
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                grid.view?.requestFocus()
                true
            } else false
        }
        query.requestFocus()
    }
}
