package cn.vectory.ocdroid.data.cache.contract

/**
 * R-20 Phase 2 (plan §1 / §3): the four states of a gap-fill lifecycle.
 *
 * Moved here from `ui.chat` (Phase 4 architecture: break the
 * `ui → data.cache → ui.chat` dependency ring). This is a pure data-carrier
 * enum with no UI coupling — the UI layer imports it; it never imports the UI.
 *
 *  - [Idle] — no fill in flight; the user can tap to page the next 50-msg step.
 *  - [Filling] — a 50-step backward fill is currently running for this gap.
 *  - [Exhausted] — the server returned a null cursor before the anchor was
 *    reached; the gap cannot be bridged (history below the gap is gone). UI
 *    shows a non-tappable "无法补齐" marker.
 *  - [Error] — the last fill step errored; the user can retry.
 */
enum class GapFillState {
    Idle,
    Filling,
    Exhausted,
    Error;
}
