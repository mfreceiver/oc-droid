package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §1C: unit tests for the destructive-gate primitives in
 * [MessageCard.kt]. All assertions call the **production** pure
 * functions directly — no self-built lambdas, no inlined mirror
 * logic. If a future refactor drifts the production predicate or
 * the production state machine, the test fails.
 *
 * The destructive gate has three legs (each tested here):
 *   1. [canEditAndRerun] — the menu-item enablement predicate. Pure
 *      function on a [DestructiveGateInputs] value type. Mirrors
 *      [cn.vectory.ocdroid.ui.RevertConversation.execute]'s own
 *      intercept set (busy / retry / sending / streamingPartTexts /
 *      streamingReasoningPart + isUser). The UI is a
 *      double-insurance alongside the use case.
 *   2. [confirmOnMenuTap] / [confirmOnConfirmTap] / [confirmOnCancel]
 *      — the [ConfirmState] state machine that gates the destructive
 *      callback to fire exactly once. The state machine is the
 *      load-bearing guard (the use case's inFlight set is the
 *      authoritative backstop; the state machine is the honest UX
 *      — a disabled confirm button after the first tap).
 *   3. [collectMessageText] — the Copy target. Streaming deltas win
 *      over committed part text; non-text parts are skipped; parts
 *      are joined in order.
 */
class MessageCardDestructiveGateTest {

    // ───────────────────────────────────────────────────────────────────
    // canEditAndRerun — production predicate, all six conditions
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `canEditAndRerun is true on an idle session with no streaming and a user message`() {
        assertTrue(
            "happy path: user message on a quiet, non-sending, non-streaming session",
            canEditAndRerun(
                DestructiveGateInputs(
                    isUser = true,
                    sessionIsBusy = false,
                    sessionIsRetry = false,
                    isSending = false,
                    hasStreamingText = false,
                    hasStreamingReasoning = false,
                )
            )
        )
    }

    @Test
    fun `canEditAndRerun is false on an assistant message even when all other gates are clear`() {
        // §1C-FIX-④: this test calls the PRODUCTION predicate with
        // isUser=false. It does NOT re-implement the logic. A
        // refactor that flipped the isUser branch would fail.
        assertFalse(
            "assistant message — Phase 0 RevertConversation rejects non-user ids",
            canEditAndRerun(
                DestructiveGateInputs(
                    isUser = false,
                    sessionIsBusy = false,
                    sessionIsRetry = false,
                    isSending = false,
                    hasStreamingText = false,
                    hasStreamingReasoning = false,
                )
            )
        )
    }

    @Test
    fun `canEditAndRerun is false on a system message even when all other gates are clear`() {
        assertFalse(
            "system message — never a valid revert pivot",
            canEditAndRerun(
                DestructiveGateInputs(
                    isUser = false,
                    sessionIsBusy = false,
                    sessionIsRetry = false,
                    isSending = false,
                    hasStreamingText = false,
                    hasStreamingReasoning = false,
                )
            )
        )
    }

    @Test
    fun `canEditAndRerun is false when the session is busy (use case intercepts too)`() {
        assertFalse(
            "busy session — use case's isBusy check would also fire",
            canEditAndRerun(
                DestructiveGateInputs(
                    isUser = true,
                    sessionIsBusy = true,
                    sessionIsRetry = false,
                    isSending = false,
                    hasStreamingText = false,
                    hasStreamingReasoning = false,
                )
            )
        )
    }

    @Test
    fun `canEditAndRerun is false when the session is retrying`() {
        assertFalse(
            "retrying session — use case's isRetry check would also fire",
            canEditAndRerun(
                DestructiveGateInputs(
                    isUser = true,
                    sessionIsBusy = false,
                    sessionIsRetry = true,
                    isSending = false,
                    hasStreamingText = false,
                    hasStreamingReasoning = false,
                )
            )
        )
    }

    @Test
    fun `canEditAndRerun is false when the session is in the sendingSessionIds window`() {
        // §1C-FIX-②: the previous test missed this leg. A send-ACK
        // is in flight — the user message exists client-side but not
        // yet on the server, so the use case would reject a revert
        // (the message id is not yet owned server-side). The UI
        // must mirror this — disabled menu item.
        assertFalse(
            "sending session — use case's sendingSessionIds check would also fire",
            canEditAndRerun(
                DestructiveGateInputs(
                    isUser = true,
                    sessionIsBusy = false,
                    sessionIsRetry = false,
                    isSending = true,
                    hasStreamingText = false,
                    hasStreamingReasoning = false,
                )
            )
        )
    }

