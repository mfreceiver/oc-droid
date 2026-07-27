package cn.vectory.ocdroid.data.repository

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.api.SSEClient
import cn.vectory.ocdroid.data.api.v2.OpenCodeApiV2
import cn.vectory.ocdroid.data.repository.http.SslConfig
import io.mockk.mockk
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * Race-condition regression test for P2 (D3 host-switch race fix).
 *
 * Verifies that [SlimSseStateMachine] with an [epochProvider] correctly
 * rejects tokens captured before a host epoch bump, while still accepting
 * tokens captured after. Also verifies the unregistered-token fail-closed
 * behavior, the no-provider legacy compat path, and that
 * [beginSlimReconfigure] clears the token-to-epoch map so new captures
 * after a reconfigure are accepted.
 */
class SlimSseStateMachineRaceTest {

    @Test
    fun `epoch check rejects stale token after epoch bump`() {
        val epoch = AtomicLong(0L)
        val machine = SlimSseStateMachine(
            Any(),
            epochProvider = { epoch.get() },
            clientBundleProvider = { null },
        )

        val tokenA = machine.captureSlimCommitToken()

        // Simulate ConnectionIdentityStore.beginReconfigure()
        epoch.incrementAndGet()

        var committed = false
        val result = machine.commitIfSlimTokenCurrent(tokenA) {
            committed = true
        }

        assertFalse("commitIfSlimTokenCurrent must return false when epoch bumped", result)
        assertFalse("commit block must not execute when epoch bumped", committed)
    }

    @Test
    fun `epoch check still passes for current token`() {
        val epoch = AtomicLong(42L)
        val machine = SlimSseStateMachine(
            Any(),
            epochProvider = { epoch.get() },
            clientBundleProvider = { null },
        )

        val token = machine.captureSlimCommitToken()

        var committed = false
        val result = machine.commitIfSlimTokenCurrent(token) {
            committed = true
        }

        assertTrue("commitIfSlimTokenCurrent must return true for current token", result)
        assertTrue("commit block must execute for current token", committed)
    }

    @Test
    fun `token not registered with provider returns false`() {
        val epoch = AtomicLong(0L)
        val machine = SlimSseStateMachine(
            Any(),
            epochProvider = { epoch.get() },
            clientBundleProvider = { null },
        )

        // Create a token bypassing capture (simulate a token from before
        // epoch provider was added, or a token manufactured by reflection).
        val token = OpenCodeRepository.SlimCommitToken(
            marker = Any(),
            issuedReady = true,
        )

        var committed = false
        val result = machine.commitIfSlimTokenCurrent(token) {
            committed = true
        }

        assertFalse("commitIfSlimTokenCurrent must return false for unregistered token", result)
        assertFalse("commit block must not execute for unregistered token", committed)
    }

    @Test
    fun `no epoch provider behaves as before`() {
        val machine = SlimSseStateMachine(Any())  // no provider

        val token = machine.captureSlimCommitToken()

        var committed = false
        val result = machine.commitIfSlimTokenCurrent(token) {
            committed = true
        }

        assertTrue("commitIfSlimTokenCurrent must return true without epoch provider", result)
        assertTrue("commit block must execute without epoch provider", committed)
    }

    @Test
    fun `beginSlimReconfigure clears token epochs`() {
        val epoch = AtomicLong(0L)
        val machine = SlimSseStateMachine(
            Any(),
            epochProvider = { epoch.get() },
            clientBundleProvider = { null },
        )

        val tokenA = machine.captureSlimCommitToken()
        epoch.incrementAndGet()

        // beginSlimReconfigure clears map and sets readiness=false
        val ticket = machine.beginSlimReconfigure()

        // tokenA should be stale (cleared from map + epoch mismatch)
        assertFalse("tokenA should be stale after beginSlimReconfigure",
            machine.commitIfSlimTokenCurrent(tokenA) {})

        // Complete the reconfigure so readiness re-arms
        machine.completeSlimReconfigure(ticket)

        // Now capture a new token (should be current)
        val tokenB = machine.captureSlimCommitToken()
        assertTrue("tokenB should be current after completeSlimReconfigure",
            machine.commitIfSlimTokenCurrent(tokenB) {})

        // tokenA should still be stale (map entry gone)
        assertFalse("tokenA should remain stale after completeSlimReconfigure",
            machine.commitIfSlimTokenCurrent(tokenA) {})
    }

