package com.boomplay.musicplayer.ui

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.boomplay.musicplayer.R
import com.boomplay.musicplayer.databinding.ViewLyricsBinding
import com.boomplay.musicplayer.library.Lyrics
import com.boomplay.musicplayer.library.LyricsResult

class PlayerLyricsUi(private val binding: ViewLyricsBinding, private val seek: (Long) -> Unit, retry: () -> Unit) {
    private val follow = LyricsFollowState()
    private val adapter = LinesAdapter(seek)
    private var data: Lyrics? = null
    private var lastPosition = 0L
    private var lastFollowed = -1
    private var trackId: Long? = null
    init {
        binding.lyricsList.layoutManager = LinearLayoutManager(binding.root.context)
        binding.lyricsList.adapter = adapter
        binding.lyricsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) follow.onManualScroll(SystemClock.elapsedRealtime())
                if (newState == RecyclerView.SCROLL_STATE_IDLE) updatePosition(lastPosition)
            }
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE && !follow.shouldFollow(SystemClock.elapsedRealtime())) {
                    follow.onManualScroll(SystemClock.elapsedRealtime())
                }
            }
        })
        binding.lyricsFollow.setOnClickListener { follow.resume(); lastFollowed = -1; updatePosition(lastPosition) }
        binding.lyricsRetry.setOnClickListener { retry() }
    }

    fun render(state: LyricsUiState) {
        val lyrics = (state.result as? LyricsResult.Available)?.lyrics
        if (trackId != state.trackId || data != lyrics) {
            trackId = state.trackId
            data = lyrics
            follow.resume()
            lastFollowed = -1
            adapter.replace(lyrics)
            binding.lyricsList.scrollToPosition(0)
        }
        binding.lyricsList.isVisible = lyrics != null
        binding.lyricsMessage.isVisible = lyrics == null
        binding.lyricsMessage.setText(if (state.loading) R.string.lyrics_loading else R.string.no_lyrics)
        binding.lyricsRetry.isVisible = state.trackId != null && !state.loading && lyrics == null
        updatePosition(lastPosition)
    }

    fun updatePosition(positionMs: Long) {
        lastPosition = positionMs
        val index = data?.activeIndex(positionMs) ?: -1
        adapter.highlight(index)
        val canFollow = follow.shouldFollow(SystemClock.elapsedRealtime())
        binding.lyricsFollow.visibility = if (data?.timed == true && !canFollow) View.VISIBLE else View.INVISIBLE
        if (canFollow && index >= 0 && index != lastFollowed && binding.root.isShown && binding.lyricsList.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
            lastFollowed = index
            (binding.lyricsList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, binding.lyricsList.height / 3)
        }
    }

    private class LinesAdapter(private val seek: (Long) -> Unit) : RecyclerView.Adapter<LineHolder>() {
        private var lyrics: Lyrics? = null
        private var active = -1
        fun replace(value: Lyrics?) {
            val previous = itemCount
            lyrics = value; active = -1
            if (previous > 0) notifyItemRangeRemoved(0, previous)
            if (itemCount > 0) notifyItemRangeInserted(0, itemCount)
        }
        fun highlight(value: Int) {
            if (value == active) return
            val old = active; active = value
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (value in 0 until itemCount) notifyItemChanged(value)
        }
        override fun getItemCount() = lyrics?.lines?.size ?: 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = LineHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_lyric, parent, false) as TextView)
        override fun onBindViewHolder(holder: LineHolder, position: Int) {
            val line = lyrics!!.lines[position]
            holder.text.text = line.text
            holder.text.setTextColor(ContextCompat.getColor(holder.text.context, if (position == active) R.color.text_primary else R.color.text_secondary))
            holder.text.setTypeface(null, if (position == active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            holder.text.setOnClickListener(if (line.timeMs != null) View.OnClickListener { seek(line.timeMs) } else null)
            holder.text.isClickable = line.timeMs != null
            holder.text.isFocusable = line.timeMs != null
        }
    }
    private class LineHolder(val text: TextView) : RecyclerView.ViewHolder(text)
}
