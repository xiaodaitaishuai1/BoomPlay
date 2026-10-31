package com.boomplay.musicplayer.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioQuality { AUTO, HIGH, MEDIUM, LOW }

sealed interface SleepTimer {
    data object Off : SleepTimer
    data class Countdown(val deadlineElapsedMs: Long) : SleepTimer
    data object EndOfTrack : SleepTimer
}

/** Preferences survive restart; sleep timers deliberately belong only to the running service. */
class ListeningSettings(context: Context) {
    private val preferences = context.getSharedPreferences("listening_settings", Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutableQuality = MutableStateFlow(runCatching {
        AudioQuality.valueOf(preferences.getString("quality", AudioQuality.AUTO.name).orEmpty())
    }.getOrDefault(AudioQuality.AUTO))
    val quality: StateFlow<AudioQuality> = mutableQuality.asStateFlow()
    private val mutableTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleepTimer: StateFlow<SleepTimer> = mutableTimer.asStateFlow()

    fun setQuality(value: AudioQuality) {
        mutableQuality.value = value
        preferences.edit { putString("quality", value.name) }
    }

    internal fun setTimer(value: SleepTimer) { mutableTimer.value = value }

    internal fun resolvedQuality(): AudioQuality {
        if (quality.value != AudioQuality.AUTO) return quality.value
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val constrained = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) != true ||
            capabilities?.linkDownstreamBandwidthKbps?.let { it in 1..255 } == true
        val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        return ListeningUtils.autoQuality(connected, constrained, wifi)
    }
}

internal object ListeningUtils {
    fun autoQuality(connected: Boolean, constrained: Boolean, wifi: Boolean): AudioQuality = when {
        !connected || constrained -> AudioQuality.LOW
        wifi -> AudioQuality.HIGH
        else -> AudioQuality.MEDIUM
    }

    fun countdown(minutes: Int, nowElapsedMs: Long): SleepTimer.Countdown {
        require(minutes in 1..1440)
        return SleepTimer.Countdown(nowElapsedMs + minutes * 60_000L)
    }

    fun expired(timer: SleepTimer, nowElapsedMs: Long): Boolean =
        timer is SleepTimer.Countdown && nowElapsedMs >= timer.deadlineElapsedMs
}

/** Shared by the resolver thread and player thread; a recovery budget never grows during retries. */
internal class AudioSourceAttempts(private val urls: List<String>) {
    private var attempt = 0
    @Synchronized fun current(): String? = urls.getOrNull(attempt.coerceAtMost(urls.lastIndex))
    @Synchronized fun advance(): Boolean {
        if (urls.isEmpty() || attempt >= urls.size) return false
        attempt++
        return true
    }
}

internal object ListeningCommands {
    const val TIMER = "com.boomplay.musicplayer.TIMER"
    const val RETRY = "com.boomplay.musicplayer.RETRY"
    const val MINUTES = "minutes"
    const val END_OF_TRACK = -1
    const val OFF = 0
}
