package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.CacheListingState
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5++ coverage: [SettingsViewModel] — user-facing settings writes
 * (theme mode / markdown font sizes / UI font + content scale). Coverage gap
 * before this file: 0/1 class, 0/6 methods, 0/13 branches, 0/112 lines.
 *
 * Each method persists through SettingsManager AND writes the live value to
 * the settings slice. The slice write flows through [SharedStateStore] which
 * is exercised by [createCore]; here we verify both side-effects land.
 *
 * R-20 Phase 4 (plan §3): extended to cover the cache-management action
 * surface — clearSession / clearProject / clearAll / sweepNow /
 * refreshCacheListing. Each test verifies the action reaches
 * the underlying Repository / Coordinator / HostProfileStore (the relaxed
 * mock on `core.cacheRepository` lets us coVerify the suspend calls).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest : MainViewModelTestBase() {

    @Test
    fun `setThemeMode persists and writes the slice`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)

        vm.setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        verify { settingsManager.themeMode = ThemeMode.DARK }
        assertEquals(ThemeMode.DARK, core.settingsFlow.value.themeMode)
    }

    @Test
    fun `setMarkdownFontSizes persists and writes the slice`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)
        val sizes = MarkdownFontSizes(body = 16f, code = 14f, reasoning = 13f, h1 = 22f)

        vm.setMarkdownFontSizes(sizes)
        advanceUntilIdle()

        verify { settingsManager.markdownFontSizes = sizes }
        assertEquals(sizes, core.settingsFlow.value.markdownFontSizes)
    }

    @Test
    fun `setUiFontScale clamps to MIN and persists`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)

        // Below MIN → clamped to MIN.
        vm.setUiFontScale(0.1f)
        advanceUntilIdle()

        verify { settingsManager.uiFontScale = SettingsManager.UI_SCALE_MIN }
        assertEquals(SettingsManager.UI_SCALE_MIN, core.settingsFlow.value.uiFontScale, 0.0001f)
    }

    @Test
    fun `setUiFontScale clamps to MAX and persists`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)

        // Above MAX → clamped to MAX.
        vm.setUiFontScale(99f)
        advanceUntilIdle()

        verify { settingsManager.uiFontScale = SettingsManager.UI_SCALE_MAX }
        assertEquals(SettingsManager.UI_SCALE_MAX, core.settingsFlow.value.uiFontScale, 0.0001f)
    }

    @Test
    fun `setUiFontScale in-range value persists as-is`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)

        vm.setUiFontScale(1.25f)
        advanceUntilIdle()

        verify { settingsManager.uiFontScale = 1.25f }
        assertEquals(1.25f, core.settingsFlow.value.uiFontScale, 0.0001f)
    }

    @Test
    fun `setUiContentScale clamps to MIN and persists`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)

        vm.setUiContentScale(0.0f)
        advanceUntilIdle()

        verify { settingsManager.uiContentScale = SettingsManager.UI_SCALE_MIN }
        assertEquals(SettingsManager.UI_SCALE_MIN, core.settingsFlow.value.uiContentScale, 0.0001f)
    }

    @Test
    fun `setUiContentScale clamps to MAX and persists`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)

        vm.setUiContentScale(10f)
        advanceUntilIdle()

        verify { settingsManager.uiContentScale = SettingsManager.UI_SCALE_MAX }
        assertEquals(SettingsManager.UI_SCALE_MAX, core.settingsFlow.value.uiContentScale, 0.0001f)
    }

    @Test
    fun `setUiContentScale in-range value persists as-is`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)

        vm.setUiContentScale(1.10f)
        advanceUntilIdle()

        verify { settingsManager.uiContentScale = 1.10f }
        assertEquals(1.10f, core.settingsFlow.value.uiContentScale, 0.0001f)
    }

    @Test
    fun `settingsFlow is exposed on the VM`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)
        // The accessor must delegate to core.settingsFlow (same instance).
        assertEquals(core.settingsFlow, vm.settingsFlow)
    }

    // ─────────── R-20 Phase 4: cache-management actions ───────────────────

    @Test
    fun `clearSession forwards to cacheRepository and refreshes listing`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)

        vm.clearSession("fp-a", "ses_xyz")
        advanceUntilIdle()

        coVerify { core.cacheRepository.evictSession("fp-a", "ses_xyz") }
        // refreshCacheListing fan-out — see CacheListingState assertions
        // below for the post-clear state.
        coVerify { core.cacheRepository.allServerGroupFps() }
    }

    @Test
    fun `clearProject forwards to evictWorkdirInGroup (never clearAll)`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)

        vm.clearProject("fp-a", "/home/me/proj")
        advanceUntilIdle()

        coVerify {
            core.cacheRepository.evictWorkdirInGroup("fp-a", "/home/me/proj")
        }
        // Strictly NOT clearAll — clearProject is project-scoped.
        coVerify(exactly = 0) { core.cacheRepository.clearAll() }
    }

    @Test
    fun `clearAll forwards to cacheRepository clearAll`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)

        vm.clearAll()
        advanceUntilIdle()

        coVerify(exactly = 1) { core.cacheRepository.clearAll() }
    }

    @Test
    fun `sweepNow triggers refreshCacheListing afterwards`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)
        // The maintenance coordinator is real (built from core.cacheRepository,
        // a relaxed mock). Forcing applyEvictionPolicy + allServerGroupFps to
        // be stubbed so the coordinator + the refresh path both complete
        // cleanly under the test dispatcher.
        coEvery { core.cacheRepository.allServerGroupFps() } returns emptyList()

        vm.sweepNow("fp-a")
        advanceUntilIdle()

        // The sweep itself calls applyEvictionPolicy + cachedWorkdirsInGroup;
        // the post-sweep refresh calls allServerGroupFps at least once.
        coVerify(atLeast = 1) { core.cacheRepository.allServerGroupFps() }
        // lastSweep was populated (the coordinator's DailySweepReport
        // surfaces here even on the Incomplete / dedup-skipped path).
        assertNotNull(vm.lastSweep.value)
    }

    @Test
    fun `sweepAllGroups sweeps the UNION of profile-derived + cache-derived fps`() = runTest {
        // §grouping-rewrite Round-3 #4: the popup listing is cache-derived
        // (refreshCacheListing → allServerGroupFps), but sweepAllGroups used
        // to sweep profile-derived fps only — so a group with cached sessions
        // but no live profile (e.g. all profiles for that group were deleted)
        // would show in the popup but be skipped by "Sweep all groups". The
        // union closes that gap.
        val core = createCore()
        val vm = SettingsViewModel(core)

        // Arrange: profile-derived fp = "fp-profile-only"; cache-derived fps
        // include "fp-profile-only" (overlap, should not duplicate sweep) AND
        // "fp-cache-only" (the gap case).
        val profileFpProfile = HostProfile(
            id = "p1",
            name = "p1",
            serverUrl = "http://x",
            serverGroupFp = "fp-profile-only",
        )
        every { hostProfileStore.profiles() } returns listOf(profileFpProfile)
        coEvery { core.cacheRepository.allServerGroupFps() } returns listOf("fp-profile-only", "fp-cache-only")
        // Stub the per-fp coordinator deps so the sweep completes cleanly
        // under the test dispatcher (applyEvictionPolicy + cachedWorkdirsInGroup
        // are invoked by the real coordinator built in the test-only ctor).
        io.mockk.coEvery { core.cacheRepository.cachedWorkdirsInGroup(any()) } returns emptyList()

        vm.sweepAllGroups()
        advanceUntilIdle()

        // Both distinct fps were swept (the union, not just the profile set).
        // applyEvictionPolicy is the first thing dailySweepIfNeeded runs per
        // fp; verifying it ran for both proves both fps entered the pipeline.
        io.mockk.coVerify(atLeast = 1) { core.cacheRepository.applyEvictionPolicy("fp-profile-only") }
        io.mockk.coVerify(atLeast = 1) { core.cacheRepository.applyEvictionPolicy("fp-cache-only") }
    }

    @Test
    fun `refreshCacheListing populates Loaded state when groups exist`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)
        coEvery { core.cacheRepository.allServerGroupFps() } returns listOf("fp-a")
        coEvery { core.cacheRepository.listGroupSessions("fp-a") } returns listOf(
            CacheRepository.CachedSessionRow(
                serverGroupFp = "fp-a",
                sessionId = "ses_1",
                workdir = "/p",
                createdAt = 1L,
                newestCachedAt = 2L,
                lastVerifiedAt = 3L,
                hasExhaustedGap = false
            )
        )
        io.mockk.every { hostProfileStore.profilesInGroup("fp-a") } returns emptyList()

        vm.refreshCacheListing()
        advanceUntilIdle()

        val state = vm.cacheListing.value
        assertTrue("expected Loaded, got $state", state is CacheListingState.Loaded)
        val loaded = state as CacheListingState.Loaded
        assertEquals(1, loaded.groups.size)
        assertEquals("fp-a", loaded.groups[0].serverGroupFp)
        assertEquals(1, loaded.groups[0].sessions.size)
    }

    @Test
    fun `refreshCacheListing surfaces Empty when no fps cached`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)
        coEvery { core.cacheRepository.allServerGroupFps() } returns emptyList()

        vm.refreshCacheListing()
        advanceUntilIdle()

        assertEquals(CacheListingState.Empty, vm.cacheListing.value)
    }

    @Test
    fun `refreshCacheListing surfaces Error when repository throws`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)
        coEvery { core.cacheRepository.allServerGroupFps() } throws RuntimeException("disk gone")

        vm.refreshCacheListing()
        advanceUntilIdle()

        val state = vm.cacheListing.value
        assertTrue("expected Error, got $state", state is CacheListingState.Error)
        assertEquals("disk gone", (state as CacheListingState.Error).message)
    }

    @Test
    fun `isOnline reflects connectionFlow isConnected`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)

        // Default ConnectionState has isConnected = false.
        assertFalse(vm.isOnline)

        // Flip the connection slice + assert the VM picks it up.
        core.store.mutateConnection { ConnectionState(isConnected = true) }
        assertTrue(vm.isOnline)
    }

    @Test
    fun `isCacheDegraded is false in the test-only constructor`() = runTest {
        // The test-only ctor hardcodes cacheDegraded=false (the in-memory
        // fallback never fires in tests). The production @Inject ctor would
        // route the @Named("cacheDegraded") Boolean from the Hilt graph.
        val vm = SettingsViewModel(createCore())
        assertFalse(vm.isCacheDegraded)
    }

    // ─────────── §analysis-8b (Lane-D8b): RefreshCacheListing wiring ──────

    @Test
    fun `RefreshCacheListing effect triggers refreshCacheListing re-read of cacheRepository`() = runTest {
        // §analysis-8b: HostProfileController.resetLocalDataAndResync emits
        // ControllerEffect.RefreshCacheListing on the SharedEffectBus after
        // wiping the cache DB. SettingsViewModel's init-block collector picks
        // it up and calls refreshCacheListing() so the manual _cacheListing /
        // _cachedDataBytes StateFlows drop stale rows. This test pins the
        // end-to-end wiring: effect → collect → refreshCacheListing →
        // cacheRepository re-read.
        val core = createCore()
        val vm = SettingsViewModel(core)
        coEvery { core.cacheRepository.allServerGroupFps() } returns emptyList()
        // SharedEffectBus has NO replay — the init block's viewModelScope.launch
        // collector must be subscribed BEFORE we emit, or the effect is lost.
        // advanceUntilIdle lets the init launch begin collecting.
        advanceUntilIdle()
        // Baseline: no refresh yet (the listing is still at its Loading
        // initial value; refreshCacheListing has not run).
        coVerify(exactly = 0) { core.cacheRepository.allServerGroupFps() }

        core.effectBus.tryEmitEffect(ControllerEffect.RefreshCacheListing)
        advanceUntilIdle()

        // The init collector forwarded the effect → refreshCacheListing() ran
        // → cacheRepository was re-read (allServerGroupFps is the first suspend
        // call inside refreshCacheListing's appScope.launch block).
        coVerify(atLeast = 1) { core.cacheRepository.allServerGroupFps() }
        // The re-read transitioned the listing from Loading to Empty (fps == []).
        assertEquals(CacheListingState.Empty, vm.cacheListing.value)
    }

    // ─────────── §grouping-rewrite Round-2 C1: disconnect reactivity ──────

    @Test
    fun `disconnectWorkdir re-derives recentWorkdirs even when hostFlow + sessionListFlow are quiet`() = runTest {
        // C1: disconnectWorkdir mutates SettingsManager + cacheRepository,
        // neither of which pokes hostFlow / sessionListFlow. Without the
        // explicit re-derivation trigger the disconnected workdir would stay
        // in recentWorkdirs (regression of the old hiddenWorkdirs behaviour).
        val core = createCore()
        val vm = SettingsViewModel(core)

        // Simulate removeRecentWorkdir by flipping a flag the getRecentWorkdirs
        // stub reads — this mirrors how the real ESP-backed store would drop
        // /a after the removal call lands.
        var removed = false
        every { settingsManager.removeRecentWorkdir(any(), eq("/a")) } answers { removed = true }
        every { settingsManager.getRecentWorkdirs(any()) } answers {
            if (removed) listOf("/b") else listOf("/a", "/b")
        }

        // recentWorkdirs is WhileSubscribed(5_000); keep a long-lived
        // collector alive so the upstream combine is active for the whole
        // test (otherwise .value would be stale by the time we re-read it).
        val collected = mutableListOf<List<String>>()
        val job = launch { vm.recentWorkdirs.collect { collected.add(it) } }
        advanceUntilIdle()

        // Initial derivation: both workdirs visible.
        assertEquals(listOf("/a", "/b"), collected.last())

        vm.disconnectWorkdir("/a")
        advanceUntilIdle()

        // Tick bumped → combine re-derived → /a dropped from the new snapshot.
        // Without the C1 fix this would still be listOf("/a", "/b") (the
        // settingsManager mutation is invisible to hostFlow/sessionListFlow).
        assertEquals(listOf("/b"), collected.last())

        // Side-effect sanity: the removal + eviction both fired.
        verify { settingsManager.removeRecentWorkdir(any(), "/a") }
        coVerify { core.cacheRepository.evictWorkdirInGroup(any(), "/a") }

        job.cancel()
    }

    @Test
    fun `connectWorkdir registers the project without creating a session or touching currentWorkdir`() = runTest {
        // §nav-redesign: Files "add project" registers a workdir as a first-
        // class entity independent of the session system. connectWorkdir must
        // ONLY addRecentWorkdir + bump the tick — it must NOT change
        // currentWorkdir, clear chat/draft, or create a session (contrast
        // SessionViewModel.createSessionInWorkdir's draft-hijack behaviour).
        // This test pins that contract so a future refactor can't silently
        // turn "add project" into "start a draft".
        val core = createCore()
        val vm = SettingsViewModel(core)

        var added = false
        every { settingsManager.addRecentWorkdir(any(), eq("/a")) } answers { added = true }
        every { settingsManager.getRecentWorkdirs(any()) } answers {
            if (added) listOf("/a", "/b") else listOf("/b")
        }
        // Capture currentWorkdir via a var-backed stub so we can assert it is
        // untouched by connectWorkdir (resets the baseline right before the
        // action so any init-time writes don't muddy the assertion).
        var currentWd = "/existing"
        every { settingsManager.currentWorkdir } answers { currentWd }
        every { settingsManager.currentWorkdir = any() } answers { currentWd = firstArg() }

        val collected = mutableListOf<List<String>>()
        val job = launch { vm.recentWorkdirs.collect { collected.add(it) } }
        advanceUntilIdle()
        assertEquals(listOf("/b"), collected.last())

        currentWd = "/existing" // baseline immediately before the action
        vm.connectWorkdir("/a")
        advanceUntilIdle()

        // /a registered + tick bumped → re-derived to include /a.
        verify { settingsManager.addRecentWorkdir(any(), "/a") }
        assertEquals(listOf("/a", "/b"), collected.last())
        // Contract: currentWorkdir UNCHANGED (no session/draft hijack).
        assertEquals("/existing", currentWd)
        // Contract: connectWorkdir is purely project-registry — it must NOT
        // evict/attempt session creation (no cache eviction, unlike disconnect).
        coVerify(exactly = 0) { core.cacheRepository.evictWorkdirInGroup(any(), any()) }

        job.cancel()
    }
}
