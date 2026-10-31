package com.boomplay.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import com.boomplay.musicplayer.library.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** All transport commands go through the service's MediaSession. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val publisher = PlaybackStatePublisher(scope)
    val state: StateFlow<PlaybackState> = publisher.state
    private val mutableQueueRevision = MutableStateFlow(0L)
    internal val queueRevision: StateFlow<Long> = mutableQueueRevision.asStateFlow()
    private var player: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var connecting = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publisher.publish(
                rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED),
                refreshCurrent = events.contains(Player.EVENT_MEDIA_METADATA_CHANGED),
            )
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) mutableQueueRevision.value++
        }
    }

    init {
        connect()
    }

    private fun connect() {
        if (connecting || player != null) return
        connecting = true
        val future = MediaController.Builder(appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (player === controller) {
                        player = null
                        publisher.detach()
                        mutableQueueRevision.value++
                    }
                }
            }).buildAsync()
        future.addListener({
            connecting = false
            runCatching { future.get() }.onSuccess { controller ->
                player = controller
                controller.addListener(listener)
                publisher.attach(controller)
                mutableQueueRevision.value++
                while (pending.isNotEmpty()) pending.removeFirst()(controller)
                publish()
            }.onFailure {
                pending.clear()
                publisher.detach()
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun play(tracks: List<Track>, index: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        command {
            it.setMediaItems(tracks.map(PlaybackMediaItems::fromTrack), index.coerceIn(tracks.indices), 0)
            it.shuffleModeEnabled = shuffle
            it.prepare()
            it.play()
        }
    }

    fun toggle() = command {
        if (it.playWhenReady && it.playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING) && it.playerError == null) it.pause()
        else {
            if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            it.play()
        }
    }

    fun next() = command {
        if (it.hasNextMediaItem()) {
            val failed = it.playerError != null
            it.seekToNextMediaItem()
            if (failed) { it.prepare(); it.play() }
        }
    }
    fun previous() = command {
        val failed = it.playerError != null
        if (it.currentPosition > 3_000 || !it.hasPreviousMediaItem()) it.seekTo(0)
        else it.seekToPreviousMediaItem()
        if (failed) { it.prepare(); it.play() }
    }
    fun seekTo(ms: Long) = command {
        val duration = it.duration.takeIf { value -> value > 0 }
            ?: PlaybackMediaItems.track(it.currentMediaItem)?.durationMs ?: 0
        it.seekTo(PlaybackUtils.clampPosition(ms, duration))
    }
    fun seekBy(delta: Long) = seekTo(state.value.positionMs + delta)
    fun setShuffle(enabled: Boolean) = command { it.shuffleModeEnabled = enabled }
    fun cycleRepeat() = command {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun playIndex(index: Int) = command {
        if (index in 0 until it.mediaItemCount) {
            it.seekTo(index, 0)
            if (it.playbackState == Player.STATE_IDLE) it.prepare()
            it.play()
        }
    }

    fun removeQueueItem(index: Int) = command {
        if (index in 0 until it.mediaItemCount) {
            if (it.mediaItemCount == 1) { it.pause(); it.clearMediaItems() }
            else it.removeMediaItem(index)
        }
    }

    fun moveQueueItem(from: Int, to: Int) = command {
        if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount) it.moveMediaItem(from, to)
    }

    fun clearQueue() = command { it.pause(); it.clearMediaItems() }

    suspend fun saveQueueAsPlaylist(name: String): Long {
        val snapshot = state.value.queue.toList()
        return (appContext as com.boomplay.musicplayer.BoomPlayApplication).library.saveQueueAsPlaylist(name, snapshot)
    }

    internal fun queueEntries(): List<Pair<String, Track>> = player?.let { controller ->
        (0 until controller.mediaItemCount).mapNotNull { index ->
            val item = controller.getMediaItemAt(index)
            PlaybackMediaItems.track(item)?.let { PlaybackMediaItems.occurrenceId(item) to it }
        }
    }.orEmpty()

    internal fun queueIndex(occurrenceId: String): Int = player?.let { controller ->
        (0 until controller.mediaItemCount).firstOrNull {
            PlaybackMediaItems.occurrenceId(controller.getMediaItemAt(it)) == occurrenceId
        }
    } ?: -1

    fun enqueue(track: Track, next: Boolean = false) = command {
        val position = PlaybackUtils.insertionIndex(it.mediaItemCount, it.currentMediaItemIndex, next)
        it.addMediaItem(position, PlaybackMediaItems.fromTrack(track))
    }

    fun retry() = command { it.sendCustomCommand(SessionCommand(ListeningCommands.RETRY, Bundle.EMPTY), Bundle.EMPTY) }

    fun setSleepTimer(minutes: Int) {
        require(minutes in 1..1440)
        timerCommand(minutes)
    }
    fun stopAfterCurrentTrack() = timerCommand(ListeningCommands.END_OF_TRACK)
    fun cancelSleepTimer() = timerCommand(ListeningCommands.OFF)
    private fun timerCommand(minutes: Int) = command {
        it.sendCustomCommand(SessionCommand(ListeningCommands.TIMER, Bundle.EMPTY), Bundle().apply {
            putInt(ListeningCommands.MINUTES, minutes)
        })
    }

    private fun command(block: (MediaController) -> Unit) {
        scope.launch {
            val controller = player
            if (controller == null) {
                pending.addLast(block)
                connect()
            } else { block(controller); publish() }
        }
    }

    private fun publish() {
        publisher.publish()
    }
}
