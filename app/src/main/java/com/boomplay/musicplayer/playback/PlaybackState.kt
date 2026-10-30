package com.boomplay.musicplayer.playback

import com.boomplay.musicplayer.library.Track

data class PlaybackState(
    val current: Track? = null,
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val error: String? = null,
)
