package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.ProvidersResponse

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
    SectionHeader(title = stringResource(R.string.settings_model_management))

    val (disabledCount, totalModels) = modelCatalogCounts(providers, disabledModels)
    if (totalModels == 0) {
        // Inline empty-state message (no dialog to open when there is nothing
        // to edit).
        Text(
            stringResource(R.string.settings_model_management_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
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
    // §setux #6: 改为 BasicAlertDialog + Surface 容器。原实现用 AlertDialog 的
    // text 槽承载 verticalScroll 列表，Switch 触摸事件被 AlertDialog 的内部
    // scroll/layout 消化导致「无法点击」。BasicAlertDialog 把内容完全交给
    // 调用方，Switch 在普通 Column 里——触摸路由恢复正常。title / Done 按钮 /
    // scroll / providers 遍历 / ProviderBlock / ModelRow / 接线全部保留。
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // §review-fix (gpter M2): cap the dialog at ~85% of the screen
                // height so the Done button stays reachable on small / landscape
                // / split-screen (the old fixed heightIn(max=480) list + title
                // + spacers + Done could exceed the viewport and clip Done).
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f),
            shape = AlertDialogDefaults.shape,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            Column(modifier = Modifier.fillMaxHeight().padding(24.dp)) {
                // title (含 hint 副标题) — 保留原 AlertDialog.title 内容。
                Column {
                    Text(stringResource(R.string.settings_model_management))
                    Text(
                        text = stringResource(R.string.settings_model_management_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                // 滚动内容（providers + 每个 ProviderBlock 的 Switch + 每个
                // ModelRow 的 Switch）。现在这些 Switch 在普通 Column（非
                // AlertDialog.text 槽），触摸事件正常派发。weight(1f) 让列表
                // 消费 title/Done 之外的剩余高度并在溢出时滚动。
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    catalog.forEachIndexed { providerIndex, provider ->
                        if (providerIndex > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        ProviderBlock(
                            provider = provider,
                            disabledModels = disabledModels,
                            onToggleModelDisabled = onToggleModelDisabled,
                            onSetProviderModelsEnabled = onSetProviderModelsEnabled
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                // confirmButton (Done) — 右对齐，保留原 TextButton。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_done))
                    }
                }
            }
        }
    }
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
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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
        Spacer(modifier = Modifier.height(8.dp))
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
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
        Spacer(modifier = Modifier.width(8.dp))
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
