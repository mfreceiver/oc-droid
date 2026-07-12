// CacheManagementSection.kt — §P5b-B (Q8) storage-management UI (缓存管理).
//
// Renders a 3-level tree: server group/standalone → project (workdir,
// collapsible) → session (bullet + full name + indented 最新消息/最近校验
// timestamps, 清除 button spanning the two time rows).
//
// The top-level action and the connected group's "清除失联会话" action are destructive sweeps
// (scan + delete orphans via [CacheMaintenanceCoordinator.dailySweepIfNeeded],
// which deletes orphaned sessions when the alive set is complete). The result
// is reported via a 3s snackbar ("已清除 N 个失联会话").
//
// Mutations are routed through [SettingsViewModel] callbacks so
// SettingsViewModelTest can verify each action reaches the underlying
// Repository/Coordinator.

package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.ui.CacheGroupListing
import cn.vectory.ocdroid.ui.CacheListingState
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.showTimed
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.util.WorkdirPaths
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * §P5b-B (Q8): the 缓存管理 section. Renders a 3-level tree (group/standalone
 * → project/workdir → session) with destructive "清除失联会话" sweep actions
 * (top-level + per-group) whose results surface via a 3s snackbar.
 *
 * The section is hosted inside [SettingsScreen]'s vertically-scrolling Column.
 * The per-group tree renders as a plain Column (NOT a LazyColumn) so it fully
 * expands — the parent scroll handles overflow (no heightIn cap needed).
 *
 * @param vm the Settings-domain VM (owns the cache listing state + the
 *   mutation methods).
 * @param snackbarHostState the host for the 3s sweep-result snackbar. Owned
 *   by [cn.vectory.ocdroid.ui.settings.SettingsStorageRoute]'s Scaffold.
 * @param hideHeader when true, the section omits its own [SectionHeader].
 */
