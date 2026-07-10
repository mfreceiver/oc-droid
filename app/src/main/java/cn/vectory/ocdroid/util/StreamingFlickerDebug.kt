package cn.vectory.ocdroid.util

import cn.vectory.ocdroid.BuildConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * §streaming-flicker-diagnosis (docs/streaming-flicker-diagnosis.md): master gate
 * for the **diagnostic-only** instrumentation that pinpoints the root cause of
 * the periodic full-screen flicker during streaming output (~0.99s normal +
 * 0.01s blank, ~1s period).
 *
 * When `true`, every flicker-related log/trace below emits to Logcat under
 * [FLICKER_TAG] so `adb logcat -s FlickerDebug` reveals which root cause
 * (Top1 / Top2 / Top3) holds:
 *  - Top1: placeholder Part two-phase mutation intermediate state → the whole
 *    assistant message is `filterNot`-ed out for one Compose snapshot (blank
 *    frame). Logged in SessionSyncCoordinator (placeholder + leading-edge) and
 *    ChatMessageContent (FILTERED OUT + cumulative count).
 *  - Top2: contentVersion LaunchedEffect restart ~every 100ms → scrollToItem(0)
 *    interrupts layout. Logged in ChatMessageContent's contentVersion effect.
 *  - §3.1 confirm experiment: broadens `isStreamingMsg` so that a session-running
 *    message carrying a text Part is never dropped. If flicker vanishes with
 *    this on, Top1 is confirmed.
 *
 * Bound to [BuildConfig.DEBUG]: instrumentation is active in **debug** builds
 * (for continued local diagnosis) and fully silenced in **release** builds —
 * every guarded block below is unreachable when this is false, so there is zero
 * behaviour change and zero logcat noise in shipped APKs. (Not `const` because
 * [BuildConfig.DEBUG] is a per-variant generated value, not a Kotlin
 * compile-time literal.) Keep this file as the single source of truth; do NOT
 * scatter local debug consts.
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
