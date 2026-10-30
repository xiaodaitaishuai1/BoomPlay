package com.boomplay.musicplayer.library

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MusicRepositoryTest {
    @Test fun `concurrent business requests share one successful configuration`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            repeat(20) { server.enqueue(MockResponse().setBody("{}")) }
            val repository = MusicRepository(MusicRepository.createApi(server.url("/").toString()))
            kotlinx.coroutines.coroutineScope {
                (1..20).map { async { repository.home() } }.forEach { it.await() }
            }
            assertEquals(21, server.requestCount)
            val requests = (1..21).map { server.takeRequest().requestUrl!!.encodedPath }
            assertEquals(1, requests.count { it.endsWith("startup1") })
        }
    }

    @Test fun `cancelled configuration can be retried immediately`() = runBlocking {
        var calls = 0
        val api = object : BoomplayApi {
            override suspend fun startup(): com.google.gson.JsonObject {
                calls++
                if (calls == 1) throw kotlinx.coroutines.CancellationException("cancelled")
                return com.google.gson.JsonObject()
            }
            override suspend fun home(parameters: Map<String, String>) = com.google.gson.JsonObject()
            override suspend fun collections(parameters: Map<String, String>) = com.google.gson.JsonObject()
            override suspend fun detail(parameters: Map<String, String>) = com.google.gson.JsonObject()
            override suspend fun artistSongs(parameters: Map<String, String>) = com.google.gson.JsonObject()
            override suspend fun search(parameters: Map<String, String>) = com.google.gson.JsonObject()
            override suspend fun track(id: Long) = com.google.gson.JsonObject()
        }
        val repository = MusicRepository(api)
        try { repository.home(); fail("Cancellation swallowed") } catch (_: kotlinx.coroutines.CancellationException) { }
        repository.home()
        assertEquals(2, calls)
    }

    @Test fun `failed configuration retries after cooldown but not during it`() = runBlocking {
        MockWebServer().use { server ->
            var now = 0L
            server.enqueue(MockResponse().setResponseCode(500))
            repeat(2) { server.enqueue(MockResponse().setBody("{}")) }
            val repository = MusicRepository(MusicRepository.createApi(server.url("/").toString()), nowMillis = { now })
            repository.home()
            now = 29_999
            repository.home()
            assertEquals(3, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"staticAddr":"https://retry.example/"}"""))
            server.enqueue(MockResponse().setBody("{}"))
            now = 30_000
            repository.home()
            assertEquals(5, server.requestCount)
            assertEquals("https://retry.example/", repository.staticAddress)
        }
    }
    @Test fun `all supported preferences are read for every regional request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            var region = "CN"
            val repository = MusicRepository(MusicRepository.createApi(server.url("/").toString()), preferredRegion = { region })
            listOf("CN", "HK", "TW").forEach { code ->
                region = code
                server.enqueue(MockResponse().setBody("""{"cacheCountryCode":"US","grps":[]}"""))
                server.enqueue(MockResponse().setBody("""{"musics":[]}"""))
                repository.home(true)
                repository.detail(Collection(42, 1, "Playlist"), 0)
            }
            server.takeRequest()
            listOf("CN", "HK", "TW").forEach { code ->
                assertEquals(code, server.takeRequest().requestUrl!!.queryParameter("cacheCountryCode"))
                assertEquals(code, server.takeRequest().requestUrl!!.queryParameter("cacheCountryCode"))
            }
        }
    }
    @Test fun `home and detail default to CN and server country cannot overwrite preference`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"cacheCountryCode":"US","grps":[]}"""))
            server.enqueue(MockResponse().setBody("""{"musics":[]}"""))
            val repository = MusicRepository(MusicRepository.createApi(server.url("/").toString()))
            repository.home()
            repository.detail(Collection(42, 1, "Playlist"), 0)
            server.takeRequest()
            assertEquals("CN", server.takeRequest().requestUrl!!.queryParameter("cacheCountryCode"))
            assertEquals("CN", server.takeRequest().requestUrl!!.queryParameter("cacheCountryCode"))
        }
    }
    @Test fun `search posts query parameters and carries required headers`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"staticAddr":"https://cdn.example/"}"""))
            server.enqueue(MockResponse().setBody("""{"musics":[{"musicID":"123","name":"Moon","ldSourceID":"music.mp3"}]}"""))
            val repository = MusicRepository(MusicRepository.createApi(server.url("/").toString()))
            val result = repository.search(" Moon light ", 2)
            val startup = server.takeRequest()
            val request = server.takeRequest()
            assertEquals("BoomPlay/7.8.43", startup.getHeader("User-Agent"))
            assertEquals("POST", request.method)
            assertEquals("application/json", request.getHeader("Accept"))
            assertEquals("Moon light", request.requestUrl!!.queryParameter("content"))
            assertEquals("2", request.requestUrl!!.queryParameter("pageIndex"))
            assertEquals("https://cdn.example/music.mp3", result.items.single().ldSource)
            assertFalse(result.hasMore)
        }
    }

    @Test fun `artist detail uses separate songs endpoint and preserves type`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"detailCol":{"colID":42,"colType":2,"name":"Artist"}}"""))
            server.enqueue(MockResponse().setBody("""{"code":"0","data":{"count":1,"musics":[{"musicID":9,"name":"Song"}]}}"""))
            val repository = MusicRepository(MusicRepository.createApi(server.url("/").toString()))
            val result = repository.detail(Collection(42, 2, "Artist"), 0)
            server.takeRequest()
            val detail = server.takeRequest()
            val songs = server.takeRequest()
            assertEquals("2", detail.requestUrl!!.queryParameter("itemType"))
            assertEquals("/BoomPlayer/content/artist-home/v1/getMoreSongs", songs.requestUrl!!.encodedPath)
            assertEquals("0", songs.requestUrl!!.queryParameter("type"))
            assertEquals(9L, result.tracks.items.single().id)
        }
    }

    @Test fun `blank search does not configure or make requests`() = runBlocking {
        MockWebServer().use { server ->
            val repository = MusicRepository(MusicRepository.createApi(server.url("/").toString()))
            assertTrue(repository.search(" ", 0).items.isEmpty())
            assertEquals(0, server.requestCount)
        }
    }
}
