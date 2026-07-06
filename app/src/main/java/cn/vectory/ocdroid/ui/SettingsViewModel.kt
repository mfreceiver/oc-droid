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
 * §R-19 Sprint 3 P2-5: this VM no longer injects [AppCore]. Its precise
 * dependency surface is the settings slice ([SharedStateStore]'s
 * [SharedStateStore.settingsFlow] / [SharedStateStore.mutateSettings]) +
 * [SettingsManager] (the persistence side-effect). The VM cannot reach any
 * other slice/controller — it has no compile-time handle to them. Reads
 * delegate to the same authoritative [SharedStateStore] singleton every
 * other VM exposes; writes funnel through [SharedStateStore.mutateSettings]
 * + [SettingsManager] exactly as they did when the methods lived on
 * [OrchestratorViewModel] — pure relocation, zero behaviour change.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SharedStateStore,
    private val settingsManager: SettingsManager,
) : ViewModel() {

    /**
     * §R-19 P2-5 test-only convenience constructor: lets existing tests that
     * build a full [AppCore] (via [cn.vectory.ocdroid.MainViewModelTestBase])
     * keep instantiating this VM with `SettingsViewModel(core)` while the
     * production Hilt graph uses the primary [@Inject constructor] above
     * (precise-injected). The secondary forwards the same deps the
     * production binding would; Hilt ignores it (not @Inject-annotated).
     */
    internal constructor(core: AppCore) : this(core.store, core.settingsManager)

    /** Read accessor — same authoritative slice [OrchestratorViewModel] and
     *  the other domain VMs expose (all delegate to [SharedStateStore]). Kept
     *  here so SettingsScreen can read settings off its own VM without
     *  reaching into another domain. */
    val settingsFlow get() = store.settingsFlow

    fun setThemeMode(mode: ThemeMode) {
        settingsManager.themeMode = mode
        store.mutateSettings { it.copy(themeMode = mode) }
    }

    fun setMarkdownFontSizes(sizes: MarkdownFontSizes) {
        settingsManager.markdownFontSizes = sizes
        store.mutateSettings { it.copy(markdownFontSizes = sizes) }
    }

    fun setUiFontScale(scale: Float) {
        val clamped = scale.coerceIn(SettingsManager.UI_SCALE_MIN, SettingsManager.UI_SCALE_MAX)
        settingsManager.uiFontScale = clamped
        store.mutateSettings { it.copy(uiFontScale = clamped) }
    }

    fun setUiContentScale(scale: Float) {
        val clamped = scale.coerceIn(SettingsManager.UI_SCALE_MIN, SettingsManager.UI_SCALE_MAX)
        settingsManager.uiContentScale = clamped
        store.mutateSettings { it.copy(uiContentScale = clamped) }
    }
}
