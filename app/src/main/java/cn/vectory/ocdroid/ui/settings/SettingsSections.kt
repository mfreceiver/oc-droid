package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.util.formatBytes
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode

/**
 * §grouping-rewrite 项 1 (spec decision): the only valid named-group labels.
 * Any `serverGroupFp` outside this set reads as "standalone" (不分组),
 * including the soft-migrated UUID form (`fp == profile.id`). Centralised
 * here so [cn.vectory.ocdroid.ui.settings.HostProfilesManagerScreen]'s
 * selector and the profile editor stay in lockstep.
 *
 * §phase3 (plan §5 task 6 step c): the `ConnectionProfileSection` composable
 * that previously consumed this list (the "current profile" header on the
 * Settings front page) was an orphan — SettingsScreen renders its own slim
 * header and never called the section. The section + its androidTest were
 * deleted; this `NamedGroupLabels` value is retained because
 * HostProfilesManagerScreen still uses it for its group dropdown.
 */
internal val NamedGroupLabels: List<String> = listOf("A", "B", "C", "D")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSection(
    themeMode: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    /**
     * §ui-scale: the two M3-canonical scale axes (font-only + content/density).
     * Applied via LocalDensity override in OpenCodeTheme. Range clamped at the
     * SettingsManager layer to [SettingsManager.UI_SCALE_MIN]–[MAX].
     */
    uiFontScale: Float = 1f,
    uiContentScale: Float = 1f,
    onFontScaleChange: (Float) -> Unit = {},
    onContentScaleChange: (Float) -> Unit = {}
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

    Spacer(modifier = Modifier.height(20.dp))

    // §ui-scale: two sliders following the official Android scalable-content
    // guidance. Font scale = text only; content scale = everything (dp + sp).
    // Live-applied via OpenCodeTheme's LocalDensity override, so dragging the
    // slider visibly resizes the whole app in real time (the root composition
    // recomposes because MainActivity subscribes to settingsFlow).
    val percentFmt = remember { java.text.DecimalFormat("0%") }
    Text(stringResource(R.string.settings_ui_font_scale), style = MaterialTheme.typography.labelMedium)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Slider(
            value = uiFontScale,
            onValueChange = onFontScaleChange,
            valueRange = SettingsManager.UI_SCALE_MIN..SettingsManager.UI_SCALE_MAX,
            steps = 8, // 0.85–1.3 in ~0.05 steps
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = percentFmt.format(uiFontScale),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    Text(stringResource(R.string.settings_ui_content_scale), style = MaterialTheme.typography.labelMedium)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Slider(
            value = uiContentScale,
            onValueChange = onContentScaleChange,
            valueRange = SettingsManager.UI_SCALE_MIN..SettingsManager.UI_SCALE_MAX,
            steps = 8,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = percentFmt.format(uiContentScale),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
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

    ListItem(
        headlineContent = {
            Text(
                "↑ ${formatBytes(sent)}   ↓ ${formatBytes(received)}",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        trailingContent = {
            IconButton(onClick = onReset) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.settings_traffic_reset),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/**
 * Danger zone: hard-reset ALL local data (open tabs, session cache, drafts,
 * current session/workdir, theme/font prefs, traffic stats) while preserving
 * server connection info and tunnel passwords, then trigger a reconnect +
 * server re-fetch via [cn.vectory.ocdroid.ui.MainViewModel.resetLocalDataAndResync].
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
        stringResource(R.string.settings_version, cn.vectory.ocdroid.BuildConfig.VERSION_NAME),
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
