package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.ui.controller.CachedSessionWindow
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-18 Phase 5+ → R-20 Phase 2 → remove-message-persistence Task 4: direct
 * unit tests for [launchCatchUp].
 *
 * The legacy `launchCloseGap` section + the gap-coordinator delegation test
 * were removed (the non-contiguous gap mechanism was deleted in Task 4 —
 * catch-up now always merges the fetched window via `mergeProbeIntoSlice`,
 * with manual "load more" paging covering older history).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatchUpActionsTest {

    private lateinit var store: SharedStateStore
    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var scope: TestScope
    private lateinit var cachedWindows: MutableList<Pair<String, CachedSessionWindow>>

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        store = SharedStateStore()
        slices = store.slices
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        // §chat-ux-batch T8 (B3): mock setup for getAgentForSession removed (deleted API).
        scope = TestScope(UnconfinedTestDispatcher())
        cachedWindows = mutableListOf()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── launchCatchUp ─────────────────────────────────────────────────────────

    @Test
    fun `launchCatchUp skips reload when server newest equals anchor`() = runTest {
        // Local newest message id m_new.
        val localNewest = Message(id = "m_new", role = "assistant", time = Message.TimeInfo(created = 100L))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(localNewest)) }
        // Server reports the same newest (via boundary facade).
        coEvery { repository.probeLatestMessageIdForCurrent("s1") } returns cn.vectory.ocdroid.data.repository.ProbeResult(
            ok = true,
            messageID = "m_new",
            updatedAt = 100L,
        )

        launchCatchUp(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            onCacheWindow = { sid, w -> cachedWindows += sid to w },
        )
        advanceUntilIdle()

        // No tail reload issued.
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
        // Loading flag restored to false; no other state changes.
        assertFalse(slices.chat.value.isLoadingMessages)
        assertTrue(cachedWindows.isEmpty())
    }

    @Test
    fun `launchCatchUp coalesces when isLoadingMessages already true`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "s1", isLoadingMessages = true) }

        launchCatchUp(scope, repository, slices, "s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.probeLatestMessageIdForCurrent(any()) }
        assertTrue(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `launchCatchUp reloads tail and clears staleNotice on success`() = runTest {
        val anchor = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor), staleNotice = true) }
        coEvery { repository.probeLatestMessageIdForCurrent("s1") } returns cn.vectory.ocdroid.data.repository.ProbeResult(
            ok = true,
            messageID = "server-newer",
            updatedAt = 200L,
        )
        // Fetched contains anchor (2 msgs, < probe page) → contiguous / NoGap.
        val fetched = listOf(
            MessageWithParts(info = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))),
            MessageWithParts(info = Message(id = "new1", role = "user", time = Message.TimeInfo(created = 100L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = null))

        launchCatchUp(scope, repository, slices, "s1", onCacheWindow = { sid, w -> cachedWindows += sid to w })
        advanceUntilIdle()

        // anchor detected in fetched → no gap; staleNotice cleared.
        assertFalse(slices.chat.value.isLoadingMessages)
        assertFalse(slices.chat.value.staleNotice)
        assertEquals(1, cachedWindows.size)
    }

    @Test
    fun `launchCatchUp merges a full 5-message probe page directly`() = runTest {
        // remove-message-persistence Task 4: the non-contiguous gap mechanism
        // was deleted. A full 5-message probe page WITHOUT the anchor now
        // merges directly (the bigger history is recoverable via the manual
        // "load more" pager, not via an automatic backfill).
        val anchor = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor)) }
        coEvery { repository.probeLatestMessageIdForCurrent("s1") } returns cn.vectory.ocdroid.data.repository.ProbeResult(
            ok = true,
            messageID = "server-newer",
            updatedAt = 200L,
        )
        val fetched = (1..5).map { i ->
            MessageWithParts(info = Message(id = "new$i", role = "user", time = Message.TimeInfo(created = 100L + i)))
        }
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = "tailCursor"))

        launchCatchUp(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            currentProfileId = { "fp1" },
            onCacheWindow = { sid, w -> cachedWindows += sid to w },
        )
        advanceUntilIdle()

        // No delegation — the fetched 5 merge directly into the slice.
        assertFalse(slices.chat.value.isLoadingMessages)
        val ids = slices.chat.value.messages.map { it.id }
        assertTrue("anchor preserved across the merge", ids.contains("anchor"))
        assertTrue("fetched 5 merged into the slice", ids.containsAll(listOf("new1", "new2", "new3", "new4", "new5")))
        assertEquals(1, cachedWindows.size)
    }

    @Test
    fun `launchCatchUp does not write messages for a non-current session`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "current") }
        coEvery { repository.probeLatestMessageIdForCurrent("other") } returns cn.vectory.ocdroid.data.repository.ProbeResult(
            ok = true,
            messageID = "server-new",
            updatedAt = 200L,
        )
        val fetched = listOf(MessageWithParts(info = Message(id = "x", role = "user")))
        coEvery { repository.getMessagesPaged("other", any(), any()) } returns Result.success(MessagesPage(fetched, null))

        launchCatchUp(scope, repository, slices, "other")
        advanceUntilIdle()

        // session mismatch → no merge.
        assertTrue(slices.chat.value.messages.isEmpty())
        // §history-load-fix round-2 (gpter 🟠): stale (non-current session)
        // catchUp does NOT clear isLoadingMessages — deferred to the
        // session-guarded finally + SessionSwitcher (a switch resets chat
        // state). This test isolates the load without the switch, so the flag
        // the stale catchUp set remains true.
        assertTrue(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `launchCatchUp failure clears loading flag`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "s1") }
        coEvery { repository.probeLatestMessageIdForCurrent("s1") } returns cn.vectory.ocdroid.data.repository.ProbeResult(
            ok = false,
            httpStatus = 500,
        )
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.failure(IllegalStateException("tail fail"))

        launchCatchUp(scope, repository, slices, "s1")
        advanceUntilIdle()

        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `launchCatchUp preserves olderMessagesCursor and hasMoreMessages on resetLimit=false`() = runTest {
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                olderMessagesCursor = "preserved",
                hasMoreMessages = true,
            )
        }
        coEvery { repository.probeLatestMessageIdForCurrent("s1") } returns cn.vectory.ocdroid.data.repository.ProbeResult(
            ok = true,
            messageID = "server-new",
            updatedAt = 200L,
        )
        val fetched = listOf(MessageWithParts(info = Message(id = "new1", role = "user")))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, null))

        launchCatchUp(scope, repository, slices, "s1", onCacheWindow = { sid, w -> cachedWindows += sid to w })
        advanceUntilIdle()

        assertEquals("preserved", slices.chat.value.olderMessagesCursor)
        assertTrue(slices.chat.value.hasMoreMessages)
        assertEquals("preserved", cachedWindows.single().second.olderMessagesCursor)
    }

    @Test
    fun `launchCatchUp skips probe when SSE covers the session workdir`() = runTest {
        // R-20 Phase 2 (G6): when the SSE feed is live for the session's workdir
        // AND the session was cold-snapshotted, shouldProbe=false → skip.
        every { settingsManager.currentWorkdir } returns "/repo"
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchCatchUp(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            settingsManager = settingsManager,
            sseCurrentWorkdir = "/repo",
            sessionsEverColdSnapshotted = setOf("s1"),
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.probeLatestMessageIdForCurrent(any()) }
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    // ── remove-message-persistence final-review M6: merge order/parts + onColdSnapshot ──

    @Test
    fun `launchCatchUp merges in exact order and overrides parts for re-fetched ids`() = runTest {
        // mergeProbeIntoSlice (resetLimit=false): olderKept = src msgs not in
        // fetched AND older than the oldest fetched; merged = olderKept + fetched.
        // m_old (created 10) is older than the fetched window (oldest 50) and not
        // re-fetched → kept up front. m_mid IS re-fetched (same id) → its src
        // part is dropped and replaced by the fetched part.
        val mOld = Message(id = "m_old", role = "assistant", time = Message.TimeInfo(created = 10L))
        val mMid = Message(id = "m_mid", role = "assistant", time = Message.TimeInfo(created = 50L))
        val partOld = Part(id = "p_mid_old", type = "text", text = "old")
        val partNew = Part(id = "p_mid_new", type = "text", text = "new")
        val partNew2 = Part(id = "p_new", type = "text", text = "new2")
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(mOld, mMid),
                partsByMessage = mapOf("m_mid" to listOf(partOld)),
            )
        }
        coEvery { repository.probeLatestMessageIdForCurrent("s1") } returns cn.vectory.ocdroid.data.repository.ProbeResult(
            ok = true,
            messageID = "m_new",
            updatedAt = 200L,
        )
        val fetched = listOf(
            MessageWithParts(info = Message(id = "m_mid", role = "assistant", time = Message.TimeInfo(created = 50L)), parts = listOf(partNew)),
            MessageWithParts(info = Message(id = "m_new", role = "user", time = Message.TimeInfo(created = 100L)), parts = listOf(partNew2)),
        )
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = null))

        launchCatchUp(scope, repository, slices, "s1", onCacheWindow = { sid, w -> cachedWindows += sid to w })
        advanceUntilIdle()

        assertFalse(slices.chat.value.isLoadingMessages)
        assertEquals(
            "olderKept up front, fetched window after",
            listOf("m_old", "m_mid", "m_new"),
            slices.chat.value.messages.map { it.id },
        )
        assertEquals(
            "re-fetched id's part is overridden by the fetched part",
            listOf(partNew),
            slices.chat.value.partsByMessage["m_mid"],
        )
        assertEquals(listOf(partNew2), slices.chat.value.partsByMessage["m_new"])
        assertTrue("m_old had no part and is not re-fetched", !slices.chat.value.partsByMessage.containsKey("m_old"))
        assertEquals(1, cachedWindows.size)
    }

    @Test
    fun `launchCatchUp marks onColdSnapshot on the anchor-equals-server short-circuit`() = runTest {
        // anchor == serverNewest → no probe-page reload; the short-circuit still
        // marks the cold-snapshot baseline when this is the current session.
        val localNewest = Message(id = "m_new", role = "assistant", time = Message.TimeInfo(created = 100L))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(localNewest)) }
        coEvery { repository.probeLatestMessageIdForCurrent("s1") } returns cn.vectory.ocdroid.data.repository.ProbeResult(
            ok = true,
            messageID = "m_new",
            updatedAt = 100L,
        )
        val snapshotted = mutableListOf<String>()

        launchCatchUp(scope, repository, slices, "s1", onColdSnapshot = { snapshotted += it })
        advanceUntilIdle()

        assertEquals(listOf("s1"), snapshotted)
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `launchCatchUp marks onColdSnapshot after a successful tail merge`() = runTest {
        val anchor = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor)) }
        coEvery { repository.probeLatestMessageIdForCurrent("s1") } returns cn.vectory.ocdroid.data.repository.ProbeResult(
            ok = true,
            messageID = "server-newer",
            updatedAt = 200L,
        )
        val fetched = listOf(MessageWithParts(info = Message(id = "new1", role = "user", time = Message.TimeInfo(created = 100L))))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = null))
        val snapshotted = mutableListOf<String>()

        launchCatchUp(scope, repository, slices, "s1", onColdSnapshot = { snapshotted += it })
        advanceUntilIdle()

        assertEquals(listOf("s1"), snapshotted)
    }

    @Test
    fun `launchCatchUp does not mark onColdSnapshot on a session mismatch`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "current") }
        coEvery { repository.probeLatestMessageIdForCurrent("other") } returns cn.vectory.ocdroid.data.repository.ProbeResult(
            ok = true,
            messageID = "server-new",
            updatedAt = 200L,
        )
        coEvery { repository.getMessagesPaged("other", any(), any()) } returns Result.success(
            MessagesPage(listOf(MessageWithParts(info = Message(id = "x", role = "user"))), nextCursor = null),
        )
        val snapshotted = mutableListOf<String>()

        launchCatchUp(scope, repository, slices, "other", onColdSnapshot = { snapshotted += it })
        advanceUntilIdle()

        assertTrue("session mismatch must not establish a baseline", snapshotted.isEmpty())
    }

    @Test
    fun `launchCatchUp does not mark onColdSnapshot on a probe failure`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "s1") }
        coEvery { repository.probeLatestMessageIdForCurrent("s1") } returns cn.vectory.ocdroid.data.repository.ProbeResult(
            ok = false,
            httpStatus = 500,
        )
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.failure(IllegalStateException("tail fail"))
        val snapshotted = mutableListOf<String>()

        launchCatchUp(scope, repository, slices, "s1", onColdSnapshot = { snapshotted += it })
        advanceUntilIdle()

        assertTrue("probe failure must not establish a baseline", snapshotted.isEmpty())
    }

    // ── Task 1 (P1 lane): slim-mode probe routing ──────────────────────────

    /**
     * P1-T1: launchCatchUp MUST route the latest-message probe through the
     * boundary facade [OpenCodeRepository.probeLatestMessageIdForCurrent] so
     * that slim-mode hosts hit the sidecar's
     * `GET /slimapi/messages/{sid}?limit=1&mode=skeleton` (NOT the legacy
     * `GET /session/{sid}/message?limit=1` direct-opencode path).
     *
     * This test uses a REAL [OpenCodeRepository] + MockWebServer configured in
     * slim mode and pins the actual wire path. Before the fix, the code calls
     * `probeLatestMessageId` directly → the request hits the legacy path →
     * this test REDs (the legacy path is never enqueued, so takeRequest
     * times out / the path assertion fails).
     */
    @Test
    fun `launchCatchUp slimMode uses slim probe path not legacy`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            DebugLog.clear()
            val realRepo = OpenCodeRepository(
                mockk(relaxed = true),
                mockk(relaxed = true),
            )
            realRepo.identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore()
            realRepo.configure(
                baseUrl = server.url("/").toString().trimEnd('/'),
                slim = true,
            )

            // Slim-mode probe response: 200 + empty array (session exists, no
            // messages). probeLatestSlim → ProbeResult(ok=true, empty=true).
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("[]")
                    .setHeader("Content-Type", "application/json"),
            )

            val localNewest = Message(id = "m_local", role = "assistant", time = Message.TimeInfo(created = 100L))
            val testStore = SharedStateStore()
            testStore.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(localNewest)) }

            launchCatchUp(
                scope = scope,
                repository = realRepo,
                slices = testStore.slices,
                sessionId = "s1",
            )
            advanceUntilIdle()

            val request = server.takeRequest()
            assertEquals(
                "slim-mode probe MUST hit the sidecar path, NOT legacy /session/{sid}/message",
                "GET",
                request.method,
            )
            assertTrue(
                "path must be slim /slimapi/messages/{sid}?limit=1&mode=skeleton, got: ${request.path}",
                request.path!!.startsWith("/slimapi/messages/s1?"),
            )
            assertTrue(
                "path must contain mode=skeleton, got: ${request.path}",
                request.path!!.contains("mode=skeleton"),
            )
        } finally {
            server.shutdown()
        }
    }

    // ── Task 5 (P1 lane): four-combination JVM contract test ───────────────

    /**
     * P1-T5: real JVM contract test that verifies the four call-path routings
     * against a REAL [OpenCodeRepository] + MockWebServer configured in slim
     * mode. Pins the exact wire paths for:
     *
     * 1. status  → `GET /slimapi/sessions/status?directory=` (NOT legacy `/session/status`)
     * 2. message → `GET /slimapi/messages/{sid}/since/{ts}` (NOT legacy `/session/{sid}/message`)
     * 3. probe   → `GET /slimapi/messages/{sid}?limit=1&mode=skeleton` (NOT legacy)
     * 4. REST fallback → thin_route_not_found triggers single-full fallback
     *
     * Avoids empty-assertion enumeration: each combination asserts BOTH the
     * path hit AND a meaningful behavioral outcome (e.g. message count,
     * status map shape).
     */
    @Test
    fun `four-combination contract - status uses slim Plan-A endpoint`() = runTest {
        // §3.1 Plan-A: in slim mode, getSlimapiSessionsStatus redirects to
        // GET /slimapi/sessions/status?directory= (the Plan-A endpoint).
        val server = MockWebServer()
        server.start()
        try {
            DebugLog.clear()
            val realRepo = OpenCodeRepository(
                mockk(relaxed = true),
                mockk(relaxed = true),
            )
            realRepo.identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore()
            realRepo.configure(
                baseUrl = server.url("/").toString().trimEnd('/'),
                slim = true,
            )

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"s1":{"type":"idle"},"s2":{"type":"busy"}}""")
                    .setHeader("Content-Type", "application/json"),
            )
            val statusResult = realRepo.getSlimapiSessionsStatus("/proj")
            assertTrue("status call must succeed: ${statusResult.exceptionOrNull()}", statusResult.isSuccess)
            val statusMap = statusResult.getOrThrow()
            assertEquals("status map carries both sessions", 2, statusMap.size)
            assertEquals("idle", statusMap["s1"]?.type)
            assertEquals("busy", statusMap["s2"]?.type)
            val statusReq = server.takeRequest()
            assertTrue(
                "status path must be /slimapi/sessions/status?directory=..., got: ${statusReq.path}",
                statusReq.path!!.startsWith("/slimapi/sessions/status"),
            )
            assertTrue(
                "status path must include directory query param, got: ${statusReq.path}",
                statusReq.path!!.contains("directory="),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `four-combination contract - probe uses slim path`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            DebugLog.clear()
            val realRepo = OpenCodeRepository(
                mockk(relaxed = true),
                mockk(relaxed = true),
            )
            realRepo.identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore()
            realRepo.configure(
                baseUrl = server.url("/").toString().trimEnd('/'),
                slim = true,
            )

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""[{"info":{"id":"m_newest","role":"assistant","time":{"created":300,"updated":400}},"parts":[]}]""")
                    .setHeader("Content-Type", "application/json"),
            )
            val probeResult = realRepo.probeLatestSlim("s1")
            assertTrue("probe must be ok", probeResult.ok)
            assertEquals("m_newest", probeResult.messageID)
            assertEquals(400L, probeResult.updatedAt)
            val probeReq = server.takeRequest()
            assertTrue(
                "probe path must be slim /slimapi/messages/{sid}?limit=1&mode=skeleton, got: ${probeReq.path}",
                probeReq.path!!.startsWith("/slimapi/messages/s1?"),
            )
            assertTrue(
                "probe must contain mode=skeleton, got: ${probeReq.path}",
                probeReq.path!!.contains("mode=skeleton"),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `four-combination contract - expand uses direct single-full path`() = runBlocking {
        // lite-v2-dev: expandMessagesFullBatch iterates individually per
        // messageId via getSlimapiMessageFull, not batch+tier-2 fallback.
        // The batch path is removed; a single GET /slimapi/messages/{sid}/full/{mid}
        // is made per message.
        val server = MockWebServer()
        server.start()
        try {
            DebugLog.clear()
            val realRepo = OpenCodeRepository(
                mockk(relaxed = true),
                mockk(relaxed = true),
            )
            realRepo.identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore()
            realRepo.configure(
                baseUrl = server.url("/").toString().trimEnd('/'),
                slim = true,
            )

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"info":{"id":"m_full","role":"user"},"parts":[{"id":"p1","type":"text","text":"expanded"}]}""")
                    .setHeader("Content-Type", "application/json"),
            )

            val token = realRepo.captureSlimCommitToken()
            val expandResult = realRepo.expandMessagesFullBatch(sessionId = "s1", messageIds = setOf("m_full"), token = token)
            assertTrue(
                "expand must succeed. Actual: ${expandResult::class.simpleName} $expandResult",
                expandResult is cn.vectory.ocdroid.data.repository.ExpandOutcome.Ok,
            )
            val ok = expandResult as cn.vectory.ocdroid.data.repository.ExpandOutcome.Ok
            assertFalse("lite-v2-dev always uses usedBatch=false", ok.usedBatch)
            assertEquals("m_full loaded via direct full", listOf("m_full"), ok.items.map { it.info.id })
            val fullReq = server.takeRequest()
            assertEquals(
                "single-full path must be /slimapi/messages/{sid}/full/{mid}, got: ${fullReq.path}",
                "/slimapi/messages/s1/full/m_full",
                fullReq.path,
            )
        } finally {
            server.shutdown()
        }
    }
}
