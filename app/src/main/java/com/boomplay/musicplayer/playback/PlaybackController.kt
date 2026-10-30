package com.boomplay.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.boomplay.musicplayer.library.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** All transport commands go through the service's MediaSession. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackController(context: Context) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var connecting = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    init {
        connect()
        scope.launch {
            while (isActive) {
                publish()
                delay(500)
            }
        }
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
                        mutableState.value = mutableState.value.copy(playing = false, buffering = false, error = "connection_failed")
                    }
                }
            }).buildAsync()
        future.addListener({
            connecting = false
            runCatching { future.get() }.onSuccess { controller ->
                player = controller
                controller.addListener(listener)
                while (pending.isNotEmpty()) pending.removeFirst()(controller)
                publish()
            }.onFailure {
                pending.clear()
                mutableState.value = mutableState.value.copy(buffering = false, error = "connection_failed")
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

    fun enqueue(track: Track, next: Boolean = false) = command {
        val position = PlaybackUtils.insertionIndex(it.mediaItemCount, it.currentMediaItemIndex, next)
        it.addMediaItem(position, PlaybackMediaItems.fromTrack(track))
    }

    fun retry() = command { it.prepare(); it.play() }

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
        val controller = player ?: return
        val queue = (0 until controller.mediaItemCount).mapNotNull { PlaybackMediaItems.track(controller.getMediaItemAt(it)) }
        val track = PlaybackMediaItems.track(controller.currentMediaItem)
        mutableState.value = PlaybackState(
            current = track,
            queue = queue,
            index = controller.currentMediaItemIndex.takeIf { track != null } ?: -1,
            playing = controller.isPlaying,
            buffering = controller.playbackState == Player.STATE_BUFFERING,
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = controller.duration.takeIf { it > 0 } ?: track?.durationMs ?: 0,
            shuffle = controller.shuffleModeEnabled,
            repeatMode = controller.repeatMode,
            error = controller.playerError?.let { "playback_failed" },
        )
    }
}
