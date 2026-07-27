package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §11.4 (slim message reliability joint plan): pure-function unit tests
 * for [mergeSlimMessageSet] — the window-style merge of an incoming
 * message set onto the current authoritative set.
 *
 * Pins the merge contract:
 *  - `complete == false` is a no-op (returns authoritative unchanged).
 *  - Missing messages are NOT interpreted as deletion (no tombstone).
 *  - Duplicate full response is idempotent.
 *  - Older same-id tuple does not overwrite newer.
 *  - Same tuple with different parts is NOT silently replaced.
 *
 * Pure — no IO, no Android deps.
 */
class SlimMessageSetMergeTest {

    // ── §11.4 contract: complete == false is a no-op ────────────────────

    @Test
    fun `§11_4 partial result does not replace authoritative set`() {
        // complete == false ⇒ the incoming partial MUST NOT replace the
        // authoritative set, even when incoming is smaller / disjoint.
        // The caller may stage the partial elsewhere, but the
        // authoritative cache is returned byte-for-byte unchanged.
        val authoritative = listOf(
            msg("m1", updated = 100L, parts = listOf(part("p1", "body-1"))),
            msg("m2", updated = 200L, parts = listOf(part("p2", "body-2"))),
        )
        val incoming = listOf(
            msg("m3", updated = 300L, parts = listOf(part("p3", "body-3"))),
        )

        val result = mergeSlimMessageSet(authoritative, incoming, complete = false)

        // Same instance — no allocation, no copy, no re-sort. Pins that
        // partial is genuinely a no-op (not "equal copy").
        assertSame(
            "complete=false must return the authoritative instance unchanged",
            authoritative,
            result,
        )
    }

    // ── §11.4 contract: missing ≠ deleted ───────────────────────────────

    @Test
    fun `§11_4 missing message is not interpreted as deletion`() {
        // The wire model has no tombstone / deleted field. An id present
        // in authoritative but absent from a complete incoming set is
        // RETAINED — absence is not a delete signal. (Phase A explicitly
        // does not invent tombstone semantics.)
        val authoritative = listOf(
            msg("m1", updated = 100L),
            msg("m2", updated = 100L),
        )
        val incoming = listOf(
            // m2 is MISSING from incoming — must NOT be dropped.
            msg("m1", updated = 100L),
        )

        val result = mergeSlimMessageSet(authoritative, incoming, complete = true)

        assertEquals(
            "missing id (m2) must be retained — missing is not deletion",
            listOf("m1", "m2"),
            result.map { it.info.id },
        )
    }

    // ── §11.4 contract: idempotent on duplicate full response ───────────

    @Test
    fun `§11_4 duplicate full response is idempotent`() {
        // Applying the same complete incoming set twice yields the same
        // authoritative view — no duplication, no drift, no parts churn.
        val authoritative = listOf(
            msg("m1", updated = 100L, parts = listOf(part("p1", "body-1"))),
            msg("m2", updated = 200L, parts = listOf(part("p2", "body-2"))),
        )
        val incoming = listOf(
            msg("m1", updated = 100L, parts = listOf(part("p1", "body-1"))),
            msg("m2", updated = 200L, parts = listOf(part("p2", "body-2"))),
        )

        val once = mergeSlimMessageSet(authoritative, incoming, complete = true)
        val twice = mergeSlimMessageSet(once, incoming, complete = true)

        assertEquals(
            "applying the same complete set twice must be idempotent",
            once,
            twice,
        )
        assertEquals(
            "no duplication — exactly the two ids survive",
            listOf("m1", "m2"),
            once.map { it.info.id },
        )
    }

    // ── §11.4 contract: older tuple ignored ─────────────────────────────

    @Test
    fun `§11_4 older same message does not overwrite newer value`() {
        // Same id, incoming has an OLDER update tuple ⇒ authoritative
        // retained (stale / out-of-order response must not regress).
        val authoritative = listOf(
            msg("m1", updated = 200L, parts = listOf(part("p-new", "newer-body"))),
        )
        val incoming = listOf(
            msg("m1", updated = 100L, parts = listOf(part("p-old", "older-body"))),
        )

        val result = mergeSlimMessageSet(authoritative, incoming, complete = true)

        assertEquals("older tuple must not overwrite newer", 200L, result[0].info.time?.updated)
        assertEquals(
            "authoritative parts retained on older incoming tuple",
            listOf(part("p-new", "newer-body")),
            result[0].parts,
        )
    }

    // ── §11.4 contract: same tuple + different parts ⇒ keep authoritative ─

