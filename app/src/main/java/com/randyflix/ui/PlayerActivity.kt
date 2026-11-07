package com.randyflix.ui

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.randyflix.R
import com.randyflix.data.Library
import com.randyflix.data.Movie
import com.randyflix.data.Playback
import com.randyflix.data.Subtitle
import com.randyflix.smb.Smb
import com.randyflix.smb.SmbDataSource

/**
 * Full-screen player. For TV episodes it queues the rest of the show, so the next episode
 * starts automatically; for multi-disc movies it queues every disc. Saves the resume
 * position every 10 seconds and on exit.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : FragmentActivity() {
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var currentId: String? = null   // episode being played, or the movie (for every disc)
    private var movie: Movie? = null
    private val handler = Handler(Looper.getMainLooper())
    private val saver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
    }

    override fun onStart() {
        super.onStart()
        if (player == null) startPlayback()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(saver)
        saveProgress()
        player?.release()
        player = null
    }

    private fun startPlayback() {
        // If we come back after being stopped, continue from where we left off.
        val id = currentId ?: intent.getStringExtra(Nav.EXTRA_ID) ?: return finish()
        val start = if (currentId != null) Playback.resume(id)?.position ?: 0L
        else intent.getLongExtra(EXTRA_START, 0L)
        val part = if (currentId != null) Playback.resume(id)?.part ?: 0
        else intent.getIntExtra(EXTRA_PART, 0)

        movie = Library.movie(id)
        val queue = buildQueue(id)
        if (queue.isEmpty()) {
            finish()
            return
        }

        val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory()))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player = p
        playerView.player = p
        currentId = id

        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (movie != null) return   // next disc of the same movie
                val previous = currentId
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previous != null) {
                    Playback.setWatched(previous, true)
                }
                currentId = mediaItem?.mediaId
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    currentId?.let { Playback.setWatched(it, true) }
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                showError(error)
            }
        })

        p.setMediaItems(queue, if (movie != null) part.coerceIn(0, queue.size - 1) else 0, start)
        p.prepare()
        p.playWhenReady = true
        handler.postDelayed(saver, 10_000)
    }

    private fun buildQueue(id: String): List<MediaItem> {
        Library.movie(id)?.let { m ->
            val files = m.files
            return files.mapIndexed { i, f ->
                mediaItem(f.path, if (files.size > 1) "${m.title} · Disc ${i + 1} of ${files.size}" else m.title, f.subtitles)
            }
        }
        val ref = Library.episode(id) ?: return emptyList()
        val eps = ref.show.ordered
        val idx = eps.indexOfFirst { it.id == id }.coerceAtLeast(0)
        // Queue this episode and the rest of the season/show (specials are not auto-played).
        return eps.drop(idx)
            .filterIndexed { i, e -> i == 0 || e.season != 0 }
            .map { e ->
                mediaItem(e.id, "${ref.show.title} · ${Format.episodeCode(e.season, e.episode)} · ${e.title}", e.subtitles)
            }
    }

    private fun mediaItem(id: String, title: String, subs: List<Subtitle>): MediaItem =
        MediaItem.Builder()
            .setUri(Smb.uri(id))
            .setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .setSubtitleConfigurations(subs.map { s ->
                MediaItem.SubtitleConfiguration.Builder(Smb.uri(s.path))
                    .setMimeType(subtitleMime(s.path))
                    .setLanguage(s.lang)
                    .setLabel(s.label)
                    .setSelectionFlags(if (s.label.contains("Forced")) C.SELECTION_FLAG_FORCED else 0)
                    .build()
            })
            .build()

    private fun subtitleMime(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt" -> MimeTypes.TEXT_VTT
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private fun saveProgress() {
        val p = player ?: return
        val id = currentId ?: return
        val duration = p.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        if (p.playbackState == Player.STATE_ENDED) return
        if (movie != null) {
            val part = p.currentMediaItemIndex
            Playback.save(id, p.currentPosition, duration, part, lastPart = part >= p.mediaItemCount - 1)
        } else {
            Playback.save(id, p.currentPosition, duration)
        }
    }

    private fun showError(error: PlaybackException) {
        handler.removeCallbacks(saver)
        val cause = generateSequence(error.cause) { it.cause }.lastOrNull()?.message
        AlertDialog.Builder(this)
            .setTitle("Can't play this file")
            .setMessage(listOfNotNull(error.errorCodeName, error.message, cause).distinct().joinToString("\n"))
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** Left/right on the remote skips back 10s / forward 30s while the controls are hidden. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { p.seekBack(); playerView.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { p.seekForward(); playerView.showController(); return true }
            }
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_START = "start"
        const val EXTRA_PART = "part"
    }
}
