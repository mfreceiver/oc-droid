package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.VcsInfo
import cn.vectory.ocdroid.data.model.VcsStatusEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import cn.vectory.ocdroid.ui.theme.SemanticColors
import cn.vectory.ocdroid.util.ThemeMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Main Settings screen — top-level page skeleton (TopAppBar + scrollable
 * sections: connection profile, traffic, appearance, debug, about). The
 * HostProfile management sub-flow lives in [HostProfilesManagerScreen] and is
 * reached from here via the "manage profiles" action.
 *
 * §grouping-rewrite 项 3: cache management used to live inline under the
 * Debug section; it now opens as a modal popup triggered by the stats line
 * under the connection profile (the popup title is `cache_management_popup_title`).
 * The Debug section keeps DebugLogSection + DangerZone in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: HostViewModel,
    composerVM: ComposerViewModel,
    connectionVM: ConnectionViewModel,
    settingsVM: SettingsViewModel,
    /**
     * §vcs-section: repository for the read-only Working-directory / Git
     * section. Wired the same way [cn.vectory.ocdroid.ui.sessions.SessionsScreen]
     * receives it (via FilesViewModel.repository in MainActivity) — the
     * Settings-domain VM deliberately does NOT inject AppCore, so the
     * repository is threaded through the Composable signature instead. Used
     * ONLY by [VcsSection]; the rest of this screen reads its data off the
     * shared slices through the VMs as before.
     */
    repository: OpenCodeRepository,
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
    // §reactive-workdir: the active workdir (absolute path) for the read-only
    // Working-directory / Git section. Collected as State so VcsSection reacts
    // to workdir changes (session switch / profile switch / disconnect) and its
    // LaunchedEffect(workdir) re-fetches /vcs/status automatically — previously
    // a plain snapshot read never recomposed, so the VCS panel went stale on
    // workdir change. VCS fetch + state still lives entirely in VcsSection.
    val workdir by settingsVM.currentWorkdirFlow.collectAsStateWithLifecycle()
    // §grouping-rewrite 项 2: group-stats counts for the connection-profile
    // stats line (drives the popup entry point).
    val groupProfileCount by settingsVM.activeGroupProfileCount.collectAsStateWithLifecycle()
    val cachedSessionCount by settingsVM.activeGroupCachedSessionCount.collectAsStateWithLifecycle()

    // Refresh traffic counters once when the Settings screen enters
    // composition so the displayed totals reflect the latest background
    // accumulation. The tracker keeps counting regardless; this just syncs
    // the snapshot for display.
    LaunchedEffect(Unit) {
        connectionVM.refreshTrafficStats()
    }

    var showHostProfiles by remember { mutableStateOf(false) }
    // §grouping-rewrite 项 3: cache-management popup state. Opened from the
    // ConnectionProfileSection stats line; the popup hosts the (formerly
    // inline) CacheManagementSection under the `cache_management_popup_title`.
    var showCacheDialog by remember { mutableStateOf(false) }

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
                groupProfileCount = groupProfileCount,
                cachedSessionCount = cachedSessionCount,
                onStatsClick = { showCacheDialog = true },
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
            // §vcs-section: read-only Working-directory / Git group. Visible
            // whenever there is a workdir (not gated on server connection — it
            // is "about the project", not "about the live session"). All VCS
            // state is local to the composable (single consumer); not routed
            // through SettingsState / SharedStateStore.
            VcsSection(repository = repository, workdir = workdir)

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

            // ── 调试 (Debug): debug log + danger zone under one header.
            // §grouping-rewrite 项 3: cache management has moved into a modal
            // popup (opened from the Connections section stats line) and is
            // no longer rendered inline here. ──
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

    // §grouping-rewrite 项 3 (+ Round-6 F5): cache-management popup. A
    // [Surface] carries the popup chrome (title row + close affordance); the
    // section body is [CacheManagementSection], which renders its OWN inner
    // card. Pre-F5 the popup wrapped the section in a Card-in-Card (Dialog →
    // outer Card → title row + CacheManagementSection's own Card) — the
    // redundant outer Card was visual noise (two stacked surfaces with
    // identical container colour). Surface is the right drop-in: it gives
    // the popup its shape + tonal elevation + dismissal affordance, and
    // CacheManagementSection's inner Card remains the content surface.
    if (showCacheDialog) {
        Dialog(onDismissRequest = { showCacheDialog = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(modifier = Modifier.padding(bottom = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.cache_management_popup_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        IconButton(onClick = { showCacheDialog = false }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.common_close)
                            )
                        }
                    }
                    CacheManagementSection(vm = settingsVM, hideHeader = true)
                }
            }
        }
    }
}

// ── §vcs-section: read-only Working-directory / Git group ───────────────────
//
// Self-contained: VCS data has a single consumer (this section), so it is NOT
// routed through SettingsState / SettingsViewModel / SharedStateStore. The
// composable owns its own load state via [remember] + [LaunchedEffect] keyed
// on [workdir]; the workdir input is read off the existing settingsManager
// field (passed in by SettingsScreen). The repository is threaded in from
// MainActivity (same pattern SessionsScreen uses).
//
// States rendered: Loading, NoWorkdir, NoGit, Error, Loaded. The optional
// "View diff" affordance is intentionally OMITTED to stay bounded — the diff
// helpers (DiffPatchView / buildAnnotatedDiff / statusColor) in
// [cn.vectory.ocdroid.ui.chat.SessionDiffCard] are private and that file is
// out of scope for this change; duplicating them would violate the "reuse,
// don't duplicate" guidance. The section therefore shows branch + the
// changed-files list only.

