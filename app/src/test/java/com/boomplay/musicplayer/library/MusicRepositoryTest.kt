package com.boomplay.musicplayer.library

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MusicRepositoryTest {
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
