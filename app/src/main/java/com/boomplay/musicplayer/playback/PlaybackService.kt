package com.boomplay.musicplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
    private val sourceAttempts = ConcurrentHashMap<String, AudioSourceAttempts>()
    private val sourceLock = Any()
    private val queuedOccurrences = mutableSetOf<String>()
    private var recoveryJob: Job? = null
    private var recoveryGeneration = 0L
    private var timerJob: Job? = null
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
                val occurrence = spec.uri.getQueryParameter("occurrence") ?: id.toString()
                val candidate = AudioSourceAttempts(PlaybackUtils.audioUrls(track, app.music.staticAddress, app.listening.resolvedQuality()))
                val attempts = synchronized(sourceLock) {
                    // A cancelled resolver must not reintroduce an occurrence removed while resolving.
                    if (occurrence in queuedOccurrences) sourceAttempts.getOrPut(occurrence) { candidate } else candidate
                }
                url = attempts.current() ?: throw IOException("No available audio source")
                spec.withUri(url.toUri())
            }
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source).setLoadErrorHandlingPolicy(
                object : DefaultLoadErrorHandlingPolicy(0) {
                    // The constructor controls fatal-error polling, not the loader retry decision.
                    // Our occurrence budget owns every retry, including the first immediate retry.
                    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
                },
            ))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build().apply {
                setWakeMode(C.WAKE_MODE_LOCAL)
                setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA).build(), true)
                setHandleAudioBecomingNoisy(true)
                addListener(object : Player.Listener {
                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        updateTrackCache()
                        saveSnapshot()
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        cancelRecovery()
                        // Keep prebuffered occurrences' source budgets: their loader may already be open.
                        lastHistoryId = null
                        recordHistory()
                        saveSnapshot()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) recordHistory()
                        saveSnapshot()
                    }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_REMOVE) cancelRecovery()
                        saveSnapshot()
                    }
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (!playWhenReady) cancelRecovery()
                        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                            app.listening.sleepTimer.value == SleepTimer.EndOfTrack) finishTimer()
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && app.listening.sleepTimer.value == SleepTimer.EndOfTrack) finishTimer()
                    }
                    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveSnapshot()
                    override fun onRepeatModeChanged(repeatMode: Int) = saveSnapshot()
                    override fun onPlayerError(error: PlaybackException) {
                        recoverSource(error)
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
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (!PlaybackUtils.acceptsController(packageName, controller.packageName, controller.isTrusted)) return MediaSession.ConnectionResult.reject()
                    val base = super.onConnect(session, controller)
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(base.availableSessionCommands.buildUpon()
                            .add(SessionCommand(ListeningCommands.TIMER, Bundle.EMPTY))
                            .add(SessionCommand(ListeningCommands.RETRY, Bundle.EMPTY)).build()).build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    // Timer and recovery settings are application actions, not system transport controls.
                    if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    when (customCommand.customAction) {
                        ListeningCommands.TIMER -> {
                            val minutes = args.getInt(ListeningCommands.MINUTES, Int.MIN_VALUE)
                            if (minutes !in 1..1440 && minutes !in listOf(ListeningCommands.OFF, ListeningCommands.END_OF_TRACK)) {
                                return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                            }
                            setTimer(when (minutes) {
                                ListeningCommands.OFF -> SleepTimer.Off
                                ListeningCommands.END_OF_TRACK -> SleepTimer.EndOfTrack
                                else -> ListeningUtils.countdown(minutes, SystemClock.elapsedRealtime())
                            })
                        }
                        ListeningCommands.RETRY -> {
                            cancelRecovery()
                            player.currentMediaItem?.let { sourceAttempts.remove(PlaybackMediaItems.occurrenceId(it)) }
                            if (player.mediaItemCount > 0) { player.prepare(); player.play() }
                        }
                        else -> return super.onCustomCommand(session, controller, customCommand, args)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build()
        restoreSnapshot()
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) saveSnapshot()
            }
        }
    }

    private fun cancelRecovery() {
        recoveryGeneration++
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun recoverSource(error: PlaybackException) {
        val retryable = error.errorCode in setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        )
        val attempts = player.currentMediaItem?.let { sourceAttempts[PlaybackMediaItems.occurrenceId(it)] }
        if (!retryable || !player.playWhenReady || attempts?.advance() != true) {
            player.pause()
            return
        }
        cancelRecovery()
        val generation = recoveryGeneration
        val item = player.currentMediaItem
        recoveryJob = scope.launch {
            delay(750)
            if (generation != recoveryGeneration || !player.playWhenReady || player.currentMediaItem != item) return@launch
            // prepare retains the selected item and seek position; no automatic track skipping.
            player.prepare()
        }
    }

    private fun setTimer(timer: SleepTimer) {
        timerJob?.cancel()
        timerJob = null
        app.listening.setTimer(timer)
        player.pauseAtEndOfMediaItems = timer == SleepTimer.EndOfTrack
        if (timer is SleepTimer.Countdown) timerJob = scope.launch {
            while (!ListeningUtils.expired(timer, SystemClock.elapsedRealtime())) {
                delay((timer.deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceIn(1, 1_000))
            }
            finishTimer()
        }
    }

    private fun finishTimer() {
        cancelRecovery()
        player.pause()
        setTimer(SleepTimer.Off)
        saveSnapshot()
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
        val occurrences = (0 until player.mediaItemCount).map { PlaybackMediaItems.occurrenceId(player.getMediaItemAt(it)) }.toSet()
        synchronized(sourceLock) {
            queuedOccurrences.clear()
            queuedOccurrences.addAll(occurrences)
            sourceAttempts.keys.retainAll(occurrences)
        }
        if (occurrences.isEmpty() && app.listening.sleepTimer.value == SleepTimer.EndOfTrack) setTimer(SleepTimer.Off)
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
        if (!::player.isInitialized) return
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
        app.listening.setTimer(SleepTimer.Off)
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
