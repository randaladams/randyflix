package com.randyflix.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.randyflix.R
import com.randyflix.data.Track
import kotlinx.coroutines.launch

/**
 * Now playing: the cover, the song, progress, ⏮ ⏯ ⏭ / Shuffle / Repeat and Up next (Select a
 * song to jump to it). The remote's play/pause, rewind and fast-forward buttons work here and on
 * every menu screen.
 */
class NowPlayingActivity : TvActivity() {
    private lateinit var art: ImageView
    private lateinit var play: Button
    private lateinit var shuffle: Button
    private lateinit var repeat: Button
    private lateinit var queue: RecyclerView
    private val adapter = QueueAdapter()
    private var shownArt: String? = "-"
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
        Screen.background(this)
        Screen.topBar(this, Tab.MUSIC, isTabRoot = false)
        art = findViewById(R.id.np_art)
        play = findViewById(R.id.np_play)
        shuffle = findViewById(R.id.np_shuffle)
        repeat = findViewById(R.id.np_repeat)
        queue = findViewById(R.id.np_queue)
        queue.layoutManager = LinearLayoutManager(this)
        queue.adapter = adapter
        queue.itemAnimator = null

        findViewById<Button>(R.id.np_prev).setOnClickListener { MusicPlayer.previous() }
        findViewById<Button>(R.id.np_next).setOnClickListener { MusicPlayer.next() }
        play.setOnClickListener { MusicPlayer.toggle() }
        shuffle.setOnClickListener {
            MusicPlayer.shuffle = !MusicPlayer.shuffle
            updateModes()
            adapter.submit(MusicPlayer.upNext())
        }
        repeat.setOnClickListener {
            MusicPlayer.cycleRepeat()
            updateModes()
        }
        play.requestFocus()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    MusicPlayer.current.collect { t ->
                        if (t == null) finish() else showTrack(t)
                    }
                }
                launch { MusicPlayer.playing.collect { play.text = if (it) "❚❚  Pause" else "▶  Play" } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateModes()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun showTrack(t: Track) {
        findViewById<TextView>(R.id.np_title).text = t.title
        findViewById<TextView>(R.id.np_artist).text = t.artist
        findViewById<TextView>(R.id.np_album).text = listOfNotNull(t.album, t.year?.toString()).joinToString("  ·  ")
        if (shownArt != t.art) {
            shownArt = t.art
            ImageLoader.load(art, t.art, Format.dp(this, 380), placeholder = R.drawable.ic_music)
        }
        adapter.submit(MusicPlayer.upNext())
        updateProgress()
    }

    private fun updateProgress() {
        val d = MusicPlayer.duration
        val p = MusicPlayer.position
        findViewById<ProgressBar>(R.id.np_progress).progress = if (d > 0) (p * 1000 / d).toInt() else 0
        findViewById<TextView>(R.id.np_time).text = Format.clock(p)
        findViewById<TextView>(R.id.np_length).text = if (d > 0) Format.clock(d) else ""
    }

    private fun updateModes() {
        shuffle.text = if (MusicPlayer.shuffle) "Shuffle: On" else "Shuffle: Off"
        repeat.text = MusicPlayer.repeatLabel
    }

    private inner class QueueAdapter : RecyclerView.Adapter<Holder>() {
        private var items: List<Pair<Int, Track>> = emptyList()

        fun submit(list: List<Pair<Int, Track>>) {
            items = list
            queue.isFocusable = list.isNotEmpty()   // an empty list mustn't take the highlight
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_music, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val (index, t) = items[position]
            h.title.text = t.title
            h.subtitle.text = "${t.artist}  ·  ${t.album}"
            h.detail.text = Format.clock(t.duration).takeIf { t.duration > 0 } ?: ""
            ImageLoader.load(h.thumb, t.art, Format.dp(this@NowPlayingActivity, 52), placeholder = R.drawable.ic_music)
            h.itemView.setOnClickListener { MusicPlayer.jumpTo(index) }
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val detail: TextView = view.findViewById(R.id.detail)
    }
}
