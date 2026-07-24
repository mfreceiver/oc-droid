package cn.vectory.ocdroid.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory, non-persistent debug log ring buffer for live diagnostics.
 *
 * Designed for the in-app "调试日志" viewer in Settings (view / clear / copy),
 * so the user can self-diagnose real-time issues (e.g. SSE message sync) from
 * inside the app WITHOUT adb. Intentionally **kept in release builds** — this
 * is a personal-device client.
 *
 * Properties:
 * - In-memory only: cleared on process death. Nothing is written to disk.
 * - Newest-first ([Entry] at index 0 is the most recent) so the viewer shows
 *   the latest line at the top without needing to auto-scroll.
 * - Capped at [MAX_ENTRIES] entries (oldest dropped).
 * - Thread-safe via `synchronized(deque)`.
 * - Each [log] also forwards to `android.util.Log` (Logcat) for adb parity.
 * - NOT gated on BuildConfig.DEBUG.
 *
 * Usage: `DebugLog.i("SSE", "connected")`, `DebugLog.log("Sync", "...", Level.WARN)`.
 */
object DebugLog {

    /**
     * §streaming-state-sync-diag (release-enabling): runtime toggle for the
     * 5 verbose diagnostic tags (`SendDiag` / `SseDiag` / `StatusDiag` /
     * `DigestDiag` / `LayerDiag`). Default OFF so release users get zero log
     * noise / perf cost (the per-frame `SseDiag` block under the SSE delta
     * flood would otherwise bloat the ring buffer + Logcat).
     *
     * The flag is read at every verbose-diag call site via
     * `if (DebugLog.verboseDiagEnabled) { ... }` (replacing the prior
     * compile-time `if (BuildConfig.DEBUG)` gate so release builds can opt in).
     * `@Volatile` for cross-thread visibility (SSE collector, send path, UI
     * toggle all touch this); the read is ~free, and when false the `if` body
     * (including string-template arg evaluation) is skipped — zero alloc cost.
     *
     * Seeded at AppCore init from [SettingsManager.debugLogVerboseEnabled]
     * (ESP-persisted). The Settings toggle writes BOTH the ESP value AND this
     * field so the change takes effect immediately without a restart.
     */
    @JvmField
    @Volatile
    var verboseDiagEnabled: Boolean = false

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val seq: Long,
        val timeMs: Long,
        val tag: String,
        val level: Level,
        val message: String
    )

    // §verbose-diag-flood: bumped 1000 → 3000 once the verbose *Diag tags were
    // scoped + coalesced (commit following cc94564). 3x history at the same
    // per-byte cost; the previous 1000-entry cap evicted in ~2s under the
    // unscoped verbose flood. With scoping + 1Hz delta coalesce, 3000 covers
    // ~5–10 minutes of signal-rich activity.
    private const val MAX_ENTRIES = 3000

    /** Monotonic per-entry sequence — a stable, collision-free LazyColumn key
     *  (unlike hashCode, which collides when identical log lines land in the
     *  same millisecond during high-frequency streams). */
    private val seqCounter = java.util.concurrent.atomic.AtomicLong(0L)

    /** Ring buffer backing store — avoids per-`log` List allocation. */
    private val deque = ArrayDeque<Entry>(MAX_ENTRIES)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest-first observable log. Subscribe in Compose via collectAsStateWithLifecycle. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Append a log entry (becomes index 0 = newest). Forwards to Logcat. */
    fun log(tag: String, message: String, level: Level = Level.DEBUG) {
        val entry = Entry(
            seq = seqCounter.incrementAndGet(),
            timeMs = System.currentTimeMillis(),
            tag = tag,
            level = level,
            message = message
        )
        synchronized(deque) {
            if (deque.size >= MAX_ENTRIES) deque.removeLast()
            deque.addFirst(entry)
            // Emit an immutable snapshot — never publish the mutable deque.
            _entries.value = deque.toList()
        }
        // Logcat parity (best-effort; never let logging itself throw).
        runCatching {
            when (level) {
                Level.DEBUG -> Log.d(tag, message)
                Level.INFO -> Log.i(tag, message)
                Level.WARN -> Log.w(tag, message)
                Level.ERROR -> Log.e(tag, message)
            }
        }
    }

    fun d(tag: String, message: String) = log(tag, message, Level.DEBUG)
    fun i(tag: String, message: String) = log(tag, message, Level.INFO)
    fun w(tag: String, message: String) = log(tag, message, Level.WARN)
    fun e(tag: String, message: String) = log(tag, message, Level.ERROR)

    /**
     * R-20 Phase 0: 3-arg ERROR convenience — Logcat parity preserves the
     * throwable stack (more useful for triaging a destructive cache reset
     * than a string-only message). The in-memory ring buffer does NOT carry
     * the throwable (the live viewer is a flat list of strings) — the user
     * sees the message, devs see the stack via Logcat.
     */
    fun e(tag: String, message: String, throwable: Throwable?) {
        log(tag, if (throwable != null) "$message: ${throwable.javaClass.simpleName}: ${throwable.message}" else message, Level.ERROR)
        runCatching { if (throwable != null) Log.e(tag, message, throwable) }
    }

    /** Clear all entries (in-memory only). */
    fun clear() {
        synchronized(deque) {
            deque.clear()
            _entries.value = emptyList()
        }
    }
}