    @Test
    fun `§11_4 same tuple with different parts is not silently replaced`() {
        // Same (updated, id) tuple but different parts. Phase A has no
        // part revision, so the merge MUST NOT silently overwrite. Keep
        // the authoritative entry; the divergence is handed off to a
        // later full / cursor reconcile (caller keeps dirty).
        val authoritative = listOf(
            msg("m1", updated = 200L, parts = listOf(part("p-auth", "auth-body"))),
        )
        val incoming = listOf(
            msg("m1", updated = 200L, parts = listOf(part("p-inc", "incoming-body"))),
        )

        val result = mergeSlimMessageSet(authoritative, incoming, complete = true)

        assertEquals(200L, result[0].info.time?.updated)
        assertNotEquals(
            "incoming parts must NOT silently replace authoritative parts on equal tuple",
            listOf(part("p-inc", "incoming-body")),
            result[0].parts,
        )
        assertEquals(
            "authoritative parts retained on same-tuple-different-parts",
            listOf(part("p-auth", "auth-body")),
            result[0].parts,
        )
    }

    // ── §11.4 extra: complete=true adds new ids & replaces strictly-newer ─

    @Test
    fun `§11_4 complete merge adds incoming id absent from authoritative`() {
        // complete=true: an id present in incoming but NOT in
        // authoritative is ADDED (it is a genuinely new message, not a
        // missing-from-authoritative deletion).
        val authoritative = listOf(
            msg("m1", updated = 100L, parts = listOf(part("p1", "body-1"))),
        )
        val incoming = listOf(
            msg("m2", updated = 200L, parts = listOf(part("p2", "body-2"))),
        )

        val result = mergeSlimMessageSet(authoritative, incoming, complete = true)

        assertEquals(
            "incoming new id (m2) must be added under complete merge",
            listOf("m1", "m2"),
            result.map { it.info.id },
        )
    }

    @Test
    fun `§11_4 complete merge replaces when incoming tuple is strictly newer`() {
        // Same id, incoming has a STRICTLY-NEWER update tuple ⇒ incoming
        // wins (both the info and the parts).
        val authoritative = listOf(
            msg("m1", updated = 100L, parts = listOf(part("p-old", "old-body"))),
        )
        val incoming = listOf(
            msg("m1", updated = 300L, parts = listOf(part("p-new", "new-body"))),
        )

        val result = mergeSlimMessageSet(authoritative, incoming, complete = true)

        assertEquals(
            "strictly-newer tuple must replace (updated advanced)",
            300L,
            result[0].info.time?.updated,
        )
        assertEquals(
            "strictly-newer tuple must replace (incoming parts adopted)",
            listOf(part("p-new", "new-body")),
            result[0].parts,
        )
    }

    @Test
    fun `§11_4 per-id merge tie-break is a no-op within a single id slot`() {
        // Documenting a structural property of the per-id merge:
        // mergeSameMessage compares (updated, id) of incoming vs previous
        // for the SAME id slot (the map key). The id component is
        // therefore ALWAYS equal across the two sides, so the ordering
        // decision reduces to `updated` alone. The compareWatermark id
        // tie-break only matters ACROSS different ids (maxMessageTuple /
        // needsReconcile), never within one slot. Here equal updated on
        // the same id ⇒ order 0 ⇒ authoritative retained (same-tuple
        // rule), regardless of parts.
        val authoritative = listOf(
            msg("m1", updated = 200L, parts = listOf(part("p-auth", "auth"))),
        )
        val incoming = listOf(
            msg("m1", updated = 200L, parts = listOf(part("p-inc", "inc"))),
        )

        val result = mergeSlimMessageSet(authoritative, incoming, complete = true)

        assertEquals(
            "same id + same updated ⇒ order 0 ⇒ retain authoritative parts",
            listOf(part("p-auth", "auth")),
            result[0].parts,
        )
    }

    // ── §11.4 extra: sort stability ─────────────────────────────────────

    @Test
    fun `§11_4 result is sorted by created then updated then id`() {
        // Sort key: (created ?: MAX, updated ?: MAX, id) ascending.
        // Pins both the ordering and the nulls-last behavior for
        // created / updated.
        val authoritative = listOf(
            // No time at all → created=MAX, updated=MAX → sorts LAST by id.
            msg("z-none", parts = listOf(part("p", "x"))),
            msg("m-created-50", created = 50L, updated = 500L),
        )
        val incoming = listOf(
            msg("a-created-50", created = 50L, updated = 100L),
            msg("b-created-10", created = 10L, updated = 999L),
        )

        val result = mergeSlimMessageSet(authoritative, incoming, complete = true)

        assertEquals(
            "sort by (created ?: MAX, updated ?: MAX, id); null-time items sort last by id",
            listOf("b-created-10", "a-created-50", "m-created-50", "z-none"),
            result.map { it.info.id },
        )
    }

    @Test
    fun `§11_4 empty authoritative with complete incoming returns incoming sorted`() {
        // Cold path: authoritative empty, incoming complete ⇒ result is
        // incoming (sorted), nothing dropped.
        val incoming = listOf(
            msg("m2", updated = 200L),
            msg("m1", updated = 100L),
        )

        val result = mergeSlimMessageSet(emptyList(), incoming, complete = true)

        assertEquals(
            listOf("m1", "m2"),
            result.map { it.info.id },
        )
    }

