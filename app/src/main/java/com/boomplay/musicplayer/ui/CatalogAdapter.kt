package com.boomplay.musicplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.boomplay.musicplayer.R
import com.boomplay.musicplayer.databinding.ItemCardBinding
import com.boomplay.musicplayer.databinding.ItemTrackBinding
import com.boomplay.musicplayer.library.Collection
import com.boomplay.musicplayer.library.Track
import com.boomplay.musicplayer.utils.UiUtils

sealed interface CatalogRow {
    data class Heading(val title: String, val action: (() -> Unit)? = null) : CatalogRow
    data class Shelf(val items: List<Collection>, val banner: Boolean = false) : CatalogRow
    data class Song(val track: Track) : CatalogRow
    data class Entry(val title: String, val subtitle: String = "", val cover: String = "", val icon: Int = R.drawable.ic_music, val action: () -> Unit, val more: (() -> Unit)? = null) : CatalogRow
}

class CatalogAdapter(
    private val openCollection: (Collection) -> Unit,
    private val play: (Track) -> Unit,
    private val songMenu: (Track) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var rows: List<CatalogRow> = emptyList(); private set
    fun submit(value: List<CatalogRow>) { rows = value; notifyDataSetChanged() }
    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = when (rows[position]) {
        is CatalogRow.Heading -> 0
        is CatalogRow.Shelf -> 1
        else -> 2
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> Holder(inflater.inflate(R.layout.item_heading, parent, false))
            1 -> Holder(inflater.inflate(R.layout.item_section, parent, false))
            else -> SongHolder(ItemTrackBinding.inflate(inflater, parent, false))
        }
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is CatalogRow.Heading -> (holder.itemView as TextView).apply {
                text = row.title
                isClickable = row.action != null; isFocusable = row.action != null
                setOnClickListener { row.action?.invoke() }
                setCompoundDrawablesWithIntrinsicBounds(0, 0, if (row.action != null) R.drawable.ic_chevron else 0, 0)
            }
            is CatalogRow.Shelf -> (holder.itemView as RecyclerView).apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                adapter = CardAdapter(row.items, row.banner, openCollection)
                isNestedScrollingEnabled = false
            }
            is CatalogRow.Song -> (holder as SongHolder).binding.apply {
                title.text = row.track.title
                subtitle.text = root.context.getString(R.string.song_subtitle, row.track.artist.name, UiUtils.time(row.track.durationMs))
                subtitle.isVisible = true
                UiUtils.artwork(artwork, row.track.cover)
                more.isVisible = true
                more.contentDescription = root.context.getString(R.string.more) + ": " + row.track.title
                root.setOnClickListener { play(row.track) }; more.setOnClickListener { songMenu(row.track) }
                root.setOnLongClickListener { songMenu(row.track); true }
            }
            is CatalogRow.Entry -> (holder as SongHolder).binding.apply {
                title.text = row.title; subtitle.text = row.subtitle; subtitle.isVisible = row.subtitle.isNotBlank()
                if (row.cover.isNotBlank()) UiUtils.artwork(artwork, row.cover) else {
                    com.bumptech.glide.Glide.with(artwork).clear(artwork); artwork.tag = null; artwork.setImageResource(row.icon)
                }
                more.isVisible = row.more != null
                root.setOnClickListener { row.action() }; more.setOnClickListener { row.more?.invoke() }
                root.setOnLongClickListener { row.more?.invoke(); row.more != null }
            }
        }
    }
    private class Holder(view: View) : RecyclerView.ViewHolder(view)
    private class SongHolder(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)
}

private class CardAdapter(val items: List<Collection>, val banner: Boolean, val click: (Collection) -> Unit) : RecyclerView.Adapter<CardAdapter.Holder>() {
    class Holder(val binding: ItemCardBinding) : RecyclerView.ViewHolder(binding.root)
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = with(holder.binding) {
        val item = items[position]
        val dp = root.resources.displayMetrics.density
        root.layoutParams.width = ((if (banner) 304 else 152) * dp).toInt()
        (artwork.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).dimensionRatio = if (banner) "2:1" else "1:1"
        artwork.round = Float.NaN
        artwork.roundPercent = if (item.type == 2) 1f else .15f
        title.text = item.title
        subtitle.setText(when (item.type) { 2 -> R.string.artist_type; 5 -> R.string.album_type; else -> R.string.playlist_type })
        UiUtils.artwork(artwork, item.cover)
        root.setOnClickListener { click(item) }
    }
}
