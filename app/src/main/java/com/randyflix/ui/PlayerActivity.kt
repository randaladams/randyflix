package com.randyflix.ui

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format as MediaFormat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.randyflix.R
import com.randyflix.Settings
import com.randyflix.data.Library
import com.randyflix.data.Movie
import com.randyflix.data.Playback
import com.randyflix.data.Subtitle
import com.randyflix.smb.Smb
import com.randyflix.smb.SmbDataSource
import java.util.Locale

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
    private var seekFlash = false   // controls are showing only because Left/Right seeked
    private lateinit var clock: ClockView
    private var controlsVisible = false
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
        // Date and time while paused or while the controls are up; never over playing video.
        clock = Screen.clock(this)
        clock.visibility = View.GONE
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            controlsVisible = v == View.VISIBLE
            updateClock()
        })
    }

    private fun updateClock() {
        val paused = player?.let { !it.playWhenReady } ?: false
        clock.visibility = if (controlsVisible || paused) View.VISIBLE else View.GONE
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

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updateClock()
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
        // Queue this episode and, with auto-play on, the rest of the show (specials are not auto-played).
        return eps.drop(idx)
            .take(if (Settings.autoPlayNext) Int.MAX_VALUE else 1)
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

    /**
     * Left/right on the remote skips back 10s / forward 30s while the controls are hidden, flashing
     * the controls just long enough to see the new time. Other buttons show them a little longer.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showMenu()
            return true
        }
        // Repeated presses keep seeking while the controls are only up because of a seek.
        val seeking = !playerView.isControllerFullyVisible || seekFlash
        if (p != null && event.action == KeyEvent.ACTION_DOWN && seeking) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { p.seekBack(); flashControls(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { p.seekForward(); flashControls(); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            seekFlash = false
            playerView.controllerShowTimeoutMs = CONTROLS_TIMEOUT_MS
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    // ---- ☰ menu: bookmarks, subtitles, audio ----

    private class MenuItem(val label: String, val opensSubMenu: Boolean = false, val action: () -> Unit)

    /** Pauses and shows the menu; playback continues when it closes (unless it was paused already). */
    private fun showMenu() {
        val p = player ?: return
        val id = currentId ?: return
        val wasPlaying = p.playWhenReady
        p.playWhenReady = false
        val multiDisc = movie?.let { it.files.size > 1 } ?: false
        val part = if (movie != null) p.currentMediaItemIndex else 0
        val here = Playback.Bookmark(p.currentPosition.coerceAtLeast(0), part)
        val marks = Playback.bookmarks(id)
        val text = tracks(C.TRACK_TYPE_TEXT)
        val audio = tracks(C.TRACK_TYPE_AUDIO)

        val items = ArrayList<MenuItem>()
        items += MenuItem("🔖 Add bookmark at ${Format.bookmark(here, multiDisc)}") {
            Playback.addBookmark(id, here)
            Toast.makeText(this, "Bookmark added at ${Format.bookmark(here, multiDisc)}", Toast.LENGTH_SHORT).show()
        }
        for (b in marks) items += MenuItem("Go to bookmark ${Format.bookmark(b, multiDisc)}") { goTo(b) }
        if (marks.isNotEmpty()) items += MenuItem("Delete a bookmark…", true) { deleteBookmarkMenu(id, multiDisc, wasPlaying) }
        val textOff = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        items += MenuItem("Subtitles: ${(if (textOff) null else selectedName(text)) ?: "Off"}", true) {
            trackMenu(C.TRACK_TYPE_TEXT, text, wasPlaying)
        }
        if (audio.size > 1) {
            items += MenuItem("Audio: ${selectedName(audio) ?: "Default"}", true) { trackMenu(C.TRACK_TYPE_AUDIO, audio, wasPlaying) }
        }

        var subMenu = false
        AlertDialog.Builder(this)
            .setTitle(p.mediaMetadata.title ?: "Menu")
            .setItems(items.map { it.label }.toTypedArray()) { _, which ->
                subMenu = items[which].opensSubMenu   // the sub-menu resumes playback when it closes
                items[which].action()
            }
            .setOnDismissListener { if (!subMenu && wasPlaying) player?.playWhenReady = true }
            .show()
    }

    private fun goTo(b: Playback.Bookmark) {
        val p = player ?: return
        if (movie != null && b.part != p.currentMediaItemIndex && b.part < p.mediaItemCount) p.seekTo(b.part, b.position)
        else p.seekTo(b.position)
    }

    private fun deleteBookmarkMenu(id: String, multiDisc: Boolean, resume: Boolean) {
        val marks = Playback.bookmarks(id)
        AlertDialog.Builder(this)
            .setTitle("Delete which bookmark?")
            .setItems(marks.map { Format.bookmark(it, multiDisc) }.toTypedArray()) { _, which ->
                Playback.removeBookmark(id, marks[which])
                Toast.makeText(this, "Bookmark deleted", Toast.LENGTH_SHORT).show()
            }
            .setOnDismissListener { if (resume) player?.playWhenReady = true }
            .show()
    }

    /** One selectable track: its group, index in the group, display name, and whether it's playing. */
    private class Track(val group: Tracks.Group, val index: Int, val name: String, val selected: Boolean)

    private fun tracks(type: Int): List<Track> {
        val out = ArrayList<Track>()
        val groups = player?.currentTracks?.groups ?: return out
        for (g in groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out += Track(g, i, trackName(g.getTrackFormat(i), type, out.size + 1), g.isTrackSelected(i))
            }
        }
        return out
    }

    private fun selectedName(list: List<Track>): String? = list.firstOrNull { it.selected }?.name

    /** "English", "English · Forced", "Spanish · 5.1" … */
    private fun trackName(f: MediaFormat, type: Int, n: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { code -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).ifBlank { code } }
        val label = f.label?.takeIf { it.isNotBlank() }
        val parts = ArrayList<String>()
        parts += when {
            label != null && lang != null && !label.contains(lang, true) -> "$label ($lang)"
            label != null -> label
            lang != null -> lang
            else -> "Track $n"
        }
        val forced = (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
        if (type == C.TRACK_TYPE_TEXT && forced && label?.contains("Forced", true) != true) parts += "Forced"
        if (type == C.TRACK_TYPE_AUDIO) when (f.channelCount) {
            1 -> parts += "Mono"
            2 -> parts += "Stereo"
            6 -> parts += "5.1"
            8 -> parts += "7.1"
        }
        return parts.joinToString(" · ")
    }

    private fun trackMenu(type: Int, list: List<Track>, resume: Boolean) {
        val p = player ?: return
        val isText = type == C.TRACK_TYPE_TEXT
        val names = (if (isText) listOf("Off") else emptyList()) + list.map { it.name }
        val disabled = p.trackSelectionParameters.disabledTrackTypes.contains(type)
        val playing = list.indexOfFirst { it.selected }
        val current = if (isText) (if (disabled || playing < 0) 0 else playing + 1) else playing
        AlertDialog.Builder(this)
            .setTitle(if (isText) "Subtitles" else "Audio")
            .setSingleChoiceItems(names.toTypedArray(), current) { d, which ->
                val params = p.trackSelectionParameters.buildUpon()
                val pick = if (isText) which - 1 else which
                if (pick < 0) {
                    params.setTrackTypeDisabled(type, true)
                } else {
                    val t = list[pick]
                    params.setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                }
                p.trackSelectionParameters = params.build()
                d.dismiss()
            }
            .setOnDismissListener { if (resume) player?.playWhenReady = true }
            .show()
    }

    private fun flashControls() {
        seekFlash = true
        playerView.controllerShowTimeoutMs = SEEK_CONTROLS_TIMEOUT_MS
        playerView.showController()
    }

    companion object {
        const val EXTRA_START = "start"
        const val EXTRA_PART = "part"
        private const val CONTROLS_TIMEOUT_MS = 2500
        private const val SEEK_CONTROLS_TIMEOUT_MS = 1500
    }
}
