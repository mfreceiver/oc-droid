package cn.vectory.ocdroid.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.ui.util.formatBytes
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
 *  - [NavRoute.settingsNotificationsRoute] → [SettingsNotificationsRoute]
 *  - [NavRoute.settingsStorageRoute]     → [SettingsStorageRoute]
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
    // §P5b-A (5.1): Settings is now a top-level screen — no back affordance
    // on its TopAppBar. The `onBack` param stays in the signature so the
    // AppShell call site is unchanged, but is no longer rendered here.
    Scaffold(
        // §bug-5.1: zero contentWindowInsets — the TopAppBar already consumes
        // the status-bar inset via its own TopAppBarDefaults.windowInsets, so
        // the default safeDrawing here was double-counting statusBars.top on
        // top of the bar height, producing the ~1-item empty band between the
        // "设置" title and the first row. Zeroing it makes contentPadding.top
        // == topBar height only (no residual inset). fix-8 had removed the
        // explicit .statusBarsPadding() on the LazyColumn but NOT this nested
        // Scaffold default — that was the leftover source of the gap.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
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
    // §setux #new4: 服务器入口用专用短文案 key（「服务器」/「Server」）。
    // settings_section_hosts（「服务器管理」）仍由 hub TopAppBar 使用。
    SettingsSectionEntry(NavRoute.settingsHostsRoute, R.string.setux_settings_hosts_entry, R.string.settings_section_hosts_subtitle, Icons.Default.Dns),
    SettingsSectionEntry(NavRoute.settingsAppearanceRoute, R.string.settings_section_appearance, R.string.settings_section_appearance_subtitle, Icons.Default.Palette),
    SettingsSectionEntry(NavRoute.settingsNotificationsRoute, R.string.settings_section_notifications, R.string.settings_section_notifications_subtitle, Icons.Default.Notifications),
    SettingsSectionEntry(NavRoute.settingsStorageRoute, R.string.settings_section_storage, R.string.settings_section_storage_subtitle, Icons.Default.Storage),
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
// [CacheManagementSection], [DangerZoneSection], [TrafficSection],
// [AboutSection], [DebugLogSection]) are reused verbatim — they were already
// M3-canonical (SegmentedButton/Slider/Card/Switch). No control replacement.
// ──────────────────────────────────────────────────────────────────────────

/**
 * Shared TopAppBar shell for every Settings sub-route. Always renders a back
 * arrow wired to [onBack] (Phase 3 / G.3 step 2: the Settings top bar carries
 * back unconditionally on every sub-page).
 *
 * §P5b-B (Q8): [snackbarHost] defaults to empty (no host); the storage route
 * passes a real [SnackbarHostState] so [CacheManagementSection] can surface
 * the 3s sweep-result snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubRouteScaffold(
    titleRes: Int,
    onBack: () -> Unit,
    snackbarHost: @Composable (() -> Unit) = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        // §bug-5.1: same nested-Scaffold inset double-count fix as the root
        // SettingsScreen — the TopAppBar consumes statusBars itself; zeroing
        // contentWindowInsets removes the residual statusBars.top that was
        // pushing the first section ~1 item below the sub-route title.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
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
    HostProfilesManagerScreen(
        viewModel = viewModel,
        connectionVM = connectionVM,
        profiles = host.hostProfiles,
        currentProfileId = host.currentHostProfileId,
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
        Column(
            modifier = mod
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.spacing4),
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

    SettingsSubRouteScaffold(titleRes = R.string.settings_section_models, onBack = onBack) { mod ->
        Column(
            modifier = mod
                .verticalScroll(rememberScrollState())
                .padding(Dimens.spacing4),
        ) {
            ModelManagementSection(
                providers = providers,
                disabledModels = disabledModels,
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
 * settings/notifications — Phase 3 / D.8 new section. Minimal read-only
 * surface: shows the system's POST_NOTIFICATIONS grant state + the §18
 * completion channel's purpose line. No controller surgery (no new state
 * slice, no SettingsManager write) — runtime permission prompt (when the
 * user has not yet granted) is still gated by the existing §18 toggle and
 * AppLifecycleMonitor; we just surface the resulting state here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsNotificationsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(notificationPermissionGranted(context)) }
    // Optional runtime prompt for API 33+. Mirrors AppLifecycleMonitor's
    // grant-bypass-on-older-OS check. Launched lazily via a button so the
    // system dialog is never triggered from the background.
    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result || notificationPermissionGranted(context)
    }

    SettingsSubRouteScaffold(titleRes = R.string.settings_section_notifications, onBack = onBack) { mod ->
        Column(
            modifier = mod
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.spacing4),
        ) {
            SectionHeader(title = stringResource(R.string.settings_section_notifications))
            // §setux #new5: 移除 leadingContent icon，与其它 settings item
            // 风格一致（无 leading icon 的标准 ListItem）。颜色态在
            // supportingContent 文案里仍可读。
            ListItem(
                headlineContent = {
                    Text(
                        if (granted) stringResource(R.string.settings_notifications_runtime_perm_granted)
                        else stringResource(R.string.settings_notifications_runtime_perm_blocked),
                    )
                },
                supportingContent = {
                    Text(stringResource(R.string.settings_notifications_completion_channel_desc))
                },
            )
            // The grant button is shown only when blocked AND API 33+ (the OS
            // surface that requires a runtime prompt). Pre-33 installs inherit
            // the install-time grant, so the button would be a dead no-op.
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Spacer(modifier = Modifier.height(Dimens.spacing3))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.TextButton(onClick = {
                        permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) {
                        Text(stringResource(R.string.settings_section_notifications))
                    }
                }
            }
        }
    }
}

/**
 * settings/storage — wraps [DangerZoneSection] (清除数据) + [CacheManagementSection]
 * (缓存管理). The previous modal-popup chrome (Dialog + Surface wrapper around
 * CacheManagementSection) is collapsed back to an inline section — the
 * sub-route's own Scaffold provides the framing.
 *
 * §P5b-A / Q7: [TrafficSection] moved from here to 服务器管理
 * ([HostProfilesManagerScreen]) — traffic is conceptually per-server, so it
 * lives with the host list now.
 *
 * §P5b-B / Q8: the page now has TWO sections in this order:
 *  ① 清除数据 (was Danger Zone) — flat, single row "已缓存数据 XXX MB" + 清除缓存
 *     button (destructive: resetLocalDataAndResync, confirmation dialog).
 *  ② 缓存管理 (was 缓存与维护) — 3-level tree (group → workdir → session) with
 *     destructive 清除失联会话 sweeps whose results surface via a 3s snackbar.
 * The Scaffold carries a [SnackbarHost] for the sweep-result snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsStorageRoute(
    viewModel: HostViewModel,
    @Suppress("UNUSED_PARAMETER") connectionVM: ConnectionViewModel,
    settingsVM: SettingsViewModel,
    onBack: () -> Unit,
) {
    // §P5b-A / Q7: `connectionVM` is kept in the signature so the AppShell
    // call site is unchanged (traffic stats moved to 服务器管理).

    // §P5b-B / Q8: SnackbarHost for the 缓存管理 sweep-result snackbar.
    val snackbarHostState = remember { SnackbarHostState() }
    // §P5b-B / Q8: cached-data payload size for the 清除数据 row.
    val cachedBytes by settingsVM.cachedDataBytes.collectAsStateWithLifecycle()
    val cachedDataSize = formatBytes(cachedBytes)

    SettingsSubRouteScaffold(
        titleRes = R.string.settings_section_storage,
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { mod ->
        Column(
            modifier = mod
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.spacing4),
        ) {
            // ① 清除数据 (first, flat).
            SectionHeader(title = stringResource(R.string.settings_danger_zone))
            DangerZoneSection(
                cachedDataSize = cachedDataSize,
                onClearLocalData = viewModel::resetLocalDataAndResync,
                hideHeader = true,
            )
            Spacer(modifier = Modifier.height(Dimens.spacing6))

            // ② 缓存管理 (3-level tree + destructive sweeps + 3s snackbar).
            SectionHeader(title = stringResource(R.string.cache_management_popup_title))
            CacheManagementSection(
                vm = settingsVM,
                snackbarHostState = snackbarHostState,
                hideHeader = true,
            )
        }
    }
}

/**
 * settings/about — wraps [AboutSection] + [DebugLogSection]. The "Debug"
 * header (formerly a top-level Settings group) now lives here, with debug-log
 * as the only entry (the diagnostic panels moved to Connections in an earlier
 * release, and the cache-management popup moved to settings/storage above).
 *
 * Parameters `viewModel` + `settingsVM` document the Activity-scoped Hilt
 * graph callers must keep alive for [DebugLogSection]'s @EntryPoint
 * (SettingsManager / AppState). They are not directly referenced in the body.
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
            modifier = mod
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.spacing4),
        ) {
            AboutSection()
            Spacer(modifier = Modifier.height(Dimens.spacing6))

            SectionHeader(title = stringResource(R.string.settings_section_debug))
            DebugLogSection(hideHeader = true)
        }
    }
}

/** POST_NOTIFICATIONS grant state (pre-33 = install-time granted). */
private fun notificationPermissionGranted(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}
