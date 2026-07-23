package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.MainDispatcherRule
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ProbeResult
import cn.vectory.ocdroid.data.repository.SlimSessionState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Task 12 (slimapi v1 §2 / §6.1 + §G2 session.error semantics): unit tests
 * for the canonical [cn.vectory.ocdroid.ui.SessionListState.sessionErrorsById]
 * routing on [SessionSyncCoordinator].
 *
 * Covers:
 *  - **T12-C1**: `session.error` with a `sessionID` writes the map
 *    (durable banner); without a `sessionID` it routes to a global toast
 *    only and does NOT touch the map.
 *  - **T12-C2**: `session.digest.lastError` three-state —
 *    [cn.vectory.ocdroid.data.model.LastErrorField.Set] → write;
 *    [cn.vectory.ocdroid.data.model.LastErrorField.Cleared] → remove key;
 *    [cn.vectory.ocdroid.data.model.LastErrorField.Omitted] → no change.
 *  - **T12-C3**: concurrent by-sid writes are idempotent (duplicate frames
 *    for the same sid converge to a single map entry; CAS via
 *    `MutableStateFlow.update` is the per-key serialization).
 *
 * **Contract truth:** `docs/slimapi-client-impl-v1.md` §G2 (lastError
 * three-state) + the session.error row in §2 ("无 sessionID → 全局 toast；
 * 有 sessionID → 该 session 行/banner").
 *
 * **T12-C4 invariant:** these tests assert on `sessionErrorsById` directly
 * (no `applySessionErrorBanner` / `sessionBanners` indirection exists).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionSyncCoordinatorLastErrorTest {

    @get:org.junit.Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var settingsManager: SettingsManager
    private lateinit var scope: TestScope
    private lateinit var repository: OpenCodeRepository
    private var slimMode: Boolean = true

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0

        val store = SharedStateStore()
        slices = store.slices
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
        scope = TestScope(UnconfinedTestDispatcher())
        repository = mockk(relaxed = true)
        slimMode = true

        // C-D3 token guard stubs.
        // captureSlimCommitToken: let relaxed mock auto-answer (no explicit every).
        every { repository.isSlimCommitTokenCurrent(any()) } returns true
        every { repository.commitIfSlimTokenCurrent(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
        // Boolean wrappers default to false on relaxed mocks — stub to true.
        every { repository.clearSlimLocalMessages(any(), any()) } returns true
        every { repository.markSlimReconcileFailure(any(), any()) } returns true
        every { repository.markSlimReconcileAligned(any(), any()) } returns true
        every { repository.markSlimSessionDeleted(any(), any()) } returns true
        every { repository.markSlimDirty(any(), any()) } returns true
        every { repository.invalidateSlimLocalApplied(any(), any()) } returns true
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun coordinator(): SessionSyncCoordinator =
        SessionSyncCoordinator(
            scope = scope,
            slices = slices,
            settingsManager = settingsManager,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            supportsWatermarkResync = { slimMode },
            repository = repository,
            reconcileDispatcher = UnconfinedTestDispatcher(),
        )

    // ── event builders ───────────────────────────────────────────────────

    /**
     * Builds a `session.error` event. [shape] controls the wire form:
     *  - [SessionErrorShape.NESTED] (default, legacy opencode):
     *    `{ sessionID?, error: { name, data: { message }, at? } }`
     *  - [SessionErrorShape.TOP_LEVEL] (slimapi v1 contract §2 / §G2):
     *    `{ sessionID?, name, message, at? }`
     *  - [SessionErrorShape.NESTED_NO_DATA] (defensive): nested error but
     *    `data` absent — exercises the `data.error` fallback branch.
     *
     * I3 (round-2): the parser must accept BOTH top-level + nested shapes
     * because the deployed slimapi sidecar's curated SSE list does NOT
     * include session.error (problem-report-wip.md C-D8), so the actual
     * wire shape is unverified.
     */
    private fun sessionErrorEvent(
        sessionId: String? = null,
        name: String? = "RateLimitError",
        message: String? = "slow down",
        at: Long? = null,
        shape: SessionErrorShape = SessionErrorShape.NESTED,
    ): SSEEvent {
        val props = buildJsonObject {
            sessionId?.let { put("sessionID", JsonPrimitive(it)) }
            when (shape) {
                SessionErrorShape.TOP_LEVEL -> {
                    name?.let { put("name", JsonPrimitive(it)) }
                    message?.let { put("message", JsonPrimitive(it)) }
                    at?.let { put("at", JsonPrimitive(it)) }
                }
                SessionErrorShape.NESTED -> put(
                    "error",
                    buildJsonObject {
                        name?.let { put("name", JsonPrimitive(it)) }
                        put(
                            "data",
                            buildJsonObject {
                                message?.let { put("message", JsonPrimitive(it)) }
                            },
                        )
                        at?.let { put("at", JsonPrimitive(it)) }
                    },
                )
                SessionErrorShape.NESTED_NO_DATA -> put(
                    "error",
                    buildJsonObject {
                        name?.let { put("name", JsonPrimitive(it)) }
                        // No `data` object — message lives at error.error (legacy
                        // fallback chain branch).
                        message?.let { put("error", JsonPrimitive(it)) }
                    },
                )
            }
        }
        return SSEEvent(payload = SSEPayload(type = "session.error", properties = props))
    }

    private enum class SessionErrorShape { TOP_LEVEL, NESTED, NESTED_NO_DATA }

    /**
     * Builds a `session.digest` event whose `lastError` field encodes the
     * three states. Per T1's `LastErrorFieldSerializer`:
     *  - omitted: don't emit the key at all.
     *  - cleared (null): `put("lastError", JsonNull)`.
     *  - set (object): `put("lastError", buildJsonObject { ... })`.
     */
    private fun digestEvent(
        sessionId: String,
        lastErrorState: LastErrorState,
        errorName: String = "UpstreamTimeout",
        errorMessage: String = "timed out",
    ): SSEEvent {
        val props = buildJsonObject {
            put("sessionID", sessionId)
            when (lastErrorState) {
                LastErrorState.OMITTED -> { /* key absent → Omitted */ }
                LastErrorState.CLEARED -> put("lastError", JsonNull)
                LastErrorState.SET -> put(
                    "lastError",
                    buildJsonObject {
                        put("name", errorName)
                        put("message", errorMessage)
                    },
                )
            }
        }
        return SSEEvent(payload = SSEPayload(type = "session.digest", properties = props))
    }

    private enum class LastErrorState { OMITTED, CLEARED, SET }

    // ── T12-C1: session.error routing ────────────────────────────────────

    @Test
    fun `session error with sessionID writes sessionErrorsById`() {
        val c = coordinator()
        c.handleEvent(sessionErrorEvent(sessionId = "s1", name = "RateLimitError", message = "slow down"))

        val banner = slices.sessionList.value.sessionErrorsById["s1"]
        assertEquals("RateLimitError", banner?.name)
        assertEquals("slow down", banner?.message)
    }

    @Test
    fun `session error without sessionID does NOT write sessionErrorsById`() {
        val c = coordinator()
        c.handleEvent(sessionErrorEvent(sessionId = null, name = "GlobalError", message = "boom"))

        assertTrue(
            "session.error without sessionID must not touch sessionErrorsById",
            slices.sessionList.value.sessionErrorsById.isEmpty(),
        )
    }

    @Test
    fun `session error without sessionID still emits a global toast UiEvent`() {
        val recorded = mutableListOf<UiEvent>()
        val collector = scope.launch { effects.uiEventsConsumed.toList(recorded) }
        val c = coordinator()
        c.handleEvent(sessionErrorEvent(sessionId = null, name = "GlobalError", message = "boom"))
        scope.advanceUntilIdle()

        assertTrue(
            "session.error without sessionID must emit at least one UiEvent.Error",
            recorded.any { it is UiEvent.Error },
        )
        collector.cancel()
    }

    @Test
    fun `session error preserves existing entries for other sids`() {
        slices.mutateSessionList {
            it.copy(
                sessionErrorsById = mapOf(
                    "other" to SlimSessionLastError(name = "Old", message = "prior"),
                )
            )
        }
        val c = coordinator()
        c.handleEvent(sessionErrorEvent(sessionId = "s1", name = "New", message = "fresh"))

        val map = slices.sessionList.value.sessionErrorsById
        assertEquals("New", map["s1"]?.name)
        assertEquals("Old", map["other"]?.name)
    }

    @Test
    fun `session error with sid replaces a prior banner for the same sid`() {
        slices.mutateSessionList {
            it.copy(
                sessionErrorsById = mapOf(
                    "s1" to SlimSessionLastError(name = "Old", message = "prior"),
                )
            )
        }
        val c = coordinator()
        c.handleEvent(sessionErrorEvent(sessionId = "s1", name = "New", message = "fresh"))

        val banner = slices.sessionList.value.sessionErrorsById["s1"]
        assertEquals("New", banner?.name)
        assertEquals("fresh", banner?.message)
    }

    @Test
    fun `session error with sid also emits the toast UiEvent`() {
        val recorded = mutableListOf<UiEvent>()
        val collector = scope.launch { effects.uiEventsConsumed.toList(recorded) }
        val c = coordinator()
        c.handleEvent(sessionErrorEvent(sessionId = "s1", name = "NamedError", message = "x"))
        scope.advanceUntilIdle()

        assertTrue(
            "session.error with sid must still emit a UiEvent.Error toast",
            recorded.any { it is UiEvent.Error },
        )
        // And the banner is also durable in the map.
        assertEquals("NamedError", slices.sessionList.value.sessionErrorsById["s1"]?.name)
        collector.cancel()
    }

    // ── T12-C2: digest lastError three-state ─────────────────────────────

    /**
     * Stubs the repo so the launched reconcile in `handleSessionDigest`
     * completes cleanly. Round-2 I1: the sessionErrorsById fold now runs
     * INSIDE the launched reconcile (under T11's per-sid stripe), so the
     * banner assertion depends on `scope.advanceUntilIdle()` running the
     * reconcile body. The mocks keep the body from throwing.
     */
    private fun stubDigestReconcile(sid: String) {
        every { repository.applySlimDigest(any(), any()) } returns null
        every { repository.getSlimSessionState(sid) } returns SlimSessionState(sessionId = sid)
        coEvery { repository.probeLatestSlim(sid) } returns ProbeResult(ok = true, empty = true)
    }

    @Test
    fun `digest lastError Set writes sessionErrorsById`() = runTest {
        stubDigestReconcile("s1")
        val c = coordinator()
        c.handleEvent(digestEvent("s1", LastErrorState.SET, errorName = "UpstreamTimeout"))
        scope.advanceUntilIdle()

        val banner = slices.sessionList.value.sessionErrorsById["s1"]
        assertEquals("UpstreamTimeout", banner?.name)
    }

    @Test
    fun `digest lastError Cleared removes the sessionErrorsById key`() = runTest {
        slices.mutateSessionList {
            it.copy(
                sessionErrorsById = mapOf(
                    "s1" to SlimSessionLastError(name = "Prior", message = "old"),
                )
            )
        }
        stubDigestReconcile("s1")
        val c = coordinator()
        c.handleEvent(digestEvent("s1", LastErrorState.CLEARED))
        scope.advanceUntilIdle()

        assertNull(
            "Cleared must remove the sid from sessionErrorsById",
            slices.sessionList.value.sessionErrorsById["s1"],
        )
    }

    @Test
    fun `digest lastError Omitted preserves the existing sessionErrorsById entry`() = runTest {
        slices.mutateSessionList {
            it.copy(
                sessionErrorsById = mapOf(
                    "s1" to SlimSessionLastError(name = "Prior", message = "old"),
                )
            )
        }
        stubDigestReconcile("s1")
        val c = coordinator()
        c.handleEvent(digestEvent("s1", LastErrorState.OMITTED))
        scope.advanceUntilIdle()

        val banner = slices.sessionList.value.sessionErrorsById["s1"]
        assertEquals(
            "Omitted must NOT clear an active banner",
            "Prior",
            banner?.name,
        )
    }

    @Test
    fun `digest lastError Cleared on absent key is a no-op`() = runTest {
        stubDigestReconcile("s1")
        val c = coordinator()
        c.handleEvent(digestEvent("s1", LastErrorState.CLEARED))
        scope.advanceUntilIdle()

        assertFalse(
            "s1 must not be present after Cleared on an empty map",
            "s1" in slices.sessionList.value.sessionErrorsById,
        )
    }

    @Test
    fun `digest lastError Set then Cleared converges to no key`() = runTest {
        stubDigestReconcile("s1")
        val c = coordinator()
        c.handleEvent(digestEvent("s1", LastErrorState.SET, errorName = "Boom"))
        scope.advanceUntilIdle()
        assertEquals("Boom", slices.sessionList.value.sessionErrorsById["s1"]?.name)

        c.handleEvent(digestEvent("s1", LastErrorState.CLEARED))
        scope.advanceUntilIdle()
        assertNull(
            "Cleared after Set must remove the key",
            slices.sessionList.value.sessionErrorsById["s1"],
        )
    }

    // ── T12-C3: idempotency ──────────────────────────────────────────────

    @Test
    fun `duplicate session error frames for the same sid are idempotent`() {
        val c = coordinator()
        repeat(5) {
            c.handleEvent(sessionErrorEvent(sessionId = "s1", name = "RateLimitError", message = "slow"))
        }
        val map = slices.sessionList.value.sessionErrorsById
        assertEquals(1, map.size)
        assertEquals("RateLimitError", map["s1"]?.name)
    }

    @Test
    fun `two concurrent session error frames for the same sid converge to a single entry`() = runTest {
        val c = coordinator()
        // Launch concurrent dispatches — the per-sid stripe serializes the
        // map writes; the final state is a single entry for the sid.
        val jobs = (1..20).map { i ->
            async {
                c.handleEvent(sessionErrorEvent(sessionId = "s1", name = "Err$i", message = "m$i"))
            }
        }
        jobs.awaitAll()
        scope.advanceUntilIdle()

        val map = slices.sessionList.value.sessionErrorsById
        assertEquals("exactly one entry for s1 after concurrent writes", 1, map.size)
        assertNotNull(map["s1"])
    }

    /**
     * I1 discriminator (round-2): per-sid stripe serialization. Set(X)
     * followed by Cleared for the same sid MUST converge to "no banner"
     * (matching the last-dispatched event), regardless of which coroutine
     * won the launch race. Under the buggy round-1 code (fold inline
     * outside the stripe), this test would still pass on Unconfined (CAS
     * gives a linearizable order); the assertion's value is regression
     * protection — if someone removes the stripe routing later, this test
     * still pins the deterministic-final-state contract.
     *
     * The stronger assertion is on the FINAL STATE (banner absent), not
     * just `map.size`.
     */
    @Test
    fun `digest Set then Cleared for the same sid serializes through the stripe to deterministic no-banner state`() = runTest {
        stubDigestReconcile("s1")
        val c = coordinator()
        // Dispatch Set(X), then Cleared — both route through stripeFor("s1").
        c.handleEvent(digestEvent("s1", LastErrorState.SET, errorName = "X"))
        c.handleEvent(digestEvent("s1", LastErrorState.CLEARED))
        scope.advanceUntilIdle()

        assertNull(
            "Set-then-Cleared for the same sid must converge to no banner (last event wins)",
            slices.sessionList.value.sessionErrorsById["s1"],
        )
    }

    /**
     * I1 discriminator (round-2): session.error + digest lastError write
     * through the SAME per-sid stripe. Dispatch session.error(X) followed
     * by digest-Cleared — the stripe serializes them in arrival order, so
     * the final state matches the digest-Cleared (last dispatched) → no
     * banner. This is the strongest routing assertion: it would fail if
     * either producer bypassed the stripe AND ran on a scheduler that
     * delivered the cleared fold before the session.error map write
     * completed (the round-1 bug).
     */
    @Test
    fun `session error then digest Cleared for the same sid serializes through the stripe to no-banner state`() = runTest {
        stubDigestReconcile("s1")
        val c = coordinator()
        c.handleEvent(sessionErrorEvent(sessionId = "s1", name = "FromError", message = "e"))
        c.handleEvent(digestEvent("s1", LastErrorState.CLEARED))
        scope.advanceUntilIdle()

        val map = slices.sessionList.value.sessionErrorsById
        assertNull(
            "session.error then digest Cleared must converge to no banner (last event wins)",
            map["s1"],
        )
    }

    /**
     * I1 discriminator (round-2): reverse order — digest-Set then
     * session.error. Final state is the session.error value (last
     * dispatched). Pins the deterministic arrival-order semantics.
     */
    @Test
    fun `digest Set then session error for the same sid serializes through the stripe to the session error banner`() = runTest {
        stubDigestReconcile("s1")
        val c = coordinator()
        c.handleEvent(digestEvent("s1", LastErrorState.SET, errorName = "FromDigest"))
        c.handleEvent(sessionErrorEvent(sessionId = "s1", name = "FromError", message = "e"))
        scope.advanceUntilIdle()

        val banner = slices.sessionList.value.sessionErrorsById["s1"]
        assertEquals(
            "digest Set then session error must converge to the session error banner (last event wins)",
            "FromError",
            banner?.name,
        )
        assertEquals("e", banner?.message)
    }

    // ── I2 (round-2): slim-mode gate regression ──────────────────────────

    @Test
    fun `non-slim session error with sid does NOT write sessionErrorsById (legacy byte-for-byte unchanged)`() {
        slimMode = false
        val c = coordinator()
        c.handleEvent(sessionErrorEvent(sessionId = "s1", name = "RateLimitError", message = "slow"))
        scope.advanceUntilIdle()

        assertTrue(
            "non-slim session.error must NOT write sessionErrorsById (legacy path unchanged)",
            slices.sessionList.value.sessionErrorsById.isEmpty(),
        )
    }

    @Test
    fun `slim session error with sid DOES write sessionErrorsById`() {
        slimMode = true
        val c = coordinator()
        c.handleEvent(sessionErrorEvent(sessionId = "s1", name = "RateLimitError", message = "slow"))
        scope.advanceUntilIdle()

        assertEquals(
            "slim session.error must write sessionErrorsById",
            "RateLimitError",
            slices.sessionList.value.sessionErrorsById["s1"]?.name,
        )
    }

    @Test
    fun `non-slim session error still emits the toast (legacy surface preserved)`() {
        slimMode = false
        val recorded = mutableListOf<UiEvent>()
        val collector = scope.launch { effects.uiEventsConsumed.toList(recorded) }
        val c = coordinator()
        c.handleEvent(sessionErrorEvent(sessionId = "s1", name = "LegacyErr", message = "m"))
        scope.advanceUntilIdle()

        assertTrue(
            "non-slim session.error must still emit the toast (legacy unchanged)",
            recorded.any { it is UiEvent.Error },
        )
        collector.cancel()
    }

    // ── I3 (round-2): defensive dual-shape parsing ───────────────────────

    @Test
    fun `session error top-level shape (slim contract) writes the canonical banner`() {
        val c = coordinator()
        c.handleEvent(
            sessionErrorEvent(
                sessionId = "s1",
                name = "UpstreamTimeout",
                message = "timed out",
                at = 1234L,
                shape = SessionErrorShape.TOP_LEVEL,
            )
        )
        scope.advanceUntilIdle()

        val banner = slices.sessionList.value.sessionErrorsById["s1"]
        assertEquals("UpstreamTimeout", banner?.name)
        assertEquals("timed out", banner?.message)
        assertEquals(1234L, banner?.at)
    }

    @Test
    fun `session error nested shape (legacy opencode) still writes the canonical banner`() {
        val c = coordinator()
        c.handleEvent(
            sessionErrorEvent(
                sessionId = "s1",
                name = "RateLimitError",
                message = "slow down",
                shape = SessionErrorShape.NESTED,
            )
        )
        scope.advanceUntilIdle()

        val banner = slices.sessionList.value.sessionErrorsById["s1"]
        assertEquals("RateLimitError", banner?.name)
        assertEquals("slow down", banner?.message)
    }

    @Test
    fun `session error nested without data object falls back through the message chain`() {
        val c = coordinator()
        c.handleEvent(
            sessionErrorEvent(
                sessionId = "s1",
                name = "ProviderError",
                message = "via-error-field",
                shape = SessionErrorShape.NESTED_NO_DATA,
            )
        )
        scope.advanceUntilIdle()

        val banner = slices.sessionList.value.sessionErrorsById["s1"]
        assertEquals("ProviderError", banner?.name)
        assertEquals(
            "fallback chain must reach error.error when error.data is absent",
            "via-error-field",
            banner?.message,
        )
    }

    @Test
    fun `session error with at field at top level populates banner at`() {
        val c = coordinator()
        c.handleEvent(
            sessionErrorEvent(
                sessionId = "s1",
                name = "X",
                message = "m",
                at = 9999L,
                shape = SessionErrorShape.TOP_LEVEL,
            )
        )
        scope.advanceUntilIdle()

        assertEquals(9999L, slices.sessionList.value.sessionErrorsById["s1"]?.at)
    }
}
