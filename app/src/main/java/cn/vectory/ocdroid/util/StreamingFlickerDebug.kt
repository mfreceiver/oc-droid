package cn.vectory.ocdroid.util

import cn.vectory.ocdroid.BuildConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * §streaming-flicker-diagnosis (docs/streaming-flicker-diagnosis.md): gate for
 * the **diagnostic-only** log/counter instrumentation that observes the root
 * cause of the periodic full-screen flicker during streaming output (~0.99s
 * normal + 0.01s blank, ~1s period).
 *
 * Scope note (Q12 always-on fix): this flag USED TO also gate the streaming
 * filter behaviour in `ChatMessageContent` — the §3.1 `isStreamingMsg`
 * broadening that prevents the Top1 blank-frame. That behaviour is now
 * **unconditional** (always-on in both debug and release); see
 * `ChatMessageContent.kt`. This flag now controls **only** the diagnostic
 * log/counter emission below; it no longer influences what gets rendered.
 *
 * When `true`, every flicker-related log/trace below emits to Logcat under
 * [FLICKER_TAG] so `adb logcat -s FlickerDebug` reveals which root cause
 * (Top1 / Top2 / Top3) holds:
 *  - Top1: placeholder Part two-phase mutation intermediate state → the whole
 *    assistant message is `filterNot`-ed out for one Compose snapshot (blank
 *    frame). Logged in SessionSyncCoordinator (placeholder + leading-edge) and
 *    ChatMessageContent (FILTERED OUT + cumulative count). NB: the *fix* for
 *    Top1 is the always-on §3.1 broadening; this log line now fires only when
 *    something still slips past it (expect ~0 during normal streaming).
 *  - Top2: contentVersion LaunchedEffect restart ~every 100ms → scrollToItem(0)
 *    interrupts layout. Logged in ChatMessageContent's contentVersion effect.
 *  - §3.1 broadening (locked, always-on): a session-running non-user message
 *    with no parts or a text/reasoning Part is never dropped. Confirmed Top1
 *    as the root cause; promoted from a gated experiment to permanent
 *    behaviour in Q12, so it is no longer driven by this flag.
 *
 * Bound to [BuildConfig.DEBUG]: diagnostic logging / counter increments are
 * active in **debug** builds (for continued local observation) and fully
 * silenced in **release** builds — the guarded blocks are unreachable when
 * this is false, so there is zero logcat noise in shipped APKs. The streaming
 * filter fix itself is NOT affected by this flag and ships in release. (Not
 * `const` because [BuildConfig.DEBUG] is a per-variant generated value, not a
 * Kotlin compile-time literal.) Keep this file as the single source of truth
 * for the diagnostic gate; do NOT scatter local debug consts.
 */
internal val STREAMING_FLICKER_DEBUG: Boolean = BuildConfig.DEBUG

/** Logcat tag shared by every flicker-diagnosis line. Filter: `FlickerDebug`. */
internal const val FLICKER_TAG = "FlickerDebug"

/**
 * Cumulative count of messages dropped by the Top1 blank-frame filter path
 * (non-user, non-streaming, renderably-empty → `filterNot`). Incremented only
 * when [STREAMING_FLICKER_DEBUG] is on. A ~1Hz climb during streaming = Top1
 * confirmed. Thread-safe (AtomicLong) since it is touched from composition.
 */
internal val flickerFilterOutCount = AtomicLong(0L)
