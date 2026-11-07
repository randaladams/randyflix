package com.randyflix.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.randyflix.R
import com.randyflix.data.Episode
import com.randyflix.data.Library
import com.randyflix.data.Playback
import com.randyflix.data.Show

/** Show details: season tabs across the top, episode list below. */
class ShowActivity : TvActivity() {
    private lateinit var show: Show
    private lateinit var recycler: RecyclerView
    private val adapter = EpisodeAdapter()
    private val seasonViews = ArrayList<Pair<Int, TextView>>()
    private var selectedSeason = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Library.show(intent.getStringExtra(Nav.EXTRA_ID))
        if (s == null) {
            finish()
            return
        }
        show = s
        setContentView(R.layout.activity_show)
        Screen.topBar(this, Tab.SHOWS, isTabRoot = false)

        ImageLoader.load(findViewById(R.id.backdrop), s.fanart ?: s.poster, 1280, 0)
        ImageLoader.load(findViewById<ImageView>(R.id.poster), s.poster, Format.dp(this, 100))
        findViewById<TextView>(R.id.title).text = s.title
        val seasons = s.ordered.map { it.season }.distinct()
        findViewById<TextView>(R.id.meta).text = listOfNotNull(
            s.year?.toString(),
            s.mpaa,
            s.rating?.let { "★ %.1f".format(it) },
            "${seasons.count { it != 0 }} seasons",
            "${s.episodes.size} episodes",
            s.genres.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", "),
        ).joinToString("   ·   ")
        findViewById<TextView>(R.id.plot).text = s.plot ?: ""

        recycler = findViewById(R.id.episodes)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val seasonBar = findViewById<LinearLayout>(R.id.seasons)
        for (n in seasons) {
            val tv = TextView(this).apply {
                text = if (n == 0) "Specials" else "Season $n"
                textSize = 16f
                setTextColor(ContextCompat.getColorStateList(this@ShowActivity, R.color.btn_text))
                background = ContextCompat.getDrawable(this@ShowActivity, R.drawable.btn_bg)
                gravity = Gravity.CENTER
                val h = Format.dp(this@ShowActivity, 18)
                val v = Format.dp(this@ShowActivity, 8)
                setPadding(h, v, h, v)
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, has -> if (has) selectSeason(n, focusEpisode = null) }
                setOnClickListener { selectSeason(n, focusEpisode = null); focusRow(0) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = Format.dp(this, 12)
            seasonBar.addView(tv, lp)
            seasonViews += n to tv
        }

        // Start on the next episode to watch.
        val next = Library.nextUp(s)
        selectSeason(next?.season ?: seasons.firstOrNull() ?: 1, focusEpisode = next)
    }

    override fun onResume() {
        super.onResume()
        if (::recycler.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun selectSeason(season: Int, focusEpisode: Episode?) {
        if (season != selectedSeason) {
            selectedSeason = season
            seasonViews.forEach { (n, v) -> v.isSelected = n == season; v.alpha = if (n == season) 1f else 0.7f }
            adapter.items = show.ordered.filter { it.season == season }
            adapter.notifyDataSetChanged()
        }
        if (focusEpisode != null) {
            val idx = adapter.items.indexOfFirst { it.id == focusEpisode.id }
            if (idx >= 0) focusRow(idx)
        }
    }

    private fun focusRow(index: Int) {
        if (adapter.itemCount == 0) return
        recycler.scrollToPosition(index)
        recycler.post { recycler.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    private fun toggleWatched(e: Episode) {
        val watched = Playback.isWatched(e.id)
        AlertDialog.Builder(this)
            .setTitle(e.title)
            .setItems(arrayOf(if (watched) "Mark unwatched" else "Mark watched", "Mark season ${e.season} watched", "Cancel")) { _, which ->
                when (which) {
                    0 -> Playback.setWatched(e.id, !watched)
                    1 -> show.episodes.filter { it.season == e.season }.forEach { Playback.setWatched(it.id, true) }
                }
                adapter.notifyDataSetChanged()
            }
            .show()
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeHolder>() {
        var items: List<Episode> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            EpisodeHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val e = items[position]
            holder.title.text = "${e.episode}. ${e.title}"
            holder.meta.text = listOfNotNull(
                Format.episodeCode(e.season, e.episode),
                e.aired,
                Format.minutes(e.runtime),
                Format.status(e.id),
            ).joinToString("   ·   ")
            holder.plot.text = e.plot ?: ""
            holder.plot.visibility = if (e.plot.isNullOrBlank()) View.GONE else View.VISIBLE
            val r = Playback.resume(e.id)
            holder.progress.visibility = if (r != null) View.VISIBLE else View.GONE
            holder.progress.progress = ((r?.fraction ?: 0f) * 1000).toInt()
            holder.thumb.alpha = if (Playback.isWatched(e.id)) 0.55f else 1f
            ImageLoader.load(holder.thumb, e.thumb ?: show.fanart, Format.dp(this@ShowActivity, 160))
            holder.itemView.setOnClickListener { Nav.playWithPrompt(this@ShowActivity, e.id, e.title) }
            holder.itemView.setOnLongClickListener { toggleWatched(e); true }
        }
    }

    private class EpisodeHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val progress: ProgressBar = view.findViewById(R.id.progress)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
        val plot: TextView = view.findViewById(R.id.plot)
    }
}
