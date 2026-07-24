package cn.vectory.ocdroid.util

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Per-request traffic debug log: endpoint URL, HTTP method, timestamp, sent bytes,
 * received bytes, and elapsed ms. Writes a ring buffer (~200 entries) to the
 * app's external files directory so it survives process death and can be pulled
 * via `adb pull` without opening the app.
 *
 * The aggregated [TrafficTracker] remains the simple total for the Settings page;
 * this logger is the detailed per-request breakdown for debugging where the 69MB
 * in 5 minutes actually comes from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TrafficLogger @Inject
constructor(@param:ApplicationContext private val context: Context) {
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var sequence = 0L
    private var unflushedEntries = 0

    /** Single reusable flush worker — replaces per-call thread spawns. */
    private val flushScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val flushChannel = Channel<Unit>(Channel.CONFLATED)

    init {
        flushScope.launch {
            for (_msg in flushChannel) {
                flushToDiskSync()
            }
        }
    }

    fun record(method: String, url: String, sent: Long, received: Long, elapsedMs: Long) {
        synchronized(buffer) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(
                Entry(
                    seq = ++sequence,
                    time = System.currentTimeMillis(),
                    method = method,
                    url = url,
                    sent = sent,
                    received = received,
                    elapsedMs = elapsedMs
                )
            )
            unflushedEntries++
            if (unflushedEntries >= AUTO_FLUSH_EVERY) {
                unflushedEntries = 0
                // Offer a flush to the shared worker (non-blocking, never under buffer lock)
                flushChannel.trySend(Unit)
            }
        }
    }

    /** Write the current buffer to a plain-text file under the app's external files dir. */
    fun flushToDisk() {
        flushChannel.trySend(Unit)
    }

    /** Cancel the background flush worker. Safe to call multiple times; idempotent. */
    fun close() {
        flushChannel.close()
        flushScope.cancel()
    }

    private fun flushToDiskSync() {
        val entries: List<Entry>
        synchronized(buffer) { entries = buffer.toList() }
        if (entries.isEmpty()) return

        val dir = File(context.getExternalFilesDir(null), "traffic_logs")
        dir.mkdirs()
        val file = File(dir, "traffic-${System.currentTimeMillis()}.txt")
        try {
            PrintWriter(FileWriter(file)).use { w ->
                w.println("# OpenCode Android traffic debug log")
                w.println("# Written ${Date()}")
                w.println("# seq | time        | method | sent | recv  | elapsed | url")
                w.println("# ----|-------------|--------|------|-------|---------|----")
                for (e in entries) {
                    w.printf(
                        "%5d | %s | %-6s | %5s | %5s | %4d ms | %s%n",
                        e.seq,
                        fmt.format(Date(e.time)),
                        e.method,
                        formatSize(e.sent),
                        formatSize(e.received),
                        e.elapsedMs,
                        e.url
                    )
                }
            }
            Log.i(TAG, "Traffic log written: ${file.absolutePath} (${file.length()} bytes)")
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to write traffic log", ex)
        }
    }

    /** Return a formatted string of the current buffer for logcat. */
    fun dump(): String {
        val sw = StringWriter()
        PrintWriter(sw).use { w ->
            val entries: List<Entry> = synchronized(buffer) { buffer.toList() }
            w.printf("TrafficLogger buffer (%d entries):%n", entries.size)
            for (e in entries) {
                w.printf(
                    "  [%5d] %s %-6s %s -> s:%s r:%s (%dms)%n",
                    e.seq, fmt.format(Date(e.time)), e.method,
                    e.url, formatSize(e.sent), formatSize(e.received), e.elapsedMs
                )
            }
            // Summary
            val totalSent = entries.sumOf { it.sent }
            val totalRecv = entries.sumOf { it.received }
            val totalTime = entries.sumOf { it.elapsedMs }
            w.printf("  TOTAL: sent=%s recv=%s time=%dms%n",
                formatSize(totalSent), formatSize(totalRecv), totalTime)
        }
        return sw.toString()
    }

    private data class Entry(
        val seq: Long,
        val time: Long,
        val method: String,
        val url: String,
        val sent: Long,
        val received: Long,
        val elapsedMs: Long
    )

    companion object {
        private const val TAG = "TrafficLogger"
        private const val MAX_ENTRIES = 200
        private const val AUTO_FLUSH_EVERY = 50

        private fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
        }
    }
}
