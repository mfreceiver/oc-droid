package com.yage.opencode_client.ui.settings

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.ui.ConnectionState
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.ui.util.formatBytes
import com.yage.opencode_client.util.ThemeMode

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
