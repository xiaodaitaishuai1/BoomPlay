package com.boomplay.musicplayer.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    private lateinit var database: LibraryDatabase
    private lateinit var library: LibraryRepository
    private val song = Track(1, "First", cover = "https://example.com/cover.jpg", hdSource = "https://example.com/first.mp3")

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
        library = LibraryRepository(database)
    }
    @After fun close() { database.close() }

    @Test fun favoriteToggleAndSnapshotUpdate() = runBlocking {
        library.toggleFavorite(song)
        assertEquals(listOf(song), library.favorites.first())
        library.saveTrack(song.copy(title = "Updated", hdSource = ""))
        assertEquals("Updated", library.favorites.first().single().title)
        assertEquals(song.hdSource, library.favorites.first().single().hdSource)
        library.toggleFavorite(song)
        assertTrue(library.favorites.first().isEmpty())
    }

    @Test fun playlistDeduplicatesAndKeepsInsertionOrder() = runBlocking {
        val id = library.createPlaylist("  Mix  ")
        library.addToPlaylist(id, song)
        library.addToPlaylist(id, song.copy(id = 2, title = "Second"))
        library.addToPlaylist(id, song)
        assertEquals(listOf(1L, 2L), library.playlistTracks(id).first().map { it.id })
        assertEquals(2, library.playlists.first().single().count)
        library.renamePlaylist(id, "Renamed")
        assertEquals("Renamed", library.playlists.first().single().name)
        library.removeFromPlaylist(id, 1)
        assertEquals(listOf(2L), library.playlistTracks(id).first().map { it.id })
        library.deletePlaylist(id)
        assertTrue(library.playlists.first().isEmpty())
        assertTrue(library.playlistTracks(id).first().isEmpty())
    }

    @Test fun savingQueueDeduplicatesWithoutChangingQueue() = runBlocking {
        val queue = listOf(song, song.copy(id = 2), song)
        val id = library.saveQueueAsPlaylist("  Queue  ", queue)
        assertEquals(listOf(1L, 2L), library.playlistTracks(id).first().map { it.id })
        assertEquals("Queue", library.playlists.first().single().name)
        assertEquals(3, queue.size)
    }

    @Test fun invalidQueueNameDoesNotCreatePlaylist() = runBlocking {
        assertTrue(runCatching { library.saveQueueAsPlaylist(" ", listOf(song)) }.isFailure)
        assertTrue(library.playlists.first().isEmpty())
    }

    @Test fun failedBatchRollsBackPlaylistAndTrackWrites() = runBlocking {
        val broken = object : AbstractList<Track>() {
            override val size = 2
            override fun get(index: Int): Track {
                if (index == 0) return song
                error("Interrupted queue snapshot")
            }
        }
        assertTrue(runCatching { library.saveQueueAsPlaylist("Failed", broken) }.isFailure)
        assertTrue(library.playlists.first().isEmpty())
        assertNull(database.dao().track(song.id))
    }

    @Test fun historyAndSearchesDeduplicate() = runBlocking {
        library.recordPlayed(song)
        library.recordPlayed(song.copy(id = 2))
        library.recordPlayed(song)
        assertEquals(listOf(1L, 2L), library.recent.first().map { it.id })
        library.rememberSearch(" music ")
        library.rememberSearch("music")
        library.rememberSearch(" ")
        assertEquals(listOf("music"), library.searches.first())
        library.removeSearch("music")
        assertTrue(library.searches.first().isEmpty())
        library.rememberSearch("another")
        library.clearSearches()
        assertTrue(library.searches.first().isEmpty())
    }

    @Test fun diskLibrarySurvivesDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "library-reopen-${System.nanoTime()}.db"
        var disk = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
        try {
            val first = LibraryRepository(disk)
            first.toggleFavorite(song)
            first.recordPlayed(song)
            val playlist = first.createPlaylist("Saved")
            first.addToPlaylist(playlist, song)
            first.rememberSearch("persisted")
            disk.close()
            disk = Room.databaseBuilder(context, LibraryDatabase::class.java, name).build()
            val restored = LibraryRepository(disk)
            assertEquals(listOf(song), restored.favorites.first())
            assertEquals(listOf(song), restored.recent.first())
            assertEquals(listOf(song), restored.playlistTracks(playlist).first())
            assertEquals(listOf("persisted"), restored.searches.first())
        } finally {
            disk.close()
            context.deleteDatabase(name)
        }
    }
}
