package cn.vectory.ocdroid.util

import android.content.SharedPreferences

/**
 * L4b domain split of [SettingsManager] — TRAFFIC domain.
 *
 * Owns the three cumulative HTTP traffic counters (bytes sent / bytes
 * received / last-reset epoch). Seeded into [TrafficTracker] on app start
 * and updated by [TrafficTracker] on every counted request.
 *
 * Behavior byte-identical to pre-split [SettingsManager]: same ESP instance,
 * same key strings, same Long defaults (0L). NO key renames.
 */
internal class TrafficPrefs(
    private val encryptedPrefs: SharedPreferences,
) {
    /**
     * Cumulative bytes sent over HTTP, persisted across restarts. Seeded into
     * [TrafficTracker] on app start; updated by [TrafficTracker] on every
     * counted request. See [trafficBytesReceived] for the inbound counterpart.
     */
    var trafficBytesSent: Long
        get() = encryptedPrefs.getLong(KEY_TRAFFIC_SENT, 0L)
        set(value) = encryptedPrefs.edit().putLong(KEY_TRAFFIC_SENT, value).apply()

    var trafficBytesReceived: Long
        get() = encryptedPrefs.getLong(KEY_TRAFFIC_RECEIVED, 0L)
        set(value) = encryptedPrefs.edit().putLong(KEY_TRAFFIC_RECEIVED, value).apply()

    /** Epoch millis of the last traffic reset (0 = never reset). Mirrors
     *  [trafficBytesSent]/[trafficBytesReceived]; written by [TrafficTracker]. */
    var trafficResetAt: Long
        get() = encryptedPrefs.getLong(KEY_TRAFFIC_RESET_AT, 0L)
        set(value) = encryptedPrefs.edit().putLong(KEY_TRAFFIC_RESET_AT, value).apply()

    companion object {
        internal const val KEY_TRAFFIC_SENT = "traffic_sent"
        internal const val KEY_TRAFFIC_RECEIVED = "traffic_received"
        internal const val KEY_TRAFFIC_RESET_AT = "traffic_reset_at"
    }
}
