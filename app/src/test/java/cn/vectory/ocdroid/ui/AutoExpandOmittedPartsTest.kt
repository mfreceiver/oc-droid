package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.ExpandOutcome
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.chat.PartExpandState
import cn.vectory.ocdroid.ui.chat.PartKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §defect-B-2B (Defect B part 2B) — unit tests for
 * [launchAutoExpandOmittedParts]: the on-load auto-expand of the most-recent
 * messages' omitted tool output, bounded + streaming-guarded + single-flight.
 *
 * This is the G6 root-cause fix (an expand in-flight while SSE rewrites a part
 * causes a concurrent-overwrite orphan): the streaming guard suppresses
 * auto-expand while any part is actively streaming.
 *
 * # Coverage (auto-expand trigger's OWN behaviour only — the existing
 * ExpandedPartsReconcile / OpenCodeRepositoryExpandBudget / SlimapiMessageMerge
 * suites already cover thin_placeholder whole-replace + real partId non-empty
 * toolOutput, so those are NOT re-asserted here):
 *
 *  1. **Streaming guard (G6 fix)** — `streamingPartTexts` non-empty → no expand.
 *  2. **Recent-K budget + windowing** — >K messages with >20 eligible ids
 *     total → repo invoked ONCE, only for the most-recent-K messages' ids.
 *  3. **Session-switch discard** — currentSessionId flipped before the launch
 *     resumes → CAS no-ops, no repo call, no state commit.
 *  4. **Idle-filter** — already-`Loaded` parts are excluded from the expand
 *     call AND survive reconcile as Loaded.
 *  5. **Failure path** — repo throw → keys marked `Failed(code=null)`, skeleton
 *     NOT removed, no exception escapes.
 *
 * Drives [SharedStateStore] directly (lag-free DerivedStateFlow) + mockk the
 * repository (the usecase's only collaborator). `runTest`'s [kotlinx.coroutines.test.TestScope]
 * is passed as the coroutine scope; `StandardTestDispatcher` (runTest default)
 * enqueues the launched body so synchronous pre-`advanceUntilIdle` mutations
 * (session flip) are observable inside the coroutine — the same race the CAS
 * guards defend against in production.
 */
class AutoExpandOmittedPartsTest {

    // ── fixtures ──────────────────────────────────────────────────────────

    /** Skeleton part eligible for G6 expand (`hasFull && omitted && messageId`). */
    private fun skeletonPart(partId: String, msgId: String) = Part(
        id = partId,
        messageId = msgId,
        type = "text",
        text = "skeleton",
        hasFull = true,
        omitted = listOf("tool"),
    )

    /** Resolved (full) part returned by the repo — no skeleton markers. */
    private fun fullPart(partId: String, msgId: String) = Part(
        id = partId,
        messageId = msgId,
        type = "text",
        text = "FULL TEXT",
    )

    private fun msg(id: String) = Message(id = id, role = "assistant")

    private fun key(msgId: String, partId: String) = PartKey(messageId = msgId, partId = partId)

    // ── 1. Streaming guard (the G6 root-cause fix) ────────────────────────

    @Test
    fun `streaming overlay non-empty suppresses auto-expand`() = runTest {
        val store = SharedStateStore()
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(msg("m1")),
                partsByMessage = mapOf("m1" to listOf(skeletonPart("p1", "m1"))),
                partExpandStates = mapOf(key("m1", "p1") to PartExpandState.Idle),
                // Active token stream → must NOT auto-expand.
                streamingPartTexts = mapOf("p1" to "streaming…"),
            )
        }

        launchAutoExpandOmittedParts(this, repo, store, "s1", { "fp" })
        advanceUntilIdle()

        // No repo call (the streaming guard returned before the CAS / network).
        coVerify(exactly = 0) { repo.expandMessagesFullBatch(any(), any()) }
        // No Loading commit — part stays Idle (the affordance is untouched).
        assertEquals(
            PartExpandState.Idle,
            store.chatFlow.value.partExpandStates[key("m1", "p1")],
        )
    }

    @Test
    fun `active token-stream owner suppresses auto-expand`() = runTest {
        val store = SharedStateStore()
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(msg("m1")),
                partsByMessage = mapOf("m1" to listOf(skeletonPart("p1", "m1"))),
                partExpandStates = mapOf(key("m1", "p1") to PartExpandState.Idle),
                // streamingPartTexts empty, but a STREAMING token owner is live.
                streamingPartTexts = emptyMap(),
                streamOwned = mapOf("p1" to StreamOwnedState.STREAMING),
            )
        }

        launchAutoExpandOmittedParts(this, repo, store, "s1", { "fp" })
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.expandMessagesFullBatch(any(), any()) }
        assertEquals(
            PartExpandState.Idle,
            store.chatFlow.value.partExpandStates[key("m1", "p1")],
        )
    }

    // ── 1b. busy/retry tool-turn guard (review hardening) ────────────────

    @Test
    fun `busy tool turn suppresses auto-expand even with no text overlay`() = runTest {
        val store = SharedStateStore()
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(msg("m1")),
                partsByMessage = mapOf("m1" to listOf(skeletonPart("p1", "m1"))),
                partExpandStates = mapOf(key("m1", "p1") to PartExpandState.Idle),
                // No text overlay AND no token-stream owner — the streaming-only
                // guard would PASS here. But the session is BUSY (a tool turn:
                // SSE rewrites tool parts via message.part.updated), which the
                // broadened guard must catch.
                streamingPartTexts = emptyMap(),
                streamOwned = emptyMap(),
            )
        }
        store.mutateSessionList {
            it.withProjection(mapOf("s1" to SessionStatus(type = "busy")))
        }

        launchAutoExpandOmittedParts(this, repo, store, "s1", { "fp" })
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.expandMessagesFullBatch(any(), any()) }
        assertEquals(
            PartExpandState.Idle,
            store.chatFlow.value.partExpandStates[key("m1", "p1")],
        )
    }

    @Test
    fun `session goes busy during in-flight expand reverts keys to Idle and discards content`() = runTest {
        val store = SharedStateStore()
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        // Gate the suspend repo call so the launch commits Loading then SUSPENDS,
        // giving a deterministic window to flip the session busy mid-flight
        // (the G6 overlap window: expand network in-flight while a tool turn
        // starts and SSE begins rewriting tool parts).
        val gate = CompletableDeferred<ExpandOutcome>()
        coEvery { repo.expandMessagesFullBatch(any(), any()) } coAnswers { gate.await() }
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(msg("m1")),
                partsByMessage = mapOf("m1" to listOf(skeletonPart("p1", "m1"))),
                partExpandStates = mapOf(key("m1", "p1") to PartExpandState.Idle),
            )
        }

        launchAutoExpandOmittedParts(this, repo, store, "s1", { "fp" })
        // Run up to the gate: Loading is committed, the expand is suspended.
        advanceUntilIdle()

        // The session goes BUSY mid-flight (the entry guard passed because it
        // was idle at load time; the tool turn started during the network call).
        store.mutateSessionList {
            it.withProjection(mapOf("s1" to SessionStatus(type = "busy")))
        }
        gate.complete(
            ExpandOutcome.Ok(
                items = listOf(MessageWithParts(msg("m1"), listOf(fullPart("p1", "m1")))),
                failures = emptyList(),
                usedBatch = true,
            ),
        )
        advanceUntilIdle()

        // The fetched content was DISCARDED (the commit re-check saw busy) and
        // the key reverted Idle (no stuck Loading spinner) — the next idle load
        // retries. This is the G6 fix: no expand commit while actively writing.
        assertEquals(
            PartExpandState.Idle,
            store.chatFlow.value.partExpandStates[key("m1", "p1")],
        )
        assertEquals(
            "skeleton",
            store.chatFlow.value.partsByMessage["m1"]?.firstOrNull()?.text,
        )
    }

    // ── 2. Recent-K budget + windowing ───────────────────────────────────

    @Test
    fun `recent-K window bounds the expand to the most recent messages`() = runTest {
        val store = SharedStateStore()
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        // 25 messages (oldest-first), each with ONE eligible Idle skeleton part.
        // recentMessageBudget=15 → window = m11..m25 (the last 15).
        // >20 eligible message-ids total (25) would overflow the engine's 20-cap
        // WITHOUT our windowing; WITH it, only the recent-15 ids are requested.
        val total = 25
        val budget = 15
        val ids = (1..total).map { "m$it" }
        val messages = ids.map { msg(it) }
        val parts = ids.associateWith { id -> listOf(skeletonPart("p_$id", id)) }
        val states = ids.associate { id -> key(id, "p_$id") to PartExpandState.Idle }
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = messages,
                partsByMessage = parts,
                partExpandStates = states,
            )
        }

        // Capture the ids collection handed to the repo. Return the resolved
        // full messages for the recent window so reconcile marks Loaded (no
        // spurious Failed noise) — the assertion is about WHICH ids were sent.
        val expectedWindow = ids.takeLast(budget) // m11..m25
        val fullItems = expectedWindow.map { id ->
            MessageWithParts(msg(id), listOf(fullPart("p_$id", id)))
        }
        val capturedIds = mutableListOf<Set<String>>()
        coEvery {
            repo.expandMessagesFullBatch(eq("s1"), capture(capturedIds))
        } returns ExpandOutcome.Ok(items = fullItems, failures = emptyList(), usedBatch = true)

        launchAutoExpandOmittedParts(this, repo, store, "s1", { "fp" }, recentMessageBudget = budget)
        advanceUntilIdle()

        // Exactly ONE batch invocation (single-flight per load).
        assertEquals("repo invoked once", 1, capturedIds.size)
        val requested = capturedIds.single()
        // Bounded to the recent window, not the full 25.
        assertEquals("only recent-$budget ids requested", budget, requested.size)
        // Newest end of the window is present.
        assertTrue("newest message in window", "m25" in requested)
        // Oldest end of the window is present.
        assertTrue("oldest of window present", "m11" in requested)
        // Just-outside-window message is NOT requested (proves windowing, not
        // the engine's 20-cap, bounded the request — m10..m1 absent).
        assertFalse("just-outside-window excluded", "m10" in requested)
        assertFalse("oldest message excluded", "m1" in requested)

        // The window keys were resolved to Loaded; an out-of-window Idle key
        // was NOT touched (still Idle — left for a future load / manual tap).
        assertEquals(
            PartExpandState.Loaded,
            store.chatFlow.value.partExpandStates[key("m25", "p_m25")],
        )
        assertEquals(
            PartExpandState.Idle,
            store.chatFlow.value.partExpandStates[key("m1", "p_m1")],
        )
    }

    // ── 3. Session-switch discard ────────────────────────────────────────

    @Test
    fun `session mismatch at launch no-ops the auto-expand`() = runTest {
        val store = SharedStateStore()
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.expandMessagesFullBatch(any(), any()) } returns ExpandOutcome.Ok(
            items = emptyList(), failures = emptyList(), usedBatch = true,
        )
        // The session is ALREADY "s2" — the auto-expand is requested for the
        // stale "s1" (a load response raced a session switch and landed after
        // the user navigated away). The session guard at the single-read must
        // bail BEFORE any CAS / network.
        store.mutateChat {
            it.copy(
                currentSessionId = "s2",
                messages = listOf(msg("m1")),
                partsByMessage = mapOf("m1" to listOf(skeletonPart("p1", "m1"))),
                partExpandStates = mapOf(key("m1", "p1") to PartExpandState.Idle),
            )
        }

        launchAutoExpandOmittedParts(this, repo, store, "s1", { "fp" })
        advanceUntilIdle()

        // No repo call for the stale session.
        coVerify(exactly = 0) { repo.expandMessagesFullBatch(any(), any()) }
        // The seeded Idle key was NOT promoted to Loading / Failed — the
        // session guard returned before the Loading CAS touched it.
        assertEquals(
            "no Loading / Failed committed for stale session",
            PartExpandState.Idle,
            store.chatFlow.value.partExpandStates[key("m1", "p1")],
        )
    }

    @Test
    fun `session switched mid-flight discards the reconcile commit`() = runTest {
        val store = SharedStateStore()
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        // Gate the suspend repo call so the launch runs up to the expand,
        // commits Loading, then SUSPENDS — giving a deterministic window to
        // flip the session before the result is reconciled (mirrors the G6
        // orphan race: an expand in-flight while the session switches).
        val gate = CompletableDeferred<ExpandOutcome>()
        coEvery { repo.expandMessagesFullBatch(any(), any()) } coAnswers { gate.await() }
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(msg("m1")),
                partsByMessage = mapOf("m1" to listOf(skeletonPart("p1", "m1"))),
                partExpandStates = mapOf(key("m1", "p1") to PartExpandState.Idle),
            )
        }

        launchAutoExpandOmittedParts(this, repo, store, "s1", { "fp" })
        // Run up to the gate: Loading is committed, the expand is suspended.
        advanceUntilIdle()

        // Flip the session WHILE the expand is in flight.
        store.mutateChat { it.copy(currentSessionId = "s2") }
        gate.complete(
            ExpandOutcome.Ok(
                items = listOf(MessageWithParts(msg("m1"), listOf(fullPart("p1", "m1")))),
                failures = emptyList(),
                usedBatch = true,
            ),
        )
        advanceUntilIdle()

        // The repo WAS invoked (Loading was committed before the suspend)…
        coVerify(exactly = 1) { repo.expandMessagesFullBatch(any(), any()) }
        // …but the reconcile CAS no-oped (session guard inside mutateChat) →
        // the key stays Loading (NOT promoted to Loaded)…
        assertEquals(
            "reconcile discarded — key not promoted",
            PartExpandState.Loading,
            store.chatFlow.value.partExpandStates[key("m1", "p1")],
        )
        // …and the fetched full content was NOT written (skeleton intact).
        assertEquals(
            "skeleton",
            store.chatFlow.value.partsByMessage["m1"]?.firstOrNull()?.text,
        )
    }


    // ── 4. Idle-filter — Loaded parts are NOT re-expanded ────────────────

    @Test
    fun `already Loaded parts are skipped and survive reconcile`() = runTest {
        val store = SharedStateStore()
        val repo = mockk<OpenCodeRepository>(relaxed = true)

        // Two recent messages: one Idle+eligible (should expand), one already
        // Loaded+eligible (must be EXCLUDED from the request and survive).
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(msg("m_idle"), msg("m_loaded")),
                partsByMessage = mapOf(
                    "m_idle" to listOf(skeletonPart("p_idle", "m_idle")),
                    "m_loaded" to listOf(skeletonPart("p_loaded", "m_loaded")),
                ),
                partExpandStates = mapOf(
                    key("m_idle", "p_idle") to PartExpandState.Idle,
                    key("m_loaded", "p_loaded") to PartExpandState.Loaded,
                ),
            )
        }

        val capturedIds = mutableListOf<Set<String>>()
        coEvery {
            repo.expandMessagesFullBatch(eq("s1"), capture(capturedIds))
        } returns ExpandOutcome.Ok(
            items = listOf(MessageWithParts(msg("m_idle"), listOf(fullPart("p_idle", "m_idle")))),
            failures = emptyList(),
            usedBatch = true,
        )

        launchAutoExpandOmittedParts(this, repo, store, "s1", { "fp" })
        advanceUntilIdle()

        // Only the Idle owner's id was requested — the Loaded owner was filtered out.
        assertEquals(1, capturedIds.size)
        assertEquals(setOf("m_idle"), capturedIds.single())

        val states = store.chatFlow.value.partExpandStates
        // Idle key resolved to Loaded by the reconcile.
        assertEquals(
            "idle key expanded",
            PartExpandState.Loaded,
            states[key("m_idle", "p_idle")],
        )
        // Loaded key SURVIVED (not regressed to Idle, not re-requested).
        assertEquals(
            "loaded key preserved",
            PartExpandState.Loaded,
            states[key("m_loaded", "p_loaded")],
        )
        // And the Loaded owner's skeleton is untouched (it was never fetched).
        val loadedParts = store.chatFlow.value.partsByMessage["m_loaded"]
        assertEquals(
            "loaded owner skeleton intact",
            "p_loaded",
            loadedParts?.firstOrNull()?.id,
        )
    }

    // ── 5. Failure path ──────────────────────────────────────────────────

    @Test
    fun `repo throw marks keys Failed null and keeps the skeleton`() = runTest {
        val store = SharedStateStore()
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        // Usecase's runSuspendCatching collapses this into Result.failure →
        // launchAutoExpandOmittedParts getOrElse branch.
        coEvery { repo.expandMessagesFullBatch(any(), any()) } throws RuntimeException("boom")

        val skeleton = skeletonPart("p1", "m1")
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(msg("m1")),
                partsByMessage = mapOf("m1" to listOf(skeleton)),
                partExpandStates = mapOf(key("m1", "p1") to PartExpandState.Idle),
            )
        }

        // Must NOT throw out of the launched coroutine (runTest fails on an
        // uncaught child exception).
        launchAutoExpandOmittedParts(this, repo, store, "s1", { "fp" })
        advanceUntilIdle()

        // Key flipped Idle → Loading → Failed(code=null).
        assertEquals(
            PartExpandState.Failed(code = null),
            store.chatFlow.value.partExpandStates[key("m1", "p1")],
        )
        // Skeleton NOT removed (partsByMessage untouched by the failure branch).
        val parts = store.chatFlow.value.partsByMessage["m1"]
        assertEquals("skeleton part preserved on failure", 1, parts?.size)
        assertEquals("p1", parts?.firstOrNull()?.id)
        // Still a skeleton marker (hasFull/omitted intact — not resolved).
        assertEquals(true, parts?.firstOrNull()?.hasFull)
        assertNotEquals(null, parts?.firstOrNull()?.omitted)
    }
}
