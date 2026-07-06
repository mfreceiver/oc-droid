// ChatContextUsageDialog.kt — context-usage detail bottom sheet and its private
// section/row helpers + count formatters. Split out of ChatTopBar.kt.
//
// Phase 6 (UI form/Expressive): 从 AlertDialog 迁移到 ModalBottomSheet——上下文
// 用量详情内容较高（多 section + 行），底部 sheet 比居中弹窗更拇指友好、可下拉
// 收起，符合 M3 Expressive 移动端"场景化展示"取向。作为 Dialog→Sheet 模式试点。
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.ContextUsage
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
    // 程序化 dismiss（Done 按钮）需先 animate hide 再 onDismiss，避免硬切。
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 8.dp)
        ) {
            // 可滚动内容区（title + sections）。Action row 在外、不随滚——保证
            // Done/Compact 按钮始终可见，不因内容高被卷到折叠线下方。weight(1f) 让
            // 滚动区在 sheet 有限高度内吃满剩余空间、footer 永远保留自然高度。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title
                Text(
                    text = stringResource(R.string.chat_context),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (usage == null) {
                    Text(
                        stringResource(R.string.chat_no_usage_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

            Spacer(Modifier.height(8.dp))

            // Action footer（非滚动，始终可见）
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
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.chat_context_compact))
                    }
                }
                // Done：先 animate hide 再 onDismiss，避免 sheet 瞬移硬切。
                TextButton(onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }) {
                    Text(stringResource(R.string.common_done))
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

@Composable
private fun ContextUsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

// §R-19 Sprint 2 #7(b): formatCount / formatOptionalCount were lifted
// verbatim into the top-level pure-functions file ChatFormatHelpers.kt
// (same package) so they can be covered by JVM unit tests (this file is
// excluded from kover coverage as a @Composable-heavy UI file — see
// PickerProviderFilter.kt for the same extraction pattern).
