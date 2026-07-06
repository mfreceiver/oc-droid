// DebugLogSection.kt — the in-Settings debug-log viewer (level filter, pause,
// copy, clear, virtualized LazyColumn) plus its private LevelChip helper.
// Pure relocation from SettingsSections.kt with no behaviour change; kept in
// the same package (ui.settings) so the existing internal visibility is
// sufficient and no public-API churn is needed.

package cn.vectory.ocdroid.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.ui.AppCore
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator
import cn.vectory.ocdroid.util.DebugLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
internal fun DebugLogSection(hideHeader: Boolean = false) {
    val liveEntries by DebugLog.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    // A — Level filter. Default INFO+ hides the per-token DEBUG spam so the
    // viewer surfaces decisions / lifecycle / failures instead of SSE noise.
    var minLevel by remember { mutableStateOf(DebugLog.Level.INFO) }

    // B — Pause freezes a snapshot so the list stops jumping while the user
    // scrolls/copies. `frozen` is only read while paused.
    var paused by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<List<DebugLog.Entry>>(emptyList()) }

    val displayed: List<DebugLog.Entry> = if (paused) frozen else liveEntries
    val filtered = remember(displayed, minLevel) {
        displayed.filter { it.level.ordinal >= minLevel.ordinal }
    }

    // Reset the "已复制" indicator after 1.5 s.
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    // ── Diagnostic panels (R-19 Sprint 1 Lane D): observability for the
    // Effect Bus drop counter + SSE unknown-event counters. Read-only.
    //
    // §dependency-injection: this Composable lives outside any
    // @HiltViewModel scope (DebugLogSection takes no VM). Rather than
    // threading new params through SettingsScreen + every caller, we resolve
    // the app singletons via a Hilt @EntryPoint — the same pattern Theme.kt
    // uses for SettingsManager. SharedEffectBus + AppCore are both
    // @Singleton @Inject, so Hilt auto-provides them.
    val diagnostics = rememberDebugDiagnostics()

    // §polling: the underlying classes expose imperative snapshot getters
    // (AtomicLong / ConcurrentHashMap), not Flows. A 1 s poll keeps the UI
    // fresh without per-event recompositions (these counters move slowly —
    // a drop or unknown event is an anomaly, not per-token noise).
    var droppedEffectCount by remember { mutableStateOf(0L) }
    var unknownEventCounts by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(diagnostics) {
        while (true) {
            droppedEffectCount = diagnostics.effectBus.droppedEffectCount()
            unknownEventCounts = diagnostics.coordinator.unknownEventCountsSnapshot()
            delay(1000)
        }
    }

    if (!hideHeader) {
        SectionHeader(title = stringResource(R.string.debug_log_title))
    }

    EffectBusDroppedPanel(droppedEffectCount)
    Spacer(modifier = Modifier.height(12.dp))
    SseUnknownEventsPanel(unknownEventCounts)
    Spacer(modifier = Modifier.height(12.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header row: title + count badge ──
            // Show `${filtered.size}/${entries.size} 条` when a filter is
            // active so the user understands how much is hidden; collapse to
            // a plain total otherwise to stay visually quiet.
            val countText = if (filtered.size == liveEntries.size) {
                "${liveEntries.size} 条"
            } else {
                "${filtered.size}/${liveEntries.size} 条"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.debug_log_title), style = MaterialTheme.typography.titleMedium)
                    if (paused) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.debug_log_paused_suffix),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(countText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Level filter chips ──
            // 全部 (DEBUG threshold) / INFO+ / WARN+. Selected chip uses the
            // v2 accent token so the active state reads cleanly on surfaceVariant.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LevelChip(stringResource(R.string.debug_log_level_all), DebugLog.Level.DEBUG, minLevel) { minLevel = it }
                LevelChip("INFO+", DebugLog.Level.INFO, minLevel) { minLevel = it }
                LevelChip("WARN+", DebugLog.Level.WARN, minLevel) { minLevel = it }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Action buttons: 复制 / 暂停 / 清除 ──
            // 复制 serializes the currently DISPLAYED + FILTERED list, so when
            // paused+filtered the clipboard matches exactly what the user sees.
            val sdf = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val text = filtered.joinToString("\n") { e ->
                            "[${sdf.format(e.timeMs)}] ${e.tag}/${e.level}: ${e.message}"
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("debug log", text))
                        copied = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (copied) stringResource(R.string.debug_log_copied) else stringResource(R.string.debug_log_copy))
                }

                OutlinedButton(
                    onClick = {
                        if (paused) {
                            paused = false
                        } else {
                            frozen = liveEntries
                            paused = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (paused) stringResource(R.string.debug_log_resume) else stringResource(R.string.debug_log_pause))
                }

                OutlinedButton(
                    onClick = { DebugLog.clear() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.debug_log_clear))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Virtualized log view (LazyColumn) ──
            // Virtualization: only ~15 visible rows compose/recompose instead
            // of all entries (up to 1000), each of which called
            // SimpleDateFormat.format() on every recomposition. That was a
            // 10–50% CPU hotspot during high-frequency SSE streams while
            // Settings was open.
            //
            // §scroll-safety: the .heightIn(max = 360.dp) below is LOAD-BEARING.
            // This LazyColumn is hosted inside SettingsScreen's
            // Column(Modifier.verticalScroll(...)), whose children receive an
            // infinite max-height constraint. Without this cap the LazyColumn
            // would crash with "Vertically scrollable component was measured
            // with an infinity maximum height constraints". Do NOT remove it.
            val logListState = rememberLazyListState()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                state = logListState
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (liveEntries.isEmpty()) stringResource(R.string.debug_log_empty) else stringResource(R.string.debug_log_empty_filtered),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                items(items = filtered, key = { entry -> entry.seq }) { entry ->
                    val levelColor = when (entry.level) {
                        DebugLog.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                        DebugLog.Level.INFO -> MaterialTheme.colorScheme.onSurface
                        DebugLog.Level.WARN -> MaterialTheme.colorScheme.error
                        DebugLog.Level.ERROR -> MaterialTheme.colorScheme.error
                    }
                    Text(
                        text = "[${sdf.format(entry.timeMs)}] ${entry.tag}/${entry.level}: ${entry.message}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = BundledMonoFamily,
                        color = levelColor
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LevelChip(
    label: String,
    level: DebugLog.Level,
    selected: DebugLog.Level,
    onSelect: (DebugLog.Level) -> Unit
) {
    val isSelected = selected == level
    FilterChip(
        selected = isSelected,
        onClick = { onSelect(level) },
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.surface,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
            selectedTrailingIconColor = MaterialTheme.colorScheme.primary
        )
    )
}

// ── R-19 Sprint 1 Lane D: diagnostic panels (read-only observability) ────

/**
 * Holds the two diagnostic sources the panels read. Resolved once per Activity
 * via [rememberDebugDiagnostics] (the underlying singletons outlive any single
 * Composition).
 */
private data class DebugDiagnostics(
    val effectBus: SharedEffectBus,
    val coordinator: SessionSyncCoordinator
)

/**
 * R-19: Hilt EntryPoint that exposes the app singletons needed by the
 * diagnostic panels. [SharedEffectBus] is `@Singleton @Inject constructor()`,
 * and [AppCore] (which owns the [SessionSyncCoordinator] as an `internal val`)
 * is also `@Singleton @Inject`. This mirrors the [SettingsManagerEntryPoint]
 * pattern in Theme.kt for Composables that live outside any @HiltViewModel
 * scope.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugDiagnosticsEntryPoint {
    fun effectBus(): SharedEffectBus
    fun appCore(): AppCore
}

/**
 * Resolves the diagnostic singletons from the Hilt-attached Application.
 * Cached with `remember(context)` so the entry-point lookup runs once per
 * Activity (the Application context is stable across recompositions).
 */
@Composable
private fun rememberDebugDiagnostics(): DebugDiagnostics {
    val context = LocalContext.current
    return remember(context) {
        val ep = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DebugDiagnosticsEntryPoint::class.java
        )
        DebugDiagnostics(
            effectBus = ep.effectBus(),
            // sessionSyncCoordinator is internal on AppCore; accessible here
            // because DebugLogSection lives in the same Gradle module.
            coordinator = ep.appCore().sessionSyncCoordinator
        )
    }
}

/**
 * R-19 Lane D: shows the total count of [ControllerEffect]s dropped by
 * [SharedEffectBus.tryEmitEffect] since process start. A non-zero value means
 * the SUSPEND buffer (256) filled under burst — should never happen in normal
 * operation, so any non-zero count is rendered in [error] colour.
 */
@Composable
private fun EffectBusDroppedPanel(droppedCount: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.debug_effect_bus_dropped_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                droppedCount.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = if (droppedCount > 0L) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * R-19 Lane D: shows the per-type counts of SSE events that fell through
 * [SessionSyncCoordinator.dispatchSseEvent]'s else branch (unrecognized types,
 * excluding the known-intentional NOISY_SSE_LOG_EVENTS skips). The header row
 * shows the running total; the body lists each type → count (sorted by count
 * descending) in monospace so the columns align. Empty → "无" / "None" in a
 * muted style. A non-zero total is rendered in [error] colour.
 */
@Composable
private fun SseUnknownEventsPanel(counts: Map<String, Int>) {
    val total = counts.values.sum()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.debug_sse_unknown_events_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    total.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (total > 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (counts.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.debug_diagnostics_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                // Sort by count desc so the most frequent unknown type surfaces
                // first (a recurring unknown type is a higher-priority signal
                // than a one-off).
                val sorted = counts.entries.sortedByDescending { it.value }
                sorted.forEach { (type, count) ->
                    Text(
                        text = "$type  ·  $count",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = BundledMonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
