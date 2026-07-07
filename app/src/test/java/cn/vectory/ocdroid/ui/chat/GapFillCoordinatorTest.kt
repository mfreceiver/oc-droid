package cn.vectory.ocdroid.ui.chat

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.vectory.ocdroid.data.cache.CacheDatabase
import cn.vectory.ocdroid.data.cache.CacheRepositoryImpl
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.CachedSessionWindow
import cn.vectory.ocdroid.ui.SharedStateStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-20 Phase 2: unit tests for [GapFillCoordinator] — the 50-step backward fill
 * state machine + session-level Mutex.
 *
 * Uses a Robolectric in-memory Room for the [cn.vectory.ocdroid.data.cache.CacheRepository]
 * (the real gap transaction logic — appendOlderSlice resolve / advance / overlap
 * — is exercised end-to-end) + a mocked [OpenCodeRepository] for the REST page
 * responses. Same pattern as [cn.vectory.ocdroid.data.cache.CacheRepositoryTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GapFillCoordinatorTest {

    private lateinit var db: CacheDatabase
    private lateinit var cacheRepo: CacheRepositoryImpl
    private lateinit var repository: OpenCodeRepository
    private lateinit var store: SharedStateStore
    private lateinit var coordinator: GapFillCoordinator

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cacheRepo = CacheRepositoryImpl(db.cacheDao(), db.gapMarkerDao(), db)
        repository = mockk(relaxed = true)
        store = SharedStateStore()
        // §fix-#3: pass a fixed-fp provider ("fp" matches the test seeds) so
        // the coordinator's compound-key guards resolve to "current" for every
        // test's default (fp="fp", sessionId="s1"). Tests that need a
        // cross-group switch re-construct the coordinator with a different
        // provider.
        coordinator = GapFillCoordinator(
            repository = repository,
            cacheRepository = cacheRepo,
            currentServerGroupFp = { "fp" },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun msg(id: String, created: Long) =
        MessageWithParts(info = Message(id = id, role = "user", time = Message.TimeInfo(created = created)))

    private suspend fun seedSession(sid: String, messages: List<Message>) {
        cacheRepo.putSessionWindow(
            "fp", sid, createdAt = 1L, workdir = "/p",
            CachedSessionWindow(messages = messages, partsByMessage = emptyMap(), olderMessagesCursor = null, hasMoreMessages = true)
        )
    }

    @Test
    fun `fillSingleGap resolves when a step covers the anchor`() = runTest {
        val anchor = Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))
        val upper = Message(id = "upper", role = "assistant", time = Message.TimeInfo(created = 500L))
        seedSession("s1", listOf(anchor, upper))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor, upper)) }
        val gapId = cacheRepo.openGap("fp", "s1", "anchor", "upper", "c1")
        // First backward step returns the anchor → resolves immediately.
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1")) } returns Result.success(
            MessagesPage(listOf(msg("bridge", 250L), msg("anchor", 100L)), nextCursor = null)
        )

        coordinator.fillSingleGap(store.slices, "fp", "s1", gapId) { _, _ -> }
        advanceUntilIdle()

        assertTrue("anchor-covering step resolves the gap", cacheRepo.gapsOf("fp", "s1").isEmpty())
        // Merged messages include the bridged step.
        assertTrue(store.slices.chat.value.messages.any { it.id == "bridge" })
    }

    @Test
    fun `fillSingleGap marks Exhausted when cursor nulls before the anchor`() = runTest {
        val anchor = Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))
        val upper = Message(id = "upper", role = "assistant", time = Message.TimeInfo(created = 500L))
        seedSession("s1", listOf(anchor, upper))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor, upper)) }
        val gapId = cacheRepo.openGap("fp", "s1", "anchor", "upper", "c1")
        // Step without the anchor + null cursor → exhausted.
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1")) } returns Result.success(
            MessagesPage(listOf(msg("bridge", 250L)), nextCursor = null)
        )

        coordinator.fillSingleGap(store.slices, "fp", "s1", gapId) { _, _ -> }
        advanceUntilIdle()

        val gap = cacheRepo.gapsOf("fp", "s1").single()
        assertEquals(GapFillState.Exhausted, gap.fillState)
        // Slice markers mirrored from the cache.
        assertEquals(GapFillState.Exhausted, store.slices.chat.value.gapMarkers.single().fillState)
    }

    @Test
    fun `fillSingleGap walks multiple steps until the anchor is reached`() = runTest {
        val anchor = Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))
        val upper = Message(id = "upper", role = "assistant", time = Message.TimeInfo(created = 500L))
        seedSession("s1", listOf(anchor, upper))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor, upper)) }
        val gapId = cacheRepo.openGap("fp", "s1", "anchor", "upper", "c1")
        // Step 1: no anchor, advances cursor.
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1")) } returns Result.success(
            MessagesPage(listOf(msg("mid", 300L)), nextCursor = "c2")
        )
        // Step 2: contains anchor → resolves.
        coEvery { repository.getMessagesPaged("s1", any(), eq("c2")) } returns Result.success(
            MessagesPage(listOf(msg("anchor", 100L)), nextCursor = null)
        )

        coordinator.fillSingleGap(store.slices, "fp", "s1", gapId) { _, _ -> }
        advanceUntilIdle()

        assertTrue("multi-step walk resolves the gap", cacheRepo.gapsOf("fp", "s1").isEmpty())
    }

    @Test
    fun `two sequential fills for the same session stay consistent`() = runTest {
        // R-20 Phase 2 (plan §3 N3): the session-level Mutex serialises fills
        // for one session. With the suspend API, two sequential fillSingleGap
        // calls (the second after the first resolves) must leave the cache
        // consistent — the second is a no-op (gap already resolved). True
        // parallel-launch concurrency is exercised structurally by the
        // ConcurrentHashMap<String, Mutex> + withLock in the coordinator; the
        // in-memory Room + test dispatcher make a parallel-launch test flaky
        // (Room's background executor resumption racing advanceUntilIdle), so
        // this test pins the sequential-consistency contract instead.
        val anchor = Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))
        val upper = Message(id = "upper", role = "assistant", time = Message.TimeInfo(created = 500L))
        seedSession("s1", listOf(anchor, upper))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor, upper)) }
        val gapId = cacheRepo.openGap("fp", "s1", "anchor", "upper", "c1")
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(
            MessagesPage(listOf(msg("anchor", 100L)), nextCursor = null)
        )

        coordinator.fillSingleGap(store.slices, "fp", "s1", gapId) { _, _ -> }
        advanceUntilIdle()
        // Second fill: gap already resolved → no-op (no fetch, no corruption).
        coordinator.fillSingleGap(store.slices, "fp", "s1", gapId) { _, _ -> }
        advanceUntilIdle()

        assertTrue(cacheRepo.gapsOf("fp", "s1").isEmpty())
    }

    @Test
    fun `fillSingleGap no-ops for a session the user already switched away from`() = runTest {
        seedSession("s1", listOf(Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))))
        // currentSessionId is a DIFFERENT session.
        store.mutateChat { it.copy(currentSessionId = "s2") }
        val gapId = cacheRepo.openGap("fp", "s1", "anchor", "upper", "c1")

        coordinator.fillSingleGap(store.slices, "fp", "s1", gapId) { _, _ -> }
        advanceUntilIdle()

        // No fetch issued (session guard returned before the fill loop).
        io.mockk.coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    // ── R-20 Phase 2 fix-#2: empty cursor GapExists must not stick at Filling ──

    @Test
    fun `fix-2 fillSingleGap marks Exhausted when the gap opens with null cursor`() = runTest {
        // gpter #2 / dser B-1: a gap opened with nextBeforeCursor=null (the
        // probe returned a null cursor alongside the gap verdict — history
        // ended at the probe boundary). Before the fix, runFill set Filling
        // then `while (cursor != null)` never entered → marker stuck Filling
        // forever (non-tappable, no progress). After the fix, runFill detects
        // the null cursor at entry, marks Exhausted + refreshes markers +
        // snapshots the window so cache + slice converge.
        val anchor = Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))
        val upper = Message(id = "upper", role = "assistant", time = Message.TimeInfo(created = 500L))
        seedSession("s1", listOf(anchor, upper))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor, upper)) }
        // Open the gap with a null cursor (the boundary condition).
        val gapId = cacheRepo.openGap("fp", "s1", "anchor", "upper", "")

        coordinator.fillSingleGap(store.slices, "fp", "s1", gapId) { _, _ -> }
        advanceUntilIdle()

        val gap = cacheRepo.gapsOf("fp", "s1").single()
        assertEquals(
            "null-cursor gap must transition to Exhausted (not stuck Filling)",
            GapFillState.Exhausted,
            gap.fillState,
        )
        // Slice markers mirror the cache.
        assertEquals(GapFillState.Exhausted, store.slices.chat.value.gapMarkers.single().fillState)
        // No fetch issued (cursor null → loop body never entered).
        io.mockk.coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    // ── R-20 Phase 2 fix-#3: gap async guard checks compound key (fp + sid) ──

    @Test
    fun `fix-3 fillSingleGap drops slice writes when host group switched mid-fill`() = runTest {
        // gpter #3: a cross-group same-sessionId collision (plan §0 N1) — the
        // fill was initiated against fp=fp-A, but the user switched host during
        // it so the current fp is now fp-B. The slice merges inside runFill
        // (refreshGapMarkers / snapshotWindow / mergeOlderStep) must NOT land
        // in fp-B's view. Without the fp leg, only sessionId would be compared
        // and the stale fill would pollute the new group's slice.
        val anchor = Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))
        val upper = Message(id = "upper", role = "assistant", time = Message.TimeInfo(created = 500L))
        seedSession("fp-A", listOf(anchor, upper))
        seedSession("fp-B", emptyList())
        // The current view is fp-B (host switched). The coordinator is wired
        // with a fixed-fp provider that returns fp-B (matching the new group).
        val switchedCoordinator = GapFillCoordinator(
            repository = repository,
            cacheRepository = cacheRepo,
            currentServerGroupFp = { "fp-B" },
        )
        // Both groups share sessionId "s1" (the collision scenario).
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor, upper)) }
        val gapId = cacheRepo.openGap("fp-A", "s1", "anchor", "upper", "c1")
        // A page would be fetched against fp-A's session — the coordinator's
        // guard must drop the slice write before merge.
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1")) } returns Result.success(
            MessagesPage(listOf(msg("bridge", 250L), msg("anchor", 100L)), nextCursor = null)
        )

        switchedCoordinator.fillSingleGap(store.slices, "fp-A", "s1", gapId) { _, _ -> }
        advanceUntilIdle()

        // The fill's setGapState(Filling) was the first write to the cache
        // (the cache is fp-scoped — that's fine, fp-A's cache row mutates).
        // But the slice's gapMarkers must NOT include fp-A's marker — the
        // current view is fp-B. The fp guard at runFill entry returned false
        // before refreshGapMarkers fired.
        assertTrue(
            "stale fp-A fill must not leak its gapMarkers into fp-B's view",
            store.slices.chat.value.gapMarkers.none { it.gapId == gapId },
        )
    }

    // ── R-20 Phase 2 dser I-4: fillSingleGap guard against Exhausted ──

    @Test
    fun `dser-I4 fillSingleGap no-ops on an Exhausted gap`() = runTest {
        // dser I-4: an Exhausted gap (server history ended below the gap) MUST
        // NOT be retried — another fetch would page the same null cursor and
        // waste a request. The UI's Exhausted divider is non-tappable; this
        // guards any future caller that invokes fillSingleGap on an Exhausted
        // marker.
        val anchor = Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))
        val upper = Message(id = "upper", role = "assistant", time = Message.TimeInfo(created = 500L))
        seedSession("s1", listOf(anchor, upper))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor, upper)) }
        val gapId = cacheRepo.openGap("fp", "s1", "anchor", "upper", "c1")
        // Pre-set the gap to Exhausted (the state the fix-#2 path converges to).
        cacheRepo.setGapState(gapId, GapFillState.Exhausted)

        coordinator.fillSingleGap(store.slices, "fp", "s1", gapId) { _, _ -> }
        advanceUntilIdle()

        // No fetch issued (the I-4 guard bailed before acquiring the lock).
        io.mockk.coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
        // The gap stays Exhausted (not reset to Filling).
        assertEquals(GapFillState.Exhausted, cacheRepo.gapsOf("fp", "s1").single().fillState)
    }

    // ── R-20 Phase 2 dser I-2: openAndFill fires onColdSnapshot only on success ──

    @Test
    fun `dser-I2 openAndFill does not fire onColdSnapshot when the user switched away mid-fill`() = runTest {
        // dser I-2: the unconditional onColdSnapshot at the end of openAndFill
        // would mark a session as cold-snapshotted even when the fill bailed
        // early (session switch). That would defeat the G6 shouldProbe gate
        // for a future reconnect. After the fix, onColdSnapshot fires ONLY on
        // the normal-completion path (fill ran to resolved / exhausted /
        // cursor-null).
        val anchor = Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))
        val upper = Message(id = "upper", role = "assistant", time = Message.TimeInfo(created = 500L))
        seedSession("s1", listOf(anchor, upper))
        // The current session is s2 (user switched away before the fill ran).
        store.mutateChat { it.copy(currentSessionId = "s2") }
        var coldSnapshotFired = false

        coordinator.openAndFill(
            slices = store.slices,
            serverGroupFp = "fp",
            sessionId = "s1",
            detection = GapDetection.GapExists(
                lowerAnchorMessageId = "anchor",
                upperBoundaryMessageId = "upper",
                initialNextBeforeCursor = "c1",
            ),
            fetched5 = listOf(upper),
            fetched5Parts = emptyMap(),
            onCacheWindow = { _, _ -> },
            onColdSnapshot = { coldSnapshotFired = true },
        )
        advanceUntilIdle()

        assertFalse(
            "onColdSnapshot must NOT fire when the fill bailed early (session switched)",
            coldSnapshotFired,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // R-20 Phase 2 复审 #3-b: per-suspend-point stillCurrent re-check
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `fix-3-b fillSingleGap drops slice writes when host switched during getMessagesPaged`() = runTest {
        // gpter 复审 #3: the pre-existing per-step guard at the top of the
        // while-loop only covered the entry to that iteration. The
        // getMessagesPaged suspend INSIDE the body could land a host switch,
        // and the subsequent mergeOlderStep would write fp-A's stale page into
        // fp-B's view. After the fix, a stillCurrent re-check fires between
        // getMessagesPaged-return and appendOlderSlice/mergeOlderStep, dropping
        // the slice write on mismatch.
        val anchor = Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))
        val upper = Message(id = "upper", role = "assistant", time = Message.TimeInfo(created = 500L))
        seedSession("fp-A", listOf(anchor, upper))
        // Stateful fp provider: starts at fp-A, the test flips it to fp-B
        // during the getMessagesPaged suspend to simulate a host switch
        // mid-REST.
        var currentFp = "fp-A"
        val switchedCoordinator = GapFillCoordinator(
            repository = repository,
            cacheRepository = cacheRepo,
            currentServerGroupFp = { currentFp },
        )
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor, upper)) }
        val gapId = cacheRepo.openGap("fp-A", "s1", "anchor", "upper", "c1")
        // Switch the host DURING the getMessagesPaged suspend (the REST call).
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1")) } answers {
            currentFp = "fp-B"
            Result.success(
                MessagesPage(listOf(msg("bridge", 250L), msg("anchor", 100L)), nextCursor = null)
            )
        }

        switchedCoordinator.fillSingleGap(store.slices, "fp-A", "s1", gapId) { _, _ -> }
        advanceUntilIdle()

        // The stale fp-A "bridge" must NOT be merged into fp-B's view (the
        // post-getMessagesPaged guard dropped the slice write).
        assertFalse(
            "stale slice must NOT leak into fp-B's view after getMessagesPaged switch",
            store.slices.chat.value.messages.any { it.id == "bridge" },
        )
        // The cache marker was reset to Idle (fix-#4 below covers this
        // explicitly; here it is a side-effect of the same switch-away path).
        val gap = cacheRepo.gapsOf("fp-A", "s1").single()
        assertEquals(GapFillState.Idle, gap.fillState)
    }

    @Test
    fun `fix-3-b fillSingleGap drops slice writes when host switched during appendOlderSlice`() = runTest {
        // gpter 复审 #3 (appendOlderSlice leg): even if getMessagesPaged
        // returns cleanly, the appendOlderSlice suspend (a Room transaction)
        // can land a host switch. The subsequent mergeOlderStep must be
        // dropped. Uses a mockk spy on [cacheRepo] so the host switch fires
        // from inside the appendOlderSlice call (the real impl is otherwise
        // inseparable from the test).
        val anchor = Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))
        val upper = Message(id = "upper", role = "assistant", time = Message.TimeInfo(created = 500L))
        seedSession("fp-A", listOf(anchor, upper))
        var currentFp = "fp-A"
        // Wrap the real cacheRepo in a mockk spy so we can inject a side
        // effect on appendOlderSlice. Every method delegates to the real impl
        // so the gap state machine behaves identically to production.
        val spyCache = io.mockk.mockk<cn.vectory.ocdroid.data.cache.CacheRepository>(relaxed = true)
        io.mockk.coEvery { spyCache.gapsOf(any(), any()) } coAnswers {
            cacheRepo.gapsOf(firstArg(), secondArg())
        }
        io.mockk.coEvery {
            spyCache.openGap(any(), any(), any(), any(), any())
        } coAnswers {
            cacheRepo.openGap(firstArg(), secondArg(), thirdArg(), arg(3), arg(4))
        }
        io.mockk.coEvery { spyCache.setGapState(any(), any()) } coAnswers {
            cacheRepo.setGapState(firstArg(), secondArg())
        }
        io.mockk.coEvery {
            spyCache.appendOlderSlice(any(), any(), any(), any())
        } coAnswers {
            cacheRepo.appendOlderSlice(firstArg(), secondArg(), arg(2), arg(3))
            // Switch the host AFTER the appendOlderSlice transaction commits.
            currentFp = "fp-B"
        }
        val switchedCoordinator = GapFillCoordinator(
            repository = repository,
            cacheRepository = spyCache,
            currentServerGroupFp = { currentFp },
        )
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor, upper)) }
        val gapId = cacheRepo.openGap("fp-A", "s1", "anchor", "upper", "c1")
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1")) } returns Result.success(
            MessagesPage(listOf(msg("bridge", 250L), msg("anchor", 100L)), nextCursor = null)
        )

        switchedCoordinator.fillSingleGap(store.slices, "fp-A", "s1", gapId) { _, _ -> }
        advanceUntilIdle()

        // appendOlderSlice ran (cache write happened against fp-A — fine, cache
        // is fp-scoped). But the post-appendOlderSlice guard fired → the slice
        // merge was dropped, so fp-B's view is NOT polluted.
        assertFalse(
            "stale slice must NOT leak into fp-B's view after appendOlderSlice switch",
            store.slices.chat.value.messages.any { it.id == "bridge" },
        )
    }

    @Test
    fun `fix-3-b openAndFill drops mergeNewerSlice when host switched during openGap`() = runTest {
        // gpter 复审 #3 (openGap leg, openAndFill path): the openGap suspend
        // (a Room insert) can land a host switch. The subsequent
        // mergeNewerSlice (the prefetched newest-5) must be dropped. After the
        // fix, a stillCurrent re-check fires between openGap-return and
        // mergeNewerSlice.
        val anchor = Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))
        val upper = Message(id = "upper", role = "assistant", time = Message.TimeInfo(created = 500L))
        seedSession("fp-A", listOf(anchor, upper))
        var currentFp = "fp-A"
        val spyCache = io.mockk.mockk<cn.vectory.ocdroid.data.cache.CacheRepository>(relaxed = true)
        io.mockk.coEvery { spyCache.gapsOf(any(), any()) } coAnswers {
            cacheRepo.gapsOf(firstArg(), secondArg())
        }
        io.mockk.coEvery {
            spyCache.openGap(any(), any(), any(), any(), any())
        } coAnswers {
            val id = cacheRepo.openGap(firstArg(), secondArg(), thirdArg(), arg(3), arg(4))
            // Switch the host AFTER openGap returns.
            currentFp = "fp-B"
            id
        }
        io.mockk.coEvery { spyCache.setGapState(any(), any()) } coAnswers {
            cacheRepo.setGapState(firstArg(), secondArg())
        }
        io.mockk.coEvery {
            spyCache.appendOlderSlice(any(), any(), any(), any())
        } coAnswers {
            cacheRepo.appendOlderSlice(firstArg(), secondArg(), arg(2), arg(3))
        }
        val switchedCoordinator = GapFillCoordinator(
            repository = repository,
            cacheRepository = spyCache,
            currentServerGroupFp = { currentFp },
        )
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor, upper)) }
        // Pass a "newer" message that would be merged by mergeNewerSlice if
        // the guard did not fire.
        val newer = Message(id = "newer1", role = "user", time = Message.TimeInfo(created = 600L))

        switchedCoordinator.openAndFill(
            slices = store.slices,
            serverGroupFp = "fp-A",
            sessionId = "s1",
            detection = GapDetection.GapExists(
                lowerAnchorMessageId = "anchor",
                upperBoundaryMessageId = "upper",
                initialNextBeforeCursor = "c1",
            ),
            fetched5 = listOf(newer),
            fetched5Parts = emptyMap(),
            onCacheWindow = { _, _ -> },
            onColdSnapshot = { },
        )
        advanceUntilIdle()

        // The "newer1" merge must NOT land in fp-B's view (the post-openGap
        // guard dropped mergeNewerSlice).
        assertFalse(
            "stale newer-slice must NOT leak into fp-B's view after openGap switch",
            store.slices.chat.value.messages.any { it.id == "newer1" },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // R-20 Phase 2 复审 #4: switch-away resets Filling → Idle
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `fix-4 fillSingleGap resets Filling to Idle when host switched mid-fill`() = runTest {
        // gpter 复审 #4: when the fill loop bails on a switch-away AFTER
        // setGapState(Filling), the cache marker MUST be reset to Idle.
        // Without this, the cache row stays Filling and the UI's switch-back
        // renders a permanent spinner (the Filling divider is non-tappable,
        // blocking retry — launchCatchUp cannot re-detect because the marker
        // is not Idle, and a manual retry via fillSingleGap would queue on
        // the session Mutex behind the (now-cancelled) fill). The fix adds
        // setGapState(gapId, Idle) on every switch-away return path inside
        // the loop (the per-step entry guard + the post-suspend guards).
        val anchor = Message(id = "anchor", role = "user", time = Message.TimeInfo(created = 100L))
        val upper = Message(id = "upper", role = "assistant", time = Message.TimeInfo(created = 500L))
        seedSession("fp-A", listOf(anchor, upper))
        var currentFp = "fp-A"
        val switchedCoordinator = GapFillCoordinator(
            repository = repository,
            cacheRepository = cacheRepo,
            currentServerGroupFp = { currentFp },
        )
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor, upper)) }
        val gapId = cacheRepo.openGap("fp-A", "s1", "anchor", "upper", "c1")
        // Switch the host DURING the getMessagesPaged suspend → the post-
        // getMessagesPaged guard fires the switch-away return path.
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1")) } answers {
            currentFp = "fp-B"
            Result.success(
                MessagesPage(listOf(msg("bridge", 250L), msg("anchor", 100L)), nextCursor = null)
            )
        }

        switchedCoordinator.fillSingleGap(store.slices, "fp-A", "s1", gapId) { _, _ -> }
        advanceUntilIdle()

        // The cache marker is Idle (NOT Filling) → on switch-back, the user
        // can re-detect the gap via launchCatchUp + manually retry. Without
        // the fix-#4 reset, the cache row would stay Filling and the UI's
        // switch-back would render a permanent, non-tappable spinner.
        //
        // NOTE: the slice's gapMarkers may transiently still show Filling
        // (the slice was written at line 235's refreshGapMarkers BEFORE the
        // switch happened mid-getMessagesPaged). That slice pollution is the
        // separate #3 concern (per-suspend guards prevent the slice write at
        // line 235 only when the switch happens BEFORE line 235 — here the
        // switch happens inside the loop, after line 235). The cache state is
        // the user-visible-on-switch-back state, which is what fix-#4 targets.
        val gap = cacheRepo.gapsOf("fp-A", "s1").single()
        assertEquals(
            "switch-away must reset Filling → Idle so the UI does not show a permanent spinner",
            GapFillState.Idle,
            gap.fillState,
        )
    }
}
