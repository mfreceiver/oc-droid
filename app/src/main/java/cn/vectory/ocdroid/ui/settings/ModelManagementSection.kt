package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.ui.theme.AppFormDialog
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.Dimens

/**
 * §model-management: Settings → Model management section.
 *
 * Renders a compact one-line row (label + chevron) that opens a scrollable
 * AlertDialog listing every provider→model the server returned via
 * GET /config/providers, each with an enable/disable Switch. Disabled models
 * are hidden from the chat quick-switch picker but remain listed here so the
 * user can re-enable them. Per-baseUrl persistence lives in
 * [cn.vectory.ocdroid.util.SettingsManager]; the caller projects the active
 * host's disabled set into [disabledModels] and routes toggles through
 * [onToggleModelDisabled]. Entries are keyed `"$providerId/$modelId"`.
 *
 * The dialog body replaces the previous always-expanded inline card so the
 * Settings page stays scannable; only the empty-state message renders inline
 * (no catalog → no row to tap, the card explains it).
 */
@Composable
internal fun ModelManagementSection(
    providers: ProvidersResponse?,
    disabledModels: Set<String>,
    isLoadingProviders: Boolean,
    onRefreshProviders: () -> Unit,
    onToggleModelDisabled: (providerId: String, modelId: String) -> Unit,
    onSetProviderModelsEnabled: (providerId: String, enabled: Boolean) -> Unit
) {
    AppSectionHeader(text = stringResource(R.string.settings_model_management))

    // §需求13: the manual refresh IconButton is a first-class affordance —
    // always rendered (header trailing slot is taken by AppSectionHeader so
    // we surface it next to the row / empty-state). Wrapped in a Row with
    // fillMaxWidth so it stays right-aligned whether or not the catalog is
    // empty.
    val refreshTrailing: @Composable () -> Unit = {
        // §需求13: manual model-catalog refresh. The fan-out's LoadProviders
        // is gated to first-launch only (providers == null); subsequent
        // refreshes go through here. Disabled + shows a CircularProgressIndicator
        // while a fetch is in flight (mirrors the SessionsScreen refresh
        // IconButton + isLoadingSessions pattern).
        IconButton(
            onClick = onRefreshProviders,
            enabled = !isLoadingProviders,
        ) {
            if (isLoadingProviders) {
                // §Dimens: no dedicated progress-stroke token; 2.dp literal
                // matches the ChangesPane.kt:207 / ChatMessageRow.kt:821
                // precedent (the canonical "small inline spinner" stroke).
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.iconStd),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.model_management_refresh),
                )
            }
        }
    }

    val (disabledCount, totalModels) = modelCatalogCounts(providers, disabledModels)
    if (totalModels == 0) {
        // Inline empty-state message (no dialog to open when there is nothing
        // to edit). §需求13: the refresh IconButton is rendered next to the
        // message so the user can pull the catalog from this state too (the
        // most likely time they need a manual refresh is precisely when the
        // list is empty).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.spacing4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spacing2)
        ) {
            Text(
                stringResource(R.string.settings_model_management_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            refreshTrailing()
        }
        return
    }

    // Disabled-count summary shown as the row's supporting text so the user
    // can tell at a glance whether any models are currently hidden. i18n'd via
    // settings_model_management_summary_none / _disabled (no hardcoded English).
    val supporting = if (disabledCount == 0) {
        stringResource(R.string.settings_model_management_summary_none, totalModels)
    } else {
        stringResource(R.string.settings_model_management_summary_disabled, disabledCount, totalModels)
    }

    var showDialog by rememberSaveable { mutableStateOf(false) }
    // §setux #new4: 移除右侧 `>` chevron 指示符（trailingContent），保留
    // clickable 行为。§需求13: trailingContent 重新启用，承载手动 refresh
    // IconButton（与 clickable 的对话框打开行为互不冲突——点击按钮区域
    // 由 IconButton 自身消费，不冒泡到 ListItem）。
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true },
        headlineContent = {
            Text(
                text = stringResource(R.string.settings_model_management_edit),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = refreshTrailing,
    )

    if (showDialog) {
        ModelManagementDialog(
            providers = providers,
            disabledModels = disabledModels,
            isLoadingProviders = isLoadingProviders,
            onToggleModelDisabled = onToggleModelDisabled,
            onSetProviderModelsEnabled = onSetProviderModelsEnabled,
            onDismiss = { showDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelManagementDialog(
    providers: ProvidersResponse?,
    disabledModels: Set<String>,
    isLoadingProviders: Boolean,
    onToggleModelDisabled: (providerId: String, modelId: String) -> Unit,
    onSetProviderModelsEnabled: (providerId: String, enabled: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val catalog = providers?.providers.orEmpty().filter { it.models.isNotEmpty() }
    // §WT5: dialog consolidated onto the shared `AppFormDialog` primitive
    // (BasicAlertDialog + Surface + AlertDialogDefaults + verticalScroll +
    // heightIn(max=screen*0.85f)). Previously this was a hand-rolled
    // BasicAlertDialog+Surface here — the canonical container now lives once
    // in `ui/theme/AppFormDialog.kt`. Per `AppFormDialog.kt` file header, the
    // BasicAlertDialog route (not AlertDialog) is mandatory because
    // AlertDialog's `text` slot swallows Switch touch events.
    //
    // The title hint + scrollable providers list + Done button are preserved
    // verbatim; only the container boilerplate is removed.
    AppFormDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_model_management),
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        },
        content = {
            Text(
                text = stringResource(R.string.settings_model_management_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Dimens.spacing4))

            // Scrollable content (providers + per-provider Switch + per-model
            // Switch). AppFormDialog already wraps the whole Column in
            // verticalScroll; an inner scroll Column would double-wrap, so we
            // emit the providers list directly into the ColumnScope.
            catalog.forEachIndexed { providerIndex, provider ->
                if (providerIndex > 0) {
                    Spacer(modifier = Modifier.height(Dimens.spacing3))
                    Spacer(modifier = Modifier.height(Dimens.spacing2))
                    Spacer(modifier = Modifier.height(Dimens.spacing3))
                }
                ProviderBlock(
                    provider = provider,
                    disabledModels = disabledModels,
                    isLoadingProviders = isLoadingProviders,
                    onToggleModelDisabled = onToggleModelDisabled,
                    onSetProviderModelsEnabled = onSetProviderModelsEnabled
                )
            }
        },
    )
}

@Composable
private fun ProviderBlock(
    provider: ConfigProvider,
    disabledModels: Set<String>,
    isLoadingProviders: Boolean,
    onToggleModelDisabled: (providerId: String, modelId: String) -> Unit,
    onSetProviderModelsEnabled: (providerId: String, enabled: Boolean) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.spacing1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spacing3)
        ) {
            Text(
                provider.name?.takeIf { it.isNotEmpty() } ?: provider.id,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            // §需求13: block the provider-level bulk toggle while the catalog
            // is mid-refresh (otherwise a toggle racing a refresh could land
            // on a stale disabledModels snapshot).
            Switch(
                checked = providerAllModelsEnabled(provider, disabledModels),
                onCheckedChange = { onSetProviderModelsEnabled(provider.id, it) },
                enabled = !isLoadingProviders,
            )
        }
        Spacer(modifier = Modifier.height(Dimens.spacing2))
        provider.models.forEach { (modelId, model) ->
            ModelRow(
                providerId = provider.id,
                modelId = modelId,
                displayName = model.name ?: modelId,
                // §fix: "$provider.id/$modelId" was a literal `.id` after the
                // variable — never matched a real disabledModels entry, so the
                // switch appeared dead and the model was always "enabled".
                // Use the real provider id from the closure parameter.
                enabled = "${provider.id}/$modelId" !in disabledModels,
                isLoadingProviders = isLoadingProviders,
                onToggle = { onToggleModelDisabled(provider.id, modelId) }
            )
        }
    }
}

