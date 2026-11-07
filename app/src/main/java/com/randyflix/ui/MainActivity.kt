package com.randyflix.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.randyflix.R
import com.randyflix.Settings
import com.randyflix.data.Library
import com.randyflix.data.ScanState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private var shown: ScanState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, MainFragment()).commit()
        }
        if (!Settings.isConfigured) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            Library.scanIfStale()
        }

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

    private fun show(view: TextView, text: String) {
        view.text = text
        view.visibility = View.VISIBLE
    }
}
