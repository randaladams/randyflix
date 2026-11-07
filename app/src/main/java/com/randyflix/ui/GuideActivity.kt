package com.randyflix.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.randyflix.R
import com.randyflix.data.Channel
import com.randyflix.data.LiveTv
import com.randyflix.smb.Smb
import com.randyflix.smb.StreamDataSource
import kotlinx.coroutines.launch

/**
 * Channel guide: categories on the left (My channels, then the catalog's categories), the
 * category's channels in the middle, and a muted live preview of the highlighted channel on the
 * right, starting after it's been highlighted for a moment. Select watches full screen (Up/Down
 * then flip through that category); ☰ adds to / removes from My channels and Favorites.
 */
@OptIn(UnstableApi::class)
class GuideActivity : TvActivity() {
    private lateinit var categoryList: LinearLayout
    private lateinit var recycler: RecyclerView
    private lateinit var listTitle: TextView
    private lateinit var empty: TextView
    private lateinit var previewView: PlayerView
    private lateinit var previewNote: TextView
    private lateinit var previewName: TextView
    private lateinit var previewInfo: TextView
    private val adapter = ChannelAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var preview: ExoPlayer? = null
    private var previewing: Channel? = null
    private var focused: Channel? = null
    private var kind = LiveTv.LIST_ALL
    private val categoryButtons = LinkedHashMap<String, Button>()
    private val startPreview = Runnable { focused?.let { play(it) } }

    private val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    // A preview is always playing here, so no screen saver on this screen.
    override fun saverAllowed() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        categoryList = findViewById(R.id.categories)
        recycler = findViewById(R.id.channels)
        listTitle = findViewById(R.id.list_title)
        empty = findViewById(R.id.empty)
        previewView = findViewById(R.id.preview)
        previewNote = findViewById(R.id.preview_note)
        previewName = findViewById(R.id.preview_name)
        previewInfo = findViewById(R.id.preview_info)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        Screen.background(this)
        Screen.topBar(this, Tab.LIVE)
        Screen.noFocusOnScrollers(this)

