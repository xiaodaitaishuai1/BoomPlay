package com.boomplay.musicplayer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.boomplay.musicplayer.databinding.ActivityMainBinding
import com.boomplay.musicplayer.library.Collection
import com.boomplay.musicplayer.library.Track
import com.boomplay.musicplayer.library.UserPlaylist
import com.boomplay.musicplayer.ui.*
import com.boomplay.musicplayer.utils.UiUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val model: CatalogViewModel by viewModels()
    private val app get() = application as BoomPlayApplication
    private val dialogs by lazy { LibraryDialogs(this) }
    private lateinit var adapter: CatalogAdapter
    private var favorites = emptyList<Track>()
    private var recent = emptyList<Track>()
    private var playlists = emptyList<UserPlaylist>()
    private var searches = emptyList<String>()
    private var localTracks = emptyList<Track>()
    private var localJob: Job? = null
    private var localId: Long? = null
    private val scrolls get() = model.scrolls
    private var renderedKey = ""
    private val notification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)
        UiUtils.insets(binding.root)
        adapter = CatalogAdapter(::openCollection, ::playTrack) { dialogs.trackMenu(it, model.state.value.screen.takeIf { screen -> screen.kind == "playlist" }?.id) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.back.setOnClickListener { goBack() }
        binding.title.setOnClickListener { if (model.state.value.screen.kind == "library") navigate(Screen()) }
        binding.libraryTab.setOnClickListener { navigate(Screen("library", getString(R.string.library))) }
        binding.searchButton.setOnClickListener { navigate(Screen("search", getString(R.string.search))) }
        binding.clearQuery.setOnClickListener { binding.query.setText(""); search("") }
        binding.query.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                search(binding.query.text.toString().trim()); true
            } else false
        }
        binding.refresh.setOnRefreshListener { model.load(true) }
        binding.footer.setOnClickListener { model.load() }
        binding.playAll.setOnClickListener { playAll(false) }; binding.shuffle.setOnClickListener { playAll(true) }
        binding.collectionMore.setOnClickListener {
            val s = model.state.value.screen
            if (s.kind == "playlist") dialogs.playlistMenu(s.id, playlists.firstOrNull { it.id == s.id }?.name ?: s.title) { goBack() }
        }
        binding.miniPlayer.root.setOnClickListener { startActivity(Intent(this, PlayerActivity::class.java)) }
        binding.miniPlayer.toggle.setOnClickListener { if (app.playback.state.value.error != null) app.playback.retry() else app.playback.toggle() }
        onBackPressedDispatcher.addCallback(this) { if (!goBack()) finish() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { model.state.collectLatest { render() } }
            launch { app.library.favorites.collectLatest { favorites = it; render() } }
            launch { app.library.recent.collectLatest { recent = it; render() } }
            launch { app.library.playlists.collectLatest { playlists = it; render() } }
            launch { app.library.searches.collectLatest { searches = it; render() } }
            launch { app.playback.state.collectLatest { state ->
                binding.miniPlayer.root.isVisible = state.current != null
                state.current?.let {
                    binding.miniPlayer.title.text = it.title
                    binding.miniPlayer.artist.text = when { state.error != null -> getString(R.string.playback_error); state.buffering -> getString(R.string.loading); else -> it.artist.name }
                    UiUtils.artwork(binding.miniPlayer.artwork, it.cover)
                }
                binding.miniPlayer.toggle.setImageResource(if (state.playing || state.buffering) R.drawable.ic_player_pause else R.drawable.ic_player_play)
                binding.miniPlayer.toggle.contentDescription = getString(if (state.playing || state.buffering) R.string.pause else R.string.play)
                binding.miniPlayer.progress.progress = if (state.durationMs > 0) (state.positionMs * 100 / state.durationMs).toInt().coerceIn(0, 100) else 0
            } }
        } }
        model.load()
    }
    private fun screenKey() = model.state.value.screen.let { "${it.kind}:${it.id}:${it.collection?.id}:${it.query}" }
    private fun saveScroll() { scrolls[screenKey()] = binding.list.layoutManager?.onSaveInstanceState() }
    private fun navigate(screen: Screen) { saveScroll(); model.open(screen) }
    private fun goBack(): Boolean { saveScroll(); return model.back() }
    private fun search(query: String) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(binding.query.windowToken, 0)
        binding.query.clearFocus(); saveScroll()
        model.open(Screen("search", getString(R.string.search), query = query), false)
    }
    private fun openCollection(collection: Collection) = navigate(Screen("detail", collection.title, collection.id, collection))
    private fun tracks() = when (model.state.value.screen.kind) { "favorites" -> favorites; "recent" -> recent; "playlist" -> localTracks; else -> model.state.value.tracks }
    private fun playTrack(track: Track) {
        val queue = tracks()
        app.playback.play(queue, queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
        openPlayer()
    }
    private fun playAll(shuffle: Boolean) {
        if (tracks().isEmpty()) return
        app.playback.play(tracks(), 0, shuffle); openPlayer()
    }
    private fun openPlayer() {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        startActivity(Intent(this, PlayerActivity::class.java))
    }
    private fun render() {
        val state = model.state.value; val screen = state.screen
        val key = screenKey(); val changed = key != renderedKey
        val top = screen.kind == "home" || screen.kind == "library"
        binding.back.isVisible = !top
        binding.libraryTab.isVisible = top
        binding.title.text = if (top) getString(R.string.home) else if (screen.kind == "playlist") playlists.firstOrNull { it.id == screen.id }?.name ?: screen.title else screen.title
        binding.title.setTextColor(getColor(if (screen.kind == "library") R.color.text_secondary else R.color.text_primary))
        binding.libraryTab.setTextColor(getColor(if (screen.kind == "library") R.color.text_primary else R.color.text_secondary))
        binding.searchBox.isVisible = screen.kind == "search"
        binding.searchButton.isVisible = screen.kind != "search"
        if (changed && screen.kind == "search") binding.query.setText(screen.query)
        val songPage = screen.kind in listOf("detail", "favorites", "recent", "playlist") || screen.kind == "search" && screen.query.isNotBlank()
        binding.actions.isVisible = songPage
        binding.collectionMore.isVisible = screen.kind == "playlist"
        binding.playAll.isEnabled = tracks().isNotEmpty(); binding.shuffle.isEnabled = tracks().isNotEmpty()
        binding.refresh.isEnabled = screen.kind in listOf("home", "more", "detail") || screen.kind == "search" && screen.query.isNotBlank()
        binding.refresh.isRefreshing = state.loading
        binding.footer.isVisible = state.failed || state.more
        binding.footer.isEnabled = !state.loading
        binding.footer.setText(if (state.failed) R.string.retry else R.string.load_more)
        if (screen.kind == "playlist" && localId != screen.id) {
            localJob?.cancel(); localId = screen.id; localTracks = emptyList()
            localJob = lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { app.library.playlistTracks(screen.id).collectLatest { localTracks = it; render() } } }
        } else if (screen.kind != "playlist") { localJob?.cancel(); localId = null; localTracks = emptyList() }
        val rows = mutableListOf<CatalogRow>()
        when (screen.kind) {
            "home" -> {
                rows += CatalogRow.Heading(getString(R.string.browse_music))
                state.sections.forEach { section ->
                    if (section.layout != 1) rows += CatalogRow.Heading(section.title) { navigate(Screen("more", section.title, section.id)) }
                    rows += CatalogRow.Shelf(section.items, section.layout == 1)
                }
            }
            "library" -> {
                rows += CatalogRow.Heading(getString(R.string.library_hint))
                rows += CatalogRow.Entry(getString(R.string.favorites), getString(R.string.song_count, favorites.size), icon = R.drawable.ic_player_heart, action = { navigate(Screen("favorites", getString(R.string.favorites))) })
                rows += CatalogRow.Entry(getString(R.string.recently_played), getString(R.string.song_count, recent.size), action = { navigate(Screen("recent", getString(R.string.recently_played))) })
                rows += CatalogRow.Entry(getString(R.string.new_playlist), icon = R.drawable.ic_add, action = { dialogs.create() })
                rows += CatalogRow.Heading(getString(R.string.playlists))
                playlists.forEach { playlist -> rows += CatalogRow.Entry(playlist.name, getString(R.string.song_count, playlist.count), playlist.cover,
                    action = { navigate(Screen("playlist", playlist.name, playlist.id)) }, more = { dialogs.playlistMenu(playlist.id, playlist.name) }) }
            }
            "more" -> state.collections.forEach { item -> rows += CatalogRow.Entry(item.title, getString(when (item.type) { 2 -> R.string.artist_type; 5 -> R.string.album_type; else -> R.string.playlist_type }), item.cover, action = { openCollection(item) }) }
            "search" -> if (screen.query.isBlank()) {
                rows += CatalogRow.Heading(getString(R.string.search_history))
                if (searches.isNotEmpty()) rows += CatalogRow.Entry(getString(R.string.clear_history), action = { dialogs.perform { app.library.clearSearches() } })
                searches.forEach { query -> rows += CatalogRow.Entry(query, icon = R.drawable.ic_home_search, action = { search(query) }, more = { dialogs.perform { app.library.removeSearch(query) } }) }
            } else rows += state.tracks.map { CatalogRow.Song(it) }
            "detail" -> {
                screen.collection?.let { item ->
                    rows += CatalogRow.Shelf(listOf(item), true)
                    if (item.description.isNotBlank()) rows += CatalogRow.Entry(item.description, action = {})
                }
                rows += state.tracks.map { CatalogRow.Song(it) }
            }
            else -> rows += tracks().map { CatalogRow.Song(it) }
        }
        if (state.failed) rows += CatalogRow.Entry(getString(R.string.network_error), action = { model.load() })
        val empty = when (screen.kind) { "home" -> state.sections.isEmpty(); "library" -> false; "more" -> state.collections.isEmpty(); "search" -> screen.query.isNotBlank() && state.tracks.isEmpty(); else -> tracks().isEmpty() }
        if (empty && !state.loading && !state.failed) rows += CatalogRow.Heading(getString(R.string.empty))
        val position = if (!changed) binding.list.layoutManager?.onSaveInstanceState() else scrolls[key]
        adapter.submit(rows)
        if (position != null) binding.list.layoutManager?.onRestoreInstanceState(position)
        else if (changed) binding.list.scrollToPosition(0)
        renderedKey = key
    }
}
