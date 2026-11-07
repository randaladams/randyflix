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
import com.randyflix.data.LiveTv
import com.randyflix.data.Backgrounds
import com.randyflix.data.M3u
import com.randyflix.data.ScanState
import com.randyflix.data.Scanner
import com.randyflix.smb.Smb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : FragmentActivity() {
    private lateinit var status: TextView
    private lateinit var clock: ClockView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        status = findViewById(R.id.status)
        Screen.background(this)
        clock = Screen.clock(this)

        val host = findViewById<EditText>(R.id.host)
        val movies = findViewById<EditText>(R.id.movies)
        val tv = findViewById<EditText>(R.id.tv)
        val appFolder = findViewById<EditText>(R.id.app_folder)

        host.setText(Settings.host)
        movies.setText(Settings.moviesPath)
        tv.setText(Settings.tvPath)
        appFolder.setText(Settings.appFolder)

        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.version).text = "RandyFlix $version"

        findViewById<Button>(R.id.save).setOnClickListener {
            // The app folder (playlist, backgrounds) isn't part of the library index, so changing it needs no full rescan.
            val pathsChanged = host.text.toString().trim() != Settings.host ||
                movies.text.toString().trim('/', '\\', ' ') != Settings.moviesPath ||
                tv.text.toString().trim('/', '\\', ' ') != Settings.tvPath
            Settings.host = host.text.toString()
            Settings.moviesPath = movies.text.toString()
            Settings.tvPath = tv.text.toString()
            Settings.appFolder = appFolder.text.toString()
            testConnection(scanAfter = true, full = pathsChanged)
        }
        findViewById<Button>(R.id.refresh).setOnClickListener { Library.scan(false) }
        findViewById<Button>(R.id.full).setOnClickListener { Library.scan(true) }
        findViewById<Button>(R.id.clear_images).setOnClickListener {
            ImageLoader.clearCache(this)
            status.text = "Artwork cache cleared."
        }
        val intro = findViewById<Button>(R.id.intro)
        fun showIntroState() {
            intro.text = if (Settings.showIntro) "Intro on startup: On" else "Intro on startup: Off"
        }
        showIntroState()
        intro.setOnClickListener {
            Settings.showIntro = !Settings.showIntro
            showIntroState()
        }
        val autoplay = findViewById<Button>(R.id.autoplay)
        fun showAutoplayState() {
            autoplay.text = if (Settings.autoPlayNext) "Auto-play next episode: On" else "Auto-play next episode: Off"
        }
        showAutoplayState()
        autoplay.setOnClickListener {
            Settings.autoPlayNext = !Settings.autoPlayNext
            showAutoplayState()
        }
        val clockButton = findViewById<Button>(R.id.clock)
        fun showClockState() {
            clockButton.text = if (Settings.clock24) "Clock: 24-hour" else "Clock: 12-hour"
            clock.update()
        }
        showClockState()
        clockButton.setOnClickListener {
            Settings.clock24 = !Settings.clock24
            showClockState()
        }
        val order = findViewById<Button>(R.id.channel_order)
        fun showOrderState() {
            order.text = if (Settings.channelsByUse) "All channels order: Most watched" else "All channels order: By name"
        }
        showOrderState()
        order.setOnClickListener {
            Settings.channelsByUse = !Settings.channelsByUse
            showOrderState()
        }
        findViewById<Button>(R.id.reset_favorites).setOnClickListener {
            if (LiveTv.hasCustomFavorites) {
                LiveTv.resetFavorites()
                status.text = "Live TV favorites reset to the playlist's Favorites section."
            } else {
                status.text = "Live TV favorites already come from the playlist."
            }
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
                    // A missing playlist only turns Live TV off, so it doesn't stop the library scan.
                    Settings.livePlaylist.takeIf { it.isNotBlank() }?.let { path ->
                        lines += try {
                            val text = Smb.readBytes(path).toString(Charsets.UTF_8)
                            val channels = M3u.parse(text, "")
                            "✓ Live TV ($path): ${channels.size} channels, ${channels.count { it.inFavorites }} in Favorites"
                        } catch (e: Exception) {
                            "⚠ Live TV ($path): ${Scanner.describe(e)}"
                        }
                    }
                    Settings.backgroundsFolder.takeIf { it.isNotBlank() }?.let { path ->
                        lines += try {
                            val n = Smb.list(path).count { !it.isDir && it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                            "✓ Backgrounds ($path): $n pictures"
                        } catch (e: Exception) {
                            "⚠ Backgrounds ($path): ${Scanner.describe(e)}"
                        }
                    }
                    lines.joinToString("\n")
                } catch (e: Exception) {
                    "✗ Could not connect: ${Scanner.describe(e)}"
                }
            }
            status.text = result
            LiveTv.refresh()
            Backgrounds.refresh()
            if (scanAfter && !result.contains("✗")) Library.scan(full)
        }
    }
}
