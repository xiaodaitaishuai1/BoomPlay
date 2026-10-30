package com.boomplay.musicplayer

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boomplay.musicplayer.ui.CatalogAdapter
import com.boomplay.musicplayer.ui.CatalogRow
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production smoke test: intentionally fails if the documented backend/audio is unavailable. */
@RunWith(AndroidJUnit4::class)
class LiveMusicFlowTest {
    @Test fun browseSearchPlayAndCaptureScreens(): Unit {
        val app = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) { android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() } }
        shell("pm grant com.boomplay.musicplayer android.permission.POST_NOTIFICATIONS")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitList { rows -> rows.any { it is CatalogRow.Shelf && it.items.any { c -> c.type == 1 && c.groupId != 10003L } } }
            capture("home")
            onView(withId(R.id.list)).perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isAssignableFrom(RecyclerView::class.java)
                override fun getDescription() = "Open first playlist card"
                override fun perform(controller: UiController, view: View) {
                    val list = view as RecyclerView
                    val rows = (list.adapter as CatalogAdapter).rows
                    val index = rows.indexOfFirst { it is CatalogRow.Shelf && it.items.any { c -> c.type == 1 && c.groupId != 10003L } }
                    list.scrollToPosition(index); controller.loopMainThreadUntilIdle()
                    val shelf = list.findViewHolderForAdapterPosition(index)!!.itemView as RecyclerView
                    shelf.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                }
            })
            waitList { rows -> rows.any { it is CatalogRow.Song } }
            capture("list")
            onView(withId(R.id.playAll)).perform(click())
            waitUntil { app.playback.state.value.playing || app.playback.state.value.error != null }
            assertNull("Live audio failed", app.playback.state.value.error)
            assertTrue(app.playback.state.value.playing)
            capture("player")
            onView(withId(R.id.favorite)).perform(click())
            onView(withId(R.id.queue)).perform(click())
            onView(withText(R.string.cancel)).perform(click())
            val firstId = app.playback.state.value.current!!.id
            onView(withId(R.id.next)).perform(click())
            waitUntil { app.playback.state.value.current?.id != firstId && app.playback.state.value.playing }
            val before = app.playback.state.value.positionMs
            onView(withId(R.id.forward)).perform(click())
            waitUntil { app.playback.state.value.positionMs >= before + 8_000 }
            onView(withId(R.id.back)).perform(click())
            onView(withId(R.id.back)).perform(click())
            onView(withId(R.id.libraryTab)).perform(click())
            capture("library")
            onView(withId(R.id.searchButton)).perform(click())
            onView(withId(R.id.query)).perform(replaceText("Moonlight"), pressImeActionButton(), closeSoftKeyboard())
            waitList { rows -> rows.any { it is CatalogRow.Song } }
            capture("search")
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(1_000)
            assertTrue("Playback must continue in background", app.playback.state.value.playing)
            app.playback.toggle()
        }
    }
    private fun waitList(predicate: (List<CatalogRow>) -> Boolean) {
        onView(withId(R.id.list)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = allOf(isAssignableFrom(RecyclerView::class.java), isDisplayed())
            override fun getDescription() = "Wait for production music content"
            override fun perform(controller: UiController, view: View) {
                val start = android.os.SystemClock.uptimeMillis()
                while (!predicate(((view as RecyclerView).adapter as CatalogAdapter).rows)) {
                    if (android.os.SystemClock.uptimeMillis() - start > 55_000) throw AssertionError("Production content did not load")
                    controller.loopMainThreadForAtLeast(100)
                }
            }
        })
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 55_000
        while (!predicate()) { if (android.os.SystemClock.uptimeMillis() > deadline) throw AssertionError("Playback state timed out"); Thread.sleep(100) }
    }
    private fun capture(name: String) {
        Thread.sleep(800)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/boomplay-$name.png")).use { it.readBytes() }
    }
}
