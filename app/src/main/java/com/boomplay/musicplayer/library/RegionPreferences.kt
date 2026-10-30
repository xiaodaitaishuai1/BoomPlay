package com.boomplay.musicplayer.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** User choice is independent of the cache country returned by the service. */
class RegionPreferences(context: Context, name: String = "region_preferences") {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val saveMutex = Mutex()
    @Volatile private var selected = preferences.getString("preferred_region", "CN").takeIf { it in CODES } ?: "CN"
    val countryCode: String get() = selected

    @Suppress("UseKtx") // Keep commit's Boolean result: failed persistence must not change selected.
    suspend fun select(code: String) = saveMutex.withLock {
        withContext(Dispatchers.IO) {
            require(code in CODES)
            check(preferences.edit().putString("preferred_region", code).commit()) { "Could not save preferred region" }
            selected = code
        }
    }

    companion object { val CODES = listOf("CN", "HK", "TW") }
}
