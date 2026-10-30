package com.boomplay.musicplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.core.net.toUri
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Owns the only player, including when every activity is stopped. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var snapshotStore: PlaybackSnapshotStore
    private lateinit var snapshotWriter: PlaybackSnapshotWriter<PendingSnapshot>
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracks = ConcurrentHashMap<Long, Track>()
    private val mediaItems = ConcurrentHashMap<Long, MediaItem>()
    private var snapshotQueue: Lazy<List<Track>> = lazy { emptyList() }
    private var queueGeneration = 0L
    private var previousWriter: Job? = null
    private var lastHistoryId: Long? = null
    private val app get() = application as BoomPlayApplication

    override fun onCreate() {
        super.onCreate()
        snapshotStore = PlaybackSnapshotStore(this)
        previousWriter = lastWriter
        snapshotWriter = PlaybackSnapshotWriter(persistenceScope) { pending ->
            snapshotStore.save(PlaybackSnapshot(pending.queue.value, pending.index, pending.positionMs, pending.shuffle, pending.repeatMode))
        }
        snapshotWriter.job.invokeOnCompletion { persistenceScope.cancel() }
        lastWriter = snapshotWriter.job
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("BoomPlay/7.8.43")
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val source = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme != "boomplay") spec else {
                val id = spec.uri.lastPathSegment?.toLongOrNull() ?: throw IOException("Invalid track ID")
                val raw = mediaItems[id]?.let(PlaybackMediaItems::track)
                var track = raw?.takeIf { PlaybackUtils.audioUrl(it, app.music.staticAddress) != null }
                    ?: tracks[id] ?: raw ?: throw IOException("Track not found")
                var url = PlaybackUtils.audioUrl(track, app.music.staticAddress)
                if (url == null) {
                    try {
                        track = runBlocking(Dispatchers.IO) { app.music.track(id) }
                        // A request for a removed item must never repopulate the queue cache.
                        if (mediaItems.containsKey(id)) tracks.compute(id) { _, _ -> if (mediaItems.containsKey(id)) track else null }
                        scope.launch { rebuildSnapshotQueue(); saveSnapshot() }
                        url = PlaybackUtils.audioUrl(track, app.music.staticAddress)
                        runBlocking(Dispatchers.IO) { app.library.saveTrack(track) }
                    } catch (error: Exception) {
                        throw IOException("Unable to resolve track", error)
                    }
                }
                if (url == null) throw IOException("No available audio source")
                spec.withUri(url.toUri())
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
        session = MediaSession.Builder(this, player).setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    if (PlaybackUtils.acceptsController(packageName, controller.packageName, controller.isTrusted)) {
                        super.onConnect(session, controller)
                    } else MediaSession.ConnectionResult.reject()
            }).build()
        restoreSnapshot()
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) saveSnapshot()
            }
        }
    }

    private fun updateTrackCache() {
        queueGeneration++
        val current = mutableMapOf<Long, MediaItem>()
        (0 until player.mediaItemCount).forEach { index ->
            val item = player.getMediaItemAt(index)
            item.mediaId.toLongOrNull()?.let { current[it] = item }
        }
        mediaItems.putAll(current)
        mediaItems.keys.retainAll(current.keys)
        tracks.keys.retainAll(current.keys)
        rebuildSnapshotQueue()
    }

    private fun rebuildSnapshotQueue() {
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        val resolvedTracks = tracks.toMap()
        val staticAddress = app.music.staticAddress
        // Only materialized by the persistence worker. Reused by ordinary progress saves.
        snapshotQueue = lazy { items.mapNotNull { item ->
            val raw = PlaybackMediaItems.track(item)
            raw?.takeIf { PlaybackUtils.audioUrl(it, staticAddress) != null }
                ?: item.mediaId.toLongOrNull()?.let { resolvedTracks[it] } ?: raw
        } }
    }

    private fun recordHistory() {
        if (!player.isPlaying) return
        val item = player.currentMediaItem ?: return
        val id = item.mediaId.toLongOrNull() ?: return
        if (lastHistoryId == id) return
        lastHistoryId = id
        scope.launch(Dispatchers.IO) { runCatching {
            val track = tracks[id] ?: PlaybackMediaItems.track(item) ?: return@runCatching
            app.library.recordPlayed(track)
        } }
    }

    private fun saveSnapshot() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        snapshotWriter.submit(PendingSnapshot(snapshotQueue, player.currentMediaItemIndex.coerceAtLeast(0),
            player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode))
    }

    private fun restoreSnapshot() {
        val generation = queueGeneration
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                // An immediately recreated service must read the previous service's final position.
                previousWriter?.join()
                snapshotStore.load()?.let { it to it.queue.map(PlaybackMediaItems::fromTrack) }
            } ?: return@launch
            if (queueGeneration != generation || player.mediaItemCount != 0) return@launch
            val (snapshot, items) = restored
            snapshot.queue.forEach { tracks[it.id] = it }
            player.setMediaItems(items, snapshot.index, snapshot.positionMs)
            player.shuffleModeEnabled = snapshot.shuffle
            player.repeatMode = snapshot.repeatMode.takeIf { it in 0..2 } ?: Player.REPEAT_MODE_OFF
            // Loading the queue must never start playback after a process restart.
            player.playWhenReady = false
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveSnapshot()
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        saveSnapshot()
        snapshotWriter.close()
        scope.cancel()
        session?.release()
        session = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private data class PendingSnapshot(
        val queue: Lazy<List<Track>>, val index: Int, val positionMs: Long,
        val shuffle: Boolean, val repeatMode: Int,
    )

    private companion object {
        // Service lifecycle callbacks run on the main thread. No disk lock is acquired here.
        var lastWriter: Job? = null
    }
}
