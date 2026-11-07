package com.randyflix.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.randyflix.R
import com.randyflix.data.Library
import com.randyflix.data.Movie
import com.randyflix.data.Playback

class MovieActivity : FragmentActivity() {
    private lateinit var movie: Movie

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val m = Library.movie(intent.getStringExtra(Nav.EXTRA_ID))
        if (m == null) {
            finish()
            return
        }
        movie = m
        setContentView(R.layout.activity_movie)

        ImageLoader.load(findViewById(R.id.backdrop), m.fanart ?: m.poster, 1280, 0)
        ImageLoader.load(findViewById<ImageView>(R.id.poster), m.poster, Format.dp(this, 180))
        findViewById<TextView>(R.id.title).text = m.title
        findViewById<TextView>(R.id.meta).text = listOfNotNull(
            m.year?.toString(),
            Format.minutes(m.runtime),
            m.mpaa,
            m.rating?.let { "★ %.1f".format(it) },
            Format.videoInfo(m.videoCodec, m.height, m.audioCodec),
            m.parts.size.takeIf { it > 1 }?.let { "$it discs" },
        ).joinToString("   ·   ")
        findViewById<TextView>(R.id.tagline).apply {
            text = m.tagline
            visibility = if (m.tagline.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.plot).text = m.plot ?: "No description available."
        findViewById<TextView>(R.id.extra).text = listOfNotNull(
            m.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
            m.subtitles.takeIf { it.isNotEmpty() }?.let { s -> "Subtitles: " + s.joinToString(", ") { it.label } },
        ).joinToString("\n")

        findViewById<Button>(R.id.play).setOnClickListener {
            val r = Playback.resume(m.id)
            Nav.play(this, m.id, r?.position ?: 0L, r?.part ?: 0)
        }
        findViewById<Button>(R.id.restart).setOnClickListener { Nav.play(this, m.id, 0L) }
        findViewById<Button>(R.id.bookmarks).setOnClickListener { showBookmarks() }
        findViewById<Button>(R.id.watched).setOnClickListener {
            Playback.setWatched(m.id, !Playback.isWatched(m.id))
            bindButtons()
        }
        findViewById<Button>(R.id.collection).apply {
            if (m.collection == null) {
                visibility = View.GONE
            } else {
                text = m.collection
                setOnClickListener {
                    startActivity(GridActivity.intent(this@MovieActivity, GridFragment.MODE_COLLECTION + m.collection, m.collection))
                }
            }
        }
        findViewById<Button>(R.id.play).requestFocus()
        Screen.clock(this)
    }

    /** Pick a bookmark to play from (Menu button in the player adds them). */
    private fun showBookmarks() {
        val marks = Playback.bookmarks(movie.id)
        if (marks.isEmpty()) return
        val multiDisc = movie.files.size > 1
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(marks.map { "Play from ${Format.bookmark(it, multiDisc)}" }.toTypedArray()) { _, which ->
                Nav.play(this, movie.id, marks[which].position, marks[which].part)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::movie.isInitialized) bindButtons()
    }

    private fun bindButtons() {
        val r = Playback.resume(movie.id)
        findViewById<Button>(R.id.play).text = if (r != null) "Resume (${Format.resumePoint(r)})" else "Play"
        findViewById<Button>(R.id.restart).visibility = if (r != null) View.VISIBLE else View.GONE
        val marks = Playback.bookmarks(movie.id).size
        findViewById<Button>(R.id.bookmarks).apply {
            visibility = if (marks > 0) View.VISIBLE else View.GONE
            text = "Bookmarks ($marks)"
        }
        findViewById<Button>(R.id.watched).text = if (Playback.isWatched(movie.id)) "Mark unwatched" else "Mark watched"
    }
}