    @Test
    fun `published client generation rejects token even when slim marker and identity epoch stay current`() {
        val bundle = AtomicReference(bundle(generation = 1L, endpoint = "a.example"))
        val machine = SlimSseStateMachine(
            slimStateLock = Any(),
            epochProvider = { 7L },
            clientBundleProvider = { bundle.get() },
        )

        val tokenA = machine.captureSlimCommitToken()
        bundle.set(bundle(generation = 2L, endpoint = "b.example"))

        var committed = false
        assertFalse(
            "a result must be stale after the published bundle changes",
            machine.commitIfSlimTokenCurrent(tokenA) { committed = true },
        )
        assertFalse(committed)
    }

    private fun bundle(generation: Long, endpoint: String): ClientBundle = ClientBundle(
        generation = generation,
        hostSnapshot = HostSnapshot(
            baseUrl = "http://$endpoint",
            hostPort = endpoint,
            username = null,
            password = null,
            slimHost = true,
        ),
        effectiveSslConfig = SslConfig.SystemDefault,
        clientCertError = null,
        restHttp = mockk(),
        restRetrofit = mockk(),
        restApi = mockk<OpenCodeApi>(),
        sseHttp = mockk(),
        sseClient = mockk<SSEClient>(),
        commandHttp = mockk<OkHttpClient>(),
        commandRetrofit = mockk<Retrofit>(),
        commandApi = mockk<OpenCodeApi>(),
        mutationHttp = mockk<OkHttpClient>(),
        mutationRetrofit = mockk<Retrofit>(),
        mutationApi = mockk<OpenCodeApi>(),
        v2Retrofit = mockk<Retrofit>(),
        apiV2 = mockk<OpenCodeApiV2>(),
        ownedClients = emptyList(),
    )

    // ── B-P0-1: clearFullRecheckFlag (token-guarded) ───────────────────

    @Test
    fun `clearFullRecheckFlag clears flagged entry under current token`() {
        val machine = SlimSseStateMachine(Any())
        val token = machine.captureSlimCommitToken()
        // Seed a flagged entry via applyMessagePartRemoved (token-guarded
        // path on the same machine).
        machine.applyMessagePartRemoved(
            sessionId = "s1",
            messageId = "m1",
            partId = "p1",
            messageEventSeq = 5L,
            token = token,
        )
        // Pre: flagged.
        val before = machine.snapshotSessionWatermarks("s1")["m1"]!!
        assertTrue(before.needsFullRecheck)
        // Clear.
        val cleared = machine.clearFullRecheckFlag("s1", "m1", token)
        assertTrue("flag cleared under current token", cleared)
        val after = machine.snapshotSessionWatermarks("s1")["m1"]!!
        assertFalse(after.needsFullRecheck)
        assertEquals("seq preserved", 5L, after.messageEventSeq)
    }

