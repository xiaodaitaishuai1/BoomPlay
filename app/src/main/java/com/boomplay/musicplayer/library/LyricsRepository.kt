package com.boomplay.musicplayer.library

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit

data class LyricLine(val timeMs: Long?, val text: String)
data class Lyrics(val lines: List<LyricLine>) {
    val timed: Boolean = lines.isNotEmpty() && lines.all { it.timeMs != null }
    fun activeIndex(positionMs: Long): Int {
        if (!timed) return -1
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            if (lines[mid].timeMs!! <= positionMs) { result = mid; low = mid + 1 }
            else high = mid - 1
        }
        return result
    }
}

object LyricsParser {
    private val timestamp = Regex("\\[(\\d{1,6}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val metadata = Regex("^\\[[a-zA-Z]+:.*]$")
    private val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
    fun parse(text: String): Lyrics {
        val shift = offset.find(text)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-86_400_000, 86_400_000) ?: 0
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<LyricLine>()
        text.removePrefix("\uFEFF").lineSequence().take(10_000).forEach { raw ->
            val line = raw.trim()
            val stamps = timestamp.findAll(line).toList()
            if (stamps.isNotEmpty()) {
                val previousCount = timed.size
                val words = timestamp.replace(line, "").trim()
                stamps.forEach { stamp ->
                    val seconds = stamp.groupValues[2].toLong()
                    if (seconds < 60) {
                        val fraction = stamp.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0
                        val time = (stamp.groupValues[1].toLong() * 60_000 + seconds * 1000 + fraction + shift).coerceAtLeast(0)
                        timed += LyricLine(time, words)
                    }
                }
                if (timed.size == previousCount) plain += LyricLine(null, line)
            } else if (line.isNotBlank() && !metadata.matches(line)) plain += LyricLine(null, line)
        }
        return Lyrics(if (timed.isNotEmpty()) timed.sortedBy { it.timeMs } else plain)
    }
}

sealed interface LyricsResult {
    data class Available(val lyrics: Lyrics) : LyricsResult
    data object Unavailable : LyricsResult
    data object Failed : LyricsResult
}

interface LyricsRepository { suspend fun lyrics(track: Track): LyricsResult }

interface OfficialLyricsApi {
    @GET("BoomPlayer/item/getLyricID")
    suspend fun lookup(@Query("musicID") id: Long, @Query("name") name: String): JsonObject
    @Streaming @GET suspend fun content(@Url url: String): ResponseBody
}

/** Official 7.8.43: getLyricID returns lyricID; zic.c prefixes it with the source address. */
class OfficialLyricsRepository(
    private val api: OfficialLyricsApi = createApi(),
    private val staticAddress: () -> String = { "https://source.boomplaymusic.com/" },
) : LyricsRepository {
    private val cache = object : LinkedHashMap<Long, LyricsResult.Available>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, LyricsResult.Available>?): Boolean = size > 32
    }
    override suspend fun lyrics(track: Track): LyricsResult = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[track.id] }?.let { return@withContext it }
        try {
            val raw = api.lookup(track.id, track.title)
            val body = BoomplayParser().body(raw)
            val lyricId = body.get("lyricID")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (lyricId.isBlank() || lyricId == "0") return@withContext LyricsResult.Unavailable
            val base = staticAddress().toHttpUrlOrNull() ?: return@withContext LyricsResult.Unavailable
            // Treat the official ID as a resource path, not an arbitrary external URL.
            val target = base.resolve(lyricId) ?: return@withContext LyricsResult.Unavailable
            if (target.host != base.host || target.scheme != base.scheme || target.port != base.port) return@withContext LyricsResult.Unavailable
            val parsed = api.content(target.toString()).use { response ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val stream = response.byteStream()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > 512 * 1024) return@withContext LyricsResult.Unavailable
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                val text = bytes.toString(Charsets.UTF_8)
                if (text.trimStart().startsWith("<")) return@withContext LyricsResult.Unavailable
                LyricsParser.parse(text)
            }
            if (parsed.lines.isEmpty()) LyricsResult.Unavailable
            else LyricsResult.Available(parsed).also { synchronized(cache) { cache[track.id] = it } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LyricsResult.Failed }
    }

    companion object {
        fun createApi(baseUrl: String = "https://api.boomplaymusic.com/"): OfficialLyricsApi {
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false)
                .readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", MusicRepository.USER_AGENT).build()) }.build()
            return Retrofit.Builder().baseUrl(baseUrl).client(client)
                .addConverterFactory(GsonConverterFactory.create()).build().create(OfficialLyricsApi::class.java)
        }
    }
}
