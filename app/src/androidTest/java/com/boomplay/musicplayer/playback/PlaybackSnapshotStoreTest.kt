package com.boomplay.musicplayer.playback

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boomplay.musicplayer.library.Track
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSnapshotStoreTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("test_$name", mode)
    }
    private val store = PlaybackSnapshotStore(context)

    @Before fun clearBefore() = clear()
    @After fun clearAfter() = clear()
    private fun clear() { context.getSharedPreferences("playback_snapshot", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun queuePositionAndModesSurviveStoreRecreation() {
        val expected = PlaybackSnapshot(
            listOf(Track(1, "One"), Track(2, "Two", hdSource = "https://cdn.test/2.mp3")),
            index = 1, positionMs = 42_000, shuffle = true, repeatMode = 2,
        )
        store.save(expected)
        assertEquals(expected, PlaybackSnapshotStore(context).load())
    }

    @Test fun oldServiceCannotOverwriteNewServiceSnapshot() {
        val newerStore = PlaybackSnapshotStore(context)
        val latest = PlaybackSnapshot(listOf(Track(2, "New service")), positionMs = 5_000)
        newerStore.save(latest)
        store.save(PlaybackSnapshot(listOf(Track(1, "Old service"))))
        assertEquals(latest, newerStore.load())
    }

    @Test fun creatingNewStoreDoesNotDiscardOldServicesFinalSave() {
        val newerStore = PlaybackSnapshotStore(context)
        val final = PlaybackSnapshot(listOf(Track(1, "Final")), positionMs = 42_000)
        store.save(final)
        assertEquals(final, newerStore.load())
    }

    @Test fun emptyAndInvalidQueuesAreNotRestored() {
        store.save(PlaybackSnapshot())
        assertNull(store.load())
        store.save(PlaybackSnapshot(listOf(Track(1, "One")), index = 5))
        assertNull(store.load())
    }

    @Test fun damagedSnapshotDoesNotCrashStartup() {
        context.getSharedPreferences("playback_snapshot", Context.MODE_PRIVATE)
            .edit().putString("snapshot", "{broken").commit()
        assertNull(store.load())
        context.getSharedPreferences("playback_snapshot", Context.MODE_PRIVATE)
            .edit().putString("snapshot", "{\"queue\":[null],\"index\":0,\"positionMs\":0}").commit()
        assertNull(store.load())
        context.getSharedPreferences("playback_snapshot", Context.MODE_PRIVATE)
            .edit().putString("snapshot", "{\"queue\":[{\"id\":1,\"title\":\"x\"}],\"index\":0,\"positionMs\":0}").commit()
        assertNull(store.load())
    }
}
