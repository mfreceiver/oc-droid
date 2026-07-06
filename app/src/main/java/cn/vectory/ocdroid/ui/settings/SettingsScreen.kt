package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.util.ThemeMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Main Settings screen — top-level page skeleton (TopAppBar + scrollable
 * sections: connection profile, traffic, appearance, debug, about). The
 * HostProfile management sub-flow lives in [HostProfilesManagerScreen] and is
 * reached from here via the "manage profiles" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: HostViewModel,
    composerVM: ComposerViewModel,
    connectionVM: ConnectionViewModel,
    settingsVM: SettingsViewModel,
    onBack: (() -> Unit)? = null
) {
    // §R-17 Stage 3 (+ follow-up debt cleanup): subscribe to the relevant
    // slice Flows directly so the host-profile picker / theme picker / traffic
    // counters / connection badge no longer recompose on every AppState
    // emission (SSE deltas, typing, session switches, etc.). The whole-app
    // `viewModel.core.state` subscription has been removed entirely.
    //
    // Field-level subscriptions (.map { it.field }.distinctUntilChanged()) are
    // used where a single field is read off a multi-field slice, so an
    // unrelated sibling-field mutation does NOT retrigger this screen. Concret
    // -ly: settingsFlow also carries agents/providers/selectedAgentName/
    // availableCommands, none of which SettingsScreen reads — so themeMode is
    // projected to a field Flow. hostFlow / trafficFlow / connectionFlow are
    // consumed whole because every field on those small slices is read here.
    val host by viewModel.hostFlow.collectAsStateWithLifecycle()
    // §flow-remember: each settingsFlow projection is wrapped in remember{}
    // so map/distinctUntilChanged aren't re-applied on every recomposition
    // (FlowOperatorInvokedInComposition).
    //
    // §R18 Phase 3 Wave 3 (P2-6): the settingsFlow reads + writes (theme /
    // providers / scales) now route through [settingsVM] (the new
    // Settings-domain VM); the trafficFlow read + refresh / reset route
    // through [connectionVM] (traffic moved into the Connection domain). The
    // flows themselves are the same SharedStateStore slices — the VM split
    // is a write-side / role-overload fix, not a state move.
    val themeMode by remember { settingsVM.settingsFlow.map { it.themeMode }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    // §model-selection: providers + disabledModels are read by the Model
    // management section. Subscribed here (not in the parent) so SSE /
    // composer deltas do not recompose SettingsScreen.
    val providers by remember { settingsVM.settingsFlow.map { it.providers }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = null)
    val disabledModels by remember { settingsVM.settingsFlow.map { it.disabledModels }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = emptySet())
    // §ui-scale: subscribe to the two scale factors so the Appearance sliders
    // render the live value + dispatch changes through the ViewModel setters.
    val uiFontScale by remember { settingsVM.settingsFlow.map { it.uiFontScale }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = 1f)
    val uiContentScale by remember { settingsVM.settingsFlow.map { it.uiContentScale }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = 1f)
    val traffic by connectionVM.trafficFlow.collectAsStateWithLifecycle()
    val connection by connectionVM.connectionFlow.collectAsStateWithLifecycle()

    // Refresh traffic counters once when the Settings screen enters
    // composition so the displayed totals reflect the latest background
    // accumulation. The tracker keeps counting regardless; this just syncs
    // the snapshot for display.
    LaunchedEffect(Unit) {
        connectionVM.refreshTrafficStats()
    }

    var showHostProfiles by remember { mutableStateOf(false) }

    if (showHostProfiles) {
        HostProfilesManagerScreen(
            viewModel = viewModel,
            connectionVM = connectionVM,
            profiles = host.hostProfiles,
            currentProfileId = host.currentHostProfileId,
            onBack = { showHostProfiles = false }
        )
        return
    }

    // Phone mode renders no TopAppBar here, so apply the status bar inset on the
    // root so content never slides under the status bar. windowInsetsPadding
    // consumes the inset, so the tablet layout (already padded at its Row) and
    // the TopAppBar branch below both see 0 and never double-pad.
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ── 连接管理 (Connection management): profile + traffic
            // under a single shared section header. Each sub-card hides its own
            // header (hideHeader = true) so only the group header shows. ──
            SectionHeader(title = stringResource(R.string.settings_section_connections))
            ConnectionProfileSection(
                profile = host.hostProfiles.firstOrNull { it.id == host.currentHostProfileId } ?: viewModel.currentHostProfile(),
                connectionState = connection,
                onManageProfiles = { showHostProfiles = true },
                hideHeader = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            TrafficSection(
                sent = traffic.trafficSent,
                received = traffic.trafficReceived,
                onReset = connectionVM::resetTrafficStats,
                hideHeader = true
            )
            Spacer(modifier = Modifier.height(12.dp))

            Spacer(modifier = Modifier.height(24.dp))

            AppearanceSection(
                themeMode = themeMode,
                onThemeSelected = settingsVM::setThemeMode,
                uiFontScale = uiFontScale,
                uiContentScale = uiContentScale,
                onFontScaleChange = settingsVM::setUiFontScale,
                onContentScaleChange = settingsVM::setUiContentScale
            )

            Spacer(modifier = Modifier.height(12.dp))
            // §model-selection: per-baseUrl disabled-model management. Toggle
            // state is persisted via SettingsManager and projected into the
            // settings slice's disabledModels field.
            ModelManagementSection(
                providers = providers,
                disabledModels = disabledModels,
                onToggleModelDisabled = { providerId, modelId ->
                    composerVM.toggleModelDisabled(providerId, modelId)
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── 调试 (Debug): debug log + danger zone under one header. ──
            SectionHeader(title = stringResource(R.string.settings_section_debug))
            DebugLogSection(hideHeader = true)
            Spacer(modifier = Modifier.height(12.dp))
            DangerZoneSection(
                onClearLocalData = viewModel::resetLocalDataAndResync,
                hideHeader = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            AboutSection()
        }
    }
}
