package com.boomplay.musicplayer.library

import com.google.gson.JsonArray
import com.google.gson.JsonObject

class BoomplayException(message: String) : java.io.IOException(message)

/** Converts inconsistent legacy API JSON into the application's stable models. */
class BoomplayParser {
    var staticAddress: String = DEFAULT_STATIC_ADDRESS
    fun url(value: String): String {
        val path = value.trim()
        if (path.isEmpty()) return ""
        return if (path.startsWith("https://") || path.startsWith("http://")) path
        else staticAddress.trimEnd('/') + "/" + path.trimStart('/')
    }

    fun body(json: JsonObject): JsonObject {
        val code = json.text("code")
        if (code.isNotEmpty() && code != "0" && code != "200") {
            throw BoomplayException(json.text("desc").ifBlank { "Service error ($code)" })
        }
        val body = json.obj("data") ?: json
        val address = body.text("staticAddr").ifBlank { json.text("staticAddr") }
        if (address.startsWith("https://") || address.startsWith("http://")) staticAddress = address
        return body
    }

    fun home(json: JsonObject): List<HomeSection> = body(json).array("grps").mapNotNull { entry ->
        if (!entry.isJsonObject) return@mapNotNull null
        val group = entry.asJsonObject
        if (group.text("value") in setOf("Videos", "Radio")) return@mapNotNull null
        val groupId = group.number("colGrpID")
        val items = group.array("cols").mapNotNull { item ->
            if (item.isJsonObject) collection(item.asJsonObject, groupId) else null
        }.filter { it.id > 0 && it.type in setOf(1, 2, 5) }.distinctBy { it.type to it.id }
        if (items.isEmpty()) null else HomeSection(groupId, group.text("name"), group.number("layout").toInt(), items)
    }

    fun collection(json: JsonObject, groupId: Long = 0): Collection = Collection(
        id = json.number("colID"),
        type = json.number("colType").toInt().takeIf { it > 0 } ?: 1,
        title = json.text("name"),
        cover = cover(json, "adImgID", "bigIconID", "smIconID", "lowIconID", "iconMagicUrl"),
        description = json.text("descr"),
        groupId = groupId,
    )

    fun track(json: JsonObject): Track {
        val artist = json.obj("beArtist") ?: JsonObject()
        val album = json.obj("beAlbum") ?: JsonObject()
        return Track(
            id = json.number("musicID"),
            title = json.text("name"),
            artist = Artist(artist.number("colID"), artist.text("name"), cover(artist)),
            album = Album(album.number("colID"), album.text("name"), cover(album)),
            cover = cover(json, "cover", "bigIconID", "smIconID", "lowIconID", "iconMagicUrl").ifBlank { cover(album) },
            durationMs = duration(json.text("deaution")),
            hdSource = url(json.text("hdSourceID")),
            mdSource = url(json.text("mdSourceID")),
            ldSource = url(json.text("ldSourceID")),
        )
    }

    fun tracksPage(json: JsonObject, page: Int): Page<Track> {
        val content = body(json)
        val raw = content.array("musics")
        val tracks = raw.filter { it.isJsonObject }.map { track(it.asJsonObject) }.filter { it.id > 0 }.distinctBy { it.id }
        val total = content.number("count").takeIf { content.has("count") }
        return Page(tracks, raw.size() >= PAGE_SIZE && (total == null || (page + 1L) * PAGE_SIZE < total))
    }

    private fun cover(json: JsonObject, vararg fields: String): String {
        val candidates = fields.takeIf { it.isNotEmpty() }?.toList() ?: listOf("bigIconID", "smIconID", "lowIconID", "iconMagicUrl")
        return url(candidates.firstNotNullOfOrNull { json.text(it).takeIf(String::isNotBlank) }.orEmpty())
    }

    companion object {
        const val PAGE_SIZE = 20
        const val DEFAULT_STATIC_ADDRESS = "https://source.boomplaymusic.com/"
        fun duration(value: String): Long {
            val parts = value.split(':').map { it.toLongOrNull() ?: return 0 }
            if (parts.size !in 2..3 || parts.any { it < 0 }) return 0
            return parts.fold(0L) { result, part -> result * 60 + part } * 1000
        }
    }
}

internal fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull || !it.isJsonPrimitive }?.asString.orEmpty()
internal fun JsonObject.number(key: String): Long = text(key).toLongOrNull() ?: 0L
internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
