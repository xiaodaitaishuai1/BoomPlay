package com.boomplay.musicplayer

import android.content.res.ColorStateList
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.boomplay.musicplayer.databinding.ActivityPlayerBinding
import com.boomplay.musicplayer.ui.LibraryDialogs
import com.boomplay.musicplayer.utils.UiUtils
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private val app get() = application as BoomPlayApplication
    private val dialogs by lazy { LibraryDialogs(this) }
    private var seeking = false
    private var favorites = emptySet<Long>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater); setContentView(binding.root)
        UiUtils.insets(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.play.setOnClickListener { app.playback.toggle() }
        binding.next.setOnClickListener { app.playback.next() }; binding.previous.setOnClickListener { app.playback.previous() }
        binding.rewind.setOnClickListener { app.playback.seekBy(-10_000) }; binding.forward.setOnClickListener { app.playback.seekBy(10_000) }
        binding.shuffle.setOnClickListener { app.playback.setShuffle(!app.playback.state.value.shuffle) }
        binding.repeat.setOnClickListener { app.playback.cycleRepeat() }
        binding.queue.setOnClickListener { dialogs.queue() }
        binding.favorite.setOnClickListener { app.playback.state.value.current?.let { dialogs.perform { app.library.toggleFavorite(it) } } }
        binding.add.setOnClickListener { app.playback.state.value.current?.let { dialogs.pickPlaylist(it) } }
        binding.more.setOnClickListener { app.playback.state.value.current?.let { dialogs.trackMenu(it) } }
        binding.retry.setOnClickListener { app.playback.retry() }
        binding.progress.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { seeking = true }
            override fun onStopTrackingTouch(slider: Slider) { app.playback.seekTo(slider.value.toLong()); seeking = false }
        })
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { app.playback.state.collect { render() } }
            launch { app.library.favorites.collect { favorites = it.map { track -> track.id }.toSet(); render() } }
        } }
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
