package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.util.DebugLog
import java.util.concurrent.atomic.AtomicLong

/**
 * §fix-error-storm P1-1 + P1-2: central gate applied to [UiEvent.Error] at the
 * [SharedEffectBus] entry point. Suppresses (a) error events that are really
 * coroutine-cancellation leakage (global defence-in-depth after the MessageActions
 * P0-1 retry-path guard), and (b) near-duplicate error events within a short time
 * window (the "refresh storm → snackbar storm" root cause: many concurrent
 * failures each emit independently → snackbar pops in sequence).
 *
 * Applied to [UiEvent.Error] ONLY — [UiEvent.Info]/[UiEvent.Success]/[UiEvent.Debug]
 * pass through untouched.
 *
 * # P1-1 cancellation leakage (CE guard)
 *
 * kotlinx.coroutines cancellation surfaces as `CancellationException` whose message
 * is "Job was cancelled" / "Scope was cancelled" / "StandaloneCoroutine was cancelled"
 * / OkHttp "canceled". Call sites wrap these via `errorMessageOrFallback(error, …)`
 * into `UiEvent.Error(resId, listOf(msg))`. P0-1 already rethrows CE at the main
 * MessageActions retry leak; this gate is the WHOLE-REPOSITORY fallback for any other
 * `.onFailure` path that funnels a CE through to a user-facing error toast.
 *
 * Match the stem "cancel" (covers canceled/cancelled/cancellation) case-insensitively
 * across all format args. Trade-off (accepted): a genuine non-cancellation error whose
 * message contains "cancel" would also be suppressed — rare in practice, and preferable
 * to the false "failed: canceled" toasts the refresh storm produced.
 *
 * # P1-2 duplicate-error dedup
 *
 * Within [dedupWindowMs], an [UiEvent.Error] with the same (resId, args) fingerprint as
 * the most recently emitted one is suppressed. "Same fingerprint" = same resId and same
 * args content (args joined with a NUL separator, then the resId xored in). This collapses
 * a burst of identical concurrent failures (refresh storm) into a single snackbar without
 * delaying distinct errors (unlike a collector-side debounce, which would delay ALL errors).
 */
internal class ErrorEventGate(
    private val dedupWindowMs: Long = DEFAULT_DEDUP_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var lastKey: Int = 0
    private var lastKeyAt: Long = Long.MIN_VALUE / 2L

    private val suppressedCancellation = AtomicLong(0)
    private val dedupedErrors = AtomicLong(0)

    /** Returns true if [event] should be emitted; false to suppress. */
    fun accept(event: UiEvent): Boolean {
        if (event !is UiEvent.Error) return true
        if (looksLikeCancellation(event)) {
            suppressedCancellation.incrementAndGet()
            DebugLog.w(TAG, "suppress cancellation-leak error event resId=${event.resId} args=${event.args}")
            return false
        }
        return !isDuplicateWithinWindow(event)
    }

    private fun looksLikeCancellation(event: UiEvent.Error): Boolean =
        event.args.any { it.toString().lowercase().contains(CANCEL_STEM) }

    private fun isDuplicateWithinWindow(event: UiEvent.Error): Boolean {
        val key = event.resId.hashCode() xor
            event.args.joinToString(separator = KEY_SEP) { it.toString() }.hashCode()
        val now = clock()
        synchronized(lock) {
            if (key == lastKey && (now - lastKeyAt) <= dedupWindowMs) {
                dedupedErrors.incrementAndGet()
                DebugLog.w(TAG, "suppress duplicate error event resId=${event.resId} within ${dedupWindowMs}ms window")
                return true
            }
            lastKey = key
            lastKeyAt = now
            return false
        }
    }

    fun suppressedCancellationCount(): Long = suppressedCancellation.get()
    fun dedupedErrorCount(): Long = dedupedErrors.get()

    companion object {
        private const val TAG = "EffectBus"
        private const val CANCEL_STEM = "cancel"
        private const val KEY_SEP = "\u0000"
        internal const val DEFAULT_DEDUP_WINDOW_MS = 2000L
    }
}
