package com.boomplay.musicplayer.playback

import com.boomplay.musicplayer.library.Track

object PlaybackUtils {
    internal fun acceptsController(appPackage: String, controllerPackage: String, trusted: Boolean): Boolean =
        appPackage == controllerPackage || trusted

    fun audioUrl(track: Track, staticAddress: String): String? {
        val source = sequenceOf(track.hdSource, track.mdSource, track.ldSource)
            .map(String::trim).firstOrNull { it.isNotEmpty() } ?: return null
        return if (source.startsWith("https://", ignoreCase = true) || source.startsWith("http://", ignoreCase = true)) {
            source
        } else {
            staticAddress.trimEnd('/') + "/" + source.trimStart('/')
        }
    }

    fun clampPosition(position: Long, duration: Long): Long =
        if (duration > 0) position.coerceIn(0, duration) else position.coerceAtLeast(0)

    fun insertionIndex(size: Int, index: Int, next: Boolean): Int =
        if (next) (index + 1).coerceIn(0, size) else size
}
