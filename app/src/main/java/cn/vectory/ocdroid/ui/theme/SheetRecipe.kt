@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.vectory.ocdroid.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// B2·P2 设计语言地基：统一 ModalBottomSheet 配方（SheetRecipe）。
//
// 项目有 10+ 个 ModalBottomSheet 调用点（Context 用量、Todo、Agent 选择、Model 选择、
// Session 选择、Workdir 控制、Directory picker、Changes 等），各自手写容器色 /
// sheetState / title 行 / footer / 底部 inset，导致视觉与行为漂移。本组件把 oracle A4
// 统一配方固化进默认值，B4 迁移时改动最小。
//
// 固化的共性（scaffold 负责）：
//  1. sheetState：默认 `skipPartiallyExpanded = true`（调用方可覆盖）。
//  2. 容器色：`surfaceContainerLow`（统一 tonal 层级）。
//  3. title 行：`titleLarge` + 水平 24dp / 垂直 8dp padding（仅当 title != null）。
//  4. footer 行：上方 `HorizontalDivider` + 水平 16dp padding（仅当 footer != null）。
//  5. 底部安全区：统一 16dp bottom content padding。
//
// 留给调用方（scaffold 不管）：
//  - content 内容与 item 渲染（4 个 sheet 结构不同，硬塞会限制表达）。
//  - 超长内容的高度处理（见下方「weight(1f) 策略」）：默认 `heightIn(max ≈ 屏
//    75-80%) + verticalScroll`；当需要 footer 恒可见时，content 槽内**可以**用
//    `Modifier.weight(1f)`（content 是 ColumnScope，支持 weight），让滚动区吃满
//    剩余高度、footer 保留自然高度——这正是现网 ChatContextUsageDialog 的做法。
//  - 显隐控制：外层 `if (visible) { AppBottomSheet(...) }`（与现有 sheet 一致）。
//
// weight(1f) 策略：scaffold 自身不内置 `weight(1f)`（让默认无 footer 场景保持自然
// 高度），但**不禁止**调用方在 content 槽内使用。`skipPartiallyExpanded = true` 下
// ModalBottomSheet 给出 bounded 高度，content 作为 ColumnScope 子项，weight(1f) 可
// 正常解析。推荐：
//  - 无 footer / 纯列表 → `verticalScroll`（+可选 heightIn 封顶），不用 weight。
//  - 有 footer + 长内容 → content 用 `weight(1f).verticalScroll(...)`，footer 恒可见。
//
// §inset-note：本组件**故意不**用 `WindowInsets.navigationBars` 吸收底部 inset——
// 在本 App 的 AppCompat 主题下，Compose 的 `navigationBars()` 解析为 0（DecorView
// 先消费了 inset，见 MainActivity.kt / AppShell.kt §bug-6.4 注释）。现有 10 个 sheet
// 统一用 `padding(bottom = 16.dp)` 作为底部留白，本组件对齐这一做法（参数化为
// [bottomContentPadding]，默认 16dp，由 scaffold 在底部统一加 Spacer 实现）。若未来
// 切到 EdgeToEdge 主题，再在此处接入 `windowInsetsPadding`。
//
// ⚠️ 双重 padding 警示：scaffold 已在底部加 `Spacer(height = bottomContentPadding)`
// 留白，调用方**不要再在 content/footer 外层加自己的 bottom padding**，否则双重
// 留白。现有 sheet 各自的 `.padding(bottom = 16.dp)` 在 B4 迁移时**必须删掉**，
// 交由 scaffold 统一处理。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 统一的 ModalBottomSheet 配方组件，固化 oracle A4 的容器色 / sheetState / title 行 /
 * footer 行 / 底部 inset 默认值。
 *
 * 4 个待迁移 sheet 的用法见下方「迁移示例」；调用方只负责 content 与可选 footer，
 * 容器层配方由本组件保证一致。
 *
 * **显隐**：本组件不做内部门控——调用方用 `if (visible) { AppBottomSheet(...) }`
 * 控制（与现有 [ModalBottomSheet] 调用点一致）。这样 `sheetState` 的生命周期与显隐
 * 自然绑定，无需额外的 `LaunchedEffect` 关闭动画协调。
 *
 * **内容高度**：scaffold 自身不内置 `weight(1f)`（让默认无 footer 场景保持自然
 * 高度），但**不禁止**调用方在 content 槽内使用（content 是 ColumnScope，支持
 * weight）。两种处理模板：
 *
 * 无 footer / 纯列表（默认推荐，不用 weight）：
 * ```
 * AppBottomSheet(
 *     title = "...",
 *     onDismissRequest = { /*...*/ },
 * ) {
 *     Column(
 *         modifier = Modifier
 *             .heightIn(max = <screenHeight * 0.75f>)  // 调用方封顶
 *             .verticalScroll(rememberScrollState())
 *     ) { /* items */ }
 * }
 * ```
 *
 * 有 footer + 长内容（content 用 weight(1f) 让 footer 恒可见）：
 * ```
 * AppBottomSheet(
 *     title = "...",
 *     footer = { /* Done / 确认 */ },
 *     onDismissRequest = { /*...*/ },
 * ) {
 *     Column(
 *         modifier = Modifier
 *             .weight(1f)                                // 滚动区吃满剩余高度
 *             .verticalScroll(rememberScrollState())
 *     ) { /* items */ }
 *     // footer 由 scaffold 渲染在 weight 区下方，恒可见
 * }
 * ```
 *
 * **迁移示例（B4）**：
 *
 * Context 用量 sheet（有 footer，footer 恒可见的旗舰示例）：
 * ```
 * // ① hoist sheetState + scope：footer 的「完成」需 animate-dismiss
 * val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
 * val scope = rememberCoroutineScope()
 *
 * if (showContext) AppBottomSheet(
 *     sheetState = sheetState,                       // 传 hoist 的 state
 *     title = stringResource(R.string.chat_context), // 原 titleMedium → 现在统一 titleLarge
 *     footer = {
 *         Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
 *             // 「压缩」保持原逻辑
 *             TextButton(onClick = onCompact) { Text("压缩") }
 *             // 「完成」animate-dismiss，避免 sheet 瞬移硬切
 *             // （对齐现网 ChatContextUsageDialog.kt:156-161）
 *             TextButton(onClick = {
 *                 scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
 *             }) { Text("完成") }
 *         }
 *     },
 *     onDismissRequest = onDismiss,
 * ) {
 *     // ② content 用 weight(1f) 让滚动区吃满剩余高度，footer（含 divider）恒可见
 *     Column(
 *         Modifier
 *             .weight(1f)
 *             .verticalScroll(rememberScrollState())
 *             .padding(horizontal = 24.dp)
 *     ) {
 *         // sections/rows（原 ContextUsageSection/Row 不变）
 *     }
 * }
 * ```
 *
 * Agent 选择 sheet（无 footer，纯列表）：
 * ```
 * if (showAgent) AppBottomSheet(
 *     title = stringResource(R.string.chat_switch_agent),
 *     onDismissRequest = onDismiss,
 * ) {
 *     Column(Modifier.verticalScroll(rememberScrollState())) {
 *         ListItem(...)  // items
 *     }
 * }
 * ```
 *
 * @param onDismissRequest scrim/swipe dismiss + 程序化关闭时回调（与 [ModalBottomSheet] 一致）。
 * @param modifier 应用到 [ModalBottomSheet] 根的 modifier。
 * @param sheetState 默认 `skipPartiallyExpanded = true`（配方固化点①）。调用方如需
 *   半屏档可传 `rememberModalBottomSheetState(skipPartiallyExpanded = false)`。
 *
 *   Todo peek 选项：Todo 是现网唯一带半屏档（`skipPartiallyExpanded = false`）的 sheet，
 *   B4 若需保留其 peek 体验，显式传 `rememberModalBottomSheetState(skipPartiallyExpanded
 *   = false)`；若统一全展开则传默认 `true`。
 * @param containerColor 默认 [MaterialTheme.colorScheme.surfaceContainerLow]（配方固化点②）。
 * @param title 可选标题文本。非 null 时渲染 `titleLarge` + 水平 24dp/垂直 8dp padding
 *   （配方固化点③）。传 null 则不渲染标题行（调用方可在 [content] 里自定义标题）。
 * @param titleTrailing 可选的标题行右侧槽（[RowScope]）。非 null 且 [title] 也非 null
 *   时，标题行渲染为 `Row { Text(weight(1f)) + trailing() }`——用于放 close 按钮、
 *   操作 chip 等。null（默认）时标题行退化为纯 `Text`（向后兼容现网 4 个调用点）。
 *   注意：trailing 内容的垂直对齐为 `CenterVertically`；其水平 padding 由 scaffold 统一
 *   在标题行外层加（h=24dp, v=8dp），调用方**不要**再自带外层 padding。
 *
 *   titleLarge 变更注：B4 迁移会让 sheet 标题从现网 `titleMedium`(16sp) 升级到
 *   `titleLarge`(18sp)，属 oracle A4 统一口径。**B4 需视觉走查**确认紧凑 sheet
 *   （尤其 Agent/Model 选择列表）标题不显挤。
 * @param footer 可选底部操作栏。非 null 时在其上方渲染 [HorizontalDivider] + 水平 16dp
 *   padding（配方固化点④）。用于「Done / Compact / 确认」类始终可见的操作按钮。
 * @param bottomContentPadding 底部安全区留白，默认 16dp（配方固化点⑤）。scaffold 在
 *   底部统一加 `Spacer(height = bottomContentPadding)` 实现。见文件头 §inset-note
 *   为什么不用 `WindowInsets.navigationBars`。
 *
 *   ⚠️ 双重 padding：scaffold 已加此底部 Spacer，调用方**不要再在 content/footer 外层
 *   加自己的 bottom padding**，否则双重留白。现有 sheet 各自的 `.padding(bottom = 16.dp)`
 *   在 B4 迁移时**必须删掉**。
 * @param content 内容槽（[ColumnScope]）。调用方控制 item 渲染与滚动；原语统一提供水平
 *   8dp padding。
 *   高度处理：默认 `heightIn(max) + verticalScroll`；当需要 footer 恒可见时，content
 *   槽内**可以**用 `Modifier.weight(1f)`（content 是 ColumnScope，支持 weight）——见
 *   文件头「weight(1f) 策略」与上方 Context 迁移示例。
 */
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    title: String? = null,
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    bottomContentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ③ title 行（仅当提供）。titleLarge + 水平 24dp / 垂直 8dp。
            // titleTrailing 非 null 时把 title 包进 Row，让 trailing 贴右——
            // 否则纯 Text（向后兼容：4 个现网调用点都不传 titleTrailing）。
            if (title != null) {
                if (titleTrailing == null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(
                            horizontal = Dimens.spacing6,  // 24dp
                            vertical = Dimens.spacing2,   // 8dp
                        ),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = Dimens.spacing6,
                                vertical = Dimens.spacing2,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        titleTrailing()
                    }
                }
            }

            // content 槽：原语层统一 8dp 水平 padding，补 ListItem 内置 16dp → 24dp keyline。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.spacing2),
            ) {
                content()
            }

            // ④ footer 行（仅当提供）。上方 divider + 水平 16dp padding。
            if (footer != null) {
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.spacing4),  // 16dp
                ) {
                    footer()
                }
            }

            // ⑤ 底部安全区留白（统一 16dp，见文件头 §inset-note）。
            // 用 Spacer 而非外层 Column padding——让 content/footer 的水平 padding
            // 独立控制，底部留白统一收口。
            Spacer(Modifier.height(bottomContentPadding))
        }
    }
}
