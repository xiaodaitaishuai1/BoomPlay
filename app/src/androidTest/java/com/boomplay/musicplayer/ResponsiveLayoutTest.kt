package com.boomplay.musicplayer

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boomplay.musicplayer.databinding.ActivityMainBinding
import com.boomplay.musicplayer.databinding.ActivityPlayerBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveLayoutTest {
    @Test fun playerControlsRemainReachableOnSmallAndLandscapeWindows() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            listOf(320 to 568, 568 to 320).forEach { (widthDp, heightDp) ->
                val context = context(widthDp, heightDp)
                val binding = ActivityPlayerBinding.inflate(LayoutInflater.from(context))
                binding.title.text = "A long music title that wraps across several lines"
                binding.artist.text = "An artist name with enough text to test accessibility"
                binding.elapsed.text = "10:45"; binding.duration.text = "12:00"
                measure(binding.root, widthDp, heightDp)
                listOf(binding.play, binding.next, binding.previous, binding.favorite, binding.queue, binding.repeat, binding.add).forEach { control ->
                    assertTrue("Control width at $widthDp dp", control.width >= px(context, 48))
                    assertTrue("Control remains in horizontal bounds", control.left >= 0 && control.right <= (control.parent as View).width)
                }
                assertTrue(binding.cover.width <= px(context, widthDp))
                assertTrue("Progress has a measurable track", binding.progress.width > 0)
            }
        }
    }
    @Test fun searchAndMiniPlayerLeaveUsableContentWithLargeFonts() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = context(320, 568)
            val binding = ActivityMainBinding.inflate(LayoutInflater.from(context))
            binding.searchBox.visibility = View.VISIBLE; binding.searchButton.visibility = View.GONE
            binding.libraryTab.visibility = View.GONE; binding.actions.visibility = View.VISIBLE
            binding.miniPlayer.root.visibility = View.VISIBLE
            binding.miniPlayer.title.text = "A long song name that needs truncation"
            binding.miniPlayer.artist.text = "Artist name"
            measure(binding.root, 320, 568)
            assertTrue(binding.list.height >= px(context, 120))
            assertTrue(binding.miniPlayer.toggle.width >= px(context, 48))
            assertTrue(binding.query.width > px(context, 100))
            assertTrue(binding.miniPlayer.title.width > 0)
        }
    }
    private fun context(width: Int, height: Int): android.content.Context {
        val app = ApplicationProvider.getApplicationContext<BoomPlayApplication>()
        val config = Configuration(app.resources.configuration).apply { screenWidthDp = width; screenHeightDp = height; fontScale = 1.5f }
        return ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_BoomPlay)
    }
    private fun px(context: android.content.Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun measure(view: View, width: Int, height: Int) {
        val parent = FrameLayout(view.context)
        parent.addView(view)
        val w = px(view.context, width); val h = px(view.context, height)
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)
    }
}
