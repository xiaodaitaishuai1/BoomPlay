package com.boomplay.musicplayer.library

data class Artist(val id: Long = 0, val name: String = "", val cover: String = "")
data class Album(val id: Long = 0, val name: String = "", val cover: String = "")
data class Track(
    val id: Long,
    val title: String,
    val artist: Artist = Artist(),
    val album: Album = Album(),
    val cover: String = "",
    val durationMs: Long = 0,
    val hdSource: String = "",
    val mdSource: String = "",
    val ldSource: String = "",
)
data class Collection(
    val id: Long,
    val type: Int = 1,
    val title: String,
    val cover: String = "",
    val description: String = "",
    val groupId: Long = 0,
)
data class HomeSection(val id: Long, val title: String, val layout: Int, val items: List<Collection>)
data class Page<T>(val items: List<T>, val hasMore: Boolean)
data class CollectionDetail(val collection: Collection, val tracks: Page<Track>)
data class UserPlaylist(val id: Long, val name: String, val count: Int = 0, val cover: String = "")
