// ChatServerManagementDialog.kt — server management popup (single host
// profile display, refresh, settings entry). §L8: collapsed from multi-host
// list to single-host display; host-switch UI removed.

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.theme.AppFormDialog
import cn.vectory.ocdroid.ui.theme.Dimens

@Composable
internal fun ServerManagementDialog(
    currentHost: HostProfile?,
    serverVersion: String?,
    onRefresh: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    // §L8: migrated from raw AlertDialog to AppFormDialog (Tier C). The dialog
    // is a read-only status display (host profile + action icons), with no
    // blocking decision — scrim/back dismisses. AppFormDialog normalises the
    // shape, scroll-pinning, and spacing tokens.
    AppFormDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.server_dialog_title),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.spacing1)
        ) {
            // --- Host profile ---
            if (currentHost == null) {
                Text(
                    stringResource(R.string.server_dialog_no_hosts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Surface(
                    // RectangleShape removed — let AppFormDialog own the dialog shape
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Dimens.spacing3),  // 12.dp → Dimens.spacing3
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentHost.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        serverVersion?.let { version ->
                            Text(
                                text = "v$version",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // --- Action icon row: Settings / Refresh ---
            Spacer(modifier = Modifier.height(Dimens.spacing1))  // 4.dp → Dimens.spacing1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // §dialog-dismiss-race-fix (2026-07-26): previously
                // `onNavigateToSettings(); onDismiss()` — the synchronous
                // onDismiss() destroyed the AlertDialog popup window on
                // the current frame, but navigation is async (StateFlow →
                // LaunchedEffect in AppShell). When dialog teardown won
                // the frame race, the navigation was silently lost — the
                // "settings button intermittently unresponsive" bug.
                // Fix: only call onNavigateToSettings(). When navigation
                // replaces SessionsScreen with SettingsScreen in the
                // NavHost, the ServerStatusIconButton (and its
                // `if (showDialog)` block) leaves composition → dialog
                // auto-dismisses as a side effect of navigation. No race.
                IconButton(onClick = onNavigateToSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.server_dialog_system_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        // §icon-distinction (#6b): Sync icon (not Refresh) — this is
                        // the SERVER popup's HARD refresh (force reconnect), visually
                        // distinct from the home screen's soft Refresh button.
                        Icons.Default.Sync,
                        // §final-review F1: this is the SERVER popup's
                        // refresh (force reconnect), NOT the Chat
                        // message-refresh. Use server_dialog_refresh
                        // ("Refresh" / "刷新"), not chat_action_refresh_messages.
                        contentDescription = stringResource(R.string.server_dialog_refresh),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
