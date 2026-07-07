// CacheManagementSection.kt — R-20 Phase 4 (plan §3) storage-management UI.
//
// Lists every cached chat session (grouped by serverGroupFp), surfaces the
// degraded-cache warning + offline-sweep hint, and exposes the manual
// actions (clear session / clear project / sweep now / clear all / copy-on-split).
//
// The Composable resolves [CacheRepository] + [CacheMaintenanceCoordinator]
// via a Hilt @EntryPoint (mirrors DebugLogSection's pattern for Composables
// that live outside any @HiltViewModel scope), but mutations are routed
// through the [SettingsViewModel] callbacks so SettingsViewModelTest can
// verify each action reaches the underlying Repository/Coordinator.

package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.ui.CacheGroupListing
import cn.vectory.ocdroid.ui.CacheListingState
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * R-20 Phase 4 (plan §3): the cache-management section. Renders one card per
 * `server_group_fp` (with each profile still pointing at it for the copy-on-
 * split row action), each card listing its cached sessions (newest-first).
 *
 * The section is hosted inside SettingsScreen's verticalScroll Column; the
 * per-group LazyColumn is capped at 320.dp so it does not crash against the
 * infinite max-height constraint from the outer scroll (same load-bearing
 * cap DebugLogSection uses).
 *
 * Mutations are routed through [vm] (SettingsViewModel owns the action
 * methods); the @EntryPoint here exists only so future read-only polling
 * helpers can reach [CacheRepository] without a VM round-trip — Phase 4
 * does not need this yet, but the entry point is cheap to declare and
 * matches the DebugLogSection precedent.
 *
 * @param vm the Settings-domain VM (owns the cache listing state + the
 *   mutation methods).
 * @param hideHeader when true, the section omits its own [SectionHeader]
 *   (used when the section is grouped under a shared header in
 *   SettingsScreen — same pattern as ConnectionProfileSection).
 */
