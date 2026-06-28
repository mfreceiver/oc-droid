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
 * Persistence is batched: mutating on every HTTP response (the old behaviour)
 * would write EncryptedSharedPreferences per request, each triggering crypto
 * operations. Now we set a dirty flag and only flush at most once every
 * [PERSIST_INTERVAL_MS] (2 s), which still survives most process deaths without
 * meaningful data loss.
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

    @Volatile
    private var dirty: Boolean = false

    @Volatile
    private var lastPersistTime: Long = 0L

    /**
     * Adds [sent] / [received] bytes to the running totals. Non-positive values
     * are skipped (OkHttp reports -1 for unknown content length, e.g. chunked
     * bodies — those cannot be counted reliably).
     */
    fun add(sent: Long, received: Long) {
        var changed = false
        if (sent > 0L) { totalBytesSent += sent; changed = true }
        if (received > 0L) { totalBytesReceived += received; changed = true }
        if (changed) {
            dirty = true
            tryPersist()
        }
    }

    /** Zeros both counters and persists the reset immediately. */
    fun reset() {
        totalBytesSent = 0L
        totalBytesReceived = 0L
        doPersist()
    }

    private fun tryPersist() {
        if (!dirty) return
        val now = System.currentTimeMillis()
        if (now - lastPersistTime < PERSIST_INTERVAL_MS) return
        doPersist()
    }

    private fun doPersist() {
        settingsManager.trafficBytesSent = totalBytesSent
        settingsManager.trafficBytesReceived = totalBytesReceived
        lastPersistTime = System.currentTimeMillis()
        dirty = false
    }

    companion object {
        private const val PERSIST_INTERVAL_MS = 2000L
    }
}
