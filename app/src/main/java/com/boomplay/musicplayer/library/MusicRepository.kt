package com.boomplay.musicplayer.library

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap
import java.util.concurrent.TimeUnit

interface BoomplayApi {
    @GET("BoomPlayer/item/startup1") suspend fun startup(): JsonObject
    @GET("BoomPlayer/item/getMusicHome") suspend fun home(@QueryMap parameters: Map<String, String>): JsonObject
    @GET("BoomPlayer/item/getCols") suspend fun collections(@QueryMap parameters: Map<String, String>): JsonObject
    @GET("BoomPlayer/item/getMusics") suspend fun detail(@QueryMap parameters: Map<String, String>): JsonObject
    @GET("BoomPlayer/content/artist-home/v1/getMoreSongs") suspend fun artistSongs(@QueryMap parameters: Map<String, String>): JsonObject
    @POST("BoomPlayer/item/searchMusics") suspend fun search(@QueryMap parameters: Map<String, String>): JsonObject
    @GET("BoomPlayer/item/getMusicInfo") suspend fun track(@Query("musicID") id: Long): JsonObject
}

class MusicRepository(
    private val api: BoomplayApi = createApi(),
    private val parser: BoomplayParser = BoomplayParser(),
) {
    val staticAddress: String get() = parser.staticAddress
    private var countryCode = "SG"
    private var configured = false
    private val configurationMutex = Mutex()

    private suspend fun configure() {
        configurationMutex.withLock {
            if (configured) return
            try { parser.body(api.startup()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The documented resource address is the offline default. */ }
            configured = true
        }
    }

    suspend fun home(refresh: Boolean = false): List<HomeSection> = withContext(Dispatchers.IO) {
        configure()
        val response = api.home(mapOf(
            "isDragDown" to refresh.toString(), "colPageSize" to "10",
            "dataVersion" to "0", "cacheCountryCode" to countryCode,
        ))
        val body = parser.body(response)
        countryCode = body.text("cacheCountryCode").ifBlank { countryCode }
        parser.home(response)
    }

    suspend fun collections(groupId: Long, page: Int): Page<Collection> = withContext(Dispatchers.IO) {
        require(page >= 0)
        configure()
        val body = parser.body(api.collections(pageParameters(page) + mapOf(
            "colGrpID" to groupId.toString(), "firstAlpha" to "", "userType" to "0",
        )))
        val raw = body.array("cols")
        val items = raw.filter { it.isJsonObject }.map { parser.collection(it.asJsonObject, groupId) }
            .filter { it.id > 0 && it.type in setOf(1, 2, 5) }.distinctBy { it.type to it.id }
        Page(items, raw.size() >= BoomplayParser.PAGE_SIZE)
    }

    suspend fun detail(collection: Collection, page: Int): CollectionDetail = withContext(Dispatchers.IO) {
        require(page >= 0)
        configure()
        // Artist getMusics contains only detailCol. Its songs have a separate endpoint.
        val response = if (page == 0 || collection.type != 2) api.detail(pageParameters(page) + mapOf(
            "colID" to collection.id.toString(), "itemType" to collection.type.toString(),
            "cacheColVersionCode" to "0", "cacheCountryCode" to countryCode,
            "trackPoint" to "", "colGrpID" to collection.groupId.toString(),
        )) else JsonObject()
        val body = parser.body(response)
        val info = body.obj("detailCol")?.let { parser.collection(it, collection.groupId) } ?: collection
        val tracks = if (collection.type == 2) parser.tracksPage(api.artistSongs(pageParameters(page) + mapOf(
            "colID" to collection.id.toString(), "type" to "0",
        )), page) else parser.tracksPage(response, page)
        CollectionDetail(info, tracks)
    }

    suspend fun search(query: String, page: Int): Page<Track> = withContext(Dispatchers.IO) {
        require(page >= 0)
        if (query.isBlank()) return@withContext Page(emptyList(), false)
        configure()
        parser.tracksPage(api.search(pageParameters(page) + mapOf("content" to query.trim(), "isZip" to "false")), page)
    }

    suspend fun track(id: Long): Track = withContext(Dispatchers.IO) {
        configure()
        val result = parser.track(parser.body(api.track(id)))
        if (result.id <= 0) throw BoomplayException("Track information is unavailable")
        result
    }

    private fun pageParameters(page: Int) = mapOf("pageIndex" to page.toString(), "pageSize" to BoomplayParser.PAGE_SIZE.toString())

    companion object {
        const val USER_AGENT = "BoomPlay/7.8.43"
        fun createApi(baseUrl: String = "https://api.boomplaymusic.com/"): BoomplayApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT).header("Accept", "application/json").build()) }
                .build()
            return Retrofit.Builder().baseUrl(baseUrl).client(client)
                .addConverterFactory(GsonConverterFactory.create()).build().create(BoomplayApi::class.java)
        }
    }
}
