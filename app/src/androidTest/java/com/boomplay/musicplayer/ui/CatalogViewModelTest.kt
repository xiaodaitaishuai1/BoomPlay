package com.boomplay.musicplayer.ui

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boomplay.musicplayer.BoomPlayApplication
import com.boomplay.musicplayer.library.BoomplayApi
import com.boomplay.musicplayer.library.Collection
import com.boomplay.musicplayer.library.LibraryDatabase
import com.boomplay.musicplayer.library.LibraryRepository
import com.boomplay.musicplayer.library.MusicRepository
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real repository parser and ViewModel state machine without network calls. */
@RunWith(AndroidJUnit4::class)
class CatalogViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
    private lateinit var database: LibraryDatabase
    private lateinit var library: LibraryRepository
    private lateinit var model: CatalogViewModel
    private val store = ViewModelStore()

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
        library = LibraryRepository(database)
    }
    @After fun cleanup() {
        onMain { store.clear() }
        database.close()
    }

    @Test fun canceledInitialLoadCanResumeWhenReturningToCachedPage() {
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            val api = FakeApi().apply { homeResponse = {
                if (requests.incrementAndGet() == 1) {
                    started.complete(Unit)
                    awaitCancellation()
                } else json("""{"grps":[]}""")
            } }
            createModel(api)
            onMain { model.load() }
            withTimeout(5_000) { started.await() }
            onMain { model.open(Screen("library")) }
            settled { it.screen.kind == "library" }
            onMain { model.open(Screen()) }
            val restored = settled { it.screen.kind == "home" }
            assertFalse(restored.loading)
            assertFalse(restored.failed)
            assertEquals(2, requests.get())
        }
    }

    @Test fun failedRefreshKeepsRowsButRetryReplacesFirstPageAndKeepsPagination() {
        runBlocking {
            val zeroPageRequests = AtomicInteger()
            val api = FakeApi().apply { detailResponse = { parameters ->
                val page = parameters.getValue("pageIndex").toInt()
                if (page == 0 && zeroPageRequests.incrementAndGet() == 2) throw IOException("Refresh interrupted")
                songs(page, if (zeroPageRequests.get() >= 3) "Fresh" else "Old")
            } }
            createModel(api)
            onMain { model.open(detailScreen()) }
            assertEquals(20, settled().tracks.size)
            onMain { model.load() }
            assertEquals(40, settled().tracks.size)
            onMain { model.load(refresh = true) }
            val failed = withTimeout(5_000) { model.state.first { it.failed && !it.loading } }
            assertEquals(40, failed.tracks.size)
            onMain { model.load() }
            val retried = settled()
            assertFalse(retried.failed)
            assertEquals((1L..20L).toList(), retried.tracks.map { it.id })
            assertEquals("Fresh 1", retried.tracks.first().title)
            assertTrue(retried.more)
            onMain { model.load() }
            assertEquals(40, settled().tracks.size)
        }
    }

    @Test fun enrichedDetailStillMatchesOriginalNavigationCacheKey() {
        runBlocking {
            val calls = AtomicInteger()
            val api = FakeApi().apply { detailResponse = { page ->
                calls.incrementAndGet()
                songs(page.getValue("pageIndex").toInt(), "Song").apply {
                    add("detailCol", json("""{"colID":42,"colType":1,"name":"Updated title","descr":"New metadata","bigIconID":"cover.jpg"}"""))
                }
            } }
            createModel(api)
            val initialRoute = detailScreen()
            onMain { model.open(initialRoute) }
            val first = settled()
            assertEquals("New metadata", first.screen.collection?.description)
            onMain { model.open(Screen("library")) }
            settled { it.screen.kind == "library" }
            onMain { model.open(initialRoute) }
            val cached = settled { it.screen.kind == "detail" }
            assertEquals("New metadata", cached.screen.collection?.description)
            assertEquals(20, cached.tracks.size)
            assertEquals(1, calls.get())
        }
    }

    @Test fun repeatedPageStopsLoadingWithoutDuplicatingTracks() {
        runBlocking {
            val api = FakeApi().apply { detailResponse = { songs(0, "Song") } }
            createModel(api)
            onMain { model.open(detailScreen()) }
            assertTrue(settled().more)
            onMain { model.load() }
            val repeated = settled()
            assertEquals(20, repeated.tracks.size)
            assertFalse(repeated.more)
        }
    }

    private fun createModel(api: BoomplayApi) = onMain {
        model = CatalogViewModel(app, MusicRepository(api), library)
        store.put("catalog-test", model)
    }
    private suspend fun settled(predicate: (CatalogState) -> Boolean = { true }): CatalogState =
        withTimeout(5_000) { model.state.first { it.initialized && !it.loading && predicate(it) } }

    private fun detailScreen() = Screen("detail", "Original title", 42, Collection(42, 1, "Original title"))
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private class FakeApi : BoomplayApi {
        var homeResponse: suspend (Map<String, String>) -> JsonObject = { json("""{"grps":[]}""") }
        var detailResponse: suspend (Map<String, String>) -> JsonObject = { songs(0, "Song") }
        override suspend fun startup() = JsonObject()
        override suspend fun home(parameters: Map<String, String>) = homeResponse(parameters)
        override suspend fun collections(parameters: Map<String, String>) = JsonObject()
        override suspend fun detail(parameters: Map<String, String>) = detailResponse(parameters)
        override suspend fun artistSongs(parameters: Map<String, String>) = JsonObject()
        override suspend fun search(parameters: Map<String, String>) = JsonObject()
        override suspend fun track(id: Long) = JsonObject()
    }

    companion object {
        private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
        private fun songs(page: Int, title: String): JsonObject {
            val rows = (page * 20 + 1..page * 20 + 20).joinToString { """{"musicID":$it,"name":"$title $it"}""" }
            return json("""{"musics":[$rows]}""")
        }
    }
}
