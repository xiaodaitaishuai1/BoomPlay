package com.boomplay.musicplayer

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boomplay.musicplayer.library.RegionPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionSettingsTest {
    @Test fun defaultIsCnAndAllChoicesSurviveRecreation(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
        val name = "test_preferred_region"
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        try {
            assertEquals("CN", RegionPreferences(context, name).countryCode)
            RegionPreferences.CODES.forEach { code ->
                RegionPreferences(context, name).select(code)
                assertEquals(code, RegionPreferences(context, name).countryCode)
            }
            val store = RegionPreferences(context, name)
            try { store.select("US"); fail("Unsupported region accepted") } catch (_: IllegalArgumentException) { }
            assertEquals("TW", store.countryCode)
            coroutineScope {
                repeat(60) { index -> launch(Dispatchers.Default) { store.select(RegionPreferences.CODES[index % 3]) } }
            }
            assertEquals("Concurrent saves must leave disk and memory consistent", store.countryCode, RegionPreferences(context, name).countryCode)
        } finally { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun settingsSelectionPersistsAndShowsOnReopen(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
        val original = app.regions.countryCode
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withId(R.id.settingsButton)).perform(click())
                openRegionChoice()
                onView(withText(R.string.region_hk)).perform(click())
                // Rotation must not cancel saving or cache invalidation owned by the ViewModel.
                scenario.recreate()
                waitForRegion("HK")
                onView(withText(R.string.region_hk)).check(matches(isDisplayed()))
                scenario.recreate()
                onView(withText(R.string.region_hk)).check(matches(isDisplayed()))
                openRegionChoice()
                onView(withText(R.string.region_tw)).perform(click())
                waitForRegion("TW")
                onView(withText(R.string.region_tw)).check(matches(isDisplayed()))
                assertEquals("TW", RegionPreferences(app).countryCode)
                openRegionChoice()
                onView(withText(R.string.region_cn)).perform(click())
                waitForRegion("CN")
                onView(withText(R.string.region_cn)).check(matches(isDisplayed()))
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /sdcard/Download/boomplay-settings.png")).use { it.readBytes() }
                onView(withId(R.id.back)).perform(click())
                onView(allOf(withId(R.id.title), withParent(withId(R.id.root)))).check(matches(withText(R.string.home)))
            }
        } finally { app.regions.select(original) }
    }
    private fun openRegionChoice() {
        onView(withId(R.id.list)).perform(object : androidx.test.espresso.ViewAction {
            override fun getDescription() = "Open the current preferred region row after its diff animation"
            override fun getConstraints(): org.hamcrest.Matcher<android.view.View> = isDisplayed()
            override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
                val list = view as androidx.recyclerview.widget.RecyclerView
                val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                while (list.isComputingLayout || list.itemAnimator?.isRunning == true) {
                    check(android.os.SystemClock.uptimeMillis() < deadline) { "Region row animation did not finish" }
                    controller.loopMainThreadForAtLeast(50)
                }
                val rows = (list.adapter as com.boomplay.musicplayer.ui.CatalogAdapter).rows
                val index = rows.indexOfFirst { it is com.boomplay.musicplayer.ui.CatalogRow.Entry && it.id == "settings:region" }
                val holder = checkNotNull(list.findViewHolderForAdapterPosition(index))
                // A change animation temporarily keeps both the previous and current text views.
                click().perform(controller, holder.itemView)
            }
        })
    }

    private fun waitForRegion(code: String) {
        val app = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
        onView(withId(R.id.list)).perform(object : androidx.test.espresso.ViewAction {
            override fun getDescription() = "Wait for preferred region $code"
            override fun getConstraints(): org.hamcrest.Matcher<android.view.View> = isDisplayed()
            override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
                val end = android.os.SystemClock.uptimeMillis() + 5_000
                while (app.regions.countryCode != code) {
                    if (android.os.SystemClock.uptimeMillis() > end) throw AssertionError("Region not saved")
                    controller.loopMainThreadForAtLeast(50)
                }
                controller.loopMainThreadUntilIdle()
            }
        })
    }
}