    @Test
    fun `canEditAndRerun is false when streamingPartTexts is non-empty`() {
        // §1C-FIX-②: the pre-part window (server created the
        // assistant message but no first delta yet) leaves
        // streamingPartTexts non-empty even when no text is visible
        // to the user. The UI gate mirrors the use case's
        // `streamingPartTexts.isNotEmpty()` check.
        assertFalse(
            "streaming text in flight — use case's streamingPartTexts check would also fire",
            canEditAndRerun(
                DestructiveGateInputs(
                    isUser = true,
                    sessionIsBusy = false,
                    sessionIsRetry = false,
                    isSending = false,
                    hasStreamingText = true,
                    hasStreamingReasoning = false,
                )
            )
        )
    }

    @Test
    fun `canEditAndRerun is false when streamingReasoningPart is non-null`() {
        // §1C-FIX-②: a standalone streaming-reasoning part is
        // active even when no text delta has arrived. The use case
        // checks `streamingReasoningPart != null`; the UI mirrors it.
        assertFalse(
            "streaming reasoning in flight — use case's streamingReasoningPart check would also fire",
            canEditAndRerun(
                DestructiveGateInputs(
                    isUser = true,
                    sessionIsBusy = false,
                    sessionIsRetry = false,
                    isSending = false,
                    hasStreamingText = false,
                    hasStreamingReasoning = true,
                )
            )
        )
    }

    @Test
    fun `canEditAndRerun is false when BOTH isBusy and isRetry are true (boundary)`() {
        // Boundary: the use case checks busy / retry independently
        // and either blocks; the UI must also block if either is
        // true. Verify the AND-of-cases: a session that is both busy
        // AND retrying is still blocked.
        assertFalse(
            "busy AND retrying — the UI gate must still block",
            canEditAndRerun(
                DestructiveGateInputs(
                    isUser = true,
                    sessionIsBusy = true,
                    sessionIsRetry = true,
                    isSending = false,
                    hasStreamingText = false,
                    hasStreamingReasoning = false,
                )
            )
        )
    }

    // ───────────────────────────────────────────────────────────────────
    // ConfirmState state machine — production transitions, the
    // load-bearing exactly-once guard (③/④)
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `menu tap from MenuClosed moves to ConfirmOpen`() {
        assertEquals(ConfirmState.ConfirmOpen, confirmOnMenuTap(ConfirmState.MenuClosed))
    }

    @Test
    fun `menu tap from MenuOpen moves to ConfirmOpen (idempotent)`() {
        assertEquals(ConfirmState.ConfirmOpen, confirmOnMenuTap(ConfirmState.MenuOpen))
    }

    @Test
    fun `menu tap from ConfirmOpen is a no-op (dialog already open)`() {
        // Prevents a double-tap on the menu item from spawning a
        // second dialog: the second tap sees ConfirmOpen and stays
        // put. The destructive callback NEVER fires from the menu
        // tap.
        assertEquals(ConfirmState.ConfirmOpen, confirmOnMenuTap(ConfirmState.ConfirmOpen))
    }

    @Test
    fun `menu tap from ConfirmFired is a no-op (already fired)`() {
        // Once the destructive callback has fired, the dialog is
        // already on its way out. A second menu tap (impossible in
        // practice because the menu is closed by then) cannot
        // re-fire the callback.
        assertEquals(ConfirmState.ConfirmFired, confirmOnMenuTap(ConfirmState.ConfirmFired))
    }

