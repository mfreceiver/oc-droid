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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.util.ThemeMode

@Composable
internal fun ConnectionProfileSection(
    profile: HostProfile,
    state: AppState,
    onManageProfiles: () -> Unit
) {
    SectionHeader(title = stringResource(R.string.settings_connection_profile))

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                }
                // Connected badge + version surfaces the live server status on
                // the right of the profile row. The Test Connection action lives
                // on the per-row icons inside Manage Connections now, so it is
                // intentionally not duplicated here.
                if (state.isConnected) {
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
                        state.serverVersion?.let { version ->
                            Text(
                                " (v$version)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

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

@Composable
internal fun ServerConnectionSection(
    serverUrl: String,
    username: String,
    password: String,
    showPassword: Boolean,
    isTesting: Boolean,
    state: AppState,
    testResult: TestResult?,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit
) {
    SectionHeader(title = stringResource(R.string.settings_server_connection))

    OutlinedTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text(stringResource(R.string.settings_server_url)) },
        placeholder = { Text("http://localhost:4096") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text(stringResource(R.string.settings_username_optional)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.settings_password_optional)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onTogglePasswordVisibility) {
                Icon(
                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showPassword) stringResource(R.string.settings_hide_password) else stringResource(R.string.settings_show_password)
                )
            }
        },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
    )

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onTestConnection,
            enabled = serverUrl.isNotBlank() && !isTesting
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(stringResource(R.string.settings_test_connection))
        }

        OutlinedButton(
            onClick = onSave,
            enabled = serverUrl.isNotBlank()
        ) {
            Text(stringResource(R.string.settings_save))
        }
    }

    testResult?.let { ResultCard(result = it) }

    if (state.isConnected) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.settings_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            state.serverVersion?.let { version ->
                Text(
                    " (v$version)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
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
    onReset: () -> Unit
) {
    SectionHeader(title = stringResource(R.string.settings_traffic))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            TrafficRow(
                label = stringResource(R.string.settings_traffic_sent),
                bytes = sent
            )
            Spacer(modifier = Modifier.height(8.dp))
            TrafficRow(
                label = stringResource(R.string.settings_traffic_received),
                bytes = received
            )
            Spacer(modifier = Modifier.height(8.dp))
            TrafficRow(
                label = stringResource(R.string.settings_traffic_total),
                bytes = sent + received
            )
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

@Composable
private fun TrafficRow(label: String, bytes: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            formatBytes(bytes),
            style = MaterialTheme.typography.bodyMedium
        )
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
private fun ResultCard(result: TestResult) {
    Spacer(modifier = Modifier.height(12.dp))
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (result.success) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (result.success) Icons.Default.Check else Icons.Default.Error,
                contentDescription = null,
                tint = if (result.success) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                result.message,
                color = if (result.success) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }
            )
        }
    }
}

@Composable
internal fun SettingsSectionDivider() {
    Spacer(modifier = Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(32.dp))
}

internal data class TestResult(
    val success: Boolean,
    val message: String
)
