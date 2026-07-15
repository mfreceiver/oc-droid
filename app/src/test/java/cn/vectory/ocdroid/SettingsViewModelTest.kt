package cn.vectory.ocdroid

import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
 * remove-message-persistence Task 5: the R-20 Phase 4 cache-management
 * action surface (clearSession / clearProject / clearAll / sweepNow /
 * sweepAllGroups / refreshCacheListing / isCacheDegraded / cache-listing
 * refresh effect wiring) was removed together with the SQLite persistence layer; the
 * tests that pinned it were removed with it.
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

    // ─────────── §grouping-rewrite Round-2 C1: disconnect reactivity ──────

    @Test
    fun `disconnectWorkdir re-derives recentWorkdirs even when hostFlow + sessionListFlow are quiet`() = runTest {
        // C1: disconnectWorkdir mutates SettingsManager (removeRecentWorkdir),
        // which does not poke hostFlow / sessionListFlow. Without the explicit
        // re-derivation trigger the disconnected workdir would stay in
        // recentWorkdirs (regression of the old hiddenWorkdirs behaviour).
        //
        // remove-message-persistence Task 5: the cacheRepository
        // .evictWorkdirInGroup side-effect that used to be verified here was
        // removed with the SQLite persistence layer; the test now pins only
        // the workdir-registry mutation + tick-bumped re-derivation.
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

        // Side-effect sanity: the removal fired.
        verify { settingsManager.removeRecentWorkdir(any(), "/a") }

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

        job.cancel()
    }
}
