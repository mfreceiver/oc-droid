package com.yage.opencode_client.ui.settings

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yage.opencode_client.R
import com.yage.opencode_client.ui.ConnectionState
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.ui.theme.opencode
import com.yage.opencode_client.util.DebugLog
import com.yage.opencode_client.util.ThemeMode
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
internal fun ConnectionProfileSection(
    profile: HostProfile,
    connectionState: ConnectionState,
    onManageProfiles: () -> Unit,
    hideHeader: Boolean = false
) {
    if (!hideHeader) {
        SectionHeader(title = stringResource(R.string.settings_connection_profile))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // A3: profile summary collapsed onto an M3 ListItem (leading host
            // icon, headline = display name, supporting = server URL, trailing
            // = live connection badge). The Card container and the
            // "Manage Connections" action below are intentionally kept.
            ListItem(
                leadingContent = {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                headlineContent = {
                    Text(
                        profile.displayName,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                supportingContent = {
                    Text(
                        profile.serverUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                },
                // Connected badge + version surfaces the live server status on
                // the right of the profile row. The Test Connection action lives
                // on the per-row icons inside Manage Connections now, so it is
                // intentionally not duplicated here.
                //
                // §R-17 Stage 3 follow-up: reads isConnected / serverVersion
                // from the connection-domain slice (ConnectionState) instead of
                // the whole-app AppState.
                trailingContent = {
                    if (connectionState.isConnected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.settings_connected),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            connectionState.serverVersion?.let { version ->
                                Text(
                                    " (v$version)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onManageProfiles,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_manage_profiles))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSection(
    themeMode: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit
) {
    SectionHeader(title = stringResource(R.string.settings_appearance))

    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelMedium)
    Spacer(modifier = Modifier.height(8.dp))
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = themeMode == mode,
                onClick = { onThemeSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = modes.size
                ),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    activeBorderColor = MaterialTheme.colorScheme.primary,
                    inactiveContainerColor = MaterialTheme.colorScheme.surface,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    inactiveBorderColor = MaterialTheme.colorScheme.outline
                )
            ) {
                Text(
                    when (mode) {
                        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                        ThemeMode.SYSTEM -> stringResource(R.string.settings_follow_system)
                    }
                )
            }
        }
    }
}

@Composable
internal fun TrafficSection(
    sent: Long,
    received: Long,
    onReset: () -> Unit,
    hideHeader: Boolean = false
) {
    if (!hideHeader) {
        SectionHeader(title = stringResource(R.string.settings_traffic))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Single-line summary: ↑ sent (upload) / ↓ received (download).
            // No Chinese labels and no total row — the two arrows + formatted
            // values are enough at a glance.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "↑ ${formatBytes(sent)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "↓ ${formatBytes(received)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_traffic_reset))
            }
        }
    }
}

/**
 * Formats [bytes] as a human-readable size. < 1KiB shows bytes; < 1MiB shows
 * KiB; < 1GiB shows MiB; otherwise GiB. Locale.US enforces ASCII digits and a
 * period decimal separator so the output is stable regardless of device locale.
 */
private fun formatBytes(bytes: Long): String {
    val unit = 1024L
    if (bytes < unit) return "$bytes B"
    val kb = bytes.toDouble() / unit
    if (kb < unit) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / unit
    if (mb < unit) return String.format(java.util.Locale.US, "%.1f MB", mb)
    val gb = mb / unit
    return String.format(java.util.Locale.US, "%.2f GB", gb)
}

/**
 * Danger zone: hard-reset ALL local data (open tabs, session cache, drafts,
 * current session/workdir, theme/font prefs, traffic stats) while preserving
 * server connection info and tunnel passwords, then trigger a reconnect +
 * server re-fetch via [com.yage.opencode_client.ui.MainViewModel.resetLocalDataAndResync].
 *
 * The button uses destructive (error-color) styling and gates the action
 * behind a confirmation [AlertDialog] that spells out exactly what is kept vs
 * cleared, since the wipe is irreversible.
 */
@Composable
internal fun DangerZoneSection(onClearLocalData: () -> Unit, hideHeader: Boolean = false) {
    var showConfirm by remember { mutableStateOf(false) }

    if (!hideHeader) {
        SectionHeader(title = stringResource(R.string.settings_danger_zone))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.settings_clear_local_data_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { showConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_clear_local_data))
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.settings_clear_local_data_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_clear_local_data_keeps),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_clear_local_data_clears),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_clear_local_data_irreversible),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onClearLocalData()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.settings_clear_local_data)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
internal fun AboutSection() {
    SectionHeader(title = stringResource(R.string.settings_about))

    Text(
        stringResource(R.string.app_name),
        style = MaterialTheme.typography.bodyLarge
    )
    Text(
        // BuildConfig.VERSION_NAME is generated at build time from
        // app/build.gradle.kts, so this always reflects the shipped version
        // without a hardcoded string to keep in sync.
        stringResource(R.string.settings_version, com.yage.opencode_client.BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
internal fun DebugLogSection(hideHeader: Boolean = false) {
    val oc = MaterialTheme.opencode
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

    if (!hideHeader) {
        SectionHeader(title = "调试日志")
    }

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
                    Text("调试日志", style = MaterialTheme.typography.titleMedium)
                    if (paused) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "（已暂停）",
                            style = MaterialTheme.typography.labelSmall,
                            color = oc.stateDangerFg
                        )
                    }
                }
                Text(countText, style = MaterialTheme.typography.labelSmall, color = oc.faint)
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
                LevelChip("全部", DebugLog.Level.DEBUG, minLevel) { minLevel = it }
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
                    Text(if (copied) "已复制" else "复制")
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
                    Text(if (paused) "继续" else "暂停")
                }

                OutlinedButton(
                    onClick = { DebugLog.clear() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清除")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Virtualized log view (LazyColumn) ──
            // Virtualization: only ~15 visible rows compose/recompose instead
            // of all entries (up to 1000), each of which called
            // SimpleDateFormat.format() on every recomposition. That was a
            // 10–50% CPU hotspot during high-frequency SSE streams while
            // Settings was open.
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
                            if (liveEntries.isEmpty()) "（暂无日志）" else "（当前过滤下无日志）",
                            color = oc.faint,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                items(items = filtered, key = { entry -> entry.seq }) { entry ->
                    val levelColor = when (entry.level) {
                        DebugLog.Level.DEBUG -> oc.faint
                        DebugLog.Level.INFO -> MaterialTheme.colorScheme.onSurface
                        DebugLog.Level.WARN -> oc.stateDangerFg
                        DebugLog.Level.ERROR -> oc.stateDangerFg
                    }
                    Text(
                        text = "[${sdf.format(entry.timeMs)}] ${entry.tag}/${entry.level}: ${entry.message}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
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
    val oc = MaterialTheme.opencode
    val isSelected = selected == level
    FilterChip(
        selected = isSelected,
        onClick = { onSelect(level) },
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.surface,
            selectedLabelColor = oc.accentText,
            selectedLeadingIconColor = oc.accentText,
            selectedTrailingIconColor = oc.accentText
        )
    )
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
internal fun SettingsSectionDivider() {
    Spacer(modifier = Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(32.dp))
}
