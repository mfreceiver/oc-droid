package cn.vectory.ocdroid.integration

import android.util.Log
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.MainDispatcherRule
import cn.vectory.ocdroid.data.model.SlimSessionDigest
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule

/**
 * rev-grok golden-path integration test for slim mode (省流 v1).
 *
 * This is the ONLY test in the suite that wires REAL SSE wire bytes → REAL
 * [SSEClient] (via [OpenCodeRepository.connectSSE]) → REAL
 * [SessionSyncCoordinator] → REAL [OpenCodeRepository] against a single
 * [MockWebServer]. The existing ~3200 unit tests are fragments — each pins
 * one component in isolation with mocked collaborators
 * (see [cn.vectory.ocdroid.data.api.SSEClientSlimModeTest] for SSEClient,
 * [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinatorSlimTest] for
 * the coordinator with a mocked repository,
 * [cn.vectory.ocdroid.data.repository.OpenCodeRepositorySlimapiEndpointsTest]
 * for repository endpoints driven by direct suspend calls). None of them
 * prove that the slim wiring actually runs end-to-end.
 *
 * Boundary choice (per rev-grok task description, "首选" tier): the FULL real
 * stack — real OkHttp EventSource parsing real `text/event-stream` bytes,
 * real coordinator dispatch folding into real slices, real repository
 * suspend GET that lands on MockWebServer. NO mocks of SSEClient internals
 * or of the repository's slimapi surface; only `SettingsManager` (disk IO)
 * and the two traffic collaborators are mocked.
 *
 * Pins:
 *  - **B1 fix carry-through**: sidecar-native `event:`-typed frames
 *    (`server.connected` / `session.digest`) parse via SSEClient's
 *    `parseSseEvent(data, eventType)` path and reach the coordinator's
 *    `session.digest` branch.
 *  - **Digest → fetch → fold**: `session.digest` triggers a real
 *    `GET /slimapi/messages/{sid}/since/{ts}` via
 *    [OpenCodeRepository.getSlimapiMessagesSince]; the returned skeletons
 *    fold into the chat slice (messages + partsByMessage).
 *  - **Flat q-p pass-through carries routeToken**: a flat
 *    `data: {"type":"question.asked",...}` frame folds into
 *    `pendingQuestions` with the slimapi HMAC `routeToken` preserved
 *    (Phase 3 reply path's link integrity).
 *  - **SlimapiVersionInterceptor on the wire**: every `/slimapi/` request
 *    carries the `X-Slimapi-Version` header (M1 gate — proves the OkHttp
 *    chain is shared between REST + SSE clients).
 *  - **Bonus (step 6)**: `replySlimapiQuestion(q1, answers, routeToken)`
 *    lands `POST /slimapi/questions/q1/reply` with the token in the body.
 *  - **Bonus (step 7, partial)**: a second `session.digest` for the SAME
 *    session with a strictly-newer `updatedAt` triggers ANOTHER
 *    `/slimapi/messages/.../since/...` GET — proves the incremental
 *    resync code path (the same one the Service would invoke on a
 *    `resync` frame) works on the wire. We do NOT drive `event: resync`
 *    here because the coordinator does not dispatch `resync` itself —
 *    that frame is handled at `ServiceSseConnectionOwner` (which calls
 *    `coldStartSlimSync`); that layer is exercised by
 *    [cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwnerResyncTest].
 */
class SlimGoldenPathIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val server = MockWebServer()
    private lateinit var repository: OpenCodeRepository
    private lateinit var store: SharedStateStore
    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var settingsManager: SettingsManager
    private lateinit var coordinator: SessionSyncCoordinator
    private lateinit var scope: CoroutineScope

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        // SessionSyncCoordinator + SSEClient call android.util.Log directly
        // (the latter via DebugLog forwarding). Stub so unit-test JVM doesn't
        // hit "not mocked" — mirrors SessionSyncCoordinatorSlimTest.setUp.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        DebugLog.clear()
        server.start()

        // Real repository wired at MockWebServer in slim mode. The 2-arg
        // construction (TrafficTracker + TrafficLogger) is the test-locked
        // shape used by OpenCodeRepositorySlimapiEndpointsTest; relaxed mocks
        // make traffic counting a no-op. SlimapiVersionInterceptor reads
        // hostConfig.slim via configure(slim=true) → injects X-Slimapi-Version
        // on every /slimapi/ request through the shared OkHttp chain.
        repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        repository.configure(
            baseUrl = server.url("/").toString().trimEnd('/'),
            slim = true,
        )

        // Real slice storage + effect bus. SharedStateStore is a @Singleton
        // with a no-arg ctor — direct construction is the test-locked pattern.
        store = SharedStateStore()
        slices = store.slices
        effects = SharedEffectBus()
        settingsManager = mockk(relaxed = true)

        // Real coordinator scope: a real dispatcher is required because the
        // digest branch launches a real suspend GET (the Retrofit suspend
        // call blocks on OkHttp's internal dispatcher and resumes here).
        // Dispatchers.Default gives us a real thread pool; assertions poll
        // for completion via [awaitCondition] (the test cannot use
        // TestScope.advanceUntilIdle because the network calls do not run
        // on the test scheduler).
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        coordinator = SessionSyncCoordinator(
            scope = scope,
            slices = slices,
            settingsManager = settingsManager,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            supportsWatermarkResync = { true },
            repository = repository,
        )

        // NOTE: cold-start snapshot responses (sessions, questions, permissions)
        // are NO LONGER pre-enqueued here. Each test that triggers the cadence-
        // driven resync (which calls coldStartSlimSync) must enqueue them itself
        // after the SSE body to ensure correct FIFO ordering with the SSE request.
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        unmockkAll()
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** MockWebServer text/event-stream response carrying one or more SSE frames. */
    private fun sseResponse(vararg frames: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(frames.joinToString(separator = "\n\n", postfix = "\n\n"))

    /** Sidecar-native event:-typed frame (type in `event:`, data has only fields). */
    private fun typedFrame(eventType: String, data: String): String =
        "event: $eventType\ndata: $data"

    /** Sidecar q/p flat frame (no `event:` line; type embedded inside JSON). */
    private fun dataFrame(json: String): String = "data: $json"

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setBody(body)
            .setHeader("Content-Type", "application/json")

    /**
     * Polls [condition] with 50ms granularity until true or [timeoutMs] elapses.
     * Required because the digest-triggered `/since` GET runs on the OkHttp
     * dispatcher + Dispatchers.Default; the test thread cannot use
     * `TestScope.advanceUntilIdle` to wait for it. Mirrors the implicit
     * "wait for async side-effect" pattern in
     * [cn.vectory.ocdroid.data.api.SSEClientSlimModeTest] (`.first { ... }`
     * with `withTimeout`).
     */
    private suspend fun awaitCondition(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        error("Condition not met within $timeoutMs ms. " +
            "chat.messages=${slices.chat.value.messages.map { it.id }} " +
            "pendingQuestions=${slices.sessionList.value.pendingQuestions.map { it.id to it.routeToken }}")
    }

    /**
     * Asserts the M1 gate ([SlimapiContract.X_SLIMAPI_VERSION] header) on a
     * recorded request. The SlimapiVersionInterceptor lives on the SHARED
     * OkHttp chain — exercising it here against the real slimapi paths proves
     * the REST + SSE clients both pass through it.
     */
    private fun assertSlimapiVersionHeader(req: RecordedRequest) {
        assertEquals(
            "M1 gate: every /slimapi/ request MUST carry X-Slimapi-Version " +
                "(path=${req.path})",
            "1",
            req.getHeader("X-Slimapi-Version"),
        )
    }

    // ── the golden path ────────────────────────────────────────────────────

    @Test
    fun `golden path - server connected, session digest folds messages, question asked carries routeToken`() =
        runBlocking {
            // Open session s1 so the digest-triggered fetch folds into the
            // chat slice (handleSessionDigest guards `fetch.sessionId ==
            // currentSessionId` before merging — mirrors message.updated's
            // defensive session guard).
            slices.mutateChat { it.copy(currentSessionId = "s1") }

            // 1) SSE feed: 3 frames in arrival order, REAL text/event-stream
            //    bytes (chunked body, parsed by OkHttp's RealEventSource).
            //    Frame order is significant: server.connected MUST come first
            //    (cold-start liveness probe); digest follows; q-p flat
            //    frame interleaves correctly with event-typed frames.
            server.enqueue(
                sseResponse(
                    typedFrame("server.connected", "{}"),
                    typedFrame(
                        "session.digest",
                        """{"sessionID":"s1","directory":"/w","updatedAt":1000}""",
                    ),
                    dataFrame(
                        """{"directory":"/w","type":"question.asked",""" +
                            """"properties":{"id":"q1","sessionID":"s1",""" +
                            """"questions":[],"routeToken":"tok-abc"}}""",
                    ),
                ),
            )

            // 2) The digest-triggered reconcile. T11 round-2 (oracle I2):
            //    reconcileSession ALWAYS probes first
            //    (`GET /slimapi/messages/s1?limit=1&mode=skeleton`). When
            //    `localAppliedUpdatedAt != null` it follows with /since/{ts};
            //    when null (this cold-start case), it follows with the
            //    bounded cursor drain façade
            //    `GET /slimapi/messages/s1?limit=200&mode=skeleton` and
            //    follows X-Next-Cursor. Three message requests are therefore
            //    enqueued in arrival order:
            //    a) the probe response (limit=1, single newest skeleton),
            //    b) the cursor-drain page 1 (limit=200, the same skeleton
            //       with a null X-Next-Cursor → terminates the walk).
            //    After the drain returns, bumpSlimBookmarkFromItems (T11
            //    rewired → onReconcileSuccess) advances localAppliedUpdatedAt
            //    to the aggregated skeleton's time.updated=200 (we APPLIED
            //    m1@200 locally). The digest's updatedAt=1000 stays in
            //    remoteUpdatedAt (the sidecar's signal — NOT a local-applied
            //    watermark).
            val skeleton = MessageWithParts(
                info = Message(
                    id = "m1",
                    role = "assistant",
                    sessionId = "s1",
                    time = Message.TimeInfo(created = 100L, updated = 200L),
                ),
                parts = listOf(
                    Part(
                        id = "p1",
                        messageId = "m1",
                        sessionId = "s1",
                        type = "text",
                        text = "hello from slimapi",
                    ),
                ),
            )
            // (a) Probe response — limit=1 → first item.
            server.enqueue(jsonResponse(json.encodeToString(listOf(skeleton))))
            // (b) Cursor drain page 1 — limit=200, same skeleton, NO
            //     X-Next-Cursor header (sidecar signals end-of-history).
            server.enqueue(jsonResponse(json.encodeToString(listOf(skeleton))))

            // 3) Start SSE collection on the coordinator scope. Each parsed
            //    SSEEvent is dispatched synchronously via handleEvent; the
            //    digest branch then launches a coroutine for the GET (returns
            //    immediately). The collect {} throws once MockWebServer
            //    closes the SSE body (onClosed → close(Exception)) — that's
            //    expected for a single-shot feed; swallow it here.
            val sseJob = scope.launch {
                try {
                    repository.connectSSE(directory = null).collect { result ->
                        result.onSuccess { coordinator.handleEvent(it) }
                    }
                } catch (e: Exception) {
                    // Expected: MockWebServer closes the SSE feed after the
                    // body is delivered; OkHttp fires onClosed → flow throws.
                }
            }

            try {
                // Wait for BOTH async outcomes: (a) the digest's GET returns
                // and the message is folded, (b) the flat q-p frame folds
                // (synchronous but happens AFTER the digest frame in the
                // arrival order, so we still wait via the same condition).
                awaitCondition(5_000) {
                    slices.chat.value.messages.any { it.id == "m1" } &&
                        slices.sessionList.value.pendingQuestions.any {
                            it.id == "q1" && it.routeToken == "tok-abc"
                        }
                }

                // ── Wire-level assertions (request arrival order). ─────────
                // With the cadence resync now suppressed on initial connect (G-F1 fix),
                // there are exactly 3 HTTP requests in order:
                // 1. SSE connect (GET /slimapi/events)
                // 2. Probe (GET /slimapi/messages/s1?limit=1&mode=skeleton)
                // 3. Cursor drain (GET /slimapi/messages/s1?limit=200&mode=skeleton)
                val sseRequest = server.takeRequest()
                assertEquals(
                    "slim mode MUST hit /slimapi/events (instance-level sidecar SSE)",
                    "/slimapi/events",
                    sseRequest.path,
                )
                assertEquals(
                    "SSE Accept header MUST be text/event-stream",
                    "text/event-stream",
                    sseRequest.getHeader("Accept"),
                )
                assertNull(
                    "slim SSE MUST NOT send X-Opencode-Directory " +
                        "(instance-level — directory is per-frame in body)",
                    sseRequest.getHeader("X-Opencode-Directory"),
                )
                assertSlimapiVersionHeader(sseRequest)

                // T11 reconcile: probe request comes BEFORE the since-fetch
                // (reconcileSession always probes first). The probe is
                // `GET /slimapi/messages/s1?limit=1&mode=skeleton`.
                val probeRequest = server.takeRequest()
                assertEquals("GET", probeRequest.method)
                assertTrue(
                    "probe MUST hit /slimapi/messages/s1 with limit=1 + mode=skeleton: ${probeRequest.path}",
                    probeRequest.path!!.startsWith("/slimapi/messages/s1"),
                )
                assertTrue(
                    "probe MUST carry limit=1: ${probeRequest.path}",
                    probeRequest.path!!.contains("limit=1"),
                )
                assertTrue(
                    "probe MUST carry mode=skeleton: ${probeRequest.path}",
                    probeRequest.path!!.contains("mode=skeleton"),
                )
                assertSlimapiVersionHeader(probeRequest)

                val messagesRequest = server.takeRequest()
                assertEquals("GET", messagesRequest.method)
                // T11 round-2 (oracle I2): fresh session → bounded cursor
                // drain façade (`/slimapi/messages/s1?limit=200&mode=skeleton`),
                // NOT /since/0. The drain is owned by the repo's
                // fetchSlimInitialWindowBounded; X-Next-Cursor was null in
                // the enqueued response so the walk terminates after one page.
                assertTrue(
                    "fresh-session fetch MUST hit cursor drain path: ${messagesRequest.path}",
                    messagesRequest.path!!.startsWith("/slimapi/messages/s1"),
                )
                assertTrue(
                    "cursor drain MUST carry limit=200: ${messagesRequest.path}",
                    messagesRequest.path!!.contains("limit=200"),
                )
                assertTrue(
                    "cursor drain MUST carry mode=skeleton: ${messagesRequest.path}",
                    messagesRequest.path!!.contains("mode=skeleton"),
                )
                assertTrue(
                    "cursor drain MUST NOT use /since path (cold start): ${messagesRequest.path}",
                    !messagesRequest.path!!.contains("/since/"),
                )
                assertSlimapiVersionHeader(messagesRequest)

                // ── Slice-level assertions (fold results). ─────────────────
                val chatSnap = slices.chat.value
                assertEquals(
                    "message skeleton folded into chat slice by messageID",
                    listOf("m1"),
                    chatSnap.messages.map { it.id },
                )
                assertEquals(
                    "assistant role preserved through MessageWithParts.info",
                    "assistant",
                    chatSnap.messages.single { it.id == "m1" }.role,
                )
                assertEquals(
                    "partsByMessage populated from MessageWithParts.parts",
                    "hello from slimapi",
                    chatSnap.partsByMessage["m1"]?.firstOrNull()?.text,
                )

                val pendingQ = slices.sessionList.value.pendingQuestions
                    .single { it.id == "q1" }
                assertEquals("s1", pendingQ.sessionId)
                assertEquals(
                    "routeToken (slimapi HMAC) preserved end-to-end from " +
                        "flat q-p frame → pendingQuestions (Phase 3 reply path)",
                    "tok-abc",
                    pendingQ.routeToken,
                )

                // T11 rewire (T6 hand-off): after the since-fetch
                // succeeds, bumpSlimBookmarkFromItems calls
                // onReconcileSuccess(state, [skeleton]) which advances
                // localAppliedUpdatedAt=200 (the fetched skeleton's
                // time.updated — we APPLIED m1@200 locally). The digest's
                // updatedAt=1000 stays in remoteUpdatedAt (the sidecar's
                // signal — NOT what we applied).
                // updatedAt accessor = localAppliedUpdatedAt ?: remoteUpdatedAt = 200.
                val bookmark = repository.snapshotSlimSseState()["s1"]
                assertNotNull("bookmark entry created", bookmark)
                assertEquals(
                    "post-T11 rewire: localAppliedUpdatedAt=200 (the fetched skeleton)",
                    200L,
                    bookmark!!.localAppliedUpdatedAt,
                )
                assertEquals(
                    "remoteUpdatedAt=1000 (digest signal, untouched by REST rewire)",
                    1000L,
                    bookmark.remoteUpdatedAt,
                )
                assertEquals(
                    "updatedAt accessor prefers localAppliedUpdatedAt post-rewire",
                    200L,
                    bookmark.updatedAt,
                )
                assertEquals("m1", bookmark.localAppliedMessageId)
            } finally {
                sseJob.cancel()
            }
        }

    // ── bonus step 6: reply carries routeToken on the wire ────────────────

    @Test
    fun `bonus step 6 - replySlimapiQuestion posts answers plus routeToken`() =
        runBlocking {
            // Direct repository call (no SSE setup needed — the wire contract
            // is what matters: routeToken reaches the POST body so the sidecar
            // can validate the HMAC + re-inject the directory). This pins the
            // Phase-3 reply half of the routeToken link (the ask half is
            // pinned by the golden-path test above).
            server.enqueue(MockResponse().setResponseCode(204))

            val result = repository.replySlimapiQuestion(
                questionId = "q1",
                answers = listOf(listOf("yes")),
                routeToken = "tok-abc",
            )

            assertTrue("reply should succeed against 204", result.isSuccess)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(
                "/slimapi/questions/q1/reply",
                request.path,
            )
            assertSlimapiVersionHeader(request)

            val body = request.body.readUtf8()
            assertTrue(
                "answers present in body: $body",
                body.contains("\"answers\":[[\"yes\"]]"),
            )
            assertTrue(
                "routeToken present in body (HMAC link): $body",
                body.contains("\"routeToken\":\"tok-abc\""),
            )
        }

    // ── bonus step 7: incremental resync code path ────────────────────────
    //
    // The full `event: resync` path lives at ServiceSseConnectionOwner (the
    // coordinator does not dispatch `resync` — that frame is consumed one
    // layer up). What we CAN pin at this layer is the INCREMENTAL fetch the
    // resync handler ultimately invokes: `coldStartSlimSync(openSessionId=...)`
    // fans out to `/slimapi/sessions` + `/slimapi/questions` + `/slimapi/permissions`
    // + `/slimapi/messages/{sid}/since/{prior-bookmark}`. The "since prior
    // bookmark" half is the resync-semantically-meaningful piece — it proves
    // a second pass does NOT refetch from 0 (the incremental / A2=A contract).
    //
    // We do NOT race two session.digest frames in one SSE body to demonstrate
    // this: the coordinator's `scope.launch { repo.getSlimapiMessagesSince }`
    // for the first digest would race the second digest's launch, and the
    // MockWebServer response queue is FIFO → flaky. The ServiceSseConnectionOwner
    // serialization (resyncMutex) is the production answer to that race, and
    // it's exercised by ServiceSseConnectionOwnerResyncTest. Here we drive
    // the same repository endpoint surface the Service calls into.

    @Test
    fun `bonus step 7 - coldStartSlimSync after digest fires incremental since-fetch anchored on prior bookmark`() =
        runBlocking {
            // Pre-seed the bookmark via applySlimDigest (the in-memory reducer
            // step that runs on every session.digest). A real resync frame
            // would have arrived after one or more digests already advanced
            // the bookmark — we simulate that state directly.
            repository.applySlimDigest(
                SlimSessionDigest(sessionId = "s1", updatedAt = 1000L),
                token = repository.captureSlimCommitToken(),
            )
            // Sanity: the bookmark reflects the seed.
            val seeded = repository.snapshotSlimSseState()["s1"]
            assertNotNull("seed bookmark entry", seeded)
            assertEquals(1000L, seeded!!.updatedAt)

            // The cold-start snapshot fan-out: 4 endpoints. The messages one
            // is only fetched when openSessionId is supplied.
            server.enqueue(jsonResponse("[]")) // sessions
            server.enqueue(jsonResponse("""{"items":[],"errors":[]}""")) // questions
            server.enqueue(jsonResponse("""{"items":[],"errors":[]}""")) // permissions
            val incrementalMessage = MessageWithParts(
                info = Message(
                    id = "m2",
                    role = "assistant",
                    sessionId = "s1",
                    time = Message.TimeInfo(created = 1500L, updated = 2400L),
                ),
            )
            server.enqueue(jsonResponse(json.encodeToString(listOf(incrementalMessage))))

            val result = repository.coldStartSlimSync(openSessionId = "s1", token = repository.captureSlimCommitToken())

            assertTrue(
                "coldStartSlimSync succeeds (per-piece degradation yields overall success)",
                result.isSuccess,
            )
            val snapshot = result.getOrThrow()
            assertNotNull(
                "openSessionId supplied → messages fetched",
                snapshot.messages,
            )
            assertEquals(
                "incremental skeleton returned by /since/1000",
                listOf("m2"),
                snapshot.messages!!.map { it.info.id },
            )

            // Drain the 4 requests; find the messages one. The first 3
            // (sessions/questions/permissions) MAY arrive in any order
            // (they're coroutines.fan-out — order not guaranteed), so we
            // collect all paths and assert the messages path is present +
            // correctly anchored.
            val paths = mutableListOf<String>()
            repeat(4) { paths += server.takeRequest().path!! }
            assertTrue(
                "resync code path anchors messages fetch on prior bookmark " +
                    "(since=1000, NOT since=0 — the incremental / A2=A contract): $paths",
                paths.any { it.startsWith("/slimapi/messages/s1/since/1000") },
            )

            // Bookmark advanced to max(1000, 2400) = 2400 (monotonic merge —
            // bumpSlimBookmarkFromItems).
            val advanced = repository.snapshotSlimSseState()["s1"]
            assertNotNull(advanced)
            assertEquals(2400L, advanced!!.updatedAt)
        }

    // ── Phase 3 T3(d): end-to-end session-switch partial-apply semantics ──

    /**
     * T3(d) (Phase 3 review): end-to-end session-switch assertion that
     * reflects T2's new partial-apply semantics. Real wire bytes → real
     * [OpenCodeRepository] → real [SessionSyncCoordinator] against a single
     * [MockWebServer]. A mid-reconcile focus rotation (sess-a → sess-b) is
     * injected at the commit gate via a [spyk] on the repo.
     *
     * Post-T2 contract pinned here:
     *  - The OLD session's (sess-a) bookmark is advanced via the REAL fetch
     *    path (`bumpSlimBookmarkFromItems` inside `getSlimapiMessagesSince` —
     *    observable via `snapshotSlimSseState`), NOT dropped.
     *  - The chat-merge is skipped (live `session-b` != `result.sid sess-a`)
     *    so the NEW session's chat view stays clean of the OLD session's
     *    items (rev-grok rule #3 — the INNER focus gate is preserved).
     *  - The `WriteSessionWindow` retention effect is emitted for sess-a so
     *    a later `switchTo(sess-a)` finds the cached items without a re-fetch.
     *
     * The fix-6 two-stage-dispatcher pins + the catch-up Stale pins in
     * [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinatorResyncTest]
     * are unchanged; this test exclusively covers the new partial-apply
     * behavior.
     */
    @Test
    fun `T3d session-switch mid-reconcile applies retention and keeps new chat view clean`() =
        runBlocking {
            // Pre-seed sess-a so the reconcile uses /since/{ts} deterministically.
            // applySlimDigest sets remote=1000; bumpSlimBookmarkFromItems inside
            // the upcoming fetch will advance localApplied to the fetched
            // skeleton's updated (2000).
            val seedToken = repository.captureSlimCommitToken()
            repository.applySlimDigest(
                SlimSessionDigest(sessionId = "sess-a", updatedAt = 1000L, messageId = "m-seed"),
                token = seedToken,
            )

            // Open sess-a as the current session so the digest / reconcile
            // path picks it up.
            slices.mutateChat { it.copy(currentSessionId = "sess-a") }

            val item = MessageWithParts(
                info = Message(
                    id = "m-new",
                    role = "assistant",
                    sessionId = "sess-a",
                    time = Message.TimeInfo(created = 1500L, updated = 2000L),
                ),
                parts = listOf(
                    Part(
                        id = "p-new",
                        messageId = "m-new",
                        sessionId = "sess-a",
                        type = "text",
                        text = "fresh skeleton for sess-a",
                    ),
                ),
            )
            val itemBody = json.encodeToString(listOf(item))

            // Probe (limit=1) + since-fetch page (returns the new skeleton).
            server.enqueue(jsonResponse(itemBody))
            server.enqueue(jsonResponse(itemBody))

            // Spy the repo to rotate focus at the commit gate (between the
            // fetch returning + bookmark bumped and the chat-merge step).
            // The real commitIfSlimTokenCurrent is invoked on the UNSPIED
            // instance to avoid recursion through the spy (same pattern as
            // `CD3-v2 UI effect gate drops REST merge after mid-flight
            // configure` in SessionSyncCoordinatorResyncTest).
            val switched = java.util.concurrent.atomic.AtomicBoolean(false)
            val realRepo = repository
            val spiedRepo = spyk(realRepo)
            every { spiedRepo.commitIfSlimTokenCurrent(any(), any()) } answers {
                val token = firstArg<OpenCodeRepository.SlimCommitToken>()
                val block = secondArg<() -> Unit>()
                if (switched.compareAndSet(false, true)) {
                    // Rotate focus to sess-b BEFORE the lambda runs so the
                    // inner branch sees liveSessionId="sess-b".
                    slices.mutateChat { it.copy(currentSessionId = "sess-b") }
                }
                // Delegate to the real method (no recursion through the spy).
                realRepo.commitIfSlimTokenCurrent(token, block)
            }

            // Re-wire the coordinator with the spied repo.
            coordinator = SessionSyncCoordinator(
                scope = scope,
                slices = slices,
                settingsManager = settingsManager,
                effects = effects,
                currentServerGroupFp = { "test-fp" },
                supportsWatermarkResync = { true },
                repository = spiedRepo,
            )

            // Drain the effect bus for the WriteSessionWindow retention check.
            val collectedEffects = mutableListOf<ControllerEffect>()
            val effectJob = scope.launch {
                effects.effectsConsumed.toList(collectedEffects)
            }

            val result = coordinator.reconcileSession(
                "sess-a",
                SessionSyncCoordinator.ReconcileMode.RESYNC,
            )

            // Give the effect collector a tick to drain the WriteSessionWindow
            // emission (it runs on Dispatchers.Default; poll briefly).
            awaitCondition(2_000) {
                collectedEffects.any {
                    it is ControllerEffect.WriteSessionWindow && it.sessionId == "sess-a"
                }
            }
            effectJob.cancel()

            // ── T2 post-condition assertions ──────────────────────────────

            // T2: the real Reconciled result is returned (NOT Stale).
            assertTrue(
                "T3d: real Reconciled returned (got $result)",
                result is SessionSyncCoordinator.ReconcileResult.Reconciled,
            )

            // OLD session's bookmark advanced via the real fetch path
            // (bumpSlimBookmarkFromItems inside getSlimapiMessagesSince).
            val bookmark = spiedRepo.snapshotSlimSseState()["sess-a"]
            assertNotNull("T3d: bookmark entry exists for sess-a", bookmark)
            assertEquals(
                "T3d: localAppliedUpdatedAt advanced to the fetched skeleton's updated=2000",
                2000L,
                bookmark!!.localAppliedUpdatedAt,
            )
            assertEquals("m-new", bookmark.localAppliedMessageId)

            // rev-grok rule #3: chat-merge skipped (live session-b !=
            // result.sid sess-a); the NEW session's chat view is clean.
            assertTrue(
                "T3d: chat-merge skipped; m-new must NOT be in chat slice",
                slices.chat.value.messages.none { it.id == "m-new" },
            )
            assertEquals("sess-b", slices.chat.value.currentSessionId)

            // T2 retention branch fired: WriteSessionWindow emitted for the
            // now-non-current sess-a so a later switchTo(sess-a) finds the
            // cached items without forcing a re-fetch.
            assertTrue(
                "T3d: WriteSessionWindow emitted for sess-a: $collectedEffects",
                collectedEffects.any {
                    it is ControllerEffect.WriteSessionWindow && it.sessionId == "sess-a"
                },
            )

            // Verify the wire shape: probe + since-fetch both hit the real
            // slimapi surface (the spy does not change URLs).
            val probeReq = server.takeRequest()
            assertTrue(
                "T3d: probe hit /slimapi/messages/sess-a: ${probeReq.path}",
                probeReq.path!!.startsWith("/slimapi/messages/sess-a"),
            )
            assertSlimapiVersionHeader(probeReq)
            val fetchReq = server.takeRequest()
            assertTrue(
                "T3d: fetch hit /slimapi/messages/sess-a: ${fetchReq.path}",
                fetchReq.path!!.startsWith("/slimapi/messages/sess-a"),
            )
            assertSlimapiVersionHeader(fetchReq)
        }
}
