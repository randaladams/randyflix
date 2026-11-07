package com.randyflix.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.view.View
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

class SettingsActivity : TvActivity() {
    private lateinit var status: TextView
    private lateinit var clock: ClockView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        status = findViewById(R.id.status)
        Screen.background(this)
        clock = Screen.topBar(this, Tab.SETTINGS).clock

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
        findViewById<TextView>(R.id.about_text).text =
            "Plays the movie and TV library on ${Settings.host} over the network, plus Live TV and " +
                "background pictures from the RandyFlix folder (${Settings.appFolder.ifBlank { "off" }}).\n\n" +
                "Library: ${Library.data.movies.size} movies, ${Library.data.shows.size} TV shows."

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
        val clicks = findViewById<Button>(R.id.key_clicks)
        fun showClicksState() {
            clicks.text = if (Settings.keyClicks) "Key clicks: On" else "Key clicks: Off"
        }
        showClicksState()
        clicks.setOnClickListener {
            Settings.keyClicks = !Settings.keyClicks
            showClicksState()
            clicksChangedHere()
        }
        val order = findViewById<Button>(R.id.channel_order)
        fun showOrderState() {
            order.text = if (Settings.channelsByUse) "My channels order: Most watched" else "My channels order: By name"
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
        val saverButtons = listOf(R.id.saver_3 to 3, R.id.saver_10 to 10, R.id.saver_30 to 30, R.id.saver_never to 0)
        fun showSaverState() {
            for ((id, min) in saverButtons) {
                val label = if (min == 0) "Never" else "$min minutes"
                findViewById<Button>(id).text = (if (Settings.screenSaverMinutes == min) "✓  " else "     ") + label
            }
        }
        showSaverState()
        for ((id, min) in saverButtons) findViewById<Button>(id).setOnClickListener {
            Settings.screenSaverMinutes = min
            showSaverState()
            saverSettingsChanged()
            status.text = if (min == 0) "Screen saver off." else "Screen saver after $min minutes without a button press."
        }

        Screen.noFocusOnScrollers(this)
        setUpSections(first = if (Settings.isConfigured) R.id.nav_about else R.id.nav_connection)

        // A text field uses Left/Right to move its cursor, so Left could take many presses to
        // get out of it. Left always goes back to the section list instead (the keyboard that
        // opens on Select is where the text is edited).
        for (field in listOf(host, movies, tv, appFolder)) {
            field.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) findViewById<View>(R.id.nav_connection).requestFocus()
                    true
                } else false
            }
        }
    }

    /**
     * Left list of sections (sorted by name): moving onto one shows it on the right; Select or
     * Right moves into it, and Left from inside comes back to its entry in the list.
     */
    private fun setUpSections(first: Int) {
        val sections = listOf(
            R.id.nav_about to R.id.sec_about,
            R.id.nav_clock to R.id.sec_clock,
            R.id.nav_connection to R.id.sec_connection,
            R.id.nav_library to R.id.sec_library,
            R.id.nav_live to R.id.sec_live,
            R.id.nav_playback to R.id.sec_playback,
            R.id.nav_saver to R.id.sec_saver,
            R.id.nav_sound to R.id.sec_sound,
        )
        fun show(nav: Int) {
            for ((n, sec) in sections) {
                findViewById<View>(sec).visibility = if (n == nav) View.VISIBLE else View.GONE
                findViewById<View>(n).isActivated = n == nav
            }
            // Left from anything in the section returns to this entry in the list.
            val section = findViewById<ViewGroup>(sections.first { it.first == nav }.second)
            focusables(section).forEach { it.nextFocusLeftId = nav }
        }
        for ((nav, sec) in sections) {
            val button = findViewById<View>(nav)
            button.setOnFocusChangeListener { _, has -> if (has) show(nav) }
            button.setOnClickListener {
                show(nav)
                focusables(findViewById(sec)).firstOrNull()?.requestFocus()
            }
            button.nextFocusRightId = View.NO_ID
        }
        show(first)
        findViewById<View>(first).requestFocus()
    }

    private fun focusables(group: ViewGroup): List<View> {
        val out = ArrayList<View>()
        for (i in 0 until group.childCount) {
            val v = group.getChildAt(i)
            if (v.isFocusable && v.visibility == View.VISIBLE) out += v
            if (v is ViewGroup) out += focusables(v)
        }
        return out
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
                    Settings.catalogPath.takeIf { it.isNotBlank() }?.let { path ->
                        lines += try {
                            val text = Smb.readBytes(path).toString(Charsets.UTF_8)
                            "✓ Channel guide ($path): ${M3u.parse(text, "").size} channels"
                        } catch (e: Exception) {
                            "⚠ Channel guide ($path): ${Scanner.describe(e)}"
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
