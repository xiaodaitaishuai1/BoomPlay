package com.boomplay.musicplayer.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackPersistenceTest {
    @Test fun `pending writes coalesce and final write drains after close`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<Int>()
        val writer = PlaybackSnapshotWriter<Int>(backgroundScope) { value ->
            if (value == 1) { started.complete(Unit); release.await() }
            saved += value
        }
        writer.submit(1)
        runCurrent()
        assertTrue(started.isCompleted)
        writer.submit(2)
        writer.submit(3)
        writer.close()
        release.complete(Unit)
        writer.job.join()
        assertEquals(listOf(1, 3), saved)
    }

    @Test fun `controller accepts own package or trusted controllers only`() {
        assertTrue(PlaybackUtils.acceptsController("app", "app", false))
        assertTrue(PlaybackUtils.acceptsController("app", "system", true))
        assertFalse(PlaybackUtils.acceptsController("app", "other", false))
    }
}
