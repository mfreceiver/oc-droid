package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import cn.vectory.ocdroid.ui.theme.AppConfirmDialog
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.ui.util.formatBytes
import cn.vectory.ocdroid.util.LocaleMode
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
     * §P5a (Q5): user-facing language preference (Follow System / 中文 /
     * English). Parallel to [themeMode]; applied via AppLocaleController
     * → AppCompatDelegate → MainActivity recreate.
     */
    localeMode: LocaleMode = LocaleMode.SYSTEM,
    onLocaleSelected: (LocaleMode) -> Unit = {},
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
    AppSectionHeader(text = stringResource(R.string.settings_appearance))

    // §review-AB: each bare widget self-pads `horizontal = Dimens.spacing4` so
    // it shares one 16dp keyline with AppSectionHeader (self-pad) + ListItem
    // (self-pad). The route Column no longer adds a parent horizontal padding
    // (that caused the header to sit at 32dp while bare content sat at 16dp).

    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
    Text(
        stringResource(R.string.settings_theme),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = Dimens.spacing4),
    )
    Spacer(modifier = Modifier.height(Dimens.spacing2))
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spacing4)
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

    Spacer(modifier = Modifier.height(Dimens.spacing5))

    // §P5a (Q5): language SegmentedButton — parallel to the Theme row above.
    // Follow System re-resolves the real system locale (zh→zh, en→en, other→zh).
    val locales = listOf(LocaleMode.SYSTEM, LocaleMode.ZH, LocaleMode.EN)
    Text(
        stringResource(R.string.settings_language),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = Dimens.spacing4),
    )
    Spacer(modifier = Modifier.height(Dimens.spacing2))
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spacing4)
    ) {
        locales.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = localeMode == mode,
                onClick = { onLocaleSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = locales.size
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
                        LocaleMode.SYSTEM -> stringResource(R.string.settings_follow_system)
                        LocaleMode.ZH -> stringResource(R.string.settings_language_chinese)
                        LocaleMode.EN -> stringResource(R.string.settings_language_english)
                    }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(Dimens.spacing5))

    // §ui-scale: two sliders following the official Android scalable-content
    // guidance. Font scale = text only; content scale = everything (dp + sp).
    // Live-applied via OpenCodeTheme's LocalDensity override, so dragging the
    // slider visibly resizes the whole app in real time (the root composition
    // recomposes because MainActivity subscribes to settingsFlow).
    val percentFmt = remember { java.text.DecimalFormat("0%") }
    Text(
        stringResource(R.string.settings_ui_font_scale),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = Dimens.spacing4),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spacing4)
            .padding(top = Dimens.spacing1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Slider(
            value = uiFontScale,
            onValueChange = onFontScaleChange,
            valueRange = SettingsManager.UI_SCALE_MIN..SettingsManager.UI_SCALE_MAX,
            steps = 8, // 0.85–1.3 in ~0.05 steps
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(Dimens.spacing3))
        Text(
            text = percentFmt.format(uiFontScale),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // §ui-scale: 44dp holds "130%" at labelMedium even at max
            // uiContentScale (1.3×). No Dimens token for 44dp — spec §3
            // tolerates this one-off width literal with a written reason.
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }

    Spacer(modifier = Modifier.height(Dimens.spacing3))

    Text(
        stringResource(R.string.settings_ui_content_scale),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = Dimens.spacing4),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spacing4)
            .padding(top = Dimens.spacing1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Slider(
            value = uiContentScale,
            onValueChange = onContentScaleChange,
            valueRange = SettingsManager.UI_SCALE_MIN..SettingsManager.UI_SCALE_MAX,
            steps = 8,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(Dimens.spacing3))
        Text(
            text = percentFmt.format(uiContentScale),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // §ui-scale: see the matching font-scale row above.
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
        AppSectionHeader(text = stringResource(R.string.settings_traffic))
    }

    ListItem(
        headlineContent = {
            Text(
                "↑ ${formatBytes(sent)}   ↓ ${formatBytes(received)}",
                // §WT5 (unify): row headline promoted bodyMedium → bodyLarge
                // to match S1 (HostProfileRow) and S3 (ModelManagementSection
                // launcher row). The trailing Refresh IconButton stays.
                style = MaterialTheme.typography.bodyLarge
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
 * §P5b-B (Q8): 清除数据 section (was Danger Zone). Renders FLAT (no tinted
 * Card) as a single row carrying the 清除缓存 button.
 *
 * The button keeps the existing destructive behavior
 * ([cn.vectory.ocdroid.ui.MainViewModel.resetLocalDataAndResync] — wipe +
 * re-fetch) and its confirmation [AlertDialog] (irreversible warning). Only
 * the action is honestly labelled "重置本地数据" (the section title is
 * "清除数据" via [R.string.settings_danger_zone]).
 *
 * remove-message-persistence Task 5: the "已缓存数据 XXX MB" size indicator
 * that used to sit to the left of the button was removed together with the
 * SQLite persistence layer (the size was read off the now-deleted
 * SettingsViewModel cache-bytes StateFlow).
 */
@Composable
internal fun DangerZoneSection(
    onClearLocalData: () -> Unit,
    hideHeader: Boolean = false,
) {
    var showConfirm by remember { mutableStateOf(false) }

    if (!hideHeader) {
        AppSectionHeader(text = stringResource(R.string.settings_danger_zone))
    }

    // §P5b-B (Q8): flat single row — no tinted Card. The right button triggers
    // the destructive wipe (gated by the confirmation dialog below).
    // §review-AB: Row self-pads horizontal 16dp (route Column no longer pads).
    // remove-message-persistence Task 5: the cache-size indicator that sat to
    // the left was removed with the SQLite persistence layer; the button now
    // carries the row alone.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spacing4),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { showConfirm = true },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(Dimens.iconSm))
            Spacer(modifier = Modifier.width(Dimens.spacing2))
            Text(stringResource(R.string.cache_clear_cache))
        }
    }

    if (showConfirm) {
        // §review-AB: clear-local-data confirm now uses the AppConfirmDialog
        // composable-body overload so the three message segments keep their
        // semantic colors (keeps=primary / clears=onSurfaceVariant /
        // irreversible=error). Previously WT5 collapsed them into a single
        // String body, losing the red "irreversible" safety cue. Texts are
        // preserved verbatim — only the per-paragraph color emphasis is
        // restored on top of the canonical dialog shell.
        AppConfirmDialog(
            title = stringResource(R.string.settings_clear_local_data_title),
            bodyContent = {
                Text(
                    stringResource(R.string.settings_clear_local_data_keeps),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(Dimens.spacing2))
                Text(
                    stringResource(R.string.settings_clear_local_data_clears),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Dimens.spacing2))
                Text(
                    stringResource(R.string.settings_clear_local_data_irreversible),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            confirmText = stringResource(R.string.cache_clear_cache),
            onConfirm = {
                showConfirm = false
                onClearLocalData()
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showConfirm = false },
            destructive = true,
        )
    }
}

@Composable
internal fun AboutSection() {
    AppSectionHeader(text = stringResource(R.string.settings_about))

    // §review-AB: bare Texts self-pad horizontal 16dp (route Column no longer
    // pads) so they share one keyline with AppSectionHeader.
    Text(
        stringResource(R.string.app_name),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = Dimens.spacing4),
    )
    Text(
        // BuildConfig.VERSION_NAME is generated at build time from
        // app/build.gradle.kts, so this always reflects the shipped version
        // without a hardcoded string to keep in sync.
        stringResource(R.string.settings_version, cn.vectory.ocdroid.BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = Dimens.spacing4),
    )
}

// §WT5: the local `SectionHeader(title: String)` composable that used to live
// here has been deleted. All settings call sites now use the canonical shared
// primitive `cn.vectory.ocdroid.ui.theme.AppSectionHeader` (titleSmall +
// onSurfaceVariant + Dimens.padding, see docs/ui-style-spec.md §2). Same-
// package callers that were migrated alongside this lane:
//  - SettingsSections.kt (AppearanceSection / TrafficSection / DangerZoneSection / AboutSection)
//  - SettingsScreen.kt (Notifications / Storage / About sub-routes)
//  - HostProfilesManagerScreen.kt (S1 服务器配置 / S2 流量统计)
//  - ModelManagementSection.kt (settings_model_management)
//  - the cache-management section composable (deleted, remove-message-
//    persistence Task 5) / DebugLogSection.kt (same package — one-line
//    callsite each).