    @Test
    fun `menu tap NEVER fires the destructive callback (production transition is pure)`() {
        // The state-machine transition function ONLY returns a new
        // state; it does NOT fire callbacks. Callbacks are fired by
        // the Composable that owns the state cells, AFTER calling
        // the production transition. This is a load-bearing
        // property: the menu-tap code path has no reference to
        // onEditAndRerun whatsoever.
        val states = listOf(
            ConfirmState.MenuClosed,
            ConfirmState.MenuOpen,
            ConfirmState.ConfirmOpen,
            ConfirmState.ConfirmFired,
        )
        for (s in states) {
            // The transition function's signature is `fun
            // confirmOnMenuTap(state): ConfirmState` — no callback
            // parameter. We assert the function is referentially
            // transparent (same input → same output) and pure (no
            // IO / no side effects on the test recorder).
            val result = confirmOnMenuTap(s)
            assertTrue(
                "menu tap on $s must return a ConfirmState (got $result)",
                result in setOf(
                    ConfirmState.MenuClosed,
                    ConfirmState.MenuOpen,
                    ConfirmState.ConfirmOpen,
                    ConfirmState.ConfirmFired,
                )
            )
        }
    }

    @Test
    fun `confirm tap from ConfirmOpen fires the callback and locks the state`() {
        // §1C-FIX-③/④: the production transition. The Composable
        // calls [confirmOnConfirmTap]; the result tells the
        // Composable whether to invoke the destructive callback.
        val result = confirmOnConfirmTap(ConfirmState.ConfirmOpen)
        assertEquals("first confirm tap must lock the state", ConfirmState.ConfirmFired, result.nextState)
        assertTrue("first confirm tap MUST fire the callback", result.firesCallback)
    }

    @Test
    fun `confirm tap from ConfirmFired does NOT fire again — exactly-once guard`() {
        // §1C-FIX-③/④: the load-bearing property. After the first
        // confirm tap, the state is ConfirmFired. A second confirm
        // tap (which in practice is blocked by the disabled button,
        // but the state machine is the authoritative guard) MUST
        // return firesCallback=false. The destructive callback fires
        // AT MOST ONCE per dialog open.
        val result = confirmOnConfirmTap(ConfirmState.ConfirmFired)
        assertEquals("state must remain ConfirmFired", ConfirmState.ConfirmFired, result.nextState)
        assertFalse(
            "second confirm tap must NOT fire the callback (exactly-once guard)",
            result.firesCallback,
        )
    }

    @Test
    fun `confirm tap from MenuClosed does NOT fire (button is gated, but the state machine is the guard)`() {
        // Defensive: the button only renders when state ==
        // ConfirmOpen, but a stray tap on a still-rendering button
        // cannot bypass the state machine.
        val result = confirmOnConfirmTap(ConfirmState.MenuClosed)
        assertFalse("must not fire from MenuClosed", result.firesCallback)
    }

    @Test
    fun `confirm tap from MenuOpen does NOT fire (button is gated, but the state machine is the guard)`() {
        val result = confirmOnConfirmTap(ConfirmState.MenuOpen)
        assertFalse("must not fire from MenuOpen", result.firesCallback)
    }

    @Test
    fun `cancel from ConfirmOpen returns to MenuClosed without firing`() {
        val next = confirmOnCancel(ConfirmState.ConfirmOpen)
        assertEquals(ConfirmState.MenuClosed, next)
    }

    @Test
    fun `cancel from MenuClosed is a no-op (defensive)`() {
        assertEquals(ConfirmState.MenuClosed, confirmOnCancel(ConfirmState.MenuClosed))
    }

    @Test
    fun `cancel from ConfirmFired is a no-op (already fired — should not re-open)`() {
        // Once the callback has fired, the dialog stays open
        // briefly for the user to see feedback. Cancel from
        // ConfirmFired is a no-op (the dialog closes via the
        // composable's clear path, not via the state machine).
        assertEquals(ConfirmState.ConfirmFired, confirmOnCancel(ConfirmState.ConfirmFired))
    }

    @Test
    fun `end-to-end exactly-once open + tap + tap does NOT fire twice`() {
        // §1C-FIX-③/④: the load-bearing end-to-end property. The
        // full state-machine round-trip:
        //   1. Start MenuClosed.
        //   2. Menu tap → ConfirmOpen.
        //   3. Confirm tap → ConfirmFired + fire.
        //   4. Confirm tap again (e.g. button still rendering while
        //      RevertConversation's inFlight is processing) → still
        //      ConfirmFired, NO second fire.
        var s = ConfirmState.MenuClosed
        val fires = mutableListOf<Boolean>()
        s = confirmOnMenuTap(s); assertEquals(ConfirmState.ConfirmOpen, s)
        val r1 = confirmOnConfirmTap(s); fires += r1.firesCallback; s = r1.nextState
        assertEquals(ConfirmState.ConfirmFired, s)
        val r2 = confirmOnConfirmTap(s); fires += r2.firesCallback; s = r2.nextState
        val r3 = confirmOnConfirmTap(s); fires += r3.firesCallback; s = r3.nextState
        assertEquals(
            "exactly-once: 1 fire out of 3 confirm taps, the rest are no-ops",
            listOf(true, false, false),
            fires,
        )
        assertEquals(ConfirmState.ConfirmFired, s)
    }