@Composable
internal fun CacheManagementSection(
    vm: SettingsViewModel,
    snackbarHostState: SnackbarHostState,
    hideHeader: Boolean = false,
) {
    if (!hideHeader) {
        SectionHeader(title = stringResource(R.string.settings_section_cache))
    }

    LaunchedEffect(Unit) { vm.refreshCacheListing() }

    val listing by vm.cacheListing.collectAsStateWithLifecycle()
    val lastSweep by vm.lastSweep.collectAsStateWithLifecycle()
    val isOnline = vm.isOnline
    val currentServerGroupFp = vm.currentServerGroupFp
    val isDegraded = vm.isCacheDegraded

    // §P5b-B (Q8): resolve the snackbar template in the composable body
    // (stringResource — the Compose-canonical way, so locale changes
    // invalidate correctly). The count is filled inside the LaunchedEffect
    // via String.format because it depends on lastSweep (dynamic).
    val clearResultTemplate = stringResource(R.string.cache_clear_lost_sessions_result)

    // §P5b-B (Q8): 3s snackbar reporting the destructive sweep result. Fires
    // whenever lastSweep changes to a non-null report — each sweep produces a
    // new DailySweepReport, so the effect re-triggers per sweep (top-level or
    // per-group). The evicted count comes from report.evictedSessionIds
    // (orphan sessions that were scan+deleted).
    LaunchedEffect(lastSweep) {
        val report = lastSweep ?: return@LaunchedEffect
        val count = report.evictedSessionIds.size
        snackbarHostState.showTimed(clearResultTemplate.format(count))
    }

    if (isDegraded) {
        DegradedCacheWarning()
        Spacer(modifier = Modifier.height(Dimens.spacing3))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(Dimens.spacing4)) {
            // ── Top-level "清除失联会话" — fully sweeps the connected group;
            // other groups receive only their safe local eviction pass.
            Button(
                onClick = { vm.sweepAllGroups() },
                enabled = isOnline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(Dimens.iconXs))
                Spacer(modifier = Modifier.width(Dimens.spacing1))
                Text(stringResource(R.string.cache_management_sweep_all))
            }

            if (!isOnline) {
                Spacer(modifier = Modifier.height(Dimens.spacing1))
                Text(
                    stringResource(R.string.cache_management_offline_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(Dimens.spacing3))

            when (val state = listing) {
                CacheListingState.Loading -> Text(
                    stringResource(R.string.cache_management_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CacheListingState.Empty -> Text(
                    stringResource(R.string.cache_management_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is CacheListingState.Error -> Text(
                    stringResource(R.string.cache_management_error, state.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                is CacheListingState.Loaded -> {
                    if (state.groups.isEmpty()) {
                        Text(
                            stringResource(R.string.cache_management_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // §P5b-B (Q8): plain Column (NOT LazyColumn) — no
                        // heightIn cap. The parent verticalScroll handles
                        // overflow; groups + projects + sessions fully expand.
                        state.groups.forEach { group ->
                            CacheGroupCard(
                                group = group,
                                isOnline = isOnline,
                                isCurrentServerGroup = group.serverGroupFp == currentServerGroupFp,
                                onClearSession = { sid ->
                                    vm.clearSession(group.serverGroupFp, sid)
                                },
                                onSweep = { vm.sweepNow(group.serverGroupFp) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

// ─────────── Level 1: per-group card (3-level tree root) ─────────────────

/**
 * One server-group's section: 2-line header (fp + member counts) with a
 * trailing "清除失联会话" button (enabled only for the connected group), followed by
 * the project → session 3-level tree.
 */
@Composable
private fun CacheGroupCard(
    group: CacheGroupListing,
    isOnline: Boolean,
    isCurrentServerGroup: Boolean,
    onClearSession: (String) -> Unit,
    onSweep: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.spacing2)) {
        // Level 1: group title (2-line) + trailing sweep button.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.cache_management_group_header, group.serverGroupFp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    fontFamily = BundledMonoFamily
                )
                Text(
                    stringResource(
                        R.string.cache_management_group_member_count,
                        group.sessions.size,
                        group.profiles.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onSweep,
                enabled = isOnline && isCurrentServerGroup
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(Dimens.iconXs))
                Spacer(modifier = Modifier.width(Dimens.spacing1))
                Text(stringResource(R.string.cache_management_action_sweep))
            }
        }

        if (!isOnline || !isCurrentServerGroup) {
            Spacer(modifier = Modifier.height(Dimens.spacing1))
            Text(
                stringResource(
                    if (!isOnline) R.string.cache_management_offline_hint
                    else R.string.cache_management_non_current_group_hint
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(Dimens.spacing2))

        // Level 2 + 3: sessions grouped by workdir (normalized), each project
        // is a collapsible row; sessions render indented under their project.
        val projects = remember(group.sessions) {
            CacheRowPresentation.groupByNormalizedWorkdir(group.sessions)
        }

        projects.forEach { project ->
            ProjectSessionTree(
                project = project,
                onClearSession = onClearSession
            )
        }
    }
}

// ─────────── Level 2: collapsible project (workdir) row ──────────────────

/**
 * One project (workdir) row: a collapsible header showing ONLY the workdir
 * directory path (no 清除项目 button — removed per §P5b-B). Tapping toggles
 * collapse; collapsing hides all sessions under that workdir.
 *
 * @param project the workdir bucket (displayWorkdir null = 未知项目).
 */
@Composable
private fun ProjectSessionTree(
    project: CacheProjectBucket,
    onClearSession: (String) -> Unit,
) {
    // Default expanded — the user sees their sessions on first render.
    var expanded by remember(project.normalizedWorkdir) { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = Dimens.spacing1),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(Dimens.iconXs),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(Dimens.spacing1))
            Text(
                project.displayWorkdir
                    ?: stringResource(R.string.cache_unknown_project),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = BundledMonoFamily
            )
        }

        if (expanded) {
            project.sessions.forEach { row ->
                CachedSessionRowItem(
                    row = row,
                    onClearSession = { onClearSession(row.sessionId) }
                )
            }
        }
    }
}

// ─────────── Level 3: per-session row ────────────────────────────────────

/**
 * One cached-session row, indented relative to its project. Renders:
 *  - A bullet "•" + the FULL session name (sessionId — not truncated; the
 *    cache does not store a separate session title, so the id IS the name).
 *  - Below the name, further indented: 最新消息 + 最近校验 (two lines).
 *  - The 清除 button on the RIGHT, vertically centered across the two time
 *    rows (Row: times Column weight=1 + button).
 *
 * The 缓存时间 (createdAt) row is removed per §P5b-B. The 疑似废弃
 * (stale >7d) red marker is kept. The 清除项目 button is removed.
 */
@Composable
private fun CachedSessionRowItem(
    row: CacheRepository.CachedSessionRow,
    onClearSession: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    val suspect = remember(row.messageCount, row.lastVerifiedAt, now) {
        CacheRowPresentation.isSuspectAbandoned(row.messageCount, row.lastVerifiedAt, now)
    }

    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val newestText = dateFormatter.format(Date(row.newestCachedAt))
    val verifiedText = if (row.lastVerifiedAt <= 0L) {
        stringResource(R.string.cache_management_never_verified)
    } else {
        dateFormatter.format(Date(row.lastVerifiedAt))
    }

    // Indented relative to the project row (start padding).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.spacing6, top = Dimens.spacing1, bottom = Dimens.spacing1)
    ) {
        // Name line: bullet + full session id (NOT truncated — maxLines
        // defaults to Int.MAX_VALUE, so the full id shows, wrapping if needed).
        Row(verticalAlignment = Alignment.Top) {
            Text(
                "•",
                style = MaterialTheme.typography.bodySmall,
                color = if (suspect) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(Dimens.spacing1))
            Text(
                row.sessionId,
                style = MaterialTheme.typography.bodySmall,
                color = if (suspect) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                fontFamily = BundledMonoFamily
            )
        }

        // Time rows + clear button: button spans the two time rows
        // (vertically centered across both lines). Times are indented
        // further than the name (extra start padding).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Dimens.spacing3, top = Dimens.spacing1),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.cache_management_row_newest_at, newestText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.cache_management_row_verified_at, verifiedText),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (suspect) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onClearSession) {
                Text(stringResource(R.string.cache_management_action_clear_session))
            }
        }
    }
}

// ─────────── Auxiliary presentational helpers ───────────────────────────

/** One normalized project bucket rendered by the cache-management tree. */
internal data class CacheProjectBucket(
    val normalizedWorkdir: String,
    val displayWorkdir: String?,
    val sessions: List<CacheRepository.CachedSessionRow>,
)

@Composable
private fun DegradedCacheWarning() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Dimens.spacing4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(Dimens.spacing3))
            Text(
                stringResource(R.string.cache_management_degraded_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

// ─────────── Pure presentation logic (testable without Compose) ──────────

/**
 * Pure helpers for the cache-management UI. Extracted so
 * CacheManagementSectionPureTest can exercise the threshold / grouping
 * logic without spinning up a Compose host.
 */
internal object CacheRowPresentation {
    /** 7 days in ms — matches [CacheRepository.applyEvictionPolicy]'s 7d rule. */
    const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L

    /**
     * True iff this is a non-empty cached session whose [lastVerifiedAt] is
     * older than 7 days from [now] (or has never been set). Empty metadata
     * rows can have a fresh-looking newestCachedAt after an empty window write,
     * but without messages they are not surfaced as "abandoned content".
     */
    fun isSuspectAbandoned(messageCount: Int, lastVerifiedAt: Long, now: Long): Boolean =
        messageCount > 0 && (lastVerifiedAt <= 0L || (now - lastVerifiedAt) > SEVEN_DAYS_MS)

    /**
     * Groups rows by [WorkdirPaths.normalize] for the project level of the
     * tree. Non-empty project keys are sorted lexically; the missing-workdir
     * bucket follows them and is rendered as "Unknown project" by Compose.
     * Rows within a bucket retain their repository order (newest first).
     */
    fun groupByNormalizedWorkdir(
        rows: List<CacheRepository.CachedSessionRow>,
    ): List<CacheProjectBucket> = rows
        .groupBy { WorkdirPaths.normalize(it.workdir) }
        .entries
        .sortedWith(compareBy<Map.Entry<String, List<CacheRepository.CachedSessionRow>>> { it.key.isEmpty() }
            .thenBy { it.key })
        .map { (normalizedWorkdir, sessions) ->
            CacheProjectBucket(
                normalizedWorkdir = normalizedWorkdir,
                displayWorkdir = sessions.first().workdir.takeIf { normalizedWorkdir.isNotEmpty() },
                sessions = sessions,
            )
        }
}

// ─────────── Hilt @EntryPoint (precedent: DebugLogSection) ───────────────

/**
 * R-20 Phase 4: Hilt EntryPoint that exposes the cache singletons needed by
 * the read-only portions of [CacheManagementSection]. Mirrors
 * DebugLogSection's pattern for Composables that live outside any
 * @HiltViewModel scope.
 *
 * Phase 4 routes mutations through [SettingsViewModel] callbacks (so the
 * existing SettingsViewModelTest can verify each action reaches the
 * underlying Repository/Coordinator); this EntryPoint exists for any
 * future read-only polling helper that wants to bypass the VM (none in
 * Phase 4 yet, but declaring it now matches the DebugLogSection precedent
 * and costs nothing).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CacheManagementEntryPoint {
    fun cacheRepository(): CacheRepository
    fun cacheMaintenanceCoordinator(): cn.vectory.ocdroid.data.cache.CacheMaintenanceCoordinator
}

/**
 * Resolves the cache singletons from the Hilt-attached Application.
 * Cached with `remember(context)` so the entry-point lookup runs once per
 * Activity (the Application context is stable across recompositions).
 */
@Composable
@Suppress("unused") // Reserved for future read-only polling helpers.
private fun rememberCacheManagementEntryPoint(): CacheManagementEntryPoint {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            CacheManagementEntryPoint::class.java
        )
    }
}

// ─────────── Compose @Preview (render samples; no ViewModel required) ────

@androidx.compose.ui.tooling.preview.Preview(
    name = "Cache management — populated",
    showBackground = true
)
@Composable
private fun CacheManagementSectionPopulatedPreview() {
    CacheManagementSectionPreviewHost(
        state = CacheListingState.Loaded(
            groups = listOf(
                CacheGroupListing(
                    serverGroupFp = "fp-aaa-111",
                    profiles = listOf(previewProfile("alpha"), previewProfile("beta")),
                    sessions = listOf(
                        previewRow(
                            fp = "fp-aaa-111",
                            sid = "ses_1234567890abcdef",
                            workdir = "/home/me/proj-x",
                            newest = System.currentTimeMillis() - 3_600_000L,
                            verified = System.currentTimeMillis() - 1_800_000L
                        ),
                        previewRow(
                            fp = "fp-aaa-111",
                            sid = "ses_deadbeef",
                            workdir = "/home/me/proj-y",
                            newest = System.currentTimeMillis() - 30 * 24 * 3_600_000L,
                            verified = System.currentTimeMillis() - 9 * 24 * 3_600_000L, // >7d → suspect
                            exhaustedGap = true
                        )
                    )
                ),
                CacheGroupListing(
                    serverGroupFp = "fp-bbb-222",
                    profiles = listOf(previewProfile("tunnel")),
                    sessions = listOf(
                        previewRow(
                            fp = "fp-bbb-222",
                            sid = "ses_other",
                            workdir = "/home/me/elsewhere",
                            newest = System.currentTimeMillis() - 86_400_000L,
                            verified = System.currentTimeMillis() - 86_400_000L
                        )
                    )
                )
            )
        ),
        isOnline = true,
        isDegraded = false
    )
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Cache management — empty",
    showBackground = true
)
@Composable
private fun CacheManagementSectionEmptyPreview() {
    CacheManagementSectionPreviewHost(
        state = CacheListingState.Empty,
        isOnline = true,
        isDegraded = false
    )
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Cache management — degraded + offline",
    showBackground = true
)
@Composable
private fun CacheManagementSectionDegradedPreview() {
    CacheManagementSectionPreviewHost(
        state = CacheListingState.Loading,
        isOnline = false,
        isDegraded = true
    )
}

/**
 * Stateless preview host that mirrors [CacheManagementSection]'s body
 * without requiring a real [SettingsViewModel]. The previews above route
 * their data through this; production code goes through [CacheManagementSection].
 *
 * §P5b-B (Q8): the host mirrors the new structure (no 已缓存的对话 row, no
 * clear-all dialog, no maxHeight, 3-level tree via [CacheGroupCard]).
 */
@Composable
private fun CacheManagementSectionPreviewHost(
    state: CacheListingState,
    isOnline: Boolean,
    isDegraded: Boolean,
) {
    if (isDegraded) {
        DegradedCacheWarning()
        Spacer(modifier = Modifier.height(Dimens.spacing3))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(Dimens.spacing4)) {
            Button(
                onClick = {},
                enabled = isOnline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(Dimens.iconXs))
                Spacer(modifier = Modifier.width(Dimens.spacing1))
                Text("Clear lost sessions")
            }

            Spacer(modifier = Modifier.height(Dimens.spacing3))

            when (state) {
                CacheListingState.Loading -> Text(
                    "Loading cached sessions…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CacheListingState.Empty -> Text(
                    "No cached sessions yet — chat sessions will be cached here automatically once you open them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is CacheListingState.Error -> Text(
                    "Failed to read cache: ${state.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                is CacheListingState.Loaded -> {
                    if (state.groups.isEmpty()) {
                        Text(
                            "No cached sessions yet — chat sessions will be cached here automatically once you open them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.groups.forEach { group ->
                            CacheGroupCard(
                                group = group,
                                isOnline = isOnline,
                                isCurrentServerGroup = group.serverGroupFp == "fp-aaa-111",
                                onClearSession = {},
                                onSweep = {}
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** Preview-only fixture builder — keeps the @Preview functions terse. */
private fun previewProfile(name: String) = cn.vectory.ocdroid.data.model.HostProfile(
    id = name,
    name = name,
    serverUrl = "http://$name.local:4096",
    serverGroupFp = "fp-$name"
)

private fun previewRow(
    fp: String,
    sid: String,
    workdir: String,
    newest: Long,
    verified: Long,
    exhaustedGap: Boolean = false
): CacheRepository.CachedSessionRow = CacheRepository.CachedSessionRow(
    serverGroupFp = fp,
    sessionId = sid,
    workdir = workdir,
    createdAt = newest,
    newestCachedAt = newest,
    lastVerifiedAt = verified,
    messageCount = 1,
    hasExhaustedGap = exhaustedGap
)
