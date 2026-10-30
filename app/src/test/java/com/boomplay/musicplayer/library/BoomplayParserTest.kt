package com.boomplay.musicplayer.library

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class BoomplayParserTest {
    private val parser = BoomplayParser()

    @Test fun `mixed numeric types and missing metadata parse safely`() {
        val track = parser.track(JsonParser.parseString("""{"musicID":"12","name":"Song","deaution":"01:02:03","hdSourceID":null,"ldSourceID":"group/a.mp3","beArtist":{"colID":"8","name":"Artist"}}""").asJsonObject)
        assertEquals(12L, track.id)
        assertEquals(3_723_000L, track.durationMs)
        assertEquals("Artist", track.artist.name)
        assertEquals("https://source.boomplaymusic.com/group/a.mp3", track.ldSource)
        assertEquals("", track.hdSource)
        assertEquals("", track.album.name)
    }

    @Test fun `home filters unsupported and empty sections while retaining order`() {
        val response = JsonParser.parseString("""{"grps":[{"colGrpID":1,"name":"Slides","layout":1,"value":"Slides","cols":[{"colID":"9","colType":1,"name":"Mix","adImgID":"cover.jpg"}]},{"colGrpID":2,"value":"Videos","cols":[{"colID":2}]},{"colGrpID":3,"value":"Radio"},{"colGrpID":4,"value":"Artists","cols":[]},{"colGrpID":5,"value":"Col","cols":[{"colID":8,"colType":5,"name":"Album"}]}]}""").asJsonObject
        val sections = parser.home(response)
        assertEquals(listOf(1L, 5L), sections.map { it.id })
        assertEquals("https://source.boomplaymusic.com/cover.jpg", sections.first().items.first().cover)
        assertEquals(5, sections.last().items.first().type)
    }

    @Test fun `resource urls keep absolute urls and omit blank fields`() {
        assertEquals("https://cdn.example/song.mp3", parser.url("https://cdn.example/song.mp3"))
        assertEquals("", parser.url(" "))
        parser.staticAddress = "https://cdn.example/root/"
        assertEquals("https://cdn.example/root/group/song.mp3", parser.url("/group/song.mp3"))
    }

    @Test fun `artist response unwraps data and detects final page`() {
        val json = JsonParser.parseString("""{"code":"0","data":{"count":"1","musics":[{"musicID":3,"name":"Track"}]}}""").asJsonObject
        val page = parser.tracksPage(json, 0)
        assertEquals(3L, page.items.single().id)
        assertFalse(page.hasMore)
    }

    @Test fun `failed response does not silently become empty content`() {
        try {
            parser.body(JsonParser.parseString("""{"code":"403","desc":"Restricted"}""").asJsonObject)
            fail("Expected service error")
        } catch (expected: BoomplayException) {
            assertEquals("Restricted", expected.message)
        }
    }
}
