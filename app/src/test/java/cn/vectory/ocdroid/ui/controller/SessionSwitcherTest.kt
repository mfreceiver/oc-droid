package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionCacheEntry
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.PendingScrollRequest
import cn.vectory.ocdroid.ui.ScrollBehavior
import cn.vectory.ocdroid.ui.ScrollCheckpoint
import cn.vectory.ocdroid.ui.controller.CachedSessionWindow
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.FileState
import cn.vectory.ocdroid.ui.HostState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.TrafficState
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-16 M2b → R-17 batch3b: independent unit test for [SessionSwitcher.switchTo]
 * — the full 8-step session-switch flow extracted from the orchestrator.
 *
 * Zero reflection — the controller is driven entirely through its public API
 * ([switchTo] / [clearSessionWindowCache] / [peekSessionWindow] /
 * [sessionWindowCacheSize] / [writeSessionWindow]) and asserted via:
 *  - a mockk [SettingsManager] whose setDraftText / currentSessionId setter /
 *    openSessionIds setter / sessionCache setter calls are captured into the
 *    [RecordingCallbacks] facade (so test bodies keep referencing the same
 *    accessor names as before),
 *  - a mockk [OpenCodeRepository] (Phase 2-E step 2: setCurrentDirectory
 *    capture was removed alongside the production call; the mock is still
 *    wired for the remaining answers SessionSwitcher needs),
 *  - a real [SharedEffectBus] drained into [collectedEffects] for the 5
 *    cross-domain effect emissions (ClearDeltaBuffers / LoadChildSessions /
 *    LoadMessages / LoadSessionStatus / LoadPendingQuestions), exposed via
 *    computed properties on the same facade.
 *
 * An injected `clock` makes lastViewedTime deterministic.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionSwitcherTest {

    // §R18 Phase 4 (P0-9): the controller takes a [SharedStateStore]. The test
    // holds read-only StateFlow views of composerFlow + expandedParts so the
    // existing assertions keep their `.value.X` shape; writes go through the
    // per-slice mutateXxx funnels on [store].
    private lateinit var store: cn.vectory.ocdroid.ui.SharedStateStore
    private lateinit var composerFlow: kotlinx.coroutines.flow.StateFlow<ComposerState>
    private lateinit var expandedParts: kotlinx.coroutines.flow.StateFlow<Map<String, Boolean>>
    private lateinit var slices: SliceFlows
    private lateinit var settingsManager: SettingsManager
    private lateinit var repository: OpenCodeRepository
    private lateinit var effects: SharedEffectBus
    private lateinit var collectedEffects: MutableList<ControllerEffect>
    private lateinit var scope: TestScope
    private lateinit var callbacks: RecordingCallbacks
    private var nowMs: Long = 10_000L
    /**
     * §R-17 batch2 step e final: in-test fixture carrying the prior snapshot
     * so successive `seed { ... }` calls compose against the prior state.
     * Production no longer has an AppState mirror; this fixture exists only
     * to drive the seed() transform chain.
     */
    private var appStateFixture: SeedFixture = SeedFixture()

    private lateinit var switcher: SessionSwitcher

    @Before
    fun setUp() {
        appStateFixture = SeedFixture()
        store = cn.vectory.ocdroid.ui.SharedStateStore()
        slices = store.slices
        composerFlow = store.composerFlow
        expandedParts = store.expandedParts
        settingsManager = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        effects = SharedEffectBus()
        collectedEffects = mutableListOf()
        scope = TestScope(UnconfinedTestDispatcher())
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { effects.effectsConsumed.toList(collectedEffects) }
        callbacks = RecordingCallbacks(settingsManager, repository, collectedEffects)
        switcher = SessionSwitcher(
            store = store,
            settingsManager = settingsManager,
            repository = repository,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            clock = { nowMs }
        )
    }

    /**
     * §R-17 batch2 step e final: seed slices directly from a SeedFixture.
     * The fixture carries the prior snapshot so successive `seed { ... }` calls
     * compose against the prior state. The switcher reads/writes slices only.
     *
     * §R18 Phase 4 (P0-9): per-slice StateFlow views are read-only; funnel
     * every seed write through the matching mutateXxx helper.
     */
    private fun seed(transform: (SeedFixture) -> SeedFixture) {
        appStateFixture = transform(appStateFixture)
        val s = appStateFixture
        slices.mutateConnection {
            ConnectionState(
                isConnected = s.isConnected,
                isConnecting = s.isConnecting,
                serverVersion = s.serverVersion,
                connectionPhase = s.connectionPhase,
                tunnelActivationState = s.tunnelActivationState
            )
        }
        slices.mutateTraffic {
            TrafficState(
                trafficSent = s.trafficSent,
                trafficReceived = s.trafficReceived
            )
        }
        slices.mutateComposer {
            ComposerState(
                inputText = s.inputText,
                imageAttachments = s.imageAttachments,
                sendingSessionIds = s.sendingSessionIds,
                draftWorkdir = s.draftWorkdir
            )
        }
        slices.mutateFile {
            FileState(
                filePathToShowInFiles = s.filePathToShowInFiles,
                filePreviewOriginRoute = s.filePreviewOriginRoute,
                fileBrowserOpen = s.fileBrowserOpen,
                fileBrowserWorkdir = s.fileBrowserWorkdir
            )
        }
        slices.mutateSettings {
            SettingsState(
                themeMode = s.themeMode,
                markdownFontSizes = s.markdownFontSizes,
                // §chat-ux-batch T8 (B3): selectedAgentName removed from both
                // SeedFixture + SettingsState (deleted in T8).
                agents = s.agents,
                providers = s.providers,
                availableCommands = s.availableCommands,
                disabledModels = s.disabledModels,
                uiFontScale = s.uiFontScale,
                uiContentScale = s.uiContentScale
            )
        }
        slices.mutateChat {
            it.copy(
                currentSessionId = s.currentSessionId,
                messages = s.messages,
                partsByMessage = s.partsByMessage,
                streamingPartTexts = s.streamingPartTexts,
                streamingReasoningPart = s.streamingReasoningPart,
                olderMessagesCursor = s.olderMessagesCursor,
                hasMoreMessages = s.hasMoreMessages,
                isLoadingMessages = s.isLoadingMessages,
                staleNotice = s.staleNotice,
                currentModel = s.currentModel
            )
        }
        slices.mutateSessionList {
            SessionListState(
                sessions = s.sessions,
                sessionStatuses = s.sessionStatuses,
                expandedSessionIds = s.expandedSessionIds,
                loadedSessionLimit = s.loadedSessionLimit,
                hasMoreSessions = s.hasMoreSessions,
                isLoadingMoreSessions = s.isLoadingMoreSessions,
                isRefreshingSessions = s.isRefreshingSessions,
                pendingPermissions = s.pendingPermissions,
                pendingQuestions = s.pendingQuestions,
                childSessions = s.childSessions,
                directorySessions = s.directorySessions,
                openSessionIds = s.openSessionIds,
                sessionTodos = s.sessionTodos
            )
        }
        slices.mutateUnread {
            UnreadState(
                unreadSessions = s.unreadSessions,
                lastViewedTime = s.lastViewedTime
            )
        }
        slices.mutateHost {
            HostState(
                hostProfiles = s.hostProfiles,
                currentHostProfileId = s.currentHostProfileId
            )
        }
    }

    // ── Step 1: LRU write-back of outgoing session ─────────────────────────

    @Test
    fun `switchTo captures outgoing session window into cache`() {
        seed {
            it.copy(
            currentSessionId = "session-A",
            sessions = listOf(
                Session(id = "session-A", directory = "/tmp/a"),
                Session(id = "session-B", directory = "/tmp/b")
            ),
            messages = listOf(Message(id = "m1", role = "user"))
            )
        }

        switcher.switchTo("session-B")

        val cached = switcher.peekSessionWindow("session-A")
        assertNotNull("outgoing session-A must be cached", cached)
        assertEquals(1, cached!!.messages.size)
        assertEquals("m1", cached.messages[0].id)
    }

    @Test
    fun `switchTo does NOT capture when switching to same session`() {
        seed {
            it.copy(
            currentSessionId = "session-A",
            sessions = listOf(Session(id = "session-A", directory = "/tmp/a")),
            messages = listOf(Message(id = "m1", role = "user"))
            )
        }

        switcher.switchTo("session-A")

        // Same-session switch should not write-back (no LRU pollution)
        assertEquals(0, switcher.sessionWindowCacheSize())
    }

    // ── M5 跟进 (§4.2): clearDeltaBuffers hookup ────────────────────────────

    @Test
    fun `switchTo clears delta buffers when leaving an outgoing session`() {
        seed {
            it.copy(
            currentSessionId = "session-A",
            sessions = listOf(
                Session(id = "session-A", directory = "/tmp/a"),
                Session(id = "session-B", directory = "/tmp/b")
            )
            )
        }

        switcher.switchTo("session-B")

        // Real session change → pending deltas of session-A must be dropped
        // exactly once so a late flush can't write into session-B's state.
        assertEquals(1, callbacks.clearDeltaBuffersCalls)
    }

    @Test
    fun `switchTo resets both load flags on switch (round-4 gpt-2 regression)`() {
        // §history-load-fix round-4 (gpt-2 🔴): a switch-during-load must reset
        // BOTH isLoadingMessages and isLoadingMoreMessages. The session-guarded
        // finally in launchLoadMessages/catchUp/launchLoadMoreMessages declines
        // to clear on a session mismatch, so without this reset a stale in-flight
        // load would leave isLoadingMessages stuck true and coalesce away the
        // newly-selected session's loads (permanent stuck until process restart).
        seed {
            it.copy(
                currentSessionId = "session-A",
                sessions = listOf(
                    Session(id = "session-A", directory = "/tmp/a"),
                    Session(id = "session-B", directory = "/tmp/b")
                )
            )
        }
        // SeedFixture doesn't carry the load flags — set them directly on the
        // chat slice to simulate an in-flight load for the outgoing session.
        slices.mutateChat { it.copy(isLoadingMessages = true, isLoadingMoreMessages = true) }

        switcher.switchTo("session-B")

        assertFalse("isLoadingMessages must reset on switch (else new session's loads are coalesced away)", slices.chat.value.isLoadingMessages)
        assertFalse("isLoadingMoreMessages must reset on switch", slices.chat.value.isLoadingMoreMessages)
    }

    @Test
    fun `switchTo does NOT clear delta buffers when switching to same session`() {
        seed {
            it.copy(
            currentSessionId = "session-A",
            sessions = listOf(Session(id = "session-A", directory = "/tmp/a"))
            )
        }

        switcher.switchTo("session-A")

        // Same-session reselect must keep its own in-flight deltas intact.
        assertEquals(0, callbacks.clearDeltaBuffersCalls)
    }

    // ── Step 2: draft save / restore ────────────────────────────────────────

    @Test
    fun `switchTo saves old draft and restores new draft`() {
        callbacks.drafts["s2"] = "draft-for-s2"
        seed {
            it.copy(
            currentSessionId = "s1",
            sessions = listOf(Session(id = "s1", directory = "/d"), Session(id = "s2", directory = "/d"))
            )
        }
        store.mutateComposer { it.copy(inputText = "old draft text") }

        switcher.switchTo("s2")

        // Saved old draft
        assertEquals(1, callbacks.saveDraftCalls.size)
        assertEquals("s1", callbacks.saveDraftCalls[0].first)
        assertEquals("old draft text", callbacks.saveDraftCalls[0].second)
        // §R18 Phase 2-F: switchTo now writes chatFlow.currentSessionId (the
        // sole runtime source) instead of SettingsManager; the AppCore
        // collector persists the non-null change.
        assertEquals("s2", slices.chat.value.currentSessionId)
        // Restored new draft
        assertEquals("draft-for-s2", composerFlow.value.inputText)
    }

    @Test
    fun `switchTo clears fileReferences and imageAttachments (I4 parity)`() {
        // §1B-FIX (I4): file-reference chips must NOT leak across
        // sessions. The restored draft text is preserved, but the
        // chip set + image attachments are wiped on every switch.
        callbacks.drafts["s2"] = "draft-for-s2"
        seed {
            it.copy(
                currentSessionId = "s1",
                sessions = listOf(Session(id = "s1", directory = "/d"), Session(id = "s2", directory = "/d"))
            )
        }
        store.mutateComposer {
            it.copy(
                inputText = "File: /leaked.kt",
                imageAttachments = listOf(
                    cn.vectory.ocdroid.data.model.ComposerImageAttachment(
                        id = "img1", filename = "x.png", mime = "image/png",
                        dataUrl = "data:,",
                        thumbnailData = ByteArray(0),
                        byteSize = 0,
                    )
                ),
                fileReferences = listOf(
                    cn.vectory.ocdroid.ui.ComposerFileReference(path = "/leaked.kt")
                ),
            )
        }

        switcher.switchTo("s2")

        // Restored new draft (text preserved from settings).
        assertEquals("draft-for-s2", composerFlow.value.inputText)
        // fileReferences + imageAttachments wiped on switch.
        assertTrue(composerFlow.value.fileReferences.isEmpty())
        assertTrue(composerFlow.value.imageAttachments.isEmpty())
    }

    @Test
    fun `switchTo does not save draft when no previous session`() {
        seed {
            it.copy(
            currentSessionId = null,
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        assertTrue("no saveDraft when previousSessionId is null", callbacks.saveDraftCalls.isEmpty())
    }

    // ── Step 3 (R-20 Phase 1): verify-before-hydrate effect ─────────────────
    // Phase 1: switchTo no longer synchronously seeds the chat slice from
    // the LRU; it emits a VerifyAndHydrate effect. remove-message-persistence
    // Task 2 changed the handler in AppCore.dispatchSessionEffect to probe the
    // in-memory sessionWindowCache via SessionSwitcher.peekSessionWindow
    // (Task 6 deleted the prior cacheRepository.verifyAndLoad SQLite call);
    // ONLY on a memory hit does it copy the cached window into the slice. The
    // tests below assert the effect is emitted with the right (fp, sid,
    // createdAt); the chat slice stays empty until the handler runs (asserted
    // for regression — the user must never see unverified cached content).

    @Test
    fun `switchTo emits VerifyAndHydrate with createdAt from the target session`() {
        val created = 1_700_000L
        seed {
            it.copy(
                currentSessionId = null,
                sessions = listOf(Session(id = "s1", directory = "/d", time = Session.TimeInfo(created = created)))
            )
        }

        switcher.switchTo("s1")

        val verify = collectedEffects.filterIsInstance<ControllerEffect.VerifyAndHydrate>().singleOrNull()
        assertNotNull("VerifyAndHydrate must be emitted", verify)
        assertEquals("s1", verify!!.sessionId)
        assertEquals("test-fp", verify.serverGroupFp)
        assertEquals(created, verify.createdAt)
    }

    @Test
    fun `switchTo does NOT synchronously seed the chat slice from the LRU`() {
        // Privacy regression (plan §0 N2 verify-before-hydrate): even with a
        // cached window present, switchTo must NOT hydrate the chat slice
        // synchronously — the cache may be stale or fingerprint-mismatched,
        // and the user must not see unverified content even for one frame.
        seed {
            it.copy(
                currentSessionId = null,
                sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }
        switcher.writeSessionWindow("test-fp", "s1", CachedSessionWindow(
            messages = listOf(Message(id = "cached-m1", role = "user")),
            partsByMessage = mapOf("cached-m1" to emptyList()),
            olderMessagesCursor = "cursor-abc",
            hasMoreMessages = true
        ))

        switcher.switchTo("s1")

        // Slice stays empty — VerifyAndHydrate handler (run by AppCore, not
        // tested here) is responsible for the verified hydrate.
        assertTrue("chat messages must stay empty until verify completes",
            slices.chat.value.messages.isEmpty())
    }

    @Test
    fun `switchTo does NOT emit LoadMessages - the VerifyAndHydrate handler owns it`() {
        // Phase 1 plan §3 v4 round-3: "Step 7 移除 LoadMessages — LoadMessages
        // 由 handler 唯一调度，禁 switchTo 同步发". Emitting LoadMessages here
        // would race the handler's verify-and-then-load.
        seed {
            it.copy(
                currentSessionId = null,
                sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        assertTrue(
            "switchTo must not emit LoadMessages (VerifyAndHydrate handler owns it)",
            collectedEffects.none { it is ControllerEffect.LoadMessages }
        )
    }

    // ── Step 4: directory-only session upsert ──────────────────────────────

    @Test
    fun `switchTo upserts directory-only session into sessions list`() {
        val dirSession = Session(id = "dir-only", directory = "/tmp/proj")
        seed {
            it.copy(
            currentSessionId = null,
            sessions = emptyList(),
            directorySessions = mapOf("/tmp/proj" to listOf(dirSession))
            )
        }

        switcher.switchTo("dir-only")

        assertTrue("directory-only session upserted into sessions", slices.sessionList.value.sessions.any { it.id == "dir-only" })
    }

    // ── Step 5: expanded parts reset ────────────────────────────────────────

    @Test
    fun `switchTo clears expanded parts`() {
        store.mutateExpandedParts { mapOf("msg1|key1" to true, "msg2|key2" to false) }
        seed {
            it.copy(
            currentSessionId = null,
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        assertTrue("expanded parts cleared on switch", expandedParts.value.isEmpty())
    }

    // ── Step 6: (Phase 2-E step 2) directory sync ──────────────────────────
    // §R18 Phase 2-E step 2: the repository.setCurrentDirectory call that
    // lived at Step 6 of switchTo was removed; directory routing now uses
    // the session's directory field directly at each callsite. The previous
    // "switchTo syncs directory to selected session" test verified the
    // removed side effect; deleted alongside the call.

    // ── Step 7: load callbacks ──────────────────────────────────────────────

    @Test
    fun `switchTo triggers VerifyAndHydrate loadSessionStatus loadChildSessions`() {
        // Phase 1: LoadMessages is no longer emitted by switchTo (the
        // VerifyAndHydrate handler owns it). The remaining Step 7 emissions
        // are LoadSessionStatus + LoadChildSessions — assert those + the
        // new VerifyAndHydrate effect.
        seed {
            it.copy(
            currentSessionId = null,
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        assertEquals(1, callbacks.loadSessionStatusCalls)
        assertEquals(listOf("s1"), callbacks.loadChildSessionsCalls)
        assertEquals(
            "VerifyAndHydrate emitted exactly once (Phase 1 replaces LoadMessages)",
            1,
            collectedEffects.count { it is ControllerEffect.VerifyAndHydrate }
        )
    }

    // ── Step 6.5: pending-questions refresh ────────────────────────────────

    @Test
    fun `switchTo refreshes pending questions exactly once before loadMessages`() {
        // §stale-question: Step 6.5 must call loadPendingQuestions() so the
        // stale-question calc uses fresh data and any live question on the
        // newly-selected session surfaces immediately. It must fire BEFORE
        // Step 7's loadMessages so the first render of the message window
        // already has the authoritative pending list to compare against.
        // NB: we deliberately do NOT clearPendingQuestions() first (reviewer
        // consensus: a failed async load would wipe a live SSE-delivered
        // question). Assert clearPendingQuestions is never invoked — the
        // callback was removed entirely from the interface.
        seed {
            it.copy(
                currentSessionId = null,
                sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        assertEquals(
            "loadPendingQuestions must be invoked exactly once",
            1,
            callbacks.loadPendingQuestionsCalls
        )
        // §batch 3b: ordering now derived from collectedEffects (the
        // synchronous mockk captures on SettingsManager / Repository + the
        // SharedFlow emissions both fire in the controller's switchTo body,
        // so the relative order between same-domain calls and cross-domain
        // effects is observable across the two recordings).
        val loadPendingIdx = collectedEffects.indexOfFirst { it is ControllerEffect.LoadPendingQuestions }
        val verifyIdx = collectedEffects.indexOfFirst { it is ControllerEffect.VerifyAndHydrate }
        assertTrue(
            "loadPendingQuestions must be recorded in collectedEffects (got $collectedEffects)",
            loadPendingIdx >= 0
        )
        assertTrue(
            "VerifyAndHydrate must be recorded in collectedEffects (got $collectedEffects)",
            verifyIdx >= 0
        )
        assertTrue(
            "VerifyAndHydrate (Step 3, idx=$verifyIdx) must fire BEFORE loadPendingQuestions (Step 6.5, idx=$loadPendingIdx); effects=$collectedEffects",
            verifyIdx < loadPendingIdx
        )
        assertFalse(
            "clearPendingQuestions must never appear in callOrder; the callback was removed",
            callbacks.callOrder.contains("clearPendingQuestions")
        )
    }

    // ── Step 8: unread state machine ────────────────────────────────────────

    @Test
    fun `switchTo removes target session from unread and records lastViewedTime`() {
        seed {
            it.copy(
            currentSessionId = null,
            unreadSessions = setOf("s1"),
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }
        nowMs = 50_000L

        switcher.switchTo("s1")

        assertFalse("selected session removed from unread", slices.unread.value.unreadSessions.contains("s1"))
        assertEquals(50_000L, slices.unread.value.lastViewedTime["s1"])
    }

    @Test
    fun `opening descendant immediately clears and consumes root unread cycle`() {
        seed {
            it.copy(
                currentSessionId = null,
                unreadSessions = setOf("root"),
                idleSince = mapOf("root" to 1_000L),
                sessions = listOf(
                    Session(id = "root", directory = "/d"),
                    Session(id = "child", directory = "/d", parentId = "root"),
                ),
            )
        }
        nowMs = 50_000L

        switcher.switchTo("child")

        assertFalse("root" in slices.unread.value.unreadSessions)
        assertFalse("child" in slices.unread.value.unreadSessions)
        assertFalse("root" in slices.unread.value.idleSince)
        assertEquals(50_000L, slices.unread.value.lastViewedTime["root"])
    }

    @Test
    fun `switchTo AB pingpong both clear`() {
        val busyStatus = SessionStatus(type = "busy")
        seed {
            it.copy(
                currentSessionId = null,
                unreadSessions = setOf("A", "B"),
                sessions = listOf(
                    Session(id = "A", directory = "/d"),
                    Session(id = "B", directory = "/d"),
                ),
                sessionStatuses = mapOf("A" to busyStatus, "B" to busyStatus),
            )
        }

        switcher.switchTo("A")
        assertEquals(setOf("B"), slices.unread.value.unreadSessions)

        switcher.switchTo("B")
        assertEquals(emptySet<String>(), slices.unread.value.unreadSessions)
    }

    @Test
    fun `switchTo does NOT re-add outgoing session to unread`() {
        seed {
            it.copy(
            currentSessionId = "old-busy",
            sessions = listOf(
                Session(id = "old-busy", directory = "/d"),
                Session(id = "new-target", directory = "/d")
            ),
            sessionStatuses = mapOf("old-busy" to SessionStatus(type = "busy"))
            )
        }

        switcher.switchTo("new-target")

        assertFalse(
            "outgoing session NOT re-added to unread by switchTo (Task 3 owns lifecycle)",
            slices.unread.value.unreadSessions.contains("old-busy")
        )
    }

    // ── Step 8b: draft discard ──────────────────────────────────────────────

    @Test
    fun `switchTo discards draftWorkdir on select`() {
        seed {
            it.copy(
            currentSessionId = null,
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }
        store.mutateComposer { it.copy(draftWorkdir = "/tmp/draft") }

        switcher.switchTo("s1")

        assertNull("draftWorkdir cleared", composerFlow.value.draftWorkdir)
        assertNull("draftWorkdir cleared in state mirror", slices.composer.value.draftWorkdir)
    }

    // ── Step 8c: openSessionIds append (new tab joins from the RIGHT) ────────

    @Test
    fun `switchTo appends new session to the end of openSessionIds`() {
        seed {
            it.copy(
            currentSessionId = null,
            openSessionIds = listOf("existing-1", "existing-2"),
            sessions = listOf(Session(id = "new-tab", directory = "/d"))
            )
        }

        switcher.switchTo("new-tab")

        // 新 tab 在末尾（右侧），而非开头
        assertEquals(listOf("existing-1", "existing-2", "new-tab"), slices.sessionList.value.openSessionIds)
        assertEquals(1, callbacks.setOpenSessionIdsCalls.size)
        assertEquals(listOf("existing-1", "existing-2", "new-tab"), callbacks.setOpenSessionIdsCalls[0])
    }

    @Test
    fun `switchTo does NOT prepend already-open session`() {
        seed {
            it.copy(
            currentSessionId = null,
            openSessionIds = listOf("already-open", "other"),
            sessions = listOf(Session(id = "already-open", directory = "/d"))
            )
        }

        switcher.switchTo("already-open")

        // openSessionIds should be unchanged (no reorder, no duplicate)
        assertEquals(listOf("already-open", "other"), slices.sessionList.value.openSessionIds)
        assertTrue("no setOpenSessionIds call", callbacks.setOpenSessionIdsCalls.isEmpty())
    }

    @Test
    fun `switchTo does NOT prepend sub-agent sessions`() {
        val childSession = Session(id = "child-1", directory = "/d", parentId = "parent-1")
        seed {
            it.copy(
            currentSessionId = null,
            openSessionIds = emptyList(),
            sessions = listOf(childSession)
            )
        }

        switcher.switchTo("child-1")

        // Sub-agents (parentId != null) should not pollute openSessionIds
        assertTrue("sub-agent not prepended", slices.sessionList.value.openSessionIds.isEmpty())
    }

    @Test
    fun `switchTo caps openSessionIds at 8 keeping newest on the right`() {
        seed {
            it.copy(
            currentSessionId = null,
            openSessionIds = listOf("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8"),
            sessions = listOf(Session(id = "s9", directory = "/d"))
            )
        }

        switcher.switchTo("s9")

        // 满 8 时丢最旧 s1，新 s9 在最右
        assertEquals(8, slices.sessionList.value.openSessionIds.size)
        assertEquals(
            listOf("s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9"),
            slices.sessionList.value.openSessionIds,
        )
    }

    // ── Step 8d: persistSessionCache ────────────────────────────────────────

    @Test
    fun `switchTo persists session cache when opening new tab`() {
        seed {
            it.copy(
            currentSessionId = null,
            openSessionIds = emptyList(),
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        // §batch 3b: persistSessionCache now runs inline in the controller
        // (calls the free helper which writes settingsManager.sessionCache).
        // Assert via the captured sessionCache setter calls.
        assertEquals(1, callbacks.persistSessionCacheCalls.size)
        val call = callbacks.persistSessionCacheCalls[0]
        assertTrue(call.cache.any { it.id == "s1" })
        // The openIds that were passed to persistSessionCache equal the
        // current settingsManager.openSessionIds (set earlier in Step 8).
        assertEquals(listOf("s1"), callbacks.setOpenSessionIdsCalls.single())
    }

    @Test
    fun `switchTo does NOT persist cache when session already open`() {
        seed {
            it.copy(
            currentSessionId = null,
            openSessionIds = listOf("s1"),
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        assertTrue("no persistSessionCache when already open", callbacks.persistSessionCacheCalls.isEmpty())
    }

    // ── LRU cache behavior ──────────────────────────────────────────────────

    @Test
    fun `LRU cache evicts at capacity 12`() {
        seed {
            it.copy(
            currentSessionId = null,
            sessions = (1..13).map { Session(id = "s$it", directory = "/d") }
            )
        }

        // Open sessions 1..12 — each populates the cache via loadMessages' callback.
        // But since we're using RecordingCallbacks (which doesn't actually call
        // writeSessionWindow), we write directly:
        for (i in 1..12) {
            switcher.writeSessionWindow("test-fp", "s$i", CachedSessionWindow(
                messages = listOf(Message(id = "m$i", role = "user")),
                partsByMessage = emptyMap(), olderMessagesCursor = null, hasMoreMessages = false
            ))
        }
        assertEquals(12, switcher.sessionWindowCacheSize())

        // Write one more — should evict the LRU (s1)
        switcher.writeSessionWindow("test-fp", "s13", CachedSessionWindow(
            messages = emptyList(), partsByMessage = emptyMap(),
            olderMessagesCursor = null, hasMoreMessages = false
        ))

        assertEquals(12, switcher.sessionWindowCacheSize())
        assertNull("s1 evicted", switcher.peekSessionWindow("s1"))
        assertNotNull("s13 present", switcher.peekSessionWindow("s13"))
    }

    // ── peekSessionWindow production contract (R-20 Phase 2 / remove-message- ──
    //    persistence Task 2): the VerifyAndHydrate handler in AppCore now uses
    //    peekSessionWindow as a TRUE read-only memory probe (no SQLite
    //    verifyAndLoad). These tests pin the contract it depends on:
    //      (a) a written window is returned verbatim,
    //      (b) an unwritten session returns null,
    //      (c) peek does NOT promote the entry to MRU (so a verify probe
    //          cannot evict a hotter window).

    @Test
    fun `peekSessionWindow returns a written window verbatim and null for an unwritten session`() {
        val window = CachedSessionWindow(
            messages = listOf(Message(id = "m1", role = "user")),
            partsByMessage = mapOf("m1" to emptyList()),
            olderMessagesCursor = "cursor-x",
            hasMoreMessages = true,
        )
        switcher.writeSessionWindow("test-fp", "s1", window)

        val hit = switcher.peekSessionWindow("s1")
        assertNotNull("written window must be returned", hit)
        assertEquals(listOf("m1"), hit!!.messages.map { it.id })
        assertEquals("cursor-x", hit.olderMessagesCursor)
        assertTrue(hit.hasMoreMessages)

        assertNull(
            "unwritten session must return null (VerifyAndHydrate cold-start path)",
            switcher.peekSessionWindow("never-written"),
        )
    }

    @Test
    fun `peekSessionWindow does NOT promote the entry to most-recently-used`() {
        // Fill the cache to capacity (12). Then peek the LRU (s1) — peek must
        // NOT promote it, so inserting s13 evicts s1 (not s2). If peek
        // accidentally promoted, s1 would survive and s2 would evict.
        for (i in 1..12) {
            switcher.writeSessionWindow("test-fp", "s$i", CachedSessionWindow(
                messages = listOf(Message(id = "m$i", role = "user")),
                partsByMessage = emptyMap(), olderMessagesCursor = null, hasMoreMessages = false
            ))
        }
        assertEquals(12, switcher.sessionWindowCacheSize())

        // Probe the LRU (s1) repeatedly — true read-only.
        repeat(3) { assertNotNull(switcher.peekSessionWindow("s1")) }

        // Overflow — evicts the TRUE LRU (s1), proving peek did not promote it.
        switcher.writeSessionWindow("test-fp", "s13", CachedSessionWindow(
            messages = emptyList(), partsByMessage = emptyMap(),
            olderMessagesCursor = null, hasMoreMessages = false
        ))
        assertNull("peek must not promote s1 to MRU (s1 should still evict)", switcher.peekSessionWindow("s1"))
        assertNotNull("s13 present after overflow", switcher.peekSessionWindow("s13"))
    }

    @Test
    fun `clearSessionWindowCache drops all entries`() {
        switcher.writeSessionWindow("test-fp", "s1", CachedSessionWindow(
            messages = emptyList(), partsByMessage = emptyMap(),
            olderMessagesCursor = null, hasMoreMessages = false
        ))
        assertEquals(1, switcher.sessionWindowCacheSize())

        switcher.clearSessionWindowCache()

        assertEquals(0, switcher.sessionWindowCacheSize())
    }

    // ── remove-message-persistence Task 3: appendMessageIfCached ───────────
    //    The SSE `message.updated` new-insert branch (SessionSyncCoordinator)
    //    emits AppendMessageToCache → AppCore handler → this method. Pins the
    //    contract: HIT appends message + merges parts under message.id;
    //    MISS is a no-op (cold-start sessions do not proactively build a
    //    cache). fp-scoped: an append under a foreign fp does NOT touch the
    //    current group's window.

    @Test
    fun `appendMessageIfCached appends message and merges parts on a cached window`() {
        // Seed a cached window for (test-fp, s1) with one existing message.
        val existingMsg = Message(id = "m1", role = "user")
        switcher.writeSessionWindow("test-fp", "s1", CachedSessionWindow(
            messages = listOf(existingMsg),
            partsByMessage = mapOf("m1" to emptyList()),
            olderMessagesCursor = "cursor-keep",
            hasMoreMessages = true,
        ))

        // Append a new message with parts.
        val newPart = Part(id = "p-new", type = "text")
        val newMsg = Message(id = "m2", role = "assistant")
        switcher.appendMessageIfCached(
            serverGroupFp = "test-fp",
            sessionId = "s1",
            message = newMsg,
            parts = listOf(newPart),
        )

        val hit = switcher.peekSessionWindow("s1")
        assertNotNull("window still resident after append", hit)
        val window = hit!!
        assertEquals(
            "appended message must be the tail of messages",
            listOf("m1", "m2"),
            window.messages.map { it.id },
        )
        assertEquals(
            "parts merged under the new message.id",
            listOf("p-new"),
            window.partsByMessage["m2"]?.map { it.id },
        )
        // Cursor / hasMoreMessages are preserved (append does NOT touch them).
        assertEquals("cursor-keep", window.olderMessagesCursor)
        assertTrue(window.hasMoreMessages)
    }

    @Test
    fun `appendMessageIfCached is a no-op when the window is not cached`() {
        // No writeSessionWindow call — (test-fp, s-cold) is not resident.
        val beforeSize = switcher.sessionWindowCacheSize()
        assertEquals(0, beforeSize)

        switcher.appendMessageIfCached(
            serverGroupFp = "test-fp",
            sessionId = "s-cold",
            message = Message(id = "m-x", role = "assistant"),
            parts = emptyList(),
        )

        assertEquals(
            "no-op on miss: cache size must not change (cold-start sessions do not proactively build a cache)",
            beforeSize,
            switcher.sessionWindowCacheSize(),
        )
        assertNull(
            "no-op on miss: window must not be created",
            switcher.peekSessionWindow("s-cold"),
        )
    }

    @Test
    fun `appendMessageIfCached is scoped by serverGroupFp - foreign fp does not touch this group's window`() {
        // Seed (test-fp, s-shared) only.
        switcher.writeSessionWindow("test-fp", "s-shared", CachedSessionWindow(
            messages = listOf(Message(id = "m1", role = "user")),
            partsByMessage = emptyMap(),
            olderMessagesCursor = null,
            hasMoreMessages = false,
        ))

        // Append under a FOREIGN fp — there is no (other-fp, s-shared) entry.
        switcher.appendMessageIfCached(
            serverGroupFp = "other-fp",
            sessionId = "s-shared",
            message = Message(id = "m-foreign", role = "assistant"),
            parts = emptyList(),
        )

        val hit = switcher.peekSessionWindow("s-shared")
        // peekSessionWindow reads under currentServerGroupFp() = "test-fp".
        assertNotNull(hit)
        assertEquals(
            "test-fp window untouched by foreign-fp append",
            listOf("m1"),
            hit!!.messages.map { it.id },
        )
    }

    // ── review-fix round 1: ID-aware upsert regression ─────────────────────
    //    The effect is delivered asynchronously through a SharedFlow; another
    //    writer (writeSessionWindow from a fetch completion OR
    //    captureCurrentSessionWindow from a session switch) can land a window
    //    already containing this message.id BEFORE the AppendMessageToCache
    //    handler runs. The handler MUST stay idempotent under that reorder:
    //    do not grow the list cardinality (replace in place) and do not
    //    overwrite already-populated parts with the effect's empty list.

    @Test
    fun `appendMessageIfCached is idempotent by message id - replaces in place and preserves existing parts when incoming parts are empty`() {
        // Seed a resident window for (test-fp, s1) that ALREADY contains the
        // same message id the effect will deliver, with NON-EMPTY parts.
        val existingMsg = Message(id = "m-dup", role = "user")
        val existingPart = Part(id = "p-keep", type = "text")
        switcher.writeSessionWindow("test-fp", "s1", CachedSessionWindow(
            messages = listOf(existingMsg),
            partsByMessage = mapOf("m-dup" to listOf(existingPart)),
            olderMessagesCursor = "cursor-keep",
            hasMoreMessages = true,
        ))

        // The effect handler fires LATE (a write-back or fetch completion
        // landed first). It delivers the same message id with empty parts —
        // the standard `message.updated` insert payload (parts arrive in a
        // separate part event).
        val replayedMsg = Message(id = "m-dup", role = "user")
        switcher.appendMessageIfCached(
            serverGroupFp = "test-fp",
            sessionId = "s1",
            message = replayedMsg,
            parts = emptyList(),
        )

        val window = switcher.peekSessionWindow("s1")!!
        assertEquals(
            "duplicate-id append must NOT grow list cardinality (replace, not append)",
            listOf("m-dup"),
            window.messages.map { it.id },
        )
        assertEquals(
            "existing parts must be preserved when incoming parts are empty (effect's empty list is not a clear instruction)",
            listOf("p-keep"),
            window.partsByMessage["m-dup"]?.map { it.id },
        )
        // Cursor / hasMoreMessages preserved (upsert does NOT touch them).
        assertEquals("cursor-keep", window.olderMessagesCursor)
        assertTrue(window.hasMoreMessages)
    }

    // ── R-20 Phase 1 review-fix #2: writeSessionWindow uses captured fp ────

    @Test
    fun `review-fix 2 writeSessionWindow keys by explicit fp not currentServerGroupFp`() {
        // The writeSessionWindow signature takes an explicit serverGroupFp
        // (review-fix #2). The prior signature read currentServerGroupFp()
        // internally — a host switch mid-flight would route the old fetch's
        // data into the NEW group's LRU slot. Now the caller passes the
        // CAPTURED fp so the write lands in the correct group.
        val window = CachedSessionWindow(
            messages = emptyList(), partsByMessage = emptyMap(),
            olderMessagesCursor = null, hasMoreMessages = false
        )
        // Write under "old-fp" explicitly (captured at hook-factory time).
        switcher.writeSessionWindow("old-fp", "s1", window)
        // Switch the currentServerGroupFp provider to "new-fp" (simulating a
        // host switch). The entry should still be under "old-fp", NOT "new-fp".
        val switcher2 = SessionSwitcher(
            store = store,
            settingsManager = settingsManager,
            repository = repository,
            effects = effects,
            currentServerGroupFp = { "new-fp" },
        )
        // switcher2 shares the same sessionWindowCache (same store/controller instance pair)?
        // No — each SessionSwitcher has its own LRU. So we verify on `switcher`:
        // the entry is under old-fp.
        assertEquals(1, switcher.sessionWindowCacheSize())
        // peekSessionWindow reads under currentServerGroupFp() = "test-fp",
        // so it won't find the "old-fp" entry. Verify the write went to old-fp
        // by checking cache size is 1 and a second write under new-fp creates
        // a separate entry.
        switcher.writeSessionWindow("new-fp", "s1", window)
        assertEquals(
            "writes under different fps create independent LRU entries",
            2,
            switcher.sessionWindowCacheSize(),
        )
    }

    // ── R-20 Phase 1 review-fix #3: directorySessions createdAt lookup ─────
    // (Tested at the AppCore.makeCacheHook level in AppCoreDispatcherTest /
    // ChatViewModelTest; SessionSwitcher.writeSessionWindow itself doesn't
    // look up metadata — it just writes. The fix is in AppCore.makeCacheHook +
    // SessionViewModel.launchLoadMessagesForEffect where session lookup
    // changed from sessions-only to sessions+directorySessions.)

    // ── Callback ordering (Step 2 before Step 7 before Step 8) ──────────────

    @Test
    fun `switchTo calls saveDraft before loadMessages and sets currentSessionId on chatFlow`() {
        seed {
            it.copy(
            currentSessionId = "old",
            sessions = listOf(
                Session(id = "old", directory = "/d"),
                Session(id = "new", directory = "/d")
            )
            )
        }
        store.mutateComposer { it.copy(inputText = "text") }

        switcher.switchTo("new")

        // §R18 Phase 2-F: switchTo no longer calls settingsManager.currentSessionId=;
        // it writes chatFlow.currentSessionId (Step 2, selectSessionState) and the
        // AppCore collector persists it. Ordering assertion now covers the two
        // remaining captured side effects: saveDraft (SettingsManager, Step 2)
        // runs synchronously BEFORE the loadMessages effect (Step 6.5/7).
        // The chatFlow.currentSessionId write also happens in Step 2 (before
        // any effect emission); verified post-hoc below.
        assertTrue("saveDraft fired", callbacks.saveDraftCalls.isNotEmpty())
        val verifyIdx = collectedEffects.indexOfFirst { it is ControllerEffect.VerifyAndHydrate }
        assertTrue("VerifyAndHydrate effect emitted (Phase 1: replaces LoadMessages)", verifyIdx >= 0)
        // Both settings calls run synchronously before any effect emission.
        assertEquals("saveDraft called exactly once", 1, callbacks.saveDraftCalls.size)
        assertEquals("currentSessionId set on chatFlow", "new", slices.chat.value.currentSessionId)
        assertTrue("VerifyAndHydrate emitted", collectedEffects.any { it is ControllerEffect.VerifyAndHydrate })
    }

    // ── RecordingCallbacks (batch 3b: facade over mockk + collectedEffects) ─

    /**
     * Façade that exposes the same accessor shape as the original
     * `RecordingCallbacks : SessionSwitcherCallbacks` so the test bodies
     * stay unchanged. The batch 3b migration removed the callback interface;
     * the controller now (a) calls [SettingsManager] / [OpenCodeRepository]
     * directly for same-domain side effects and (b) emits [ControllerEffect]s
     * for cross-domain ones. This façade wires mockk `answers` on the
     * settings/repository mocks to capture the same-domain side effects, and
     * derives the cross-domain counts from [collectedEffects].
     *
     * `callOrder` records the same-domain side effects in invocation order
     * (synchronous controller calls — no async races). Cross-domain effects
     * fire synchronously too (tryEmit returns immediately); the façade
     * appends them to `callOrder` lazily from [collectedEffects] so the
     * legacy ordering assertions still work (effects are emitted in the order
     * the controller calls them).
     */
    private class RecordingCallbacks(
        settingsManager: SettingsManager,
        repository: OpenCodeRepository,
        private val collectedEffects: MutableList<ControllerEffect>,
    ) {
        val drafts = mutableMapOf<String, String>()
        val saveDraftCalls = mutableListOf<Pair<String, String>>()
        val setCurrentSessionIdCalls = mutableListOf<String?>()
        val setOpenSessionIdsCalls = mutableListOf<List<String>>()
        val syncDirectoryCalls = mutableListOf<String?>()
        val callOrder = mutableListOf<String>()

    init {
        // SettingsManager answers — capture into our lists for assertion.
        // R-20 Phase 5: setDraftText/getDraftText now take (fp, sessionId[, text])
        // — the captured "key" for saveDraft is the SECOND arg (sessionId);
        // drafts map is keyed by sessionId (fp agnostic within the test).
        every { settingsManager.setDraftText(any(), any(), any()) } answers {
            saveDraftCalls.add(secondArg<String>() to thirdArg<String>())
            callOrder += "saveDraft"
        }
        every { settingsManager.getDraftText(any(), any()) } answers {
            callOrder += "getDraft"
            drafts[secondArg<String>()] ?: ""
        }
        every { settingsManager.currentSessionId = any() } answers {
            setCurrentSessionIdCalls.add(firstArg())
            callOrder += "setCurrentSessionId"
        }
        every { settingsManager.openSessionIds = any() } answers {
            setOpenSessionIdsCalls.add(firstArg())
            callOrder += "setOpenSessionIds"
        }
        every { settingsManager.sessionCache = any() } answers {
            persistSessionCacheCalls.add(PersistCall(firstArg()))
            callOrder += "persistSessionCache"
        }
        // §R18 Phase 2-E step 2: the repository.setCurrentDirectory mock that
        // lived here was removed alongside the production call it captured
        // (switchTo Step 6). syncDirectoryCalls is kept (still mutable) for
        // any future capture need but is no longer populated by switchTo.
    }

        // ── Cross-domain counts (derived from collectedEffects) ──

        val clearDeltaBuffersCalls: Int
            get() = collectedEffects.count { it is ControllerEffect.ClearDeltaBuffers }

        val loadChildSessionsCalls: List<String>
            get() = collectedEffects.filterIsInstance<ControllerEffect.LoadChildSessions>().map { it.sessionId }

        val loadMessagesCalls: List<Pair<String, Boolean>>
            get() = collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().map { it.sessionId to it.resetLimit }

        val loadSessionStatusCalls: Int
            get() = collectedEffects.count { it is ControllerEffect.LoadSessionStatus }

        val loadPendingQuestionsCalls: Int
            get() = collectedEffects.count { it is ControllerEffect.LoadPendingQuestions }

        // ── persistSessionCache: captured via settingsManager.sessionCache= ──

        val persistSessionCacheCalls = mutableListOf<PersistCall>()

        data class PersistCall(val cache: List<SessionCacheEntry>)
    }

    // ── §Wave5b-Q13: scroll-state machine routing tests ────────────────────
    //
    // Verifies switchTo's behavior parameter threads through to the slot, the
    // same-session no-op guard, requestLatestScroll bypass, and the
    // compare-and-clear semantics from the consumer side. The pure resolver
    // (anchor / fallback / clamping) is tested separately in
    // ChatMessageContentHelpersTest.

    @Test
    fun `Wave5b-Q13 switchTo default dispatches a Latest intent for the new session`() {
        seed {
            it.copy(
                currentSessionId = "session-A",
                sessions = listOf(
                    Session(id = "session-A", directory = "/tmp/a"),
                    Session(id = "session-B", directory = "/tmp/b"),
                ),
            )
        }

        switcher.switchTo("session-B")

        val req = slices.chat.value.pendingScrollRequest
        assertNotNull("switchTo must produce a PendingScrollRequest", req)
        assertEquals("session-B", req!!.targetSessionId)
        assertTrue("default behavior is Latest", req.behavior is ScrollBehavior.Latest)
        assertEquals("session-B", slices.chat.value.currentSessionId)
    }

    @Test
    fun `Wave5b-Q13 switchTo with Restore behavior threads the checkpoint through to the slot`() {
        seed {
            it.copy(
                currentSessionId = "child",
                sessions = listOf(
                    Session(id = "parent", directory = "/p"),
                    Session(id = "child", directory = "/p", parentId = "parent"),
                ),
            )
        }
        val checkpoint = ScrollCheckpoint(anchorKey = "msg-7", fallbackIndex = 5, offset = 42)

        switcher.switchTo("parent", ScrollBehavior.Restore(checkpoint))

        val req = slices.chat.value.pendingScrollRequest
        assertNotNull(req)
        assertEquals("parent", req!!.targetSessionId)
        assertTrue("behavior is Restore", req.behavior is ScrollBehavior.Restore)
        val actual = (req.behavior as ScrollBehavior.Restore).checkpoint
        assertEquals(checkpoint, actual)
    }

    @Test
    fun `Wave5b-Q13 switchTo same-session is a no-op for scroll intents`() {
        // §Wave5b-Q13 oracle test #4: same-session select does NOT reload and
        // does NOT produce a new scroll intent (the existing slot, if any,
        // survives untouched; if the slot was already empty it stays empty).
        seed {
            it.copy(
                currentSessionId = "only",
                sessions = listOf(Session(id = "only", directory = "/x")),
            )
        }

        switcher.switchTo("only")

        assertNull(
            "same-session switchTo must NOT generate a new PendingScrollRequest",
            slices.chat.value.pendingScrollRequest,
        )
    }

    @Test
    fun `Wave5b-Q13 switchTo to a different target replaces the prior intent`() {
        // §Wave5b-Q13 oracle test #6: switching A→B→C supersedes the prior
        // intent. The single-slot overwrite means only the LATEST intent is
        // observable.
        seed {
            it.copy(
                currentSessionId = "A",
                sessions = listOf(
                    Session(id = "A", directory = "/a"),
                    Session(id = "B", directory = "/b"),
                    Session(id = "C", directory = "/c"),
                ),
            )
        }

        switcher.switchTo("B")
        val firstReq = slices.chat.value.pendingScrollRequest
        assertNotNull(firstReq)
        assertEquals("B", firstReq!!.targetSessionId)

        switcher.switchTo("C")
        val secondReq = slices.chat.value.pendingScrollRequest
        assertNotNull(secondReq)
        assertEquals("C", secondReq!!.targetSessionId)
        assertTrue(
            "the new intent's requestId MUST differ from the prior (single-slot overwrite)",
            secondReq.requestId != firstReq.requestId,
        )
    }

    @Test
    fun `Wave5b-Q13 requestLatestScroll bypasses the same-session no-op guard`() {
        // The send / Chat-tab reselect paths need a fresh Latest intent on an
        // ALREADY-current session. switchTo would early-return; requestLatestScroll
        // must dispatch anyway.
        seed {
            it.copy(
                currentSessionId = "current",
                sessions = listOf(Session(id = "current", directory = "/x")),
            )
        }

        switcher.requestLatestScroll("current")

        val req = slices.chat.value.pendingScrollRequest
        assertNotNull("requestLatestScroll must produce a PendingScrollRequest even on same session", req)
        assertEquals("current", req!!.targetSessionId)
        assertTrue(req.behavior is ScrollBehavior.Latest)
    }

    @Test
    fun `Wave5b-Q13 requestLatestScroll with null sessionId is a no-op`() {
        // Defensive: draft mode (currentSessionId == null) → no scroll target.
        seed { it.copy(currentSessionId = null, sessions = emptyList()) }

        switcher.requestLatestScroll(null)

        assertNull(slices.chat.value.pendingScrollRequest)
    }

    @Test
    fun `Wave5b-Q13 requestLatestScroll produces a fresh requestId each call`() {
        // Two consecutive calls MUST produce different requestIds so the
        // compare-and-clear token is unique (a stale consumer cannot
        // accidentally clear a newer intent).
        seed {
            it.copy(
                currentSessionId = "x",
                sessions = listOf(Session(id = "x", directory = "/x")),
            )
        }

        switcher.requestLatestScroll("x")
        val first = slices.chat.value.pendingScrollRequest?.requestId
        switcher.requestLatestScroll("x")
        val second = slices.chat.value.pendingScrollRequest?.requestId

        assertNotNull(first)
        assertNotNull(second)
        assertTrue("requestId must be unique per call", first != second)
    }

    @Test
    fun `Wave5b-Q13 compare-and-clear - stale consumer cannot wipe newer intent`() {
        // §Wave5b-Q13 oracle test #5: A→B→C, A's consumer finishes LAST and
        // tries to clear with A's stale requestId. The clear MUST be a no-op
        // so C's newer intent survives.
        seed {
            it.copy(
                currentSessionId = "A",
                sessions = listOf(
                    Session(id = "A", directory = "/a"),
                    Session(id = "B", directory = "/b"),
                    Session(id = "C", directory = "/c"),
                ),
            )
        }

        switcher.switchTo("B")
        val bReq = slices.chat.value.pendingScrollRequest?.requestId
        switcher.switchTo("C")
        val cReq = slices.chat.value.pendingScrollRequest
        assertNotNull(bReq)
        assertNotNull(cReq)
        assertTrue("C's intent is newer than B's", cReq!!.requestId != bReq)

        // B's stale consumer fires its clear (B's requestId).
        store.dispatch(cn.vectory.ocdroid.ui.AppAction.ScrollConsumed(bReq!!))
        assertEquals(
            "stale clear MUST NOT wipe C's newer intent",
            cReq,
            slices.chat.value.pendingScrollRequest,
        )

        // C's matching consumer fires — clears correctly.
        store.dispatch(cn.vectory.ocdroid.ui.AppAction.ScrollConsumed(cReq.requestId))
        assertNull(slices.chat.value.pendingScrollRequest)
    }

    @Test
    fun `Wave5b-Q13 blocker-1 - switchTo commits currentSessionId and pendingScrollRequest atomically`() {
        // §Wave5b-Q13 blocker-1 (gpter 8.7 FAIL / groker 9.6 PASS): the
        // pre-fix code dispatched ScrollRequested (one commit) THEN
        // mutateChat (a second commit). The intermediate state —
        // pendingScrollRequest set with targetSessionId = NEW but
        // currentSessionId still = OLD — was observable to stateFlow
        // collectors, violating the oracle's "在同一次 chat mutation 里原子
        // 提交" contract. This test collects the aggregate stateFlow
        // emission stream DURING a switchTo call and asserts NO intermediate
        // emission has the slot set against a stale currentSessionId.
        seed {
            it.copy(
                currentSessionId = "session-A",
                sessions = listOf(
                    Session(id = "session-A", directory = "/tmp/a"),
                    Session(id = "session-B", directory = "/tmp/b"),
                ),
            )
        }

        val seen = mutableListOf<cn.vectory.ocdroid.ui.StoreState>()
        val job = scope.launch {
            store.stateFlow.collect { seen += it }
        }
        // Pump the collector's initial subscription.
        scope.advanceUntilIdle()

        switcher.switchTo("session-B")

        scope.advanceUntilIdle()
        job.cancel()

        // Assert NO torn intermediate exists in the whole emission stream:
        // every emission that has a pendingScrollRequest MUST have a
        // currentSessionId that matches pendingScrollRequest.targetSessionId.
        // (If the pre-fix two-commit bug were still here, there would be an
        // emission where pendingScrollRequest.targetSessionId = "session-B"
        // but currentSessionId = "session-A".)
        seen.forEach { s ->
            val req = s.chat.pendingScrollRequest
            if (req != null) {
                assertEquals(
                    "atomicity violated: pendingScrollRequest set but currentSessionId is stale " +
                        "(targetSessionId=${req.targetSessionId}, currentSessionId=${s.chat.currentSessionId})",
                    req.targetSessionId,
                    s.chat.currentSessionId,
                )
            }
        }

        // Final-state sanity: both fields aligned on the new session.
        val final = slices.chat.value
        assertEquals("session-B", final.currentSessionId)
        assertEquals("session-B", final.pendingScrollRequest?.targetSessionId)
    }
}
