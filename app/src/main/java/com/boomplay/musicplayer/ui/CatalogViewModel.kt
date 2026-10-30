package com.boomplay.musicplayer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boomplay.musicplayer.BoomPlayApplication
import com.boomplay.musicplayer.library.Collection
import com.boomplay.musicplayer.library.HomeSection
import com.boomplay.musicplayer.library.LibraryRepository
import com.boomplay.musicplayer.library.MusicRepository
import com.boomplay.musicplayer.library.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Screen(val kind: String = "home", val title: String = "", val id: Long = 0, val collection: Collection? = null, val query: String = "")
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
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        (application as BoomPlayApplication).music,
        (application as BoomPlayApplication).library,
    )
    private val mutable = MutableStateFlow(CatalogState())
    val state = mutable.asStateFlow()
    private var task: Job? = null
    private var generation = 0
    private var page = 0
    private val saved = mutableMapOf<String, Pair<CatalogState, Int>>()
    private fun key(screen: Screen) = "${screen.kind}:${screen.id}:${screen.collection?.type}:${screen.query}"
    val stack = mutableListOf<Screen>()
    val scrolls = mutableMapOf<String, android.os.Parcelable?>()

    fun open(screen: Screen, push: Boolean = true) {
        if (screen == mutable.value.screen && mutable.value.initialized) return
        saved[key(mutable.value.screen)] = mutable.value.copy(loading = false) to page
        if (push) stack.add(mutable.value.screen)
        task?.cancel(); generation++
        val cached = saved[key(screen)]
        page = cached?.second ?: 0
        mutable.value = cached?.first?.copy(loading = false) ?: CatalogState(screen)
        if (!mutable.value.initialized) load()
    }
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        open(stack.removeAt(stack.lastIndex), false)
        return true
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
}
