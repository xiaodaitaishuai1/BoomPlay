package com.boomplay.musicplayer.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boomplay.musicplayer.BoomPlayApplication
import com.boomplay.musicplayer.PlayerActivity
import com.boomplay.musicplayer.R
import com.boomplay.musicplayer.library.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetBehavior

@RunWith(AndroidJUnit4::class)
class QueueSheetTest {
    private val app get() = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!predicate() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("Queue operation timed out", predicate())
    }
    private fun sheetSettled() = object : ViewAction {
        override fun getDescription() = "Wait for expanded sheet to regain input focus and stop moving"
        override fun getConstraints() = isAssignableFrom(android.widget.FrameLayout::class.java)
        override fun perform(uiController: UiController, view: View) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            var stable = 0
            var oldY = Int.MIN_VALUE
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val location = IntArray(2); view.getLocationOnScreen(location)
                val ready = view.hasWindowFocus() && BottomSheetBehavior.from(view).state == BottomSheetBehavior.STATE_EXPANDED
                stable = if (ready && location[1] == oldY) stable + 1 else 0
                if (stable >= 5) return
                oldY = location[1]
                uiController.loopMainThreadForAtLeast(50)
            }
            error("Queue sheet did not settle or regain focus")
        }
    }

    @Test fun sheetReordersSavesAndClearsQueue() {
        val first = Track(903001, "Queue UI one", hdSource = "http://127.0.0.1:1/audio.mp3")
        val second = first.copy(id = 903002, title = "Queue UI two")
        val name = "UI Queue ${System.nanoTime()}"
        var savedId: Long? = null
        try {
            ActivityScenario.launch(PlayerActivity::class.java).use {
                main { app.playback.play(listOf(first, second)) }
                await { app.playback.state.value.queue.size == 2 }
                onView(withId(R.id.queue)).perform(click())
                onView(withId(com.google.android.material.R.id.design_bottom_sheet)).perform(sheetSettled())
                onView(allOf(withContentDescription(R.string.drag_queue_item), hasSibling(withText(containsString(first.title)))))
                    .perform(click())
                onView(withText(R.string.queue_move_down)).perform(click())
                await { app.playback.state.value.queue.firstOrNull()?.id == second.id }
                onView(withId(com.google.android.material.R.id.design_bottom_sheet)).perform(sheetSettled())
                onView(withText(R.string.save_queue)).perform(click())
                onView(isAssignableFrom(android.widget.EditText::class.java)).perform(replaceText(name))
                onView(withId(android.R.id.button1)).perform(click())
                await { runBlocking { app.library.playlists.first().any { it.name == name } } }
                savedId = runBlocking { app.library.playlists.first().first { it.name == name }.id }
                assertEquals(listOf(second.id, first.id), runBlocking { app.library.playlistTracks(savedId!!).first().map { it.id } })
                onView(withText(R.string.clear_queue)).perform(click())
                onView(withId(android.R.id.button1)).perform(click())
                await { app.playback.state.value.queue.isEmpty() }
                onView(withText(R.string.queue_empty)).check(matches(isDisplayed()))
                onView(withText(R.string.save_queue)).check(matches(isNotEnabled()))
            }
        } finally {
            savedId?.let { runBlocking { app.library.deletePlaylist(it) } }
            main { app.playback.clearQueue() }
        }
    }
}
