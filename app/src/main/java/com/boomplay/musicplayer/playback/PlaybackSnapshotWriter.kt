package com.boomplay.musicplayer.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Single writer: obsolete pending snapshots coalesce, closing drains the newest value. */
internal class PlaybackSnapshotWriter<T>(scope: CoroutineScope, write: suspend (T) -> Unit) {
    private val pending = Channel<T>(Channel.CONFLATED)
    val job = scope.launch {
        for (snapshot in pending) {
            try { write(snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Persistence failure must not stop playback or later saves. */ }
        }
    }

    fun submit(snapshot: T) { pending.trySend(snapshot) }
    fun close() { pending.close() }
}
