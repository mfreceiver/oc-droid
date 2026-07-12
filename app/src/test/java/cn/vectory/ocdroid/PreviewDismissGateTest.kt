package cn.vectory.ocdroid

import cn.vectory.ocdroid.ui.files.PreviewDismissGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §B1-P0 (fix copy→predictive-back crash): JVM unit coverage for the
 * [PreviewDismissGate] state machine that drives the Files preview's
 * one-frame "dismissing" phase.
 *
 * The gate itself is pure / stateless — Compose snapshot state and the
 * actual `withFrameNanos { }` timing live in `FilesScreen`. What we test
 * here are the **transition predicates**: when the host should arm
 * dismissing, when it should reset, and whether text is selectable in each
 * phase. These are the silent-regression surface (a check.sh build can't
 * catch a logic flip in the closing guard or the reset condition).
 *
 * Runs under `testDebugUnitTest` (no Compose rule, no Robolectric, no
 * device).
 */
class PreviewDismissGateTest {

    // ------------------------------------------------------------------
    // shouldStartDismissing — requestClosePreview's body
    // ------------------------------------------------------------------

    @Test
    fun `shouldStartDismissing true when preview open and not already dismissing`() {
        assertTrue(
            PreviewDismissGate.shouldStartDismissing(
                currentSelectedFilePath = "src/Main.kt",
                dismissing = false,
            )
        )
    }

    @Test
    fun `shouldStartDismissing false when no preview is open (closing guard - no-op)`() {
        // Prevents arming dismissing with no preview to dismiss (otherwise
        // we'd cause a one-frame blackout on the file list for nothing).
        assertFalse(
            PreviewDismissGate.shouldStartDismissing(
                currentSelectedFilePath = null,
                dismissing = false,
            )
        )
    }

    @Test
    fun `shouldStartDismissing false when already dismissing (closing guard - reentry safe)`() {
        // The lock-up preventer: a second tap on the close button while the
        // deferred close is in flight MUST NOT re-arm. Otherwise the user
        // could chain-stall dismissing and never see the preview close.
        assertFalse(
            PreviewDismissGate.shouldStartDismissing(
                currentSelectedFilePath = "src/Main.kt",
                dismissing = true,
            )
        )
    }

    @Test
    fun `shouldStartDismissing false when no preview AND already dismissing`() {
        // Defensive: never re-arm in a weird transitional state. Both guard
        // conditions OR together to false.
        assertFalse(
            PreviewDismissGate.shouldStartDismissing(
                currentSelectedFilePath = null,
                dismissing = true,
            )
        )
    }

    @Test
    fun `shouldStartDismissing is idempotent - calling twice with same dismissing=true returns false`() {
        // Simulates the rapid double-tap: first call flips host dismissing
        // false→true; second call (now with dismissing=true) must no-op.
        var hostDismissedOnce = PreviewDismissGate.shouldStartDismissing(
            currentSelectedFilePath = "a.txt",
            dismissing = false,
        )
        assertTrue(hostDismissedOnce)
        val secondCall = PreviewDismissGate.shouldStartDismissing(
            currentSelectedFilePath = "a.txt",
            dismissing = true, // host applied the first transition
        )
        assertFalse(secondCall)
    }

    // ------------------------------------------------------------------
    // shouldReset — the LaunchedEffect(selectedFilePath) body
    // ------------------------------------------------------------------

    @Test
    fun `shouldReset true when currently dismissing`() {
        // The host fires this on every selectedFilePath change. While
        // dismissing, any change should reset so the gate doesn't stick.
        assertTrue(PreviewDismissGate.shouldReset(dismissing = true))
    }

    @Test
    fun `shouldReset false when not dismissing`() {
        // No-op when there's nothing to reset (the common steady state).
        assertFalse(PreviewDismissGate.shouldReset(dismissing = false))
    }

    // ------------------------------------------------------------------
    // textSelectable — the FilePreviewPane parameter binding
    // ------------------------------------------------------------------

