package com.boomplay.musicplayer.ui

import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.boomplay.musicplayer.BoomPlayApplication
import com.boomplay.musicplayer.R
import com.boomplay.musicplayer.playback.AudioQuality
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ListeningDialogs(private val activity: AppCompatActivity) {
    private val app get() = activity.application as BoomPlayApplication
    fun options(songActions: () -> Unit) {
        val labels = arrayOf(R.string.sleep_timer, R.string.audio_quality, R.string.song_actions).map(activity::getString)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.listening_tools).setItems(labels.toTypedArray()) { _, index ->
            when (index) { 0 -> sleepTimer(); 1 -> quality(); else -> songActions() }
        }.show()
    }

    fun sleepTimer() {
        val options = listOf(activity.getString(R.string.timer_off)) + listOf(15, 30, 60).map { activity.resources.getQuantityString(R.plurals.timer_minutes, it, it) } +
            listOf(activity.getString(R.string.timer_custom), activity.getString(R.string.timer_end_of_track))
        MaterialAlertDialogBuilder(activity).setTitle(R.string.sleep_timer).setItems(options.toTypedArray()) { _, index ->
            when (index) {
                0 -> app.playback.cancelSleepTimer()
                in 1..3 -> app.playback.setSleepTimer(listOf(15, 30, 60)[index - 1])
                4 -> customTimer()
                5 -> app.playback.stopAfterCurrentTrack()
            }
        }.show()
    }

    private fun customTimer() {
        val field = EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); hint = activity.getString(R.string.timer_custom) }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.sleep_timer).setView(field)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val minutes = field.text.toString().toIntOrNull()
            if (minutes == null || minutes !in 1..1440) field.error = activity.getString(R.string.timer_invalid)
            else { app.playback.setSleepTimer(minutes); dialog.dismiss() }
        } }
        dialog.show()
    }

    fun quality() {
        val values = AudioQuality.entries
        val labels = listOf(R.string.quality_auto, R.string.quality_high, R.string.quality_medium, R.string.quality_low).map(activity::getString)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.audio_quality)
            .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(app.listening.quality.value)) { dialog, index ->
                app.listening.setQuality(values[index])
                Toast.makeText(activity, R.string.quality_next_track, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }.setNegativeButton(R.string.cancel, null).show()
    }
}
