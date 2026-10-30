package com.boomplay.musicplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import com.boomplay.musicplayer.R
import com.boomplay.musicplayer.databinding.ItemCardBinding
import com.boomplay.musicplayer.databinding.ItemTrackBinding
import com.boomplay.musicplayer.library.Collection
import com.boomplay.musicplayer.library.Track
import com.boomplay.musicplayer.utils.UiUtils

sealed interface CatalogRow {
    data class Note(val text: String) : CatalogRow
    data class Heading(val title: String, val id: String = title, val action: (() -> Unit)? = null) : CatalogRow
    data class Shelf(val items: List<Collection>, val banner: Boolean = false, val id: String = items.firstOrNull()?.let { "${it.type}:${it.id}" }.orEmpty()) : CatalogRow
    data class Song(val track: Track) : CatalogRow
    data class Entry(val title: String, val subtitle: String = "", val cover: String = "", val icon: Int = R.drawable.ic_music, val action: () -> Unit, val more: (() -> Unit)? = null, val id: String = title) : CatalogRow
}

internal object CatalogDiff : DiffUtil.ItemCallback<CatalogRow>() {
    private fun identity(row: CatalogRow): String = when (row) {
        is CatalogRow.Note -> "note:${row.text}"
        is CatalogRow.Heading -> "heading:${row.id}"
        is CatalogRow.Shelf -> "shelf:${row.id}"
        is CatalogRow.Song -> "song:${row.track.id}"
        is CatalogRow.Entry -> "entry:${row.id}"
    }
    override fun areItemsTheSame(oldItem: CatalogRow, newItem: CatalogRow) = identity(oldItem) == identity(newItem)
    override fun areContentsTheSame(oldItem: CatalogRow, newItem: CatalogRow): Boolean = when {
        oldItem is CatalogRow.Heading && newItem is CatalogRow.Heading -> oldItem.title == newItem.title && (oldItem.action != null) == (newItem.action != null)
        oldItem is CatalogRow.Entry && newItem is CatalogRow.Entry -> oldItem.title == newItem.title && oldItem.subtitle == newItem.subtitle && oldItem.cover == newItem.cover && oldItem.icon == newItem.icon && (oldItem.more != null) == (newItem.more != null)
        else -> oldItem == newItem
    }
}

