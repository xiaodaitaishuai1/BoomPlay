package com.boomplay.musicplayer.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.widget.FrameLayout
import android.view.View
import android.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.boomplay.musicplayer.BoomPlayApplication
import com.boomplay.musicplayer.R
import com.boomplay.musicplayer.library.Collection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CatalogAdapterTest {
    @Test fun callbacksDoNotInvalidateIdenticalContentAndDuplicateNamesKeepDistinctIdentity() {
        val first = CatalogRow.Entry("Same", action = {}, id = "playlist:1")
        val rebound = CatalogRow.Entry("Same", action = {}, id = "playlist:1")
        val other = CatalogRow.Entry("Same", action = {}, id = "playlist:2")
        assertTrue(CatalogDiff.areItemsTheSame(first, rebound))
        assertTrue(CatalogDiff.areContentsTheSame(first, rebound))
        assertFalse(CatalogDiff.areItemsTheSame(first, other))
        assertFalse(CatalogDiff.areContentsTheSame(first, rebound.copy(subtitle = "Updated")))
        assertFalse(CatalogDiff.areItemsTheSame(
            CatalogRow.Entry("Clear history", action = {}, id = "search:clear"),
            CatalogRow.Entry("Clear history", action = {}, id = "search:query:Clear history"),
        ))
    }

    @Test fun bindingShelfAgainReusesLayoutManagerAndAdapter() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = themedContext()
            val adapter = CatalogAdapter({}, {}, {})
            adapter.submit(listOf(CatalogRow.Shelf((1L..20L).map { Collection(it, title = "Card $it") }, id = "home:section:1")))
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 1)
            adapter.onBindViewHolder(holder, 0)
            val shelf = holder.itemView as RecyclerView
            val manager = shelf.layoutManager
            val cards = shelf.adapter
            adapter.onBindViewHolder(holder, 0)
            assertSame(manager, shelf.layoutManager)
            assertSame(cards, shelf.adapter)
        }
    }

    @Test fun shelfScrollSurvivesRecycledHolder() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = themedContext()
            val positions = mutableMapOf<String, CatalogScroll>()
            val adapter = CatalogAdapter({}, {}, {}, positions)
            adapter.submit(listOf(CatalogRow.Shelf((1L..20L).map { Collection(it, title = "Card $it") }, id = "home:section:1")))
            val parent = FrameLayout(context)
            val first = adapter.onCreateViewHolder(parent, 1)
            adapter.onBindViewHolder(first, 0)
            val shelf = first.itemView as RecyclerView
            layout(shelf)
            val manager = shelf.layoutManager as LinearLayoutManager
            manager.scrollToPositionWithOffset(4, -12)
            layout(shelf)
            adapter.onViewRecycled(first)
            assertEquals(CatalogScroll(4, -12), positions["home:section:1"])
            val restored = adapter.onCreateViewHolder(parent, 1)
            adapter.onBindViewHolder(restored, 0)
            layout(restored.itemView)
            val restoredManager = (restored.itemView as RecyclerView).layoutManager as LinearLayoutManager
            assertEquals(4, restoredManager.findFirstVisibleItemPosition())
            val restoredShelf = restored.itemView as RecyclerView
            assertEquals(-12, restoredManager.findViewByPosition(4)?.left?.minus(restoredShelf.paddingLeft))
        }
    }

    @Test fun unchangedRowUsesNewActionWithoutRebinding() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val committed = CountDownLatch(1)
        var clicked = ""
        lateinit var holder: RecyclerView.ViewHolder
        lateinit var adapter: CatalogAdapter
        instrumentation.runOnMainSync {
            val context = themedContext()
            adapter = CatalogAdapter({}, {}, {})
            adapter.submit(listOf(CatalogRow.Entry("Same", action = { clicked = "old" }, id = "entry")))
            holder = adapter.onCreateViewHolder(FrameLayout(context), 2)
            adapter.onBindViewHolder(holder, 0)
            adapter.submit(listOf(CatalogRow.Entry("Same", action = { clicked = "new" }, id = "entry"))) { committed.countDown() }
        }
        assertTrue(committed.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { holder.itemView.performClick() }
        assertEquals("new", clicked)
    }

    @Test fun rapidSameShelfBindsKeepPendingScrollRestore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val submitted = CountDownLatch(1)
        lateinit var shelf: RecyclerView
        instrumentation.runOnMainSync {
            val adapter = CatalogAdapter({}, {}, {}, mutableMapOf("second" to CatalogScroll(4, -12)))
            val first = CatalogRow.Shelf((1L..20L).map { Collection(it, title = "First $it") }, id = "first")
            adapter.submit(listOf(first))
            val holder = adapter.onCreateViewHolder(FrameLayout(themedContext()), 1)
            adapter.onBindViewHolder(holder, 0)
            shelf = holder.itemView as RecyclerView
            layout(shelf)
            val second = CatalogRow.Shelf((21L..40L).map { Collection(it, title = "Second $it") }, id = "second")
            adapter.submit(listOf(second)) {
                adapter.onBindViewHolder(holder, 0)
                adapter.onBindViewHolder(holder, 0)
                submitted.countDown()
            }
        }
        assertTrue(submitted.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var restored = false
        while (!restored && System.nanoTime() < deadline) {
            instrumentation.runOnMainSync {
                layout(shelf)
                val manager = shelf.layoutManager as LinearLayoutManager
                restored = manager.findFirstVisibleItemPosition() == 4 && manager.findViewByPosition(4)?.left?.minus(shelf.paddingLeft) == -12
            }
            if (!restored) Thread.sleep(10)
        }
        assertTrue("The last same-section commit must apply the pending target", restored)
    }

    @Test fun removedShelfDoesNotRepopulatePrunedPositionWhenRecycled() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val positions = mutableMapOf<String, CatalogScroll>()
            val adapter = CatalogAdapter({}, {}, {}, positions)
            adapter.submit(listOf(CatalogRow.Shelf((1L..20L).map { Collection(it, title = "Card $it") }, id = "home:obsolete")))
            val holder = adapter.onCreateViewHolder(FrameLayout(themedContext()), 1)
            adapter.onBindViewHolder(holder, 0)
            layout(holder.itemView)
            adapter.submit(emptyList())
            positions.clear()
            adapter.onViewRecycled(holder)
            assertFalse(positions.containsKey("home:obsolete"))
        }
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 600)
    }
    private fun themedContext() = ContextThemeWrapper(ApplicationProvider.getApplicationContext<BoomPlayApplication>(), R.style.Theme_BoomPlay)
}
