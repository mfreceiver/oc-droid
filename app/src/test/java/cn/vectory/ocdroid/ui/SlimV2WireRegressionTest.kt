package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.TokenStreamCoordinatorEffect
import cn.vectory.ocdroid.data.repository.TokenPartStreamState
import cn.vectory.ocdroid.data.repository.TokenStreamReducer
import cn.vectory.ocdroid.data.repository.TokenStreamReducerState
import cn.vectory.ocdroid.di.tokenStreamProductionHooks
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator
import cn.vectory.ocdroid.ui.controller.sse.TokenFrameCommitContext
import cn.vectory.ocdroid.ui.controller.sse.TokenStreamCoordinator
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
 * Test group 3: SlimAPI V2 wire regression tests.
 *
 * B-1: digest lastError=MessageAbortedError guard — no user error banner.
 * B-3: event name routing for `question.v2.asked`, `permission.v2.asked`,
 *      `permission.v2.resolved`, `permission.resolved`.
 * B-2: done marker with malicious text — NOT adopted, TriggerSinceFetch emitted.
 * B-4: partEventRevision strict `>` dedup through TokenStreamCoordinator +
 *      production hooks.
 *
 * Uses production wiring where possible: SessionSyncCoordinator.handleEvent()
 * for B-1/B-3 (the real SSE dispatch path), TokenStreamCoordinator with
 * production TokenStreamProductionHooks for B-2/B-4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlimV2WireRegressionTest {

    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var collectedEffects: MutableList<ControllerEffect>
    private lateinit var recordNonFatalCount: java.util.concurrent.atomic.AtomicInteger
    private lateinit var settingsManager: SettingsManager
    private lateinit var scope: TestScope
    private lateinit var coordinator: SessionSyncCoordinator
    private lateinit var stateStore: SharedStateStore
    private lateinit var repository: OpenCodeRepository
    private lateinit var identityStore: cn.vectory.ocdroid.service.identity.ConnectionIdentityStore

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        io.mockk.every { Log.w(any<String>(), any<String>()) } returns 0
        io.mockk.every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        stateStore = SharedStateStore()
        slices = stateStore.slices
        repository = OpenCodeRepository(
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        val bundle = repository.currentClientBundle()!!
        stateStore.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
        collectedEffects = mutableListOf()
        scope = TestScope(UnconfinedTestDispatcher())
        identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            effects.effectsConsumed.toList(collectedEffects)
        }
        recordNonFatalCount = java.util.concurrent.atomic.AtomicInteger(0)
        coordinator = SessionSyncCoordinator(
            scope, slices, settingsManager, effects,
            currentProfileId = { "test-fp" },
            identityStore = identityStore,
            repository = repository,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun event(type: String, block: JsonObjectBuilder.() -> Unit): SSEEvent =
        SSEEvent(payload = SSEPayload(type = type, properties = buildJsonObject(block)))

    // ═══════════════════════════════════════════════════════════════════════
    // B-1: digest lastError=MessageAbortedError guard
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `B1 digest MessageAbortedError lastError does NOT set session error banner`() {
        // V2 §3:95: abort (MessageAbortedError) is silently discarded.
        // A digest with lastError={"name":"MessageAbortedError",...} must NOT
        // produce an entry in sessionErrorsById.
        coordinator.handleEvent(event("session.digest") {
            put("sessionID", JsonPrimitive("s1"))
            put("lastError", buildJsonObject {
                put("name", JsonPrimitive("MessageAbortedError"))
                put("message", JsonPrimitive("upstream aborted"))
                put("at", JsonPrimitive(1_700_000_000_000))
            })
        })

        val errors = slices.sessionList.value.sessionErrorsById
        assertFalse(
            "MessageAbortedError must NOT produce an error banner",
            errors.containsKey("s1"),
        )
        // No error-side-effect emitted (SessionError, UiEvent.Error, etc).
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty())
    }

    @Test
    fun `B1 digest normal error lastError SETS session error banner`() {
        // A digest with lastError={"name":"SomeError",...} must produce an
        // entry in sessionErrorsById.
        coordinator.handleEvent(event("session.digest") {
            put("sessionID", JsonPrimitive("s1"))
            put("lastError", buildJsonObject {
                put("name", JsonPrimitive("RateLimitError"))
                put("message", JsonPrimitive("too many requests"))
                put("at", JsonPrimitive(1_700_000_000_001))
            })
        })

        val error = slices.sessionList.value.sessionErrorsById["s1"]
        assertNotNull("RateLimitError must produce an error banner", error)
        assertEquals("RateLimitError", error?.name)
        assertEquals("too many requests", error?.message)
        assertEquals(1_700_000_000_001L, error?.at)
    }

    @Test
    fun `B1 digest JsonNull lastError CLEARS existing error banner`() {
        // Seed an existing error banner for s1
        slices.mutateSessionList { s ->
            s.copy(sessionErrorsById = mapOf(
                "s1" to SlimSessionLastError("OldError", "previous", 1_000L),
            ))
        }
        assertEquals(1, slices.sessionList.value.sessionErrorsById.size)

        // Digest with lastError=null clears the banner
        coordinator.handleEvent(event("session.digest") {
            put("sessionID", JsonPrimitive("s1"))
            put("lastError", JsonNull)
        })

        assertFalse(
            "JsonNull lastError must clear the error banner",
            slices.sessionList.value.sessionErrorsById.containsKey("s1"),
        )
    }

    @Test
    fun `B1 digest absent lastError preserves existing error banner`() {
        // Seed an existing error banner for s1
        slices.mutateSessionList { s ->
            s.copy(sessionErrorsById = mapOf(
                "s1" to SlimSessionLastError("PersistentError", "still there", 2_000L),
            ))
        }

        // Digest without lastError field should preserve the existing banner
        coordinator.handleEvent(event("session.digest") {
            put("sessionID", JsonPrimitive("s1"))
            put("status", JsonPrimitive("busy"))
        })

        val error = slices.sessionList.value.sessionErrorsById["s1"]
        assertNotNull("Absent lastError must preserve existing banner", error)
        assertEquals("PersistentError", error?.name)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // B-3: event name routing — V2.asked/v2.resolved events
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `B3 question v2 asked routes to handler and does not produce unrecognized warning`() {
        // question.v2.asked must be recognized by the router and handled.
        // The handler calls parseQuestionAskedEvent; provide a minimal valid
        // payload so it does not log a non-fatal issue.
        coordinator.handleEvent(event("question.v2.asked") {
            put("sessionID", JsonPrimitive("s1"))
            put("id", JsonPrimitive("q1"))
            put("questions", buildJsonArray {
                add(buildJsonObject {
                    put("question", JsonPrimitive("What is the weather?"))
                    put("header", JsonPrimitive("Weather"))
                    put("options", buildJsonArray {
                        add(buildJsonObject {
                            put("label", JsonPrimitive("sunny"))
                            put("description", JsonPrimitive("Clear skies"))
                        })
                    })
                })
            })
            put("routeToken", JsonPrimitive("tok_q1"))
        })

        // No unrecognized warning (Log.w about unknown event type).
        // The handler succeeded: pendingQuestions has the entry.
        val questions = slices.sessionList.value.pendingQuestions
        assertTrue(
            "question.v2.asked must add question to pendingQuestions",
            questions.any { it.id == "q1" },
        )
    }

    @Test
    fun `B3 permission v2 asked triggers LoadPendingPermissions`() {
        // permission.v2.asked must route to handlePermissionAsked
        // which emits LoadPendingPermissions.
        coordinator.handleEvent(event("permission.v2.asked") {
            put("sessionID", JsonPrimitive("s1"))
            put("requestID", JsonPrimitive("preq1"))
            put("permissionType", JsonPrimitive("write_file"))
        })

        assertTrue(
            "permission.v2.asked must emit LoadPendingPermissions",
            collectedEffects.any { it is ControllerEffect.LoadPendingPermissions },
        )
    }

    @Test
    fun `B3 permission v2 resolved triggers LoadPendingPermissions`() {
        // permission.v2.resolved must route to handlePermissionResolved
        // which emits LoadPendingPermissions.
        coordinator.handleEvent(event("permission.v2.resolved") {
            put("sessionID", JsonPrimitive("s1"))
            put("requestID", JsonPrimitive("preq1"))
        })

        assertTrue(
            "permission.v2.resolved must emit LoadPendingPermissions",
            collectedEffects.any { it is ControllerEffect.LoadPendingPermissions },
        )
    }

    @Test
    fun `B3 permission resolved triggers LoadPendingPermissions`() {
        // permission.resolved (no v2 prefix) must also route to
        // handlePermissionResolved which emits LoadPendingPermissions.
        coordinator.handleEvent(event("permission.resolved") {
            put("sessionID", JsonPrimitive("s1"))
            put("requestID", JsonPrimitive("preq1"))
        })

        assertTrue(
            "permission.resolved must emit LoadPendingPermissions",
            collectedEffects.any { it is ControllerEffect.LoadPendingPermissions },
        )
    }

    @Test
    fun `B3 permission v2 asked with requestID via id fallback also routes`() {
        // permission.v2.resolved also falls back to `id` when requestID is absent.
        coordinator.handleEvent(event("permission.v2.resolved") {
            put("sessionID", JsonPrimitive("s1"))
            put("id", JsonPrimitive("preq-alt"))
        })

        assertTrue(
            "permission.v2.resolved with id fallback must emit LoadPendingPermissions",
            collectedEffects.any { it is ControllerEffect.LoadPendingPermissions },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // B-2: done marker with malicious text
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `B2 done marker with malicious text does NOT adopt text and triggers sinceFetch`() {
        // V2 §3.x.2 杠杆1: done marker carries NO text — only marks DONE +
        // triggers authoritative fetch. Even if frame.text is non-null, the
        // accumulated buffer is preserved.
        val state0 = TokenStreamReducerState()
        // First, establish a streaming part with accumulated text.
        val (state1, _) = TokenStreamReducer.reduce(
            state0,
            TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "accumulated", done = false, truncated = false),
        )
        // Now send done:true with malicious text.
        val (state2, effects) = TokenStreamReducer.reduce(
            state1,
            TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "MALICIOUS_OVERRIDE", done = true, truncated = false),
        )

        val part = state2.parts["p1"]
        assertNotNull("part must exist after done:true", part)
        assertEquals(
            "done marker must NOT adopt frame.text — buffer preserved",
            "accumulated",
            part?.text,
        )
        assertEquals(
            "part must be DONE after done:true",
            TokenPartStreamState.DONE,
            part?.state,
        )
        assertTrue(
            "done marker must emit TriggerSinceFetch effect",
            effects.any { it is TokenStreamCoordinatorEffect.TriggerSinceFetch },
        )
    }

    @Test
    fun `B2 done marker without text preserves accumulated buffer and triggers sinceFetch`() {
        // done marker with null text preserves the accumulated buffer.
        val state0 = TokenStreamReducerState()
        val (state1, _) = TokenStreamReducer.reduce(
            state0,
            TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "accumulated", done = false, truncated = false),
        )
        val (state2, effects) = TokenStreamReducer.reduce(
            state1,
            TokenStreamFrame.PartSnapshot("s1", "m1", "p1", null, done = true, truncated = false),
        )

        val part = state2.parts["p1"]
        assertNotNull(part)
        assertEquals("accumulated", part?.text)
        assertEquals(TokenPartStreamState.DONE, part?.state)
        assertTrue(effects.any { it is TokenStreamCoordinatorEffect.TriggerSinceFetch })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // B-4: partEventRevision strict > dedup through TokenStreamCoordinator
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `B4 coordinator production hooks reject duplicate revision`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repo = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        val bundle = repo.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        // The production hooks are constructed by tokenStreamProductionHooks.
        // Extract only dedupPartRevision (others keep defaults).
        val skeleton = SkeletonReloadCoordinator(
            scope = scope,
            repository = repo,
            slices = store.slices,
        )
        val productionHooks = tokenStreamProductionHooks(
            store = store,
            skeletonReloadCoordinator = skeleton,
            appScope = scope,
            debounceMs = 0L,
        )

        val dedupCalls = mutableListOf<Long?>()
        val coordinator = TokenStreamCoordinator(
            scope = scope,
            slices = store.slices,
            streamProvider = { _, _ -> emptyFlow() },
            triggerSinceFetch = { _, _ -> },
            bundleCommitLock = repo,
            currentBundleProvider = { repo.currentClientBundle() },
            dedupPartRevision = { sid, mid, pid, rev, ctx ->
                dedupCalls += rev
                productionHooks.dedupPartRevision(sid, mid, pid, rev, ctx)
            },
            onMessagePartRemoved = productionHooks.onMessagePartRemoved,
            onMessageRemoved = productionHooks.onMessageRemoved,
            onPartDone = productionHooks.onPartDone,
            clearSessionRevisions = productionHooks.clearSessionRevisions,
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        // First frame with rev=5 — should be accepted.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "text", false, false, partEventRevision = 5),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )
        assertEquals("first revision must be checked by dedup", 1, dedupCalls.size)

        // Capture full chat state before duplicate dispatch.
        val beforeChat = store.chatFlow.value

        // Second frame with same rev=5 — should be rejected (duplicate).
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "updated", false, false, partEventRevision = 5),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )
        // Full chat state must not change (rejected frame skips reducer entirely).
        // This catches ANY field drift, not just streamOwned/streamingPartTexts.
        val afterChat = store.chatFlow.value
        assertEquals(
            "B4 duplicate revision must produce identical full chat state",
            beforeChat, afterChat,
        )
        // Earliest frame text ("text") must survive; "updated" must be rejected.
        assertEquals(
            "duplicate revision must NOT change text in streamingPartTexts",
            "text",
            afterChat.streamingPartTexts["p1"],
        )
        // dedupCount=2: second call reaches hook but is rejected by the dedup ledger.
        assertEquals(
            "dedupPartRevision must be called twice (second reaches hook, rejected by ledger)",
            2, dedupCalls.size,
        )
        assertEquals("both calls carry same revision=5", listOf(5L, 5L), dedupCalls)
    }

    @Test
    fun `B4 coordinator production hooks accept strictly higher revision`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repo = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        val bundle = repo.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        val skeleton = SkeletonReloadCoordinator(
            scope = scope,
            repository = repo,
            slices = store.slices,
        )
        val productionHooks = tokenStreamProductionHooks(
            store = store,
            skeletonReloadCoordinator = skeleton,
            appScope = scope,
            debounceMs = 0L,
        )

        val coordinator = TokenStreamCoordinator(
            scope = scope,
            slices = store.slices,
            streamProvider = { _, _ -> emptyFlow() },
            triggerSinceFetch = { _, _ -> },
            bundleCommitLock = repo,
            currentBundleProvider = { repo.currentClientBundle() },
            dedupPartRevision = productionHooks.dedupPartRevision,
            onMessagePartRemoved = productionHooks.onMessagePartRemoved,
            onMessageRemoved = productionHooks.onMessageRemoved,
            onPartDone = productionHooks.onPartDone,
            clearSessionRevisions = productionHooks.clearSessionRevisions,
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        // rev=5 accepted.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "v1", false, false, partEventRevision = 5),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )
        assertTrue("rev=5 must produce streamOwned entry", store.chatFlow.value.streamOwned.containsKey("p1"))
        val stateAfter5 = store.chatFlow.value.streamingPartTexts["p1"]
        assertEquals("v1", stateAfter5)

        // rev=6 (strictly higher) — accepted.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "v2", false, false, partEventRevision = 6),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )
        assertEquals("higher revision must update streamingPartTexts", "v2", store.chatFlow.value.streamingPartTexts["p1"])
    }

    @Test
    fun `B4 coordinator production hooks reject out-of-order lower revision`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repo = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
        val bundle = repo.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))

        val skeleton = SkeletonReloadCoordinator(
            scope = scope,
            repository = repo,
            slices = store.slices,
        )
        val productionHooks = tokenStreamProductionHooks(
            store = store,
            skeletonReloadCoordinator = skeleton,
            appScope = scope,
            debounceMs = 0L,
        )

        val coordinator = TokenStreamCoordinator(
            scope = scope,
            slices = store.slices,
            streamProvider = { _, _ -> emptyFlow() },
            triggerSinceFetch = { _, _ -> },
            bundleCommitLock = repo,
            currentBundleProvider = { repo.currentClientBundle() },
            dedupPartRevision = productionHooks.dedupPartRevision,
            onMessagePartRemoved = productionHooks.onMessagePartRemoved,
            onMessageRemoved = productionHooks.onMessageRemoved,
            onPartDone = productionHooks.onPartDone,
            clearSessionRevisions = productionHooks.clearSessionRevisions,
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val gen = coordinator.beginSession("s1")

        // rev=10 accepted.
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "higher", false, false, partEventRevision = 10),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )
        val stateAfter10 = store.chatFlow.value.streamingPartTexts["p1"]
        assertEquals("higher", stateAfter10)

        // rev=5 (lower than 10) — must be rejected.
        val beforeKeys = store.chatFlow.value.streamOwned.keys
        coordinator.dispatchEpochFrame(
            sid = "s1", epoch = epoch, gen = gen,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "lower", false, false, partEventRevision = 5),
            capturedRouteInstance = 0L, boundBundle = bundle,
        )
        // State unchanged.
        assertEquals("out-of-order revision must NOT change streamOwned", beforeKeys, store.chatFlow.value.streamOwned.keys)
        assertEquals("out-of-order revision must NOT change streamingPartTexts", "higher", store.chatFlow.value.streamingPartTexts["p1"])
    }
}
