package com.boomplay.musicplayer.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boomplay.musicplayer.library.LyricsRepository
import com.boomplay.musicplayer.library.LyricsResult
import com.boomplay.musicplayer.library.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LyricsUiState(val trackId: Long? = null, val loading: Boolean = false, val result: LyricsResult = LyricsResult.Unavailable)

class LyricsViewModel(private val savedState: SavedStateHandle, private val repository: LyricsRepository) : ViewModel() {
    val visible = savedState.getStateFlow("lyrics_visible", false)
    private val mutableState = MutableStateFlow(LyricsUiState())
    val state = mutableState.asStateFlow()
    private var selected: Track? = null
    private var request: Job? = null

    fun setVisible(value: Boolean) { savedState["lyrics_visible"] = value }
    fun select(track: Track?, force: Boolean = false) {
        if (!force && selected?.id == track?.id) return
        selected = track
        request?.cancel()
        mutableState.value = LyricsUiState(track?.id, loading = track != null)
        if (track == null) return
        request = viewModelScope.launch {
            val result = try { repository.lyrics(track) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LyricsResult.Failed }
            if (selected?.id == track.id) mutableState.value = LyricsUiState(track.id, result = result)
        }
    }
    fun retry() = select(selected, force = true)
}

class LyricsFollowState {
    private var resumeAt = 0L
    fun onManualScroll(nowMs: Long) { resumeAt = nowMs + 5000 }
    fun shouldFollow(nowMs: Long): Boolean = nowMs >= resumeAt
    fun resume() { resumeAt = 0 }
}
