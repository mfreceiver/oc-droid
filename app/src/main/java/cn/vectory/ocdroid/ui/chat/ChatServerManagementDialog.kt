// ChatServerManagementDialog.kt — server management popup (host profile list,
// traffic counters, refresh, tunnel-activation, settings entry); traffic
// counters use shared FormatUtils.formatBytes. Pure relocation from
// ChatTopBar.kt with no behaviour change.

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.util.formatBytes
import cn.vectory.ocdroid.ui.theme.SemanticColors

@Composable
internal fun ServerManagementDialog(
    hostProfiles: List<HostProfile>,
    currentHostProfileId: String?,
    tunnelActivationState: TunnelActivationState,
    showTunnelAuth: Boolean,
    trafficSent: Long,
    trafficReceived: Long,
    serverVersion: String?,
    onSelectHost: (String) -> Unit,
    onRefresh: () -> Unit,
    onActivateTunnel: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // --- Host profiles ---
                if (hostProfiles.isEmpty()) {
                    Text(
                        stringResource(R.string.server_dialog_no_hosts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    hostProfiles.forEach { profile ->
                        val isSelected = profile.id == currentHostProfileId
                        if (isSelected) {
                            // Current host: non-clickable display only
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = profile.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        serverVersion?.let { version ->
                                            Text(
                                                text = "v$version",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = SemanticColors.stateSuccessFg()
                                    )
                                }
                            }
                        } else {
                            // Other hosts: tappable to switch
                            Surface(
                                onClick = { onSelectHost(profile.id) },
                                shape = MaterialTheme.shapes.medium,
                                color = Color.Transparent,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    // Version is shown ONLY on the selected (current/connected)
                                    // host row — it's a single global value for the connected
                                    // server, so rendering it under non-current profiles would
                                    // be misleading (would show the connected host's version
                                    // for hosts we haven't probed).
                                }
                            }
                        }
                    }
                }

                // --- Traffic statistics ---
                // #4a: the two traffic counters are centered horizontally
                // within the dialog (16dp spacing kept), so the ↑/↓ row reads
                // as a balanced pair instead of left-aligned.
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = "↑ ${formatBytes(trafficSent)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "↓ ${formatBytes(trafficReceived)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // --- Action icon row: Settings / Refresh / Tunnel ---
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.server_dialog_system_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.chat_action_refresh_messages),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (showTunnelAuth) {
                        val isActivating = tunnelActivationState is TunnelActivationState.Loading
                        IconButton(
                            onClick = onActivateTunnel,
                            enabled = !isActivating
                        ) {
                            if (isActivating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Icon(
                                    Icons.Default.VpnKey,
                                    contentDescription = stringResource(R.string.server_dialog_activate_tunnel),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        // No confirm or dismiss buttons — tap scrim to dismiss
        confirmButton = {}
    )
}

