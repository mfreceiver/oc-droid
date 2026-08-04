package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.CommandInfo
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.util.LocaleMode
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.ThemeMode

/**
 * §R-17 batch2: settings/global-preference state slice. Authoritative storage
 * via _settingsFlow.update. Field set strictly follows RFC R-17 §2.4 (error
 * is NOT here — it is a one-shot UiEvent on _uiEvents).
 *
 * `availableCommands` is a connect-time / host-switch config (not live state)
 * but RFC §2.4 groups it here rather than under composer.
 */
data class SettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * §P5a (Q5): user-facing language preference. Seeded from
     * [cn.vectory.ocdroid.util.SettingsManager.localeMode] at cold-start
     * (ConnectionActions) and mutated by [cn.vectory.ocdroid.ui.SettingsViewModel.setLocaleMode].
     * The locale itself is applied via [cn.vectory.ocdroid.util.AppLocaleController]
     * (AppCompatDelegate); this field is the reactive UI mirror so the
     * Appearance SegmentedButton shows the right selection.
     */
    val localeMode: LocaleMode = LocaleMode.SYSTEM,
    val markdownFontSizes: MarkdownFontSizes = MarkdownFontSizes(),
    val agents: List<AgentInfo> = emptyList(),
    // §chat-ux-batch T8 (B3): the legacy `selectedAgentName` field was deleted
    // here. T7 rewired agent selection to the TRANSIENT `pendingAgent` chat-
    // slice field (resolved `pending ?: infer ?: null` at send). The UI reads
    // the effective agent via that projection (ChatScaffold passes
    // `currentAgentName = effectiveAgent` to ChatTopBar); no settings-slice
    // mirror is needed.
    val providers: ProvidersResponse? = null,
    /**
     * §需求13: true while a model-catalog fetch (launchLoadProviders) is in
     * flight. Drives the Model management refresh IconButton's loading state
     * + the per-row Switch disabled state so the user can't toggle a model
     * whose catalog is mid-refresh. Set true at fetch start, false on
     * success/failure/cancellation. Independent of [providers] (catalog
     * content) — a manual refresh sets this even when providers is already
     * non-null. Mirrors the [SessionListState.isRefreshingSessions] pattern.
     */
    val isLoadingProviders: Boolean = false,
    val availableCommands: List<CommandInfo> = emptyList(),
    /**
     * §model-selection: per-baseUrl disabled-model entries (format
     * `"$providerId/$modelId"`), projected from
     * [cn.vectory.ocdroid.util.SettingsManager.getDisabledModels] for the
     * current host. Used by Settings → Model management and the chat
     * quick-switch picker to hide unchecked models.
     */
    val disabledModels: Set<String> = emptySet(),
    /**
     * §ui-scale: user-adjustable UI scale factors (M3 LocalDensity override
     * pattern). [uiFontScale] multiplies fontScale (text only);
     * [uiContentScale] multiplies density (dp dimensions + sp text together).
     * Both default 1.0; clamped to SettingsManager.UI_SCALE_MIN–MAX. Seeded
     * from SettingsManager on connect; persisted via the setters in
     * MainViewModel. Read by MainActivity → OpenCodeTheme → LocalDensity.
     */
    val uiFontScale: Float = 1f,
    val uiContentScale: Float = 1f
)
