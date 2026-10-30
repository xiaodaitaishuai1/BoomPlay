package com.boomplay.musicplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.boomplay.musicplayer.BoomPlayApplication
import com.boomplay.musicplayer.library.Track
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Owns the only player, including when every activity is stopped. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var snapshotStore: PlaybackSnapshotStore
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracks = ConcurrentHashMap<Long, Track>()
    private var lastHistoryId: Long? = null
    private val app get() = application as BoomPlayApplication

    override fun onCreate() {
        super.onCreate()
        snapshotStore = PlaybackSnapshotStore(this)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("BoomPlay/7.8.43")
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val source = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme != "boomplay") spec else {
                val id = spec.uri.lastPathSegment?.toLongOrNull() ?: throw IOException("Invalid track ID")
                var track = tracks[id] ?: throw IOException("Track not found")
                var url = PlaybackUtils.audioUrl(track, app.music.staticAddress)
                if (url == null) {
                    try {
                        track = runBlocking(Dispatchers.IO) { app.music.track(id) }
                        tracks[id] = track
                        url = PlaybackUtils.audioUrl(track, app.music.staticAddress)
                        runBlocking(Dispatchers.IO) { app.library.saveTrack(track) }
                    } catch (error: Exception) {
                        throw IOException("Unable to resolve track", error)
                    }
                }
                if (url == null) throw IOException("No available audio source")
                spec.withUri(Uri.parse(url))
            }
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA).build(), true)
                setHandleAudioBecomingNoisy(true)
                addListener(object : Player.Listener {
                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        updateTrackCache()
                        saveSnapshot()
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        lastHistoryId = null
                        recordHistory()
                        saveSnapshot()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) recordHistory()
                        saveSnapshot()
                    }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        saveSnapshot()
                    }
                    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveSnapshot()
                    override fun onRepeatModeChanged(repeatMode: Int) = saveSnapshot()
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // Fail the selected track visibly; never loop through the rest of the queue.
                        playWhenReady = false
                        saveSnapshot()
                    }
                })
            }
        val pendingIntent = PendingIntent.getActivity(this, 0,
            Intent().setClassName(this, "com.boomplay.musicplayer.PlayerActivity")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(pendingIntent).build()
        restoreSnapshot()
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) saveSnapshot()
            }
        }
    }

    private fun updateTrackCache() {
        (0 until player.mediaItemCount).forEach { index ->
            PlaybackMediaItems.track(player.getMediaItemAt(index))?.let { track ->
                tracks.compute(track.id) { _, previous ->
                    if (PlaybackUtils.audioUrl(track, app.music.staticAddress) != null) track else previous ?: track
                }
            }
        }
    }

    private fun recordHistory() {
        if (!player.isPlaying) return
        val track = PlaybackMediaItems.track(player.currentMediaItem) ?: return
        if (lastHistoryId == track.id) return
        lastHistoryId = track.id
        scope.launch { runCatching { app.library.recordPlayed(tracks[track.id] ?: track) } }
    }

    private fun saveSnapshot() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val queue = (0 until player.mediaItemCount).mapNotNull {
            PlaybackMediaItems.track(player.getMediaItemAt(it))?.let { track -> tracks[track.id] ?: track }
        }
        snapshotStore.save(PlaybackSnapshot(queue, player.currentMediaItemIndex.coerceAtLeast(0),
            player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode))
    }

    private fun restoreSnapshot() {
        val snapshot = snapshotStore.load() ?: return
        snapshot.queue.forEach { tracks[it.id] = it }
        player.setMediaItems(snapshot.queue.map(PlaybackMediaItems::fromTrack), snapshot.index, snapshot.positionMs)
        player.shuffleModeEnabled = snapshot.shuffle
        player.repeatMode = snapshot.repeatMode.takeIf { it in 0..2 } ?: Player.REPEAT_MODE_OFF
        // Loading the queue must never start playback after a process restart.
        player.playWhenReady = false
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveSnapshot()
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        saveSnapshot()
        scope.cancel()
        session?.release()
        session = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
