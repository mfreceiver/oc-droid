// ChatContextUsageDialog.kt — context-usage detail bottom sheet and its private
// section/row helpers + count formatters. Split out of ChatTopBar.kt.
//
// Phase 6 (UI form/Expressive): 从 AlertDialog 迁移到 ModalBottomSheet——上下文
// 用量详情内容较高（多 section + 行），底部 sheet 比居中弹窗更拇指友好、可下拉
// 收起，符合 M3 Expressive 移动端"场景化展示"取向。作为 Dialog→Sheet 模式试点。
//
// ── B4·P3 子任务 A：迁移到统一容器 AppBottomSheet（SheetRecipe.kt）──────────────
// 用户投诉点 4「高度占满屏 / 字号偏小 / 不如其它浮窗」，本次修复：
//  1. 容器改用 [AppBottomSheet]，title/footer 由 scaffold 统一渲染（titleLarge +
//     HorizontalDivider），与 Todo / Agent / Model 等 sheet 对齐口径。
//  2. 【关键】去掉 content 的 `weight(1f)`（原 :94-98）——那是"强制占满屏"的根因：
//     `skipPartiallyExpanded=true` 下 ModalBottomSheet 给出 bounded 高度，weight(1f)
//     让滚动区吃满剩余空间 ≈ 屏 90%。改为 `heightIn(max ≈ 屏高 78%) + verticalScroll`
//     的自然高度：短内容（如 usage==null 单行）不被撑满，长内容（多 section）触顶
//     后在封顶区内滚动；footer 自然跟随内容下方，不强制钉底。
//  3. section header 从 labelMedium+SemiBold+primary 改为 labelLarge+onSurfaceVariant
//     （14sp/Med，对齐其它 sheet 的次级标题口径，更易读）。
//  4. item 字号保持 bodyLarge（与其它 sheet 一致；全局 bodyLarge 将在 P5 升到 16sp，
//     届时 context 字号随之增大——这是决策 4「全局 16」的预期，本批不动数值）。
//  5. footer「完成」用 animate-dismiss（sheetState.hide() → onDismiss），避免硬切。
//
// ── Dialog→Sheet 迁移决策（Phase 6 范围说明）──────────────────────────────────
// 本 Phase 仅迁 ContextUsageDialog 一个作为模式验证。其余 10+ 个 AlertDialog 暂留：
//  - 简短确认/输入型（stop-confirm、archive-confirm、rename 等）→ 保留 Dialog（居中
//    弹窗对短交互更聚焦）
//  - 内容型详情（ContextUsage 已迁；ChatServerManagement / Todo / Agent 详情内容
//    较高，是后续迁移候选，留作 follow-up）
// 判定准则：内容高度 > 半屏且为只读详情 → Sheet；短确认/表单 → Dialog。

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.ContextUsage
import cn.vectory.ocdroid.ui.theme.AppBottomSheet
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.Dimens
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * R-28: fixed currency symbol for the context-usage cost display.
 *
 * Confirmed with the user: this value is always CNY regardless of device
 * locale (the upstream server reports cost in CNY), so we deliberately do NOT
 * use NumberFormat.getCurrencyInstance(locale) — that would render "$" or
 * "€" on non-CNY devices and mislead the user. Pinned to "¥" with the same
 * 4-decimal formatting as before, so the visual is unchanged; the constant
 * centralises the policy and documents why locale is intentionally ignored.
 */
private const val CURRENCY_SYMBOL = "¥"

