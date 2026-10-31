package com.boomplay.musicplayer.playback

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningSettingsTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("listening_test_$name", mode)
    }

    @After fun clear() { context.getSharedPreferences("listening_settings", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun qualitySurvivesRecreationButSleepTimerDoesNot() {
        val first = ListeningSettings(context)
        first.setQuality(AudioQuality.LOW)
        first.setTimer(SleepTimer.EndOfTrack)
        val recreated = ListeningSettings(context)
        assertEquals(AudioQuality.LOW, recreated.quality.value)
        assertEquals(SleepTimer.Off, recreated.sleepTimer.value)
        recreated.setQuality(AudioQuality.AUTO)
        assertEquals(AudioQuality.AUTO, ListeningSettings(context).quality.value)
    }

    @Test fun corruptQualityFallsBackToAutomatic() {
        context.getSharedPreferences("listening_settings", Context.MODE_PRIVATE).edit().putString("quality", "broken").commit()
        assertEquals(AudioQuality.AUTO, ListeningSettings(context).quality.value)
    }
}
