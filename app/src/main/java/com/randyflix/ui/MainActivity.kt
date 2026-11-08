package com.randyflix.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.randyflix.R
import com.randyflix.Settings
import com.randyflix.data.Library
import com.randyflix.data.LiveTv
import com.randyflix.data.Backgrounds
import com.randyflix.data.ScanState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : TvActivity() {
    private var shown: ScanState? = null
    private lateinit var topBar: TopBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, MainFragment()).commit()
        }
        if (!Settings.isConfigured) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            LiveTv.refresh()
            Backgrounds.refresh()
            Library.scanIfStale()
        }

        Screen.background(this)
        topBar = Screen.topBar(this, Tab.HOME)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        // Scan progress goes in a small line at the bottom so it doesn't crowd the home screen.
        val status = findViewById<TextView>(R.id.scan_status)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Library.state.collectLatest { s ->
                    // Coming back to the home screen re-delivers the last result; don't flash it again.
                    if (s === shown && s !is ScanState.Running) {
                        status.visibility = View.GONE
                        return@collectLatest
                    }
                    shown = s
                    when (s) {
                        is ScanState.Running -> show(status, "Updating library · ${s.message}")
                        is ScanState.Done -> {
                            show(status, "Library updated · ${s.message}")
                            delay(4_000)
                            status.visibility = View.GONE
                        }
                        is ScanState.Failed -> {
                            show(status, "⚠ ${s.message.lineSequence().first()}")
                            delay(15_000)
                            status.visibility = View.GONE
                        }
                        ScanState.Idle -> status.visibility = View.GONE
                    }
                }
            }
        }
    }

    /** A top-bar tab chosen on another screen comes back through Home, so Back from it returns here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tab = intent.getStringExtra(EXTRA_TAB)?.let { name -> Tab.values().firstOrNull { it.name == name } }
        val focusBar = intent.getBooleanExtra(TopBar.EXTRA_FOCUS_BAR, false)
        if (tab != null && tab != Tab.HOME) openTab(this, tab, focusBar)
        else if (tab == Tab.HOME && focusBar) topBar.postDelayed({ topBar.focusCurrent() }, 150)
    }

    /** "Exit RandyFlix?" with Yes selected; pressing Back again also exits. */
    private fun confirmExit() {
        val dialog = AlertDialog.Builder(this)
            .setMessage("Exit RandyFlix?")
            .setPositiveButton("Yes") { _, _ -> finish() }
            .setNegativeButton("No", null)
            .setOnKeyListener { d, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) {
                    d.dismiss()
                    finish()
                }
                true
            }
            .create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
        dialog.show()
    }

    /** Whether the Back press now ending started on this screen (not on the one just closed). */
    private var backDown = false

    /**
     * Back from the rows goes up to the top bar (and scrolls back to the first row); Back on the
     * top bar asks "Exit RandyFlix?", where Back once more exits.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !screenSaverShowing()) {
            saverPoke()
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) backDown = true
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                if (backDown && !event.isCanceled) {
                    if (topBar.hasFocus()) confirmExit()
                    else {
                        (supportFragmentManager.findFragmentById(R.id.container) as? MainFragment)?.scrollToTop()
                        topBar.focusCurrent()
                    }
                }
                backDown = false
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val home = supportFragmentManager.findFragmentById(R.id.container) as? MainFragment
            if (home?.onMenuKey() == true) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun show(view: TextView, text: String) {
        view.text = text
        view.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_TAB = "tab"

        fun openTab(activity: MainActivity, tab: Tab, focusBar: Boolean = false) {
            TopBar.intentFor(activity, tab)?.let {
                activity.startActivity(it.putExtra(TopBar.EXTRA_FOCUS_BAR, focusBar))
                activity.overridePendingTransition(0, 0)
            }
        }
    }
}
