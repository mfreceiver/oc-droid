package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.SessionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 §8.4: unit tests for [SlimSessionReconciler]'s pure policy helpers.
 *
 * These test the moved helpers DIRECTLY (no SSC / coroutine / port wiring
 * required) so the extraction can be validated in isolation:
 *
 *  - [isAuthoritativeSlimMerge] — the authoritative-vs-skeleton slim merge
 *    decision. The expression was moved byte-for-byte from SSC (P4-B); these
 *    tests pin all four decision branches so a future edit can't silently
 *    flip ownership semantics for an actively-streaming token stream.
 *
 * The predicate is a file-level `internal fun` in [SlimSessionReconciler.kt]
 * (same package), so it is callable directly here with no constructor /
 * harness setup.
 *
 * See docs/ocmar/plans/2026-07-24-p4-slim-session-reconciler-design.md §8.4.
 */
class SlimSessionReconcilerTest {

    // ── §8.4: isAuthoritativeSlimMerge (expression UNCHANGED) ──────────────

    /**
     * §8.4: RESYNC is a forced catch-up — fetched content wins even when the
     * session happens to be busy at merge time. An in-flight token stream's
     * owned parts are cleared (authoritative splice).
     *
     * This is the resync-vs-busy guarantee: if P4-B drops the
     * `mode == SlimReconcileMode.RESYNC` short-circuit, this assertion flips
     * to false (busy session → skeleton), regressing the Stage-B §3.4
     * authoritative-clear contract.
     */
    @Test
    fun `RESYNC merge is authoritative even while busy`() {
        assertTrue(
            isAuthoritativeSlimMerge(
                mode = SlimReconcileMode.RESYNC,
                sid = "s1",
                sessionStatuses = mapOf("s1" to SessionStatus(type = "busy")),
            )
        )
    }

    /**
     * §8.4: DIGEST_FOCUS for an actively busy OR retrying session must be
     * SKELETON (non-authoritative) — the session owns an in-flight token
     * stream whose streamed parts must be preserved on a skeleton merge.
     *
     * If P4-B accidentally makes DIGEST_FOCUS authoritative while busy, the
     * optimistic-busy-overwrite regression (the StatusDiag failure mode)
     * returns: a fetched skeleton would clear owned streaming parts mid-
     * stream.
     */
    @Test
    fun `DIGEST_FOCUS busy and retry merges preserve stream ownership`() {
        assertFalse(
            isAuthoritativeSlimMerge(
                mode = SlimReconcileMode.DIGEST_FOCUS,
                sid = "s1",
                sessionStatuses = mapOf("s1" to SessionStatus(type = "busy")),
            )
        )
        assertFalse(
            isAuthoritativeSlimMerge(
                mode = SlimReconcileMode.DIGEST_FOCUS,
                sid = "s1",
                sessionStatuses = mapOf("s1" to SessionStatus(type = "retry")),
            )
        )
    }

    /**
     * §8.4: DIGEST_FOCUS for an UNKNOWN status (`null` — session not in the
     * map) OR an IDLE session must be AUTHORITATIVE — there is no active
     * token stream to preserve, so fetched content wins outright.
     *
     * The unknown-status branch is the fail-safe: a session the status map
     * doesn't know about is treated as idle/authoritative (never silently
     * skeleton).
     */
    @Test
    fun `DIGEST_FOCUS unknown and idle statuses are authoritative`() {
        // Unknown (absent from the map) → fail-safe authoritative.
        assertTrue(
            isAuthoritativeSlimMerge(
                mode = SlimReconcileMode.DIGEST_FOCUS,
                sid = "s1",
                sessionStatuses = emptyMap(),
            )
        )
        // Idle → no active stream → authoritative.
        assertTrue(
            isAuthoritativeSlimMerge(
                mode = SlimReconcileMode.DIGEST_FOCUS,
                sid = "s1",
                sessionStatuses = mapOf("s1" to SessionStatus(type = "idle")),
            )
        )
    }

    // ── §8.4 supplements: forceAuthoritative override + DIGEST_BACKGROUND ──

    /**
     * §8.4 supplement: the explicit [forceAuthoritative] override wins
     * regardless of mode/status — the cold-start snapshot path (and any
     * future forced-reconcile trigger) uses it to force a full authoritative
     * splice even for a busy session.
     */
    @Test
    fun `forceAuthoritative override wins even for busy DIGEST_FOCUS`() {
        assertTrue(
            isAuthoritativeSlimMerge(
                mode = SlimReconcileMode.DIGEST_FOCUS,
                sid = "s1",
                sessionStatuses = mapOf("s1" to SessionStatus(type = "busy")),
                forceAuthoritative = true,
            )
        )
    }

    /**
     * §8.4 supplement: DIGEST_BACKGROUND behaves like DIGEST_FOCUS for the
     * busy/idle decision (neither is RESYNC). A busy BACKGROUND merge is
     * SKELETON; an idle BACKGROUND merge is AUTHORITATIVE. Pins that the
     * mode-agnostic status branch applies uniformly to both DIGEST_* modes.
     */
    @Test
    fun `DIGEST_BACKGROUND busy is skeleton and idle is authoritative`() {
        assertFalse(
            isAuthoritativeSlimMerge(
                mode = SlimReconcileMode.DIGEST_BACKGROUND,
                sid = "s1",
                sessionStatuses = mapOf("s1" to SessionStatus(type = "busy")),
            )
        )
        assertTrue(
            isAuthoritativeSlimMerge(
                mode = SlimReconcileMode.DIGEST_BACKGROUND,
                sid = "s1",
                sessionStatuses = mapOf("s1" to SessionStatus(type = "idle")),
            )
        )
    }
}