    @Test
    fun `clearFullRecheckFlag returns false under stale token`() {
        val epoch = AtomicLong(0L)
        val machine = SlimSseStateMachine(
            Any(),
            epochProvider = { epoch.get() },
            clientBundleProvider = { null },
        )
        val token = machine.captureSlimCommitToken()
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p1", messageEventSeq = 5L, token = token,
        )
        // Rotate epoch → token is stale.
        epoch.incrementAndGet()
        val cleared = machine.clearFullRecheckFlag("s1", "m1", token)
        assertFalse("stale token must not mutate flag", cleared)
        // Flag still set.
        assertTrue(
            "flag preserved on stale-token rejection",
            machine.snapshotSessionWatermarks("s1")["m1"]!!.needsFullRecheck,
        )
    }

    @Test
    fun `clearFullRecheckFlag on absent entry returns false`() {
        val machine = SlimSseStateMachine(Any())
        val token = machine.captureSlimCommitToken()
        assertFalse(machine.clearFullRecheckFlag("s1", "ghost", token))
    }

    // ── rev-b-fix §3: commitFull200 atomic commit port ───────────────────

    @Test
    fun `commitFull200 happy path commits seq clears flag and runs commitUi`() {
        val machine = SlimSseStateMachine(Any())
        val token = machine.captureSlimCommitToken()
        // Seed: seq=5, flag set (digest path).
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p1", messageEventSeq = 5L, token = token,
        )
        var uiMerged = false
        val committed = machine.commitFull200(
            sessionId = "s1",
            messageId = "m1",
            requestSeq = 5L,
            responseSeq = 7L,
            token = token,
        ) {
            uiMerged = true
            true
        }
        assertTrue("happy-path commit MUST return true", committed)
        assertTrue("commitUi lambda MUST run inside the critical section", uiMerged)
        val w = machine.snapshotSessionWatermarks("s1")["m1"]!!
        assertEquals(7L, w.messageEventSeq)
        assertFalse("flag cleared on commit", w.needsFullRecheck)
    }

    @Test
    fun `commitFull200 rejects stale token without running commitUi`() {
        val epoch = AtomicLong(0L)
        val machine = SlimSseStateMachine(
            Any(),
            epochProvider = { epoch.get() },
            clientBundleProvider = { null },
        )
        val token = machine.captureSlimCommitToken()
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p1", messageEventSeq = 5L, token = token,
        )
        // Rotate epoch → token is stale.
        epoch.incrementAndGet()
        var uiMerged = false
        val committed = machine.commitFull200(
            sessionId = "s1", messageId = "m1",
            requestSeq = 5L, responseSeq = 7L, token = token,
        ) {
            uiMerged = true
            true
        }
        assertFalse("stale token MUST reject", committed)
        assertFalse("commitUi MUST NOT run on stale token", uiMerged)
        // Watermark untouched (flag still set, seq still 5).
        val w = machine.snapshotSessionWatermarks("s1")["m1"]!!
        assertEquals(5L, w.messageEventSeq)
        assertTrue(w.needsFullRecheck)
    }

    @Test
    fun `commitFull200 rejects responseSeq less than currentSeq without clearing flag`() {
        // rev-b-fix §3 frozen rule: a stale /full response that lag
        // behind the watermark MUST NOT merge and MUST NOT clear the
        // flag. The newer seq will drive a fresh reconcile.
        val machine = SlimSseStateMachine(Any())
        val token = machine.captureSlimCommitToken()
        // Seed: seq=10, flag set.
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p1", messageEventSeq = 10L, token = token,
        )
        var uiMerged = false
        val committed = machine.commitFull200(
            sessionId = "s1", messageId = "m1",
            requestSeq = 10L,
            responseSeq = 7L, // OLDER than current 10
            token = token,
        ) {
            uiMerged = true
            true
        }
        assertFalse("responseSeq < currentSeq MUST reject", committed)
        assertFalse("commitUi MUST NOT run on rejected commit", uiMerged)
        val w = machine.snapshotSessionWatermarks("s1")["m1"]!!
        assertEquals(10L, w.messageEventSeq)
        assertTrue("flag MUST stay set when stale response rejected", w.needsFullRecheck)
    }

    @Test
    fun `commitFull200 rejects responseSeq zero as protocol failure`() {
        // Frozen protocol: 0 is the uninitialised sentinel and MUST
        // NOT be accepted as a real watermark value.
        val machine = SlimSseStateMachine(Any())
        val token = machine.captureSlimCommitToken()
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p1", messageEventSeq = 5L, token = token,
        )
        var uiMerged = false
        val committed = machine.commitFull200(
            sessionId = "s1", messageId = "m1",
            requestSeq = 5L, responseSeq = 0L, token = token,
        ) {
            uiMerged = true
            true
        }
        assertFalse("responseSeq=0 is a protocol failure", committed)
        assertFalse(uiMerged)
        // State untouched.
        val w = machine.snapshotSessionWatermarks("s1")["m1"]!!
        assertEquals(5L, w.messageEventSeq)
        assertTrue(w.needsFullRecheck)
    }

    // ── rev-ogpt #2: commitUi verdict gates the watermark mutation ──────

    @Test
    fun `commitFull200 with commitUi=false preserves flag and does not advance seq`() {
        // rev-ogpt #2: when commitUi returns false (UI rejected the
        // dispatch — route/bundle CAS fail OR route=0), the flag MUST
        // stay set AND the seq MUST NOT advance. The next digest sweep
        // (or route reactivation when route=0) retries with the same
        // requestSeq. Previously the flag was cleared UNCONDITIONALLY
        // and the message was silently dropped.
        val machine = SlimSseStateMachine(Any())
        val token = machine.captureSlimCommitToken()
        // Seed: seq=5, flag set.
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p1", messageEventSeq = 5L, token = token,
        )
        val committed = machine.commitFull200(
            sessionId = "s1", messageId = "m1",
            requestSeq = 5L,
            responseSeq = 7L,
            token = token,
        ) { false } // UI rejected the dispatch.
        assertFalse("commitUi=false MUST reject the commit", committed)
        val w = machine.snapshotSessionWatermarks("s1")["m1"]!!
        assertEquals(
            "seq MUST NOT advance when commitUi rejects",
            5L,
            w.messageEventSeq,
        )
        assertTrue(
            "flag MUST stay set when commitUi rejects — retry next sweep",
            w.needsFullRecheck,
        )
    }

    @Test
    fun `commitFull200 with commitUi=false on absent entry does not seed`() {
        // rev-ogpt #2: when the entry doesn't exist yet AND commitUi
        // rejects, we MUST NOT seed a cleared entry. (Pre-fix, the
        // canCommitFull200Seq + commitFull200Seq path would seed a
        // fresh entry on the would-accept branch.) The state machine
        // now validates seq FIRST, calls commitUi, and ONLY mutates
        // on acceptance — so a rejected commit leaves NO entry behind.
        val machine = SlimSseStateMachine(Any())
        val token = machine.captureSlimCommitToken()
        val committed = machine.commitFull200(
            sessionId = "s1", messageId = "ghost",
            requestSeq = 0L,
            responseSeq = 7L,
            token = token,
        ) { false }
        assertFalse(committed)
        assertNull(
            "no entry should be seeded when commitUi rejects",
            machine.snapshotSessionWatermarks("s1")["ghost"],
        )
    }

    // ── rev-b-fix §4: commitFull304 conditional flag clear ───────────────

    @Test
    fun `commitFull304 clears flag when seq matches request`() {
        val machine = SlimSseStateMachine(Any())
        val token = machine.captureSlimCommitToken()
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p1", messageEventSeq = 5L, token = token,
        )
        val cleared = machine.commitFull304(
            sessionId = "s1", messageId = "m1",
            requestSeq = 5L, token = token,
        )
        assertTrue("flag cleared when seq matches", cleared)
        val w = machine.snapshotSessionWatermarks("s1")["m1"]!!
        assertFalse(w.needsFullRecheck)
        assertEquals(5L, w.messageEventSeq)
    }

    @Test
    fun `commitFull304 does NOT clear flag when seq advanced during network window`() {
        // rev-b-fix §4 frozen rule: if the local seq has ADVANCED (a
        // message.part.* SSE event arrived between the /full request
        // and the 304), the 304's "your view is authoritative"
        // assertion no longer holds — keep the flag so the next sweep
        // re-fetches against the new seq.
        val machine = SlimSseStateMachine(Any())
        val token = machine.captureSlimCommitToken()
        // Issue /full at seq=5 — requestSeq=5.
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p1", messageEventSeq = 5L, token = token,
        )
        // Network window: a newer part arrives, advancing seq to 7.
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p2", messageEventSeq = 7L, token = token,
        )
        // 304 lands. The request was issued at seq=5; current is 7.
        val cleared = machine.commitFull304(
            sessionId = "s1", messageId = "m1",
            requestSeq = 5L, token = token,
        )
        assertFalse("seq mismatch MUST NOT clear the flag", cleared)
        assertTrue(
            "flag preserved so next sweep re-fetches against new seq",
            machine.snapshotSessionWatermarks("s1")["m1"]!!.needsFullRecheck,
        )
    }

    @Test
    fun `commitFull304 rejects stale token without mutating flag`() {
        val epoch = AtomicLong(0L)
        val machine = SlimSseStateMachine(
            Any(),
            epochProvider = { epoch.get() },
            clientBundleProvider = { null },
        )
        val token = machine.captureSlimCommitToken()
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p1", messageEventSeq = 5L, token = token,
        )
        epoch.incrementAndGet()
        val cleared = machine.commitFull304(
            sessionId = "s1", messageId = "m1",
            requestSeq = 5L, token = token,
        )
        assertFalse("stale token MUST reject", cleared)
        assertTrue(
            "flag preserved on stale-token rejection",
            machine.snapshotSessionWatermarks("s1")["m1"]!!.needsFullRecheck,
        )
    }

    @Test
    fun `commitFull304 on absent entry returns false`() {
        val machine = SlimSseStateMachine(Any())
        val token = machine.captureSlimCommitToken()
        assertFalse(machine.commitFull304("s1", "ghost", requestSeq = 5L, token = token))
    }

    // ── rev-b-fix M3: clearWatermarksForReconnect(token) ─────────────────

    @Test
    fun `clearWatermarksForReconnect token-guarded resets under current token`() {
        val machine = SlimSseStateMachine(Any())
        val token = machine.captureSlimCommitToken()
        // Seed two sessions with seq state.
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p1", messageEventSeq = 5L, token = token,
        )
        machine.applyMessagePartRemoved(
            sessionId = "s2", messageId = "mX",
            partId = "p1", messageEventSeq = 9L, token = token,
        )
        val work = machine.clearWatermarksForReconnect(token)
        assertEquals(setOf("m1"), work["s1"])
        assertEquals(setOf("mX"), work["s2"])
        // Every preserved entry is now (seq=0, flag=true).
        val w1 = machine.snapshotSessionWatermarks("s1")["m1"]!!
        assertEquals(0L, w1.messageEventSeq)
        assertTrue(w1.needsFullRecheck)
    }

    @Test
    fun `clearWatermarksForReconnect token-guarded rejects stale token with empty map`() {
        val epoch = AtomicLong(0L)
        val machine = SlimSseStateMachine(
            Any(),
            epochProvider = { epoch.get() },
            clientBundleProvider = { null },
        )
        val token = machine.captureSlimCommitToken()
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p1", messageEventSeq = 5L, token = token,
        )
        epoch.incrementAndGet()
        val work = machine.clearWatermarksForReconnect(token)
        assertTrue("stale token MUST return empty map (no-op)", work.isEmpty())
        // State preserved (not reset).
        val w = machine.snapshotSessionWatermarks("s1")["m1"]!!
        assertEquals(5L, w.messageEventSeq)
    }

    @Test
    fun `clearWatermarksForReconnect legacy no-arg overload still works as bridge`() {
        // The legacy no-arg overload is retained until Lane R migrates
        // SlimFullReconciler's port signature. It MUST still perform
        // the reset (ControllerModule wires it).
        val machine = SlimSseStateMachine(Any())
        val seedToken = machine.captureSlimCommitToken()
        machine.applyMessagePartRemoved(
            sessionId = "s1", messageId = "m1",
            partId = "p1", messageEventSeq = 5L, token = seedToken,
        )
        val work = machine.clearWatermarksForReconnect()
        assertEquals(setOf("m1"), work["s1"])
        val w = machine.snapshotSessionWatermarks("s1")["m1"]!!
        assertEquals(0L, w.messageEventSeq)
        assertTrue(w.needsFullRecheck)
    }
}
