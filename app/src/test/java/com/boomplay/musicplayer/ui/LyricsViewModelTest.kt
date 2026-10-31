package com.boomplay.musicplayer.ui

import androidx.lifecycle.SavedStateHandle
import com.boomplay.musicplayer.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsViewModelTest {
    @Test fun `late old song cannot replace current lyrics and progress does not reload`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls = 0
            val repository = object : LyricsRepository {
                override suspend fun lyrics(track: Track): LyricsResult {
                    calls++
                    delay(if (track.id == 1L) 1000 else 10)
                    return LyricsResult.Available(LyricsParser.parse(track.title))
                }
            }
            val model = LyricsViewModel(SavedStateHandle(), repository)
            model.select(Track(1, "Old")); runCurrent()
            model.select(Track(2, "New")); advanceUntilIdle()
            assertEquals("New", (model.state.value.result as LyricsResult.Available).lyrics.lines.single().text)
            repeat(100) { model.select(Track(2, "New")) }
            advanceUntilIdle()
            assertEquals(2, calls)
            model.select(null)
            assertEquals(LyricsResult.Unavailable, model.state.value.result)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `lyrics visibility survives saved state reconstruction`() {
        val state = SavedStateHandle()
        val repository = object : LyricsRepository { override suspend fun lyrics(track: Track) = LyricsResult.Unavailable }
        val first = LyricsViewModel(state, repository)
        first.setVisible(true)
        val restored = LyricsViewModel(SavedStateHandle(mapOf("lyrics_visible" to state.get<Boolean>("lyrics_visible"))), repository)
        assertTrue(restored.visible.value)
    }

    @Test fun `manual scroll suspends following until timeout or explicit resume`() {
        val follow = LyricsFollowState()
        follow.onManualScroll(1000)
        assertFalse(follow.shouldFollow(5999))
        assertTrue(follow.shouldFollow(6000))
        follow.onManualScroll(9000)
        follow.resume()
        assertTrue(follow.shouldFollow(9000))
    }
}