        kind = savedInstanceState?.getString(STATE_KIND) ?: LiveTv.LIST_ALL
        buildCategories()
        showList(kind)
        categoryButtons[kind]?.requestFocus()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveTv.version.collect {
                    buildCategories()
                    showList(kind, keepFocus = true)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_KIND, kind)
    }

    // The preview is made and released with resume/pause (not start/stop) so it never holds the
    // video decoder while the full-screen player is starting.
    override fun onResume() {
        super.onResume()
        val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(StreamDataSource.Factory(http)))
            .build()
        p.volume = 0f   // muted preview
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) previewNote.text = ""
            }

            override fun onPlayerError(error: PlaybackException) {
                previewNote.text = "Preview not available"
            }
        })
        preview = p
        previewView.player = p
        focused?.let { handler.postDelayed(startPreview, PREVIEW_DELAY_MS) }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(startPreview)
        preview?.release()
        preview = null
        previewing = null
    }

    /** Left column: My channels, then each catalog category, with how many channels each has. */
    private fun buildCategories() {
        val wanted = listOf(LiveTv.LIST_ALL) + LiveTv.categories().map { LiveTv.LIST_CATEGORY + it }
        if (wanted == categoryButtons.keys.toList()) {
            for ((k, b) in categoryButtons) b.text = categoryLabel(k)
            return
        }
        categoryList.removeAllViews()
        categoryButtons.clear()
        for (k in wanted) {
            val b = Button(this, null, 0, R.style.NavButton).apply {
                text = categoryLabel(k)
                isAllCaps = false
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = Format.dp(this@GuideActivity, 6) }
                setOnFocusChangeListener { _, has -> if (has) showList(k) }
                setOnClickListener {
                    showList(k)
                    recycler.post { recycler.getChildAt(0)?.requestFocus() }
                }
            }
            categoryButtons[k] = b
            categoryList.addView(b)
        }
    }

    private fun categoryLabel(k: String) = "${LiveTv.listName(k)}  (${LiveTv.list(k).size})"

    private fun showList(k: String, keepFocus: Boolean = false) {
        val list = LiveTv.list(k)
        if (k == kind && keepFocus.not() && adapter.items == list) return
        kind = k
        for ((key, b) in categoryButtons) b.isActivated = key == k
        listTitle.text = "${LiveTv.listName(k)}  ·  ${list.size} channels"
        adapter.submit(list, keepPlace = keepFocus)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        // An empty list would otherwise take the highlight itself, with nothing to show for it.
        recycler.isFocusable = list.isNotEmpty()
        empty.text = when {
            LiveTv.catalog.isEmpty() && k != LiveTv.LIST_ALL ->
                "No catalog.m3u in the RandyFlix folder yet."
            k == LiveTv.LIST_ALL -> "No channels yet. Pick a category, highlight a channel and press ☰ to add it."
            else -> "No channels in this category."
        }
    }

    /** Highlighting a channel: show its name now, start the preview after a short pause. */
    private fun onChannelFocused(ch: Channel) {
        focused = ch
        previewName.text = ch.name
        previewInfo.text = listOfNotNull(
            ch.groups.firstOrNull(),
            "My channels".takeIf { LiveTv.isMine(ch) },
            "★ Favorite".takeIf { LiveTv.isFavorite(ch) },
        ).joinToString("  ·  ")
        handler.removeCallbacks(startPreview)
        if (previewing?.url != ch.url) handler.postDelayed(startPreview, PREVIEW_DELAY_MS)
    }

    private fun play(ch: Channel) {
        val p = preview ?: return
        previewing = ch
        previewNote.text = "Loading…"
        http.setUserAgent(ch.userAgent ?: LiveActivity.DEFAULT_USER_AGENT)
        http.setDefaultRequestProperties(ch.referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
        p.setMediaItem(MediaItem.Builder().setUri(if (ch.isRemote) Uri.parse(ch.url) else Smb.uri(ch.url)).build())
        p.prepare()
        p.playWhenReady = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val ch = focused
            if (ch != null && recycler.hasFocus()) {
                Nav.channelMenu(this, ch, kind)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelHolder>() {
        var items: List<Channel> = emptyList()
            private set
        // Worked out once per list, not once per row drawn.
        private var favoriteUrls: Set<String> = emptySet()
        private var mineUrls: Set<String> = emptySet()

        /** Same channels: just redraw (marks may have changed) without losing the highlighted row. */
        fun submit(list: List<Channel>, keepPlace: Boolean) {
            favoriteUrls = LiveTv.favorites().mapTo(HashSet()) { it.url }
            mineUrls = LiveTv.all().mapTo(HashSet()) { it.url }
            if (list == items) {
                notifyItemRangeChanged(0, items.size)
                return
            }
            items = list
            notifyDataSetChanged()
            if (!keepPlace) recycler.scrollToPosition(0)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ChannelHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

        override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
            val ch = items[position]
            holder.name.text = ch.name
            holder.marks.text = listOfNotNull("★".takeIf { ch.url in favoriteUrls }, "✓".takeIf { ch.url in mineUrls && kind != LiveTv.LIST_ALL })
                .joinToString(" ")
            ImageLoader.load(holder.logo, ch.logo, Format.dp(this@GuideActivity, 88), placeholder = R.drawable.ic_tv)
            // Left goes back to the category this list belongs to.
            holder.itemView.nextFocusLeftId = categoryButtons[kind]?.id ?: View.NO_ID
            holder.itemView.setOnFocusChangeListener { _, has -> if (has) onChannelFocused(ch) }
            holder.itemView.setOnClickListener { Nav.watch(this@GuideActivity, ch, kind) }
        }
    }

    private class ChannelHolder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.logo)
        val name: TextView = view.findViewById(R.id.name)
        val marks: TextView = view.findViewById(R.id.marks)
    }

    companion object {
        private const val STATE_KIND = "kind"
        private const val PREVIEW_DELAY_MS = 1_000L
    }
}
