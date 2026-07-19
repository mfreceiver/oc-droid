package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 8 (slimapi v1 §G6 merge + §G2 status mapping) — pure-function
 * unit tests for the two primitives in this file:
 *
 *  - [mergeFullBatchIntoLocal] — T8-C1 null-safe (messageId, partId)
 *    replace. Pins: both-sided null messageId (owner-id fallback),
 *    duplicate partId (associateBy keeps last), partial full response
 *    (non-fetched messages untouched), non-replaced parts preserved,
 *    and the mismatch case (one null + one non-null messageId does
 *    NOT match).
 *  - [mapStatusOutcome] — T8-C2 each [StatusOutcome] variant maps to
 *    the documented [MappedStatusAction]; DirectoryError yields
 *    `Warn(null)` (no code on source), UpstreamWarn carries its code.
 *
 * Pure — no IO, no Android deps.
 */
class SlimapiMessageMergeTest {

    // ── T8-C1: mergeFullBatchIntoLocal ────────────────────────────────────

    @Test
    fun `T8-C1 both-sided null messageId falls back to owner ids and replaces`() {
        // Local part: messageId = null. Full part: messageId = null.
        // Both normMsg(owner) = "msg-1" (the owning message id) → match.
        val local = listOf(
            msgWithParts("msg-1", part(id = "p1", messageId = null, text = "thin"))
        )
        val full = listOf(
            msgWithParts("msg-1", part(id = "p1", messageId = null, text = "full-expanded"))
        )

        val merged = mergeFullBatchIntoLocal(local, full)

        assertEquals(1, merged.size)
        assertEquals(1, merged[0].parts.size)
        assertEquals("full-expanded", merged[0].parts[0].text)
    }

    @Test
    fun `T8-C1 messageId mismatch one null one non-null blocks replacement`() {
        // Local part: messageId = null → normMsg = "msg-1".
        // Full part:   messageId = "msg-other" → normMsg = "msg-other".
        // Same partId, same owner message id, but normMsg differs → NOT replaced.
        val local = listOf(
            msgWithParts("msg-1", part(id = "p1", messageId = null, text = "thin"))
        )
        val full = listOf(
            msgWithParts("msg-1", part(id = "p1", messageId = "msg-other", text = "should-not-apply"))
        )

        val merged = mergeFullBatchIntoLocal(local, full)

        assertEquals("thin", merged[0].parts[0].text)
    }

    @Test
    fun `T8-C1 both-sided non-null matching messageId replaces`() {
        val local = listOf(
            msgWithParts("msg-1", part(id = "p1", messageId = "msg-1", text = "thin"))
        )
        val full = listOf(
            msgWithParts("msg-1", part(id = "p1", messageId = "msg-1", text = "full"))
        )

        val merged = mergeFullBatchIntoLocal(local, full)

        assertEquals("full", merged[0].parts[0].text)
    }

    @Test
    fun `T8-C1 duplicate partId within full message keeps last (associateBy pins behaviour)`() {
        // Two full parts share partId "p1" — associateBy keeps the LAST one.
        val local = listOf(
            msgWithParts("msg-1", part(id = "p1", messageId = null, text = "thin"))
        )
        val full = listOf(
            msgWithParts(
                "msg-1",
                part(id = "p1", messageId = null, text = "first-wins-no"),
                part(id = "p1", messageId = null, text = "last-wins-yes"),
            )
        )

        val merged = mergeFullBatchIntoLocal(local, full)

        assertEquals(
            "associateBy must keep the last duplicate partId",
            "last-wins-yes",
            merged[0].parts[0].text,
        )
    }

    @Test
    fun `T8-C1 partial full response leaves non-fetched messages unchanged`() {
        // Local has msg-1 and msg-2; full has only msg-1.
        // msg-2 must come back byte-for-byte identical.
        val local = listOf(
            msgWithParts("msg-1", part(id = "p1", messageId = null, text = "thin-1")),
            msgWithParts("msg-2", part(id = "p2", messageId = null, text = "thin-2")),
        )
        val full = listOf(
            msgWithParts("msg-1", part(id = "p1", messageId = null, text = "full-1"))
        )

        val merged = mergeFullBatchIntoLocal(local, full)

        assertEquals(2, merged.size)
        assertEquals("full-1", merged[0].parts[0].text)
        // msg-2 untouched: same instance (the `return@map lm` early-exit).
        assertSame(local[1], merged[1])
        assertEquals("thin-2", merged[1].parts[0].text)
    }

    @Test
    fun `T8-C1 full replacement preserves non-replaced sibling parts`() {
        // Local msg has [p1, p2]; full fetch only carries p1.
        // Result must be [replaced-p1, original-p2] — the `?: lp` fallback.
        val originalP2 = part(id = "p2", messageId = null, text = "thin-2")
        val local = listOf(
            msgWithParts(
                "msg-1",
                part(id = "p1", messageId = null, text = "thin-1"),
                originalP2,
            )
        )
        val full = listOf(
            msgWithParts("msg-1", part(id = "p1", messageId = null, text = "full-1"))
        )

        val merged = mergeFullBatchIntoLocal(local, full)

        assertEquals(2, merged[0].parts.size)
        assertEquals("full-1", merged[0].parts[0].text)
        assertSame(
            "non-replaced sibling part must be the same instance (untouched)",
            originalP2,
            merged[0].parts[1],
        )
    }

