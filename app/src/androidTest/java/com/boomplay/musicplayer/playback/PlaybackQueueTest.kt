package com.boomplay.musicplayer.playback

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boomplay.musicplayer.BoomPlayApplication
import com.boomplay.musicplayer.PlayerActivity
import com.boomplay.musicplayer.library.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackQueueTest {
    private val app get() = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!predicate() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("Queue update did not arrive", predicate())
    }

    @Test fun occurrenceIdentityIsSharedByMetadataAndSourceUri() {
        val track = Track(901001, "Duplicate")
        val first = PlaybackMediaItems.fromTrack(track)
        val second = PlaybackMediaItems.fromTrack(track)
        assertEquals("901001", first.mediaId)
        assertEquals("901001", first.localConfiguration!!.uri.lastPathSegment)
        assertEquals(PlaybackMediaItems.occurrenceId(first), first.localConfiguration!!.uri.getQueryParameter("occurrence"))
        assertNotEquals(PlaybackMediaItems.occurrenceId(first), PlaybackMediaItems.occurrenceId(second))
    }

    @Test fun duplicateOccurrencesMoveAndRemoveIndependently() {
        ActivityScenario.launch(PlayerActivity::class.java).use {
            val first = Track(901001, "Duplicate", hdSource = "http://127.0.0.1:1/audio.mp3")
            val second = first.copy(id = 901002, title = "Second")
            main { app.playback.play(listOf(first, second, first)) }
            await { app.playback.state.value.queue.size == 3 }
            var ids = emptyList<String>()
            main { ids = app.playback.queueEntries().map { it.first } }
            assertEquals(3, ids.toSet().size)
            main { app.playback.moveQueueItem(2, 0) }
            await { app.playback.state.value.queue.map { it.id } == listOf(901001L, 901001L, 901002L) }
            main { assertEquals(listOf(ids[2], ids[0], ids[1]), app.playback.queueEntries().map { it.first }) }
            main { app.playback.removeQueueItem(1) }
            await { app.playback.state.value.queue.size == 2 }
            main { assertEquals(listOf(ids[2], ids[1]), app.playback.queueEntries().map { it.first }) }
            main { app.playback.clearQueue() }
            await { app.playback.state.value.queue.isEmpty() }
            assertNull(app.playback.state.value.current)
            assertFalse(app.playback.state.value.playing)
        }
    }
}
