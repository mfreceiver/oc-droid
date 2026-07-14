package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
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
    onToggleModelDisabled: (providerId: String, modelId: String) -> Unit,
    onSetProviderModelsEnabled: (providerId: String, enabled: Boolean) -> Unit
) {
    AppSectionHeader(text = stringResource(R.string.settings_model_management))

    val (disabledCount, totalModels) = modelCatalogCounts(providers, disabledModels)
    if (totalModels == 0) {
        // Inline empty-state message (no dialog to open when there is nothing
        // to edit).
        Text(
            stringResource(R.string.settings_model_management_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Dimens.spacing4)
        )
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
    // clickable 行为。
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
    )

    if (showDialog) {
        ModelManagementDialog(
            providers = providers,
            disabledModels = disabledModels,
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
            Switch(
                checked = providerAllModelsEnabled(provider, disabledModels),
                onCheckedChange = { onSetProviderModelsEnabled(provider.id, it) }
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
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
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
        Switch(checked = enabled, onCheckedChange = { onToggle() })
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
