// SseBreathSpec.kt — shared SSE breathing-pulse animation constants.
//
// Extracted from ServerStatusIconButton.kt (item ①) so the same calm pulse
// can be reused by SessionAttentionBadge for the PendingUserInput tier
// (§ui-badges). The animation pattern (rememberInfiniteTransition +
// graphicsLayer alpha/scale) lives at the call site; only the constants are
// shared here.
//
// §ui-style-spec §2: these are animation timings + float ranges, not dp
// dimensions — they live outside Dimens.kt (which is for dp/sp geometry tokens).

package cn.vectory.ocdroid.ui.theme

/**
 * §breathing-indicator (item ①): breathing-pulse constants for SSE-connected
 * status dot AND the PendingUserInput badge tier. Intentionally CALM (slow
 * tween + reverse repeat) so the pulse reads as "attention needed" without
 * being distracting. A designer will separately review the feel; these are a
 * spec-compliant basic pulse.
 */
internal object SseBreathSpec {
    /** One half-cycle duration (initialValue → targetValue). Reverse repeat
     *  doubles the visible period, so a full breathe-in+breathe-out is 2×this. */
    const val DURATION_MS: Int = 1_600
    /** Min alpha during the pulse (dot dims to ~65%). */
    const val ALPHA_MIN: Float = 0.65f
    /** Max alpha (full opacity). */
    const val ALPHA_MAX: Float = 1f
    /** Min scale (dot shrinks to 90% — a subtle "inhale"). */
    const val SCALE_MIN: Float = 0.9f
    /** Max scale (resting size). */
    const val SCALE_MAX: Float = 1f
}
