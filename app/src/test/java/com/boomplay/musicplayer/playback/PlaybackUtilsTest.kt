package com.boomplay.musicplayer.playback

import com.boomplay.musicplayer.library.Track
import org.junit.Assert.*
import org.junit.Test

class PlaybackUtilsTest {
    @Test fun `prefers HD and preserves absolute source URLs`() {
        val track = Track(1, "Song", hdSource = "https://cdn.test/song.mp3", ldSource = "low.mp3")
        assertEquals("https://cdn.test/song.mp3", PlaybackUtils.audioUrl(track, "https://source.test/"))
    }

    @Test fun `joins medium relative paths without repeated slashes`() {
        assertEquals("https://source.test/group/song.mp3", PlaybackUtils.audioUrl(
            Track(1, "Song", mdSource = "/group/song.mp3"), "https://source.test/",
        ))
    }

    @Test fun `missing sources remain unavailable`() {
        assertNull(PlaybackUtils.audioUrl(Track(1, "Song"), "https://source.test/"))
    }

    @Test fun `seek clamps both ends and handles unknown duration`() {
        assertEquals(0L, PlaybackUtils.clampPosition(-10, 500))
        assertEquals(500L, PlaybackUtils.clampPosition(1000, 500))
        assertEquals(1000L, PlaybackUtils.clampPosition(1000, 0))
    }

    @Test fun `enqueue next and append use valid insertion positions`() {
        assertEquals(1, PlaybackUtils.insertionIndex(2, 0, true))
        assertEquals(2, PlaybackUtils.insertionIndex(2, 0, false))
        assertEquals(0, PlaybackUtils.insertionIndex(0, -1, true))
    }
}
