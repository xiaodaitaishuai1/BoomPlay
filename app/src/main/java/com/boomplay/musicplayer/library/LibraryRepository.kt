package com.boomplay.musicplayer.library

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LibraryRepository internal constructor(private val database: LibraryDatabase) {
    constructor(context: Context) : this(Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "boomplay-library.db").build())

    private val dao = database.dao()
    private val gson = Gson()
    val favorites: Flow<List<Track>> = dao.favorites().map(::decodeTracks)
    val recent: Flow<List<Track>> = dao.recent().map(::decodeTracks)
    val playlists: Flow<List<UserPlaylist>> = dao.playlists().map { rows ->
        rows.map { UserPlaylist(it.id, it.name, it.count, it.snapshot?.let(::decode)?.cover.orEmpty()) }
    }
    val searches: Flow<List<String>> = dao.searches()
    fun playlistTracks(id: Long): Flow<List<Track>> = dao.playlistTracks(id).map(::decodeTracks)

    suspend fun toggleFavorite(track: Track) = withContext(Dispatchers.IO) {
        database.withTransaction {
            saveTrack(track)
            if (dao.favoriteCount(track.id) > 0) dao.removeFavorite(track.id)
            else dao.favorite(FavoriteEntity(track.id, maxOf(System.currentTimeMillis(), dao.nextFavoriteTime())))
        }
    }

    suspend fun recordPlayed(track: Track) = withContext(Dispatchers.IO) {
        database.withTransaction {
            saveTrack(track)
            dao.record(RecentEntity(track.id, maxOf(System.currentTimeMillis(), dao.nextPlayTime())))
        }
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) { dao.create(PlaylistEntity(name = validName(name))) }
    suspend fun renamePlaylist(id: Long, name: String) = withContext(Dispatchers.IO) { dao.rename(id, validName(name)) }
    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) { dao.deletePlaylist(id) }
    suspend fun addToPlaylist(id: Long, track: Track) = withContext(Dispatchers.IO) {
        database.withTransaction {
            saveTrack(track)
            dao.add(PlaylistTrackEntity(id, track.id, dao.nextPosition(id)))
        }
    }
    suspend fun saveQueueAsPlaylist(name: String, tracks: List<Track>): Long = withContext(Dispatchers.IO) {
        val clean = validName(name)
        database.withTransaction {
            val id = dao.create(PlaylistEntity(name = clean))
            tracks.asSequence().filter { it.id > 0 }.distinctBy { it.id }.forEachIndexed { index, track ->
                saveTrack(track)
                dao.add(PlaylistTrackEntity(id, track.id, index.toLong()))
            }
            id
        }
    }
    suspend fun removeFromPlaylist(id: Long, trackId: Long) = withContext(Dispatchers.IO) { dao.remove(id, trackId) }
    suspend fun rememberSearch(query: String) = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isNotBlank()) database.withTransaction {
            dao.search(SearchEntity(term, maxOf(System.currentTimeMillis(), dao.nextSearchTime())))
        }
    }
    suspend fun removeSearch(query: String) = withContext(Dispatchers.IO) { dao.removeSearch(query) }
    suspend fun clearSearches() = withContext(Dispatchers.IO) { dao.clearSearches() }

    suspend fun saveTrack(track: Track) = withContext(Dispatchers.IO) {
        if (track.id <= 0) return@withContext
        database.withTransaction {
            val previous = dao.track(track.id)?.let { decode(it.snapshot) }
            val snapshot = if (previous == null) track else track.copy(
                title = track.title.ifBlank { previous.title },
                artist = track.artist.takeIf { it.name.isNotBlank() } ?: previous.artist,
                album = track.album.takeIf { it.name.isNotBlank() } ?: previous.album,
                cover = track.cover.ifBlank { previous.cover },
                durationMs = track.durationMs.takeIf { it > 0 } ?: previous.durationMs,
                hdSource = track.hdSource.ifBlank { previous.hdSource },
                mdSource = track.mdSource.ifBlank { previous.mdSource },
                ldSource = track.ldSource.ifBlank { previous.ldSource },
            )
            dao.saveTrack(TrackEntity(track.id, gson.toJson(snapshot)))
        }
    }

    private fun validName(name: String): String = name.trim().also { require(it.isNotEmpty()) { "Playlist name must not be empty" } }
    private fun decode(snapshot: String): Track? = runCatching { gson.fromJson(snapshot, Track::class.java) }.getOrNull()
    private fun decodeTracks(rows: List<TrackEntity>): List<Track> = rows.mapNotNull { decode(it.snapshot) }
}
