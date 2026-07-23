// PickerSheets.kt — relocated from Composer.kt (Wave 1′-B lane L1e, pure
// relocation). Contains the Agent + Model picker ModalBottomSheet composables.
// They are `internal` so ChatScaffold (same package) can invoke them directly;
// same-package resolution means no call-site import changes were needed.

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.ui.theme.AppBottomSheet
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.ui.theme.PickerTrailingCheck

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPickerSheet(
    agents: List<AgentInfo>,
    currentAgentName: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // §B4·P3-B: 迁移到 AppBottomSheet（容器色 / titleLarge / sheetState /
    // 底部 inset 由 recipe 统一）。Agent 视为单一 section，无 provider 分组。
    // 选中态：headline primary 色 + trailing 槽固定 18dp Check（替代 SmartToy，
    // 与 ModelPickerSheet 统一为 Filled Check）。
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_switch_agent),
    ) {
        // §fix-sheet-remeasure-jump: same height cap as ModelPickerSheet —
        // structurally identical LazyColumn picker; bound it so a long agent
        // list cannot trigger the same remeasure-driven sheet jump.
        val pickerMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
        LazyColumn(modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = pickerMaxHeight)) {
            // 默认 agent（选中 = null）。
            item(key = "__default__") {
                val isSelected = currentAgentName == null
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.agent_default_label),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingContent = { PickerTrailingCheck(isSelected) },
                    modifier = Modifier.clickable { onPick(null) },
                )
            }
            // §B4: 稳定 key 让选中态切换不丢动画；trailing 槽位恒渲染避免文本跳动。
            items(items = agents, key = { it.name }) { agent ->
                val isSelected = agent.name == currentAgentName
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text(
                            text = agent.name,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingContent = { PickerTrailingCheck(isSelected) },
                    modifier = Modifier.clickable { onPick(agent.name) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    providers: ProvidersResponse?,
    disabledModels: Set<String>,
    currentModel: cn.vectory.ocdroid.data.model.Message.ModelInfo?,
    onSwitch: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * §chat-ux-batch T7 (B2): "默认" pick — clears the transient pendingModel
     * so the next send falls back to inference / server default. Mirrors the
     * AgentPickerSheet's `__default__` item (which routes to `onPick(null)`).
     * The caller (ChatScaffold) wires this to [cn.vectory.ocdroid.ui.ComposerViewModel.clearSessionModel].
     */
    onClear: () -> Unit = {},
) {
    // §B4·P3-B: 迁移到 AppBottomSheet（容器色 / titleLarge / sheetState /
    // 底部 inset 由 recipe 统一）。provider header 用 labelLarge + 顶 8dp/
    // 底 4dp（与 Agent 标题节奏对称）；trailing 槽统一 Filled Check（替代
    // Outlined Memory）。
    val catalog = visiblePickerProviders(providers, disabledModels)
    // §fix-sheet-remeasure-jump: cap the picker list at ~80% of the screen
    // height. Instrumented repro (contentHeight 1975↔2103px on a 2400px screen
    // ≈ 82-88%) confirmed the LazyColumn's intrinsic height rides close to the
    // sheet's fully-expanded anchor and fluctuates as items scroll/recycle,
    // which couples back into ModalBottomSheet's layout and produces the
    // visible "height反复跳动" during a real-finger drag. Bounding the list
    // height breaks that feedback loop — the LazyColumn scrolls internally
    // against a stable bounded constraint instead of remeasuring the sheet.
    // Local to the picker per SheetRecipe's "调用方封顶" contract (the recipe
    // itself deliberately does NOT impose a fixed cap).
    val pickerMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_model_picker_title),
    ) {
        // §chat-ux-batch T7 review-fix (I1): the "默认" row is rendered
        // UNCONDITIONALLY so the user can always clear a pending model /
        // pick server default — even when the provider catalog is empty
        // (or unusable). Mirrors AgentPickerSheet's structure (line ~475),
        // where `__default__` lives at the head of an always-present
        // LazyColumn and the empty-catalog state is just a list-item
        // message UNDER it. Pre-fix the 默认 row sat inside the `else`
        // (non-empty) branch, so an empty catalog rendered ONLY the empty
        // message — T7-C4 requires 默认 to always be available + highlighted
        // when the effective model is null.
        LazyColumn(modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = pickerMaxHeight)) {
            // §chat-ux-batch T7 (B2): top "默认" item — selected when the
            // effective model (pending ?: infer ?: null) is null. Routes
            // to onClear (ComposerViewModel.clearSessionModel), which
            // resets pendingModel so the next send falls back to inference
            // / server default. Mirrors the AgentPickerSheet's `__default__`
            // row at line ~477.
            item(key = "__default__") {
                val isSelected = currentModel == null
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.agent_default_label),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingContent = { PickerTrailingCheck(isSelected) },
                    modifier = Modifier.clickable { onClear() },
                )
            }
            if (catalog.isEmpty()) {
                // Empty catalog: show the message UNDER the always-present
                // 默认 row so the user can still clear / pick server default.
                // §12: horizontal spacing6→spacing4（AppBottomSheet content 槽已统一
                // 8dp，8+16=24dp keyline；原 spacing6 会变 8+24=32dp）。
                item(key = "__empty__") {
                    Text(
                        text = stringResource(R.string.chat_model_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = Dimens.spacing4,
                            vertical = Dimens.spacing3,
                        ),
                    )
                }
            } else {
                catalog.forEach { provider ->
                    val matchingModels = provider.models.entries
                        .map { (modelId, model) -> modelId to model }
                        .filter { (modelId, _) ->
                            "${provider.id}/$modelId" !in disabledModels
                        }
                    if (matchingModels.isEmpty()) return@forEach
                    // §WT1 provider header：用 AppSectionHeader（titleSmall 14sp/Med
                    // + onSurfaceVariant + 16dp/8dp padding），替代原手写 labelLarge
                    // + 硬编码 padding(start=24,end=24,top=8,bottom=4)——与 ui-style-spec
                    // §2 的 section header 原语对齐，跨 picker 统一。
                    item(key = "header_${provider.id}") {
                        AppSectionHeader(
                            text = provider.name?.takeIf { it.isNotEmpty() } ?: provider.id,
                        )
                    }
                    items(
                        items = matchingModels,
                        key = { (modelId, _) -> "${provider.id}/$modelId" },
                    ) { (modelId, model) ->
                        val isSelected = currentModel != null &&
                            currentModel.providerId == provider.id &&
                            (currentModel.modelId == modelId || currentModel.modelId == model.id)
                        androidx.compose.material3.ListItem(
                            headlineContent = {
                                Text(
                                    text = model.name ?: modelId,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            trailingContent = { PickerTrailingCheck(selected = isSelected) },
                            modifier = Modifier.clickable { onSwitch(provider.id, modelId) },
                        )
                    }
                }
            }
        }
    }
}
