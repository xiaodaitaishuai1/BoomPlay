package com.boomplay.musicplayer

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.boomplay.musicplayer.databinding.ActivityPlayerBinding
import com.boomplay.musicplayer.ui.LibraryDialogs
import com.boomplay.musicplayer.ui.LyricsViewModel
import com.boomplay.musicplayer.ui.PlayerLyricsUi
import com.boomplay.musicplayer.ui.ListeningDialogs
import com.boomplay.musicplayer.playback.SleepTimer
import com.boomplay.musicplayer.utils.UiUtils
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private val app get() = application as BoomPlayApplication
    private val dialogs by lazy { LibraryDialogs(this) }
    private var seeking = false
    private var favorites = emptySet<Long>()
    private val lyricsModel: LyricsViewModel by viewModels {
        viewModelFactory { initializer { LyricsViewModel(createSavedStateHandle(), app.lyrics) } }
    }
    private lateinit var lyricsUi: PlayerLyricsUi
    private val listeningDialogs by lazy { ListeningDialogs(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater); setContentView(binding.root)
        UiUtils.insets(binding.root)
        lyricsUi = PlayerLyricsUi(binding.lyricsPanel, app.playback::seekTo, lyricsModel::retry)
        binding.lyricsToggle.setOnClickListener { lyricsModel.setVisible(!lyricsModel.visible.value) }
        binding.timerStatus.setOnClickListener { listeningDialogs.sleepTimer() }
        binding.back.setOnClickListener { finish() }
        binding.play.setOnClickListener { app.playback.toggle() }
        binding.next.setOnClickListener { app.playback.next() }; binding.previous.setOnClickListener { app.playback.previous() }
        binding.rewind.setOnClickListener { app.playback.seekBy(-10_000) }; binding.forward.setOnClickListener { app.playback.seekBy(10_000) }
        binding.shuffle.setOnClickListener { app.playback.setShuffle(!app.playback.state.value.shuffle) }
        binding.repeat.setOnClickListener { app.playback.cycleRepeat() }
        binding.queue.setOnClickListener { dialogs.queue() }
        binding.favorite.setOnClickListener { app.playback.state.value.current?.let { dialogs.perform { app.library.toggleFavorite(it) } } }
        binding.add.setOnClickListener { app.playback.state.value.current?.let { dialogs.pickPlaylist(it) } }
        binding.more.setOnClickListener { listeningDialogs.options { app.playback.state.value.current?.let { dialogs.trackMenu(it) } } }
        binding.retry.setOnClickListener { app.playback.retry() }
        binding.progress.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { seeking = true }
            override fun onStopTrackingTouch(slider: Slider) { app.playback.seekTo(slider.value.toLong()); seeking = false }
        })
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { app.playback.state.collect {
                if (lyricsModel.visible.value) lyricsModel.select(it.current)
                lyricsUi.updatePosition(it.positionMs)
                render()
            } }
            launch { app.library.favorites.collect { favorites = it.map { track -> track.id }.toSet(); render() } }
            launch { lyricsModel.state.collect(lyricsUi::render) }
            launch { lyricsModel.visible.collect { visible ->
                binding.cover.visibility = if (visible) View.INVISIBLE else View.VISIBLE
                binding.lyricsPanel.root.isVisible = visible
                binding.lyricsToggle.setText(if (visible) R.string.artwork else R.string.lyrics)
                if (visible) lyricsModel.select(app.playback.state.value.current)
                lyricsUi.updatePosition(app.playback.state.value.positionMs)
            } }
            launch { app.listening.sleepTimer.collectLatest { timer ->
                renderTimer()
                if (timer is SleepTimer.Countdown) while (isActive) { delay(1000); renderTimer() }
            } }
        } }
    }
    private fun renderTimer() {
        val timer = app.listening.sleepTimer.value
        binding.timerStatus.isVisible = timer != SleepTimer.Off
        binding.timerStatus.text = when (timer) {
            SleepTimer.Off -> ""
            SleepTimer.EndOfTrack -> getString(R.string.timer_end_of_track)
            is SleepTimer.Countdown -> getString(R.string.timer_remaining, UiUtils.time((timer.deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)))
        }
    }
    private fun render() {
        val state = app.playback.state.value; val track = state.current
        binding.title.text = track?.title ?: getString(R.string.no_song)
        binding.artist.text = track?.artist?.name.orEmpty()
        track?.let { UiUtils.artwork(binding.cover, it.cover); UiUtils.artwork(binding.background, it.cover) }
        binding.play.setImageResource(if (state.playing || state.buffering) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        binding.play.contentDescription = getString(if (state.playing || state.buffering) R.string.pause else R.string.play)
        val duration = state.durationMs.coerceAtLeast(1).toFloat()
        if (!seeking) {
            if (binding.progress.value > duration) binding.progress.value = 0f
            binding.progress.valueTo = duration
            binding.progress.value = state.positionMs.toFloat().coerceIn(0f, duration)
        }
        binding.progress.isEnabled = track != null && state.durationMs > 0
        binding.elapsed.text = UiUtils.time(state.positionMs); binding.duration.text = UiUtils.time(state.durationMs)
        binding.shuffle.alpha = if (state.shuffle) 1f else .4f
        binding.repeat.alpha = if (state.repeatMode != 0) 1f else .4f
        binding.repeat.contentDescription = getString(when (state.repeatMode) { 1 -> R.string.repeat_one; 2 -> R.string.repeat_all; else -> R.string.repeat_off })
        androidx.core.view.ViewCompat.setStateDescription(binding.repeat, binding.repeat.contentDescription)
        val favorite = track?.id in favorites
        binding.favorite.imageTintList = ColorStateList.valueOf(if (favorite) 0xFFFF6B8A.toInt() else 0xFFFFFFFF.toInt())
        binding.favorite.contentDescription = getString(if (favorite) R.string.remove_favorite else R.string.add_favorite)
        binding.status.text = when { state.error != null -> getString(R.string.playback_error); state.buffering -> getString(R.string.loading); else -> "" }
        binding.retry.isVisible = state.error != null
    }
}
