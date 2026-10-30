package com.boomplay.musicplayer

import android.app.Application
import com.boomplay.musicplayer.library.LibraryRepository
import com.boomplay.musicplayer.library.MusicRepository
import com.boomplay.musicplayer.library.RegionPreferences
import com.boomplay.musicplayer.playback.PlaybackController

class BoomPlayApplication : Application() {
    val regions by lazy { RegionPreferences(this) }
    val music by lazy { MusicRepository(preferredRegion = { regions.countryCode }) }
    val library by lazy { LibraryRepository(this) }
    val playback by lazy { PlaybackController(this) }
}