    @Test
    fun `T8-C1 empty full items returns local unchanged`() {
        val local = listOf(
            msgWithParts("msg-1", part(id = "p1", messageId = null, text = "thin"))
        )

        val merged = mergeFullBatchIntoLocal(local, emptyList())

        assertEquals(1, merged.size)
        assertSame(local[0], merged[0])
    }

    @Test
    fun `T8-C1 empty local returns empty`() {
        val merged = mergeFullBatchIntoLocal(
            local = emptyList(),
            fullItems = listOf(msgWithParts("msg-1", part(id = "p1", messageId = null))),
        )
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `T8-C1 multiple messages each get their own replacement scope`() {
        // Two messages, each with one part sharing the same partId "p1".
        // Replacement must be scoped per-message-id (no cross-contamination).
        val local = listOf(
            msgWithParts("msg-1", part(id = "p1", messageId = null, text = "thin-1")),
            msgWithParts("msg-2", part(id = "p1", messageId = null, text = "thin-2")),
        )
        val full = listOf(
            msgWithParts("msg-2", part(id = "p1", messageId = null, text = "full-2")),
            // msg-1 deliberately absent — partial.
        )

        val merged = mergeFullBatchIntoLocal(local, full)

        assertEquals("thin-1", merged[0].parts[0].text)
        assertEquals("full-2", merged[1].parts[0].text)
    }

    // ── T8-C2: mapStatusOutcome ───────────────────────────────────────────

    @Test
    fun `T8-C2 SessionMissing maps to ClearLocal`() {
        val action = mapStatusOutcome(StatusOutcome.SessionMissing(sessionId = "s1"))

        assertEquals(MappedStatusAction.ClearLocal, action)
    }

    @Test
    fun `T8-C2 Success maps to ApplyStatus carrying the raw status`() {
        val status = SessionStatus(type = "busy", attempt = 2, message = null, next = 1234L)
        val action = mapStatusOutcome(StatusOutcome.Success(sessionId = "s1", status = status))

        assertTrue("Success must map to ApplyStatus", action is MappedStatusAction.ApplyStatus)
        assertEquals(status, (action as MappedStatusAction.ApplyStatus).status)
    }

    @Test
    fun `T8-C2 Success idle stays ApplyStatus (T4-C2 not folded to ClearLocal)`() {
        // Idle is NOT folded here — the reconcile layer cross-checks the
        // session list before trusting it. Pins the T4-C2 invariant.
        val status = SessionStatus(type = "idle")
        val action = mapStatusOutcome(StatusOutcome.Success(sessionId = "s1", status = status))

        assertNotEquals(
            "idle Success must NOT fold to ClearLocal at this layer",
            MappedStatusAction.ClearLocal,
            action,
        )
        assertEquals(status, (action as MappedStatusAction.ApplyStatus).status)
    }

    @Test
    fun `T8-C2 Retry maps to Retry`() {
        val action = mapStatusOutcome(
            StatusOutcome.Retry(sessionId = "s1", code = "upstream_unavailable")
        )

        assertEquals(MappedStatusAction.Retry, action)
    }

    @Test
    fun `T8-C2 Retry transport-level null code still maps to Retry`() {
        val action = mapStatusOutcome(
            StatusOutcome.Retry(sessionId = "s1", code = null)
        )

        assertEquals(MappedStatusAction.Retry, action)
    }

    @Test
    fun `T8-C2 DirectoryError maps to Warn with null code (no code on source)`() {
        val action = mapStatusOutcome(StatusOutcome.DirectoryError(sessionId = "s1"))

        assertTrue(action is MappedStatusAction.Warn)
        assertEquals(
            "DirectoryError has no code on source → Warn.code must be null",
            null,
            (action as MappedStatusAction.Warn).code,
        )
    }

    @Test
    fun `T8-C2 UpstreamWarn carries its sidecar code into Warn`() {
        val action = mapStatusOutcome(
            StatusOutcome.UpstreamWarn(sessionId = "s1", code = "upstream_http_500")
        )

        assertTrue(action is MappedStatusAction.Warn)
        assertEquals("upstream_http_500", (action as MappedStatusAction.Warn).code)
    }

    @Test
    fun `T8-C2 UpstreamWarn null code maps to Warn null`() {
        val action = mapStatusOutcome(
            StatusOutcome.UpstreamWarn(sessionId = "s1", code = null)
        )

        assertTrue(action is MappedStatusAction.Warn)
        assertEquals(null, (action as MappedStatusAction.Warn).code)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun msgWithParts(id: String, vararg parts: Part): MessageWithParts =
        MessageWithParts(
            info = Message(id = id, role = "assistant"),
            parts = parts.toList(),
        )

    private fun part(id: String, messageId: String?, text: String? = null): Part =
        Part(id = id, messageId = messageId, type = "text", text = text)
}
