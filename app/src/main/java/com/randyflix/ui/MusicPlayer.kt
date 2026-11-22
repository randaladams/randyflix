package com.randyflix.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.randyflix.data.Track
import com.randyflix.smb.SmbDataSource
import com.randyflix.smb.Smb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The music player. One for the whole app, so music keeps playing while you move around the
 * menus. It gives way (pauses) when a movie, show or live channel starts, and pauses when the app
 * goes to the background.
 */
@OptIn(UnstableApi::class)
object MusicPlayer {
    private lateinit var app: Context
    private var player: ExoPlayer? = null
    private val tracks = HashMap<String, Track>()

    private val _current = MutableStateFlow<Track?>(null)
    /** The song playing (or paused on), or null when stopped. */
    val current: StateFlow<Track?> = _current

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** There's a queue to play or resume. */
    val isActive: Boolean get() = _current.value != null

    val isPlaying: Boolean get() = _playing.value

    private fun ensure(): ExoPlayer = player ?: ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory()))
        // Music asks for the speakers like any player, so a movie starting makes it pause.
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    _current.value = mediaItem?.mediaId?.let { tracks[it] }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playing.value = isPlaying
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        _playing.value = false
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // A song that won't play is skipped.
                    if (p.hasNextMediaItem()) {
                        p.seekToNextMediaItem()
                        p.prepare()
                        p.play()
                    } else {
                        _playing.value = false
                    }
                }
            })
            player = p
        }

    private fun item(t: Track): MediaItem {
        tracks[t.id] = t
        return MediaItem.Builder()
            .setUri(Smb.uri(t.id))
            .setMediaId(t.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build())
            .build()
    }

    /** Plays [list] from [start]; with [shuffle], in random order (starting with a random song). */
    fun play(list: List<Track>, start: Int = 0, shuffle: Boolean = false) {
        if (list.isEmpty()) return
        val p = ensure()
        tracks.clear()
        p.shuffleModeEnabled = shuffle
        val first = if (shuffle && start == 0) list.indices.random() else start.coerceIn(list.indices)
        p.setMediaItems(list.map { item(it) }, first, 0L)
        p.prepare()
        p.play()
        _current.value = list[first]
    }

    fun toggle() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekToDefaultPosition(0)
            p.play()
        } else if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    fun pause() {
        player?.pause()
    }

    fun next() {
        val p = player ?: return
        if (p.hasNextMediaItem()) p.seekToNextMediaItem()
    }

    /** Back to the start of the song, or to the previous song when near the start. */
    fun previous() {
        val p = player ?: return
        if (p.currentPosition > 3_000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
    }

    fun seekBy(ms: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + ms).coerceIn(0, maxOf(0, p.duration)))
    }

    /** Right after the current song. */
    fun playNext(list: List<Track>) {
        val p = player
        if (p == null || !isActive) return play(list)
        p.addMediaItems(p.currentMediaItemIndex + 1, list.map { item(it) })
    }

    /** At the end of the queue. */
    fun addToQueue(list: List<Track>) {
        val p = player
        if (p == null || !isActive) return play(list)
        p.addMediaItems(list.map { item(it) })
    }

    fun stop() {
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        _current.value = null
        _playing.value = false
    }

    var shuffle: Boolean
        get() = player?.shuffleModeEnabled ?: false
        set(v) { player?.shuffleModeEnabled = v }

    /** Off → all → one → off. */
    fun cycleRepeat() {
        val p = player ?: return
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    val repeatLabel: String get() = when (player?.repeatMode) {
        Player.REPEAT_MODE_ALL -> "Repeat: All"
        Player.REPEAT_MODE_ONE -> "Repeat: One"
        else -> "Repeat: Off"
    }

    /** Pause/Play, Next, Previous, Stop as menu lines (for the ☰ menus); empty when nothing's queued. */
    fun controls(): List<Pair<String, () -> Unit>> = if (!isActive) emptyList() else listOf(
        (if (isPlaying) "❚❚  Pause music" else "▶  Play music") to { toggle() },
        "⏭  Next song" to { next() },
        "⏮  Previous song" to { previous() },
        "⏹  Stop music" to { stop() },
    )

    /** A ☰ menu with just the music controls. */
    fun showControls(activity: android.app.Activity) {
        val c = controls()
        if (c.isEmpty()) return
        android.app.AlertDialog.Builder(activity)
            .setTitle(current.value?.let { "${it.title}  ·  ${it.artist}" } ?: "Music")
            .setItems(c.map { it.first }.toTypedArray()) { _, i -> c[i].second() }
            .show()
    }

    val position: Long get() = player?.currentPosition ?: 0L
    val duration: Long get() = player?.duration?.takeIf { it > 0 } ?: (_current.value?.duration ?: 0L)

    /** The songs after the current one, in the order they'll play (shuffle included). */
    fun upNext(limit: Int = 100): List<Pair<Int, Track>> {
        val p = player ?: return emptyList()
        val tl = p.currentTimeline
        if (tl.isEmpty) return emptyList()
        val out = ArrayList<Pair<Int, Track>>()
        var i = p.currentMediaItemIndex
        while (out.size < limit) {
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) break
            val id = p.getMediaItemAt(i).mediaId
            tracks[id]?.let { out += i to it }
        }
        return out
    }

    /** Jumps to a song in the queue (an index from [upNext]). */
    fun jumpTo(index: Int) {
        val p = player ?: return
        if (index in 0 until p.mediaItemCount) {
            p.seekToDefaultPosition(index)
            p.play()
        }
    }
}