@Composable
private fun ModelRow(
    providerId: String,
    modelId: String,
    displayName: String,
    enabled: Boolean,
    isLoadingProviders: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // §需求13: keep the row clickable but it routes through the same
            // Switch; while loading, the Switch is disabled AND the row's
            // clickable mirrors that (avoids a dead-tap on the row body that
            // silently no-ops while the Switch looks disabled). clickable's
            // `enabled` param gates pointer events so the ripple is also
            // suppressed.
            .clickable(enabled = !isLoadingProviders, onClick = onToggle)
            .padding(vertical = Dimens.spacingCompact),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacing3)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$providerId/$modelId",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(Dimens.spacing2))
        // §需求13: per-model Switch disabled during refresh so the user can't
        // toggle a model whose catalog entry may be about to disappear /
        // reappear (would race the reconcileModelData RMW).
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() },
            enabled = !isLoadingProviders,
        )
    }
}

/**
 * Pure counts for the model-management summary. Returns
 * `(disabledCount, totalModels)` over the providers catalog, where
 * `totalModels` is the number of models the server returned and
 * `disabledCount` is how many of those have a `"$providerId/$modelId"` entry
 * in [disabledModels].
 *
 * Extracted from the [ModelManagementSection] Composable so the
 * `"${provider.id}/$modelId"` key-format invariant is JVM-unit-testable
 * (see `ModelCatalogCountsTest`). This guards against a regression of the
 * v0.1.2 bug where the key was interpolated as `"$provider.id/$modelId"`
 * (literal `.id` after the variable) and never matched a real disabled entry.
 *
 * Pure / deterministic; safe to call from tests without Compose.
 */
internal fun modelCatalogCounts(
    providers: ProvidersResponse?,
    disabledModels: Set<String>
): Pair<Int, Int> {
    val catalog = providers?.providers.orEmpty().filter { it.models.isNotEmpty() }
    val total = catalog.sumOf { it.models.size }
    val disabled = catalog.sumOf { provider ->
        provider.models.count { (modelId, _) -> "${provider.id}/$modelId" in disabledModels }
    }
    return disabled to total
}

/**
 * §provider-bulk-toggle: 该 provider 下是否"所有 model 都启用"。供
 * [ProviderBlock] 标题行的 Switch 计算 checked 态。语义：当且仅当
 * 该 provider 的每个 `"$providerId/$modelId"` 都不在 [disabledModels]
 * 中时返回 true（全启用）。部分启用或全禁用均返回 false。
 *
 * 纯函数；可在无 Compose 的 JVM 单测中测试（与 [modelCatalogCounts] 同）。
 * 保持了与 disabledModels 相同的 `"${provider.id}/$modelId"` key 格式不变式。
 */
internal fun providerAllModelsEnabled(
    provider: ConfigProvider,
    disabledModels: Set<String>
): Boolean {
    return provider.models.keys.all { modelId -> "${provider.id}/$modelId" !in disabledModels }
}
