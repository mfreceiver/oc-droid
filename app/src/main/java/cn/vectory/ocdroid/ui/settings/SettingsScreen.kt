package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.resolveMessage
import cn.vectory.ocdroid.ui.showTimed
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Slim Settings root (Phase 3 / scheme D.8 + G.3).
 *
 * Replaces the prior everything-inline Settings page with a `LazyColumn` of
 * `ListItem` section rows. Each row pushes a sub-route via [onNavigateSection]
 * — the sub-routes own their own `Scaffold` + `TopAppBar` + back, so this
 * composable no longer boolean-branches into HostProfilesManagerScreen /
 * CacheManagement popup / etc.
 *
 * The Settings top-app-bar **always** carries a back affordance (previously
 * conditional on `onBack != null` — `SettingsScreen.kt:176-180`). AppShell
 * (the sole shell; the legacy PhoneLayout + USE_NEW_SHELL flag were removed
 * in the redesign) always supplies a real [onBack]; the conditional has been
 * removed.
 *
 * Sub-routes (route constants live in [NavRoute]):
 *  - [NavRoute.settingsHostsRoute]       → [SettingsHostsRoute]
 *  - [NavRoute.settingsAppearanceRoute]  → [SettingsAppearanceRoute]
 *  - [NavRoute.settingsModelsRoute]      → [SettingsModelsRoute]
 *  - [NavRoute.settingsAboutRoute]       → [SettingsAboutRoute]
 *
 * §phase3 red line: the Appearance sub-route REUSES the existing M3
 * [AppearanceSection] (SegmentedButton + Slider in [SettingsSections.kt])
 * verbatim — no replacement, no rewrite (plan §5 task 5 / §12 gpter #12).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: HostViewModel,
    composerVM: ComposerViewModel,
    connectionVM: ConnectionViewModel,
    settingsVM: SettingsViewModel,
    onBack: () -> Unit,
    onNavigateSection: (String) -> Unit,
) {
    // §back-unify (2026-07-26): re-enabled the back affordance on the main
    // Settings TopAppBar. It was removed in P5b-A ("Settings is now a top-level
    // screen — no back affordance"), but the user requirement is that all
    // non-root screens (Git, Settings, Files, Chat) show a unified ArrowBack.
    // The `onBack` param was already in the signature (line 108) — just
    // rendering it now.
    Scaffold(
        // §bug-5.1 (+ §polish ⑤): zero contentWindowInsets. The TopAppBar
        // consumes the status-bar inset via its modifier now
        // (statusBarsPadding().height(topBarHeight), windowInsets = 0); the
        // default safeDrawing here would otherwise double-count statusBars.top
        // on top of the bar height, producing the ~1-item empty band between
        // the title and the first row. Zeroing it keeps contentPadding.top ==
        // topBar height (no residual inset).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding().height(Dimens.topBarHeight),
                windowInsets = WindowInsets(0, 0, 0, 0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_back_to_home),
                        )
                    }
                },
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { padding ->
        // Phone-mode status-bar inset: windowInsetsPadding consumes the inset
        // so the tablet layout + the Scaffold-padded branch both see 0.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // §P5b-A (5.1): dividers dropped — the slim list is title-only rows
            // separated by default ListItem spacing.
            settingsSections().forEach { sec ->
                item(key = sec.route) {
                    SettingsSectionRow(section = sec, onClick = { onNavigateSection(sec.route) })
                }
            }
        }
    }
}

/** Section descriptor consumed by [SettingsSectionRow]. */
private data class SettingsSectionEntry(
    val route: String,
    val titleRes: Int,
    // §P5b-A (5.1): the row is now title-only — `subtitleRes` is no longer
    // rendered by [SettingsSectionRow]. The field is kept on the data class
    // to avoid a ripple through call sites; new sections can leave it 0.
    @Suppress("unused") val subtitleRes: Int,
    val icon: ImageVector,
)

/**
 * Single source of truth for the slim list ordering + route key + label.
 *
 * §P5b-A (5.3): the top-level "模型" entry was removed — its content
 * ([ModelManagementSection]) now lives inside 服务器管理 (see
 * [HostProfilesManagerScreen]). The `settingsModelsRoute` destination is
 * retained as a compatible direct destination in AppShell, but is no longer
 * listed at the Settings root.
 */
