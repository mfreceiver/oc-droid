package cn.vectory.ocdroid.ui.files

/**
 * §B1-P0 (fix copy→predictive-back crash): pure-logic, stateless gate that
 * decides when the Files preview should enter its one-frame "dismissing"
 * phase and when that phase should reset. Extracted from [FilesScreen] so
 * the state machine is unit-testable on the JVM (no Compose rule / no
 * Robolectric needed — see `PreviewDismissGateTest`).
 *
 * The actual `dismissing: Boolean` state and the `selectedFilePath` value
 * still live in [FilesScreen] (because they're Compose snapshot state tied
 * to composition). This object only owns the **transition predicates** —
 * the host queries it and applies the resulting yes/no decision.
 *
 * ## Why "any selectedFilePath change resets" (instead of "== null resets")
 *
 * StateFlow is conflating — if `selectedFilePath` transitions `X → null → Y`
 * while a collector is briefly suspended, the collector may only see the
 * final value `Y`. Compose's `LaunchedEffect(selectedFilePath)` would then
 * fire once with `Y`, never observing the `null`. A reset condition of
 * `selectedFilePath == null` would never see a hit in that case, so
 * `dismissing` would stick at `true`: the preview would stay non-selectable,
 * and every subsequent close attempt would early-return through the closing
 * guard — locking the user out of all 4 close entry points.
 *
 * Resetting on **any** selectedFilePath transition closes that hole:
 *  - `X → null` (normal close): resets; the closePreview coroutine has
 *    either completed or gets cancelled by `LaunchedEffect(dismissing)`
 *    re-keying when `dismissing` flips back to false (Compose cancels the
 *    previous run before launching the new one). Safe.
 *  - `X → Y`    (reopen mid-dismiss, including the conflate-collapsed case
 *    above): resets; cancels the pending `withFrameNanos {}` + `closePreview`
 *    coroutine; the new preview opens selectable.
 *  - `null → Y` (first open): `dismissing` is already false, so this is a
 *    no-op.
 */
internal object PreviewDismissGate {

    /**
     * True iff the host should enter the dismissing phase NOW. Drives the
     * `requestClosePreview()` body.
     *
     * Closing guard (reentry-safe):
     *  - returns `false` when [dismissing] is already `true` (a deferred
     *    close is in flight — discard the duplicate trigger),
     *  - returns `false` when [currentSelectedFilePath] is `null` (no
     *    preview is open — nothing to gate; also prevents accidentally
     *    arming `dismissing = true` and causing a one-frame blackout on the
     *    file list).
     */
    fun shouldStartDismissing(
        currentSelectedFilePath: String?,
        dismissing: Boolean,
    ): Boolean = !dismissing && currentSelectedFilePath != null

    /**
     * True iff the host should reset `dismissing` to `false`. The host
     * queries this from `LaunchedEffect(selectedFilePath)`, which Compose
     * only re-runs when `selectedFilePath` changes — so by the time this is
     * called, the path has already transitioned. See class kdoc for why
     * "any change resets" is required (StateFlow conflate hazard).
     *
     * Returns `dismissing` itself: nothing to reset if not currently
     * dismissing, otherwise always reset. The implicit "path changed"
     * precondition is the meaningful part; the trivial-looking body just
     * gates on whether there's anything to reset.
     */
    fun shouldReset(dismissing: Boolean): Boolean = dismissing

    /**
     * True iff the text preview should wrap its content in SelectionContainer
     * (and thus expose selection handles / `onGloballyPositioned`). The host
     * passes `textSelectable = PreviewDismissGate.textSelectable(dismissing)`
     * into `FilePreviewPane`. When `dismissing == true` we return `false` so
     * `PreviewPlainText` renders plain `Text` — SelectionContainer leaves
     * composition one frame before the pane is removed, breaking the
     * LayoutCoordinate-after-detach crash chain.
     */
    fun textSelectable(dismissing: Boolean): Boolean = !dismissing
}
