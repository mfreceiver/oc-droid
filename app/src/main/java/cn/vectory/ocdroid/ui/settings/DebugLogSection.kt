// DebugLogSection.kt — the in-Settings debug-log viewer (level filter, pause,
// copy, clear, non-virtualized Column + SelectionContainer for cross-line selection)
// plus its private LevelChip helper.
//
// §grouping-rewrite 项 2: the two R-19 Sprint 1 Lane D diagnostic panels
// (EffectBusDroppedPanel + SseUnknownEventsPanel) and their Hilt @EntryPoint
// plumbing have been removed — the stats they surfaced migrated to the
// Connections section (ConnectionProfileSection's group-stats line). Only the
// log viewer body remains, kept in the same package (ui.settings) so the
// existing internal visibility is sufficient.

package cn.vectory.ocdroid.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import cn.vectory.ocdroid.ui.theme.Dimens
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
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

    // §streaming-state-sync-diag (release-enabling): the verbose-diag toggle.
    // Reads/writes SettingsManager.debugLogVerboseEnabled (ESP-persisted) AND
    // mirrors into DebugLog.verboseDiagEnabled so the change takes effect
    // immediately (the call sites read the runtime flag on every event).
    // Default OFF — release users get zero log noise / perf cost.
    val settingsManager = rememberDebugVerboseSettingsManager()
    var verboseEnabled by remember { mutableStateOf(settingsManager.debugLogVerboseEnabled) }

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

    if (!hideHeader) {
        AppSectionHeader(text = stringResource(R.string.debug_log_title))
    }

    // §streaming-state-sync-diag (release-enabling): the verbose-diag toggle.
    // M3 ListItem + Switch per ui-style-spec §2 (no scattered dp; ListItem
    // self-pads horizontal 16dp so it shares one keyline with the Card below
    // + AppSectionHeader above). Whole row is tappable (mirrors the
    // persistent-notification row pattern in SettingsNotificationsRoute).
    // Default OFF; on toggle, persist to ESP AND set DebugLog.verboseDiagEnabled
    // for immediate effect (no restart).
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(R.string.settings_debug_verbose_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_debug_verbose_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(
                checked = verboseEnabled,
                onCheckedChange = { next ->
                    settingsManager.debugLogVerboseEnabled = next
                    DebugLog.verboseDiagEnabled = next
                    verboseEnabled = next
                },
            )
        },
        modifier = Modifier.clickable {
            val next = !verboseEnabled
            settingsManager.debugLogVerboseEnabled = next
            DebugLog.verboseDiagEnabled = next
            verboseEnabled = next
        },
    )

    // §debug-card-identity: toggle for the in-chat debug card identity overlay.
    var cardIdentityEnabled by remember { mutableStateOf(settingsManager.debugCardIdentityEnabled) }
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(R.string.settings_debug_card_identity_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_debug_card_identity_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(
                checked = cardIdentityEnabled,
                onCheckedChange = { next ->
                    settingsManager.debugCardIdentityEnabled = next
                    cardIdentityEnabled = next
                },
            )
        },
        modifier = Modifier.clickable {
            val next = !cardIdentityEnabled
            settingsManager.debugCardIdentityEnabled = next
            cardIdentityEnabled = next
        },
    )

    // §review-AB: Card self-pads horizontal 16dp (route Column no longer pads)
    // so it shares one keyline with AppSectionHeader + ListItem.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spacing4),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
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
            // v2 accent token so the active state reads cleanly on surfaceContainer.
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

            // ── Non-virtualized log view (Column + SelectionContainer) ──
            // Replaces the prior LazyColumn to support cross-line text selection.
            // SelectionContainer + LazyColumn is known-unstable across screen
            // boundaries (only materialized items are selectable, selection is lost
            // on scroll), so we sacrifice virtualization in favor of usable
            // multi-line selection. The ring-buffer cap (MAX_ENTRIES = 3000)
            // keeps composition cost acceptable.
            //
            // §stable-seq-key: each Text is wrapped in `key(entry.seq)` so
            // Compose matches nodes by the Entry's monotonic sequence number,
            // NOT by position. This is mandatory because the log is newest-first
            // (DebugLog.log addFirst to index 0): every new entry shifts all
            // existing entries down one slot, so a position-only identity would
            // cause Text instances (and their SelectionContainer selectable
            // registrations) to be reused across different log lines on every
            // recomposition — corrupting the active selection. `seq` is the
            // stable, collision-free key designed for exactly this (see
            // DebugLog.Entry KDoc). Note: `key` stabilizes node IDENTITY so the
            // active selection survives appends (no mis-assignment / clearing),
            // but it does NOT skip recomposition — because the list is
            // newest-first, inserting a row still shifts all following entries,
            // and Compose recomposes shifted keyed nodes. The per-row text is
            // therefore cached (see §row-text-cache) to make that unavoidable
            // recomposition cheap.
            //
            // §platform-limit-autoscroll: the Compose Foundation LIBRARY
            // (androidx.compose.foundation:foundation) at the version pinned by
            // composeBom 2025.12.00 does NOT auto-scroll the viewport when a
            // drag selection extends beyond the visible bounds. That capability
            // (auto-scroll-on-drag-beyond-viewport) is introduced in Compose
            // Foundation 1.12.0-alpha02 — this is a library version, unrelated
            // to the Android API level. Cross-line selection is fully usable
            // within the viewport; to extend a selection past the edge the user
            // must scroll manually (then continue dragging). This is an
            // accepted platform trade-off (user decision: keep cross-line
            // selection), not a bug.
            //
            // §scroll-safety: the .heightIn(max = 360.dp) below is LOAD-BEARING.
            // This Column is hosted inside SettingsScreen's
            // Column(Modifier.verticalScroll(...)), whose children receive an
            // infinite max-height constraint. Without this cap the Column would
            // grow unbounded. Do NOT remove it.
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (filtered.isEmpty()) {
                        Text(
                            if (liveEntries.isEmpty()) stringResource(R.string.debug_log_empty) else stringResource(R.string.debug_log_empty_filtered),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    // §row-text-cache: bounded cache of formatted row text,
                    // keyed by entry.seq in a TreeMap.
                    //
                    // WHY CACHE: the log is newest-first (§stable-seq-key), so
                    // every append shifts all following entries and forces their
                    // recomposition — without a cache that re-runs sdf.format +
                    // string-template for up to MAX_ENTRIES rows each time. seq is
                    // monotonic and never reused (DebugLog's AtomicLong), so a seq
                    // key is stable: an entry formatted once is reused on every
                    // subsequent recomposition. The cache has NO remember-key — a
                    // key on `filtered`/`displayed` would rebuild the whole map on
                    // every append and destroy the benefit (per fixup2 r2).
                    //
                    // WHY TREEMAP (not LinkedHashMap/LRU): the viewer iterates
                    // in the deque's publish order (typically newest-first). Under
                    // that access pattern an access-order LRU's "eldest" is the
                    // LARGEST seq (the entry we just touched), so an LRU evicts the
                    // wrong end and a full-capacity append cascades into a full
                    // re-format (fixup2 r4 regression). A seq-keyed TreeMap makes
                    // eviction ORDER-INDEPENDENT: firstKey() is always the smallest
                    // seq, which on the common path (seq published in allocation
                    // order) is the entry the ring buffer drops next.
                    //
                    // Concurrency caveat (cache is a perf optimization, NOT a
                    // correctness mechanism — see BOUND for the hard guarantee):
                    // DebugLog.log allocates seq BEFORE the deque lock, so under
                    // concurrent logging a later-published entry can carry a
                    // smaller seq than an already-published one. A small-seq entry
                    // still in the visible window can then be the cache's smallest
                    // key and get evicted (pollFirstEntry) before the data it
                    // represents leaves the ring — causing that entry to miss again
                    // on later recompositions until it ages out of the ring. This
                    // degrades hit-rate but never breaks the cap or corrupts text
                    // (every miss just re-formats). In practice the app's log
                    // sources publish near-sequentially, so this is rare; the bound
                    // and correctness hold in ALL states regardless.
                    //
                    // NO CASCADE on the hot path (running append at full capacity,
                    // common seq order): ring holds seqs [N-2999 .. N]; append N+1
                    // evicts N-2999 from the ring. forEach visits N+1 (miss →
                    // insert → size>cap → pollFirstEntry evicts N-2999, which is no
                    // longer in `filtered` so it is not revisited), then N, N-1, …
                    // all HIT. Net: 1 format + 1 eviction per append. (LRU evicted
                    // N instead → cascade.)
                    //
                    // PAUSED: forEach iterates the frozen snapshot, whose seqs were
                    // typically all cached before pause → HIT, zero inserts/
                    // evictions, so background appends usually do not perturb the
                    // cache while paused. (Edge cases that CAN cause misses while
                    // paused: a pause followed by a level-filter change that exposes
                    // rows never previously formatted, or the concurrency caveat
                    // above. Each miss just inserts+evicts; the cap still holds.)
                    //
                    // BOUND: eviction runs only on the new-seq miss path, removing
                    // the single smallest seq when size > MAX_ENTRIES. The map is
                    // therefore structurally capped at DebugLog.MAX_ENTRIES in ALL
                    // states (running / paused / post-clear) — the cap is the ring
                    // buffer's own capacity (single source of truth, DebugLog.kt).
                    //
                    // levelColor is NOT cached — it reads MaterialTheme.colorScheme,
                    // so it stays recomposed per row.
                    val rowTextCache = remember { java.util.TreeMap<Long, String>() }
                    filtered.forEach { entry ->
                        val levelColor = when (entry.level) {
                            DebugLog.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                            DebugLog.Level.INFO -> MaterialTheme.colorScheme.onSurface
                            DebugLog.Level.WARN -> MaterialTheme.colorScheme.error
                            DebugLog.Level.ERROR -> MaterialTheme.colorScheme.error
                        }
                        // §stable-seq-key (see comment above): wrap in key() so
                        // node identity follows the Entry, not the list index.
                        key(entry.seq) {
                            // §row-text-cache access: hit → reuse; miss → format
                            // and, if over cap, evict the smallest seq (the entry
                            // the ring buffer has dropped next on the common seq
                            // path — see §row-text-cache caveat above). getOrPut
                            // alone doesn't bound a TreeMap, so the cap check runs
                            // only on the miss path.
                            val cached = rowTextCache[entry.seq]
                            val text = cached ?: run {
                                val formatted = "[${sdf.format(entry.timeMs)}] ${entry.tag}/${entry.level}: ${entry.message}"
                                rowTextCache[entry.seq] = formatted
                                if (rowTextCache.size > DebugLog.MAX_ENTRIES) {
                                    rowTextCache.pollFirstEntry()
                                }
                                formatted
                            }
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = BundledMonoFamily,
                                color = levelColor
                            )
                        }
                    }
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

/**
 * §streaming-state-sync-diag (release-enabling): Hilt EntryPoint that exposes
 * the application-wide [SettingsManager] to [DebugLogSection] without threading
 * a new parameter through AppShell / SettingsAboutRoute. Mirrors the
 * [NotificationsSettingsManagerEntryPoint] pattern in SettingsScreen.kt.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugVerboseSettingsManagerEntryPoint {
    fun settingsManager(): SettingsManager
}

/**
 * Resolve the application-wide [SettingsManager] via Hilt EntryPoint.
 * Cached on the application Context (stable across recompositions).
 */
@Composable
private fun rememberDebugVerboseSettingsManager(): SettingsManager {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DebugVerboseSettingsManagerEntryPoint::class.java,
        ).settingsManager()
    }
}
