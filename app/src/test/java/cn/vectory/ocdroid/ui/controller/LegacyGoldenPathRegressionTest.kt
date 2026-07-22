package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.MainDispatcherRule
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.HostProfileStore
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
import org.junit.Assert.assertNotNull
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

    // ── P0-1: A→B→A session switch round-trip preserves session A's chat ────

    /**
     * P0-1: in legacy mode, a REAL A→B→A session-switch round-trip preserves
     * session A's chat messages across the switch. spec §2.1 P0-1 requires
     * "A 有消息 → 切到 B → 切回 A，A 消息存活（不被 B 的 cold-start 快照覆盖）".
     *
     * This test drives the real [SessionSyncCoordinator] (for the SSE layer)
     * AND the real [SessionSwitcher] (for the LRU capture + chat-clear),
     * sharing one [SharedStateStore] / [SharedEffectBus] / [SettingsManager]
     * / [OpenCodeRepository]. The three preservation halves pinned here:
     *
     *  1. **List-upsert survival**: a legacy `session.created(B)` frame
     *     upserts the session list (B prepended, A retained) and does NOT
     *     full-replace-wipe — guards the 6bf7bf7 "list-disappear" class.
     *  2. **Session-guard isolation**: a late-arriving legacy `message.updated`
     *     for the NON-current session A is blocked by the coordinator's
     *     session-guard (`eventSessionId != currentSessionId` early-return
     *     @ `SessionSyncCoordinator.kt:1259`) from touching B's chat.
     *  3. **LRU capture round-trip**: the real [SessionSwitcher.switchTo]
     *     captures A's outgoing window into its in-memory LRU
     *     (`captureCurrentSessionWindow`); the cache entry survives across
     *     the B→A switch, so the AppCore VerifyAndHydrate consumer (NOT
     *     constructed in this coordinator harness — see boundary note below)
     *     would re-hydrate A's m1 from a peek hit.
     *
     * **HARNESS BOUNDARY** (do NOT weaken — annotate): the AppCore
     * `dispatchSessionEffect(VerifyAndHydrate)` consumer that turns a peek
     * hit into `mutateChat { messages = cached.messages }` lives in the
     * orchestration layer (`AppCore`), not in the coordinator or
     * [SessionSwitcher]. We drive the LRU capture + chat-clear via the real
     * `SessionSwitcher.switchTo`, then inline-mirror that consumer's single
     * re-hydration step (`mutateChat { copy(messages = peek.messages) }`).
     * The pinned invariant is the coordinator-side preservation that makes
     * the round-trip restorable; the full end-to-end verify-and-hydrate
     * window-restore flow is pinned in [SessionSwitcherTest]
     * `switchTo captures outgoing session window into cache`.
     */
    @Test
    fun `P0-1 legacy A_to_B_to_A session switch round-trip preserves session A chat`() {
        // ── Setup: session A is current with one message; list has A. ─────────
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
        // Real SessionSwitcher sharing the coordinator's store/settings/repo/
        // effects — the LRU capture + chat-clear run for real; only the
        // AppCore VerifyAndHydrate consumer is mirrored inline (see above).
        val switcher = SessionSwitcher(
            store = slices.store,
            settingsManager = settingsManager,
            repository = repository,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
        )

        // ── Step 1: a legacy session.created for B arrives while A is current.
        //    The list upserts (B prepended, A retained); A's chat messages
        //    survive the list mutation. Guards 6bf7bf7 list-disappear class.
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

        // ── Step 2: switch A → B via the real SessionSwitcher. Its mutateChat
        //    flips currentSessionId to B + clears chat (SessionSwitcher:417-469);
        //    captureCurrentSessionWindow writes A's window into the LRU. ───────
        switcher.switchTo("session-B")
        assertEquals(
            "after switch A→B: currentSessionId flipped to B",
            "session-B",
            slices.chat.value.currentSessionId,
        )
        assertTrue(
            "after switch A→B: chat slice cleared (SessionSwitcher mutateChat contract)",
            slices.chat.value.messages.isEmpty(),
        )
        // The LRU capture is the half that makes the round-trip restorable —
        // pin it via the internal peekSessionWindow probe
        // (captureCurrentSessionWindow is private; peekSessionWindow is the
        // internal read-only view of the same LRU).
        val cachedA = switcher.peekSessionWindow("session-A")
        assertEquals(
            "SessionSwitcher LRU captured A's window on switch-out (round-trip pivot)",
            listOf("m1"),
            cachedA?.messages?.map { it.id },
        )

        // ── Step 3: a late-arriving legacy SSE frame for the NON-current
        //    session A MUST be blocked from polluting B's chat. The
        //    coordinator session-guard (@SSC:1259) early-returns when
        //    eventSessionId != currentSessionId. This is the coordinator-side
        //    preservation that keeps A's frozen window stable. ───────────────
        c.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-A"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m-late-A"))
                put("role", JsonPrimitive("assistant"))
            })
        })
        assertTrue(
            "late-arriving message.updated for non-current A does NOT touch B's chat " +
                "(coordinator session-guard early-returns at SSC:1259)",
            slices.chat.value.messages.none { it.id == "m-late-A" },
        )
        assertEquals(
            "B's chat stays empty (no spurious insert from the non-current SSE)",
            emptyList<String>(),
            slices.chat.value.messages.map { it.id },
        )

        // ── Step 4: switch B → A via the real SessionSwitcher. The chat slice
        //    clears again; the AppCore VerifyAndHydrate consumer (NOT in this
        //    coordinator harness — see HARNESS BOUNDARY in the kdoc) would
        //    re-hydrate from the LRU peek hit. We inline-mirror that single
        //    re-hydration step. ───────────────────────────────────────────────
        switcher.switchTo("session-A")
        assertEquals(
            "after switch B→A: currentSessionId flipped back to A",
            "session-A",
            slices.chat.value.currentSessionId,
        )
        // Harness boundary: AppCore.dispatchSessionEffect(VerifyAndHydrate)
        // does peekSessionWindow(sid) → on hit, mutateChat { messages = ... }.
        // That consumer is not constructed here; inline-mirror its single
        // re-hydration step. The peek proves the LRU still holds A's window
        // across the B→A switch (the pivot the consumer relies on).
        val stillCachedA = switcher.peekSessionWindow("session-A")
        assertNotNull(
            "SessionSwitcher LRU MUST still hold A's window across the B→A switch " +
                "(AppCore VerifyAndHydrate consumer relies on this peek hit)",
            stillCachedA,
        )
        assertEquals(
            "A's cached window is byte-for-byte intact across the round-trip",
            listOf("m1"),
            stillCachedA!!.messages.map { it.id },
        )
        slices.mutateChat { it.copy(messages = stillCachedA.messages) }

        // ── Round-trip assertion: A's m1 survived the A→B→A switch. ───────────
        assertEquals(
            "round-trip A→B→A: session A's chat messages survived the switch " +
                "(coordinator session-guard + SessionSwitcher LRU capture preserved A)",
            listOf("m1"),
            slices.chat.value.messages.map { it.id },
        )
    }

    // ── P0-2: legacy SSE message.part.delta lifecycle (overlay → final form) ─

    /**
     * P0-2: a legacy `message.part.delta` frame is **legacy-only wire** (C3:
     * slim SSE never carries `message.part.*`; slim has no token streaming).
     * It feeds the shared streaming overlay (`streamingPartTexts`) AND
     * injects a placeholder [Part] into `partsByMessage`. The full streaming
     * → finalize lifecycle pinned here:
     *
     *  1. **Leading edge** → `streamingPartTexts` populated (zero-latency
     *     first token) + `partsByMessage` gets the placeholder Part.
     *  2. **Trailing coalesce** → buffered delta flushes into the overlay in
     *     one state write (collapse of per-token recompositions).
     *  3. **Finalize** → a follow-up legacy `message.updated` lands the
     *     final form of m1 in the messages list (patch-in-place). This is
     *     the "delta eventually lands in the message list" final state.
     *
     *  4. **Slim non-cross-contamination**: legacy streaming populates the
     *     SHARED overlay (`streamingPartTexts`) but MUST NOT touch the slim-
     *     only `partExpandStates` affordance map. C3: slim has no token
     *     streaming; `partExpandStates` is fed only by slim digest→REST
     *     skeleton fills.
     *
     * Anchor: `dispatchSseEvent` `message.part.delta` branch (@SSC:1491) →
     * `applyPartDeltaLeadingEdge` (@SSC:3951) → `ensurePlaceholderPart`
     * (@SSC:4286); finalize via `message.updated` patch path (@SSC:1270).
     */
    @Test
    fun `P0-2 legacy SSE message part delta lifecycle streams into overlay then finalizes via message list patch`() {
        slices.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(Message(id = "m1", role = "assistant", sessionId = "session-A")),
            )
        }

        val c = coordinator()

        // ── Streaming phase (leading edge): legacy message.part.delta writes
        //    the shared overlay AND injects a placeholder Part so the assistant
        //    bubble can render the streaming text anchored to a real Part. ─────
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
        // Final-state (1): applyPartDeltaLeadingEdge → ensurePlaceholderPart
        // injects a Part into partsByMessage (the persistent side of the
        // streaming pair).
        assertEquals(
            "delta frame injects a placeholder Part into partsByMessage (final-state)",
            listOf("part-1"),
            slices.chat.value.partsByMessage["m1"]?.map { it.id },
        )
        assertEquals(
            "the placeholder Part carries the resolved streaming type (text)",
            "text",
            slices.chat.value.partsByMessage["m1"]?.firstOrNull { it.id == "part-1" }?.type,
        )

        // ── Coalesce phase: a trailing delta within DELTA_COALESCE_MS buffers
        //    into deltaBuffer; the pending flush appends it to the overlay in
        //    a single state write. ─────────────────────────────────────────────
        c.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-A"))
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("part-1"))
            put("field", JsonPrimitive("text"))
            put("delta", JsonPrimitive(", world!"))
        })
        scope.testScheduler.advanceUntilIdle()

        assertEquals(
            "coalesced flush accumulates the trailing delta (legacy streaming)",
            "Hello, world!",
            slices.chat.value.streamingPartTexts["part-1"],
        )

        // ── Finalize phase: a legacy message.updated (legacy-only wire per
        //    C3) lands the FINAL form of m1 in the messages list via the
        //    patch-in-place path (applyMessageUpdated @SSC:3884). This is the
        //    "delta eventually lands in the message list" final state. ────────
        c.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-A"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m1"))
                put("role", JsonPrimitive("assistant"))
                put("cost", JsonPrimitive(0.42))
            })
        })
        scope.testScheduler.advanceUntilIdle()

        val finalized = slices.chat.value.messages.firstOrNull { it.id == "m1" }
        assertNotNull(
            "final message.updated landed m1 in the messages list (delta lifecycle → final form)",
            finalized,
        )
        assertEquals(
            "final m1 carries the patched cost (proves the patch-in-place path ran)",
            0.42,
            finalized?.cost,
        )

        // ── Slim/non-cross-contamination: legacy streaming populated the SHARED
        //    overlay but MUST NOT touch the slim-only affordance map. C3: slim
        //    has no token streaming; partExpandStates is fed only by slim
        //    digest→REST skeleton fills. Anchor: SSC:1491 message.part.delta
        //    branch writes only streamingPartTexts/partsByMessage.
        assertTrue(
            "partExpandStates (slim omitted-part affordance) stays EMPTY across the " +
                "legacy streaming lifecycle (C3: slim has no token streaming)",
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

    // ── P0-5: legacy mode MUST reject slim cold-start snapshot (host-isolation)
    //
    // *** EXPECTED-FAILING — host-isolation bug 6bf7bf7 family (pre-P1-1) ***
    //
    // This is the documented T0 "destructive-probe anchor" (plan §4-T0 /
    // task2 §2.1 P0-5). It pins the DESIRED host-isolation contract: a slim
    // cold-start snapshot landing while `slimMode=false` MUST be rejected
    // (return false) and MUST NOT rewrite `SessionListState.sessions`.
    //
    // *** THE BUG (current reality) ***: [applySlimColdStartSnapshot]
    // (`SessionSyncCoordinator.kt:3354`) has NO `isSlimMode()` entry guard —
    // it trusts the caller's slim discipline + the slim commit token only.
    // When driven directly with a mock repository whose
    // `commitIfSlimTokenCurrent` runs the fold (the relaxed-mock answer),
    // the snapshot's `mutateSessionList` REWRITES the legacy session list.
    // This is the heaviest historical bug class — the 6bf7bf7 family
    // (374 sessions replaced by 100 → list vanished) — and the structural
    // fix is P1-1: add `check(isSlimMode()) { ... }` at the entry of
    // `applySlimColdStartSnapshot`.
    //
    // Methodology (spec §5.3 ③ host-isolation conflict): do NOT freeze the
    // bug; assert the DESIRED contract as an expected-fail anchor. The test
    // is @Ignore'd to keep `check.sh` GREEN across the T0→T1 window
    // (AGENTS.md hard rule: the tree must be green after every change; a
    // test red-until-T1 would pollute every check.sh run in between). The
    // assertions stay FROZEN (hash-guarded by _priv-verifier) — only
    // activation is deferred.
    //
    // LIFECYCLE:
    //  - T1 test lane UN-IGNORES this test (removes the @Ignore below).
    //  - T1 impl lane adds P1-1: `check(isSlimMode()) { "cold-start snapshot
    //    must not apply in legacy mode" }` @ `SessionSyncCoordinator.kt:3354`.
    //  - With both landed, this test goes GREEN: `applySlimColdStartSnapshot`
    //    throws `IllegalStateException` (check-fail) → test catches it /
    //    sees the rejection → assertions pass.
    //  - Removing the P1-1 guard later MUST turn this RED again — the
    //    destructive-probe contract the verifier exercises at the T1 gate.

    @Ignore(
        "T0 host-isolation bug anchor: applySlimColdStartSnapshot lacks isSlimMode() " +
            "entry guard (6bf7bf7 family — slim cold-start snapshot rewrites legacy " +
            "SessionListState.sessions when driven directly). RED pre-P1-1; kept green " +
            "via @Ignore across T0→T1 per AGENTS.md check.sh-hard-rule; T1 test lane " +
            "un-ignores, P1-1 guard @ SSC:3354 greens it. Frozen assertions unchanged.",
    )
    @Test
    fun `P0-5 legacy mode MUST reject slim cold-start snapshot (host-isolation bug 6bf7bf7 family, pre-P1-1)`() = runTest {
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

    // ── P0-6: legacy fold toggle drives ComposerController.expandedParts ────

    /**
     * P0-6: in legacy mode the tool/reasoning-card fold lives in
     * `StoreState.expandedParts` (the legacy fold map, written by
     * [ComposerController.togglePartExpand] via `mutateExpandedParts`). The
     * slim omitted-part affordance lives in the SEPARATE
     * `ChatState.partExpandStates` map and MUST stay empty in legacy mode.
     *
     * This test drives a REAL fold toggle through [ComposerController] — the
     * production writer of `expandedParts` (anchor: `ComposerController.kt:
     * 131-135`, routed from the UI's `onToggleExpand` → ChatViewModel →
     * ComposerController) — and pins that:
     *  1. `togglePartExpand` writes `expandedParts` (the legacy fold map),
     *     NOT `partExpandStates` (the slim omitted-part affordance).
     *  2. Legacy token streaming populates `streamingPartTexts` (the shared
     *     real-time layer) WITHOUT touching either fold map.
     *  3. The legacy fold survives a second toggle invert via `expandedParts`.
     *
     * C3: slim has no token streaming; `partExpandStates` is fed only by slim
     * digest→REST skeleton fills. Anchor: `AppStateSlices.kt:406-410`
     * ("SEPARATE") + `StoreState.kt:40` (`expandedParts`).
     */
    @Test
    fun `P0-6 legacy fold toggle drives ComposerController expandedParts and partExpandStates stays empty`() {
        // Structural separation (AppStateSlices.kt:406-410 "SEPARATE"):
        //   - StoreState.expandedParts (StoreState.kt:40): Map<String,Boolean>
        //     — the legacy tool/reasoning/sub-agent/patch fold.
        //   - ChatState.partExpandStates (AppStateSlices.kt:410):
        //     Map<PartKey, PartExpandState> — the slim omitted-part
        //     affordance (fed by slim digest→REST skeleton fills).
        val store = slices.store

        // ── Drive a REAL legacy fold toggle via ComposerController — the
        //    production writer of expandedParts (the UI's onToggleExpand
        //    routes ChatViewModel → ComposerController.togglePartExpand). ────
        val hostProfileStore = mockk<HostProfileStore>(relaxed = true).also {
            every { it.currentProfile() } returns HostProfile.defaultDirect("http://test")
        }
        val composer = ComposerController(
            store = store,
            settingsManager = settingsManager,
            hostProfileStore = hostProfileStore,
        )
        assertTrue(
            "baseline: expandedParts empty before any toggle",
            store.expandedParts.value.isEmpty(),
        )
        composer.togglePartExpand(key = "fold:m1:part-1", currentValue = false)
        assertEquals(
            "real ComposerController.togglePartExpand wrote expandedParts (legacy fold map)",
            mapOf("fold:m1:part-1" to true),
            store.expandedParts.value,
        )
        assertTrue(
            "first toggle MUST NOT touch partExpandStates (slim affordance map)",
            slices.chat.value.partExpandStates.isEmpty(),
        )

        // ── Drive a legacy streaming frame; the slim expand map MUST NOT move. ─
        slices.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(Message(id = "m1", role = "assistant", sessionId = "session-A")),
            )
        }
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
            "partExpandStates (slim omitted-part affordance) stays EMPTY across legacy " +
                "streaming (writer routing is structurally separate)",
            slices.chat.value.partExpandStates.isEmpty(),
        )
        assertEquals(
            "legacy fold state in expandedParts SURVIVES legacy streaming " +
                "(streaming writes streamingPartTexts, NOT expandedParts/partExpandStates)",
            mapOf("fold:m1:part-1" to true),
            store.expandedParts.value,
        )

        // ── Drive a second toggle to prove the writer inverts via expandedParts
        //    (the production fold-toggle path), NOT partExpandStates. ─────────
        composer.togglePartExpand(key = "fold:m1:part-1", currentValue = true)
        assertEquals(
            "second toggle flipped expandedParts[fold:m1:part-1] → false " +
                "(ComposerController writer inverts via expandedParts)",
            mapOf("fold:m1:part-1" to false),
            store.expandedParts.value,
        )
        assertTrue(
            "partExpandStates STILL empty after the second legacy fold toggle " +
                "(slim affordance map is never touched by legacy fold writes)",
            slices.chat.value.partExpandStates.isEmpty(),
        )
    }
}
