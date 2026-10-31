package com.boomplay.musicplayer.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.boomplay.musicplayer.library.Track
import com.google.gson.Gson

internal object PlaybackMediaItems {
    private val gson = Gson()
    private const val TRACK_KEY = "boomplay_track"
    private const val OCCURRENCE_KEY = "boomplay_occurrence"

    fun occurrenceId(item: MediaItem): String = item.mediaMetadata.extras?.getString(OCCURRENCE_KEY)
        ?: item.mediaId

    fun fromTrack(track: Track): MediaItem {
        val occurrence = java.util.UUID.randomUUID().toString()
        return MediaItem.Builder()
        .setMediaId(track.id.toString())
        .setUri("boomplay://track/${track.id}?occurrence=$occurrence")
        .setMimeType("audio/mpeg")
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.name)
            .setAlbumTitle(track.album.name)
            .setArtworkUri(track.cover.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(Uri::parse))
            .setExtras(Bundle().apply {
                putString(TRACK_KEY, gson.toJson(track))
                putString(OCCURRENCE_KEY, occurrence)
            })
            .build())
        .build()
    }

    fun track(item: MediaItem?): Track? = runCatching {
        item?.mediaMetadata?.extras?.getString(TRACK_KEY)?.let { gson.fromJson(it, Track::class.java) }
    }.getOrNull()
}