@Composable
internal fun ContextUsageDialog(
    usage: ContextUsage?,
    onDismiss: () -> Unit,
    /**
     * §context-compact: optional callback fired when the user taps the
     * "压缩" (compact) button. When null (the default), the button is
     * hidden and the sheet behaves as before.
     */
    onCompact: (() -> Unit)? = null
) {
    // skipPartiallyExpanded = true：用户主动点开"查看用量"，意图是看全数据而非
    // peek，故跳过半屏档直接全展开。sheet 在 swipe/scrim dismiss 时 onDismiss 触发。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 程序化 dismiss（完成按钮）需先 animate hide 再 onDismiss，避免硬切。
    val scope = rememberCoroutineScope()

    // 内容区封顶高度：屏高 78%。短内容（如 usage==null 单行）按自然高度不撑满全屏，
    // 长内容（多 section）触顶后在封顶区内滚动。取代原 weight(1f)（强制吃满屏）。
    val maxContentHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp

    AppBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        title = stringResource(R.string.chat_context),
        footer = {
            // Action footer（scaffold 已在上方渲染 HorizontalDivider + 水平 16dp padding，
            // 底部 16dp Spacer；此处不再重复加 bottom padding，避免双重留白）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // §context-compact: only render the compact affordance when the caller
                // wired a callback AND we actually have usage data to compact against.
                if (onCompact != null && usage != null) {
                    TextButton(onClick = onCompact) {
                        Icon(
                            Icons.Outlined.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.iconSm),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(Dimens.spacingCompact))
                        Text(stringResource(R.string.chat_context_compact))
                    }
                }
                // 完成：先 animate hide 再 onDismiss，避免 sheet 瞬移硬切。
                TextButton(onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }) {
                    Text(stringResource(R.string.common_done))
                }
            }
        },
    ) {
        // §WT1 chat-sheets 统一：body 行从手写 `Row(SpaceBetween){Text(label) Text(value)}`
        // 改为 M3 ListItem(headlineContent=label, trailingContent=value)；section header
        // 从手写 labelLarge+onSurfaceVariant 改为 AppSectionHeader。ListItem 自带 16dp
        // 水平 padding（与 AppSectionHeader 对齐）；原语层统一 8dp，补 ListItem
        // 内置 16dp → 24dp keyline。
        // **不用 weight(1f)**——heightIn(max) 封顶 + verticalScroll，sheet 按内容自然高度，
        // footer 自然跟随下方（footer 由 AppBottomSheet 在 content 之后渲染）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxContentHeight)
                .verticalScroll(rememberScrollState())
        ) {
            if (usage == null) {
                Text(
                    stringResource(R.string.chat_no_usage_data),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = Dimens.spacing4,
                        vertical = Dimens.spacing3,
                    ),
                )
            } else {
                ContextUsageSection(stringResource(R.string.chat_context_model_section)) {
                    ContextUsageRow(stringResource(R.string.chat_context_provider), usage.providerId ?: stringResource(R.string.chat_context_unknown))
                    ContextUsageRow(stringResource(R.string.chat_context_model), usage.modelId ?: stringResource(R.string.chat_context_unknown))
                    ContextUsageRow(stringResource(R.string.chat_context_limit), formatCount(usage.contextLimit))
                }
                ContextUsageSection(stringResource(R.string.chat_context_tokens)) {
                    ContextUsageRow(stringResource(R.string.chat_context_total), formatCount(usage.totalTokens))
                    ContextUsageRow(stringResource(R.string.chat_context_input), formatOptionalCount(usage.inputTokens))
                    ContextUsageRow(stringResource(R.string.chat_context_output), formatOptionalCount(usage.outputTokens))
                    ContextUsageRow(stringResource(R.string.chat_context_reasoning), formatOptionalCount(usage.reasoningTokens))
                    ContextUsageRow(stringResource(R.string.chat_context_cached_read), formatOptionalCount(usage.cachedReadTokens))
                    ContextUsageRow(stringResource(R.string.chat_context_cached_write), formatOptionalCount(usage.cachedWriteTokens))
                }
                ContextUsageSection(stringResource(R.string.chat_context_cost)) {
                    ContextUsageRow(stringResource(R.string.chat_context_cost), usage.cost?.let { "$CURRENCY_SYMBOL${String.format(Locale.US, "%.4f", it)}" } ?: stringResource(R.string.chat_context_no_cost))
                }
            }
        }
    }
}

@Composable
private fun ContextUsageSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    // §WT1：section header 改用 AppSectionHeader（titleSmall 14sp/Med +
    // onSurfaceVariant + 16dp/8dp padding），跨 sheet 统一。section 内 item 间
    // 距由 ListItem 自身垂直 padding 自然分隔。
    Column {
        AppSectionHeader(text = title)
        content()
    }
}

@Composable
private fun ContextUsageRow(label: String, value: String) {
    // §WT1：行从手写 Row(SpaceBetween){Text(label) Text(value)} 改为 M3 ListItem
    // ——headlineContent 承载 label（onSurfaceVariant），trailingContent 承载 value
    // （Medium 字重）。ListItem 自带 16dp 水平 padding，与 AppSectionHeader 对齐。
    // 长 value 允许换行（ListItem 默认支持）。
    ListItem(
        headlineContent = {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
    )
}

// §R-19 Sprint 2 #7(b): formatCount / formatOptionalCount were lifted
// verbatim into the top-level pure-functions file ChatFormatHelpers.kt
// (same package) so they can be covered by JVM unit tests (this file is
// excluded from kover coverage as a @Composable-heavy UI file — see
// PickerProviderFilter.kt for the same extraction pattern).
