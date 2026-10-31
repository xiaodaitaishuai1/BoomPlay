package com.boomplay.musicplayer.library

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun `invalid timestamps fall back to readable text`() {
        val result = LyricsParser.parse("[00:99]Still readable\n[bad]Also readable")
        assertFalse(result.timed)
        assertEquals(2, result.lines.size)
        assertTrue(result.lines.first().text.contains("Still readable"))
    }

    @Test fun `content redirect cannot load a third party provider`() = runBlocking {
        MockWebServer().use { official -> MockWebServer().use { external ->
            official.enqueue(MockResponse().setBody("""{"lyricID":"lyrics.lrc"}"""))
            official.enqueue(MockResponse().setResponseCode(302).setHeader("Location", external.url("/external.lrc")))
            external.enqueue(MockResponse().setBody("[00:00]External lyrics"))
            val repository = OfficialLyricsRepository(OfficialLyricsRepository.createApi(official.url("/").toString()), { official.url("/").toString() })
            assertFalse(repository.lyrics(Track(42, "Song")) is LyricsResult.Available)
            assertEquals(0, external.requestCount)
        } }
    }

    @Test fun `timestamps fractions multiple tags offset and sorting are parsed`() {
        val lyrics = LyricsParser.parse("[ar:Artist]\n[offset:-500]\n[00:03.25][00:05.250]Echo\n[00:01.2]Start")
        assertEquals(listOf(700L, 2750L, 4750L), lyrics.lines.map { it.timeMs })
        assertEquals(listOf("Start", "Echo", "Echo"), lyrics.lines.map { it.text })
        assertEquals(-1, lyrics.activeIndex(699))
        assertEquals(1, lyrics.activeIndex(3000))
    }

    @Test fun `plain text remains readable and metadata alone is empty`() {
        val lyrics = LyricsParser.parse("First line\nSecond line")
        assertFalse(lyrics.timed)
        assertEquals(2, lyrics.lines.size)
        assertEquals(-1, lyrics.activeIndex(9000))
        assertTrue(LyricsParser.parse("[ar:Someone]\n[ti:Song]").lines.isEmpty())
    }

    @Test fun `official lookup uses query then source ID and caches parsed result`() = runBlocking {
        MockWebServer().use { server ->
            // Contract fixtures match official Api.getLyricID -> lyricID and sourceAddr + ID.
            server.enqueue(MockResponse().setBody("""{"musicID":"42","lyricID":"lyrics/test.lrc"}"""))
            server.enqueue(MockResponse().setBody("[00:01.00]Hello"))
            val repository = OfficialLyricsRepository(OfficialLyricsRepository.createApi(server.url("/").toString()), { server.url("/").toString() })
            val track = Track(42, "A song")
            val first = repository.lyrics(track)
            assertTrue(first is LyricsResult.Available)
            assertEquals(first, repository.lyrics(track))
            assertEquals(2, server.requestCount)
            val lookup = server.takeRequest()
            assertEquals("GET", lookup.method)
            assertEquals("42", lookup.requestUrl!!.queryParameter("musicID"))
            assertEquals("A song", lookup.requestUrl!!.queryParameter("name"))
            assertEquals("/lyrics/test.lrc", server.takeRequest().path)
        }
    }

    @Test fun `empty lookup does not invent a content endpoint`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val repository = OfficialLyricsRepository(OfficialLyricsRepository.createApi(server.url("/").toString()))
            assertEquals(LyricsResult.Unavailable, repository.lyrics(Track(42, "Song")))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `server errors and malformed data become retryable unavailable lyrics`() = runBlocking {
        MockWebServer().use { server ->
            val repository = OfficialLyricsRepository(OfficialLyricsRepository.createApi(server.url("/").toString()))
            server.enqueue(MockResponse().setResponseCode(500))
            assertEquals(LyricsResult.Failed, repository.lyrics(Track(42, "Song")))
            server.enqueue(MockResponse().setBody("{bad json"))
            assertEquals(LyricsResult.Failed, repository.lyrics(Track(42, "Song")))
        }
    }

    @Test fun `cancellation propagates and next request can retry`() = runBlocking {
        var cancelled = true
        val api = object : OfficialLyricsApi {
            override suspend fun lookup(id: Long, name: String): com.google.gson.JsonObject {
                if (cancelled) throw kotlinx.coroutines.CancellationException("song changed")
                return com.google.gson.JsonObject()
            }
            override suspend fun content(url: String): okhttp3.ResponseBody = error("No content expected")
        }
        val repository = OfficialLyricsRepository(api)
        try { repository.lyrics(Track(1, "Song")); fail("Cancellation must propagate") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        cancelled = false
        assertEquals(LyricsResult.Unavailable, repository.lyrics(Track(1, "Song")))
    }

    @Test fun `official lookup cannot redirect content to a different provider`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"lyricID":"https://thirdparty.example/lyrics"}"""))
            val repository = OfficialLyricsRepository(OfficialLyricsRepository.createApi(server.url("/").toString()))
            assertEquals(LyricsResult.Unavailable, repository.lyrics(Track(1, "Song")))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `parsed lyrics cache retains only most recent 32 songs`() = runBlocking {
        MockWebServer().use { server ->
            repeat(34) {
                server.enqueue(MockResponse().setBody("""{"lyricID":"lyrics.lrc"}"""))
                server.enqueue(MockResponse().setBody("[00:00]Line"))
            }
            val repository = OfficialLyricsRepository(OfficialLyricsRepository.createApi(server.url("/").toString()), { server.url("/").toString() })
            (1L..33L).forEach { repository.lyrics(Track(it, "Song")) }
            repository.lyrics(Track(33, "Song"))
            assertEquals(66, server.requestCount)
            repository.lyrics(Track(1, "Song"))
            assertEquals(68, server.requestCount)
        }
    }
}
