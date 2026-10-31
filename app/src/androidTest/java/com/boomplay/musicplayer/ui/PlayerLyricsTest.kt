package com.boomplay.musicplayer.ui

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boomplay.musicplayer.BoomPlayApplication
import com.boomplay.musicplayer.PlayerActivity
import com.boomplay.musicplayer.R
import com.boomplay.musicplayer.databinding.ViewLyricsBinding
import com.boomplay.musicplayer.library.LyricsParser
import com.boomplay.musicplayer.library.LyricsResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerLyricsTest {
    @Test fun lyricsEntryAndNoSongStateSurviveActivityRecreation() {
        val app = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { app.playback.clearQueue() }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (app.playback.state.value.current != null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        ActivityScenario.launch(PlayerActivity::class.java).use { scenario ->
            onView(withId(R.id.lyrics_toggle)).perform(click())
            onView(withId(R.id.lyrics_message)).check(matches(withText(R.string.no_lyrics)))
            onView(withId(R.id.lyrics_message)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withId(R.id.lyrics_toggle)).check(matches(withText(R.string.artwork)))
            onView(withId(R.id.lyrics_message)).check(matches(isDisplayed()))
            onView(withId(R.id.lyrics_toggle)).perform(click())
            onView(withId(R.id.cover)).check(matches(isDisplayed()))
            onView(withId(R.id.more)).perform(click())
            onView(withText(R.string.audio_quality)).perform(click())
            onView(withText(R.string.quality_low)).perform(click())
            assertEquals(com.boomplay.musicplayer.playback.AudioQuality.LOW, app.listening.quality.value)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.listening.setQuality(com.boomplay.musicplayer.playback.AudioQuality.AUTO) }
        }
    }

    @Test fun timedLyricsHighlightAndSeekWhilePlainLyricsRemainNonSeekable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val app = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
            val context = ContextThemeWrapper(app, R.style.Theme_BoomPlay)
            val binding = ViewLyricsBinding.inflate(LayoutInflater.from(context))
            var seek = -1L
            var retried = false
            val ui = PlayerLyricsUi(binding, { seek = it }, { retried = true })
            ui.render(LyricsUiState(1, result = LyricsResult.Available(LyricsParser.parse("[00:01.00]First\n[00:02.00]Second"))))
            ui.updatePosition(2500)
            binding.root.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            binding.root.layout(0, 0, 900, 900)
            val holder = binding.lyricsList.findViewHolderForAdapterPosition(1)!!
            assertEquals(android.graphics.Typeface.BOLD, (holder.itemView as android.widget.TextView).typeface.style)
            holder.itemView.performClick()
            assertEquals(2000L, seek)
            ui.render(LyricsUiState(2, result = LyricsResult.Available(LyricsParser.parse("Plain lyrics"))))
            binding.root.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            binding.root.layout(0, 0, 900, 900)
            assertFalse(binding.lyricsList.findViewHolderForAdapterPosition(0)!!.itemView.isClickable)
            ui.render(LyricsUiState(3, result = LyricsResult.Failed))
            assertEquals(View.VISIBLE, binding.lyricsRetry.visibility)
            binding.lyricsRetry.performClick()
            assertTrue(retried)
        }
    }
}
