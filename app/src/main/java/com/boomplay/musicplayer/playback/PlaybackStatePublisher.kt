package com.boomplay.musicplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.boomplay.musicplayer.library.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Caches timeline metadata; the active progress timer never traverses the queue. */
internal class PlaybackStatePublisher(
    private val scope: CoroutineScope,
    private val decode: (MediaItem) -> Track? = PlaybackMediaItems::track,
) {
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    private var player: Player? = null
    private var progressJob: Job? = null
    private var queue = emptyList<Track>()
    private var tracksById = emptyMap<String, Track>()
    private var cachedItems = emptyMap<String, MediaItem>()

    init { scope.launch { mutable.subscriptionCount.collect { publish() } } }

    fun attach(player: Player) { this.player = player; publish(rebuildQueue = true) }

    fun detach() {
        player = null
        progressJob?.cancel(); progressJob = null
        mutable.value = mutable.value.copy(playing = false, buffering = false, error = "connection_failed")
    }

    fun publish(rebuildQueue: Boolean = false, refreshCurrent: Boolean = false) {
        val player = player ?: return
        if (rebuildQueue) {
            val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            queue = items.mapNotNull(decode)
            tracksById = queue.associateBy { it.id.toString() }
            cachedItems = items.associateBy { it.mediaId }
        } else if (refreshCurrent) {
            player.currentMediaItem?.let { item ->
                if (cachedItems[item.mediaId] != item) {
                    decode(item)?.let { updated ->
                        queue = queue.map { if (it.id == updated.id) updated else it }
                        tracksById = tracksById + (item.mediaId to updated)
                    }
                    cachedItems = cachedItems + (item.mediaId to item)
                }
            }
        }
        val track = tracksById[player.currentMediaItem?.mediaId]
        mutable.value = PlaybackState(
            current = track, queue = queue,
            index = player.currentMediaItemIndex.takeIf { track != null } ?: -1,
            playing = player.isPlaying, buffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it > 0 } ?: track?.durationMs ?: 0,
            shuffle = player.shuffleModeEnabled, repeatMode = player.repeatMode,
            error = player.playerError?.let { "playback_failed" },
        )
        if (mutable.subscriptionCount.value == 0 || !player.isPlaying) {
            progressJob?.cancel(); progressJob = null
        } else if (progressJob == null) {
            progressJob = scope.launch {
                while (isActive) {
                    delay(500)
                    val current = this@PlaybackStatePublisher.player ?: break
                    mutable.value = mutable.value.copy(positionMs = current.currentPosition.coerceAtLeast(0))
                }
            }
        }
    }
}
