package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.ResyncReason
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.LoadedContent
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.StreamOwnedState
import cn.vectory.ocdroid.ui.controller.sse.SseDispatchHost
import cn.vectory.ocdroid.ui.controller.sse.SharedConversationSseHandler
import cn.vectory.ocdroid.ui.controller.sse.TokenStreamCoordinator
import cn.vectory.ocdroid.ui.launchLoadMoreMessages
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §B2 rev-gpt #3 (BLOCK hardening): production-wiring sequence coverage.
 * Drives the REAL dispatch call sites — [SharedConversationSseHandler.handleEvent],
 * [TokenStreamCoordinator.dispatchEpochFrame] / its completion-cleanup bridge,
 * and [launchLoadMoreMessages] — against a live [SharedStateStore] (no hand-
 * dispatched AppActions for the SSE / token-stream paths), proving the
 * end-to-end chain the route-owned LoadedContent authority depends on:
 *
 *  1. a live `message.part.delta` reaches the route-owned LoadedContent for
 *     the ACTIVE route (the [SharedConversationSseHandler] →
 *     `PartDeltaReceived(expectedRouteInstance=…)` →
 *     [reducePartDeltaReceived] → `withRouteContentSynced` mirror);
 *  2. a `message.part.delta` after a route switch does NOT pollute the new
 *     route's content (the handler's currentSessionId guard + the §7.2
 *     freshness CAS keep the new slot clean);
 *  3. route-aware load-more reads its cursor/baseline from LoadedContent
 *     (not flat — no Elvis fallback) and prepends the older page into the
 *     route slot; with no matching slot it aborts cleanly;
 *  4. [TokenStreamCoordinator]'s REAL completion/cleanup bridge (a `resync`
 *     frame → reducer `ClearPartState` → `handleEffect` →
 *     `ClearTokenStreamState`) clears the owned part from BOTH the route-
 *     owned LoadedContent AND the flat mirror — no hand-dispatched action;
 *  5. an A→B→A incarnation sequence: a stale route completion carrying the
 *     PRIOR A incarnation token (T1) is rejected by the §7.2 freshness CAS
 *     even though its sessionId matches the now-active A (T3); a live
 *     completion for the active incarnation DOES commit. Proves the
 *     incarnation CAS, not just session-id matching.
 *
 * The fake [SseDispatchHost] / [FakeStreamProvider] are thin adapters over a
 * real [SharedStateStore.slices]; the handlers/coordinator under test are the
 * production types.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class B2RouteWiringSequenceTest {

    private lateinit var scope: TestScope
    private lateinit var store: SharedStateStore
    private lateinit var bundleRepository: OpenCodeRepository

    private fun setUpStore() {
        scope = TestScope(UnconfinedTestDispatcher())
        store = SharedStateStore()
        bundleRepository = OpenCodeRepository(
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        val bundle = bundleRepository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundle.generation, bundle.endpointFp))
    }

    /**
     * Minimal [SseDispatchHost] wired to a real [SharedStateStore]. Only the
     * surface [SharedConversationSseHandler] touches is meaningfully
     * implemented; the rest are inert stubs (the handler never reaches them
     * for `message.part.delta`). `isFlushActiveForPart` returns false so the
     * leading-edge dispatch path fires.
     */
    private class FakeSseHost(
        override val slices: cn.vectory.ocdroid.ui.SliceFlows,
        override val scope: CoroutineScope,
        override val repository: OpenCodeRepository? = null,
    ) : SseDispatchHost {
        override val effects: SharedEffectBus = SharedEffectBus()
        override val settingsManager: SettingsManager = mockk(relaxed = true)
        override val statusAggregatorInput: cn.vectory.ocdroid.service.status.StatusAggregatorInput? = null
        override fun serverGroupFp(): String = "test-fp"
        override fun stripeFor(sid: String): Mutex = Mutex()
        override fun scheduleDeltaFlush(partId: String) {}
        override fun clearDeltaBuffers() {}
        override fun applySseSideEffects(sideEffects: List<cn.vectory.ocdroid.ui.controller.SseSideEffect>) {}
        override fun bumpUnknownEventCounter(type: String) {}
        override fun sseClock(): Long = 0L
        override fun supportsDurableSessionErrorBanner(): Boolean = false
        override fun isFlushActiveForPart(partId: String): Boolean = false
        override fun handleSessionDigest(event: SSEEvent) {}
        override fun dispatchTokenStreamPlaceholder(
            partType: String,
            partId: String,
            messageId: String,
            sessionId: String,
            expectedRouteInstance: Long,
        ): Boolean {
            slices.store.dispatch(
                cn.vectory.ocdroid.ui.AppAction.PartPlaceholderEnsured(
                    partType = partType,
                    partId = partId,
                    messageId = messageId,
                    sessionId = sessionId,
                    expectedRouteInstance = expectedRouteInstance,
                    bundleStamp = cn.vectory.ocdroid.ui.BundleStamp(0L, ""),
                ),
            )
            return true
        }
    }

    /**
     * Channel-backed stream provider (mirrors TokenStreamCoordinatorTest's
     * FakeStreamProvider) so the coordinator's `runStream` collector stays
     * open across `dispatchEpochFrame` calls. The channel is never pumped
     * here — frames are driven directly via `dispatchEpochFrame`, the unit-
     * testable internal entry the production `flow.collect` lambda calls.
     */
    private class FakeStreamProvider {
        val provider: (String, String?) -> Flow<TokenStreamFrame> = { _, _ ->
            val ch = Channel<TokenStreamFrame>(Channel.UNLIMITED)
            flow { for (frame in ch) emit(frame) }
        }
    }

    /**
     * Build a [TokenStreamCoordinator] wired to [store]'s slices with a large
     * watchdog (so it cannot trip during these synchronous tests) and zero
     * debounce. `triggerSinceFetch` is recorded so the cleanup test can assert
     * the authoritative re-fetch side-effect fires alongside the clear.
     */
    private fun buildCoordinator(
        sinceFetchCalls: MutableList<Pair<String, Boolean>> = mutableListOf(),
    ): TokenStreamCoordinator = TokenStreamCoordinator(
        scope = scope,
        slices = store.slices,
        streamProvider = FakeStreamProvider().provider,
        triggerSinceFetch = { sid, auth -> sinceFetchCalls += sid to auth },
        bundleCommitLock = bundleRepository,
        currentBundleProvider = { bundleRepository.currentClientBundle() },
        openDebounceMs = 0L,
        watchdogPollMs = 10L,
        watchdogMs = 10_000L,
        initialBackoffMs = 50L,
        maxBackoffMs = 200L,
        clock = { scope.testScheduler.currentTime },
    )

    private fun runPending() = scope.runCurrent()

    private fun snapshot(
        sessionId: String = "A",
        messageId: String = "m1",
        partId: String = "p1",
        text: String? = "hello",
        done: Boolean = false,
        truncated: Boolean = false,
    ) = TokenStreamFrame.PartSnapshot(sessionId, messageId, partId, text, done, truncated)

    private fun deltaEvent(sessionId: String, messageId: String, partId: String, delta: String): SSEEvent =
        SSEEvent(payload = SSEPayload(
            type = "message.part.delta",
            properties = buildJsonObject {
                put("sessionID", JsonPrimitive(sessionId))
                put("messageID", JsonPrimitive(messageId))
                put("partID", JsonPrimitive(partId))
                put("field", JsonPrimitive("text"))
                put("delta", JsonPrimitive(delta))
            },
        ))

    /**
     * Stage a parameterized route for [sessionId] under incarnation [token]:
     * advances chatRouteInstance, sets nav.lastRoute = "chat/$sessionId",
     * currentSessionId, and (optionally) a route-owned [LoadedContent]. When
     * [content] is supplied the flat transcript fields are mirrored from it
     * (matches how [reduceChatContentLoaded] commits BOTH projections), so the
     * route-aware `withRouteContentSynced` mirror sees a consistent snapshot.
     */
    private fun stageRoute(sessionId: String, token: Long, content: LoadedContent? = null) {
        store.mutateState { s ->
            s.copy(
                chatRouteInstance = token,
                nav = s.nav.copy(lastRoute = "chat/$sessionId"),
                chat = s.chat.copy(
                    currentSessionId = sessionId,
                    content = content,
                    messages = content?.messages ?: s.chat.messages,
                    partsByMessage = content?.partsByMessage ?: s.chat.partsByMessage,
                    streamingPartTexts = content?.streamingPartTexts ?: s.chat.streamingPartTexts,
                    streamOwned = content?.streamOwned ?: s.chat.streamOwned,
                ),
            )
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 1. Live message.part.delta → route-owned LoadedContent
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `live message part delta reaches route-owned LoadedContent for the active route`() = runTest {
        setUpStore()
        // Active route A under token 5; content owns assistant message m1
        // (the §user-part-guard requires a non-user owner).
        stageRoute(
            sessionId = "A",
            token = 5,
            content = LoadedContent(
                sessionId = "A",
                messages = listOf(Message(id = "m1", sessionId = "A", role = "assistant")),
                routeInstance = 5,
            ),
        )
        val host = FakeSseHost(store.slices, scope)
        val handler = SharedConversationSseHandler(host)

        // Production dispatch path: handler captures routeInstanceFor("A") = 5
        // and threads it through PartDeltaReceived → reducer mirrors into slot.
        handler.handle(deltaEvent("A", "m1", "p1", "live-token"))

        assertEquals(
            "live delta must reach route-owned streamingPartTexts",
            "live-token",
            store.chatFlow.value.content?.streamingPartTexts?.get("p1"),
        )
        // The flat mirror is updated too (route-aware sync writes both).
        assertEquals("live-token", store.chatFlow.value.streamingPartTexts["p1"])
    }

    // ───────────────────────────────────────────────────────────────────────
    // 2. Stale delta after route switch does NOT pollute the new route slot
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `message part delta after route switch does not pollute the new route content`() = runTest {
        setUpStore()
        stageRoute(
            sessionId = "A",
            token = 5,
            content = LoadedContent(
                sessionId = "A",
                messages = listOf(Message(id = "m1", sessionId = "A", role = "assistant")),
                routeInstance = 5,
            ),
        )
        val host = FakeSseHost(store.slices, scope)
        val handler = SharedConversationSseHandler(host)

        // Switch to route B under a fresh token (simulating navigateToChat(B)):
        // the §7.2 incarnation advances, the nav route + currentSessionId flip,
        // and B gets its own content slot owning assistant message mB.
        stageRoute(
            sessionId = "B",
            token = 6,
            content = LoadedContent(
                sessionId = "B",
                messages = listOf(Message(id = "mB", sessionId = "B", role = "assistant")),
                routeInstance = 6,
            ),
        )

        // A late delta for A arrives. The handler's currentSessionId guard
        // (A != B) early-returns; even if it had dispatched, routeInstanceFor
        // ("A") would now return 0L (nav route mismatch) → flat-only write,
        // never reaching B's slot.
        handler.handle(deltaEvent("A", "m1", "p1", "STALE-A"))

        assertNull(
            "stale-A delta must NOT reach B's route content",
            store.chatFlow.value.content?.streamingPartTexts?.get("p1"),
        )
        // A live delta for the now-active B DOES land in B's slot.
        handler.handle(deltaEvent("B", "mB", "pB", "live-B"))
        assertEquals(
            "live delta for active route B must reach B's content",
            "live-B",
            store.chatFlow.value.content?.streamingPartTexts?.get("pB"),
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // 3. Route-aware load-more reads cursor/baseline from LoadedContent
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `route-aware load-more reads cursor from LoadedContent and prepends into route content`() = runTest {
        setUpStore()
        // Route A owns content with cursor "c1" + hasMore + one message m1.
        // FLAT cursor is intentionally null/hasMore=false — the route-aware
        // path must source from LoadedContent, not flat (rev-gpt #5).
        stageRoute(
            sessionId = "A",
            token = 7,
            content = LoadedContent(
                sessionId = "A",
                messages = listOf(Message(id = "m1", sessionId = "A", role = "user")),
                olderMessagesCursor = "c1",
                hasMoreMessages = true,
                routeInstance = 7,
            ),
        )
        // Force flat cursor/hasMore to "nothing here" to prove the route path
        // does not read them.
        store.mutateState { it.copy(chat = it.chat.copy(olderMessagesCursor = null, hasMoreMessages = false)) }

        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery {
            repo.getMessagesPaged(eq("A"), any(), eq("c1"), any())
        } returns Result.success(
            MessagesPage(
                items = listOf(MessageWithParts(Message(id = "m0", sessionId = "A", role = "user"))),
                nextCursor = "c0",
            )
        )

        launchLoadMoreMessages(
            scope = scope,
            repository = repo,
            slices = store.slices,
            sessionId = "A",
            expectedRouteInstance = 7,
        )
        advanceUntilIdle()

        val content = store.chatFlow.value.content
        assertEquals(
            "older page m0 must be prepended before existing m1 (baseline from LoadedContent)",
            listOf("m0", "m1"),
            content?.messages?.map { it.id },
        )
        assertEquals("c0", content?.olderMessagesCursor)
        assertTrue("hasMore must reflect the new cursor", content?.hasMoreMessages == true)
        // Flat mirror is kept in lockstep by the reducer's route-aware sync.
        assertEquals(
            "flat mirror must match the route slot after route-aware load-more",
            listOf("m0", "m1"),
            store.chatFlow.value.messages.map { it.id },
        )
    }

    @Test
    fun `route-aware load-more aborts when no matching LoadedContent slot exists`() = runTest {
        setUpStore()
        // Route A under token 9 but NO LoadedContent slot (content == null).
        // The route-aware path must NOT fall back to flat cursor/hasMore here
        // — it aborts (nothing route-owned to extend). Flat cursor stays null
        // so the legacy fallback would also abort; the point is the route path
        // does not invent a cursor.
        stageRoute(sessionId = "A", token = 9, content = null)
        val repo = mockk<OpenCodeRepository>(relaxed = true)

        launchLoadMoreMessages(
            scope = scope,
            repository = repo,
            slices = store.slices,
            sessionId = "A",
            expectedRouteInstance = 9,
        )
        advanceUntilIdle()

        // No slot → no fetch, no state mutation. The flag must not get stuck.
        assertFalse("isLoadingMoreMessages must not be left set without a slot",
            store.chatFlow.value.isLoadingMoreMessages)
        assertNull("content slot must remain absent", store.chatFlow.value.content)
        assertEquals("route token must remain unchanged", 9L, store.chatRouteInstanceFlow.value)
    }

    // ───────────────────────────────────────────────────────────────────────
    // 4. TokenStreamCoordinator completion cleanup → clears slot + flat
    //    (REAL dispatch path: resync frame → reducer ClearPartState →
    //    handleEffect → ClearTokenStreamState, NOT a hand-dispatched action)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `TokenStreamCoordinator completion cleanup clears the owned part from route content and flat mirror`() {
        setUpStore()
        // Active route A@T1 owning assistant message m1.
        stageRoute(
            sessionId = "A",
            token = 5,
            content = LoadedContent(
                sessionId = "A",
                messages = listOf(Message(id = "m1", sessionId = "A", role = "assistant")),
                routeInstance = 5,
            ),
        )
        val sinceFetch = mutableListOf<Pair<String, Boolean>>()
        val coordinator = buildCoordinator(sinceFetch)
        coordinator.open("A", source = "test")
        runPending()
        val epoch = coordinator.epochOf("A")
        val gen = coordinator.genOf("A")
        assertTrue("epoch must be seeded by open", epoch > 0L)

        // 1. Drive a STREAMING snapshot through the REAL dispatch path —
        //    bridgePartToChatState uses the threaded token (=5, captured at
        //    open() from routeInstanceFor("A")) and mirrors the part into
        //    BOTH flat + route-owned slot.
        //    §B4 round-2 (rev-gpt C2): the token is now a required parameter
        //    on dispatchEpochFrame (no shared-field read). Pass the live
        //    routeInstanceFor("A") value (=5) explicitly.
        coordinator.dispatchEpochFrame(
            "A", epoch, gen, snapshot(text = "hello"), store.slices.routeInstanceFor("A"),
            bundleRepository.currentClientBundle(),
        )
        assertEquals(
            "live snapshot must reach route-owned streamingPartTexts",
            "hello",
            store.chatFlow.value.content?.streamingPartTexts?.get("p1"),
        )
        assertEquals(
            "route-owned streamOwned must mark the part STREAMING",
            StreamOwnedState.STREAMING,
            store.chatFlow.value.content?.streamOwned?.get("p1"),
        )
        assertEquals("hello", store.chatFlow.value.streamingPartTexts["p1"])

        // 2. Drive a resync frame through the REAL dispatch path — the reducer
        //    emits ClearPartState for the session's owned parts; handleEffect
        //    dispatches ClearTokenStreamState (B3 legacy flat-path clear). This
        //    is the production completion/cleanup bridge — no hand dispatch.
        coordinator.dispatchEpochFrame(
            "A", epoch, gen,
            TokenStreamFrame.Resync(ResyncReason.SESSION_IDLE, "A"),
            store.slices.routeInstanceFor("A"),
            bundleRepository.currentClientBundle(),
        )
        runPending()

        val content = store.chatFlow.value.content
        assertFalse(
            "route content streamingPartTexts must drop the cleared part",
            content?.streamingPartTexts?.containsKey("p1") == true,
        )
        assertFalse(
            "route content streamOwned must drop the cleared part",
            content?.streamOwned?.containsKey("p1") == true,
        )
        // Flat mirror cleared in the same dispatch (acceptsRouteUpdate + sync).
        assertFalse(store.chatFlow.value.streamingPartTexts.containsKey("p1"))
        assertFalse(store.chatFlow.value.streamOwned.containsKey("p1"))
        // The cleanup also fired the authoritative /since re-fetch hook.
        assertTrue(
            "resync cleanup must trigger an authoritative re-fetch",
            sinceFetch.any { it.first == "A" && it.second },
        )
        coordinator.close("A")
        runPending()
    }

    // ───────────────────────────────────────────────────────────────────────
    // 5. A→B→A incarnation CAS: stale prior-incarnation completion rejected,
    //    live current-incarnation completion committed.
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `A to B to A incarnation CAS rejects stale prior-token completion and accepts live completion`() {
        setUpStore()
        // Incarnation T1: route A, content loading (null). A ChatContentLoaded
        // carrying T1 is "in flight" (captured at request time per §7.2).
        stageRoute(sessionId = "A", token = 5, content = null)

        // Switch to B under a fresh incarnation T2.
        stageRoute(
            sessionId = "B",
            token = 6,
            content = LoadedContent(
                sessionId = "B",
                messages = listOf(Message(id = "mB", sessionId = "B", role = "assistant")),
                routeInstance = 6,
            ),
        )

        // Switch back to A as a NEW incarnation T3 > T1, with already-committed
        // content (the A→B→A race window: req-1 under T1 returns AFTER a newer
        // A@T3 has committed). currentSessionId is A again — a pure session-id
        // guard would NOT reject the stale T1 completion. The incarnation CAS
        // must.
        stageRoute(
            sessionId = "A",
            token = 7,
            content = LoadedContent(
                sessionId = "A",
                messages = listOf(Message(id = "mA-committed", sessionId = "A", role = "assistant")),
                routeInstance = 7,
            ),
        )

        // Stale completion carrying the PRIOR A incarnation token T1=5: the
        // reducer's §7.2 CAS (expectedRouteInstance != chatRouteInstance) drops
        // it. The new A@T3 slot is NOT polluted.
        store.dispatch(
            AppAction.ChatContentLoaded(
                sessionId = "A",
                expectedRouteInstance = 5,
                messages = listOf(Message(id = "stale-mA", sessionId = "A", role = "assistant")),
            ),
        )
        assertEquals(
            "stale T1 completion must NOT pollute the new A@T3 slot",
            listOf("mA-committed"),
            store.chatFlow.value.content?.messages?.map { it.id },
        )

        // Live completion carrying the CURRENT incarnation token T3=7 commits.
        store.dispatch(
            AppAction.ChatContentLoaded(
                sessionId = "A",
                expectedRouteInstance = 7,
                messages = listOf(Message(id = "live-mA", sessionId = "A", role = "assistant")),
            ),
        )
        assertEquals(
            "live T3 completion must commit into the A@T3 slot",
            listOf("live-mA"),
            store.chatFlow.value.content?.messages?.map { it.id },
        )

        // The live token-stream dispatch path also respects the new
        // incarnation: a snapshot driven through the REAL coordinator threads
        // routeInstanceFor("A")=7 (the live incarnation) and lands in A@T3.
        // §B4 round-2 (rev-gpt C2): token is now a required dispatchEpochFrame
        // parameter — pass the live routeInstanceFor("A") explicitly.
        val coordinator = buildCoordinator()
        coordinator.open("A", source = "test")
        runPending()
        coordinator.dispatchEpochFrame(
            "A", coordinator.epochOf("A"), coordinator.genOf("A"),
            snapshot(messageId = "live-mA", partId = "pLive", text = "live-token"),
            store.slices.routeInstanceFor("A"),
            bundleRepository.currentClientBundle(),
        )
        assertEquals(
            "coordinator must bridge the live snapshot into the active A@T3 slot",
            "live-token",
            store.chatFlow.value.content?.streamingPartTexts?.get("pLive"),
        )
        coordinator.close("A")
        runPending()
    }
}
