package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.MainViewModelTestBase
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.HostProfileStore
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * Harness (extends [MainViewModelTestBase] — same pattern as
 * [cn.vectory.ocdroid.ui.AppCoreDispatcherTest]):
 *  - **P0-1** drives the REAL AppCore consumer (`newCore()` →
 *    `core.sessionSwitcher.switchTo(...)` for LRU capture + chat-clear, then
 *    the AppCore init-block effectBus collector routes the emitted
 *    `VerifyAndHydrate` to `dispatchSessionEffect` → the AppCore.kt:559-638
 *    handler that injects the full cached window). No inline `mutateChat`.
 *  - **P0-2/3/4/5/6** drive a legacy coordinator directly (via
 *    [legacyCoordinator]) — these don't need the AppCore consumer; the
 *    coordinator-level paths under test are synchronous or use the
 *    `legacyScope` UnconfinedTestDispatcher for coalesce flushes. P0-4 / P0-5
 *    additionally need the `repository` mock wired INTO the SSC (the AppCore
 *    harness wires `repository = null` into SSC by default, which would mask
 *    the host-isolation bug P0-5 pins + trivialize P0-4's NoRepository teeth).
 *
 * Cases map 1:1 to the plan's P0-1~P0-6.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LegacyGoldenPathRegressionTest : MainViewModelTestBase() {

    /**
     * Legacy-mode coordinator scope (UnconfinedTestDispatcher) for tests that
     * drive the coordinator's coalesce flush job (P0-2/3/4/5/6 via
     * [legacyCoordinator]). P0-1 uses the real AppCore harness via [newCore]
     * (StandardTestDispatcher via the base class's MainDispatcherRule) +
     * `runTest { advanceUntilIdle() }`.
     */
    private lateinit var legacyScope: TestScope
    private lateinit var legacyStore: SharedStateStore
    private lateinit var legacyEffects: SharedEffectBus

    @Before
    override fun setUp() {
        super.setUp()
        // reportNonFatalIssue runs inline and calls Log.w(throwable) + Log.i;
        // the base setUp stubs Log.d / Log.w(no-throwable) / Log.e but not
        // these two variants.
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0

        legacyScope = TestScope(UnconfinedTestDispatcher())
        legacyStore = SharedStateStore()
        legacyEffects = SharedEffectBus()
    }

    /**
     * Legacy coordinator (`supportsWatermarkResync = { false }`) with the inherited
     * `repository` mock wired INTO the SSC. Used by P0-2/3/4/5/6.
     *
     * P0-4 (reconcileSession) and P0-5 (applySlimColdStartSnapshot) NEED a
     * non-null repository inside the SSC: P0-4's `coVerify(exactly = 0)
     * { repository.probeLatestSlim(...) }` teeth require a real repo that
     * COULD have been probed (legacy early-return is what skips it); P0-5's
     * host-isolation bug only manifests when `applySlimColdStartSnapshot`
     * actually reaches `commitIfSlimTokenCurrent { ... }` (which needs
     * `repository != null` — the AppCore harness's SSC has `repository = null`
     * which would mask the bug by short-circuiting at the `?: return false`).
     * P0-2/3/6 don't touch the repository; they use this harness for diff
     * stability.
     */
    private fun legacyCoordinator(): SessionSyncCoordinator =
        SessionSyncCoordinator(
            scope = legacyScope,
            slices = legacyStore.slices,
            settingsManager = settingsManager,
            effects = legacyEffects,
            currentServerGroupFp = { "test-fp" },
            supportsWatermarkResync = { false },
            repository = repository,
            reconcileDispatcher = UnconfinedTestDispatcher(),
        )

    private fun event(type: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): SSEEvent =
        SSEEvent(payload = SSEPayload(type = type, properties = buildJsonObject(block)))

    // ── P0-1: A→B→A session switch round-trip preserves session A's full window

    /**
     * P0-1: in legacy mode, a REAL A→B→A session-switch round-trip preserves
     * session A's chat window across the switch — pinned via the REAL AppCore
     * `VerifyAndHydrate` consumer (no inline `mutateChat`). spec §2.1 P0-1
     * requires "A 有消息 → 切到 B → 切回 A，A 消息存活（不被 B 的 cold-start 快照
     * 覆盖）".
     *
     * This test drives the real [SessionSyncCoordinator] (for SSE), the real
     * [SessionSwitcher] (for LRU capture + chat-clear), AND the real AppCore
     * `dispatchSessionEffect(VerifyAndHydrate)` consumer (AppCore.kt:559-638
     * — the handler that does the post-peek composite-key re-check and
     * injects the FULL cached window). The four preservation halves pinned:
     *
     *  1. **List-upsert survival**: a legacy `session.created(B)` frame
     *     upserts the session list (B prepended, A retained) — guards the
     *     6bf7bf7 "list-disappear" class.
     *  2. **Full-window LRU capture**: the real `SessionSwitcher.switchTo(B)`
     *     captures ALL 4 CachedSessionWindow fields (messages /
     *     partsByMessage / olderMessagesCursor / hasMoreMessages) with
     *     NON-TRIVIAL seeded values (so a field-by-field assertion
     *     distinguishes a real capture from a slice default).
     *  3. **Session-guard isolation**: a late-arriving legacy `message.updated`
     *     for the NON-current session A is blocked by the coordinator's
     *     session-guard (`eventSessionId != currentSessionId` early-return @
     *     `SessionSyncCoordinator.kt:1259`) from touching B's chat.
     *  4. **Real consumer round-trip inject**: the real
     *     `SessionSwitcher.switchTo(A)` emits `VerifyAndHydrate`; the AppCore
     *     init-block effectBus collector dispatches it; the
     *     `dispatchSessionEffect(VerifyAndHydrate)` arm runs the entry guard
     *     → `peekSessionWindow(A)` hit → post-peek composite-key re-check
     *     passes → `cached.messages.isNotEmpty()` → `store.mutateChat` copies
     *     the FULL 4-field cached window (AppCore.kt:631-638). Field-by-field
     *     assertion after the round-trip proves the real consumer injected
     *     all 4 fields, not just message ids.
     */
    @Test
    fun `P0-1 legacy A_to_B_to_A session switch round-trip preserves session A full window via real VerifyAndHydrate consumer`() = runTest {
        val core = newCore()
        // Pin the host profile so currentServerGroupFp() == "test-fp"
        // consistently — the LRU is keyed by (fp, sid), and the VerifyAndHydrate
        // post-peek composite-key re-check (AppCore.kt:601-611) requires
        // effect.serverGroupFp == currentServerGroupFp(). The default base-class
        // profile uses a random UUID serverGroupFp; pinning avoids cross-test
        // key drift.
        val testProfile = HostProfile(
            id = "test-host",
            name = "Test",
            serverUrl = "http://test",
            serverGroupFp = "test-fp",
        )
        every { hostProfileStore.currentProfile() } returns testProfile

        // ── Seed A with NON-TRIVIAL values for all 4 CachedSessionWindow
        //    fields. Real capture + real VerifyAndHydrate consumer inject
        //    all 4; a field-by-field post-round-trip assertion distinguishes
        //    a real injection from a slice default (empty list / null /
        //    false). ─────────────────────────────────────────────────────────
        val aMsg = Message(id = "m1", role = "user", sessionId = "session-A")
        val aPart = Part(
            id = "part-1",
            messageId = "m1",
            sessionId = "session-A",
            type = "text",
            text = "hello-A",
        )
        val expectedPartsByMessage = mapOf("m1" to listOf(aPart))
        val expectedCursor = "cursor-A"
        val expectedHasMore = true
        core.store.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(aMsg),
                partsByMessage = expectedPartsByMessage,
                olderMessagesCursor = expectedCursor,
                hasMoreMessages = expectedHasMore,
            )
        }
        core.store.mutateSessionList {
            it.copy(sessions = listOf(Session(id = "session-A", directory = "/tmp/a")))
        }

        // ── Step 1: legacy SSE session.created(B) while A is current. The
        //    coordinator upserts the list (B prepended, A retained); A's full
        //    chat window survives the list mutation. Guards the 6bf7bf7
        //    list-disappear class. ───────────────────────────────────────────
        core.handleSSEEvent(event("session.created") {
            put("session", buildJsonObject {
                put("id", JsonPrimitive("session-B"))
                put("directory", JsonPrimitive("/tmp/b"))
                put("title", JsonPrimitive("New"))
            })
        })
        assertEquals(
            "session list upserted (not wiped): B prepended, A retained",
            listOf("session-B", "session-A"),
            core.store.slices.sessionList.value.sessions.map { it.id },
        )
        assertEquals(
            "current session A's chat messages survive the session-list mutation",
            listOf("m1"),
            core.store.slices.chat.value.messages.map { it.id },
        )

        // ── Step 2: switch A → B via the REAL SessionSwitcher. Its
        //    mutateChat flips currentSessionId to B + clears chat;
        //    captureCurrentSessionWindow writes A's FULL 4-field window into
        //    the in-memory LRU. Pin the capture is full (not just messages). ─
        core.sessionSwitcher.switchTo("session-B")
        assertEquals(
            "after switch A→B: currentSessionId flipped to B",
            "session-B",
            core.store.slices.chat.value.currentSessionId,
        )
        assertTrue(
            "after switch A→B: chat slice cleared (SessionSwitcher mutateChat contract)",
            core.store.slices.chat.value.messages.isEmpty(),
        )
        val cachedA = core.peekSessionWindow("session-A")
        assertNotNull(
            "SessionSwitcher LRU captured A's window on switch-out",
            cachedA,
        )
        assertEquals(
            "captured messages field",
            listOf(aMsg),
            cachedA!!.messages,
        )
        assertEquals(
            "captured partsByMessage field (NON-TRIVIAL — distinguishes real capture from slice default)",
            expectedPartsByMessage,
            cachedA.partsByMessage,
        )
        assertEquals(
            "captured olderMessagesCursor field (NON-TRIVIAL)",
            expectedCursor,
            cachedA.olderMessagesCursor,
        )
        assertEquals(
            "captured hasMoreMessages field (NON-TRIVIAL)",
            expectedHasMore,
            cachedA.hasMoreMessages,
        )

        // ── Step 3: a late-arriving legacy SSE frame for the NON-current
        //    session A MUST be blocked from polluting B's chat. The
        //    coordinator session-guard (@SSC:1259) early-returns when
        //    eventSessionId != currentSessionId. This is the coordinator-side
        //    preservation that keeps A's frozen window stable. ───────────────
        core.handleSSEEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-A"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m-late-A"))
                put("role", JsonPrimitive("assistant"))
            })
        })
        assertTrue(
            "late-arriving message.updated for non-current A does NOT touch B's chat " +
                "(coordinator session-guard early-returns at SSC:1259)",
            core.store.slices.chat.value.messages.none { it.id == "m-late-A" },
        )
        assertEquals(
            "B's chat stays empty (no spurious insert from the non-current SSE)",
            emptyList<String>(),
            core.store.slices.chat.value.messages.map { it.id },
        )

        // ── Step 4: switch B → A via the REAL SessionSwitcher. switchTo emits
        //    VerifyAndHydrate(serverGroupFp="test-fp", sessionId="session-A")
        //    to the effectBus. The REAL AppCore consumer (AppCore.init-block
        //    effectBus collector → dispatchEffect → dispatchSessionEffect →
        //    VerifyAndHydrate arm @ AppCore.kt:559-638) picks it up under
        //    advanceUntilIdle: entry guard passes (currentSessionId was
        //    flipped to "session-A" by switchTo's mutateChat) →
        //    peekSessionWindow("session-A") hit → post-peek composite-key
        //    re-check passes (fp + sid match) → cached.messages.isNotEmpty()
        //    → store.mutateChat copies the FULL 4-field cached window
        //    (AppCore.kt:631-638). No inline mutateChat — the real consumer
        //    runs end-to-end. ────────────────────────────────────────────────
        core.sessionSwitcher.switchTo("session-A")
        advanceUntilIdle() // let the AppCore init collector's dispatch land

        // ── Step 5: field-by-field assertion — the real VerifyAndHydrate
        //    consumer injected A's FULL cached window (all 4
        //    CachedSessionWindow fields, not just message ids). Proves the
        //    production hydrate path restored A across the A→B→A round-trip. ─
        assertEquals(
            "round-trip A→B→A via real VerifyAndHydrate consumer: messages field injected",
            listOf(aMsg),
            core.store.slices.chat.value.messages,
        )
        assertEquals(
            "round-trip A→B→A: partsByMessage field injected (NON-TRIVIAL — distinguishes " +
                "real full-window injection from message-id-only or slice default)",
            expectedPartsByMessage,
            core.store.slices.chat.value.partsByMessage,
        )
        assertEquals(
            "round-trip A→B→A: olderMessagesCursor field injected (NON-TRIVIAL)",
            expectedCursor,
            core.store.slices.chat.value.olderMessagesCursor,
        )
        assertEquals(
            "round-trip A→B→A: hasMoreMessages field injected (NON-TRIVIAL)",
            expectedHasMore,
            core.store.slices.chat.value.hasMoreMessages,
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
        val slices = legacyStore.slices
        slices.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(Message(id = "m1", role = "assistant", sessionId = "session-A")),
            )
        }

        val c = legacyCoordinator()

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
        legacyScope.testScheduler.advanceUntilIdle()

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
        legacyScope.testScheduler.advanceUntilIdle()

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
        val slices = legacyStore.slices
        slices.mutateChat {
            it.copy(
                currentSessionId = "session-A",
                messages = listOf(
                    Message(id = "m1", role = "assistant", sessionId = "session-A"),
                    Message(id = "m2", role = "user", sessionId = "session-A"),
                ),
            )
        }

        val c = legacyCoordinator()

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
     *
     * The `coVerify(exactly = 0) { repository.probeLatestSlim(any()) }` teeth
     * require a NON-NULL repository wired into the SSC (so the assertion
     * proves the legacy early-return skipped a probe that COULD have run);
     * this is why we use [legacyCoordinator] (repository wired) rather than
     * the AppCore-harness SSC (which has `repository = null` and would
     * trivially short-circuit at a different guard).
     */
    @Test
    fun `P0-4 legacy reconcileSession returns NoRepository without touching ChatState`() = runTest {
        val slices = legacyStore.slices
        val seeded = listOf(
            Message(id = "m1", role = "user", sessionId = "session-A"),
            Message(id = "m2", role = "assistant", sessionId = "session-A"),
        )
        slices.mutateChat {
            it.copy(currentSessionId = "session-A", messages = seeded)
        }

        val c = legacyCoordinator()
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
        // structural, before any repo I/O — pinned via coVerify with the
        // repository wired so the assertion has teeth).
        coVerify(exactly = 0) { repository.probeLatestSlim(any()) }
        verify(exactly = 0) { repository.getSlimSessionState(any()) }
    }

    // ── P0-5: legacy mode MUST reject slim cold-start snapshot (host-isolation)
    //
    // *** T1d FREEZE ACTIVATION — @Ignore REMOVED; RED until P1-1 lands ***
    //
    // This is the documented T0 "destructive-probe anchor" (plan §4-T0 /
    // task2 §2.1 P0-5). It pins the DESIRED host-isolation contract: a slim
    // cold-start snapshot landing while `slimMode=false` MUST be rejected at
    // the entry of [applySlimColdStartSnapshot] (throw IllegalStateException)
    // and MUST NOT rewrite `SessionListState.sessions`.
    //
    // *** THE BUG (current reality, HEAD=acd9eb4) ***: [applySlimColdStartSnapshot]
    // (`SessionSyncCoordinator.kt:3354`) has NO `isSlimMode()` entry guard —
    // it trusts the caller's slim discipline + the slim commit token only.
    // When driven directly with a mock repository whose
    // `commitIfSlimTokenCurrent` runs the fold (the relaxed-mock answer),
    // the snapshot's `mutateSessionList` REWRITES the legacy session list.
    // This is the heaviest historical bug class — the 6bf7bf7 family
    // (374 sessions replaced by 100 → list vanished) — and the structural
    // fix is P1-1: add `check(isSlimMode()) { ... }` (or
    // `requireSlimOnlyStateWrite(isSlimMode(), "cold-start-snapshot")`)
    // at the entry of `applySlimColdStartSnapshot`, BEFORE
    // `val repo = repository ?: return false`.
    //
    // *** ASSERTION FORM *** (rev-gpt round-2 fix): the P1-1 fix is a
    // `check(isSlimMode())` / `requireSlimOnlyStateWrite(...)` (fail-fast),
    // NOT a softening `return false`. So the assertion is
    // `assertThrows(IllegalStateException) { applySlim... }` — pre-P1-1
    // (current) no exception is thrown, so assertThrows FAILS (RED);
    // post-P1-1 the guard throws IllegalStateException (or the documented
    // subclass SlimOnlyStateWriteException) and assertThrows PASSES (GREEN).
    //
    // *** T1d ACTIVATION ***: the @Ignore that kept this GREEN across the
    // T0→T1 window is REMOVED here. This test is now RED until the T1d impl
    // lane lands P1-1. check.sh will show this as a FAILURE — that is the
    // intended freeze signal (impl MUST land P1-1 to turn it GREEN). The
    // assertions are unchanged from the T0 round-2 freeze.

    @Test
    fun `P0-5 legacy mode MUST reject slim cold-start snapshot (host-isolation bug 6bf7bf7 family, pre-P1-1)`() = runTest {
        val slices = legacyStore.slices
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

        val c = legacyCoordinator()

        // DESIRED host-isolation contract (post-P1-1): the entry guard
        // `check(isSlimMode())` throws IllegalStateException — applySlim-
        // ColdStartSnapshot is a slim-only state write that MUST NEVER run in
        // legacy mode. assertThrows (NOT assertFalse): P1-1's fix is a
        // check() (fail-fast), not a softening return-false.
        assertThrows(IllegalStateException::class.java) {
            c.applySlimColdStartSnapshot(snapshot)
        }
        // The entry guard threw BEFORE any mutateSessionList — the legacy
        // session list is byte-for-byte unchanged. (This assertion only runs
        // post-P1-1; pre-P1-1 the assertThrows above fails first, which is
        // the expected RED state of this @Ignore'd anchor.)
        assertEquals(
            "legacy session list MUST NOT be rewritten — the entry guard rejected " +
                "the slim cold-start snapshot before any mutateSessionList (6bf7bf7 family fix)",
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
        val slices = legacyStore.slices
        // Structural separation (AppStateSlices.kt:406-410 "SEPARATE"):
        //   - StoreState.expandedParts (StoreState.kt:40): Map<String,Boolean>
        //     — the legacy tool/reasoning/sub-agent/patch fold.
        //   - ChatState.partExpandStates (AppStateSlices.kt:410):
        //     Map<PartKey, PartExpandState> — the slim omitted-part
        //     affordance (fed by slim digest→REST skeleton fills).
        val store = legacyStore

        // ── Drive a REAL legacy fold toggle via ComposerController — the
        //    production writer of expandedParts (the UI's onToggleExpand
        //    routes ChatViewModel → ComposerController.togglePartExpand). ────
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
        val c = legacyCoordinator()
        c.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-A"))
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("part-1"))
            put("field", JsonPrimitive("text"))
            put("delta", JsonPrimitive("streaming"))
        })
        legacyScope.testScheduler.advanceUntilIdle()

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