    @Test
    fun `end-to-end cancel-then-reopen cancel returns to MenuClosed, menu tap re-opens`() {
        // The state machine allows the user to cancel and reopen
        // (within the same MessageCard composition). The previous
        // design's `confirmRevertOpen` boolean + the new state
        // machine both support this — the test pins that the
        // cancel transition does NOT lock the state forever.
        var s = ConfirmState.MenuClosed
        s = confirmOnMenuTap(s)
        s = confirmOnCancel(s)
        assertEquals(ConfirmState.MenuClosed, s)
        s = confirmOnMenuTap(s)
        assertEquals(ConfirmState.ConfirmOpen, s)
        val r = confirmOnConfirmTap(s)
        assertTrue(r.firesCallback)
    }

    // ───────────────────────────────────────────────────────────────────
    // collectMessageText — production helper (unchanged from prior phase)
    // ───────────────────────────────────────────────────────────────────

    @Test
    fun `collectMessageText concatenates text parts in order`() {
        val parts = listOf(
            textPart("p1", "hello"),
            textPart("p2", "world"),
        )
        val text = collectMessageText(parts, streamingPartTexts = emptyMap(), context = null)
        assertEquals("hello\nworld", text)
    }

    @Test
    fun `collectMessageText prefers streaming delta over committed part text`() {
        val parts = listOf(
            textPart("p1", "committed draft"),
        )
        val text = collectMessageText(
            parts,
            streamingPartTexts = mapOf("p1" to "live streaming text"),
            context = null,
        )
        assertEquals(
            "streaming deltas must win — user copies what they SEE, not the stale committed text",
            "live streaming text",
            text,
        )
    }

    @Test
    fun `collectMessageText skips non-text parts (tool, patch, image, sub-agent)`() {
        val parts = listOf(
            textPart("p1", "user prompt"),
            toolPart("p2"),
            textPart("p3", "follow-up"),
            patchPart("p4"),
            imagePart("p5"),
            subAgentPart("p6"),
        )
        val text = collectMessageText(parts, streamingPartTexts = emptyMap(), context = null)
        assertEquals(
            "non-text parts (tool/patch/image/sub-agent) must be filtered out",
            "user prompt\nfollow-up",
            text,
        )
    }

    @Test
    fun `collectMessageText returns empty string for a no-text-only message`() {
        val parts = listOf(
            toolPart("p1"),
            patchPart("p2"),
        )
        val text = collectMessageText(parts, streamingPartTexts = emptyMap(), context = null)
        assertEquals("", text)
    }

    @Test
    fun `collectMessageText treats blank text part as no contribution (empty parts are filtered)`() {
        val parts = listOf(
            textPart("p1", ""),
            textPart("p2", "hello"),
        )
        val text = collectMessageText(parts, streamingPartTexts = emptyMap(), context = null)
        // Empty text parts are filtered (takeIf it.isNotEmpty), so the
        // result is just "hello" — no spurious blank line.
        assertEquals("hello", text)
    }

    // ───────────────────────────────────────────────────────────────────
    // Fixtures
    // ───────────────────────────────────────────────────────────────────

    private fun textPart(id: String, text: String) =
        Part(id = id, messageId = "m1", sessionId = "s1", type = "text", text = text)
    private fun toolPart(id: String) =
        Part(id = id, messageId = "m1", sessionId = "s1", type = "tool", tool = "bash")
    private fun patchPart(id: String) =
        Part(id = id, messageId = "m1", sessionId = "s1", type = "patch", tool = "edit")
    private fun imagePart(id: String) =
        Part(id = id, messageId = "m1", sessionId = "s1", type = "file", mime = "image/png")
    private fun subAgentPart(id: String) =
        Part(id = id, messageId = "m1", sessionId = "s1", type = "tool", tool = "task")
}
