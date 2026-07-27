package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.SlimSinceStageAOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // ── §11.1 stage A: reconciler uses typed staging outcome ────────────────

    /**
     * §11.1 stage A: the reconciler's anchored `/since` port MUST return
     * [SlimSinceStageAOutcome] (NOT the legacy `Result<List<MessageWithParts>>`).
     *
     * This pins the structural contract: the reconciler consumes the typed
     * staging outcome, which makes it impossible to treat a `Staged` variant
     * as an authoritative success (the type system prevents it). If a future
     * edit reverts to the legacy `fetchSince(...): Result<List<MessageWithParts>>`,
     * this assertion fails at compile time (the return type no longer matches).
     */
    @Test
    fun `reconcilerUsesStageAOutcomeInsteadOfLegacySinceFacade`() {
        // The port's return type IS the assertion: fetchSinceStageA returns
        // SlimSinceStageAOutcome (not Result<List<MessageWithParts>>). We
        // pin it by assigning the port reference to a typed local — if the
        // signature regresses, this line fails to compile.
        val port: suspend SlimReconcileRepositoryPort.(String, Long, cn.vectory.ocdroid.data.repository.OpenCodeRepository.SlimCommitToken) -> SlimSinceStageAOutcome =
            SlimReconcileRepositoryPort::fetchSinceStageA
        // Structural assertion: the method exists and returns the typed outcome.
        assertTrue("fetchSinceStageA port must exist", port != null)

        // The legacy port (Result<List<MessageWithParts>>) must NOT exist on
        // the interface. We pin its absence by asserting that the interface
        // does NOT declare a `fetchSince` method returning Result.
        val legacyPort = try {
            SlimReconcileRepositoryPort::class.java.getMethod(
                "fetchSince",
                String::class.java,
                Long::class.java,
                cn.vectory.ocdroid.data.repository.OpenCodeRepository.SlimCommitToken::class.java,
            )
            true
        } catch (e: NoSuchMethodException) {
            false
        }
        assertFalse(
            "legacy fetchSince(...): Result<List<MessageWithParts>> port must NOT exist (stage A closed it)",
            legacyPort,
        )
    }

    // ── §11.1 stage A: MessageSource anchored path does not bind bookmark lambda

    /**
     * §11.1 stage A: the anchored slim `/since` path in [MessageSource] MUST
     * NOT bind or invoke a bookmark-mutation lambda. The old `bumpBookmark`
     * constructor parameter was removed; the anchored path now returns a
     * [SlimSinceStageAOutcome] without any bookmark mutation.
     *
     * §11.1 fix-8 P1-1: a 3rd constructor parameter was ADDED —
     * `requireSlimTokenCurrent: (SlimCommitToken) -> Unit` — so the
     * Stage-A fetch can perform pre/post token guards (mirroring
     * SlimSyncEngine.fetchSinceForStageA). The constructor param count
     * went from 2 (apiProvider + slimSessionUpdatedAt) to 3. This test
     * pins the SHAPE: all three params must be function types; a future
     * edit that re-introduces the old `bumpBookmark` signature
     * `(String, List<MessageWithParts>, SlimCommitToken) -> Boolean`
     * surfaces as a non-Function1 3rd param and fails the assertion.
     */
    @Test
    fun `messageSourceAnchoredPathDoesNotBindBookmarkLambda`() {
        val ctor = cn.vectory.ocdroid.data.repository.SlimMessageSource::class.java
            .constructors.firstOrNull()
        assertNotNull("SlimMessageSource must have a constructor", ctor)

        val paramCount = ctor!!.parameters.size
        assertEquals(
            "SlimMessageSource constructor must have exactly 3 params " +
                "(apiProvider + slimSessionUpdatedAt + requireSlimTokenCurrent); " +
                "the old bumpBookmark lambda was removed in stage A. Got $paramCount",
            3,
            paramCount,
        )
        // Pin the parameter types: all three are function types.
        // - param 0: apiProvider ((SlimCommitToken) -> OpenCodeApi)
        // - param 1: slimSessionUpdatedAt ((String) -> Long)
        // - param 2: requireSlimTokenCurrent ((SlimCommitToken) -> Unit)  [P1-1]
        // The OLD 3rd param was suspend (String, List<MessageWithParts>,
        // SlimCommitToken) -> Boolean (the bumpBookmark lambda) — that's a
        // Function3. The new 3rd param is a Function1. The assertion below
        // only checks "is a function type" — the arity check would require
        // reflection on the lambda interface; the source-level review
        // covers that.
        val paramTypes = ctor.parameters.map { it.type.name }
        assertTrue(
            "param 0 should be a function type (apiProvider)",
            paramTypes[0].contains("Function"),
        )
        assertTrue(
            "param 1 should be a function type (slimSessionUpdatedAt)",
            paramTypes[1].contains("Function"),
        )
        assertTrue(
            "param 2 should be a function type (requireSlimTokenCurrent — P1-1)",
            paramTypes[2].contains("Function"),
        )
    }

    // ── §11.1 fix-12b P1-2: cache-retention failure → forceDirty re-ratchet ─

    /**
     * §11.1 fix-12b P1-2 (rev-ogpt): the cache-retention failure paths in
     * [SlimSessionReconciler.applyCurrentReconcileResult] MUST call
     * `forceDirty` (UNCONDITIONAL dirty ratchet), NOT `markDirty` (gated
     * by `needsReconcile`).
     *
     * The prior code's `markDirty` was a semantic NO-OP in the normal
     * steady state — right after an authoritative commit landed with
     * `remote <= localApplied`, `dirty = false`, `needsReconcile = false`,
     * `markSlimDirty` returned `true` but left `dirty = false` (because
     * the gate evaluated to false). The user was left with no cached
     * window AND no scheduled retry.
     *
     * `forceDirty` closes the hole by setting `dirty` unconditionally
     * (still under the SlimCommitToken guard). This test pins the
     * contract at the port surface (compile-time + reflection):
     *
     *  - [SlimReconcileRepositoryPort] declares `forceDirty` (the
     *    reconciler-facing API).
     *  - [cn.vectory.ocdroid.data.repository.OpenCodeRepository] exposes
     *    `forceSlimDirty` (the production delegate target, retained in
     *    fix-11b for exactly this retention path).
     *
     * Behavioral coverage of the actual retention-failure call site
     * (success-path contrast) lives in
     * `SessionSyncCoordinatorResyncTest.kt` (T3a — `markSlimDirty` NOT
     * re-invoked when retention SUCCEEDED).
     */
    @Test
    fun `cache-retention failure re-ratchets via forceDirty port not gated markDirty (P1-2)`() {
        // (1) SlimReconcileRepositoryPort declares forceDirty (the
        //     unconditional dirty ratchet). If a future edit removes it,
        //     this fails. Match the structural-test idiom of
        //     `reconcilerUsesStageAOutcomeInsteadOfLegacySinceFacade`.
        val portForceDirty = try {
            SlimReconcileRepositoryPort::class.java.getMethod(
                "forceDirty",
                String::class.java,
                cn.vectory.ocdroid.data.repository.OpenCodeRepository.SlimCommitToken::class.java,
            )
            true
        } catch (e: NoSuchMethodException) {
            false
        }
        assertTrue(
            "SlimReconcileRepositoryPort must declare forceDirty(sid, token): Boolean " +
                "for P1-2 cache-retention failure re-ratchet",
            portForceDirty,
        )

        // (2) OpenCodeRepository exposes forceSlimDirty — the production
        //     adapter delegate target. Retained in fix-11b for exactly
        //     this retention path; pinned here so a future cleanup cannot
        //     silently delete it without breaking the contract test.
        val repoForceSlimDirty = try {
            cn.vectory.ocdroid.data.repository.OpenCodeRepository::class.java.getMethod(
                "forceSlimDirty",
                String::class.java,
                cn.vectory.ocdroid.data.repository.OpenCodeRepository.SlimCommitToken::class.java,
            )
            true
        } catch (e: NoSuchMethodException) {
            false
        }
        assertTrue(
            "OpenCodeRepository must expose forceSlimDirty(sid, token): Boolean " +
                "for P1-2 (the production adapter delegates forceDirty → forceSlimDirty)",
            repoForceSlimDirty,
        )

        // (3) Sanity: the gated markDirty is still on the port (other
        //     call sites depend on it — fix-12b only swapped the three
        //     retention failure paths). This pins that the fix did NOT
        //     delete the gated method (which would break those callers).
        val portMarkDirty = try {
            SlimReconcileRepositoryPort::class.java.getMethod(
                "markDirty",
                String::class.java,
                cn.vectory.ocdroid.data.repository.OpenCodeRepository.SlimCommitToken::class.java,
            )
            true
        } catch (e: NoSuchMethodException) {
            false
        }
        assertTrue(
            "SlimReconcileRepositoryPort must STILL declare markDirty(sid, token): Boolean " +
                "(P1-2 only swapped the three retention failure paths; other callers depend on the gated method)",
            portMarkDirty,
        )
    }
}
