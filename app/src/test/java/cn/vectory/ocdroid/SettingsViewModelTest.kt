package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import io.mockk.coEvery
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
