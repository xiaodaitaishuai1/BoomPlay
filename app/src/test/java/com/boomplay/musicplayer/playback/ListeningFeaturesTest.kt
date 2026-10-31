package com.boomplay.musicplayer.playback

import com.boomplay.musicplayer.library.Track
import org.junit.Assert.*
import org.junit.Test

class ListeningFeaturesTest {
    @Test fun `quality selects preferred source and deduplicates fallback URLs`() {
        val track = Track(id = 1, title = "Track", hdSource = "high", mdSource = "mid", ldSource = "mid")
        assertEquals(listOf("https://cdn/mid", "https://cdn/high"), PlaybackUtils.audioUrls(track, "https://cdn", AudioQuality.MEDIUM))
        assertEquals("https://cdn/high", PlaybackUtils.audioUrls(track, "https://cdn", AudioQuality.HIGH).first())
    }

    @Test fun `automatic quality respects constrained and mobile connections`() {
        assertEquals(AudioQuality.LOW, ListeningUtils.autoQuality(connected = false, constrained = false, wifi = true))
        assertEquals(AudioQuality.LOW, ListeningUtils.autoQuality(connected = true, constrained = true, wifi = true))
        assertEquals(AudioQuality.MEDIUM, ListeningUtils.autoQuality(connected = true, constrained = false, wifi = false))
        assertEquals(AudioQuality.HIGH, ListeningUtils.autoQuality(connected = true, constrained = false, wifi = true))
    }

    @Test fun `source recovery tries alternatives once then retries final source only once`() {
        val attempts = AudioSourceAttempts(listOf("high", "mid", "low"))
        assertEquals("high", attempts.current())
        assertTrue(attempts.advance()); assertEquals("mid", attempts.current())
        assertTrue(attempts.advance()); assertEquals("low", attempts.current())
        assertTrue(attempts.advance()); assertEquals("low", attempts.current())
        assertFalse(attempts.advance())
    }

    @Test fun `sleep countdown uses monotonic deadline and validates minutes`() {
        assertEquals(SleepTimer.Countdown(901_000), ListeningUtils.countdown(15, 1_000))
        assertTrue(ListeningUtils.expired(SleepTimer.Countdown(5_000), 5_000))
        assertFalse(ListeningUtils.expired(SleepTimer.EndOfTrack, Long.MAX_VALUE))
        assertFalse(ListeningUtils.expired(SleepTimer.Off, Long.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { ListeningUtils.countdown(0, 0) }
    }
}
