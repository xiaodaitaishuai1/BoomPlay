package com.boomplay.musicplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.boomplay.musicplayer.library.Track
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStatePublisherTest {
    @Test fun `thousand song queue is decoded once while polling follows subscriptions and playing`() = runTest {
        val items = (1L..1000L).map { MediaItem.Builder().setMediaId(it.toString()).build() }
        var position = 0L
        var playing = true
        var reads = 0
        var decodes = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args!![0] as Int]
                "getCurrentMediaItem" -> items[0]
                "getCurrentMediaItemIndex" -> 0
                "getIsPlaying", "isPlaying" -> playing
                "getPlaybackState" -> Player.STATE_READY
                "getCurrentPosition" -> { reads++; position }
                "getDuration" -> 60_000L
                "getShuffleModeEnabled" -> false
                "getRepeatMode" -> 0
                "getPlayerError" -> null
                else -> throw AssertionError("Unexpected player read ${method.name}")
            }
        } as Player
        val publisher = PlaybackStatePublisher(backgroundScope) { item -> decodes++; Track(item.mediaId.toLong(), "Song") }
        publisher.attach(player)
        runCurrent()
        assertEquals(1000, decodes)
        publisher.publish(refreshCurrent = true)
        assertEquals("A current-item metadata event must reuse unchanged queue items", 1000, decodes)
        val initialReads = reads
        advanceTimeBy(2_000); runCurrent()
        assertEquals(initialReads, reads)
        val collector = backgroundScope.launch { publisher.state.collect {} }
        runCurrent()
        position = 15_000
        advanceTimeBy(500); runCurrent()
        assertEquals(15_000, publisher.state.value.positionMs)
        assertEquals(1000, decodes)
        playing = false
        publisher.publish()
        val pausedReads = reads
        advanceTimeBy(2_000); runCurrent()
        assertEquals(pausedReads, reads)
        playing = true
        publisher.publish()
        collector.cancel(); runCurrent()
        val hiddenReads = reads
        advanceTimeBy(2_000); runCurrent()
        assertEquals(hiddenReads, reads)
        position = 35_000
        backgroundScope.launch { publisher.state.collect {} }
        runCurrent()
        assertEquals("Resubscribing publishes the current position immediately", 35_000, publisher.state.value.positionMs)
        assertEquals(1000, decodes)
    }
}
