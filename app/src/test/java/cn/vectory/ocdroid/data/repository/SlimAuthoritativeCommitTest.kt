package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §11.2 (slim message reliability joint plan — stage A): unit tests for
 * [InternalSlimAuthoritativeCommitter] — the process-local token-guarded
 * authoritative commit protocol.
 *
 * # Scope pinned here
 *
 *  - The fixed 7-step commit order (plan §11.2 steps 1–7): every failure
 *    branch keeps `oldVisibleMessages == currentVisibleMessages`,
 *    `oldLocalApplied*` unchanged, and `dirty == true`.
 *  - Token staleness (pre-check and in-critical-section recheck) maps to
 *    [SlimAuthoritativeCommitResult.StaleToken] with no memory mutation.
 *  - Diagnostic cache failure aborts BEFORE the in-memory critical
 *    section and surfaces as [SlimAuthoritativeCommitResult.CacheWriteFailed]
 *    with memory state unchanged.
 *  - Structural candidate validation rejects split localApplied pairs,
 *    empty-messages-with-non-null-localApplied, and
 *    eligible-messages-with-null-localApplied as
 *    [SlimAuthoritativeCommitResult.MergeRejected].
 *  - The committed localApplied* is the candidate's tuple, NOT a
 *    re-derivation from messages.
 *  - The commit does NOT read or mutate `remote*`.
 *  - The candidate's `messages` list instance is not mutated in place.
 *
 * Tests use a [RecordingHost] fake that records every host call and lets
 * each test program the token / cache / commit behavior. This keeps the
 * committer testable in isolation without constructing a full
 * [OpenCodeRepository] (the host adapter is fix-4's wiring job).
 */
class SlimAuthoritativeCommitTest {

    @Before
    fun enableDebugAssertions() {
        SlimAuthoritativeCommitDebugAssertions.enabled = true
    }

    // ── §11.2 scenario: stale token pre-check keeps old state ────────────

    @Test
    fun `§11_2 staleTokenKeepsOldState`() {
        // requireToken throws StaleSlimCommitException → committer returns
        // StaleToken WITHOUT touching cache or memory. oldVisible* /
        // oldLocalApplied* / dirty must all survive.
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "msg_old",
                    dirty = true,
                    remoteUpdatedAt = 200L,
                    remoteMessageId = "msg_remote",
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("msg_old", updated = 100L))),
            requireTokenThrows = true,
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            messages = listOf(msg("msg_new", updated = 200L)),
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "msg_new",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "stale token (pre-check throw) must surface as StaleToken",
            SlimAuthoritativeCommitResult.StaleToken,
            result,
        )
        // Failure-branch invariants.
        assertEquals(
            listOf(msg("msg_old", updated = 100L)),
            host.visibleMessages["s1"],
        )
        assertEquals(100L, host.sessions["s1"]?.localAppliedUpdatedAt)
        assertEquals("msg_old", host.sessions["s1"]?.localAppliedMessageId)
        assertTrue("dirty must stay true on StaleToken", host.sessions["s1"]?.dirty == true)
        // remote* untouched.
        assertEquals(200L, host.sessions["s1"]?.remoteUpdatedAt)
        assertEquals("msg_remote", host.sessions["s1"]?.remoteMessageId)
        // requireToken was the first and only host call up to the throw —
        // no cache write, no critical section.
        assertFalse(
            "no cache write on StaleToken pre-check",
            host.writeDiagnosticCacheCalls.any { it.sessionId == "s1" },
        )
        assertFalse(
            "no commitIfCurrent invocation on StaleToken pre-check",
            host.commitIfCurrentInvocations.any { it.token === candidate.token },
        )
    }

    // ── §11.2 scenario: stale generation / incarnation → StaleToken ──────

    @Test
    fun `§11_2 staleGenerationKeepsDirty`() {
        // Stage A collapses every staleness mode (marker rotation, epoch
        // bump, generation/endpoint mismatch) into StaleToken — there is
        // no separate stamp-conflict result. Here requireToken throws
        // (any staleness cause) → StaleToken, dirty stays true.
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "msg_old",
                    dirty = true,
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("msg_old", updated = 100L))),
            requireTokenThrows = true, // simulates any staleness cause
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            messages = listOf(msg("msg_new", updated = 200L)),
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "msg_new",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "stale generation / incarnation must surface as StaleToken (no separate stamp-conflict result)",
            SlimAuthoritativeCommitResult.StaleToken,
            result,
        )
        assertTrue("dirty must stay true on stale generation", host.sessions["s1"]?.dirty == true)
        assertEquals(
            "localApplied* unchanged on stale generation",
            100L,
            host.sessions["s1"]?.localAppliedUpdatedAt,
        )
    }

    // ── §11.2 scenario: cache write failure → CacheWriteFailed, no commit ─

    @Test
    fun `§11_2 diagnosticCacheFailureDoesNotAffectAuthoritativeMemoryState`() {
        // requireToken passes, validation passes, then the diagnostic
        // cache write throws. Stage A keeps the protocol simple: the
        // cache failure aborts BEFORE the in-memory critical section, so
        // authoritative memory stays at old* (plan §11.2 step 5).
        val cacheFailureCause = IOExceptionSim("disk full")
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "msg_old",
                    dirty = true,
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("msg_old", updated = 100L))),
            writeDiagnosticCacheThrows = cacheFailureCause,
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            messages = listOf(msg("msg_new", updated = 200L)),
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "msg_new",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertTrue(
            "cache failure must surface as CacheWriteFailed",
            result is SlimAuthoritativeCommitResult.CacheWriteFailed,
        )
        assertSame(
            "CacheWriteFailed must carry the original cause instance (not a copy)",
            cacheFailureCause,
            (result as SlimAuthoritativeCommitResult.CacheWriteFailed).cause,
        )
        // Failure-branch invariants — memory state untouched.
        assertEquals(
            "oldVisibleMessages must equal currentVisibleMessages on CacheWriteFailed",
            listOf(msg("msg_old", updated = 100L)),
            host.visibleMessages["s1"],
        )
        assertEquals(
            "localAppliedUpdatedAt unchanged on CacheWriteFailed",
            100L,
            host.sessions["s1"]?.localAppliedUpdatedAt,
        )
        assertEquals(
            "localAppliedMessageId unchanged on CacheWriteFailed",
            "msg_old",
            host.sessions["s1"]?.localAppliedMessageId,
        )
        assertTrue(
            "dirty must stay true on CacheWriteFailed",
            host.sessions["s1"]?.dirty == true,
        )
        assertFalse(
            "no commitIfCurrent invocation on CacheWriteFailed",
            host.commitIfCurrentInvocations.any { it.committed },
        )
    }

    // ── §11.2 scenario: successful commit updates visible + localApplied atomically ─

    @Test
    fun `§11_2 successfulCommitUpdatesVisibleContentAndLocalAppliedTogether`() {
        // Happy path: requireToken passes, validation passes, cache write
        // succeeds, commitIfCurrent returns true. The host's
        // replaceVisibleAndAuthoritative + replaceLocalAppliedAndClearDirty
        // fire ONCE, atomically, inside the one commitIfCurrent block.
        //
        // §11.1 fix-9 P0-4: remoteUpdatedAt is set BELOW the candidate's
        // tuple (200) so the commit's atomic dirty decision clears dirty
        // (remote <= localApplied ⇒ needsReconcile=false ⇒ dirty=false).
        // The prior version of this test set remote=250 against localApplied=
        // 200, which under the P0-4 contract CORRECTLY keeps dirty=true (the
        // server is still ahead post-commit). The happy-path commit clears
        // dirty only when remote <= localApplied.
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "msg_old",
                    dirty = true,
                    remoteUpdatedAt = 150L,
                    remoteMessageId = "msg_remote",
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("msg_old", updated = 100L))),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val newMessages = listOf(msg("msg_new", updated = 200L))
        val candidate = candidate(
            sessionId = "s1",
            messages = newMessages,
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "msg_new",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "successful commit must surface as Committed",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        // Success assertions (plan §11.2 step 7): visible content,
        // localApplied watermark, dirty. NOT cache coherency.
        //
        // rev-ogpt P1-1 fix-14: the happy-path commit per-ID MERGES rather
        // than overwrites. msg_old@100 (in current but missing from the
        // candidate) is PRESERVED (missing ≠ deleted); msg_new@200 (from
        // the candidate) is ADDED. The merge sorts by (created, updated,
        // id) — both messages here have created=null, so the secondary
        // `updated` key sorts msg_old@100 before msg_new@200.
        assertEquals(
            "visible content is the per-ID merge result — msg_old@100 PRESERVED + msg_new@200 added",
            listOf(msg("msg_old", updated = 100L), msg("msg_new", updated = 200L)),
            host.visibleMessages["s1"],
        )
        assertEquals(
            "localAppliedUpdatedAt must be the candidate's tuple",
            200L,
            host.sessions["s1"]?.localAppliedUpdatedAt,
        )
        assertEquals(
            "localAppliedMessageId must be the candidate's tuple",
            "msg_new",
            host.sessions["s1"]?.localAppliedMessageId,
        )
        assertFalse(
            "dirty must be cleared on Committed when remote <= localApplied",
            host.sessions["s1"]?.dirty == true,
        )
        // remote* MUST be untouched (invariant #1).
        assertEquals(
            "remoteUpdatedAt must be untouched by the commit (digest-reducer owned)",
            150L,
            host.sessions["s1"]?.remoteUpdatedAt,
        )
        assertEquals(
            "remoteMessageId must be untouched by the commit (digest-reducer owned)",
            "msg_remote",
            host.sessions["s1"]?.remoteMessageId,
        )
        // Exactly one commitIfCurrent invocation.
        assertEquals(
            "exactly one commitIfCurrent invocation on happy path",
            1,
            host.commitIfCurrentInvocations.size,
        )
        assertTrue(
            "commitIfCurrent must have committed (returned true)",
            host.commitIfCurrentInvocations.single().committed,
        )
    }

    // ── §11.2 scenario: partial candidate → MergeRejected ────────────────

    @Test
    fun `§11_2 partialCandidateCannotCommit`() {
        // Candidate carries messages with an eligible item (updated > 0,
        // non-blank id) but localApplied* is (null, null). This is the
        // "caller forgot to run maxMessageTuple after a complete drain"
        // signal. The committer rejects it; memory is untouched.
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "msg_old",
                    dirty = true,
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("msg_old", updated = 100L))),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            // Eligible item: updated=200, non-blank id.
            messages = listOf(msg("msg_new", updated = 200L)),
            // BUT localApplied* is (null, null) — caller forgot.
            localAppliedUpdatedAt = null,
            localAppliedMessageId = null,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertTrue(
            "eligible messages + null localApplied must be MergeRejected",
            result is SlimAuthoritativeCommitResult.MergeRejected,
        )
        // Failure-branch invariants.
        assertEquals(
            listOf(msg("msg_old", updated = 100L)),
            host.visibleMessages["s1"],
        )
        assertEquals(100L, host.sessions["s1"]?.localAppliedUpdatedAt)
        assertEquals("msg_old", host.sessions["s1"]?.localAppliedMessageId)
        assertTrue(host.sessions["s1"]?.dirty == true)
        assertFalse(
            "no cache write on MergeRejected",
            host.writeDiagnosticCacheCalls.any { it.sessionId == "s1" },
        )
    }

    // ── extra: split localApplied pair → MergeRejected ───────────────────

    @Test
    fun `§11_2 splitLocalAppliedPairIsRejected`() {
        // §11.3 pair legality: only (null, null) or (non-null, non-null).
        // (ts, null) and (null, id) are illegal and must be rejected.
        val host = RecordingHost(
            sessions = mapOf("s1" to sessionState(dirty = true)),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)

        val tsNullIdNonNull = candidate(
            sessionId = "s1",
            messages = listOf(msg("m1", updated = 100L)),
            localAppliedUpdatedAt = null,
            localAppliedMessageId = "msg_x",
        )
        val r1 = runBlocking { committer.commitAuthoritative(tsNullIdNonNull) }
        assertTrue(
            "(null, id) split pair must be MergeRejected",
            r1 is SlimAuthoritativeCommitResult.MergeRejected,
        )

        val tsNonNullIdNull = candidate(
            sessionId = "s1",
            messages = listOf(msg("m1", updated = 100L)),
            localAppliedUpdatedAt = 100L,
            localAppliedMessageId = null,
        )
        val r2 = runBlocking { committer.commitAuthoritative(tsNonNullIdNull) }
        assertTrue(
            "(ts, null) split pair must be MergeRejected",
            r2 is SlimAuthoritativeCommitResult.MergeRejected,
        )
    }

    // ── extra: candidate with "null" (empty) messages → MergeRejected ────

    @Test
    fun `§11_2 candidateWithNullMessagesReturnsMergeRejected`() {
        // The candidate's `messages` field is non-nullable per the plan
        // DTO shape. "Null messages" here is the closest Kotlin-equivalent
        // illegal state: an EMPTY messages list combined with a NON-NULL
        // localApplied pair. The caller cannot have derived a watermark
        // from no messages, so this is structurally inconsistent.
        val host = RecordingHost(
            sessions = mapOf("s1" to sessionState(dirty = true)),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            messages = emptyList(),
            localAppliedUpdatedAt = 100L,
            localAppliedMessageId = "msg_x",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertTrue(
            "empty messages + non-null localApplied must be MergeRejected",
            result is SlimAuthoritativeCommitResult.MergeRejected,
        )
    }

    // ── extra: empty messages + null localApplied is VALID (cold-start) ───

    @Test
    fun `§11_2 fix-9 P0-2 emptyCandidateAgainstNonEmptyWatermarkReturnsMergeRejected`() {
        // §11.1 fix-9 P0-2: an empty candidate (null,null) against a
        // current state with a non-null localApplied pair is a watermark
        // REGRESSION — the candidate would clear the watermark that was
        // previously advanced. The in-lock authoritative check refuses
        // it as MergeRejected. This pins the new contract (the prior
        // fix-8 test pinned the invalid behavior — empty-candidate
        // cleared the watermark — that P0-2 removes).
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "msg_old",
                    dirty = true,
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("msg_old", updated = 100L))),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            messages = emptyList(),
            localAppliedUpdatedAt = null,
            localAppliedMessageId = null,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertTrue(
            "P0-2: empty candidate against non-empty watermark MUST be MergeRejected (got $result)",
            result is SlimAuthoritativeCommitResult.MergeRejected,
        )
        val reason = (result as SlimAuthoritativeCommitResult.MergeRejected).reason
        assertTrue(
            "reason must mention null watermark regression (got: $reason)",
            reason.contains("null watermark", ignoreCase = true),
        )
        // Failure-branch invariants: state untouched.
        assertEquals(
            "visible content preserved",
            listOf(msg("msg_old", updated = 100L)),
            host.visibleMessages["s1"],
        )
        assertEquals(
            "localAppliedUpdatedAt preserved",
            100L,
            host.sessions["s1"]?.localAppliedUpdatedAt,
        )
        assertEquals(
            "localAppliedMessageId preserved",
            "msg_old",
            host.sessions["s1"]?.localAppliedMessageId,
        )
        assertTrue(
            "dirty preserved on MergeRejected",
            host.sessions["s1"]?.dirty == true,
        )
    }

    @Test
    fun `§11_2 fix-9 P0-2 emptyCandidateAgainstEmptyWatermarkCommitsSuccessfully`() {
        // §11.1 fix-9 P0-2: empty candidate against an empty (cold-start)
        // watermark is legitimate — the session genuinely has no messages
        // and the caller captured that fact. Commit succeeds; visible
        // content cleared to empty; localApplied* stays (null,null).
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = null,
                    localAppliedMessageId = null,
                    dirty = true,
                ),
            ),
            visibleMessages = mapOf("s1" to emptyList()),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            messages = emptyList(),
            localAppliedUpdatedAt = null,
            localAppliedMessageId = null,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "P0-2: empty candidate + empty watermark is a valid cold-start commit",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        assertEquals(
            "visible content cleared to empty",
            emptyList<MessageWithParts>(),
            host.visibleMessages["s1"],
        )
        assertNull(
            "localAppliedUpdatedAt stays null (cold-start empty session)",
            host.sessions["s1"]?.localAppliedUpdatedAt,
        )
        assertNull(
            "localAppliedMessageId stays null (cold-start empty session)",
            host.sessions["s1"]?.localAppliedMessageId,
        )
    }

    // ── extra: token recheck in critical section detects race → StaleToken ─

    @Test
    fun `§11_2 tokenRecheckInCriticalSectionDetectsStaleAndReturnsStaleToken`() {
        // requireToken passes (token current at pre-check), validation
        // passes, cache write passes — BUT commitIfCurrent returns false
        // (the token rotated in the window between the cache write and
        // the critical section). Stage A surfaces this as StaleToken and
        // does NOT mutate memory. The cache write is left in place
        // (non-authoritative).
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "msg_old",
                    dirty = true,
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("msg_old", updated = 100L))),
            commitIfCurrentReturns = false, // simulate in-critical-section staleness
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            messages = listOf(msg("msg_new", updated = 200L)),
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "msg_new",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "token rotating inside the critical section must surface as StaleToken",
            SlimAuthoritativeCommitResult.StaleToken,
            result,
        )
        // Failure-branch invariants — memory untouched.
        assertEquals(
            listOf(msg("msg_old", updated = 100L)),
            host.visibleMessages["s1"],
        )
        assertEquals(100L, host.sessions["s1"]?.localAppliedUpdatedAt)
        assertEquals("msg_old", host.sessions["s1"]?.localAppliedMessageId)
        assertTrue(host.sessions["s1"]?.dirty == true)
        // The cache write DID happen (it's outside the critical section)
        // — that's acceptable per the plan (cache is non-authoritative).
        assertTrue(
            "cache write happens before the critical-section recheck",
            host.writeDiagnosticCacheCalls.any { it.sessionId == "s1" },
        )
        // commitIfCurrent was invoked exactly once and did NOT commit.
        assertEquals(1, host.commitIfCurrentInvocations.size)
        assertFalse(
            "commitIfCurrent returned false (token rotated in critical section)",
            host.commitIfCurrentInvocations.single().committed,
        )
        // Critically: replaceVisibleAndAuthoritative must NOT have fired
        // (commitIfCurrent's block did not run).
        assertFalse(
            "no visible replacement when commitIfCurrent returns false",
            host.replaceVisibleAndAuthoritativeCalls.any { it.sessionId == "s1" },
        )
        assertFalse(
            "no localApplied replacement when commitIfCurrent returns false",
            host.replaceLocalAppliedAndClearDirtyCalls.any { it.sessionId == "s1" },
        )
    }

    // ── extra: committed localApplied* is the candidate's, not re-derived ──

    @Test
    fun `§11_2 successfulCommitUsesCandidateLocalAppliedTupleNotDerivedFromMessages`() {
        // The committer must write the candidate's localApplied* verbatim,
        // NOT re-derive via maxMessageTuple. Construct a candidate whose
        // localApplied* does NOT match what maxMessageTuple(messages)
        // would produce — the committed value must still be the
        // candidate's tuple. This pins the "committer trusts the
        // candidate" contract (plan §11.2 step 6 / fix-1 maxMessageTuple
        // is the CALLER's derivation tool, not the committer's).
        val host = RecordingHost(
            sessions = mapOf("s1" to sessionState(dirty = true)),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        // maxMessageTuple(messages) would return (300L, "m3") — but the
        // candidate carries a deliberately different tuple. The committer
        // must commit the candidate's tuple, not the derived one.
        val messages = listOf(
            msg("m1", updated = 100L),
            msg("m3", updated = 300L),
            msg("m2", updated = 200L),
        )
        val candidate = candidate(
            sessionId = "s1",
            messages = messages,
            // Deliberately does NOT match maxMessageTuple = (300, "m3").
            localAppliedUpdatedAt = 150L,
            localAppliedMessageId = "m1-stale-marker",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "candidate with non-max-derived localApplied* must still commit",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        assertEquals(
            "committed localAppliedUpdatedAt must be the candidate's tuple, not maxMessageTuple's",
            150L,
            host.sessions["s1"]?.localAppliedUpdatedAt,
        )
        assertEquals(
            "committed localAppliedMessageId must be the candidate's tuple, not maxMessageTuple's",
            "m1-stale-marker",
            host.sessions["s1"]?.localAppliedMessageId,
        )
    }

    // ── extra: commit does NOT read or mutate remote* ────────────────────

    @Test
    fun `§11_2 commitDoesNotReadRemoteWatermark`() {
        // The commit protocol advances ONLY localApplied* + visible +
        // dirty. remote* is owned by the digest reducer (plan invariant
        // #1). Provide non-trivial remote* on the prior state; the
        // commit must leave them byte-for-byte unchanged.
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "msg_old",
                    dirty = true,
                    remoteUpdatedAt = 999L,
                    remoteMessageId = "msg_remote_high",
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("msg_old", updated = 100L))),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            messages = listOf(msg("msg_new", updated = 200L)),
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "msg_new",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(SlimAuthoritativeCommitResult.Committed, result)
        assertEquals(
            "remoteUpdatedAt must be untouched by commit",
            999L,
            host.sessions["s1"]?.remoteUpdatedAt,
        )
        assertEquals(
            "remoteMessageId must be untouched by commit",
            "msg_remote_high",
            host.sessions["s1"]?.remoteMessageId,
        )
        // Also: the host must not have received a remote-write call.
        // (replaceLocalAppliedAndClearDirty is the only state-mutating op
        // the committer issues, and the host fake records its args.)
        val mutation = host.replaceLocalAppliedAndClearDirtyCalls.single()
        assertEquals("s1", mutation.sessionId)
        // localApplied* fields carried, but the host fake does not expose
        // a remote-write path at all — assert by contract: the
        // RecordingHost has NO setRemote* method.
        assertNotNull(mutation)
    }

    // ── extra: candidate's messages list is not mutated in place ─────────

    @Test
    fun `§11_2 commitDoesNotMutateMessagesListInstance`() {
        // The committer must make a defensive copy of the candidate's
        // messages list before handing it to the host. The host (or any
        // downstream consumer) must not be able to mutate the candidate's
        // original list reference. The recording host's
        // replaceVisibleAndAuthoritative implementation deliberately
        // mutates the list it receives; the candidate's original must
        // survive intact.
        val host = RecordingHost(
            sessions = mapOf("s1" to sessionState(dirty = true)),
            // The host fake will call .clear() on the list it receives —
            // the committer's defensive copy protects the candidate.
            mutateReceivedMessageList = true,
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val originalMessages = mutableListOf(
            msg("m1", updated = 100L),
            msg("m2", updated = 200L),
        )
        val candidate = candidate(
            sessionId = "s1",
            messages = originalMessages,
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "m2",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(SlimAuthoritativeCommitResult.Committed, result)
        assertEquals(
            "candidate's original messages list must not be mutated in place",
            listOf(msg("m1", updated = 100L), msg("m2", updated = 200L)),
            originalMessages,
        )
        // The host fake stored a defensive copy of what it received; that
        // copy may have been .clear()'d by the host's mutation. Either
        // way, the candidate's original is safe.
        assertEquals(
            "exactly one replaceVisibleAndAuthoritative call",
            1,
            host.replaceVisibleAndAuthoritativeCalls.size,
        )
    }

    // ── extra: validation runs before cache write (no cache pollution) ───

    @Test
    fun `§11_2 validationRunsBeforeCacheWriteOnMergeRejected`() {
        // A MergeRejected candidate must NOT trigger a cache write — the
        // diagnostic cache must not be polluted by structurally-invalid
        // candidates. The committer's order is validate → cache write →
        // critical section.
        val host = RecordingHost(
            sessions = mapOf("s1" to sessionState(dirty = true)),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            messages = listOf(msg("m1", updated = 100L)),
            // Split pair — structurally invalid.
            localAppliedUpdatedAt = 100L,
            localAppliedMessageId = null,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertTrue(result is SlimAuthoritativeCommitResult.MergeRejected)
        assertFalse(
            "no cache write on MergeRejected (validation runs first)",
            host.writeDiagnosticCacheCalls.any { it.sessionId == "s1" },
        )
        assertFalse(
            "no commitIfCurrent invocation on MergeRejected",
            host.commitIfCurrentInvocations.isNotEmpty(),
        )
    }

    // ── extra: blank sessionId is rejected ───────────────────────────────

    @Test
    fun `§11_2 blankSessionIdIsRejected`() {
        val host = RecordingHost()
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "   ",
            messages = listOf(msg("m1", updated = 100L)),
            localAppliedUpdatedAt = 100L,
            localAppliedMessageId = "m1",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertTrue(result is SlimAuthoritativeCommitResult.MergeRejected)
        val reason = (result as SlimAuthoritativeCommitResult.MergeRejected).reason
        assertTrue(
            "MergeRejected reason must mention blank sessionId (got: $reason)",
            reason.contains("sessionId", ignoreCase = true),
        )
    }

    // ── §11.1 fix-8 P1-4: monotonic localApplied guard ───────────────────

    @Test
    fun `§11_2 monotonicLocalAppliedGuard rejects regressing candidate tuple`() {
        // P1-4: a candidate whose (ts, id) tuple is STRICTLY LESS THAN the
        // captured oldState tuple must be MergeRejected. The committer
        // captures pre-commit state and refuses to roll the watermark
        // backward (a late-arriving candidate from a superseded drain).
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 200L,
                    localAppliedMessageId = "m_newer",
                    dirty = true,
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("m_newer", updated = 200L))),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        // Regressing candidate: older ts (100 < 200).
        val candidate = candidate(
            sessionId = "s1",
            messages = listOf(msg("m_older", updated = 100L)),
            localAppliedUpdatedAt = 100L,
            localAppliedMessageId = "m_older",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertTrue(
            "P1-4: regressing ts must be MergeRejected (got $result)",
            result is SlimAuthoritativeCommitResult.MergeRejected,
        )
        val reason = (result as SlimAuthoritativeCommitResult.MergeRejected).reason
        assertTrue(
            "reason must mention non-monotonic (got: $reason)",
            reason.contains("non-monotonic", ignoreCase = true),
        )
        // Failure-branch invariants: oldState preserved.
        assertEquals(200L, host.sessions["s1"]?.localAppliedUpdatedAt)
        assertEquals("m_newer", host.sessions["s1"]?.localAppliedMessageId)
        assertTrue("dirty stays true on monotonic rejection", host.sessions["s1"]?.dirty == true)
    }

    @Test
    fun `§11_2 monotonicLocalAppliedGuard rejects regressing id at equal ts`() {
        // P1-4 variant: equal ts but smaller id (stale / out-of-order).
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 200L,
                    localAppliedMessageId = "m_zzz",  // larger id
                    dirty = true,
                ),
            ),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            messages = listOf(msg("m_aaa", updated = 200L)),  // smaller id, same ts
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "m_aaa",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertTrue(
            "P1-4: equal ts + smaller id must be MergeRejected",
            result is SlimAuthoritativeCommitResult.MergeRejected,
        )
        assertEquals(200L, host.sessions["s1"]?.localAppliedUpdatedAt)
        assertEquals("m_zzz", host.sessions["s1"]?.localAppliedMessageId)
    }

    @Test
    fun `§11_2 monotonicLocalAppliedGuard accepts idempotent re commit`() {
        // P1-4 variant: equal tuple (idempotent re-commit) is ALLOWED —
        // the watermark is unchanged; the visible content / dirty clear
        // may still be desirable.
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 200L,
                    localAppliedMessageId = "m_same",
                    dirty = true,
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("m_old", updated = 100L))),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            messages = listOf(msg("m_same", updated = 200L)),
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "m_same",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "P1-4: idempotent re-commit (equal tuple) is Committed",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
    }

    @Test
    fun `§11_2 monotonicLocalAppliedGuard accepts strictly advancing tuple`() {
        // P1-4 variant: strictly-greater tuple is allowed (normal forward
        // progress). This is the happy path — pinned to ensure the
        // monotonic guard doesn't accidentally reject legitimate advances.
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "m_old",
                    dirty = true,
                ),
            ),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = candidate(
            sessionId = "s1",
            messages = listOf(msg("m_new", updated = 300L)),
            localAppliedUpdatedAt = 300L,
            localAppliedMessageId = "m_new",
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "P1-4: strictly-advancing tuple is Committed",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        assertEquals(300L, host.sessions["s1"]?.localAppliedUpdatedAt)
        assertEquals("m_new", host.sessions["s1"]?.localAppliedMessageId)
        assertFalse("dirty cleared on Committed", host.sessions["s1"]?.dirty == true)
    }

    // ── rev-ogpt P1-1 (six rounds): in-lock same-tuple content conflict ──

    /**
     * rev-ogpt P1-1 (six rounds): when a concurrent candidate commits
     * DIFFERENT parts at the SAME watermark tuple BEFORE this candidate
     * enters the lock, the in-lock same-tuple content comparison MUST
     * detect the divergence and:
     *
     *  - PRESERVE currentMessages (lock-latest, written by the racing
     *    committer) — candidate's parts must NOT silently overwrite.
     *  - Force effectiveHasConflict=true → dirty=true (via
     *    replaceLocalAppliedAndClearDirtyLocked(hasConflict=true)).
     *  - Return Committed (the watermark is the same, no regression).
     *
     * Setup: pre-snapshot has localApplied=(100, m_old). The race
     * simulator advances localApplied in-lock to (200, m_a) — the SAME
     * tuple the candidate carries — AND replaces visibleMessages with
     * parts_B (the racing candidate's parts). The candidate carries
     * parts_A at (200, m_a). The monotonic guard passes (equal tuple is
     * NOT a regression); the in-lock content compare catches the
     * divergence.
     *
     * NOTE: debug assertions are disabled for this test because the race
     * simulator mutates state inside the lock — the failure-branch
     * invariant would (correctly) flag the visible-content change as a
     * state mutation on a non-commit branch (the mutation is from the
     * racing simulator, not from the candidate under test).
     */
    @Test
    fun `§11_2 rev-ogpt P1-1 equal tuple different parts concurrent candidate preserves current authoritative and keeps dirty`() {
        val priorAssertionsFlag = SlimAuthoritativeCommitDebugAssertions.enabled
        SlimAuthoritativeCommitDebugAssertions.enabled = false
        try {
            // parts_A vs parts_B: same message id + ts but different parts
            // content (the same-tuple-different-parts divergence).
            val partsA = msgWithParts("m_a", updated = 200L, parts = listOf("part_a_v1"))
            val partsB = msgWithParts("m_a", updated = 200L, parts = listOf("part_a_v2"))
            val host = RecordingHost(
                sessions = mapOf(
                    "s1" to sessionState(
                        // Pre-snapshot watermark OLDER than candidate — the
                        // outer fast-path check passes.
                        localAppliedUpdatedAt = 100L,
                        localAppliedMessageId = "m_old",
                        dirty = true,
                        // remote <= localApplied post-commit (the racing
                        // candidate advanced localApplied to 200 == remote)
                        // ⇒ needsReconcile=false; dirty stays true ONLY
                        // because of the conflict path.
                        remoteUpdatedAt = 200L,
                        remoteMessageId = "m_a",
                    ),
                ),
                visibleMessages = mapOf("s1" to listOf(msg("m_old", updated = 100L))),
                // Race simulator: in-lock, advance watermark to (200, m_a)
                // AND replace visibleMessages with parts_B (the concurrent
                // candidate landed different parts at this watermark).
                injectConcurrentCommitInLock = true,
                concurrentCommitTuple = 200L to "m_a",
                concurrentCommitMessages = listOf(partsB),
            )
            val committer = InternalSlimAuthoritativeCommitter(host)
            val candidate = SlimAuthoritativeCandidate(
                sessionId = "s1",
                token = TOKEN,
                messages = listOf(partsA),
                localAppliedUpdatedAt = 200L,
                localAppliedMessageId = "m_a",
                // NOTE: hasConflict=false here on purpose — the candidate's
                // own merge (against the OLDER snapshot) saw no divergence.
                // The conflict is detected IN-LOCK via the content compare,
                // not from the candidate's merge signal. This isolates the
                // new P1-1 path from the prior hasConflict-based fix.
                hasConflict = false,
            )

            val result = runBlocking { committer.commitAuthoritative(candidate) }

            assertEquals(
                "P1-1: same-tuple content conflict MUST surface as Committed (no watermark regression)",
                SlimAuthoritativeCommitResult.Committed,
                result,
            )
            assertEquals(
                "P1-1: visible content MUST be PRESERVED as the lock-latest (parts_B) — candidate's parts_A MUST NOT silently overwrite",
                listOf(partsB),
                host.visibleMessages["s1"],
            )
            assertTrue(
                "P1-1: dirty MUST stay true (concurrent content conflict forces dirty via hasConflict=true path); got dirty=${host.sessions["s1"]?.dirty}",
                host.sessions["s1"]?.dirty == true,
            )
            // Exactly one commitIfCurrent — the dirty decision was ATOMIC
            // with the commit (no separate forceSlimDirty critical section).
            assertEquals(
                "P1-2: exactly one commitIfCurrent invocation",
                1,
                host.commitIfCurrentInvocations.size,
            )
            // The replaceLocalAppliedAndClearDirty call carried the
            // EFFECTIVE hasConflict=true (not the candidate's original false).
            val mutation = host.replaceLocalAppliedAndClearDirtyCalls.single()
            assertTrue(
                "P1-1: effectiveHasConflict=true was threaded into the host call (got hasConflict=${mutation.hasConflict})",
                mutation.hasConflict,
            )
            // The watermark reflects the candidate's tuple (the equal tuple
            // is written verbatim — no regression, no advance).
            assertEquals(
                "P1-1: localAppliedUpdatedAt written as the candidate's tuple",
                200L,
                host.sessions["s1"]?.localAppliedUpdatedAt,
            )
            assertEquals(
                "P1-1: localAppliedMessageId written as the candidate's tuple",
                "m_a",
                host.sessions["s1"]?.localAppliedMessageId,
            )
        } finally {
            SlimAuthoritativeCommitDebugAssertions.enabled = priorAssertionsFlag
        }
    }

    /**
     * rev-ogpt P1-1 (six rounds) — idempotent re-commit at the same tuple
     * with the SAME content MUST commit normally (no conflict triggered).
     * The in-lock content comparison sees currentMessages == candidate.messages
     * and falls through to the normal path; dirty is decided by the
     * candidate's hasConflict (false here) + needsReconcile (remote<=localApplied
     * here ⇒ false). Pins that the new content-conflict guard does NOT
     * false-positive on legitimate idempotent re-commits.
     */
    @Test
    fun `§11_2 rev-ogpt P1-1 equal tuple same content idempotent commit`() {
        val sharedParts = msgWithParts("m_same", updated = 200L, parts = listOf("part_same"))
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    // Same tuple as the candidate — equal-tuple path.
                    localAppliedUpdatedAt = 200L,
                    localAppliedMessageId = "m_same",
                    dirty = true,
                    // remote <= localApplied post-commit ⇒ needsReconcile=false.
                    remoteUpdatedAt = 150L,
                    remoteMessageId = "m_remote",
                ),
            ),
            // visibleMessages already equals the candidate's content — the
            // in-lock content compare sees equality ⇒ idempotent re-commit.
            visibleMessages = mapOf("s1" to listOf(sharedParts)),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "s1",
            token = TOKEN,
            messages = listOf(sharedParts),
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "m_same",
            hasConflict = false,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "P1-1: same-tuple same-content re-commit is Committed",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        assertEquals(
            "P1-1: visible content equals the candidate's content (idempotent)",
            listOf(sharedParts),
            host.visibleMessages["s1"],
        )
        assertFalse(
            "P1-1: same-tuple same-content commit clears dirty when hasConflict=false and remote<=localApplied",
            host.sessions["s1"]?.dirty == true,
        )
        // The host call carried the candidate's hasConflict=false verbatim
        // (no in-lock conflict escalation).
        val mutation = host.replaceLocalAppliedAndClearDirtyCalls.single()
        assertFalse(
            "P1-1: hasConflict=false threaded into the host call on idempotent re-commit",
            mutation.hasConflict,
        )
    }

    /**
     * rev-ogpt P1-1 fix-14 — even on a STRICTLY-ADVANCING global tuple,
     * the in-lock per-ID merge MUST run (the equalTuple gate is removed).
     * When the candidate's content differs from current, the candidate's
     * messages do NOT overwrite current wholesale; the per-ID merge
     * decides per-message. In particular, IDs in current but MISSING from
     * the candidate are PRESERVED (missing ≠ deleted — the candidate may
     * be a partial drain that doesn't enumerate every prior ID).
     *
     * Setup: current = [m_old@100]; candidate = [m_new@300] (strictly
     * advancing global tuple 300 > 100). Under fix-12a/fix-13 the
     * strictly-advancing path skipped the merge and overwrote current
     * wholesale, DROPPING m_old@100. fix-14 routes through the merge:
     * m_old@100 is preserved (no incoming for that ID), m_new@300 is
     * added (no previous), no same-tuple-different-parts divergence.
     */
    @Test
    fun `§11_2 rev-ogpt P1-1 fix-14 strictly advancing tuple per-ID merges preserving current IDs missing from candidate`() {
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    // Older tuple — candidate advances the watermark.
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "m_old",
                    dirty = true,
                    // remote <= localApplied post-commit ⇒ needsReconcile=false.
                    remoteUpdatedAt = 150L,
                    remoteMessageId = "m_remote",
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("m_old", updated = 100L))),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val newMessages = listOf(msg("m_new", updated = 300L))
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "s1",
            token = TOKEN,
            messages = newMessages,
            localAppliedUpdatedAt = 300L,
            localAppliedMessageId = "m_new",
            hasConflict = false,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "fix-14: strictly-advancing tuple is Committed",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        assertEquals(
            "fix-14: visible content is the per-ID merge result — m_old@100 PRESERVED (missing ≠ deleted) + m_new@300 added",
            listOf(msg("m_old", updated = 100L), msg("m_new", updated = 300L)),
            host.visibleMessages["s1"],
        )
        assertEquals(
            "fix-14: localAppliedUpdatedAt advanced to candidate's tuple",
            300L,
            host.sessions["s1"]?.localAppliedUpdatedAt,
        )
        assertFalse(
            "fix-14: dirty cleared on strictly-advancing per-ID merge (no conflict, remote<=localApplied)",
            host.sessions["s1"]?.dirty == true,
        )
        val mutation = host.replaceLocalAppliedAndClearDirtyCalls.single()
        assertFalse(
            "fix-14: effectiveHasConflict=false on strictly-advancing per-ID merge",
            mutation.hasConflict,
        )
    }

    // ── rev-ogpt P1-1 fix-13: in-lock per-ID conflict-aware merge ────────

    /**
     * rev-ogpt P1-1 fix-13: at an EQUAL global max watermark, a candidate
     * that STRICTLY UPDATES a non-max message MUST enter the authoritative
     * set (not be silently dropped by an over-broad "preserve all current"
     * guard). The prior fix-12a whole-list compare rejected this case,
     * trapping the session in a permanent dirty loop.
     *
     * Setup: current authoritative = [m_old@100, m_max@300]; candidate =
     * [m_old@200, m_max@300]; global max watermark (300, m_max) is EQUAL
     * on both sides. Per-ID merge: m_old@200 strictly newer than m_old@100
     * ⇒ take incoming (legitimate update); m_max@300 equal ⇒ keep current.
     * No same-tuple-different-parts divergence ⇒ inLockConflict=false.
     */
    @Test
    fun `§11_2 rev-ogpt P1-1 fix-13 equal global max non-max message strictly newer enters authoritative`() {
        val mOldAt100 = msgWithParts("m_old", updated = 100L, parts = listOf("old_v1"))
        val mOldAt200 = msgWithParts("m_old", updated = 200L, parts = listOf("old_v2"))
        val mMaxAt300 = msgWithParts("m_max", updated = 300L, parts = listOf("max"))
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    // ALREADY at the same global max tuple as the candidate
                    // — equal-tuple path triggers in-lock per-ID merge.
                    localAppliedUpdatedAt = 300L,
                    localAppliedMessageId = "m_max",
                    dirty = true,
                    // remote <= localApplied post-commit ⇒ needsReconcile=false.
                    // dirty decision will be made purely by effectiveHasConflict.
                    remoteUpdatedAt = 300L,
                    remoteMessageId = "m_max",
                ),
            ),
            // current authoritative content: m_old@100 (stale) + m_max@300.
            visibleMessages = mapOf("s1" to listOf(mOldAt100, mMaxAt300)),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "s1",
            token = TOKEN,
            // candidate: m_old@200 (strictly newer) + m_max@300 (equal).
            messages = listOf(mOldAt200, mMaxAt300),
            localAppliedUpdatedAt = 300L,
            localAppliedMessageId = "m_max",
            hasConflict = false,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "fix-13: equal-tuple strictly-newer-non-max candidate MUST surface as Committed",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        assertEquals(
            "fix-13: visible content MUST reflect the per-ID merge — m_old@200 admitted, m_max@300 kept " +
                "(NOT the prior fix-12a 'preserve all current' behavior that would have left m_old@100 in place)",
            listOf(mOldAt200, mMaxAt300),
            host.visibleMessages["s1"],
        )
        val mutation = host.replaceLocalAppliedAndClearDirtyCalls.single()
        assertFalse(
            "fix-13: effectiveHasConflict MUST be false (no same-tuple-different-parts divergence at any ID)",
            mutation.hasConflict,
        )
        assertFalse(
            "fix-13: dirty cleared when effectiveHasConflict=false and remote<=localApplied post-commit",
            host.sessions["s1"]?.dirty == true,
        )
    }

    /**
     * rev-ogpt P1-1 fix-13: at an EQUAL global max watermark, a candidate
     * that ADDS a new OLDER ID (not previously in authoritative) MUST have
     * that ID admitted. The prior fix-12a whole-list compare dropped this
     * legitimate add along with the rest of the candidate.
     *
     * Setup: current = [m_max@300]; candidate = [m_max@300, m_new@200];
     * global max (300, m_max) EQUAL. Per-ID merge: m_max@300 equal ⇒ keep
     * current; m_new@200 has no previous ⇒ take incoming (legitimate add).
     */
    @Test
    fun `§11_2 rev-ogpt P1-1 fix-13 equal global max new older ID added to authoritative`() {
        val mMaxAt300 = msgWithParts("m_max", updated = 300L, parts = listOf("max"))
        val mNewAt200 = msgWithParts("m_new", updated = 200L, parts = listOf("new"))
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 300L,
                    localAppliedMessageId = "m_max",
                    dirty = true,
                    remoteUpdatedAt = 300L,
                    remoteMessageId = "m_max",
                ),
            ),
            // current authoritative content: only m_max@300.
            visibleMessages = mapOf("s1" to listOf(mMaxAt300)),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "s1",
            token = TOKEN,
            // candidate: m_max@300 (equal) + m_new@200 (new older ID).
            messages = listOf(mMaxAt300, mNewAt200),
            localAppliedUpdatedAt = 300L,
            localAppliedMessageId = "m_max",
            hasConflict = false,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "fix-13: equal-tuple new-older-ID candidate MUST surface as Committed",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        // The merge sorts by (created, updated, id) — m_new@200 sorts
        // before m_max@300 (earlier created/updated ts).
        assertEquals(
            "fix-13: m_new@200 MUST be admitted to authoritative (legitimate add of older ID at equal global max)",
            listOf(mNewAt200, mMaxAt300),
            host.visibleMessages["s1"],
        )
        val mutation = host.replaceLocalAppliedAndClearDirtyCalls.single()
        assertFalse(
            "fix-13: effectiveHasConflict=false (new ID add is not a same-tuple divergence)",
            mutation.hasConflict,
        )
        assertFalse(
            "fix-13: dirty cleared on legitimate add",
            host.sessions["s1"]?.dirty == true,
        )
    }

    /**
     * rev-ogpt P1-1 fix-13: at an EQUAL global max watermark, a candidate
     * carrying the SAME ID + SAME tuple + DIFFERENT parts at that ID MUST
     * be detected as a conflict — the per-ID merge keeps the current
     * (lock-latest) parts at that ID and forces effectiveHasConflict=true,
     * so dirty stays true for a follow-up reconcile. This is the §11.4
     * same-tuple-different-parts case that the in-lock compare was added
     * to catch in the first place.
     *
     * Setup: current = [m@300 parts_A]; candidate = [m@300 parts_B];
     * equal tuple (300, m). Per-ID merge: same ID, same tuple, parts differ
     * ⇒ keep previous (parts_A), set hasConflict=true.
     */
    @Test
    fun `§11_2 rev-ogpt P1-1 fix-13 equal global max same ID same tuple different parts conflict preserved`() {
        val partsA = msgWithParts("m", updated = 300L, parts = listOf("parts_a"))
        val partsB = msgWithParts("m", updated = 300L, parts = listOf("parts_b"))
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 300L,
                    localAppliedMessageId = "m",
                    dirty = true,
                    remoteUpdatedAt = 300L,
                    remoteMessageId = "m",
                ),
            ),
            // current authoritative content: m@300 parts_A.
            visibleMessages = mapOf("s1" to listOf(partsA)),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "s1",
            token = TOKEN,
            // candidate: m@300 parts_B (same ID/tuple, different parts).
            messages = listOf(partsB),
            localAppliedUpdatedAt = 300L,
            localAppliedMessageId = "m",
            hasConflict = false,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "fix-13: same-tuple-different-parts candidate MUST surface as Committed (no watermark regression)",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        assertEquals(
            "fix-13: at the conflicting ID, the lock-latest parts_A MUST be PRESERVED (parts_B MUST NOT silently overwrite)",
            listOf(partsA),
            host.visibleMessages["s1"],
        )
        assertTrue(
            "fix-13: dirty MUST stay true (in-lock per-ID conflict forces effectiveHasConflict=true)",
            host.sessions["s1"]?.dirty == true,
        )
        val mutation = host.replaceLocalAppliedAndClearDirtyCalls.single()
        assertTrue(
            "fix-13: effectiveHasConflict=true threaded into the host call",
            mutation.hasConflict,
        )
    }

    /**
     * rev-ogpt P1-1 fix-13: when BOTH the candidate's own merge set
     * `hasConflict=true` AND the in-lock per-ID merge detects a fresh
     * conflict, effectiveHasConflict MUST be the OR of the two. This
     * happens when the candidate already saw a divergence against its
     * outer snapshot AND a concurrent candidate landed ANOTHER divergence
     * at the same watermark before this candidate entered the lock.
     *
     * Setup: current = [m@300 parts_A]; candidate carries parts_B at the
     * same ID/tuple AND `hasConflict=true` (the outer merge flagged a
     * divergence against the pre-snapshot). In-lock merge detects the
     * parts_A vs parts_B conflict; effectiveHasConflict = true || true.
     */
    @Test
    fun `§11_2 rev-ogpt P1-1 fix-13 candidate hasConflict true and in-lock conflict both present ORs to true`() {
        val partsA = msgWithParts("m", updated = 300L, parts = listOf("parts_a"))
        val partsB = msgWithParts("m", updated = 300L, parts = listOf("parts_b"))
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 300L,
                    localAppliedMessageId = "m",
                    dirty = true,
                    remoteUpdatedAt = 300L,
                    remoteMessageId = "m",
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(partsA)),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "s1",
            token = TOKEN,
            messages = listOf(partsB),
            localAppliedUpdatedAt = 300L,
            localAppliedMessageId = "m",
            // The candidate's own outer merge already flagged a conflict.
            hasConflict = true,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "fix-13: dual-conflict candidate MUST surface as Committed",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        assertEquals(
            "fix-13: lock-latest parts_A preserved at the conflicting ID",
            listOf(partsA),
            host.visibleMessages["s1"],
        )
        assertTrue(
            "fix-13: dirty=true under dual conflict",
            host.sessions["s1"]?.dirty == true,
        )
        val mutation = host.replaceLocalAppliedAndClearDirtyCalls.single()
        assertTrue(
            "fix-13: effectiveHasConflict = candidate.hasConflict(true) || inLockConflict(true) = true",
            mutation.hasConflict,
        )
    }

    // ── rev-ogpt P1-1 fix-14: advancing global tuple + per-ID merge ──────

    /**
     * rev-ogpt P1-1 fix-14: the equalTuple gate is REMOVED. Even when
     * the candidate's global tuple is STRICTLY GREATER than current's,
     * the in-lock per-ID merge MUST run when content differs. This closes
     * the fix-13 regression where a strictly-advancing candidate skipped
     * the merge and silently overwrote a NEWER same-ID message that a
     * concurrent committer had just written.
     *
     * Race scenario this pins: A/B both drain from an OLD snapshot
     * (visible=[m_a@100], localApplied=(100, m_a)). B's candidate commits
     * first (visible=[m_a@250], localApplied=(250, m_a)). A's candidate
     * carries m_a@100 (STALE — A doesn't know about B's write) + m_b@300
     * (new global max). A's global tuple (300, m_b) > current (250, m_a),
     * so the monotonic guard admits — and the per-ID merge is what catches
     * the m_a@100 regression (older than current's m_a@250) and PRESERVES
     * the lock-latest m_a@250.
     *
     * Assertions:
     *  - Committed (no watermark regression; global tuple advanced).
     *  - visible contains m_a@250 (lock-latest, NOT m_a@100 from candidate).
     *  - visible contains m_b@300 (new ID from candidate, added).
     *  - effectiveHasConflict=false (m_a@100 is older, not same-tuple divergence;
     *    m_b@300 is a new ID; no same-tuple-different-parts anywhere).
     *  - dirty=false (no conflict + remote<=localApplied post-commit).
     */
    @Test
    fun `§11_2 rev-ogpt P1-1 fix-14 advancing global tuple candidate has older same-ID per-ID merge preserves newer current`() {
        val priorAssertionsFlag = SlimAuthoritativeCommitDebugAssertions.enabled
        SlimAuthoritativeCommitDebugAssertions.enabled = false
        try {
            // A's stale view of m_a (drained from the OLD snapshot).
            val mAAt100 = msgWithParts("m_a", updated = 100L, parts = listOf("a_v1"))
            // B's commit (NEWER m_a, written between A's drain and A's commit).
            val mAAt250 = msgWithParts("m_a", updated = 250L, parts = listOf("a_v2"))
            // A's new message (the global max that drives the candidate's tuple).
            val mBAt300 = msgWithParts("m_b", updated = 300L, parts = listOf("b_v1"))
            val host = RecordingHost(
                sessions = mapOf(
                    "s1" to sessionState(
                        // Pre-snapshot watermark — older than both B's write
                        // (250) and A's candidate (300), so the outer fast-path
                        // check passes and the cache write proceeds.
                        localAppliedUpdatedAt = 100L,
                        localAppliedMessageId = "m_a",
                        dirty = true,
                        // remote <= localApplied post-commit (300)
                        // ⇒ needsReconcile=false; dirty decided purely by
                        // effectiveHasConflict.
                        remoteUpdatedAt = 300L,
                        remoteMessageId = "m_b",
                    ),
                ),
                // Pre-snapshot visible (A's stale view).
                visibleMessages = mapOf("s1" to listOf(mAAt100)),
                // Race simulator: B's commit lands in-lock BEFORE A's
                // commit lambda runs. Advances localApplied to (250, m_a)
                // AND replaces visible with [m_a@250] (B's NEWER same-ID
                // write that A's candidate doesn't know about).
                injectConcurrentCommitInLock = true,
                concurrentCommitTuple = 250L to "m_a",
                concurrentCommitMessages = listOf(mAAt250),
            )
            val committer = InternalSlimAuthoritativeCommitter(host)
            val candidate = SlimAuthoritativeCandidate(
                sessionId = "s1",
                token = TOKEN,
                // A's candidate: m_a@100 (STALE) + m_b@300 (new global max).
                // Global tuple = (300, m_b), strictly > in-lock current (250, m_a).
                messages = listOf(mAAt100, mBAt300),
                localAppliedUpdatedAt = 300L,
                localAppliedMessageId = "m_b",
                hasConflict = false,
            )

            val result = runBlocking { committer.commitAuthoritative(candidate) }

            assertEquals(
                "fix-14: advancing global tuple + older same-ID candidate MUST surface as Committed",
                SlimAuthoritativeCommitResult.Committed,
                result,
            )
            assertEquals(
                "fix-14: visible content is the per-ID merge — m_a@250 (lock-latest NEWER) PRESERVED, " +
                    "m_b@300 (new ID from candidate) ADDED. m_a MUST NOT regress to candidate's m_a@100.",
                listOf(mAAt250, mBAt300),
                host.visibleMessages["s1"],
            )
            val mutation = host.replaceLocalAppliedAndClearDirtyCalls.single()
            assertFalse(
                "fix-14: effectiveHasConflict=false (m_a@100 is older, not same-tuple divergence)",
                mutation.hasConflict,
            )
            assertFalse(
                "fix-14: dirty cleared (no conflict, remote<=localApplied post-commit)",
                host.sessions["s1"]?.dirty == true,
            )
            assertEquals(
                "fix-14: localAppliedUpdatedAt written as candidate's tuple (300)",
                300L,
                host.sessions["s1"]?.localAppliedUpdatedAt,
            )
            assertEquals(
                "fix-14: localAppliedMessageId written as candidate's tuple (m_b)",
                "m_b",
                host.sessions["s1"]?.localAppliedMessageId,
            )
        } finally {
            SlimAuthoritativeCommitDebugAssertions.enabled = priorAssertionsFlag
        }
    }

    /**
     * rev-ogpt P1-1 fix-14: IDs in current but MISSING from the candidate
     * are PRESERVED (missing ≠ deleted — the candidate may legitimately be
     * a partial drain that doesn't enumerate every prior ID, or A's older
     * snapshot simply never knew about m_c). The per-ID merge keys by ID;
     * an ID present only on the `authoritative` (current) side is kept as-is.
     *
     * Race scenario: current after B's commit = [m_a@250, m_c@200] (B saw
     * both); A's candidate = [m_a@100, m_b@300] (A never knew about m_c,
     * A's m_a is stale). Per-ID merge: m_a@250 preserved (older incoming),
     * m_c@200 PRESERVED (no incoming for m_c), m_b@300 added.
     */
    @Test
    fun `§11_2 rev-ogpt P1-1 fix-14 advancing global tuple current has candidate-missing ID missing preserved`() {
        val priorAssertionsFlag = SlimAuthoritativeCommitDebugAssertions.enabled
        SlimAuthoritativeCommitDebugAssertions.enabled = false
        try {
            val mAAt100 = msgWithParts("m_a", updated = 100L, parts = listOf("a_v1"))
            val mAAt250 = msgWithParts("m_a", updated = 250L, parts = listOf("a_v2"))
            val mCAt200 = msgWithParts("m_c", updated = 200L, parts = listOf("c_v1"))
            val mBAt300 = msgWithParts("m_b", updated = 300L, parts = listOf("b_v1"))
            val host = RecordingHost(
                sessions = mapOf(
                    "s1" to sessionState(
                        localAppliedUpdatedAt = 100L,
                        localAppliedMessageId = "m_a",
                        dirty = true,
                        remoteUpdatedAt = 300L,
                        remoteMessageId = "m_b",
                    ),
                ),
                visibleMessages = mapOf("s1" to listOf(mAAt100)),
                // B's commit advances localApplied to (250, m_a) and writes
                // [m_a@250, m_c@200] (B knows about m_c, A does not).
                injectConcurrentCommitInLock = true,
                concurrentCommitTuple = 250L to "m_a",
                concurrentCommitMessages = listOf(mAAt250, mCAt200),
            )
            val committer = InternalSlimAuthoritativeCommitter(host)
            val candidate = SlimAuthoritativeCandidate(
                sessionId = "s1",
                token = TOKEN,
                // A's candidate: m_a@100 (stale) + m_b@300 (new global max).
                // NOTE: m_c is NOT in A's candidate — A's drain never saw it.
                messages = listOf(mAAt100, mBAt300),
                localAppliedUpdatedAt = 300L,
                localAppliedMessageId = "m_b",
                hasConflict = false,
            )

            val result = runBlocking { committer.commitAuthoritative(candidate) }

            assertEquals(
                "fix-14: candidate with missing ID MUST surface as Committed",
                SlimAuthoritativeCommitResult.Committed,
                result,
            )
            // Sort by (created, updated, id): m_c(200) < m_a(250) < m_b(300).
            assertEquals(
                "fix-14: per-ID merge — m_c@200 PRESERVED (missing ≠ deleted), " +
                    "m_a@250 PRESERVED (lock-latest newer), m_b@300 ADDED",
                listOf(mCAt200, mAAt250, mBAt300),
                host.visibleMessages["s1"],
            )
            val mutation = host.replaceLocalAppliedAndClearDirtyCalls.single()
            assertFalse(
                "fix-14: effectiveHasConflict=false (no same-tuple-different-parts at any ID)",
                mutation.hasConflict,
            )
            assertFalse(
                "fix-14: dirty cleared (no conflict, remote<=localApplied post-commit)",
                host.sessions["s1"]?.dirty == true,
            )
        } finally {
            SlimAuthoritativeCommitDebugAssertions.enabled = priorAssertionsFlag
        }
    }

    /**
     * rev-ogpt P1-1 fix-14: even on a strictly-advancing global tuple, a
     * SAME-ID/SAME-TUPLE/DIFFERENT-PARTS divergence at any ID MUST be
     * detected as a conflict. The per-ID merge keeps the lock-latest parts
     * at the conflicting ID and sets inLockConflict=true; effectiveHasConflict
     * forces dirty=true for a follow-up reconcile.
     *
     * Race scenario: B commits m_a@300 with parts_A. A's candidate carries
     * m_a@300 with parts_B (same tuple, different parts — A's drain saw a
     * different version) AND m_b@400 (new global max). Per-ID merge at m_a:
     * same tuple, parts differ → keep parts_A + hasConflict=true. m_b@400
     * added. effectiveHasConflict = false || true = true.
     */
    @Test
    fun `§11_2 rev-ogpt P1-1 fix-14 advancing global tuple same-ID same-tuple different parts conflict`() {
        val priorAssertionsFlag = SlimAuthoritativeCommitDebugAssertions.enabled
        SlimAuthoritativeCommitDebugAssertions.enabled = false
        try {
            // A's stale view of m_a at tuple (300, m_a) — but with parts_B.
            val mAAt300PartsB = msgWithParts("m_a", updated = 300L, parts = listOf("parts_b"))
            // B's commit at the SAME tuple (300, m_a) with parts_A.
            val mAAt300PartsA = msgWithParts("m_a", updated = 300L, parts = listOf("parts_a"))
            // A's new message (the global max that drives the candidate's tuple).
            val mBAt400 = msgWithParts("m_b", updated = 400L, parts = listOf("b_v1"))
            val host = RecordingHost(
                sessions = mapOf(
                    "s1" to sessionState(
                        // Pre-snapshot — older than B's (300, m_a) write.
                        localAppliedUpdatedAt = 100L,
                        localAppliedMessageId = "m_a_old",
                        dirty = true,
                        // remote <= localApplied post-commit (400).
                        remoteUpdatedAt = 400L,
                        remoteMessageId = "m_b",
                    ),
                ),
                visibleMessages = mapOf("s1" to listOf(msg("m_a_old", updated = 100L))),
                // Race simulator: B's commit lands in-lock at (300, m_a) with parts_A.
                injectConcurrentCommitInLock = true,
                concurrentCommitTuple = 300L to "m_a",
                concurrentCommitMessages = listOf(mAAt300PartsA),
            )
            val committer = InternalSlimAuthoritativeCommitter(host)
            val candidate = SlimAuthoritativeCandidate(
                sessionId = "s1",
                token = TOKEN,
                // A's candidate: m_a@300 parts_B (same tuple as B's, different parts)
                // + m_b@400 (new global max).
                messages = listOf(mAAt300PartsB, mBAt400),
                localAppliedUpdatedAt = 400L,
                localAppliedMessageId = "m_b",
                hasConflict = false,
            )

            val result = runBlocking { committer.commitAuthoritative(candidate) }

            assertEquals(
                "fix-14: advancing tuple + same-ID same-tuple different parts MUST surface as Committed",
                SlimAuthoritativeCommitResult.Committed,
                result,
            )
            // Sort by (created, updated, id): m_a(300) < m_b(400).
            assertEquals(
                "fix-14: at the conflicting ID m_a, lock-latest parts_A PRESERVED; m_b@400 ADDED",
                listOf(mAAt300PartsA, mBAt400),
                host.visibleMessages["s1"],
            )
            assertTrue(
                "fix-14: dirty MUST stay true (in-lock per-ID conflict forces effectiveHasConflict=true)",
                host.sessions["s1"]?.dirty == true,
            )
            val mutation = host.replaceLocalAppliedAndClearDirtyCalls.single()
            assertTrue(
                "fix-14: effectiveHasConflict=true (in-lock conflict OR candidate.hasConflict)",
                mutation.hasConflict,
            )
        } finally {
            SlimAuthoritativeCommitDebugAssertions.enabled = priorAssertionsFlag
        }
    }

    // ── rev-ogpt P1-1 / P1-2: hasConflict atomic dirty decision ──────────

    /**
     * rev-ogpt P1-1 + P1-2: candidate.hasConflict=true MUST keep dirty=true
     * after commit, ATOMICALLY (no intermediate dirty=false window). The
     * commit's critical section decides dirty from hasConflict + post-write
     * state in ONE atomic step; there is NO post-commit forceSlimDirty /
     * markDirty call (which would have a race window).
     *
     * This is the P1-1 hole: prior to the fix, the reconciler path called
     * `markDirty` (gated by needsReconcile), which was a NO-OP for same-
     * tuple conflicts because needsReconcile returns false when
     * remote <= localApplied. With hasConflict as a commit input, dirty
     * is forced true UNCONDITIONALLY inside the commit's critical section.
     *
     * Setup: remote == localApplied == 200 (same tuple, aligned watermark).
     * needsReconcile would return false. But hasConflict=true forces
     * dirty=true regardless.
     */
    @Test
    fun `§11_2 rev-ogpt P1-1 hasConflict true forces dirty true atomically on commit`() {
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "msg_old",
                    dirty = true,
                    // remote == localApplied post-commit ⇒ needsReconcile=false.
                    // hasConflict=true MUST still force dirty=true.
                    remoteUpdatedAt = 200L,
                    remoteMessageId = "msg_remote",
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("msg_old", updated = 100L))),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "s1",
            token = TOKEN,
            messages = listOf(msg("msg_new", updated = 200L)),
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "msg_new",
            hasConflict = true,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "hasConflict=true commit must still surface as Committed",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        assertTrue(
            "P1-1: hasConflict=true MUST force dirty=true after commit " +
                "(needsReconcile=false here but conflict forces dirty); got dirty=${host.sessions["s1"]?.dirty}",
            host.sessions["s1"]?.dirty == true,
        )
        // Exactly one commitIfCurrent invocation — the dirty decision was
        // ATOMIC with the commit (no separate forceSlimDirty critical section
        // — that's the P1-2 atomicity guarantee).
        assertEquals(
            "P1-2: exactly one commitIfCurrent invocation (no separate forceDirty critical section)",
            1,
            host.commitIfCurrentInvocations.size,
        )
        // The replaceLocalAppliedAndClearDirty call carried hasConflict=true.
        val mutation = host.replaceLocalAppliedAndClearDirtyCalls.single()
        assertTrue(
            "P1-1: hasConflict=true was threaded into the host call",
            mutation.hasConflict,
        )
    }

    /**
     * rev-ogpt P0-4 (non-regression): candidate.hasConflict=false + remote >
     * localApplied post-commit MUST still keep dirty=true (P0-4 TOCTOU
     * mitigation). The hasConflict flag is an ADDITIONAL input, not a
     * replacement for the existing remote>localApplied check.
     */
    @Test
    fun `§11_2 rev-ogpt P0-4 non-regression hasConflict false with remote ahead keeps dirty true`() {
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "msg_old",
                    dirty = true,
                    // remote > localApplied post-commit ⇒ needsReconcile=true.
                    remoteUpdatedAt = 300L,
                    remoteMessageId = "msg_remote_ahead",
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("msg_old", updated = 100L))),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "s1",
            token = TOKEN,
            messages = listOf(msg("msg_new", updated = 200L)),
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "msg_new",
            hasConflict = false,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "hasConflict=false commit with remote>localApplied must still surface as Committed",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        assertTrue(
            "P0-4: hasConflict=false BUT remote(300) > localApplied(200) MUST keep dirty=true",
            host.sessions["s1"]?.dirty == true,
        )
    }

    /**
     * rev-ogpt happy path: candidate.hasConflict=false + remote <= localApplied
     * ⇒ dirty=false (normal clear). Pins that the new hasConflict input does
     * not break the existing happy-path clear.
     */
    @Test
    fun `§11_2 rev-ogpt hasConflict false with remote aligned clears dirty`() {
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = 100L,
                    localAppliedMessageId = "msg_old",
                    dirty = true,
                    // remote <= localApplied post-commit ⇒ needsReconcile=false.
                    remoteUpdatedAt = 150L,
                    remoteMessageId = "msg_remote",
                ),
            ),
            visibleMessages = mapOf("s1" to listOf(msg("msg_old", updated = 100L))),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "s1",
            token = TOKEN,
            messages = listOf(msg("msg_new", updated = 200L)),
            localAppliedUpdatedAt = 200L,
            localAppliedMessageId = "msg_new",
            hasConflict = false,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(SlimAuthoritativeCommitResult.Committed, result)
        assertFalse(
            "hasConflict=false + remote<=localApplied MUST clear dirty",
            host.sessions["s1"]?.dirty == true,
        )
    }

    /**
     * rev-ogpt P1-1: candidate.hasConflict=true against an EMPTY watermark
     * (cold-start conflict) MUST still commit + keep dirty=true. The
     * hasConflict flag is authoritative for dirty regardless of watermark
     * state. (Edge case: a cold-start drain produced an aggregate that
     * conflicts at an existing tuple but the watermark was previously null.)
     */
    @Test
    fun `§11_2 rev-ogpt P1-1 hasConflict true on empty watermark commits and keeps dirty true`() {
        val host = RecordingHost(
            sessions = mapOf(
                "s1" to sessionState(
                    localAppliedUpdatedAt = null,
                    localAppliedMessageId = null,
                    dirty = true,
                    remoteUpdatedAt = null,
                    remoteMessageId = null,
                ),
            ),
            visibleMessages = mapOf("s1" to emptyList()),
        )
        val committer = InternalSlimAuthoritativeCommitter(host)
        // Eligible messages + hasConflict=true.
        val candidate = SlimAuthoritativeCandidate(
            sessionId = "s1",
            token = TOKEN,
            messages = listOf(msg("m1", updated = 100L)),
            localAppliedUpdatedAt = 100L,
            localAppliedMessageId = "m1",
            hasConflict = true,
        )

        val result = runBlocking { committer.commitAuthoritative(candidate) }

        assertEquals(
            "hasConflict=true against empty watermark MUST still commit",
            SlimAuthoritativeCommitResult.Committed,
            result,
        )
        assertTrue(
            "P1-1: hasConflict=true forces dirty=true even on empty watermark",
            host.sessions["s1"]?.dirty == true,
        )
        assertEquals(
            "localApplied watermark advanced normally",
            100L,
            host.sessions["s1"]?.localAppliedUpdatedAt,
        )
    }

    // ── §11.1 fix-9 P0-1: TOCTOU concurrency regression ───────────────────

    /**
     * §11.1 fix-9 P0-1: when a concurrent committer advances the watermark
     * between the outer snapshot and the in-lock check, the second
     * candidate MUST be rejected by the AUTHORITATIVE in-lock monotonic
     * check (not just the outer fast-path check). This test simulates the
     * race by having the host's commitIfCurrent block re-write the
     * session state to a higher watermark BEFORE running the commit
     * lambda — exactly what a real concurrent commit would do. The
     * candidate (200, m_a) < in-lock state (300, m_b) → MergeRejected.
     *
     * NOTE: debug assertions are temporarily disabled for this test
     * because the failure-branch invariant would (correctly) flag the
     * state-mutation by the race simulator — that mutation is from a
     * CONCURRENT committer (candidate B), not from the candidate under
     * test. The invariant is designed to catch the OUTER committer
     * mutating on a failure branch, which is not what's happening here.
     */
    @Test
    fun `§11_2 fix-9 P0-1 inLockMonotonicCheck rejects regressing candidate after concurrent commit`() {
        // Disable debug assertions for this test — the race simulator
        // mutates state inside the lock to emulate a concurrent commit,
        // which the failure-branch invariant would (correctly) flag as a
        // state change on a non-commit branch. We're testing the
        // committer's in-lock monotonic rejection, not the failure-branch
        // invariant.
        val priorAssertionsFlag = SlimAuthoritativeCommitDebugAssertions.enabled
        SlimAuthoritativeCommitDebugAssertions.enabled = false
        try {
            // Host simulates a concurrent commit by advancing the state
            // INSIDE commitIfCurrent's lambda, before the committer's
            // captureCurrentState re-read.
            val host = RecordingHost(
                sessions = mapOf(
                    "s1" to sessionState(
                        localAppliedUpdatedAt = 100L,
                        localAppliedMessageId = "m_seed",
                        dirty = true,
                    ),
                ),
                // Race simulator: when commitIfCurrent is called, advance the
                // session state to (300, m_b) BEFORE the commit lambda runs.
                // The committer's in-lock captureCurrentState will see (300, m_b);
                // the candidate (200, m_a) is strictly less → rejected.
                injectConcurrentCommitInLock = true,
                concurrentCommitTuple = 300L to "m_b",
            )
            val committer = InternalSlimAuthoritativeCommitter(host)
            val candidate = candidate(
                sessionId = "s1",
                messages = listOf(msg("m_a", updated = 200L)),
                localAppliedUpdatedAt = 200L,
                localAppliedMessageId = "m_a",
            )

            val result = runBlocking { committer.commitAuthoritative(candidate) }

            assertTrue(
                "P0-1: in-lock monotonic check MUST reject regressing candidate (got $result)",
                result is SlimAuthoritativeCommitResult.MergeRejected,
            )
            val reason = (result as SlimAuthoritativeCommitResult.MergeRejected).reason
            assertTrue(
                "P0-1: reason must mention in-lock concurrent advance (got: $reason)",
                reason.contains("in-lock current", ignoreCase = true),
            )
        } finally {
            SlimAuthoritativeCommitDebugAssertions.enabled = priorAssertionsFlag
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun sessionState(
        localAppliedUpdatedAt: Long? = null,
        localAppliedMessageId: String? = null,
        dirty: Boolean = false,
        remoteUpdatedAt: Long? = null,
        remoteMessageId: String? = null,
    ): SlimSessionState = SlimSessionState(
        sessionId = "s1",
        localAppliedUpdatedAt = localAppliedUpdatedAt,
        localAppliedMessageId = localAppliedMessageId,
        dirty = dirty,
        remoteUpdatedAt = remoteUpdatedAt,
        remoteMessageId = remoteMessageId,
    )

    private fun candidate(
        sessionId: String,
        messages: List<MessageWithParts>,
        localAppliedUpdatedAt: Long?,
        localAppliedMessageId: String?,
    ): SlimAuthoritativeCandidate = SlimAuthoritativeCandidate(
        sessionId = sessionId,
        token = TOKEN,
        messages = messages,
        localAppliedUpdatedAt = localAppliedUpdatedAt,
        localAppliedMessageId = localAppliedMessageId,
    )

    private fun msg(
        id: String,
        updated: Long? = null,
        created: Long? = null,
        role: String = "assistant",
    ): MessageWithParts = MessageWithParts(
        info = Message(
            id = id,
            role = role,
            time = if (updated != null || created != null) {
                Message.TimeInfo(created = created, updated = updated)
            } else null,
        ),
    )

    /**
     * §11.1 rev-ogpt P1-1 (six rounds): helper that builds a
     * [MessageWithParts] carrying the given [parts] strings as text
     * segments. Used by the same-tuple-different-parts tests to construct
     * candidate vs concurrent-committer messages that share id+ts but
     * diverge on parts content.
     */
    private fun msgWithParts(
        id: String,
        updated: Long?,
        parts: List<String>,
        role: String = "assistant",
    ): MessageWithParts {
        val messageParts = parts.mapIndexed { idx, text ->
            cn.vectory.ocdroid.data.model.Part(
                id = "${id}_part_$idx",
                type = "text",
                text = text,
            )
        }
        return MessageWithParts(
            info = Message(
                id = id,
                role = role,
                time = Message.TimeInfo(created = updated, updated = updated),
            ),
            parts = messageParts,
        )
    }

    /**
     * Shared token instance for tests. The committer only forwards it to
     * the host (it never inspects the token's internals), so a single
     * sentinel instance suffices.
     */
    private val TOKEN: OpenCodeRepository.SlimCommitToken =
        OpenCodeRepository.SlimCommitToken(
            marker = Any(),
            issuedReady = true,
        )

    /**
     * Sentinel throwable for cache-write-failure simulation. A dedicated
     * class (not java.io.IOException directly) so tests can assert
     * referential identity of the cause via [assertSame].
     */
    private class IOExceptionSim(message: String) : java.io.IOException(message) {
        // Override equals to make this work with assertSame in tests;
        // data-class-style equality would compare message contents, but
        // we want identity comparison to pin that the SAME exception
        // instance propagates. The default referential equality is fine.
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    // ── recording host fake ──────────────────────────────────────────────

    /**
     * In-memory [SlimAuthoritativeCommitHost] fake. Records every call so
     * tests can assert order and arguments; programmable hooks let each
     * test simulate staleness, cache failure, and critical-section races.
     *
     * NOT thread-safe — the committer is sequential within one
     * `commitAuthoritative` invocation, so single-threaded test code is
     * safe.
     */
    private class RecordingHost(
        sessions: Map<String, SlimSessionState> = emptyMap(),
        visibleMessages: Map<String, List<MessageWithParts>> = emptyMap(),
        private val requireTokenThrows: Boolean = false,
        private val writeDiagnosticCacheThrows: Throwable? = null,
        private val commitIfCurrentReturns: Boolean = true,
        private val mutateReceivedMessageList: Boolean = false,
        /**
         * §11.1 fix-9 P0-1: race simulator — when `true`, the host's
         * [commitIfCurrent] ADVANCES the session's localApplied* tuple to
         * [concurrentCommitTuple] BEFORE running the commit lambda. This
         * emulates a concurrent committer that landed its write between
         * the outer pre-check and the in-lock captureCurrentState re-read.
         *
         * §11.1 rev-ogpt P1-1 (six rounds): when [concurrentCommitMessages]
         * is non-null, the race simulator ALSO replaces the session's
         * visibleMessages with that list in-lock. This emulates a
         * concurrent candidate that landed the SAME watermark tuple with
         * DIFFERENT parts — the scenario the in-lock same-tuple content
         * comparison must detect (preserve current, force dirty=true).
         */
        private val injectConcurrentCommitInLock: Boolean = false,
        private val concurrentCommitTuple: Pair<Long, String> = 0L to "",
        private val concurrentCommitMessages: List<MessageWithParts>? = null,
    ) : SlimAuthoritativeCommitHost {

        val sessions: MutableMap<String, SlimSessionState> = sessions.toMutableMap()
        val visibleMessages: MutableMap<String, List<MessageWithParts>> = visibleMessages.toMutableMap()

        val writeDiagnosticCacheCalls = mutableListOf<WriteCacheCall>()
        val commitIfCurrentInvocations = mutableListOf<CommitIfCurrentCall>()
        val replaceVisibleAndAuthoritativeCalls = mutableListOf<ReplaceVisibleCall>()
        val replaceLocalAppliedAndClearDirtyCalls = mutableListOf<ReplaceLocalAppliedCall>()

        data class WriteCacheCall(val sessionId: String, val messages: List<MessageWithParts>)
        data class CommitIfCurrentCall(
            val token: OpenCodeRepository.SlimCommitToken,
            val committed: Boolean,
        )

        data class ReplaceVisibleCall(
            val sessionId: String,
            val messages: List<MessageWithParts>,
        )

        data class ReplaceLocalAppliedCall(
            val sessionId: String,
            val localAppliedUpdatedAt: Long?,
            val localAppliedMessageId: String?,
            val hasConflict: Boolean,
        )

        override fun requireToken(token: OpenCodeRepository.SlimCommitToken) {
            if (requireTokenThrows) {
                throw OpenCodeRepository.StaleSlimCommitException()
            }
        }

        override fun captureCurrentState(sessionId: String): SlimSessionState? =
            sessions[sessionId]

        override fun captureCurrentVisibleMessages(sessionId: String): List<MessageWithParts> =
            visibleMessages[sessionId] ?: emptyList()

        override fun writeDiagnosticCache(sessionId: String, messages: List<MessageWithParts>) {
            writeDiagnosticCacheCalls += WriteCacheCall(sessionId, messages)
            writeDiagnosticCacheThrows?.let { throw it }
        }

        override fun commitIfCurrent(
            token: OpenCodeRepository.SlimCommitToken,
            commit: () -> Unit,
        ): Boolean {
            val committed = commitIfCurrentReturns
            commitIfCurrentInvocations += CommitIfCurrentCall(token, committed)
            if (committed) {
                // §11.1 fix-9 P0-1 race simulator: a concurrent committer
                // lands its write BEFORE this commit lambda runs. The
                // committer's in-lock captureCurrentState sees the advanced
                // state and runs authoritativeInLockCheck, which refuses
                // the regression.
                if (injectConcurrentCommitInLock) {
                    val sid = commitIfCurrentInvocations.last().token.let { token }
                    // Advance ANY session that exists; tests use "s1".
                    sessions.keys.toList().forEach { sid2 ->
                        val prev = sessions[sid2] ?: return@forEach
                        sessions[sid2] = prev.copy(
                            localAppliedUpdatedAt = concurrentCommitTuple.first,
                            localAppliedMessageId = concurrentCommitTuple.second,
                        )
                    }
                    // §11.1 rev-ogpt P1-1: also emulate the concurrent
                    // candidate's visible-content write (different parts at
                    // the same watermark). Tests that pin the same-tuple
                    // content-conflict path set [concurrentCommitMessages]
                    // to the parts the racing candidate would have written.
                    concurrentCommitMessages?.let { newMessages ->
                        sessions.keys.toList().forEach { sid2 ->
                            visibleMessages[sid2] = ArrayList(newMessages)
                        }
                    }
                }
                commit()
            }
            return committed
        }

        override fun replaceVisibleAndAuthoritative(
            sessionId: String,
            messages: List<MessageWithParts>,
        ) {
            // Store what we received. Optionally mutate the received list
            // to verify the committer's defensive copy.
            val stored = ArrayList(messages)
            replaceVisibleAndAuthoritativeCalls += ReplaceVisibleCall(sessionId, stored)
            visibleMessages[sessionId] = ArrayList(stored)
            if (mutateReceivedMessageList) {
                // The committer should have passed a copy; mutating it
                // must not affect the candidate's original list. The
                // interface types the param as a non-mutable List, so
                // cast — the committer's defensive copy is an ArrayList.
                @Suppress("UNCHECKED_CAST")
                (messages as MutableCollection<MessageWithParts>).clear()
            }
        }

        override fun replaceLocalAppliedAndClearDirty(
            sessionId: String,
            localAppliedUpdatedAt: Long?,
            localAppliedMessageId: String?,
            hasConflict: Boolean,
        ) {
            replaceLocalAppliedAndClearDirtyCalls += ReplaceLocalAppliedCall(
                sessionId,
                localAppliedUpdatedAt,
                localAppliedMessageId,
                hasConflict,
            )
            val prev = sessions[sessionId] ?: SlimSessionState(sessionId)
            // CRITICAL: must NOT touch remote* (invariant #1).
            // §11.1 fix-9 P0-4 + rev-ogpt P1-1/P1-2: mirror the production
            // logic in SlimSseStateMachine.replaceLocalAppliedAndClearDirtyLocked —
            // decide dirty ATOMICALLY from hasConflict + post-write state via
            // needsReconcile. hasConflict=true forces dirty=true unconditionally;
            // otherwise needsReconcile(remote > localApplied post-write) decides.
            val candidate = prev.copy(
                localAppliedUpdatedAt = localAppliedUpdatedAt,
                localAppliedMessageId = localAppliedMessageId,
                dirty = false,
            )
            val next = when {
                hasConflict -> candidate.copy(dirty = true)
                needsReconcile(candidate) -> candidate.copy(dirty = true)
                else -> candidate
            }
            sessions[sessionId] = next
        }
    }

}
