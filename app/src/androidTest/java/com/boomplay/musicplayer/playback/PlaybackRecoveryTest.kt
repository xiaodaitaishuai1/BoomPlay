package com.boomplay.musicplayer.playback

import android.content.ComponentName
import android.os.SystemClock
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
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackRecoveryTest {
    private val app get() = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        assertTrue(message, predicate())
    }

    @Test fun httpFailureFallsBackAndPauseCancelsPendingRecovery() {
        LoopbackAudio().use { server ->
            ActivityScenario.launch(PlayerActivity::class.java).use {
                lateinit var future: ListenableFuture<MediaController>
                main { future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
                val controller = future.get(15, TimeUnit.SECONDS)
                val originalQuality = app.listening.quality.value
                try {
                    main {
                        app.listening.setQuality(AudioQuality.HIGH)
                        controller.repeatMode = Player.REPEAT_MODE_OFF
                        app.playback.play(listOf(Track(903001, "Fallback fixture", durationMs = 20_000,
                            hdSource = server.url("bad"), mdSource = server.url("good"))))
                    }
                    await("High source should fail then medium should play") {
                        server.paths.contains("/good") && app.playback.state.value.playing
                    }
                    assertEquals(listOf("/bad", "/good"), server.paths.take(2))
                    assertEquals(903001, app.playback.state.value.current?.id?.toInt())
                    main { app.playback.clearQueue() }

                    main {
                        app.playback.play(listOf(Track(903002, "Pause fixture", hdSource = server.url("held"), mdSource = server.url("forbidden"))))
                    }
                    assertTrue("Expected held HTTP request", server.heldRequest.await(10, TimeUnit.SECONDS))
                    main { controller.pause() }
                    server.releaseHeld.countDown()
                    Thread.sleep(1_500)
                    assertFalse("Pause must suppress automatic source recovery", server.paths.contains("/forbidden"))
                    main { assertFalse(controller.playWhenReady) }
                } finally {
                    server.releaseHeld.countDown()
                    main { controller.pause(); controller.clearMediaItems(); controller.release(); app.listening.setQuality(originalQuality) }
                }
            }
        }
    }

    @Test fun allFailedSourcesExhaustFiniteBudgetWithoutSkippingTrack() {
        LoopbackAudio().use { server ->
            ActivityScenario.launch(PlayerActivity::class.java).use {
                val original = app.listening.quality.value
                try {
                    main {
                        app.listening.setQuality(AudioQuality.HIGH)
                        app.playback.play(listOf(
                            Track(903003, "Failures fixture", hdSource = server.url("bad"), mdSource = server.url("bad2")),
                            Track(903004, "Do not skip", hdSource = server.url("good")),
                        ))
                    }
                    await("Finite source budget should finish with visible error") {
                        server.paths.count { it == "/bad2" } >= 2 && app.playback.state.value.error != null && !app.playback.state.value.playing
                    }
                    Thread.sleep(1_000)
                    assertEquals(listOf("/bad", "/bad2", "/bad2"), server.paths.toList())
                    assertEquals(903003, app.playback.state.value.current?.id?.toInt())
                } finally { main { app.playback.clearQueue(); app.listening.setQuality(original) } }
            }
        }
    }

    private class LoopbackAudio : AutoCloseable {
        private val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val heldRequest = CountDownLatch(1)
        val releaseHeld = CountDownLatch(1)
        private val wav = ByteBuffer.allocate(44 + 8_000 * 20 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            val samples = 8_000 * 20
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }.array()
        private val worker = thread(isDaemon = true, name = "audio-fixture") {
            while (!server.isClosed) runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    while (!input.readLine().isNullOrEmpty()) Unit
                    paths.add(path)
                    if (path == "/held") { heldRequest.countDown(); releaseHeld.await(10, TimeUnit.SECONDS) }
                    val success = path == "/good"
                    val body = if (success) wav else ByteArray(0)
                    val header = "HTTP/1.1 ${if (success) "200 OK" else "500 Failure"}\r\nContent-Type: audio/wav\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
                }
            }
        }
        fun url(path: String) = "http://127.0.0.1:${server.localPort}/$path"
        override fun close() { releaseHeld.countDown(); server.close(); worker.join(1_000) }
    }
}
