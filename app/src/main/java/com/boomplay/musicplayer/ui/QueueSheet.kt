package com.boomplay.musicplayer.ui

import android.graphics.Typeface
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.boomplay.musicplayer.BoomPlayApplication
import com.boomplay.musicplayer.R
import com.boomplay.musicplayer.library.Track
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

/** Queue actions resolve occurrence IDs at click time, including duplicate songs. */
internal class QueueSheet(private val activity: AppCompatActivity, private val dialogs: LibraryDialogs) {
    private val playback = (activity.application as BoomPlayApplication).playback
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun index(id: String) = playback.queueIndex(id)

    fun show() {
        val sheet = BottomSheetDialog(activity)
        val root = ConstraintLayout(activity).apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
        val title = TextView(activity).apply { id = View.generateViewId(); setText(R.string.queue); textSize = 22f }
        val clear = MaterialButton(activity).apply { id = View.generateViewId(); setText(R.string.clear_queue) }
        val save = MaterialButton(activity).apply { id = View.generateViewId(); setText(R.string.save_queue) }
        val list = RecyclerView(activity).apply {
            id = View.generateViewId()
            layoutManager = LinearLayoutManager(activity)
            itemAnimator = null
        }
        val empty = TextView(activity).apply { id = View.generateViewId(); setText(R.string.queue_empty); textSize = 18f }
        root.addView(title); root.addView(clear); root.addView(save); root.addView(empty)
        root.addView(list, ConstraintLayout.LayoutParams(0, 0))
        ConstraintSet().apply {
            clone(root)
            connect(title.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(title.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(clear.id, ConstraintSet.TOP, title.id, ConstraintSet.BOTTOM, dp(12))
            connect(clear.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(clear.id, ConstraintSet.END, save.id, ConstraintSet.START, dp(8))
            constrainWidth(clear.id, 0)
            connect(save.id, ConstraintSet.TOP, clear.id, ConstraintSet.TOP)
            connect(save.id, ConstraintSet.START, clear.id, ConstraintSet.END)
            connect(save.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constrainWidth(save.id, 0)
            connect(empty.id, ConstraintSet.TOP, clear.id, ConstraintSet.BOTTOM, dp(20))
            connect(empty.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(list.id, ConstraintSet.TOP, clear.id, ConstraintSet.BOTTOM, dp(8))
            connect(list.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            connect(list.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(list.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            applyTo(root)
        }
        var dragging = false
        lateinit var touch: ItemTouchHelper
        val adapter = QueueAdapter(
            play = { id -> index(id).takeIf { it >= 0 }?.let(playback::playIndex) },
            remove = { id -> index(id).takeIf { it >= 0 }?.let(playback::removeQueueItem) },
            drag = { holder -> touch.startDrag(holder) },
        )
        list.adapter = adapter
        fun refresh() {
            if (dragging) return
            val entries = playback.queueEntries()
            adapter.update(entries, entries.getOrNull(playback.state.value.index)?.first)
            empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            clear.isEnabled = entries.isNotEmpty(); save.isEnabled = entries.isNotEmpty()
        }
        touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = source.bindingAdapterPosition; val to = target.bindingAdapterPosition
                if (from !in adapter.rows.indices || to !in adapter.rows.indices) return false
                val currentFrom = index(adapter.rows[from].first); val currentTo = index(adapter.rows[to].first)
                if (currentFrom < 0 || currentTo < 0) return false
                adapter.move(from, to)
                playback.moveQueueItem(currentFrom, currentTo)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) dragging = true
                super.onSelectedChanged(viewHolder, actionState)
            }
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                dragging = false; refresh()
            }
        })
        touch.attachToRecyclerView(list)
        clear.setOnClickListener {
            MaterialAlertDialogBuilder(activity).setTitle(R.string.clear_queue).setMessage(R.string.clear_queue_message)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.clear_queue) { _, _ -> playback.clearQueue() }.show()
        }
        save.setOnClickListener {
            val snapshot = playback.state.value.queue.toList()
            if (snapshot.isEmpty()) return@setOnClickListener
            dialogs.name { name ->
                (activity.application as BoomPlayApplication).library.saveQueueAsPlaylist(name, snapshot)
                android.widget.Toast.makeText(activity, R.string.queue_saved, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        // A match-constraint list cannot determine a wrap-content parent's height.
        // Give the sheet an explicit viewport so rows are measured even before first layout.
        val sheetHeight = (activity.resources.displayMetrics.heightPixels * .72).toInt()
        sheet.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, sheetHeight))
        sheet.setOnShowListener {
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        val job = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(playback.queueRevision, playback.state.map { it.queue to it.index }.distinctUntilChanged()) { _, _ -> Unit }
                    .collect { refresh() }
            }
        }
        sheet.setOnDismissListener { job.cancel(); touch.attachToRecyclerView(null) }
        refresh(); sheet.show()
    }

    private inner class QueueAdapter(
        val play: (String) -> Unit,
        val remove: (String) -> Unit,
        val drag: (RecyclerView.ViewHolder) -> Unit,
    ) : RecyclerView.Adapter<QueueAdapter.Holder>() {
        val rows = mutableListOf<Pair<String, Track>>()
        private val ids = mutableMapOf<String, Long>()
        private var nextId = 0L
        private var current: String? = null
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = ids.getOrPut(rows[position].first) { nextId++ }
        override fun getItemCount() = rows.size
        fun update(entries: List<Pair<String, Track>>, playing: String?) {
            if (rows == entries && current == playing) return
            val oldRows = rows.toList(); val oldCurrent = current
            rows.clear(); rows.addAll(entries); current = playing
            ids.keys.retainAll(entries.mapTo(mutableSetOf()) { it.first })
            androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = oldRows.size
                override fun getNewListSize() = entries.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    oldRows[oldItemPosition].first == entries[newItemPosition].first
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = oldRows[oldItemPosition]; val new = entries[newItemPosition]
                    return old == new && (old.first == oldCurrent) == (new.first == playing)
                }
            }).dispatchUpdatesTo(this)
        }
        fun move(from: Int, to: Int) { rows.add(to, rows.removeAt(from)); notifyItemMoved(from, to) }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(ConstraintLayout(activity))
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])
        inner class Holder(private val root: ConstraintLayout) : RecyclerView.ViewHolder(root) {
            private val handle = TextView(activity).apply {
                id = View.generateViewId(); text = "≡"; textSize = 28f
                contentDescription = activity.getString(R.string.drag_queue_item)
                gravity = android.view.Gravity.CENTER
            }
            private val label = TextView(activity).apply { id = View.generateViewId(); textSize = 16f; maxLines = 2 }
            private val delete = androidx.constraintlayout.utils.widget.ImageFilterView(activity).apply {
                id = View.generateViewId(); setImageResource(R.drawable.ic_queue_remove)
                scaleType = android.widget.ImageView.ScaleType.CENTER
                isClickable = true; isFocusable = true
                contentDescription = activity.getString(R.string.remove_queue_item)
            }
            init {
                root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                root.minimumHeight = dp(64)
                root.addView(handle, ConstraintLayout.LayoutParams(dp(48), dp(56)))
                root.addView(label, ConstraintLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT))
                root.addView(delete, ConstraintLayout.LayoutParams(dp(48), dp(56)))
                ConstraintSet().apply {
                    clone(root)
                    connect(handle.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    connect(handle.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                    connect(label.id, ConstraintSet.START, handle.id, ConstraintSet.END)
                    connect(label.id, ConstraintSet.END, delete.id, ConstraintSet.START, dp(8))
                    connect(label.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, dp(10))
                    connect(label.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, dp(10))
                    connect(delete.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                    connect(delete.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                    applyTo(root)
                }
                setupDragTouch()
            }
            @SuppressLint("ClickableViewAccessibility") // Accessible click opens equivalent move up/down actions; dragging is touch-only.
            private fun setupDragTouch() {
                var downY = 0f
                val slop = android.view.ViewConfiguration.get(activity).scaledTouchSlop
                handle.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> downY = event.y
                        MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.y - downY) > slop) drag(this)
                    }
                    false
                }
            }
            fun bind(row: Pair<String, Track>) {
                label.text = if (row.first == current) activity.getString(R.string.queue_playing_title, row.second.title) else row.second.title
                label.setTypeface(null, if (row.first == current) Typeface.BOLD else Typeface.NORMAL)
                label.setOnClickListener { play(row.first) }
                delete.setOnClickListener { remove(row.first) }
                handle.setOnClickListener {
                    val position = index(row.first)
                    if (position < 0) return@setOnClickListener
                    val choices = listOfNotNull(
                        (position - 1).takeIf { it >= 0 }?.let { R.string.queue_move_up to it },
                        (position + 1).takeIf { it < rows.size }?.let { R.string.queue_move_down to it },
                    )
                    if (choices.isNotEmpty()) MaterialAlertDialogBuilder(activity)
                        .setItems(choices.map { activity.getString(it.first) }.toTypedArray()) { _, chosen ->
                            val latest = index(row.first)
                            if (latest >= 0) playback.moveQueueItem(latest, choices[chosen].second)
                        }.show()
                }
            }
        }
    }
}
