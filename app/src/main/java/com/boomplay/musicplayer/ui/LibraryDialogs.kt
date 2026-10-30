package com.boomplay.musicplayer.ui

import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.boomplay.musicplayer.BoomPlayApplication
import com.boomplay.musicplayer.R
import com.boomplay.musicplayer.library.Track
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LibraryDialogs(private val activity: AppCompatActivity) {
    private val app get() = activity.application as BoomPlayApplication
    fun perform(block: suspend () -> Unit) { activity.lifecycleScope.launch {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Toast.makeText(activity, R.string.operation_failed, Toast.LENGTH_LONG).show() }
    } }
    fun name(initial: String = "", saved: suspend (String) -> Unit) {
        val field = EditText(activity).apply { setSingleLine(); setText(initial); hint = activity.getString(R.string.playlist_name); setPadding(32, 24, 32, 24) }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(if (initial.isBlank()) R.string.new_playlist else R.string.rename)
            .setView(field).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val value = field.text.toString().trim()
            if (value.isBlank()) field.error = activity.getString(R.string.name_required)
            else perform { saved(value); dialog.dismiss() }
        } }; dialog.show()
    }
    fun create() = name { app.library.createPlaylist(it) }
    fun playlistMenu(id: Long, title: String, deleted: () -> Unit = {}) {
        MaterialAlertDialogBuilder(activity).setTitle(title).setItems(arrayOf(activity.getString(R.string.rename), activity.getString(R.string.delete))) { _, choice ->
            if (choice == 0) name(title) { app.library.renamePlaylist(id, it) }
            else MaterialAlertDialogBuilder(activity).setTitle(R.string.delete_playlist).setMessage(R.string.delete_playlist_message)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.delete) { _, _ -> perform { app.library.deletePlaylist(id); deleted() } }.show()
        }.show()
    }
    fun pickPlaylist(track: Track) = perform {
        val playlists = app.library.playlists.first()
        val labels = playlists.map { it.name } + activity.getString(R.string.new_playlist)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.add_to_playlist).setItems(labels.toTypedArray()) { _, index ->
            if (index == playlists.size) name { val id = app.library.createPlaylist(it); app.library.addToPlaylist(id, track) }
            else perform { app.library.addToPlaylist(playlists[index].id, track); Toast.makeText(activity, R.string.added, Toast.LENGTH_SHORT).show() }
        }.show()
    }
    fun trackMenu(track: Track, playlistId: Long? = null) = perform {
        val favorite = app.library.favorites.first().any { it.id == track.id }
        val actions = mutableListOf(
            (if (favorite) R.string.remove_favorite else R.string.add_favorite) to { perform { app.library.toggleFavorite(track) } },
            R.string.add_to_playlist to { pickPlaylist(track); Unit },
            R.string.add_to_queue to { app.playback.enqueue(track); Unit },
            R.string.play_next to { app.playback.enqueue(track, true); Unit },
        )
        if (playlistId != null) actions.add(R.string.remove_from_playlist to { perform { app.library.removeFromPlaylist(playlistId, track.id) } })
        MaterialAlertDialogBuilder(activity).setTitle(track.title).setItems(actions.map { activity.getString(it.first) }.toTypedArray()) { _, index -> actions[index].second() }.show()
    }
    fun queue() {
        val state = app.playback.state.value
        MaterialAlertDialogBuilder(activity).setTitle(R.string.queue)
            .setSingleChoiceItems(state.queue.map { it.title }.toTypedArray(), state.index) { dialog, index -> app.playback.playIndex(index); dialog.dismiss() }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
