package com.boomplay.musicplayer.playback

import android.content.Context
import androidx.core.content.edit
import com.boomplay.musicplayer.library.Track
import com.google.gson.Gson
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class PlaybackSnapshot(
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)

internal class PlaybackSnapshotStore(context: Context) {
    private val preferences = context.getSharedPreferences("playback_snapshot", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val owner = synchronized(owners) { owners.getOrPut(preferences) { Owner() } }
    private val generation = nextGeneration.incrementAndGet()

    fun load(): PlaybackSnapshot? = runCatching {
        preferences.getString("snapshot", null)?.let { gson.fromJson(it, PlaybackSnapshot::class.java) }
            ?.takeIf { snapshot ->
                val tracks: List<Track?> = snapshot.queue
                tracks.isNotEmpty() && snapshot.index in tracks.indices &&
                    tracks.all { track -> track != null && track.id > 0 && validTrack(track) } && snapshot.positionMs >= 0
            }
    }.getOrNull()

    fun save(snapshot: PlaybackSnapshot) {
        val json = gson.toJson(snapshot)
        // Only background writers acquire this disk lock. Creating a service never waits for I/O.
        synchronized(owner.writeLock) {
            if (owner.latestGeneration > generation) return
            owner.latestGeneration = generation
            preferences.edit(commit = true) { putString("snapshot", json) }
        }
    }

    private class Owner {
        var latestGeneration = 0L
        val writeLock = Any()
    }

    private companion object {
        val owners = WeakHashMap<android.content.SharedPreferences, Owner>()
        val nextGeneration = AtomicLong()
        // Gson can bypass Kotlin constructors and populate non-null fields with null.
        @Suppress("SENSELESS_COMPARISON")
        fun validTrack(track: Track): Boolean = track.title != null && track.cover != null &&
            track.hdSource != null && track.mdSource != null && track.ldSource != null &&
            track.artist != null && track.artist.name != null && track.artist.cover != null &&
            track.album != null && track.album.name != null && track.album.cover != null
    }
}