@Composable
internal fun CacheManagementSection(
    vm: SettingsViewModel,
    hideHeader: Boolean = false,
) {
    if (!hideHeader) {
        SectionHeader(title = stringResource(R.string.settings_section_cache))
    }

    // ── Initial + on-enter refresh: the listing is empty until the first
    // refreshCacheListing() round-trip lands. Idempotent — safe to call on
    // every recomposition because refreshCacheListing is itself idempotent
    // (it overwrites the StateFlow rather than accumulating). The
    // LaunchedEffect(Unit) ensures it fires ONCE per Settings screen entry.
    LaunchedEffect(Unit) { vm.refreshCacheListing() }

    val listing by vm.cacheListing.collectAsStateWithLifecycle()
    val lastSweep by vm.lastSweep.collectAsStateWithLifecycle()
    val isOnline = vm.isOnline
    val isDegraded = vm.isCacheDegraded

    // ── Degraded-cache warning (dser I-1 / plan §3): the persistent store
    // could not be opened and the cache fell back to an in-memory substitute.
    // Surfacing this here is the explicit Phase 4 deliverable for the flag.
    if (isDegraded) {
        DegradedCacheWarning()
        Spacer(modifier = Modifier.height(12.dp))
    }

    // ── Clear-all (the only cache-busting action that crosses server-group
    // boundaries) is gated behind a confirmation dialog — same destructive-
    // action pattern DangerZoneSection uses.
    var showClearAllConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── §grouping-rewrite 项 3: "Sweep all groups" — the popup's
            // primary action. Fanned out across every distinct fp via
            // [SettingsViewModel.sweepAllGroups]; disabled offline for the
            // same reason the per-group sweep button is (a sweep without a
            // live connection cannot enumerate the alive set).
            Button(
                onClick = { vm.sweepAllGroups() },
                enabled = isOnline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.cache_management_sweep_all))
            }

            if (!isOnline) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.cache_management_offline_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.cache_management_title),
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedButton(
                    onClick = { showClearAllConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.cache_management_action_clear_all))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                        // §scroll-safety: the per-group LazyColumn lives
                        // inside SettingsScreen's verticalScroll Column
                        // (infinite max-height constraint). Without the
                        // heightIn cap it would crash the same way an
                        // uncapped DebugLogSection LazyColumn would.
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                        ) {
                            items(
                                items = state.groups,
                                key = { group -> group.serverGroupFp }
                            ) { group ->
                                CacheGroupCard(
                                    group = group,
                                    isOnline = isOnline,
                                    onClearSession = { sid ->
                                        vm.clearSession(group.serverGroupFp, sid)
                                    },
                                    onClearProject = { workdir ->
                                        vm.clearProject(group.serverGroupFp, workdir)
                                    },
                                    onSweep = { vm.sweepNow(group.serverGroupFp) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            // ── Last sweep outcome (surfaces what got evicted / marked).
            lastSweep?.let { report ->
                Spacer(modifier = Modifier.height(8.dp))
                SweepResultLine(report)
            }
        }
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(stringResource(R.string.cache_management_clear_all_title)) },
            text = {
                Text(
                    stringResource(R.string.cache_management_clear_all_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearAllConfirm = false
                        vm.clearAll()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.cache_management_clear_all_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

// ─────────── Per-group card ─────────────────────────────────────────────

/**
 * One server-group's section: header (fp + member counts) + sweep
 * row actions + the cached-session list. Each row carries its own clear-
 * session + clear-project buttons.
 */
@Composable
private fun CacheGroupCard(
    group: CacheGroupListing,
    isOnline: Boolean,
    onClearSession: (String) -> Unit,
    onClearProject: (String) -> Unit,
    onSweep: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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

        Spacer(modifier = Modifier.height(8.dp))

        // ── Group-level action: sweep now (offline-disabled).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSweep,
                enabled = isOnline,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.cache_management_action_sweep))
            }
        }

        if (!isOnline) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.cache_management_offline_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Per-session rows.
        group.sessions.forEach { row ->
            CachedSessionRowItem(
                row = row,
                onClearSession = { onClearSession(row.sessionId) },
                onClearProject = { onClearProject(row.workdir) }
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

// ─────────── Per-session row ────────────────────────────────────────────

/**
 * One cached-session row. Renders workdir + sessionId abbrev + the three
 * timestamps + clear-session / clear-project buttons. Marks rows red when
 * lastVerifiedAt > 7d ("疑似废弃") + flags exhausted gaps.
 *
 * Pure presentation only — the threshold logic lives in
 * [CacheRowPresentation.isSuspectAbandoned] (testable).
 */
@Composable
private fun CachedSessionRowItem(
    row: CacheRepository.CachedSessionRow,
    onClearSession: () -> Unit,
    onClearProject: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    val suspect = remember(row.messageCount, row.lastVerifiedAt, now) {
        CacheRowPresentation.isSuspectAbandoned(row.messageCount, row.lastVerifiedAt, now)
    }
    val exhausted = row.hasExhaustedGap
    val rowColor = if (suspect) MaterialTheme.colorScheme.error
                   else MaterialTheme.colorScheme.onSurface

    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val createdAtText = row.createdAt?.let { dateFormatter.format(Date(it)) }
        ?: stringResource(R.string.cache_management_unknown_time)
    val newestText = dateFormatter.format(Date(row.newestCachedAt))
    val verifiedText = if (row.lastVerifiedAt <= 0L) {
        stringResource(R.string.cache_management_never_verified)
    } else {
        dateFormatter.format(Date(row.lastVerifiedAt))
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            stringResource(
                R.string.cache_management_row_workdir,
                row.workdir.ifBlank { stringResource(R.string.cache_management_row_workdir_unknown) }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = rowColor
        )
        Text(
            stringResource(R.string.cache_management_row_session_id, abbreviateSessionId(row.sessionId)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = BundledMonoFamily
        )
        Text(
            stringResource(R.string.cache_management_row_created_at, createdAtText),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

        if (suspect) {
            Text(
                stringResource(R.string.cache_management_row_suspect),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (exhausted) {
            Text(
                stringResource(R.string.cache_management_row_exhausted),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onClearSession,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.cache_management_action_clear_session))
            }
            OutlinedButton(
                onClick = onClearProject,
                enabled = row.workdir.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FolderOff, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.cache_management_action_clear_project))
            }
        }
    }
}

// ─────────── Auxiliary presentational helpers ───────────────────────────

@Composable
private fun DegradedCacheWarning() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                stringResource(R.string.cache_management_degraded_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SweepResultLine(report: cn.vectory.ocdroid.data.cache.DailySweepReport) {
    val text = when (report.completeness) {
        cn.vectory.ocdroid.data.cache.AliveCompleteness.Complete ->
            stringResource(
                R.string.cache_management_sweep_result_complete,
                report.verifiedAliveCount,
                report.evictedSessionIds.size
            )
        cn.vectory.ocdroid.data.cache.AliveCompleteness.Incomplete ->
            stringResource(
                R.string.cache_management_sweep_result_incomplete,
                report.verifiedAliveCount
            )
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Trim a `ses_xxxx…` branded session id to its first 12 chars for display
 * (the full id is shown via long-press in a future iteration; for now the
 * abbreviation is enough to distinguish rows in the same workdir).
 */
internal fun abbreviateSessionId(sessionId: String): String =
    if (sessionId.length <= 12) sessionId else sessionId.substring(0, 12) + "…"

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
     * Group a flat session list by `serverGroupFp`, preserving the order
     * each fp first appears in. Used by the section to render one card per
     * group without relying on a Map iteration order the caller cannot
     * predict.
     */
    fun groupByFp(
        rows: List<CacheRepository.CachedSessionRow>
    ): List<List<CacheRepository.CachedSessionRow>> {
        val order = LinkedHashSet<String>()
        val byFp = LinkedHashMap<String, MutableList<CacheRepository.CachedSessionRow>>()
        for (row in rows) {
            order.add(row.serverGroupFp)
            byFp.getOrPut(row.serverGroupFp) { mutableListOf() }.add(row)
        }
        return order.map { fp -> byFp.getValue(fp) }
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
        lastSweep = null,
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
        lastSweep = null,
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
        lastSweep = null,
        isOnline = false,
        isDegraded = true
    )
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Cache management — single suspect row",
    showBackground = true
)
@Composable
private fun CacheManagementSectionSuspectPreview() {
    CacheManagementSectionPreviewHost(
        state = CacheListingState.Loaded(
            groups = listOf(
                CacheGroupListing(
                    serverGroupFp = "fp-suspect",
                    profiles = listOf(previewProfile("only")),
                    sessions = listOf(
                        previewRow(
                            fp = "fp-suspect",
                            sid = "ses_old",
                            workdir = "/home/me/old",
                            newest = System.currentTimeMillis() - 60 * 24 * 3_600_000L,
                            verified = System.currentTimeMillis() - 12 * 24 * 3_600_000L
                        )
                    )
                )
            )
        ),
        lastSweep = cn.vectory.ocdroid.data.cache.DailySweepReport(
            serverGroupFp = "fp-suspect",
            completeness = cn.vectory.ocdroid.data.cache.AliveCompleteness.Complete,
            verifiedAliveCount = 0,
            evictedSessionIds = listOf("ses_old"),
            suspiciousSessionIds = emptyList()
        ),
        isOnline = true,
        isDegraded = false
    )
}

/**
 * Stateless preview host that mirrors [CacheManagementSection]'s body
 * without requiring a real [SettingsViewModel]. The previews above route
 * their data through this; production code goes through [CacheManagementSection].
 *
 * The render is identical because the body only reads:
 *  - cacheListing / lastSweep StateFlows (preview substitutes static values)
 *  - isOnline / isDegraded booleans
 *  - 5 mutation callbacks (preview passes no-ops)
 */
@Composable
private fun CacheManagementSectionPreviewHost(
    state: CacheListingState,
    lastSweep: cn.vectory.ocdroid.data.cache.DailySweepReport?,
    isOnline: Boolean,
    isDegraded: Boolean,
) {
    if (isDegraded) {
        DegradedCacheWarning()
        Spacer(modifier = Modifier.height(12.dp))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // §grouping-rewrite 项 3: primary "Sweep all groups" action
            // (disabled in the preview host — the host has no VM to invoke).
            Button(
                onClick = {},
                enabled = isOnline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sweep all groups")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Cached chat sessions",
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedButton(onClick = {}, enabled = false) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear all cached sessions")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                        ) {
                            items(
                                items = state.groups,
                                key = { group -> group.serverGroupFp }
                            ) { group ->
                                CacheGroupCard(
                                    group = group,
                                    isOnline = isOnline,
                                    onClearSession = {},
                                    onClearProject = {},
                                    onSweep = {}
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            lastSweep?.let { report ->
                Spacer(modifier = Modifier.height(8.dp))
                SweepResultLine(report)
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
