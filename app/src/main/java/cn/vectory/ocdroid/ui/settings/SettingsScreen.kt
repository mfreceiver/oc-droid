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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.SettingsViewModel
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
 * conditional on `onBack != null` — `SettingsScreen.kt:176-180`). The Phase 1A
 * shell + the PhoneLayout fallback both supply a real [onBack]; the
 * conditional has been removed.
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // Phone-mode status-bar inset: windowInsetsPadding consumes the inset
        // so the tablet layout + the Scaffold-padded branch both see 0.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding(),
        ) {
            settingsSections().forEachIndexed { index, sec ->
                item(key = sec.route) {
                    SettingsSectionRow(section = sec, onClick = { onNavigateSection(sec.route) })
                    if (index < settingsSections().lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

/** Section descriptor consumed by [SettingsSectionRow]. */
private data class SettingsSectionEntry(
    val route: String,
    val titleRes: Int,
    val subtitleRes: Int,
    val icon: ImageVector,
)

/** Single source of truth for the slim list ordering + route key + label. */
private fun settingsSections(): List<SettingsSectionEntry> = listOf(
    SettingsSectionEntry(NavRoute.settingsHostsRoute, R.string.settings_section_hosts, R.string.settings_section_hosts_subtitle, Icons.Default.Dns),
    SettingsSectionEntry(NavRoute.settingsAppearanceRoute, R.string.settings_section_appearance, R.string.settings_section_appearance_subtitle, Icons.Default.Palette),
    SettingsSectionEntry(NavRoute.settingsModelsRoute, R.string.settings_section_models, R.string.settings_section_models_subtitle, Icons.Default.Memory),
    SettingsSectionEntry(NavRoute.settingsNotificationsRoute, R.string.settings_section_notifications, R.string.settings_section_notifications_subtitle, Icons.Default.Notifications),
    SettingsSectionEntry(NavRoute.settingsStorageRoute, R.string.settings_section_storage, R.string.settings_section_storage_subtitle, Icons.Default.Storage),
    SettingsSectionEntry(NavRoute.settingsAboutRoute, R.string.settings_section_about, R.string.settings_section_about_subtitle, Icons.Default.Info),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSectionRow(section: SettingsSectionEntry, onClick: () -> Unit) {
    // M3 ListItem has no onClick overload in the bundled version; clickability
    // is wired by `Modifier.clickable` on the row. All six slim-list rows
    // share this single click pattern.
    ListItem(
        headlineContent = { Text(stringResource(section.titleRes)) },
        supportingContent = { Text(stringResource(section.subtitleRes)) },
        leadingContent = {
            Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubRouteScaffold(
    titleRes: Int,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
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
    ) { padding ->
        content(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
        )
    }
}

/**
 * settings/hosts — wraps the existing [HostProfilesManagerScreen]. The manager
 * screen already supplies its own TopAppBar + back, so we delegate to it
 * directly. The previous inline [ConnectionProfileSection] header is dropped:
 * the manager IS the list, and tapping a row opens the same detail/edit
 * dialogs the inline card surfaced.
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
    val uiFontScale by remember { settingsVM.settingsFlow.map { it.uiFontScale }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = 1f)
    val uiContentScale by remember { settingsVM.settingsFlow.map { it.uiContentScale }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = 1f)

    SettingsSubRouteScaffold(titleRes = R.string.settings_section_appearance, onBack = onBack) { mod ->
        Column(
            modifier = mod
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            AppearanceSection(
                themeMode = themeMode,
                onThemeSelected = settingsVM::setThemeMode,
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
                .padding(16.dp),
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
                .padding(16.dp),
        ) {
            SectionHeader(title = stringResource(R.string.settings_section_notifications))
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
                leadingContent = {
                    Icon(
                        Icons.Default.Notifications,
                        contentDescription = null,
                        tint = if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                },
            )
            HorizontalDivider()
            // The grant button is shown only when blocked AND API 33+ (the OS
            // surface that requires a runtime prompt). Pre-33 installs inherit
            // the install-time grant, so the button would be a dead no-op.
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Spacer(modifier = Modifier.height(12.dp))
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
 * settings/storage — wraps [TrafficSection] + [CacheManagementSection] +
 * [DangerZoneSection]. The previous modal-popup chrome (Dialog + Surface
 * wrapper around CacheManagementSection) is collapsed back to an inline
 * section — the sub-route's own Scaffold provides the framing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsStorageRoute(
    viewModel: HostViewModel,
    connectionVM: ConnectionViewModel,
    settingsVM: SettingsViewModel,
    onBack: () -> Unit,
) {
    val traffic by connectionVM.trafficFlow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { connectionVM.refreshTrafficStats() }

    SettingsSubRouteScaffold(titleRes = R.string.settings_section_storage, onBack = onBack) { mod ->
        Column(
            modifier = mod
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionHeader(title = stringResource(R.string.settings_traffic))
            TrafficSection(
                sent = traffic.trafficSent,
                received = traffic.trafficReceived,
                onReset = connectionVM::resetTrafficStats,
                hideHeader = true,
            )
            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader(title = stringResource(R.string.cache_management_popup_title))
            CacheManagementSection(vm = settingsVM, hideHeader = true)
            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader(title = stringResource(R.string.settings_danger_zone))
            DangerZoneSection(
                onClearLocalData = viewModel::resetLocalDataAndResync,
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
                .padding(16.dp),
        ) {
            AboutSection()
            Spacer(modifier = Modifier.height(24.dp))

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
