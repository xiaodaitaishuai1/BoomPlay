package com.boomplay.musicplayer.playback

import com.boomplay.musicplayer.library.Track

object PlaybackUtils {
    internal fun acceptsController(appPackage: String, controllerPackage: String, trusted: Boolean): Boolean =
        appPackage == controllerPackage || trusted

    fun audioUrl(track: Track, staticAddress: String): String? {
        return audioUrls(track, staticAddress, AudioQuality.HIGH).firstOrNull()
    }

    fun audioUrls(track: Track, staticAddress: String, quality: AudioQuality): List<String> {
        val sources = when (quality) {
            AudioQuality.MEDIUM -> listOf(track.mdSource, track.ldSource, track.hdSource)
            AudioQuality.LOW -> listOf(track.ldSource, track.mdSource, track.hdSource)
            else -> listOf(track.hdSource, track.mdSource, track.ldSource)
        }
        return sources.map(String::trim).filter(String::isNotEmpty).map { source ->
            if (source.startsWith("https://", true) || source.startsWith("http://", true)) source
            else staticAddress.trimEnd('/') + "/" + source.trimStart('/')
        }.distinct()
    }

    fun clampPosition(position: Long, duration: Long): Long =
        if (duration > 0) position.coerceIn(0, duration) else position.coerceAtLeast(0)

    fun insertionIndex(size: Int, index: Int, next: Boolean): Int =
        if (next) (index + 1).coerceIn(0, size) else size
}
