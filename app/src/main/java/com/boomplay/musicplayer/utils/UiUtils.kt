package com.boomplay.musicplayer.utils

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.boomplay.musicplayer.R
import com.bumptech.glide.Glide
import java.util.Locale

object UiUtils {
    fun time(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
    fun artwork(view: androidx.constraintlayout.utils.widget.ImageFilterView, url: String) {
        if (view.tag == url) return
        view.tag = url
        Glide.with(view).load(url.ifBlank { null }).placeholder(R.drawable.ic_app).error(R.drawable.ic_app).centerCrop().into(view)
    }
    fun insets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