    @Test
    fun `§11_4 empty incoming with complete true retains authoritative sorted`() {
        // complete=true but incoming empty ⇒ authoritative retained
        // (re-sorted). Missing-from-incoming is not deletion (again).
        val authoritative = listOf(
            msg("m2", updated = 200L),
            msg("m1", updated = 100L),
        )

        val result = mergeSlimMessageSet(authoritative, emptyList(), complete = true)

        assertEquals(
            "empty incoming ⇒ authoritative retained and re-sorted",
            listOf("m1", "m2"),
            result.map { it.info.id },
        )
    }

    // ── rev-ogpt P1-1: hasConflict signal coverage ───────────────────────

    /**
     * rev-ogpt P1-1: mergeSlimMessageSetWithConflict surfaces hasConflict=true
     * when at least one same-tuple-different-parts divergence is detected.
     * The caller (commit + reconciler paths) threads this into the commit's
     * atomic dirty decision. Pins the conflict-detection signal itself.
     */
    @Test
    fun `§11_4 rev-ogpt P1-1 mergeSlimMessageSetWithConflict flags same tuple different parts`() {
        // Same (updated, id) tuple but different parts on m1 ⇒ hasConflict=true.
        // m2 has no prior — pure insertion, no conflict.
        val authoritative = listOf(
            msg("m1", updated = 200L, parts = listOf(part("p-auth", "auth-body"))),
        )
        val incoming = listOf(
            msg("m1", updated = 200L, parts = listOf(part("p-inc", "inc-body"))),
            msg("m2", updated = 300L, parts = listOf(part("p2", "body-2"))),
        )

        val result = mergeSlimMessageSetWithConflict(authoritative, incoming, complete = true)

        assertTrue(
            "P1-1: same-tuple-different-parts on m1 MUST set hasConflict=true",
            result.hasConflict,
        )
        assertEquals(
            "both ids present in the merged set",
            listOf("m1", "m2"),
            result.messages.map { it.info.id },
        )
        // Authoritative parts retained on the conflicting id (phase A has no
        // part-level authoritative merge — kept for later reconcile).
        val m1Merged = result.messages.first { it.info.id == "m1" }
        assertEquals(
            "authoritative parts retained on same-tuple-different-parts",
            listOf(part("p-auth", "auth-body")),
            m1Merged.parts,
        )
    }

    /**
     * rev-ogpt P1-1: hasConflict=false when no same-tuple-different-parts
     * divergence exists (strictly-newer replacement, pure insertion, or
     * identical parts). Pins the negative case.
     */
    @Test
    fun `§11_4 rev-ogpt P1-1 mergeSlimMessageSetWithConflict no conflict on strictly newer or identical`() {
        // m1: strictly newer (incoming wins, no conflict).
        // m2: identical tuple + identical parts (idempotent, no conflict).
        val authoritative = listOf(
            msg("m1", updated = 100L, parts = listOf(part("p1", "v1"))),
            msg("m2", updated = 200L, parts = listOf(part("p2", "v2"))),
        )
        val incoming = listOf(
            msg("m1", updated = 300L, parts = listOf(part("p1-new", "v1-new"))),
            msg("m2", updated = 200L, parts = listOf(part("p2", "v2"))),
        )

        val result = mergeSlimMessageSetWithConflict(authoritative, incoming, complete = true)

        assertFalse(
            "P1-1: strictly-newer replacement + idempotent identical parts MUST NOT set hasConflict",
            result.hasConflict,
        )
    }

    /**
     * rev-ogpt P1-1: partial merge (complete=false) returns hasConflict=false
     * (the merge is a no-op; no conflict detection runs).
     */
    @Test
    fun `§11_4 rev-ogpt P1-1 mergeSlimMessageSetWithConflict partial returns no conflict`() {
        val authoritative = listOf(
            msg("m1", updated = 200L, parts = listOf(part("p-auth", "auth"))),
        )
        val incoming = listOf(
            msg("m1", updated = 200L, parts = listOf(part("p-inc", "inc"))),
        )

        val result = mergeSlimMessageSetWithConflict(authoritative, incoming, complete = false)

        assertFalse(
            "P1-1: complete=false MUST return hasConflict=false (no-op merge)",
            result.hasConflict,
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun msg(
        id: String,
        updated: Long? = null,
        created: Long? = null,
        parts: List<Part> = emptyList(),
        role: String = "assistant",
    ): MessageWithParts = MessageWithParts(
        info = Message(
            id = id,
            role = role,
            time = if (updated != null || created != null) {
                Message.TimeInfo(created = created, updated = updated)
            } else null,
        ),
        parts = parts,
    )

    private fun part(id: String, text: String): Part =
        Part(id = id, type = "text", text = text)
}