class CatalogAdapter(
    private val openCollection: (Collection) -> Unit,
    private val play: (Track) -> Unit,
    private val songMenu: (Track) -> Unit,
    private val shelfScrolls: MutableMap<String, CatalogScroll> = mutableMapOf(),
) : ListAdapter<CatalogRow, RecyclerView.ViewHolder>(CatalogDiff) {
    val rows: List<CatalogRow> get() = currentList
    private var actions = emptyList<CatalogRow>()
    private val visibleShelves = mutableSetOf<ShelfHolder>()
    fun saveShelfPositions() { visibleShelves.forEach { it.savePosition() } }
    fun submit(value: List<CatalogRow>, committed: () -> Unit = {}) {
        actions = value
        submitList(value, committed)
    }
    private fun latest(row: CatalogRow): CatalogRow? = actions.firstOrNull { CatalogDiff.areItemsTheSame(it, row) }
    override fun getItemViewType(position: Int) = when (rows[position]) {
        is CatalogRow.Note -> 3
        is CatalogRow.Heading -> 0
        is CatalogRow.Shelf -> 1
        else -> 2
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            3 -> Holder(inflater.inflate(R.layout.item_note, parent, false))
            0 -> Holder(inflater.inflate(R.layout.item_heading, parent, false))
            1 -> ShelfHolder(inflater.inflate(R.layout.item_section, parent, false) as RecyclerView)
            else -> SongHolder(ItemTrackBinding.inflate(inflater, parent, false))
        }
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is CatalogRow.Note -> (holder.itemView as TextView).text = row.text
            is CatalogRow.Heading -> (holder.itemView as TextView).apply {
                text = row.title
                isClickable = row.action != null; isFocusable = row.action != null
                setOnClickListener { (latest(row) as? CatalogRow.Heading)?.action?.invoke() }
                setCompoundDrawablesWithIntrinsicBounds(0, 0, if (row.action != null) R.drawable.ic_chevron else 0, 0)
            }
            is CatalogRow.Shelf -> (holder as ShelfHolder).bind(row)
            is CatalogRow.Song -> (holder as SongHolder).binding.apply {
                title.text = row.track.title
                subtitle.text = root.context.getString(R.string.song_subtitle, row.track.artist.name, UiUtils.time(row.track.durationMs))
                subtitle.isVisible = true
                UiUtils.artwork(artwork, row.track.cover)
                more.isVisible = true
                more.contentDescription = root.context.getString(R.string.more) + ": " + row.track.title
                root.setOnClickListener { (latest(row) as? CatalogRow.Song)?.track?.let(play) }
                more.setOnClickListener { (latest(row) as? CatalogRow.Song)?.track?.let(songMenu) }
                root.setOnLongClickListener { (latest(row) as? CatalogRow.Song)?.track?.let { songMenu(it); true } ?: false }
            }
            is CatalogRow.Entry -> (holder as SongHolder).binding.apply {
                title.text = row.title; subtitle.text = row.subtitle; subtitle.isVisible = row.subtitle.isNotBlank()
                if (row.cover.isNotBlank()) UiUtils.artwork(artwork, row.cover) else {
                    com.bumptech.glide.Glide.with(artwork).clear(artwork); artwork.tag = null; artwork.setImageResource(row.icon)
                }
                more.isVisible = row.more != null
                root.setOnClickListener { (latest(row) as? CatalogRow.Entry)?.action?.invoke() }; more.setOnClickListener { (latest(row) as? CatalogRow.Entry)?.more?.invoke() }
                root.setOnLongClickListener { (latest(row) as? CatalogRow.Entry)?.more?.let { it(); true } ?: false }
            }
        }
    }
    private class Holder(view: View) : RecyclerView.ViewHolder(view)
    private class SongHolder(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)
    private inner class ShelfHolder(private val list: RecyclerView) : RecyclerView.ViewHolder(list) {
        private val manager = LinearLayoutManager(list.context, RecyclerView.HORIZONTAL, false)
        private val cards = CardAdapter(openCollection)
        private var section: String? = null
        private var pendingRestore: CatalogScroll? = null
        init {
            list.layoutManager = manager; list.adapter = cards; list.isNestedScrollingEnabled = false
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { savePosition() }
            })
        }
        fun savePosition() {
            if (pendingRestore != null) return
            if (actions.none { it is CatalogRow.Shelf && it.id == section }) return
            val position = manager.findFirstVisibleItemPosition()
            if (position >= 0) section?.let { shelfScrolls[it] = CatalogScroll(position, manager.findViewByPosition(position)?.left?.minus(list.paddingLeft) ?: 0) }
        }
        fun bind(row: CatalogRow.Shelf) {
            if (section != row.id) {
                savePosition()
                pendingRestore = shelfScrolls[row.id] ?: CatalogScroll()
            }
            section = row.id
            cards.submitList(row.items.map { CardItem(it, row.banner) }) {
                if (section == row.id) pendingRestore?.let { position ->
                    manager.scrollToPositionWithOffset(position.position.coerceAtMost((cards.itemCount - 1).coerceAtLeast(0)), position.offset)
                    pendingRestore = null
                }
            }
        }
    }
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) { if (holder is ShelfHolder) holder.savePosition(); super.onViewRecycled(holder) }
    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) { if (holder is ShelfHolder) visibleShelves.add(holder); super.onViewAttachedToWindow(holder) }
    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) { if (holder is ShelfHolder) { holder.savePosition(); visibleShelves.remove(holder) }; super.onViewDetachedFromWindow(holder) }
}

private data class CardItem(val collection: Collection, val banner: Boolean)
private class CardAdapter(val click: (Collection) -> Unit) : ListAdapter<CardItem, CardAdapter.Holder>(object : DiffUtil.ItemCallback<CardItem>() {
    override fun areItemsTheSame(oldItem: CardItem, newItem: CardItem) = oldItem.collection.id == newItem.collection.id && oldItem.collection.type == newItem.collection.type
    override fun areContentsTheSame(oldItem: CardItem, newItem: CardItem) = oldItem == newItem
}) {
    class Holder(val binding: ItemCardBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = with(holder.binding) {
        val card = getItem(position); val item = card.collection; val banner = card.banner
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
