package com.boomplay.musicplayer.playback

import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boomplay.musicplayer.BoomPlayApplication
import com.boomplay.musicplayer.PlayerActivity
import com.boomplay.musicplayer.library.Track
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackSleepTimerTest {
    private val app get() = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!predicate() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(message, predicate())
    }

    @Test fun minuteCountdownStopsBackgroundPlaybackAndCanBeCancelled() {
        val wave = File(app.cacheDir, "countdown-${System.nanoTime()}.wav")
        val samples = 8_000 * 3
        wave.writeBytes(ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array())
        try {
            ActivityScenario.launch(PlayerActivity::class.java).use { scenario ->
                lateinit var future: ListenableFuture<MediaController>
                main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
                val controller = future.get(15, TimeUnit.SECONDS)
                try {
                    main {
                        controller.setMediaItem(PlaybackMediaItems.fromTrack(Track(902002, "Countdown fixture", durationMs = 3_000))
                            .buildUpon().setUri(android.net.Uri.fromFile(wave)).build())
                        controller.repeatMode = Player.REPEAT_MODE_ONE
                        controller.prepare(); controller.play()
                    }
                    await("Local WAV must play") { app.playback.state.value.playing }
                    main { app.playback.setSleepTimer(1) }
                    await("Countdown must arm") { app.listening.sleepTimer.value is SleepTimer.Countdown }
                    main { app.playback.cancelSleepTimer() }
                    await("Countdown cancellation must clear timer") { app.listening.sleepTimer.value == SleepTimer.Off }
                    assertTrue("Cancelling timer must preserve playback", app.playback.state.value.playing)
                    main { app.playback.setSleepTimer(1) }
                    await("Countdown must rearm") { app.listening.sleepTimer.value is SleepTimer.Countdown }
                    scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                    val deadline = android.os.SystemClock.elapsedRealtime() + 75_000
                    while (app.listening.sleepTimer.value != SleepTimer.Off && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
                    assertEquals(SleepTimer.Off, app.listening.sleepTimer.value)
                    main { assertFalse("Service countdown must pause background playback", controller.playWhenReady) }
                } finally {
                    main { controller.pause(); controller.clearMediaItems(); controller.release(); app.playback.cancelSleepTimer() }
                }
            }
        } finally { wave.delete() }
    }

    @Test fun endOfTrackStopsEvenWhenRepeatOneOrRepeatAllEnabled() {
        val wave = File(app.cacheDir, "timer-${System.nanoTime()}.wav")
        val samples = 8_000 * 3
        wave.writeBytes(ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort(0) }
        }.array())
        try {
            ActivityScenario.launch(PlayerActivity::class.java).use {
                lateinit var future: ListenableFuture<MediaController>
                main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
                val controller = future.get(15, TimeUnit.SECONDS)
                try {
                    for (mode in listOf(Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)) {
                        main {
                            controller.pause()
                            controller.setMediaItem(PlaybackMediaItems.fromTrack(Track(902001, "Timer fixture", durationMs = 3_000))
                                .buildUpon().setUri(android.net.Uri.fromFile(wave)).build())
                            controller.repeatMode = mode
                            controller.prepare()
                            controller.play()
                        }
                        await("Local WAV must actually play before timer assertion") { app.playback.state.value.playing }
                        main { app.playback.stopAfterCurrentTrack() }
                        await("End-of-track timer must arm") { app.listening.sleepTimer.value == SleepTimer.EndOfTrack }
                        await("End-of-track timer must clear at natural completion with repeat=$mode") { app.listening.sleepTimer.value == SleepTimer.Off }
                        await("Playback must pause after natural completion") { !app.playback.state.value.playing }
                        Thread.sleep(300)
                        assertFalse(app.playback.state.value.playing)
                        main { assertFalse(controller.playWhenReady) }
                    }
                } finally {
                    main { controller.pause(); controller.clearMediaItems(); controller.release(); app.playback.cancelSleepTimer() }
                }
            }
        } finally { wave.delete() }
    }
}
