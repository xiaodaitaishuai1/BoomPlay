package com.boomplay.musicplayer

import android.app.Application
import com.boomplay.musicplayer.library.LibraryRepository
import com.boomplay.musicplayer.library.MusicRepository
import com.boomplay.musicplayer.playback.PlaybackController

class BoomPlayApplication : Application() {
    val music by lazy { MusicRepository() }
    val library by lazy { LibraryRepository(this) }
    val playback by lazy { PlaybackController(this) }
}