    @Test
    fun `textSelectable true in normal preview state (dismissing=false)`() {
        // Preview is open normally → SelectionContainer wraps text → user
        // can long-press / copy / drag handles.
        assertTrue(PreviewDismissGate.textSelectable(dismissing = false))
    }

    @Test
    fun `textSelectable false during dismissing phase`() {
        // The crash fix: while dismissing, render plain Text so the
        // SelectionContainer subtree leaves composition one frame BEFORE
        // the pane is removed. After that frame, closePreview() runs and
        // the pane leaves cleanly — no LayoutCoordinate reads on a
        // detached node.
        assertFalse(PreviewDismissGate.textSelectable(dismissing = true))
    }

    // ------------------------------------------------------------------
    // Integration-by-hand: the StateFlow conflate hazard that motivated
    // "reset on ANY change" (vs "reset only on == null").
    // ------------------------------------------------------------------

    @Test
    fun `dismissing does not stick across a conflate-collapsed X to null to Y transition`() {
        // Simulates: preview open at X → user closes → StateFlow conflates
        // the close+reopen so the LaunchedEffect(selectedFilePath) collector
        // only sees the final value Y, never observing the intermediate
        // null. The host should still be able to dismiss again afterwards.
        //
        // Pre-fix (when reset required `selectedFilePath == null`): the
        // effect would fire with Y (not null), skip the reset branch, and
        // dismissing would stick at true — every subsequent close entry
        // would no-op via the closing guard. User lock-out.
        //
        // Post-fix: reset fires on any change, so dismissing clears even
        // when the null emission was elided.
        var hostDismissing = false
        val pathX = "src/FileA.kt"
        val pathY = "src/FileB.kt"

        // 1. Preview open at X; user requests close → gate arms dismissing.
        assertTrue(PreviewDismissGate.shouldStartDismissing(pathX, hostDismissing))
        hostDismissing = true

        // 2. Conflation collapses `X → null → Y` so the host LaunchedEffect
        //    observes only Y. Because the effect key changed (X → Y), the
        //    block runs; gate should still reset on this non-null change.
        val hostObservesPath = pathY // the `null` is elided by conflation
        // (the effect block runs once with this new value)
        // → host queries shouldReset:
        assertTrue(PreviewDismissGate.shouldReset(hostDismissing))
        hostDismissing = false

        // 3. The new preview at Y is open & selectable (not stuck dismissing).
        assertFalse("new preview must be selectable", hostDismissing)
        assertTrue(
            "must be able to dismiss the new preview",
            PreviewDismissGate.shouldStartDismissing(hostObservesPath, hostDismissing),
        )
    }

    @Test
    fun `X to null normal close also resets correctly`() {
        // Companion to the conflate test: the plain normal-close path still
        // behaves (selectedFilePath is observed null directly).
        var hostDismissing = true // already in dismissing phase

        // LaunchedEffect fires on selectedFilePath change; the new value is null.
        assertTrue(PreviewDismissGate.shouldReset(hostDismissing))
        hostDismissing = false

        // After reset, opening a fresh preview arms dismissing again.
        assertTrue(
            PreviewDismissGate.shouldStartDismissing("next.txt", hostDismissing)
        )
    }

    @Test
    fun `close mid-flight reopen (X to Y, no null observed) cancels the pending close`() {
        // Reaffirms the conflate case from a different angle: dismissing was
        // armed for X; before the deferred closePreview fires, the user (or
        // a deep-link nav) opens Y. The reset on any change clears
        // dismissing, and the host's `LaunchedEffect(dismissing)` re-keys
        // (false→ previously true→ false) cancelling the pending
        // withFrameNanos{} + closePreview coroutine.
        var hostDismissing = true

        // User opens Y mid-dismiss; LaunchedEffect(selectedFilePath) fires
        // for X→Y; reset clears dismissing.
        assertTrue(PreviewDismissGate.shouldReset(hostDismissing))
        hostDismissing = false

        // New preview is selectable.
        assertTrue(PreviewDismissGate.textSelectable(hostDismissing))
    }
}
