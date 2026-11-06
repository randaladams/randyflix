package com.randyflix.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.randyflix.R
import com.randyflix.Settings
import com.randyflix.data.Library

/**
 * Launcher screen: plays the bundled intro clip (res/raw/intro.mp4) once per app start,
 * then opens the home screen. Any button skips it; it can be turned off in Settings.
 */
@OptIn(UnstableApi::class)
class IntroActivity : FragmentActivity() {
    private var player: ExoPlayer? = null
    private var done = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start refreshing the library while the clip plays.
        if (Settings.isConfigured) Library.scanIfStale()

        if (!Settings.showIntro) {
            openHome(animate = false)
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_intro)

        val p = ExoPlayer.Builder(this).build()
        player = p
        findViewById<PlayerView>(R.id.intro_view).player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) openHome(animate = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                openHome(animate = false)
            }
        })
        p.setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.intro)))
        p.prepare()
        p.playWhenReady = true

        // Safety net in case the clip never reports that it ended.
        handler.postDelayed({ openHome(animate = true) }, 15_000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Any button (including Back) skips the intro.
        if (event.action == KeyEvent.ACTION_UP) openHome(animate = true)
        return true
    }

    override fun onStop() {
        super.onStop()
        // Left the app mid-intro (e.g. Home button): just close; the next launch starts fresh.
        if (!done && !isChangingConfigurations) {
            done = true
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun openHome(animate: Boolean) {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        player?.pause()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        if (animate) {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
