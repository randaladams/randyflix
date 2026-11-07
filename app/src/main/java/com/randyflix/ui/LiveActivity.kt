package com.randyflix.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.randyflix.R
import com.randyflix.data.Channel
import com.randyflix.data.LiveTv
import com.randyflix.smb.Smb
import com.randyflix.smb.StreamDataSource

/**
 * Full-screen live TV. Up/Down change channel (wrapping around the playlist), with a banner
 * showing the channel. No resume points or watched marks: it's live.
 */
@OptIn(UnstableApi::class)
class LiveActivity : FragmentActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var banner: View
    private lateinit var bannerLogo: ImageView
    private lateinit var bannerName: TextView
    private lateinit var bannerInfo: TextView
    private var player: ExoPlayer? = null
    private var index = 0
    private val handler = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { banner.visibility = View.GONE }

    // Per-channel user agent / referrer are set on this before each tune.
    private val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LiveTv.channels.isEmpty()) return finish()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_live)
        playerView = findViewById(R.id.player_view)
        banner = findViewById(R.id.banner)
        bannerLogo = findViewById(R.id.banner_logo)
        bannerName = findViewById(R.id.banner_name)
        bannerInfo = findViewById(R.id.banner_info)
        index = savedInstanceState?.getInt(STATE_INDEX) ?: LiveTv.indexOf(intent.getStringExtra(Nav.EXTRA_ID)).coerceAtLeast(0)
    }

    override fun onStart() {
        super.onStart()
        if (player == null && LiveTv.channels.isNotEmpty()) start()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(hideBanner)
        player?.release()
        player = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, index)
    }

    private fun start() {
        val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(StreamDataSource.Factory(http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Fell too far behind the live edge (e.g. after a stall): jump back to live.
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                showBanner(channel(), "Can't play this channel right now · Up/Down for another", stay = true)
            }
        })
        player = p
        playerView.player = p
        tune(index)
    }

    private fun channel(): Channel = LiveTv.channels[index.coerceIn(0, LiveTv.channels.size - 1)]

    private fun tune(i: Int) {
        val channels = LiveTv.channels
        index = Math.floorMod(i, channels.size)
        val ch = channels[index]
        http.setUserAgent(ch.userAgent ?: DEFAULT_USER_AGENT)
        http.setDefaultRequestProperties(ch.referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
        val item = MediaItem.Builder()
            .setUri(if (ch.isRemote) Uri.parse(ch.url) else Smb.uri(ch.url))
            .setMediaId(ch.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.name).build())
            .build()
        player?.apply {
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
        showBanner(ch, listOfNotNull("${index + 1} of ${channels.size}", ch.groups.joinToString(", ").ifEmpty { null })
            .joinToString("  ·  "))
    }

    private fun showBanner(ch: Channel, info: String, stay: Boolean = false) {
        handler.removeCallbacks(hideBanner)
        bannerName.text = ch.name
        bannerInfo.text = info
        ImageLoader.load(bannerLogo, ch.logo, Format.dp(this, 96), placeholder = R.drawable.ic_tv)
        banner.visibility = View.VISIBLE
        if (!stay) handler.postDelayed(hideBanner, BANNER_MS)
    }

    /** Up/Down (or Channel +/-) change channel while the controls are hidden. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (player != null && event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { tune(index - 1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { tune(index + 1); return true }
                KeyEvent.KEYCODE_INFO -> { showBanner(channel(), "${index + 1} of ${LiveTv.channels.size}"); return true }
            }
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    companion object {
        private const val STATE_INDEX = "index"
        private const val BANNER_MS = 4_000L
        // Some free streams refuse players they don't recognise; a plain browser string works for most.
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
