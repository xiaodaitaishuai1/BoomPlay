package com.boomplay.musicplayer.ui

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.boomplay.musicplayer.BoomPlayApplication
import com.boomplay.musicplayer.library.Collection
import com.boomplay.musicplayer.library.HomeSection
import com.boomplay.musicplayer.library.LibraryRepository
import com.boomplay.musicplayer.library.MusicRepository
import com.boomplay.musicplayer.library.RegionPreferences
import com.boomplay.musicplayer.library.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class Screen(val kind: String = "home", val title: String = "", val id: Long = 0, val collection: Collection? = null, val query: String = "")
data class CatalogScroll(val position: Int = 0, val offset: Int = 0)
data class CatalogState(
    val screen: Screen = Screen(), val sections: List<HomeSection> = emptyList(),
    val collections: List<Collection> = emptyList(), val tracks: List<Track> = emptyList(),
    val loading: Boolean = false, val failed: Boolean = false, val more: Boolean = false,
    val initialized: Boolean = false,
)

class CatalogViewModel(
    application: Application,
    private val music: MusicRepository,
    private val library: LibraryRepository,
    private val regions: RegionPreferences = (application as BoomPlayApplication).regions,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) : this(
        application,
        (application as BoomPlayApplication).music,
        (application as BoomPlayApplication).library,
        savedState = savedState,
    )
    private val mutable = MutableStateFlow(CatalogState(decodeScreen(savedState.get<Any?>("catalog_route") as? Bundle) ?: Screen()))
    val state = mutable.asStateFlow()
    private val mutableSettingsErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val settingsErrors = mutableSettingsErrors.asSharedFlow()
    private var task: Job? = null
    private var generation = 0
    private var page = 0
    private val saved = LinkedHashMap<String, Pair<CatalogState, Int>>(16, .75f, true)
    private fun key(screen: Screen) = "${screen.kind}:${screen.id}:${screen.collection?.type}:${screen.query}"
    val stack = (savedState.get<Any?>("catalog_stack") as? List<*>).orEmpty().mapNotNull { decodeScreen(it as? Bundle) }.takeLast(30).toMutableList()
    private val scrolls = LinkedHashMap<String, CatalogScroll>().apply {
        (savedState.get<Any?>("catalog_scrolls") as? Bundle)?.let { bundle -> bundle.keySet().toList().takeLast(10).forEach { key ->
            bundle.getIntArray(key)?.takeIf { it.size == 2 && it[0] >= 0 }?.let { put(key, CatalogScroll(it[0], it[1])) }
        } }
    }
    val shelfScrolls = mutableMapOf<String, CatalogScroll>().apply {
        (savedState.get<Any?>("catalog_shelves") as? Bundle)?.let { bundle -> bundle.keySet().forEach { key ->
            bundle.getIntArray(key)?.takeIf { it.size == 2 && it[0] >= 0 }?.let { put(key, CatalogScroll(it[0], it[1])) }
        } }
    }
    private val searchDrafts = LinkedHashMap<String, String>().apply {
        (savedState.get<Any?>("catalog_drafts") as? Bundle)?.let { bundle -> bundle.keySet().toList().takeLast(10).forEach { key ->
            bundle.getString(key)?.let { put(key, it) }
        } }
    }
    var searchDraft: String
        get() = searchDrafts[screenKey()] ?: mutable.value.screen.query
        set(value) {
            searchDrafts.remove(screenKey()); searchDrafts[screenKey()] = value
            while (searchDrafts.size > 10) searchDrafts.remove(searchDrafts.keys.first())
            savedState["catalog_drafts"] = Bundle().apply { searchDrafts.forEach { (key, draft) -> putString(key, draft) } }
        }
    fun screenKey() = key(mutable.value.screen)
    fun scrollFor(key: String): CatalogScroll? = scrolls[key]
    fun pruneShelves(pageKey: String, activeIds: Set<String>) {
        shelfScrolls.keys.removeAll { it.startsWith("$pageKey:") && it !in activeIds }
        persistNavigation()
    }
    fun saveScroll(key: String, scroll: CatalogScroll) {
        scrolls.remove(key); scrolls[key] = scroll
        while (scrolls.size > 10) scrolls.remove(scrolls.keys.first())
        persistNavigation()
    }
    private fun persistNavigation() {
        savedState["catalog_route"] = encodeScreen(mutable.value.screen)
        savedState["catalog_stack"] = ArrayList(stack.map(::encodeScreen))
        savedState["catalog_scrolls"] = Bundle().apply { scrolls.forEach { (key, value) -> putIntArray(key, intArrayOf(value.position, value.offset)) } }
        savedState["catalog_shelves"] = Bundle().apply { shelfScrolls.forEach { (key, value) -> putIntArray(key, intArrayOf(value.position, value.offset)) } }
    }

    fun open(screen: Screen, push: Boolean = true) {
        if (screen == mutable.value.screen && mutable.value.initialized) return
        saved[key(mutable.value.screen)] = mutable.value.copy(loading = false) to page
        if (push) { stack.add(mutable.value.screen); if (stack.size > 30) stack.removeAt(0) }
        task?.cancel(); generation++
        val cached = saved.remove(key(screen))
        page = cached?.second ?: 0
        mutable.value = cached?.first?.copy(loading = false) ?: CatalogState(screen)
        // Nine inactive pages plus the current page keep the total at ten.
        while (saved.size > 9) {
            val evicted = saved.keys.first(); saved.remove(evicted); scrolls.remove(evicted)
            shelfScrolls.keys.removeAll { it.startsWith("$evicted:") }
        }
        persistNavigation()
        if (!mutable.value.initialized) load()
    }
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        open(stack.removeAt(stack.lastIndex), false)
        return true
    }
    fun regionChanged() {
        task?.cancel(); generation++
        saved.clear(); scrolls.clear(); shelfScrolls.clear(); page = 0
        mutable.value = CatalogState(mutable.value.screen)
        persistNavigation()
        load()
    }
    fun selectRegion(code: String) {
        viewModelScope.launch {
            try {
                regions.select(code)
                regionChanged()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableSettingsErrors.tryEmit(Unit) }
        }
    }
    fun load(refresh: Boolean = false) {
        val before = mutable.value
        if (before.loading && !refresh) return
        if (!refresh && before.initialized && !before.more && !before.failed) return
        if (refresh) { task?.cancel(); generation++; page = 0 }
        val stamp = generation
        val requestedPage = page
        mutable.value = before.copy(loading = true, failed = false)
        task = viewModelScope.launch {
            try {
                val screen = before.screen
                var result = if (requestedPage == 0) CatalogState(screen) else before
                when (screen.kind) {
                    "home" -> result = result.copy(sections = music.home(refresh), more = false)
                    "more" -> {
                        val response = music.collections(screen.id, requestedPage)
                        val old = result.collections
                        val merged = (old + response.items).distinctBy { it.id to it.type }
                        result = result.copy(collections = merged, more = response.hasMore && merged.size > old.size)
                    }
                    "detail" -> {
                        val response = music.detail(requireNotNull(screen.collection), requestedPage)
                        val old = result.tracks
                        val merged = (old + response.tracks.items).distinctBy { it.id }
                        result = result.copy(screen = screen.copy(collection = response.collection), tracks = merged, more = response.tracks.hasMore && merged.size > old.size)
                    }
                    "search" -> if (screen.query.isNotBlank()) {
                        val response = music.search(screen.query, requestedPage)
                        val old = result.tracks
                        val merged = (old + response.items).distinctBy { it.id }
                        result = result.copy(tracks = merged, more = response.hasMore && merged.size > old.size)
                        library.rememberSearch(screen.query)
                    }
                }
                if (stamp == generation) { page = requestedPage + 1; mutable.value = result.copy(loading = false, failed = false, initialized = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (stamp == generation) mutable.value = mutable.value.copy(loading = false, failed = true) }
        }
    }

    private fun encodeScreen(screen: Screen) = Bundle().apply {
        putString("kind", screen.kind); putString("title", screen.title); putLong("id", screen.id); putString("query", screen.query)
        screen.collection?.let { collection ->
            putLong("collection_id", collection.id); putInt("collection_type", collection.type); putLong("group_id", collection.groupId)
        }
    }
    private fun decodeScreen(bundle: Bundle?): Screen? = runCatching {
        bundle ?: return null
        val kind = bundle.getString("kind") ?: return null
        if (kind !in setOf("home", "library", "settings", "favorites", "recent", "playlist", "search", "more", "detail")) return null
        val id = bundle.getLong("id")
        val title = bundle.getString("title").orEmpty()
        val collection = if (kind == "detail") {
            val collectionId = bundle.getLong("collection_id")
            val type = bundle.getInt("collection_type")
            if (collectionId <= 0 || type !in setOf(1, 2, 5)) return null
            Collection(collectionId, type, title, groupId = bundle.getLong("group_id"))
        } else null
        if (kind in setOf("playlist", "more") && id <= 0) return null
        Screen(kind, title, collection?.id ?: id, collection, bundle.getString("query").orEmpty())
    }.getOrNull()
}
