package com.boomplay.musicplayer.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class TrackEntity(@PrimaryKey val id: Long, val snapshot: String)

@Entity(tableName = "favorites", foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)])
data class FavoriteEntity(@PrimaryKey val trackId: Long, val addedAt: Long)

@Entity(tableName = "recent", foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)])
data class RecentEntity(@PrimaryKey val trackId: Long, val playedAt: Long)

@Entity(tableName = "playlists")
data class PlaylistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackId"], indices = [Index("trackId")], foreignKeys = [
    ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
])
data class PlaylistTrackEntity(val playlistId: Long, val trackId: Long, val position: Long)

@Entity(tableName = "search_history")
data class SearchEntity(@PrimaryKey val query: String, val searchedAt: Long)

data class PlaylistSummary(val id: Long, val name: String, val count: Int, val snapshot: String?)

@Dao
interface LibraryDao {
    @Upsert suspend fun saveTrack(track: TrackEntity)
    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun track(id: Long): TrackEntity?
    @Query("SELECT t.* FROM tracks t JOIN favorites f ON f.trackId = t.id ORDER BY f.addedAt DESC, f.trackId DESC") fun favorites(): Flow<List<TrackEntity>>
    @Query("SELECT COUNT(*) FROM favorites WHERE trackId = :id") suspend fun favoriteCount(id: Long): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun favorite(value: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE trackId = :id") suspend fun removeFavorite(id: Long)
    @Query("SELECT COALESCE(MAX(addedAt), 0) + 1 FROM favorites") suspend fun nextFavoriteTime(): Long
    @Query("SELECT t.* FROM tracks t JOIN recent r ON r.trackId = t.id ORDER BY r.playedAt DESC, r.trackId DESC") fun recent(): Flow<List<TrackEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun record(value: RecentEntity)
    @Query("SELECT COALESCE(MAX(playedAt), 0) + 1 FROM recent") suspend fun nextPlayTime(): Long
    @Query("SELECT p.id, p.name, (SELECT COUNT(*) FROM playlist_tracks pt WHERE pt.playlistId = p.id) AS count, (SELECT t.snapshot FROM playlist_tracks pt JOIN tracks t ON t.id = pt.trackId WHERE pt.playlistId = p.id ORDER BY pt.position ASC LIMIT 1) AS snapshot FROM playlists p ORDER BY p.id DESC") fun playlists(): Flow<List<PlaylistSummary>>
    @Insert suspend fun create(value: PlaylistEntity): Long
    @Query("UPDATE playlists SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: Long)
    @Query("SELECT t.* FROM tracks t JOIN playlist_tracks pt ON pt.trackId = t.id WHERE pt.playlistId = :id ORDER BY pt.position ASC") fun playlistTracks(id: Long): Flow<List<TrackEntity>>
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlistId = :id") suspend fun nextPosition(id: Long): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun add(value: PlaylistTrackEntity)
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id AND trackId = :trackId") suspend fun remove(id: Long, trackId: Long)
    @Query("SELECT query FROM search_history ORDER BY searchedAt DESC, query ASC") fun searches(): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun search(value: SearchEntity)
    @Query("SELECT COALESCE(MAX(searchedAt), 0) + 1 FROM search_history") suspend fun nextSearchTime(): Long
    @Query("DELETE FROM search_history WHERE query = :query") suspend fun removeSearch(query: String)
    @Query("DELETE FROM search_history") suspend fun clearSearches()
}

@Database(entities = [TrackEntity::class, FavoriteEntity::class, RecentEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, SearchEntity::class], version = 1, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
}
