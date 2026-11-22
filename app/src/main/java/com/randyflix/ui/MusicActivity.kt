package com.randyflix.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.randyflix.R
import com.randyflix.Settings
import com.randyflix.data.Album
import com.randyflix.data.Artist
import com.randyflix.data.Genre
import com.randyflix.data.MusicLibrary
import com.randyflix.data.MusicPlaylist
import com.randyflix.data.ScanState
import com.randyflix.data.SongSort
import com.randyflix.data.Track
import kotlinx.coroutines.launch

/**
 * The Music tab: sections on the left (Now playing, Search, Playlists, Artists, Albums, Genres,
 * Songs), the chosen list on the right. Select plays or opens; ☰ on a row has the rest (play
 * next, add to queue or a playlist, go to album or artist). Back goes up a level, then to the
 * sections, then to the top bar.
 */
class MusicActivity : TvActivity() {
    private lateinit var sections: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var pageTitle: TextView
    private lateinit var pageInfo: TextView
    private lateinit var query: EditText
    private lateinit var empty: TextView
    private lateinit var strip: View
    private val adapter = RowAdapter()
    private val sectionButtons = LinkedHashMap<String, Button>()
    private val stack = ArrayList<Page>()
    private var section = ARTISTS
    private var focusedRow: Row? = null
    private var searchResult: MusicLibrary.SearchResult? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateStrip()
            handler.postDelayed(this, 1_000)
        }
    }

    // ---- rows and pages

    sealed class Row {
        class Action(val label: String, val run: () -> Unit) : Row()
        class Header(val label: String) : Row()
        class ArtistRow(val artist: Artist) : Row()
        class AlbumRow(val album: Album) : Row()
        class GenreRow(val genre: Genre) : Row()
        /** A song in [list] (what plays from it on Select); [playlist]/[playlistIndex] when it's in one of yours. */
        class SongRow(val track: Track, val list: List<Track>, val index: Int, val playlist: MusicPlaylist? = null, val playlistIndex: Int = -1) : Row()
        class PlaylistRow(val playlist: MusicPlaylist) : Row()
        class QueueRow(val queueIndex: Int, val track: Track) : Row()
    }

    /** A list on the right. Rebuilt each time it's shown, so it reflects changes. */
    class Page(val title: () -> String, val info: () -> String, val search: Boolean = false, val build: () -> List<Row>) {
        var focus = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music)
        sections = findViewById(R.id.sections)
        list = findViewById(R.id.music_list)
        pageTitle = findViewById(R.id.page_title)
        pageInfo = findViewById(R.id.page_info)
        query = findViewById(R.id.music_query)
        empty = findViewById(R.id.music_empty)
        strip = findViewById(R.id.now_strip)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null
        Screen.background(this)
        Screen.topBar(this, Tab.MUSIC)
        Screen.noFocusOnScrollers(this)

        strip.setOnClickListener { startActivity(Intent(this, NowPlayingActivity::class.java)) }
        setUpSearch()
        buildSections()
        section = savedInstanceState?.getString(STATE_SECTION) ?: if (MusicPlayer.isActive) NOW else ARTISTS
        showSection(section)
        sectionButtons[section]?.requestFocus()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { MusicLibrary.version.collect { render(keepFocus = true) } }
                launch {
                    // Progress messages arrive several times a second; only the "reading your
                    // music" message on an empty list needs them. New songs come via version.
                    var lastKind: Class<*>? = null
                    MusicLibrary.state.collect { s ->
                        val kind = s.javaClass
                        val empty = MusicLibrary.data.tracks.isEmpty()
                        if (kind != lastKind || (empty && s is ScanState.Running)) {
                            lastKind = kind
                            if (empty) render(keepFocus = true)
                            else pageInfo.text = stack.lastOrNull()?.info?.invoke() ?: ""
                        }
                    }
                }
                launch { MusicPlayer.current.collect { buildSections(); updateStrip(); if (section == NOW) render(keepFocus = true) } }
                launch { MusicPlayer.playing.collect { updateStrip() } }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SECTION, section)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    // ---- sections (left)

    private fun buildSections() {
        val wanted = listOfNotNull(NOW.takeIf { MusicPlayer.isActive }, SEARCH, PLAYLISTS, ARTISTS, ALBUMS, GENRES, SONGS)
        if (wanted == sectionButtons.keys.toList()) return
        val hadFocus = sections.findFocus()
        sections.removeAllViews()
        sectionButtons.clear()
        for (k in wanted) {
            val b = Button(this, null, 0, R.style.NavButton).apply {
                text = LABELS.getValue(k)
                isAllCaps = false
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                // Run just after, not during: Android can move the highlight here in the middle of
                // the list redrawing itself, and redrawing it again right then crashes.
                setOnFocusChangeListener { v, has ->
                    if (has) v.post { if (v.hasFocus() && (k != section || stack.size > 1)) showSection(k) }
                }
                setOnClickListener {
                    if (k == NOW) startActivity(Intent(this@MusicActivity, NowPlayingActivity::class.java))
                    else if (k == SEARCH) query.requestFocus()
                    else focusList(0)
                }
            }
            sectionButtons[k] = b
            sections.addView(b)
        }
        for ((k, b) in sectionButtons) b.isActivated = k == section
        if (hadFocus != null && section in sectionButtons) sectionButtons[section]?.requestFocus()
        if (section !in sectionButtons) showSection(ARTISTS)
    }

    private fun showSection(k: String) {
        section = k
        for ((key, b) in sectionButtons) b.isActivated = key == k
        stack.clear()
        stack += rootPage(k)
        render(keepFocus = false)
    }

    // ---- pages

    private fun rootPage(k: String): Page = when (k) {
        NOW -> Page({ "Now playing" }, { MusicPlayer.current.value?.let { "${it.title}  ·  ${it.artist}" } ?: "" }) {
            listOf(Row.Action("Open the Now playing screen") { startActivity(Intent(this, NowPlayingActivity::class.java)) }) +
                listOf(Row.Header("Up next")) + MusicPlayer.upNext().map { (i, t) -> Row.QueueRow(i, t) }
        }
        SEARCH -> Page({ "Search" }, { searchInfo() }, search = true) { searchRows() }
        PLAYLISTS -> Page({ "Playlists" }, { "${MusicLibrary.playlists().size} playlists" }) {
            listOf(Row.Action("+  New playlist") { newPlaylist(emptyList()) }) +
                MusicLibrary.playlists().map { Row.PlaylistRow(it) }
        }
        ALBUMS -> Page({ "Albums" }, { "${MusicLibrary.albums().size} albums" }) { MusicLibrary.albums().map { Row.AlbumRow(it) } }
        GENRES -> Page({ "Genres" }, { "${MusicLibrary.genres().size} genres" }) { MusicLibrary.genres().map { Row.GenreRow(it) } }
        SONGS -> Page({ "Songs" }, { "${MusicLibrary.data.tracks.size} songs  ·  sorted by ${MusicLibrary.songSort.label.lowercase()}" }) {
            val songs = MusicLibrary.songs()
            listOf(Row.Action("Sort by: ${MusicLibrary.songSort.label}  (Select to change)") {
                val all = SongSort.values()
                MusicLibrary.songSort = all[(MusicLibrary.songSort.ordinal + 1) % all.size]
                render(keepFocus = true)
            }) + playRows(songs) + songRows(songs)
        }
        else -> Page({ "Artists" }, { "${MusicLibrary.artists().size} artists" }) { MusicLibrary.artists().map { Row.ArtistRow(it) } }
    }

    private fun artistPage(a: Artist) = Page({ a.name }, { "${a.albums.size} albums  ·  ${a.tracks.size} songs" }) {
        playRows(a.tracks.sortedWith(trackOrder)) + a.albums.map { Row.AlbumRow(it) } +
            Row.Action("All songs (${a.tracks.size})") { push(songsPage(a.name, a.tracks.sortedWith(trackOrder))) }
    }

    private fun albumPage(al: Album) = Page({ al.title }, { listOfNotNull(al.artist, al.year?.toString(), "${al.tracks.size} songs", totalTime(al.tracks)).joinToString("  ·  ") }) {
        playRows(al.tracks) + songRows(al.tracks, showAlbum = false)
    }

    private fun genrePage(g: Genre) = Page({ g.name }, { "${g.albums.size} albums  ·  ${g.tracks.size} songs" }) {
        playRows(g.tracks) + g.albums.sortedBy { MusicLibrary.sortKey(it.artist) }.map { Row.AlbumRow(it) } +
            Row.Action("All songs (${g.tracks.size})") { push(songsPage(g.name, g.tracks)) }
    }

    private fun playlistPage(id: String) = Page(
        { MusicLibrary.playlist(id)?.name ?: "Playlist" },
        { MusicLibrary.playlist(id)?.let { p -> listOfNotNull("${p.tracks.size} songs", totalTime(p.tracks), if (p.custom) "yours" else p.folder ?: "music folder").joinToString("  ·  ") } ?: "" },
    ) {
        val p = MusicLibrary.playlist(id) ?: return@Page emptyList()
        val tracks = p.tracks
        // Keep each row's place in the playlist (so ☰ → Remove removes the right one).
        val rows = ArrayList<Row>()
        var n = 0
        p.trackIds.forEachIndexed { i, tid ->
            MusicLibrary.track(tid)?.let { t -> rows += Row.SongRow(t, tracks, n++, p, i) }
        }
        playRows(tracks) + rows
    }

    private fun songsPage(title: String, songs: List<Track>) = Page({ title }, { "${songs.size} songs" }) { playRows(songs) + songRows(songs) }

    private fun playRows(tracks: List<Track>): List<Row> = if (tracks.isEmpty()) emptyList() else listOf(
        Row.Action("▶  Play all") { MusicPlayer.play(tracks) },
        Row.Action("🔀  Shuffle") { MusicPlayer.play(tracks, shuffle = true) },
    )

    private fun songRows(tracks: List<Track>, showAlbum: Boolean = true): List<Row> =
        tracks.mapIndexed { i, t -> Row.SongRow(t, tracks, i) }.also { showAlbumInRows = showAlbum }

    /** Album pages leave the album name off each song (it's in the title). */
    private var showAlbumInRows = true

    private val trackOrder = compareBy<Track>({ it.year ?: 0 }, { MusicLibrary.sortKey(it.album) }, { it.disc }, { it.track })

    private fun push(p: Page) {
        stack.lastOrNull()?.focus = currentRowIndex()
        stack += p
        val had = list.hasFocus()
        render(keepFocus = false, focusTo = 0)
        if (!had) focusList(0)
    }

    private fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1)
        val had = list.hasFocus()
        val at = stack.last().focus
        render(keepFocus = false, focusTo = at)
        if (!had) focusList(at)
    }

    // ---- drawing

    /**
     * Redraws the list. If the highlight is in it, it's parked on the list itself while the rows
     * are replaced (otherwise Android hands it to the first section button), then put back on
     * row [focusTo] — or the same row, with [keepFocus] — once the new rows are laid out.
     */
    private fun render(keepFocus: Boolean, focusTo: Int? = null) {
        val page = stack.lastOrNull() ?: return
        val hadFocus = list.hasFocus()
        val target = focusTo ?: if (keepFocus) currentRowIndex() else 0
        showAlbumInRows = true
        val rows = if (MusicLibrary.isEnabled) page.build() else emptyList()
        pageTitle.text = page.title()
        pageInfo.text = page.info()
        query.visibility = if (page.search) View.VISIBLE else View.GONE
        val hasRows = rows.any { it !is Row.Header }
        if (hadFocus) {
            list.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            list.isFocusable = true
            list.requestFocus()
        }
        adapter.submit(rows)
        empty.text = emptyMessage(page)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (!hadFocus) {
            list.isFocusable = hasRows
            return
        }
        list.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                if (hasRows) focusList(target)
                else {
                    list.isFocusable = false
                    sectionButtons[section]?.requestFocus()
                }
            }
        })
    }

    private fun emptyMessage(page: Page): String {
        val s = MusicLibrary.state.value
        return when {
            !MusicLibrary.isEnabled -> "Music isn't set up yet. In Settings → Connection, fill in the Music folder (for example \"music\"), then Save."
            s is ScanState.Running && MusicLibrary.data.tracks.isEmpty() -> "Reading your music…  ${s.message}"
            s is ScanState.Failed && MusicLibrary.data.tracks.isEmpty() -> "Couldn't read the music folder:\n${s.message}"
            MusicLibrary.data.tracks.isEmpty() -> "No music found in ${Settings.musicPath} yet. Add some, then choose Refresh library in Settings."
            page.search -> if (query.text.isBlank()) "Type what you're looking for, then press Search on the keyboard." else "Nothing found."
            section == PLAYLISTS -> "No playlists yet."
            else -> "Nothing here."
        }
    }

    private fun focusList(index: Int) {
        if (adapter.itemCount == 0) return
        var i = index.coerceIn(0, adapter.itemCount - 1)
        while (i < adapter.itemCount - 1 && adapter.rows[i] is Row.Header) i++
        list.scrollToPosition(i)
        list.post { list.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
    }

    private fun currentRowIndex(): Int {
        val v = list.focusedChild ?: return 0
        return list.getChildAdapterPosition(v).coerceAtLeast(0)
    }

    private fun updateStrip() {
        val t = MusicPlayer.current.value
        if (t == null) {
            strip.visibility = View.GONE
            return
        }
        strip.visibility = View.VISIBLE
        findViewById<TextView>(R.id.now_title).text = t.title
        findViewById<TextView>(R.id.now_info).text = "${t.artist}  ·  ${t.album}"
        findViewById<TextView>(R.id.now_state).text = if (MusicPlayer.isPlaying) "▶ Playing" else "❚❚ Paused"
        val d = MusicPlayer.duration
        findViewById<ProgressBar>(R.id.now_progress).progress = if (d > 0) (MusicPlayer.position * 1000 / d).toInt() else 0
        val art = findViewById<ImageView>(R.id.now_art)
        if (art.tag != t.art) {
            art.tag = t.art
            ImageLoader.load(art, t.art, Format.dp(this, 48), placeholder = R.drawable.ic_music)
        }
    }

    // ---- search

    private fun setUpSearch() {
        query.setOnFocusChangeListener { v, has ->
            if (has) v.postDelayed({
                if (v.hasFocus()) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
            }, 200)
        }
        query.setOnKeyListener { _, keyCode, event ->
            // Left goes back to the sections instead of moving the cursor.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.action == KeyEvent.ACTION_DOWN) sectionButtons[SEARCH]?.requestFocus()
                true
            } else false
        }
        query.setOnEditorActionListener { v, actionId, event ->
            val enterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER || event?.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            if (enterKey && event?.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enterKey) {
                searchResult = MusicLibrary.search(v.text.toString())
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                render(keepFocus = false)
                if (adapter.rows.any { it !is Row.Header }) focusList(0)
                true
            } else false
        }
    }

    private fun searchInfo(): String {
        val r = searchResult ?: return "Artists, albums and songs"
        return "${r.artists.size} artists  ·  ${r.albums.size} albums  ·  ${r.songs.size} songs"
    }

    private fun searchRows(): List<Row> {
        val r = searchResult ?: return emptyList()
        val rows = ArrayList<Row>()
        if (r.artists.isNotEmpty()) { rows += Row.Header("Artists"); r.artists.take(30).forEach { rows += Row.ArtistRow(it) } }
        if (r.albums.isNotEmpty()) { rows += Row.Header("Albums"); r.albums.take(50).forEach { rows += Row.AlbumRow(it) } }
        if (r.songs.isNotEmpty()) { rows += Row.Header("Songs"); rows += songRows(r.songs.take(200)) }
        return rows
    }

    // ---- Select and ☰

    private fun select(row: Row) {
        when (row) {
            is Row.Action -> row.run()
            is Row.ArtistRow -> push(artistPage(row.artist))
            is Row.AlbumRow -> push(albumPage(row.album))
            is Row.GenreRow -> push(genrePage(row.genre))
            is Row.PlaylistRow -> push(playlistPage(row.playlist.id))
            is Row.SongRow -> MusicPlayer.play(row.list, row.index)
            is Row.QueueRow -> MusicPlayer.jumpTo(row.queueIndex)
            is Row.Header -> {}
        }
    }

    private fun menu(row: Row) {
        when (row) {
            is Row.SongRow -> songMenu(row)
            is Row.AlbumRow -> groupMenu(row.album.title, row.album.tracks, null)
            is Row.ArtistRow -> groupMenu(row.artist.name, row.artist.tracks.sortedWith(trackOrder), null)
            is Row.GenreRow -> groupMenu(row.genre.name, row.genre.tracks, null)
            is Row.PlaylistRow -> groupMenu(row.playlist.name, row.playlist.tracks, row.playlist)
            else -> {}
        }
    }

    private fun songMenu(row: Row.SongRow) {
        val t = row.track
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun add(label: String, run: () -> Unit) { labels += label; actions += run }
        add("Play next") { MusicPlayer.playNext(listOf(t)); toast("Plays next: ${t.title}") }
        add("Add to queue") { MusicPlayer.addToQueue(listOf(t)); toast("Added to the queue") }
        add("Add to playlist…") { addToPlaylist(listOf(t)) }
        MusicLibrary.album(t.albumKey)?.let { al -> add("Go to album: ${al.title}") { push(albumPage(al)) } }
        MusicLibrary.artist(t.artistName)?.let { a -> add("Go to artist: ${a.name}") { push(artistPage(a)) } }
        val pl = row.playlist
        if (pl != null && pl.custom && row.playlistIndex >= 0) add("Remove from ${pl.name}") {
            MusicLibrary.removeFromPlaylist(pl.id, row.playlistIndex)
            toast("Removed from ${pl.name}")
        }
        AlertDialog.Builder(this).setTitle(t.title).setItems(labels.toTypedArray()) { _, i -> actions[i]() }.show()
    }

    private fun groupMenu(title: String, tracks: List<Track>, playlist: MusicPlaylist?) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun add(label: String, run: () -> Unit) { labels += label; actions += run }
        add("Play") { MusicPlayer.play(tracks) }
        add("Shuffle") { MusicPlayer.play(tracks, shuffle = true) }
        add("Play next") { MusicPlayer.playNext(tracks); toast("${tracks.size} songs play next") }
        add("Add to queue") { MusicPlayer.addToQueue(tracks); toast("${tracks.size} songs added to the queue") }
        add("Add to playlist…") { addToPlaylist(tracks) }
        if (playlist != null && playlist.custom) {
            add("Rename playlist") {
                nameDialog("Rename playlist", playlist.name) { name ->
                    if (!MusicLibrary.renamePlaylist(playlist.id, name)) toast("There's already a playlist called $name")
                }
            }
            add("Delete playlist") {
                AlertDialog.Builder(this).setMessage("Delete the playlist \"${playlist.name}\"? The songs stay in your music.")
                    .setPositiveButton("Delete") { _, _ -> MusicLibrary.deletePlaylist(playlist.id); toast("Deleted ${playlist.name}") }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        AlertDialog.Builder(this).setTitle(title).setItems(labels.toTypedArray()) { _, i -> actions[i]() }.show()
    }

    private fun addToPlaylist(tracks: List<Track>) {
        val mine = MusicLibrary.playlists().filter { it.custom }
        val labels = listOf("+  New playlist…") + mine.map { it.name }
        AlertDialog.Builder(this).setTitle("Add to playlist").setItems(labels.toTypedArray()) { _, i ->
            if (i == 0) newPlaylist(tracks.map { it.id })
            else {
                val p = mine[i - 1]
                MusicLibrary.addToPlaylist(p.id, tracks.map { it.id })
                toast(if (tracks.size == 1) "Added to ${p.name}" else "${tracks.size} songs added to ${p.name}")
            }
        }.show()
    }

    private fun newPlaylist(trackIds: List<String>) {
        nameDialog("New playlist", "") { name ->
            if (MusicLibrary.createPlaylist(name, trackIds)) toast("Made the playlist $name")
            else toast("There's already a playlist called $name")
        }
    }

    private fun nameDialog(title: String, initial: String, done: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(initial)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        val pad = Format.dp(this, 20)
        val box = LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(field) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- keys

    private var backHandled = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !screenSaverShowing()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                // Back in the list: up a level, then back to the sections. Otherwise as usual.
                backHandled = (list.hasFocus() || strip.hasFocus() || query.hasFocus())
                if (backHandled) return true
            } else if (event.action == KeyEvent.ACTION_UP && backHandled) {
                backHandled = false
                if (list.hasFocus() && stack.size > 1) pop() else sectionButtons[section]?.requestFocus()
                return true
            } else if (backHandled) return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && list.hasFocus()) {
            if (event.action == KeyEvent.ACTION_UP) focusedRow?.let { menu(it) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- list

    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>() {
        var rows: List<Row> = emptyList()
            private set
        private var albumNames = true

        fun submit(list: List<Row>) {
            rows = list
            albumNames = showAlbumInRows
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_music, parent, false))

        override fun onBindViewHolder(h: RowHolder, position: Int) {
            val row = rows[position]
            val ctx = this@MusicActivity
            val thumbSize = Format.dp(ctx, 52)
            h.itemView.isFocusable = row !is Row.Header
            h.itemView.background = if (row is Row.Header) null else ctx.getDrawable(R.drawable.btn_bg)
            h.thumb.visibility = View.VISIBLE
            h.subtitle.visibility = View.VISIBLE
            h.detail.text = ""
            when (row) {
                is Row.Action -> {
                    h.title.text = row.label
                    h.subtitle.visibility = View.GONE
                    h.thumb.visibility = View.GONE
                }
                is Row.Header -> {
                    h.title.text = row.label
                    h.subtitle.visibility = View.GONE
                    h.thumb.visibility = View.GONE
                }
                is Row.ArtistRow -> {
                    h.title.text = row.artist.name
                    h.subtitle.text = "${row.artist.albums.size} albums  ·  ${row.artist.tracks.size} songs"
                    ImageLoader.load(h.thumb, row.artist.art, thumbSize, placeholder = R.drawable.ic_music)
                }
                is Row.AlbumRow -> {
                    h.title.text = row.album.title
                    h.subtitle.text = listOfNotNull(row.album.artist, row.album.year?.toString()).joinToString("  ·  ")
                    h.detail.text = "${row.album.tracks.size}"
                    ImageLoader.load(h.thumb, row.album.art, thumbSize, placeholder = R.drawable.ic_music)
                }
                is Row.GenreRow -> {
                    h.title.text = row.genre.name
                    h.subtitle.text = "${row.genre.tracks.size} songs"
                    ImageLoader.load(h.thumb, row.genre.tracks.firstNotNullOfOrNull { it.art }, thumbSize, placeholder = R.drawable.ic_music)
                }
                is Row.PlaylistRow -> {
                    h.title.text = row.playlist.name
                    h.subtitle.text = listOfNotNull("${row.playlist.trackIds.size} songs",
                        if (row.playlist.custom) "yours" else row.playlist.folder ?: "music folder").joinToString("  ·  ")
                    ImageLoader.load(h.thumb, row.playlist.tracks.firstNotNullOfOrNull { it.art }, thumbSize, placeholder = R.drawable.ic_music)
                }
                is Row.SongRow -> {
                    val t = row.track
                    val playing = MusicPlayer.current.value?.id == t.id
                    h.title.text = (if (playing) "♪  " else "") + (if (!albumNames && t.track > 0) "${t.track}.  " else "") + t.title
                    h.subtitle.text = if (albumNames) "${t.artist}  ·  ${t.album}" else t.artist
                    h.detail.text = Format.clock(t.duration).takeIf { t.duration > 0 } ?: ""
                    ImageLoader.load(h.thumb, t.art, thumbSize, placeholder = R.drawable.ic_music)
                }
                is Row.QueueRow -> {
                    h.title.text = row.track.title
                    h.subtitle.text = "${row.track.artist}  ·  ${row.track.album}"
                    h.detail.text = Format.clock(row.track.duration).takeIf { row.track.duration > 0 } ?: ""
                    ImageLoader.load(h.thumb, row.track.art, thumbSize, placeholder = R.drawable.ic_music)
                }
            }
            h.itemView.nextFocusLeftId = sectionButtons[section]?.id ?: View.NO_ID
            h.itemView.setOnFocusChangeListener { _, has -> if (has) focusedRow = row }
            h.itemView.setOnClickListener { select(row) }
        }
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val detail: TextView = view.findViewById(R.id.detail)
    }

    companion object {
        private const val STATE_SECTION = "section"
        const val NOW = "now"
        const val SEARCH = "search"
        const val PLAYLISTS = "playlists"
        const val ARTISTS = "artists"
        const val ALBUMS = "albums"
        const val GENRES = "genres"
        const val SONGS = "songs"
        private val LABELS = mapOf(
            NOW to "♪  Now playing", SEARCH to "Search", PLAYLISTS to "Playlists", ARTISTS to "Artists",
            ALBUMS to "Albums", GENRES to "Genres", SONGS to "Songs",
        )

        private fun totalTime(tracks: List<Track>): String? {
            val ms = tracks.sumOf { it.duration }
            if (ms <= 0) return null
            val min = ms / 60_000
            return if (min >= 60) "${min / 60} hr ${min % 60} min" else "$min min"
        }

        @Suppress("unused")
        fun open(activity: Activity) = activity.startActivity(Intent(activity, MusicActivity::class.java))
    }
}