private fun settingsSections(): List<SettingsSectionEntry> = listOf(
    // §setux-unify: 服务器入口与 hub TopAppBar 共用同一短文案 key
    // （「服务器」/「Server」），与其它三项（外观/关于）「入口名 = 页面名」一致.
    SettingsSectionEntry(NavRoute.settingsHostsRoute, R.string.setux_settings_hosts_entry, R.string.settings_section_hosts_subtitle, Icons.Default.Dns),
    SettingsSectionEntry(NavRoute.settingsAppearanceRoute, R.string.settings_section_appearance, R.string.settings_section_appearance_subtitle, Icons.Default.Palette),
    SettingsSectionEntry(NavRoute.settingsDebugRoute, R.string.settings_section_debug, 0, Icons.Default.BugReport),
    SettingsSectionEntry(NavRoute.settingsAboutRoute, R.string.settings_section_about, R.string.settings_section_about_subtitle, Icons.Default.Info),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSectionRow(section: SettingsSectionEntry, onClick: () -> Unit) {
    // §P5b-A (5.1): title-only — no supportingContent (subtitle) is rendered.
    // M3 ListItem has no onClick overload in the bundled version; clickability
    // is wired by `Modifier.clickable` on the row. All slim-list rows share
    // this single click pattern.
    // §setux #new4: 移除右侧 `>` chevron 指示符（trailingContent），保留
    // clickable 行为。
    ListItem(
        headlineContent = { Text(stringResource(section.titleRes)) },
        leadingContent = {
            Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

// ──────────────────────────────────────────────────────────────────────────
// §phase3 (G.3 / D.8): per-section sub-route composables.
//
// Each owns its own Scaffold + TopAppBar (always-back). The existing M3
// section composables ([AppearanceSection], [ModelManagementSection],
// [DangerZoneSection], [TrafficSection],
// [AboutSection], [DebugLogSection]) are reused verbatim — they were already
// M3-canonical (SegmentedButton/Slider/Card/Switch). No control replacement.
// ──────────────────────────────────────────────────────────────────────────

/**
 * Shared TopAppBar shell for every Settings sub-route. Always renders a back
 * arrow wired to [onBack] (Phase 3 / G.3 step 2: the Settings top bar carries
 * back unconditionally on every sub-page).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubRouteScaffold(
    titleRes: Int,
    onBack: () -> Unit,
    snackbarHost: @Composable (() -> Unit) = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        // §bug-5.1 (+ §polish ⑤): same nested-Scaffold inset double-count fix
        // as the root SettingsScreen — the TopAppBar consumes statusBars via
        // its modifier (statusBarsPadding().height(topBarHeight),
        // windowInsets = 0); zeroing contentWindowInsets removes the residual
        // statusBars.top that would push the first section below the title.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding().height(Dimens.topBarHeight),
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = actions,
            )
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        content(
            Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

/**
 * settings/hosts — wraps the existing [HostProfilesManagerScreen]. The manager
 * screen is the 服务器管理 hub (§P5b-A / Q7): it carries the host list plus
 * the traffic stats section (moved here from settings/storage) and the model
 * management section (moved here from the removed top-level 模型 entry). The
 * manager supplies its own TopAppBar + back, so we delegate to it directly.
 *
 * §P5b-A / Q7: the model-management subscriptions (providers + disabledModels
 * live on the settings slice, which [HostViewModel.settingsFlow] already
 * exposes) and the toggle actions (resolved via a Hilt SettingsManager
 * EntryPoint inside [HostProfilesManagerScreen]) are kept on the host VM /
 * EntryPoint so the AppShell call signature is unchanged.
 */
@Composable
fun SettingsHostsRoute(
    viewModel: HostViewModel,
    connectionVM: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val host by viewModel.hostFlow.collectAsStateWithLifecycle()
    // §L8: single-host — resolve the current (only) profile instead of passing a list.
    val currentProfile = host.hostProfiles.firstOrNull { it.id == host.currentHostProfileId }
        ?: host.hostProfiles.firstOrNull()
    HostProfilesManagerScreen(
        viewModel = viewModel,
        connectionVM = connectionVM,
        currentProfile = currentProfile,
        onBack = onBack,
    )
}

/**
 * settings/appearance — REUSES [AppearanceSection] (M3 SegmentedButton +
 * Slider) **verbatim**. §phase3 red line (plan §5 task 5 + §12 gpter #12): do
 * NOT replace or rewrite the existing controls; only relocate them into this
 * sub-route. The theme/font/content-scale subscriptions are read here (off
 * `settingsVM.settingsFlow`) so SSE/composer deltas do not recompose the
 * slim Settings root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearanceRoute(
    settingsVM: SettingsViewModel,
    onBack: () -> Unit,
) {
    val themeMode by remember { settingsVM.settingsFlow.map { it.themeMode }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    // §P5a (Q5): language preference (Follow System / 中文 / English).
    val localeMode by remember { settingsVM.settingsFlow.map { it.localeMode }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = cn.vectory.ocdroid.util.LocaleMode.SYSTEM)
    val uiFontScale by remember { settingsVM.settingsFlow.map { it.uiFontScale }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = 1f)
    val uiContentScale by remember { settingsVM.settingsFlow.map { it.uiContentScale }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = 1f)

    SettingsSubRouteScaffold(titleRes = R.string.settings_section_appearance, onBack = onBack) { mod ->
        // §review-AB: parent Column no longer adds `.padding(horizontal = ...)`
        // — AppSectionHeader (self-pad 16dp) + ListItem (self-pad 16dp) +
        // bare widgets now share ONE 16dp keyline (header was at 32dp before,
        // misaligned with bare content). Bare widgets inside AppearanceSection
        // carry their own `Modifier.padding(horizontal = Dimens.spacing4)`.
        Column(
            modifier = mod.verticalScroll(rememberScrollState()),
        ) {
            AppearanceSection(
                themeMode = themeMode,
                onThemeSelected = settingsVM::setThemeMode,
                localeMode = localeMode,
                onLocaleSelected = settingsVM::setLocaleMode,
                uiFontScale = uiFontScale,
                uiContentScale = uiContentScale,
                onFontScaleChange = settingsVM::setUiFontScale,
                onContentScaleChange = settingsVM::setUiContentScale,
            )
        }
    }
}

/**
 * settings/models — wraps [ModelManagementSection]. The inline AlertDialog
 * launcher stays as-is; tapping the row in this sub-route opens the same
 * dialog. Subscriptions to providers + disabledModels live here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsModelsRoute(
    composerVM: ComposerViewModel,
    settingsVM: SettingsViewModel,
    onBack: () -> Unit,
) {
    val providers by remember { settingsVM.settingsFlow.map { it.providers }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = null)
    val disabledModels by remember { settingsVM.settingsFlow.map { it.disabledModels }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = emptySet())
    // §需求13: model-catalog loading flag — drives the Model management refresh
    // IconButton's spinner + per-row Switch disabled state. distinctUntilChanged
    // so unrelated settings churn doesn't recompose this screen.
    val isLoadingProviders by remember { settingsVM.settingsFlow.map { it.isLoadingProviders }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false)

    // §需求13 rev-7 #3: snackbar consumer for UiEvent.Error. ChatScaffold is
    // the ONLY collector of the shared uiEvents bus — when the user navigates
    // INTO this sub-route (different NavHost destination), a refresh failure
    // would emit UiEvent.Error but nobody shows a snackbar → invisible failure.
    // This collector mirrors ChatScaffold.kt:709's pattern so the
    // model_management_refresh_failed error surfaces here too.
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        composerVM.uiEvents.collect { event ->
            if (event is UiEvent.Error) {
                snackbarHostState.showTimed(
                    message = event.resolveMessage(context),
                    durationMillis = 3_000L,
                )
            }
        }
    }

    SettingsSubRouteScaffold(
        titleRes = R.string.settings_section_models,
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { mod ->
        // §review-AB: no parent horizontal padding — ModelManagementSection's
        // AppSectionHeader + ListItem self-pad; its bare empty-state Text
        // already self-pads (`Modifier.padding(Dimens.spacing4)`).
        Column(
            modifier = mod.verticalScroll(rememberScrollState()),
        ) {
            ModelManagementSection(
                providers = providers,
                disabledModels = disabledModels,
                isLoadingProviders = isLoadingProviders,
                onRefreshProviders = { composerVM.refreshProviders() },
                onToggleModelDisabled = { providerId, modelId ->
                    composerVM.toggleModelDisabled(providerId, modelId)
                },
                onSetProviderModelsEnabled = { providerId, enabled ->
                    composerVM.setProviderModelsEnabled(providerId, enabled)
                },
            )
        }
    }
}

/**
 * settings/about — wraps [AboutSection] + License information.
 *
 * The Debug section was migrated to [SettingsDebugRoute]; this page now shows
 * the app version + a License section with the project's MIT license and a
 * curated list of third-party dependencies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAboutRoute(
    @Suppress("UNUSED_PARAMETER") viewModel: HostViewModel,
    @Suppress("UNUSED_PARAMETER") settingsVM: SettingsViewModel,
    onBack: () -> Unit,
) {
    SettingsSubRouteScaffold(titleRes = R.string.settings_section_about, onBack = onBack) { mod ->
        Column(
            modifier = mod.verticalScroll(rememberScrollState()),
        ) {
            AboutSection()
            Spacer(modifier = Modifier.height(Dimens.spacing6))

            LicenseSection()
        }
    }
}
