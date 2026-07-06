package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * §R18 Phase 3 Wave 3 (P2-6): Settings-domain ViewModel. Owns the user-
 * facing settings writes (theme mode, markdown font sizes, UI font/content
 * scale). Split out of [OrchestratorViewModel] so the latter no longer
 * carries the settings role (it was overloaded with settings + traffic +
 * permission + nav + file + cross-domain orchestration).
 *
 * Reads stay delegated through [core] (the same [SharedStateStore] flows
 * every other VM exposes); writes flow through [core.writeSettings] +
 * [SettingsManager] exactly as they did when the methods lived on
 * [OrchestratorViewModel] — pure relocation, zero behaviour change.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    internal val core: AppCore,
) : ViewModel() {

    /** Read accessor — same authoritative slice [OrchestratorViewModel] and
     *  the other domain VMs expose (all delegate to [SharedStateStore]). Kept
     *  here so SettingsScreen can read settings off its own VM without
     *  reaching into another domain. */
    val settingsFlow get() = core.settingsFlow

    fun setThemeMode(mode: ThemeMode) {
        core.settingsManager.themeMode = mode
        core.writeSettings { it.copy(themeMode = mode) }
    }

    fun setMarkdownFontSizes(sizes: MarkdownFontSizes) {
        core.settingsManager.markdownFontSizes = sizes
        core.writeSettings { it.copy(markdownFontSizes = sizes) }
    }

    fun setUiFontScale(scale: Float) {
        val clamped = scale.coerceIn(SettingsManager.UI_SCALE_MIN, SettingsManager.UI_SCALE_MAX)
        core.settingsManager.uiFontScale = clamped
        core.writeSettings { it.copy(uiFontScale = clamped) }
    }

    fun setUiContentScale(scale: Float) {
        val clamped = scale.coerceIn(SettingsManager.UI_SCALE_MIN, SettingsManager.UI_SCALE_MAX)
        core.settingsManager.uiContentScale = clamped
        core.writeSettings { it.copy(uiContentScale = clamped) }
    }
}
