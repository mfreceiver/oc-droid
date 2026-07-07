package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.CacheListingState
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * splitProfile / refreshCacheListing. Each test verifies the action reaches
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
    fun `splitProfile copies old group config to new profile fp after split`() = runTest {
        val core = createCore()
        val vm = SettingsViewModel(core)
        every {
            hostProfileStore.profiles()
        } returns listOf(HostProfile(id = "profile-1", name = "Profile", serverUrl = "http://h", serverGroupFp = "old-fp"))

        vm.splitProfile("profile-1")
        advanceUntilIdle()

        verify { hostProfileStore.splitProfileToOwnGroup("profile-1") }
        verify { settingsManager.copyPerFpConfig(fromFp = "old-fp", toFp = "profile-1") }
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
}
