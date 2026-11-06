package com.randyflix.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.randyflix.R
import com.randyflix.Settings
import com.randyflix.data.Library
import com.randyflix.data.ScanState
import com.randyflix.data.Scanner
import com.randyflix.smb.Smb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : FragmentActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        status = findViewById(R.id.status)

        val host = findViewById<EditText>(R.id.host)
        val movies = findViewById<EditText>(R.id.movies)
        val tv = findViewById<EditText>(R.id.tv)
        val user = findViewById<EditText>(R.id.user)
        val password = findViewById<EditText>(R.id.password)
        val domain = findViewById<EditText>(R.id.domain)

        host.setText(Settings.host)
        movies.setText(Settings.moviesPath)
        tv.setText(Settings.tvPath)
        user.setText(Settings.user)
        password.setText(Settings.password)
        domain.setText(Settings.domain)

        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.version).text = "RandyFlix $version"

        findViewById<Button>(R.id.save).setOnClickListener {
            val pathsChanged = host.text.toString().trim() != Settings.host ||
                movies.text.toString().trim('/', '\\', ' ') != Settings.moviesPath ||
                tv.text.toString().trim('/', '\\', ' ') != Settings.tvPath
            Settings.host = host.text.toString()
            Settings.moviesPath = movies.text.toString()
            Settings.tvPath = tv.text.toString()
            Settings.user = user.text.toString()
            Settings.password = password.text.toString()
            Settings.domain = domain.text.toString()
            testConnection(scanAfter = true, full = pathsChanged)
        }
        findViewById<Button>(R.id.refresh).setOnClickListener { Library.scan(false) }
        findViewById<Button>(R.id.full).setOnClickListener { Library.scan(true) }
        findViewById<Button>(R.id.clear_images).setOnClickListener {
            ImageLoader.clearCache(this)
            status.text = "Artwork cache cleared."
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Library.state.collect { s ->
                    when (s) {
                        is ScanState.Running -> status.text = "Scanning… ${s.message}"
                        is ScanState.Done -> status.text = "Library updated: ${s.message}"
                        is ScanState.Failed -> status.text = "Problem:\n${s.message}"
                        ScanState.Idle -> {}
                    }
                }
            }
        }
        host.requestFocus()
    }

    private fun testConnection(scanAfter: Boolean, full: Boolean) {
        status.text = "Connecting to ${Settings.host}…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Smb.reset()
                    val lines = ArrayList<String>()
                    for ((label, path) in listOf("Movies" to Settings.moviesPath, "TV" to Settings.tvPath)) {
                        if (path.isBlank()) continue
                        lines += try {
                            val n = Smb.list(path).count { it.isDir }
                            "✓ $label ($path): $n folders"
                        } catch (e: Exception) {
                            "✗ $label ($path): ${Scanner.describe(e)}"
                        }
                    }
                    lines.joinToString("\n")
                } catch (e: Exception) {
                    "✗ Could not connect: ${Scanner.describe(e)}"
                }
            }
            status.text = result
            if (scanAfter && !result.contains("✗")) Library.scan(full)
        }
    }
}
