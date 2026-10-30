package com.boomplay.musicplayer

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boomplay.musicplayer.ui.CatalogAdapter
import com.boomplay.musicplayer.ui.CatalogRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Local navigation acceptance test; no assertions depend on the production API. */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @Test fun createPlaylistReopenLibraryAndUseSearchHistory(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
        val marker = "Acceptance ${System.nanoTime()}"
        val search = "history ${System.nanoTime()}"
        var playlistId: Long? = null
        try {
            app.library.rememberSearch(search)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(allOf(withId(R.id.title), withParent(withId(R.id.root)))).check(matches(withText(R.string.home)))
                onView(withId(R.id.libraryTab)).perform(click())
                onView(withId(R.id.list)).perform(scrollToEntry(app.getString(R.string.new_playlist)))
                entry(app.getString(R.string.new_playlist)).perform(click())
                onView(withHint(R.string.playlist_name)).perform(replaceText(marker), closeSoftKeyboard())
                onView(withId(android.R.id.button1)).perform(click())
                playlistId = withTimeout(5_000) { app.library.playlists.first { rows -> rows.any { it.name == marker } }.first { it.name == marker }.id }
                onView(withId(R.id.list)).perform(scrollToEntry(marker))
                entry(marker).check(matches(isDisplayed()))

                // Recreate the activity to verify UI restores the saved local collection.
                scenario.recreate()
                onView(withId(R.id.list)).perform(scrollToEntry(marker))
                entry(marker).perform(click())
                onView(allOf(withId(R.id.title), withParent(withId(R.id.root)))).check(matches(withText(marker)))
                onView(withId(R.id.playAll)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
                onView(withText(R.string.empty)).check(matches(isDisplayed()))
                onView(withId(R.id.back)).perform(click())

                onView(withId(R.id.searchButton)).perform(click())
                onView(withId(R.id.query)).check(matches(isDisplayed()))
                onView(withId(R.id.list)).perform(scrollToEntry(search))
                entry(search).check(matches(isDisplayed()))
                onView(withId(R.id.query)).perform(replaceText("unfinished query"), closeSoftKeyboard())
                onView(withId(R.id.clearQuery)).perform(click())
                onView(withId(R.id.query)).check(matches(withText("")))
                assertTrue(app.library.searches.first().contains(search))
            }
            // A fresh Activity launch obtains the collection from the persistent repository.
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withId(R.id.libraryTab)).perform(click())
                onView(withId(R.id.list)).perform(scrollToEntry(marker))
                entry(marker).check(matches(isDisplayed()))
            }
        } finally {
            playlistId?.let { app.library.deletePlaylist(it) }
            app.library.removeSearch(search)
        }
    }

    private fun entry(title: String) = onView(allOf(withText(title), isDescendantOfA(withId(R.id.list))))

    private fun scrollToEntry(title: String) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = allOf(isAssignableFrom(RecyclerView::class.java), isDisplayed())
        override fun getDescription() = "Wait for local entry and scroll to $title"
        override fun perform(uiController: UiController, view: View) {
            val list = view as RecyclerView
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            do {
                val rows = (list.adapter as CatalogAdapter).rows
                val position = rows.indexOfFirst { it is CatalogRow.Entry && it.title == title }
                if (position >= 0) {
                    list.scrollToPosition(position)
                    uiController.loopMainThreadUntilIdle()
                    return
                }
                uiController.loopMainThreadForAtLeast(50)
            } while (android.os.SystemClock.uptimeMillis() < deadline)
            throw AssertionError("Local entry not rendered: $title")
        }
    }
}