private sealed interface VcsLoadState {
    data object Loading : VcsLoadState
    data object NoWorkdir : VcsLoadState
    data object NoGit : VcsLoadState
    data class Error(val message: String) : VcsLoadState
    data class Loaded(
        val info: VcsInfo,
        val status: List<VcsStatusEntry>
    ) : VcsLoadState
}

/**
 * §vcs-section: read-only VCS / Git group for the Settings screen. Renders
 * the active workdir's branch + changed-files list. [repository] is the same
 * OpenCodeRepository every other screen uses (wired through MainActivity);
 * [workdir] is the absolute path (null while no session/project is bound).
 *
 * Loads on entry and whenever [workdir] changes; treats an absent workdir or a
 * non-git workdir as a first-class state (not an error). A null branch with an
 * empty status list is interpreted as "not a git repository" (the v1 /vcs
 * endpoint returns 200 with null fields when the workdir is not a git repo).
 */
@Composable
private fun VcsSection(
    repository: OpenCodeRepository,
    workdir: String?,
    modifier: Modifier = Modifier
) {
    var state by remember(workdir) { mutableStateOf<VcsLoadState>(VcsLoadState.Loading) }
    LaunchedEffect(workdir) {
        val dir = workdir
        if (dir.isNullOrBlank()) {
            state = VcsLoadState.NoWorkdir
            return@LaunchedEffect
        }
        // §glm-P3: re-throw CancellationException (structured-concurrency) — raw
        // runCatching would swallow it when the workdir changes mid-fetch and the
        // LaunchedEffect is cancelled.
        state = try {
            val info = repository.getVcs(dir).getOrThrow()
            val status = repository.getVcsStatus(dir).getOrDefault(emptyList())
            // Both null/empty → workdir is not a git repo (server returns 200
            // with null fields rather than 4xx).
            if (info.branch == null && info.defaultBranch == null && status.isEmpty()) {
                VcsLoadState.NoGit
            } else {
                VcsLoadState.Loaded(info, status)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            VcsLoadState.Error(e.message ?: "unknown")
        }
    }

    SectionHeader(title = stringResource(R.string.settings_section_working_directory))

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (val s = state) {
                is VcsLoadState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = workdir.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                is VcsLoadState.NoWorkdir -> Text(
                    text = stringResource(R.string.settings_vcs_no_working_directory),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is VcsLoadState.NoGit -> Column {
                    Text(
                        text = workdir.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_vcs_not_a_git_repo),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is VcsLoadState.Error -> Column {
                    Text(
                        text = workdir.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_vcs_load_error, s.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                is VcsLoadState.Loaded -> VcsLoadedBody(info = s.info, status = s.status)
            }
        }
    }
}

@Composable
private fun VcsLoadedBody(info: VcsInfo, status: List<VcsStatusEntry>) {
    // Branch line: branch (dim "default <defaultBranch>" suffix when the
    // current branch differs from / complements the default).
    Text(
        text = stringResource(R.string.settings_vcs_branch),
        style = MaterialTheme.typography.labelMedium
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = info.branch ?: "—",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        info.defaultBranch?.takeIf { it.isNotBlank() }?.let { default ->
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_vcs_default_branch_suffix, default),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (status.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_vcs_changed_files),
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        // §gpter-P3: cap the eagerly-composed rows so a repo with many untracked /
        // changed files doesn't compose/layout a huge list inside the Settings
        // verticalScroll. The remainder is summarised as "+N more".
        val maxRows = 50
        status.take(maxRows).forEach { entry -> VcsStatusRow(entry = entry) }
        if (status.size > maxRows) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_vcs_more_files, status.size - maxRows),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_vcs_no_changes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VcsStatusRow(entry: VcsStatusEntry) {
    val statusColor = vcsStatusColor(entry.status)
    val basename = entry.file.substringAfterLast("/").ifEmpty { entry.file }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = statusColor, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = basename,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = entry.file,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (entry.additions > 0) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "+${entry.additions}",
                style = MaterialTheme.typography.labelSmall,
                color = SemanticColors.stateSuccessFg(),
                fontFamily = BundledMonoFamily
            )
        }
        if (entry.deletions > 0) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "-${entry.deletions}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontFamily = BundledMonoFamily
            )
        }
    }
}

/**
 * File-status → semantic color. Mirrors the mapping in
 * [cn.vectory.ocdroid.ui.chat.SessionDiffCard.statusColor] (added/modified/
 * deleted/untracked) so the working-directory list uses the same visual
 * language as the chat SessionDiffCard. Local copy because that helper is
 * private to the chat package (and SessionDiffCard.kt is out of scope).
 */
private fun vcsStatusColor(status: String?): Color = when (status?.lowercase()) {
    "added" -> SemanticColors.addedFile
    "deleted" -> SemanticColors.deletedFile
    "modified" -> SemanticColors.modifiedFile
    else -> SemanticColors.untrackedFile
}
