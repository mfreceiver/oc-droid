package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.MainDispatcherRule
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ProbeResult
import cn.vectory.ocdroid.data.repository.SlimFullReconciler
import cn.vectory.ocdroid.data.repository.SlimSinceStageAOutcome
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C2 CRITICAL trigger-chain unit tests for [SessionSyncCoordinator]:
 *
 *  - DIGEST_FOCUS digest → debounce → [SlimFullReconciler.reconcileActiveSession]
 *    called exactly-once with the request's token threaded unchanged.
 *  - DIGEST_BACKGROUND digest → NO sweep (the flag stays in the watermark for
 *    the next focus sweep / reconnect to consume).
 *  - [reconcileFullAfterTransportReset] → [SlimFullReconciler.reconcileReconnect]
 *    called exactly-once (the single server.connected-path wiring point).
 *
 * # Dispatcher choice + time advancement
 *
 * Mirrors [SessionSyncCoordinatorTest]: class-level [scope] bound to
 * [UnconfinedTestDispatcher] (its [TestScope.testScheduler] owns the virtual
 * clock the debounce `delay(DIGEST_FULL_SWEEP_DEBOUNCE_MS)` schedules on) +
 * tests are plain `@Test` (no `runTest`). Time is advanced via
 * `scope.testScheduler.advanceUntilIdle()` — the SAME proven pattern used
 * for the `DELTA_COALESCE_MS` debounce tests in
 * [SessionSyncCoordinatorTest.`message part delta opens a leading edge then
 *  coalesces trailing deltas within the window`]. `coVerify` reads mockk's
 * recorded-call log; it does NOT need a `runTest` scope.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionSyncCoordinatorC2TriggerChainTest {

    @get:org.junit.Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var settingsManager: SettingsManager
    private lateinit var repository: OpenCodeRepository
    private lateinit var slimFullReconciler: SlimFullReconciler

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        val store = SharedStateStore()
        slices = store.slices
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
        repository = mockk(relaxed = true)
        slimFullReconciler = mockk(relaxed = true)

        // Token-guard stubs (relaxed Boolean default = false would reject
        // every commit). isSlimCommitTokenCurrent = true so the digest's
        // reducer apply + commit succeed.
        every { repository.isSlimCommitTokenCurrent(any()) } returns true
        every { repository.commitIfSlimTokenCurrent(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
        // SlimSessionReconciler's commit surfaces default to false on relaxed
        // mocks — stub to true so the focus reconcile completes a full path.
        every { repository.clearSlimLocalMessages(any(), any()) } returns true
        every { repository.markSlimReconcileFailure(any(), any()) } returns true
        every { repository.markSlimReconcileAligned(any(), any()) } returns true
        every { repository.markSlimSessionDeleted(any(), any()) } returns true
        every { repository.markSlimDirty(any(), any()) } returns true
        every { repository.applySlimDigest(any(), any()) } returns null
        // SlimSessionReconciler Prep state: pre-align the watermark so the
        // focus reconcile's needsCatchUp check returns false (probe's tuple
        // matches localApplied*). This short-circuits to Aligned WITHOUT
        // driving the cold-path / cursor-drain / commitAuthoritative body
        // — the test's concern is the post-reconcile trigger chain, not the
        // reconcile internals (those live in SlimFullReconcilerTest /
        // SlimSyncEngineStageATest).
        every { repository.getSlimSessionState(any()) } returns
            cn.vectory.ocdroid.data.repository.SlimSessionState(
                sessionId = "sess-focus",
                localAppliedMessageId = "m-aligned",
                localAppliedUpdatedAt = 100L,
                remoteMessageId = "m-aligned",
                remoteUpdatedAt = 100L,
            )
        every { repository.snapshotSlimSseState() } returns emptyMap()
        coEvery { repository.probeLatestSlim(any()) } returns ProbeResult(
            ok = true,
            messageID = "m-aligned",
            updatedAt = 100L,
        )
        coEvery {
            repository.fetchSinceForStageA(any(), any(), any(), any(), any())
        } returns SlimSinceStageAOutcome.Staged(
            items = emptyList(),
            completeHeader = null,
            statusCode = 200,
            transportComplete = true,
        )

        // SlimFullReconciler stub returns: Completed(emptyMap) so the launch
        // completes cleanly without R2 work.
        coEvery {
            slimFullReconciler.reconcileActiveSession(any(), any(), any(), any())
        } returns SlimFullReconciler.BatchOutcome.Completed(emptyMap())
        coEvery {
            slimFullReconciler.reconcileReconnect(any(), any(), any())
        } returns SlimFullReconciler.BatchOutcome.Completed(emptyMap())
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
            supportsWatermarkResync = { true },
            repository = repository,
            reconcileDispatcher = UnconfinedTestDispatcher(),
            slimFullReconciler = slimFullReconciler,
        )

    private fun digestEvent(
        sessionId: String,
        updatedAt: Long? = null,
        messageId: String? = null,
    ): SSEEvent {
        val props = buildJsonObject {
            put("sessionID", sessionId)
            put("directory", "/proj")
            updatedAt?.let { put("updatedAt", it) }
            messageId?.let { put("messageID", it) }
        }
        return SSEEvent(payload = SSEPayload(type = "session.digest", properties = props))
    }

    // ── A1: DIGEST_FOCUS → debounce → reconcileActiveSession ───────────────

    @Test
    fun `DIGEST_FOCUS digest triggers reconcileActiveSession after debounce`() {
        // currentSessionId matches the digest's sid → DIGEST_FOCUS.
        slices.mutateChat { it.copy(currentSessionId = "sess-focus") }
        val c = coordinator()

        c.handleSessionDigest(digestEvent("sess-focus", updatedAt = 100L, messageId = "m1"))
        // Before the debounce window elapses, reconcileActiveSession has NOT
        // been called yet (the sweep Job is pending on the scheduler).
        scope.testScheduler.advanceUntilIdle()
        // The debounce delay(DIGEST_FULL_SWEEP_DEBOUNCE_MS) is virtual-time-
        // advanced by advanceUntilIdle → the trailing sweep fires within
        // this single call.

        // Exactly one reconcileActiveSession call, with the focus session's
        // sid threaded unchanged.
        coVerify(exactly = 1) {
            slimFullReconciler.reconcileActiveSession(
                sessionId = "sess-focus",
                token = any(),
                context = any(),
                maxActive = any(),
            )
        }
    }

    @Test
    fun `DIGEST_FOCUS threads the digest request token unchanged (no recapture)`() {
        slices.mutateChat { it.copy(currentSessionId = "sess-focus") }
        // The token SlimSessionReconciler.prepareSessionDigest captures via
        // repository.captureSlimCommitToken() — the SAME instance MUST be
        // threaded unchanged into reconcileActiveSession (freeze protocol:
        // single entry token, no recapture inside the sweep).
        val capturedToken = OpenCodeRepository.SlimCommitToken(marker = Any(), issuedReady = true)
        every { repository.captureSlimCommitToken() } returns capturedToken

        val c = coordinator()
        c.handleSessionDigest(digestEvent("sess-focus", updatedAt = 100L, messageId = "m1"))
        scope.testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            slimFullReconciler.reconcileActiveSession(
                sessionId = "sess-focus",
                token = eq(capturedToken),
                context = any(),
                maxActive = any(),
            )
        }
    }

    // ── A1 negative: DIGEST_BACKGROUND → NO sweep ───────────────────────────

    @Test
    fun `DIGEST_BACKGROUND digest does NOT trigger reconcileActiveSession`() {
        // The current session is sess-A; the digest is for sess-B → BACKGROUND.
        slices.mutateChat { it.copy(currentSessionId = "sess-A") }
        val c = coordinator()

        c.handleSessionDigest(digestEvent("sess-B", updatedAt = 100L, messageId = "m1"))
        scope.testScheduler.advanceUntilIdle()

        // No reconcileActiveSession call — the flag stays in the watermark
        // for the next focus sweep / reconnect R1 to consume.
        coVerify(exactly = 0) {
            slimFullReconciler.reconcileActiveSession(any(), any(), any(), any())
        }
    }

    // ── A1 negative: no SlimFullReconciler wired → silent no-op ────────────

    @Test
    fun `focus digest with no SlimFullReconciler wired is a silent no-op`() {
        slices.mutateChat { it.copy(currentSessionId = "sess-focus") }
        // Construct WITHOUT slimFullReconciler (legacy / not-yet-wired-by-Lane-I).
        val c = SessionSyncCoordinator(
            scope = scope,
            slices = slices,
            settingsManager = settingsManager,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            supportsWatermarkResync = { true },
            repository = repository,
            reconcileDispatcher = UnconfinedTestDispatcher(),
            // slimFullReconciler intentionally omitted → default null.
        )

        // Should NOT throw — requestDigestFullSweep short-circuits on null.
        c.handleSessionDigest(digestEvent("sess-focus", updatedAt = 100L, messageId = "m1"))
        scope.testScheduler.advanceUntilIdle()
        // No reconciler to verify against — the test's sole assertion is
        // that the path did not throw. The focus reconcile itself still ran
        // (the digest's reducer apply + commit), so the slim digest path
        // is unaffected by the null reconciler.
        assertTrue("focus digest path survived a null SlimFullReconciler", true)
    }

    // ── A3: reconcileFullAfterTransportReset → reconcileReconnect exactly-once ─

    @Test
    fun `reconcileFullAfterTransportReset calls reconcileReconnect exactly once`() {
        // Make isStillCurrent + token-current both true so the workflow
        // proceeds past the gates.
        every { repository.captureSlimCommitToken() } returns
            OpenCodeRepository.SlimCommitToken(marker = Any(), issuedReady = true)
        every { repository.currentClientBundle() } returns mockk {
            every { generation } returns 7L
            every { endpointFp } returns "fp-reconnect"
        }
        val c = coordinator()

        c.reconcileFullAfterTransportReset(isStillCurrent = { true })
        scope.testScheduler.advanceUntilIdle()

        // Exactly one reconcileReconnect call (the single server.connected
        // wiring point). The token is threaded unchanged from capture.
        coVerify(exactly = 1) {
            slimFullReconciler.reconcileReconnect(
                context = any(),
                token = any(),
                concurrency = any(),
            )
        }
    }

    @Test
    fun `reconcileFullAfterTransportReset aborts cleanly when transport is no longer current`() {
        every { repository.captureSlimCommitToken() } returns
            OpenCodeRepository.SlimCommitToken(marker = Any(), issuedReady = true)
        val c = coordinator()

        c.reconcileFullAfterTransportReset(isStillCurrent = { false })
        scope.testScheduler.advanceUntilIdle()

        // isStillCurrent() == false → the workflow aborts BEFORE capturing
        // a token / calling reconcileReconnect. No side effects landed.
        coVerify(exactly = 0) {
            slimFullReconciler.reconcileReconnect(any(), any(), any())
        }
    }

    @Test
    fun `reconcileFullAfterTransportReset aborts cleanly on stale token`() {
        // captureSlimCommitToken returns a token, but isSlimCommitTokenCurrent
        // returns false (host reconfigure rotated the incarnation between
        // the transport gate and the token capture). This stub overrides the
        // setUp default for THIS test only.
        every { repository.captureSlimCommitToken() } returns
            OpenCodeRepository.SlimCommitToken(marker = Any(), issuedReady = true)
        every { repository.isSlimCommitTokenCurrent(any()) } returns false
        val c = coordinator()

        c.reconcileFullAfterTransportReset(isStillCurrent = { true })
        scope.testScheduler.advanceUntilIdle()

        // Token-current gate fired → no reconcileReconnect call.
        coVerify(exactly = 0) {
            slimFullReconciler.reconcileReconnect(any(), any(), any())
        }
    }

    @Test
    fun `reconcileFullAfterTransportReset with no SlimFullReconciler wired is a silent no-op`() {
        val c = SessionSyncCoordinator(
            scope = scope,
            slices = slices,
            settingsManager = settingsManager,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            supportsWatermarkResync = { true },
            repository = repository,
            reconcileDispatcher = UnconfinedTestDispatcher(),
            // slimFullReconciler intentionally omitted → default null.
        )
        // No throw, no reconcileReconnect (nothing to call).
        c.reconcileFullAfterTransportReset(isStillCurrent = { true })
        scope.testScheduler.advanceUntilIdle()
        assertTrue("reconnect path survived a null SlimFullReconciler", true)
    }
}
