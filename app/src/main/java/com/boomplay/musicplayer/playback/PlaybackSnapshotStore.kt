package com.boomplay.musicplayer.playback

import android.content.Context
import com.boomplay.musicplayer.library.Track
import com.google.gson.Gson

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

    fun load(): PlaybackSnapshot? = runCatching {
        preferences.getString("snapshot", null)?.let { gson.fromJson(it, PlaybackSnapshot::class.java) }
            ?.takeIf { snapshot ->
                val tracks: List<Track?> = snapshot.queue
                tracks.isNotEmpty() && snapshot.index in tracks.indices &&
                    tracks.all { it != null && it.id > 0 } && snapshot.positionMs >= 0
            }
    }.getOrNull()

    fun save(snapshot: PlaybackSnapshot) {
        preferences.edit().putString("snapshot", gson.toJson(snapshot)).apply()
    }
}
