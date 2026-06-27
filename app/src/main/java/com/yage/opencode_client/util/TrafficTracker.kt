package com.yage.opencode_client.util

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accumulates request/response byte counters across the app's lifetime,
 * surfaced on the Settings page as "流量统计" (Traffic).
 *
 * Counters are mutated from OkHttp interceptor(s) running on IO/dispatcher
 * threads, so they are marked [@Volatile] for cross-thread visibility. The
 * non-atomic read-modify-write ([+=]) is intentional: traffic accounting is
 * best-effort and does not require billing-grade atomicity, and concurrent
 * requests are rare enough that the occasional lost increment is acceptable.
 *
 * Totals are persisted to [SettingsManager] on every mutation so they survive
 * process restarts. Responses without a declared Content-Length (chunked / SSE
 * streams) report -1 and are intentionally NOT counted by the interceptor, so
 * actual wire usage is generally higher than what is displayed — streaming
 * responses are the main under-counted source.
 */
@Singleton
class TrafficTracker @Inject constructor(
    private val settingsManager: SettingsManager
) {
    @Volatile
    var totalBytesSent: Long = settingsManager.trafficBytesSent
        private set

    @Volatile
    var totalBytesReceived: Long = settingsManager.trafficBytesReceived
        private set

    /**
     * Adds [sent] / [received] bytes to the running totals. Non-positive values
     * are skipped (OkHttp reports -1 for unknown content length, e.g. chunked
     * SSE bodies — those cannot be counted reliably).
     */
    fun add(sent: Long, received: Long) {
        if (sent > 0L) totalBytesSent += sent
        if (received > 0L) totalBytesReceived += received
        if (sent > 0L || received > 0L) persist()
    }

    /** Zeros both counters and persists the reset. */
    fun reset() {
        totalBytesSent = 0L
        totalBytesReceived = 0L
        persist()
    }

    private fun persist() {
        settingsManager.trafficBytesSent = totalBytesSent
        settingsManager.trafficBytesReceived = totalBytesReceived
    }
}
