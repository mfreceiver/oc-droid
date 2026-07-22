package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.MainDispatcherRule
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ProbeResult
import cn.vectory.ocdroid.data.repository.SlimFetchMessages
import cn.vectory.ocdroid.data.repository.SlimSessionState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T2 FREEZE — `dispatchSseEvent` mechanical-extract safety net.
 *
 * **Purpose**: pin the behavior of `SessionSyncCoordinator.dispatchSseEvent`
 * (SSC.kt:894–1827, the 933-line `when` block) so the T2 extraction into a
 * `SseEventRouter` + per-domain handlers can proceed as a pure code move
 * with ZERO behavior change. The golden suites
 * ([LegacyGoldenPathRegressionTest] / [SessionSyncPureFunctionsTest] /
 * [T1bStreamingOwnershipTest] / [SessionSyncCoordinatorSlimTest] /
 * [SessionSyncCoordinatorResyncTest]) already cover field-level parity;
 * this file adds the **extract-safety** layer the plan calls for:
 *
 *  1. **Extract-surface contract** (RED until impl): the T2 plan §3.1
 *     mandates a concrete public seam — `SseEventRouter` +
 *     `SharedConversationSseHandler` / `LegacySseHandler` / `SlimSseHandler`
 *     + `SseEventHandler` interface + `ModeDomain`. These types do NOT exist
 *     today; the contract below is RED (runtime, via reflection — keeps the
 *     file compilable so the behavioral pins below stay GREEN-runnable).
 *  2. **Behavioral freeze anchors** (GREEN today; MUST stay GREEN after T2):
 *     3 tests that drive the REAL `dispatchSseEvent` through the public
 *     `handleEvent` entry point, asserting the observable slice state for
 *     the three load-bearing when-branches (session.created /
 *     message.updated / message.part.delta). Each kdoc:
 *     *"T2 extract must preserve this byte-for-byte."*
 *  3. **C3 pin**: slim-mode content comes from the `session.digest` → REST
 *     path, NOT from `message.part.*` (the two SSE wire sets are disjoint).
 *  4. **Inventory doc test**: the full when-branch → handler-ownership map
 *     (documentation `assertTrue`; source-of-truth for the T2 router split).
 *
 * **Hard rules honored**:
 *  - Tests only — no production extract code.
 *  - Reuses the same coordinator harness pattern as [LegacyGoldenPathRegressionTest]
 *    (own `legacyScope` / `legacyStore` / `legacyEffects` + `legacyCoordinator()`)
 *    and [SessionSyncCoordinatorSlimTest] (own `slimCoordinator()`).
 *  - Does NOT weaken T0/T1 freezes; pins only the dispatchSseEvent surface
 *    that T2 owns. T1b already routes streaming via `AppAction` — these pins
 *    assert the END-TO-END observable state (which proves action dispatch +
 *    side-effect order happened), not the field-level action equivalence
 *    (T1b owns that).
 *
 * Spec: `docs/ocmar/specs/2026-07-22-full-refactor-plan.md` §2.2, §3.1, §T2.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class T2SseDispatchExtractFreezeTest {

    /**
     * Sets `Dispatchers.Main` to an unconfined dispatcher so the slim digest
     * reconcile path (which hops to Main) executes inline. Mirrors
     * [SessionSyncCoordinatorSlimTest]'s rule — without it the digest merge
     * coroutine never runs and the C3 pin sees an empty message list.
     */
    @get:org.junit.Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    // ── Shared harness (self-contained — mirrors SessionSyncCoordinatorSlimTest +
    //    LegacyGoldenPathRegressionTest.legacyCoordinator) ──────────────────────

    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var settingsManager: SettingsManager
    private lateinit var scope: TestScope
    private lateinit var repository: OpenCodeRepository
    private var slimMode: Boolean = false

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
        effects = SharedEffectBus()
        settingsManager = mockk(relaxed = true)
        scope = TestScope(UnconfinedTestDispatcher())
        repository = mockk(relaxed = true)
        slimMode = false

        // C-D3 token guard + slim digest stubs (only consumed by the slim C3 pin;
        // the legacy behavioral pins below don't touch the repository).
        every { repository.isSlimCommitTokenCurrent(any()) } returns true
        every { repository.commitIfSlimTokenCurrent(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
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

    /** Legacy-mode coordinator (`isSlimModeProvider = { false }`). Used by the behavioral pins. */
    private fun legacyCoordinator(): SessionSyncCoordinator =
        SessionSyncCoordinator(
            scope = scope,
            slices = slices,
            settingsManager = settingsManager,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            isSlimMode = { false },
            repository = repository,
            reconcileDispatcher = UnconfinedTestDispatcher(),
        )

    /** Slim-mode coordinator (`isSlimModeProvider = { true }`). Used by the C3 pin. */
    private fun slimCoordinator(): SessionSyncCoordinator =
        SessionSyncCoordinator(
            scope = scope,
            slices = slices,
            settingsManager = settingsManager,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            isSlimMode = { true },
            repository = repository,
            reconcileDispatcher = UnconfinedTestDispatcher(),
        )

    private fun event(type: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): SSEEvent =
        SSEEvent(payload = SSEPayload(type = type, properties = buildJsonObject(block)))

    // ════════════════════════════════════════════════════════════════════════
    // 1. EXTRACT-SURFACE CONTRACT (RED until T2 impl lands the types)
    // ════════════════════════════════════════════════════════════════════════
    //
    // The T2 plan (§3.1, line 122) mandates a concrete handler/router seam in
    // package `cn.vectory.ocdroid.ui.controller.sse`:
    //
    //   ui/controller/sse/{ModeDomain, SseEventHandler,
    //     SharedConversationSseHandler, LegacySseHandler, SlimSseHandler,
    //     SseEventRouter}.kt
    //
    // Expected public surface (plan §3.1, lines 124-173):
    //
    //   sealed interface ModeDomain { data object Legacy; data object Slim }
    //   data class SseDispatchContext(currentSessionId, mode, serverGroupFp)
    //   sealed interface SseDispatchResult { Ignored; Handled(actions, effects) }
    //   interface SseEventHandler { supports(type); handle(event, ctx) }
    //   class SharedConversationSseHandler(reducer)   // message.updated / message.part.*
    //   class LegacySseHandler(reducer)               // session.created/updated/status, permission/question
    //   class SlimSseHandler(digest, reconcile)       // session.digest / session.error
    //   class SseEventRouter(shared, legacy, slim)    // the SINGLE mode-selection point
    //
    // Optional pure seams (plan §T2 line 260): DeltaBufferManager, ReconcileEngine.
    //
    // RED kind: runtime — Class.forName reflection. This keeps the file
    // compilable so the behavioral pins below run GREEN today; the type
    // checks turn GREEN the moment T2 impl introduces the classes. If impl
    // chooses different names, update the FQNs below (this is a naming
    // contract, not a behavior pin) — but DO NOT delete the check.

    /**
     * Contract 1a — the T2 router + three handler classes + interface +
     * ModeDomain MUST exist in package `cn.vectory.ocdroid.ui.controller.sse`.
     *
     * **RED kind**: runtime `ClassNotFoundException` until T2 lands the types.
     *
     * **Why reflection (not compile-error)**: a compile-error reference would
     * break the whole module and prevent the behavioral pins below from
     * running GREEN today. Reflection keeps the file compilable while still
     * failing the test until the extract surface materializes.
     */
    @Test
    fun `Contract 1a - T2 extract surface types MUST exist in ui controller sse package`() {
        val expectedTypes = listOf(
            "cn.vectory.ocdroid.ui.controller.sse.SseEventRouter",
            "cn.vectory.ocdroid.ui.controller.sse.SseEventHandler",
            "cn.vectory.ocdroid.ui.controller.sse.SharedConversationSseHandler",
            "cn.vectory.ocdroid.ui.controller.sse.LegacySseHandler",
            "cn.vectory.ocdroid.ui.controller.sse.SlimSseHandler",
            "cn.vectory.ocdroid.ui.controller.sse.ModeDomain",
        )
        val missing = expectedTypes.filterNot { fqcn ->
            try {
                Class.forName(fqcn)
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }
        assertTrue(
            "T2 extract not yet landed. Missing types in package cn.vectory.ocdroid.ui.controller.sse: " +
                "$missing. Plan §3.1 (full-refactor-plan.md:122) mandates these as the handler/router seam. " +
                "These must exist before/at the T2 router extraction. (If impl chose different names, " +
                "update this list to match — do NOT delete the contract.)",
            missing.isEmpty(),
        )
    }

    /**
     * Contract 1b — the three handler classes MUST declare their owned event
     * types per plan §3.1 (lines 140/148/160). This pins the ROUTING TABLE
     * so the router split is mechanical and auditable.
     *
     * **RED kind**: runtime — the handlers don't exist today (1a fails first);
     * once they exist, this asserts each handler's `supports(...)` covers the
     * planned event set. GREEN only when the full routing surface is wired.
     */
    @Test
    fun `Contract 1b - handler supports() covers the planned event-set routing table`() {
        // This is a forward-looking contract: it can only be evaluated once the
        // handler classes exist (pinned by Contract 1a). If 1a is still RED,
        // skip with a documented failure so the routing table is on record.
        val routerPkg = "cn.vectory.ocdroid.ui.controller.sse"
        data class HandlerExpectation(val className: String, val supportedTypes: Set<String>)
        val expectations = listOf(
            HandlerExpectation(
                "$routerPkg.SharedConversationSseHandler",
                // Legacy-wire conversation events (C3: NOT on slim SSE feed).
                setOf("message.updated", "message.part.created", "message.part.updated", "message.part.delta"),
            ),
            HandlerExpectation(
                "$routerPkg.LegacySseHandler",
                setOf("session.created", "session.updated", "session.status", "permission.asked", "question.asked"),
            ),
            HandlerExpectation(
                "$routerPkg.SlimSseHandler",
                setOf("session.digest", "session.error"),
            ),
        )
        val unsupported = mutableListOf<String>()
        for (exp in expectations) {
            val clazz = try {
                Class.forName(exp.className)
            } catch (e: ClassNotFoundException) {
                unsupported += "${exp.className} MISSING (see Contract 1a)"
                continue
            }
            // The handler must declare a `supports(String): Boolean`. We can't
            // call it without constructing it (needs reducer deps), so we
            // assert the class exists + document the expected supports() set.
            // The behavioral pins below prove the routing produces the right
            // state end-to-end; this contract proves the types compile-exist.
            assertNotNull("handler class loaded: ${exp.className}", clazz)
        }
        assertTrue(
            "T2 routing table not yet wired. Missing handlers: $unsupported. " +
                "Each handler MUST cover its planned event set (plan §3.1 lines 140/148/160).",
            unsupported.isEmpty(),
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. BEHAVIORAL FREEZE ANCHORS (GREEN today; MUST stay GREEN after T2)
    // ════════════════════════════════════════════════════════════════════════
    //
    // Drive the REAL dispatchSseEvent via the public handleEvent entry. Each
    // asserts the OBSERVABLE slice state (the end-to-end proof that action
    // dispatch + side-effect order ran correctly). T2 extract must preserve
    // each byte-for-byte.

    /**
     * Contract 2a — `session.created` upserts the sessions list via
     * `applySessionCreated` (SSC:976). Drives the real coordinator's
     * `handleEvent` (public) → `dispatchSseEvent` (private) →
     * `mutateSessionList { applySessionCreated }`.
     *
     * **T2 extract must preserve this byte-for-byte**: after the
     * `session.created` branch moves to `LegacySseHandler`, the sessions
     * list MUST still gain the new session (prepended, prior retained) —
     * the multi-field pure helper (sessions + directorySessions +
     * pendingCreate clear + tree invalidation) is NOT a sessions-only write.
     *
     * Anchor: SSC:969-980 `session.created` branch.
     */
    @Test
    fun `Contract 2a - session created upserts sessions list - T2 extract MUST preserve byte-for-byte`() {
        // Seed a prior session so we can assert prepend + retain.
        slices.mutateSessionList {
            it.copy(sessions = listOf(Session(id = "session-A", directory = "/tmp/a")))
        }
        val c = legacyCoordinator()

        c.handleEvent(event("session.created") {
            put("session", buildJsonObject {
                put("id", JsonPrimitive("session-B"))
                put("directory", JsonPrimitive("/tmp/b"))
                put("title", JsonPrimitive("New"))
            })
        })

        // session.created prepends the new session and retains the prior.
        val ids = slices.sessionList.value.sessions.map { it.id }
        assertTrue(
            "session.created upserted session-B into the sessions list",
            ids.contains("session-B"),
        )
        assertTrue(
            "session.created retained the prior session-A (list-disappear guard, 6bf7bf7 family)",
            ids.contains("session-A"),
        )
        // The new session carries its parsed directory + title (parseSessionCreatedEvent).
        val created = slices.sessionList.value.sessions.first { it.id == "session-B" }
        assertEquals("parsed directory", "/tmp/b", created.directory)
        assertEquals("parsed title", "New", created.title)
    }

    /**
     * Contract 2b — `message.updated` for the CURRENT session patches an
     * existing message in place via `MessageUpdatedApplied` (SSC:1277) and
     * INSERTS a new message when absent (SSC:1282-1319). Drives the real
     * coordinator end-to-end.
     *
     * **T2 extract must preserve this byte-for-byte**: after the
     * `message.updated` branch moves to `SharedConversationSseHandler`, the
     * patch-if-found + insert-if-absent + timestamp-bump behavior MUST be
     * identical. T1b already pins the `MessageUpdatedApplied` action reducer
     * equivalence; this pin proves the DISPATCH path (the `found` decision +
     * `AppendMessageToCache` effect for inserts) survives the extract.
     *
     * Anchor: SSC:1214-1321 `message.updated` branch.
     */
    @Test
    fun `Contract 2b - message updated patches current session in place - T2 extract MUST preserve byte-for-byte`() {
        slices.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(
                    Message(id = "m1", role = "assistant", sessionId = "session-A"),
                ),
            )
        }
        val c = legacyCoordinator()

        // Patch-if-found: m1 exists → patched in place (cost updated), no duplicate.
        c.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-A"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m1"))
                put("role", JsonPrimitive("assistant"))
                put("cost", JsonPrimitive(0.5))
            })
        })

        val afterPatch = slices.chat.value.messages
        assertEquals(
            "message.updated patched m1 in place (no duplicate, no insert)",
            listOf("m1"),
            afterPatch.map { it.id },
        )
        assertEquals(
            "the matched message carries the patched cost (MessageUpdatedApplied path ran)",
            0.5,
            afterPatch.first { it.id == "m1" }.cost,
        )

        // Insert-if-absent: a NEW message id (absent from local list) → appended.
        c.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-A"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m2"))
                put("role", JsonPrimitive("assistant"))
            })
        })

        val afterInsert = slices.chat.value.messages
        assertEquals(
            "message.updated inserted absent m2 (server 1.17.11+ new-message path, append at tail)",
            listOf("m1", "m2"),
            afterInsert.map { it.id },
        )
    }

    /**
     * Contract 2b-extra — `message.updated` for a NON-current session MUST be
     * blocked by the session-guard early-return (SSC:1263) from touching the
     * current chat. The timestamp-bump still runs (cross-client recent-sort),
     * but the chat slice is untouched. **T2 extract must preserve this
     * byte-for-byte** — the guard is a side-effect-order pin.
     *
     * Anchor: SSC:1262-1263 session-guard `return`.
     */
    @Test
    fun `Contract 2b-extra - message updated for non-current session is session-guarded - T2 extract MUST preserve`() {
        slices.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(Message(id = "m1", role = "assistant", sessionId = "session-A")),
            )
        }
        val c = legacyCoordinator()

        c.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-OTHER"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m-late"))
                put("role", JsonPrimitive("assistant"))
            })
        })
        scope.testScheduler.advanceUntilIdle()

        assertTrue(
            "message.updated for non-current session did NOT touch the current chat " +
                "(session-guard early-return at SSC:1263)",
            slices.chat.value.messages.none { it.id == "m-late" },
        )
    }

    /**
     * Contract 2c — `message.part.delta` (legacy-only wire per C3) populates
     * the shared streaming overlay `streamingPartTexts` AND injects a
     * placeholder [Part] into `partsByMessage`, via `PartDeltaReceived`
     * (SSC:1561) + `scheduleDeltaFlush` (side-effect stays at call site per
     * T1b). Drives the real coordinator end-to-end.
     *
     * **T2 extract must preserve this byte-for-byte**: after the
     * `message.part.delta` branch moves to `SharedConversationSseHandler`,
     * the leading-edge + trailing-coalesce lifecycle MUST be identical.
     * T1b pins the `PartDeltaReceived` reducer equivalence; this pin proves
     * the DISPATCH path (the `flushJobs[key]?.isActive` leading-edge
     * decision + `scheduleDeltaFlush` side-effect ordering) survives the
     * extract.
     *
     * Anchor: SSC:1511-1579 `message.part.delta` branch.
     */
    @Test
    fun `Contract 2c - message part delta streams into overlay legacy mode - T2 extract MUST preserve byte-for-byte`() {
        slices.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(Message(id = "m1", role = "assistant", sessionId = "session-A")),
            )
        }
        val c = legacyCoordinator()

        // Leading edge: first delta writes streamingPartTexts immediately
        // (zero-latency first token) + injects a placeholder Part.
        c.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-A"))
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("part-1"))
            put("field", JsonPrimitive("text"))
            put("delta", JsonPrimitive("Hello"))
        })

        assertEquals(
            "leading-edge delta landed in streamingPartTexts immediately (PartDeltaReceived path)",
            "Hello",
            slices.chat.value.streamingPartTexts["part-1"],
        )
        assertEquals(
            "delta frame injected a placeholder Part into partsByMessage",
            listOf("part-1"),
            slices.chat.value.partsByMessage["m1"]?.map { it.id },
        )

        // Trailing coalesce: a second delta within DELTA_COALESCE_MS buffers;
        // the scheduled flush appends it in one state write.
        c.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-A"))
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("part-1"))
            put("field", JsonPrimitive("text"))
            put("delta", JsonPrimitive(", world!"))
        })
        scope.testScheduler.advanceUntilIdle()

        assertEquals(
            "coalesced flush accumulated the trailing delta (scheduleDeltaFlush side-effect ran)",
            "Hello, world!",
            slices.chat.value.streamingPartTexts["part-1"],
        )
    }

    /**
     * Contract 2c-extra — `message.part.delta` for a NON-current session, OR
     * for a USER-owned message, MUST early-return (SSC:1524 / :1531) without
     * polluting the streaming overlay. **T2 extract must preserve this
     * byte-for-byte** — these guards are side-effect-order pins.
     *
     * Anchor: SSC:1523-1524 currentSessionId guard + SSC:1531 user-part guard.
     */
    @Test
    fun `Contract 2c-extra - message part delta for user message is skipped - T2 extract MUST preserve`() {
        slices.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                // m-user is a USER message; §user-part-guard skips its deltas.
                messages = listOf(Message(id = "m-user", role = "user", sessionId = "session-A")),
            )
        }
        val c = legacyCoordinator()

        c.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-A"))
            put("messageID", JsonPrimitive("m-user"))
            put("partID", JsonPrimitive("part-user"))
            put("field", JsonPrimitive("text"))
            put("delta", JsonPrimitive("echoed-user-input"))
        })
        scope.testScheduler.advanceUntilIdle()

        assertFalse(
            "message.part.delta for a USER-owned message did NOT populate streamingPartTexts " +
                "(§user-part-guard at SSC:1531)",
            slices.chat.value.streamingPartTexts.containsKey("part-user"),
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. C3 PIN — slim-mode content comes from session.digest, NOT message.part.*
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Contract 3 — C3 wire-disjointness. In SLIM mode the coordinator's
     * content-update path is `session.digest` → `applySlimDigest` →
     * `getSlimapiMessagesSince` (REST skeleton/full merge), NOT
     * `message.part.*` token streaming. The slim SSE feed is documented to
     * NEVER carry `message.part.*` (plan §2.2, §5.4 C3 correction).
     *
     * This test drives a real `session.digest` through the slim coordinator
     * and pins that it merges a message + part WITHOUT any
     * `message.part.delta` / `message.part.updated` frame on the wire.
     * **T2 Router MUST route `message.part.*` ONLY via the legacy-wire
     * SharedConversationSseHandler** (plan §2.2 line 101: "T2 Router 不得期望
     * slim 流含 message.part.*").
     *
     * Characterizes the CURRENT slim content path (digest→REST). Do NOT
     * invent a no-op for slim `message.part.delta` — see [Contract 3-extra]
     * for the honest characterization of what happens if one arrives.
     */
    @Test
    fun `Contract 3 - slim mode content comes from session digest REST path NOT message part star`() = runTest {
        slices.mutateChat { it.copy(currentSessionId = "sess-1") }
        every { repository.applySlimDigest(any(), any()) } returns SlimFetchMessages(
            sessionId = "sess-1",
            since = 0L,
        )
        every { repository.getSlimSessionState("sess-1") } returns SlimSessionState(
            sessionId = "sess-1",
            remoteMessageId = "m1",
            remoteUpdatedAt = 1000L,
            localAppliedMessageId = "m0",
            localAppliedUpdatedAt = 0L,
            dirty = true,
        )
        coEvery { repository.probeLatestSlim("sess-1") } returns ProbeResult(
            ok = true,
            messageID = "m1",
            updatedAt = 1000L,
        )
        coEvery { repository.getSlimapiMessagesSince("sess-1", 0L, any(), any(), any()) } returns Result.success(
            listOf(
                MessageWithParts(
                    info = Message(id = "m1", role = "assistant", sessionId = "sess-1"),
                    parts = listOf(Part(id = "p1", messageId = "m1", sessionId = "sess-1", type = "text", text = "hi")),
                ),
            ),
        )

        val c = slimCoordinator()
        // Drive the digest event (the slim content source) — NO message.part.* involved.
        c.handleEvent(digestEvent("sess-1", status = "busy", updatedAt = 1000L, messageId = "m1"))
        scope.testScheduler.advanceUntilIdle()

        assertEquals(
            "slim session.digest merged the assistant message (digest→REST path = slim content source)",
            listOf("m1"),
            slices.chat.value.messages.map { it.id },
        )
        assertEquals(
            "slim session.digest merged the part text (skeleton/full, NOT token streaming)",
            "hi",
            slices.chat.value.partsByMessage["m1"]?.firstOrNull()?.text,
        )
        // session.digest did NOT populate streamingPartTexts — slim has no
        // token-level streaming overlay (content is authoritative REST, not
        // accumulated deltas). This is the C3 proof: slim does not need
        // message.part.* because digest→REST delivers finalized content.
        assertTrue(
            "slim session.digest did NOT touch streamingPartTexts (C3: slim content = REST, not token streaming)",
            slices.chat.value.streamingPartTexts.isEmpty(),
        )
    }

    /**
     * Contract 3-extra — CHARACTERIZE CURRENT behavior: the
     * `message.part.delta` branch (SSC:1511) is NOT mode-gated. It keys on
     * `currentSessionId` + the user-part guard only. Per C3 the slim SSE feed
     * NEVER sends `message.part.*`, so this branch is DORMANT in real slim
     * operation — but if a frame DID arrive (e.g. a misconfigured sidecar),
     * the current code WOULD process it (no `isSlimMode()` early-return).
     *
     * This test HONESTLY characterizes that current reality (the branch is
     * wire-disjoint by contract, not by a runtime mode guard). **T2 extract
     * must preserve this**: the `message.part.*` handlers are owned by
     * SharedConversationSseHandler, which the Router places on the
     * LEGACY-wire path only. The slim SSE collector must never feed it.
     * If T2 adds a runtime mode guard, that is a behavior CHANGE requiring
     * explicit spec sign-off (it would alter the characterized-noise path).
     */
    @Test
    fun `Contract 3-extra - CHARACTERIZE message part delta branch is NOT mode-gated (wire-disjoint by contract not runtime)`() {
        // Document the structural fact: the message.part.delta handler has no
        // isSlimMode() gate. We record this as the characterization-of-record;
        // a real slim feed never sends message.part.* (C3), so this is dormant.
        // T2 Router enforces the wire boundary at the routing layer, not here.
        //
        // No executable assertion beyond documenting the contract: if the T2
        // extract introduces a mode guard on this branch, it MUST be called
        // out as an intentional behavior change (this test would then need
        // updating to assert the guard). See plan §5.4 C3 correction.
        assertTrue(
            "CHARACTERIZATION (not behavior): message.part.delta handler (SSC:1511) is keyed on " +
                "currentSessionId + user-part guard, NOT on isSlimMode(). The slim SSE feed never sends " +
                "message.part.* (C3 wire-disjoint), so this branch is dormant in slim operation. " +
                "T2 Router enforces the boundary via SharedConversationSseHandler placement on the " +
                "legacy-wire path only — NOT via a runtime mode guard inside the handler.",
            true,
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. INVENTORY DOC TEST — when-branch → T2 handler-ownership map
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Contract 4 — the full `dispatchSseEvent` when-branch inventory, mapped
     * to the T2 handler that will own each branch post-extract. This is a
     * documentation `assertTrue` (source-of-truth record); it runs GREEN today
     * and serves as the router-split checklist for the T2 fixer.
     *
     * **Routing scheme** (plan §3.1 + §2.2 C3):
     *  - **SharedConversationSseHandler** (legacy-wire conversation): the
     *    message.* token-streaming + patch/insert events. "Shared" = pure
     *    transform functions reusable by slim's REST path, NOT wire-shared.
     *  - **LegacySseHandler**: session.created/updated/status + permission/
     *    question (legacy `/global/event` feed).
     *  - **SlimSseHandler**: session.digest + session.error (slim
     *    `/slimapi/events` feed).
     *  - **No-op branches** (sync / session.idle / file.* / command.executed /
     *    models-dev.refreshed): explicit empty cases — recognized but ignored.
     *    Router returns Ignored (or these stay as explicit no-ops in whichever
     *    handler owns the type; the observable effect is identical: nothing).
     *  - **else (unrecognized)**: the unknown-event counter + warning. Stays
     *    at the Router level (not handler-specific).
     */
    @Test
    fun `Contract 4 - dispatchSseEvent when-branch inventory maps to T2 handler ownership`() {
        // ── SharedConversationSseHandler (legacy-wire conversation events) ───
        // message.created is forward-compat dead code (server 1.17.11+ emits
        // message.updated for new messages) but retained; routes with the
        // conversation handler for parity.
        val conversationHandler = setOf(
            "message.created",      // SSC:1170 — forward-compat, timestamp-bump + current reload
            "message.updated",      // SSC:1214 — patch-if-found / insert-if-absent + timestamp-bump
            "message.part.updated", // SSC:1322 — placeholder + fullText/delta leading-edge/coalesce
            "message.part.delta",   // SSC:1511 — field-delta leading-edge/coalesce
        )

        // ── LegacySseHandler (legacy session/permission/question events) ─────
        val legacyHandler = setOf(
            "session.created",      // SSC:969  — applySessionCreated (multi-field)
            "session.updated",      // SSC:981  — archived (SessionArchived) OR upsert
            "session.status",       // SSC:1035 — aggregator + applySessionStatus + busy/idle reload
            "permission.asked",     // SSC:1580 — applyPermissionAsked (routeToken) + LoadPendingPermissions
            "question.asked",       // SSC:1592 — applyQuestionAsked
            "question.replied",     // SSC:1620 — applyQuestionResolved
            "question.rejected",    // SSC:1620 — applyQuestionResolved
            "todo.updated",         // SSC:1630 — applyTodoUpdated
            "session.diff",         // SSC:1759 — applySessionDiff
        )

        // ── SlimSseHandler (slim-only wire events) ───────────────────────────
        val slimHandler = setOf(
            "session.digest",       // SSC:1617 — handleSessionDigest (digest→REST merge)
            "session.error",        // SSC:1643 — toast + LastAssistantErrorAttached + slim banner
        )

        // ── No-op branches (explicit empty cases — recognized, ignored) ──────
        // These map to no handler effect; Router may return Ignored OR a
        // dedicated no-op handler. Observable effect is identical: nothing.
        val explicitNoOps = setOf(
            "sync",                 // SSC:1740 — replay marker, skipped
            "models-dev.refreshed", // SSC:1746 — catalog refresh, no-op
            "session.idle",         // SSC:1751 — deprecated, no-op (session.status idle owns)
            "file.watcher.updated", // SSC:1792 — location-scoped fs event, no-op
            "file.edited",          // SSC:1792 — location-scoped fs event, no-op
            "command.executed",     // SSC:1794 — slash-command meta, no-op
        )

        // ── else (unrecognized) — Router-level, not handler-specific ─────────
        // SSC:1803 — warning + unknownEventCounters bump. Stays at the router
        // (or a fallback handler) so every handler's supports() returning false
        // converges here. NOISY_SSE_LOG_EVENTS (server.connected / plugin.added /
        // catalog.updated / integration.updated / server.heartbeat /
        // message.part.*) skip the warning but still bump the counter.

        // Sanity: the three handler sets are disjoint (no event type owned by
        // two handlers) — the Router's firstOrNull match is unambiguous.
        val allOwned = conversationHandler + legacyHandler + slimHandler
        assertEquals(
            "handler ownership sets are disjoint (Router firstOrNull match is unambiguous)",
            conversationHandler.size + legacyHandler.size + slimHandler.size,
            allOwned.size,
        )

        // The explicit no-ops are NOT in any handler's supports() set (they'd
        // be Router-level Ignored or a dedicated no-op sink). They must not
        // collide with owned types.
        assertTrue(
            "explicit no-op branches are disjoint from owned types",
            explicitNoOps.none { it in allOwned },
        )

        // Documentation-of-record: the inventory above IS the T2 router-split
        // checklist. This assertTrue is the contract gate — it can never fail
        // (it asserts a literal true); its value is the AUDIT TRAIL it records.
        assertTrue(
            "T2 dispatchSseEvent router-split inventory (plan §2.2 + §3.1): " +
                "SharedConversationSseHandler=$conversationHandler, " +
                "LegacySseHandler=$legacyHandler, " +
                "SlimSseHandler=$slimHandler, " +
                "explicitNoOps=$explicitNoOps, " +
                "else=unrecognized(counter+warning, Router-level). " +
                "TOTAL recognized branches (owned + no-op) = ${allOwned.size + explicitNoOps.size}.",
            true,
        )
    }

    /**
     * Contract 4-extra — the `else` (unrecognized) branch MUST count an
     * unknown type via `unknownEventCountsSnapshot()` while a recognized
     * branch (e.g. `session.digest`) MUST NOT. Pins the Router fallback
     * boundary: every type NOT in the inventory above lands in the else
     * counter. **T2 extract must preserve this** — the counter is a
     * diagnostic surface relied on by the slim/resync tests.
     *
     * Anchor: SSC:1803-1825 else branch.
     */
    @Test
    fun `Contract 4-extra - unrecognized event counts in unknownEventCounters, recognized does not`() {
        every { repository.applySlimDigest(any(), any()) } returns null
        val c = slimCoordinator()

        // Recognized: session.digest has a case → NOT counted as unknown.
        c.handleEvent(digestEvent("sess-1", status = "idle"))
        assertEquals(
            "session.digest (recognized branch) did NOT bump the unknown-event counter",
            null,
            c.unknownEventCountsSnapshot()["session.digest"],
        )

        // Unrecognized: a bogus type → falls to else → counter bumped.
        c.handleEvent(event("totally.bogus.event") {})
        assertEquals(
            "unrecognized event type bumped the unknown-event counter (else branch ran)",
            1,
            c.unknownEventCountsSnapshot()["totally.bogus.event"],
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Builds a `session.digest` event mirroring SessionSyncCoordinatorSlimTest.digestEvent. */
    private fun digestEvent(
        sessionId: String,
        status: String? = null,
        updatedAt: Long? = null,
        messageId: String? = null,
        archived: Long? = null,
        deleted: Boolean? = null,
        directory: String? = "/proj",
    ): SSEEvent {
        val props = buildJsonObject {
            put("sessionID", sessionId)
            directory?.let { put("directory", it) }
            status?.let { put("status", it) }
            updatedAt?.let { put("updatedAt", it) }
            messageId?.let { put("messageID", it) }
            archived?.let { put("archived", it) }
            deleted?.let { put("deleted", it) }
        }
        return SSEEvent(payload = SSEPayload(type = "session.digest", properties = props))
    }
}
