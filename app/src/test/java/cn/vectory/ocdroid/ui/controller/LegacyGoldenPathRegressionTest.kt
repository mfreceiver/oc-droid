package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.MainDispatcherRule
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.SlimColdStartSnapshot
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * T0 (safety net) — **legacy (`slimMode=false`) golden-path characterization**.
 *
 * Mirrors the [SessionSyncCoordinatorSlimTest] MockK coordinator harness but
 * flips `isSlimMode` to `false`, freezing the CURRENT behaviour of the legacy
 * coordinator path so a future slim-only write that leaks across the mode
 * boundary turns `check.sh` red instead of silently corrupting legacy state.
 *
 * Non-negotiables (see `docs/ocmar/specs/2026-07-22-full-refactor-plan.md`
 * §一(1.3 facts) + `…slim-legacy-isolation-task2.md` §2.1 P0-1~P0-6):
 *  - C3: legacy `/global/event` and slim `/slimapi/events` SSE event sets are
 *    **disjoint**. `message.updated` / `message.part.*` / `text.delta` /
 *    `tool.*` are **legacy-only wire**. Slim has NO token-level streaming.
 *  - Slim-only writes (cold-start snapshot / reconcile merge) must never land
 *    when `slimMode=false`; the structural early-returns are pinned here.
 *
 * Cases map 1:1 to the plan's P0-1~P0-6.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LegacyGoldenPathRegressionTest {

    @get:org.junit.Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var settingsManager: SettingsManager
    private lateinit var scope: TestScope
    private lateinit var repository: OpenCodeRepository

    @Before
    fun setUp() {
        // reportNonFatalIssue runs inline and calls android.util.Log.w; stub the
        // statics so the JVM harness does not throw (mirrors SlimTest setUp).
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0

        val store = SharedStateStore()
        slices = store.slices
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
        scope = TestScope(UnconfinedTestDispatcher())
        repository = mockk(relaxed = true)

        // C-D3 token guard stubs (same as SlimTest) — relaxed mock would
        // default the boolean wrappers to false; stub to true so any path
        // that reaches them is faithful to the slim wiring. These are only
        // hit by slim-only code paths which the legacy guards below short-
        // circuit BEFORE the token is consulted.
        every { repository.isSlimCommitTokenCurrent(any()) } returns true
        every { repository.commitIfSlimTokenCurrent(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** Legacy coordinator: `isSlimMode = { false }`. */
    private fun coordinator(): SessionSyncCoordinator =
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

    private fun event(type: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): SSEEvent =
        SSEEvent(payload = SSEPayload(type = type, properties = buildJsonObject(block)))

    // ── P0-1: session switch preserves message list ────────────────────────

    /**
     * P0-1: in legacy mode, SSE-driven session-list mutations (session.created
     * / session.updated) UPSERT the list and never FULL-REPLACE-wipe the
     * currently-open session's chat messages. Guards the commit 6bf7bf7 class
     * of "list-disappear" regression at the coordinator fold.
     *
     * (The switch→cache→switch-back window restoration itself is owned by
     * [SessionSwitcher] and pinned in [SessionSwitcherTest]
     * `switchTo captures outgoing session window into cache`; this case pins
     * the coordinator-side preservation that makes the restore meaningful.)
     */
    @Test
    fun `P0-1 legacy session-list upsert preserves the current session message list`() {
        // current session A already has a message.
        slices.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(Message(id = "m1", role = "user", sessionId = "session-A")),
                partsByMessage = mapOf("m1" to emptyList()),
            )
        }
        slices.mutateSessionList {
            it.copy(sessions = listOf(Session(id = "session-A", directory = "/tmp/a")))
        }

        val c = coordinator()

        // A session.created for B arrives — list upserts (B prepended), A stays.
        c.handleEvent(event("session.created") {
            put("session", buildJsonObject {
                put("id", JsonPrimitive("session-B"))
                put("directory", JsonPrimitive("/tmp/b"))
                put("title", JsonPrimitive("New"))
            })
        })

        assertEquals(
            "session list upserted (not wiped): B prepended, A retained",
            listOf("session-B", "session-A"),
            slices.sessionList.value.sessions.map { it.id },
        )
        assertEquals(
            "current session A's chat messages survive the session-list mutation",
            listOf("m1"),
            slices.chat.value.messages.map { it.id },
        )

        // A new message for the CURRENT session A inserts (no reload wipe).
        c.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-A"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m2"))
                put("role", JsonPrimitive("assistant"))
            })
        })

        assertEquals(
            "messages preserved + new one appended (legacy patch/insert path)",
            listOf("m1", "m2"),
            slices.chat.value.messages.map { it.id },
        )
    }

    // ── P0-2: legacy SSE message.part.delta streams into streamingPartTexts ─

    /**
     * P0-2: a legacy `message.part.delta` frame feeds the streaming overlay
     * (`streamingPartTexts`). Per C3 this is **legacy-only wire** — slim SSE
     * never carries `message.part.*`; slim content updates go via
     * digest→REST. Anchor: `dispatchSseEvent` `message.part.delta` branch.
     */
    @Test
    fun `P0-2 legacy SSE message part delta streams into streamingPartTexts`() {
        slices.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(Message(id = "m1", role = "assistant", sessionId = "session-A")),
            )
        }

        val c = coordinator()

        // Leading-edge delta writes immediately (zero-latency first token).
        c.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-A"))
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("part-1"))
            put("field", JsonPrimitive("text"))
            put("delta", JsonPrimitive("Hello"))
        })

        assertEquals(
            "leading-edge delta lands in streamingPartTexts immediately",
            "Hello",
            slices.chat.value.streamingPartTexts["part-1"],
        )

        // Trailing deltas coalesce within the 100ms window then flush.
        c.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-A"))
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("part-1"))
            put("delta", JsonPrimitive(", world!"))
        })
        scope.testScheduler.advanceUntilIdle()

        assertEquals(
            "coalesced flush accumulates the trailing delta (legacy streaming)",
            "Hello, world!",
            slices.chat.value.streamingPartTexts["part-1"],
        )
        // The slim omitted-part affordance map is untouched by legacy streaming
        // (cross-check feeding into P0-6's structural separation).
        assertTrue(
            "partExpandStates stays empty during legacy token streaming",
            slices.chat.value.partExpandStates.isEmpty(),
        )
    }

    // ── P0-3: legacy message.updated patches in place ───────────────────────

    /**
     * P0-3: a legacy `message.updated` frame PATCHES an existing message in
     * place — no duplicate, no insert, no list reorder. Anchor:
     * `dispatchSseEvent` `message.updated` patch-if-found branch.
     */
    @Test
    fun `P0-3 legacy message updated patches an existing message in place`() {
        slices.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(
                    Message(id = "m1", role = "assistant", sessionId = "session-A"),
                    Message(id = "m2", role = "user", sessionId = "session-A"),
                ),
            )
        }

        val c = coordinator()

        c.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-A"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m1"))
                put("role", JsonPrimitive("assistant"))
                put("cost", JsonPrimitive(0.5))
            })
        })

        val msgs = slices.chat.value.messages
        assertEquals(
            "list shape unchanged (patched, NOT duplicated/inserted)",
            listOf("m1", "m2"),
            msgs.map { it.id },
        )
        assertEquals(
            "the matched message was patched in place",
            0.5,
            msgs.first { it.id == "m1" }.cost,
        )
    }

    // ── P0-4: legacy reconcileSession early-returns without touching ChatState

    /**
     * P0-4: with `slimMode=false`, [reconcileSession] early-returns
     * [SessionSyncCoordinator.ReconcileResult.NoRepository] (the structural
     * legacy guard at `SessionSyncCoordinator.kt:2401`) WITHOUT probing,
     * fetching, or mutating ChatState. Anchor: the `if (!isSlimMode()) return
     * NoRepository(sid)` early-return.
     */
    @Test
    fun `P0-4 legacy reconcileSession returns NoRepository without touching ChatState`() = runTest {
        val seeded = listOf(
            Message(id = "m1", role = "user", sessionId = "session-A"),
            Message(id = "m2", role = "assistant", sessionId = "session-A"),
        )
        slices.mutateChat {
            it.copy(currentSessionId = "session-A", messages = seeded)
        }

        val c = coordinator()
        val result = c.reconcileSessionExposed("session-A", SessionSyncCoordinator.ReconcileMode.DIGEST_FOCUS)

        assertTrue(
            "legacy reconcile MUST early-return NoRepository",
            result is SessionSyncCoordinator.ReconcileResult.NoRepository,
        )
        // ChatState byte-for-byte unchanged: no probe / fetch / merge landed.
        assertEquals(
            "messages untouched by legacy reconcile",
            seeded,
            slices.chat.value.messages,
        )
        // No slim-side repo probe/fetch was ever issued (early-return is
        // structural, before any repo I/O).
        coVerify(exactly = 0) { repository.probeLatestSlim(any()) }
        verify(exactly = 0) { repository.getSlimSessionState(any()) }
    }

    // ── P0-5: legacy cold-start does NOT rewrite the session list ───────────
    //
    // *** needs-product-decision / EXPECTED-FAILING (pre-P1-1) ***
    //
    // This is the documented T0 "destructive-probe anchor"
    // (plan §4-T0 / task2 §2.1 P0-5). It asserts the DESIRED host-isolation
    // contract: a slim cold-start snapshot landing while `slimMode=false` MUST
    // NOT rewrite `SessionListState.sessions`.
    //
    // CURRENT REALITY: [applySlimColdStartSnapshot] has NO `isSlimMode()`
    // entry guard (only the caller-side slim discipline + the slim commit
    // token). When driven directly with a mock repo whose
    // `commitIfSlimTokenCurrent` runs the fold, the snapshot DOES rewrite the
    // legacy session list — a latent host-isolation leak (the heaviest
    // historical bug class, commit 6bf7bf7 family).
    //
    // Methodology (spec §5.3 ③): host-isolation conflict → do NOT freeze the
    // bug; assert the DESIRED contract as an expected-fail anchor. This test
    // is @Ignore'd to keep `check.sh` GREEN across the T0→T1 window (AGENTS.md
    // hard rule: the tree must be green after every change; a test red-until-T1
    // would pollute every check.sh run in between). The assertions below stay
    // FROZEN (hash-guarded by _priv-verifier) — only activation is deferred.
    //
    // LIFECYCLE: T1's test lane UN-IGNORES this as part of T1's freeze, then
    // impl P1-1 (`check(isSlimMode()) { "cold-start snapshot must not apply in
    // legacy mode" }` at `SessionSyncCoordinator.kt:3354`) turns it GREEN.
    // Removing that guard later MUST turn it RED again — the destructive-probe
    // contract the verifier exercises at the T1 gate.

    @Ignore(
        "T0 destructive-probe anchor: RED pre-P1-1 (host-isolation leak 6bf7bf7 family). " +
            "Kept green via @Ignore across T0->T1 per AGENTS.md check.sh-hard-rule; " +
            "T1 test lane un-ignores, P1-1 guard greens it. Frozen assertions unchanged.",
    )
    @Test
    fun `P0-5 legacy cold-start snapshot MUST NOT rewrite SessionListState sessions (needs-product-decision)`() = runTest {
        val prior = listOf(
            Session(id = "legacy-A", directory = "/A", title = "keep-A"),
            Session(id = "legacy-B", directory = "/B", title = "keep-B"),
        )
        slices.mutateSessionList { it.copy(sessions = prior) }

        // A slim cold-start snapshot covering a different directory arrives
        // while the coordinator is in LEGACY mode.
        val snapshot = SlimColdStartSnapshot(
            sessions = listOf(Session(id = "slim-X", directory = "/slim", title = "slim-only")),
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
            ),
            messages = null,
        )

        val c = coordinator()
        val landed = c.applySlimColdStartSnapshot(snapshot)

        // DESIRED (host-isolation contract): legacy mode rejects the snapshot.
        assertFalse(
            "needs-product-decision: applySlimColdStartSnapshot MUST be a no-op " +
                "(return false) in legacy mode — currently returns $landed",
            landed,
        )
        assertEquals(
            "needs-product-decision: legacy session list MUST NOT be rewritten by " +
                "a slim cold-start snapshot (P1-1 guard missing today)",
            prior,
            slices.sessionList.value.sessions,
        )
    }

    // ── P0-6: legacy tool/reasoning fold uses expandedParts, not partExpandStates

    /**
     * P0-6: in legacy mode the tool/reasoning-card fold lives in
     * `StoreState.expandedParts` (the legacy fold map, fed via
     * ComposerController / `onToggleExpand`). The slim omitted-part
     * affordance lives in the SEPARATE `ChatState.partExpandStates` map and
     * MUST stay empty in legacy mode (no slim skeleton parts ever materialize,
     * so no expand affordance is created).
     *
     * Anchor: `AppStateSlices.kt:406-410` ("SEPARATE") + `StoreState.kt:40`
     * (`expandedParts`). This test pins the structural separation from the
     * coordinator side: legacy token streaming populates `streamingPartTexts`
     * (the shared real-time layer) WITHOUT touching `partExpandStates`.
     */
    @Test
    fun `P0-6 legacy fold map is expandedParts and partExpandStates stays empty`() {
        // Structural separation: the two fold maps live on DIFFERENT state
        // classes (StoreState.expandedParts: Map<String,Boolean> — the legacy
        // tool/reasoning/sub-agent/patch fold; ChatState.partExpandStates:
        // Map<PartKey, PartExpandState> — the slim omitted-part affordance).
        // AppStateSlices.kt:406-410 self-documents them as "SEPARATE".
        val store = slices.store

        // Drive a legacy streaming frame; the slim expand map must not move.
        slices.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(Message(id = "m1", role = "assistant", sessionId = "session-A")),
            )
        }
        // Pre-seed the legacy fold map (tool-call card expanded by the user).
        store.mutateExpandedParts { it + ("fold:m1:part-1" to true) }

        val c = coordinator()
        c.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-A"))
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("part-1"))
            put("field", JsonPrimitive("text"))
            put("delta", JsonPrimitive("streaming"))
        })
        scope.testScheduler.advanceUntilIdle()

        assertTrue(
            "streamingPartTexts populated by legacy streaming (shared real-time layer)",
            slices.chat.value.streamingPartTexts.containsKey("part-1"),
        )
        assertTrue(
            "partExpandStates (slim omitted-part affordance) stays EMPTY in legacy mode",
            slices.chat.value.partExpandStates.isEmpty(),
        )
        assertEquals(
            "legacy fold state lives in expandedParts and survives legacy streaming",
            mapOf("fold:m1:part-1" to true),
            store.expandedParts.value,
        )
    }
}
