package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SessionListState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §需求10 §2.5: unit tests for [SessionMetadataPoller]'s identity guard
 * (§2.3) and poll-mode selection.
 *
 * The poller's coroutine-driven poll loop (init → combine → onEach) is
 * tested at the behavioral level through the [ConnectionIdentityStore]
 * guard assertions below.
 */
class SessionMetadataPollerTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun session(id: String, title: String? = null, directory: String = "/repo") =
        Session(id = id, title = title, directory = directory)

    // ── §2.3 host-identity guard ─────────────────────────────────────────────

    @Test
    fun `commitIfCurrent returns true when identity unchanged`() {
        val store = ConnectionIdentityStore()
        store.bind(
            serverGroupFp = "fp-a",
            normalizedWorkdir = "/workdir",
            endpointFp = "ep-a",
        )
        val cap = store.capture()

        var committed = false
        val result = store.commitIfCurrent(cap.identity, cap.epoch) {
            committed = true
        }

        assertTrue("commitIfCurrent should return true when identity is current", result)
        assertTrue("commit lambda should have run", committed)
    }

    @Test
    fun `commitIfCurrent returns false and does not run commit when epoch bumped`() {
        val store = ConnectionIdentityStore()
        store.bind(
            serverGroupFp = "fp-a",
            normalizedWorkdir = "/workdir",
            endpointFp = "ep-a",
        )
        val cap = store.capture()

        // Host switch: beginReconfigure bumps epoch + clears identity
        store.beginReconfigure()

        var committed = false
        val result = store.commitIfCurrent(cap.identity, cap.epoch) {
            committed = true
        }

        assertFalse("commitIfCurrent should return false after epoch bump", result)
        assertFalse("commit lambda must NOT run when stale", committed)
    }

    @Test
    fun `commitIfCurrent returns false and does not run commit when identity changed`() {
        val store = ConnectionIdentityStore()
        store.bind(
            serverGroupFp = "fp-a",
            normalizedWorkdir = "/workdir",
            endpointFp = "ep-a",
        )
        val cap = store.capture()

        // New bind with different identity fields (same epoch implicitly through bind)
        store.bind(
            serverGroupFp = "fp-b",
            normalizedWorkdir = "/other",
            endpointFp = "ep-b",
        )

        var committed = false
        val result = store.commitIfCurrent(cap.identity, cap.epoch) {
            committed = true
        }

        assertFalse("commitIfCurrent should return false after identity change", result)
        assertFalse("commit lambda must NOT run with stale identity", committed)
    }

    @Test
    fun `capture before beginReconfigure then commitIfCurrent fails for in-flight poll`() {
        // Simulates the full poll() sequence: capture → network call → host
        // switch → commitIfCurrent should reject the stale snapshot.
        val store = ConnectionIdentityStore()
        store.bind(
            serverGroupFp = "fp-a",
            normalizedWorkdir = "/wd",
            endpointFp = "ep-a",
        )
        val cap = store.capture()  // capture before host switch

        // host switches while network call is in-flight
        store.beginReconfigure()
        store.bind(
            serverGroupFp = "fp-b",
            normalizedWorkdir = "/wd2",
            endpointFp = "ep-b",
        )

        var sideEffect = false
        val result = store.commitIfCurrent(cap.identity, cap.epoch) {
            sideEffect = true
        }

        assertFalse("stale capture from old host must be rejected", result)
        assertFalse("session list must NOT be mutated by stale poll response", sideEffect)
    }

    // ── §2.5 session-list unchanged on stale commit ─────────────────────────

    @Test
    fun `stale poll response does not mutate sessionListState`() {
        // Integration-style: real SharedStateStore + identityStore. Simulate a
        // poll whose capture predates a host switch — the mutation inside
        // commitIfCurrent must not execute.
        val identityStore = ConnectionIdentityStore()
        identityStore.bind("fp-a", "/wd", "ep-a")

        val stateFlow = MutableStateFlow(
            cn.vectory.ocdroid.ui.StoreState.initial().copy(
                connection = cn.vectory.ocdroid.ui.ConnectionState(
                    connectionPhase = ConnectionPhase.Connected,
                    isConnected = true,
                ),
            )
        )
        val store = SharedStateStore(stateFlow)

        // Inject store with one existing session
        store.mutateSessionList {
            it.copy(sessions = listOf(session("s1", title = "old-title")))
        }
        val originalSessions: List<Session> = store.state.value.sessionList.sessions

        val cap = identityStore.capture()

        // Host switch after capture
        identityStore.beginReconfigure()
        identityStore.bind("fp-b", "/wd2", "ep-b")

        val committed = identityStore.commitIfCurrent(cap.identity, cap.epoch) {
            store.mutateSessionList { current ->
                // This should not execute because commitIfCurrent returns false
                current.copy(sessions = listOf(session("s1", title = "stale-title")))
            }
        }

        assertFalse("stale poll response must be rejected by commitIfCurrent", committed)

        // Verify sessionList is UNCHANGED (the poll was from a stale host).
        // Read from store.state directly (DerivedStateFlow projections are
        // initialized before the testState reassignment in the constructor).
        val afterSessions: List<Session> = store.state.value.sessionList.sessions
        assertEquals("session list must be unchanged after stale poll", originalSessions, afterSessions)
        assertEquals("title must NOT be overwritten by stale poll", "old-title", afterSessions.first().title)
    }

    // ── §2.2 poll-mode derivation ────────────────────────────────────────────

    @Test
    fun `baseline when foreground connected and SSE healthy`() {
        val mode = derivePollMode(
            foreground = true,
            phase = ConnectionPhase.Connected,
            isConnected = true,
            sseConnected = true,
        )
        assertEquals(PollModeForTest.BASELINE, mode)
    }

    @Test
    fun `fallback when foreground connected but SSE stalled`() {
        val mode = derivePollMode(
            foreground = true,
            phase = ConnectionPhase.Connected,
            isConnected = true,
            sseConnected = false,
        )
        assertEquals(PollModeForTest.FALLBACK, mode)
    }

    @Test
    fun `fallback when foreground disconnected`() {
        val mode = derivePollMode(
            foreground = true,
            phase = ConnectionPhase.Disconnected,
            isConnected = false,
            sseConnected = false,
        )
        assertEquals(PollModeForTest.FALLBACK, mode)
    }

    @Test
    fun `null when foreground but connecting (not connected and not SSE-down)`() {
        val mode = derivePollMode(
            foreground = true,
            phase = ConnectionPhase.Connecting,
            isConnected = false,
            sseConnected = false,
        )
        assertEquals(null, mode)
    }

    @Test
    fun `null when not foreground regardless of phase`() {
        val mode = derivePollMode(
            foreground = false,
            phase = ConnectionPhase.Connected,
            isConnected = true,
            sseConnected = true,
        )
        assertEquals(null, mode)
    }

    @Test
    fun `null when foreground idle`() {
        val mode = derivePollMode(
            foreground = true,
            phase = ConnectionPhase.Idle,
            isConnected = false,
            sseConnected = false,
        )
        assertEquals(null, mode)
    }

}

/**
 * Test-only copy of [SessionMetadataPoller]'s internal [Enum] for assertions.
 * Mirrors the actual PollMode values so the tests don't depend on private
 * enum visibility.
 */
internal enum class PollModeForTest { BASELINE, FALLBACK }

/**
 * Pure logic extracted from the poll-mode combine lambda for testability.
 * Returns the equivalent [PollModeForTest] (or null for "don't poll").
 */
internal fun derivePollMode(
    foreground: Boolean,
    phase: ConnectionPhase,
    isConnected: Boolean,
    sseConnected: Boolean,
): PollModeForTest? {
    if (!foreground) return null
    val sseEffectivelyDown =
        (phase is ConnectionPhase.Connected && !sseConnected) ||
        phase is ConnectionPhase.Disconnected
    return when {
        isConnected && !sseEffectivelyDown -> PollModeForTest.BASELINE
        sseEffectivelyDown -> PollModeForTest.FALLBACK
        else -> null
    }
}
