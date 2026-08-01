@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.vectory.ocdroid.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// B2·P2 设计语言地基：统一 ModalBottomSheet 配方（SheetRecipe）。
//
// 项目有 12 个 AppBottomSheet 调用点（Agent/Model/Session 选择、Context 用量、
// Todo、Workdir 控制/切换、Directory picker、Changes diff、Add 菜单、FolderContents 等），
// 各自手写容器色 / sheetState / title 行 / footer / 高度封顶 / 底部 inset，导致视觉
// 与行为漂移（高度反复跳动、双重滚动、scrim 不同步等）。本组件把统一配方固化进默认值，
// 并定义三种 content 槽 recipe（A=单列表 / B=滚动+固定footer / C=短内容），调用方
// 按 recipe 约束组织 content，容器层保证一致。
//
// 固化的共性（scaffold 负责）：
//  1. sheetState：默认 `skipPartiallyExpanded = true`（调用方可覆盖）。
//  2. 容器色：`surfaceContainerLow`（统一 tonal 层级）。
//  3. title 行：`titleLarge` + 水平 24dp / 垂直 8dp padding（仅当 title != null）。
//  4. footer 行：上方 `HorizontalDivider` + 水平 16dp padding（仅当 footer != null）。
//  5. 底部安全区：统一 16dp bottom content padding。
//
// 留给调用方（scaffold 不管）：
//  - content 内容与 item 渲染（按 Recipe A/B/C 组织，见 KDoc）。
//  - 超长内容的高度封顶：由 scaffold 的 `contentMaxHeightFraction`（默认 0.8）统一处理，
//    调用方不再需要自行 `LocalConfiguration` 计算。Recipe C 可传 0f 禁用。
//  - 显隐控制：外层 `if (visible) { AppBottomSheet(...) }`（与现有 sheet 一致）。
//
// content wrapper 权重策略：scaffold 的 content wrapper 统一加 `weight(1f, fill =
// false)`——title / footer / 底部 Spacer 先按自然高度测量，content 占剩余空间（短内
// 容 fill=false 不撑高，长内容 bounded 到剩余高度内，footer 恒可见）。调用方 content
// 槽内**不要**再自带 `weight(1f)`（会与 wrapper 冲突），也**不要**自带本地 `heightIn`
// 封顶（已由 wrapper 的 `contentMaxHeightFraction` 统一施加）。滚动按 Recipe A/B/C
// 组织（见下方 KDoc）。
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
 * Default maximum content height as a fraction of screen height (80%).
 * [AppBottomSheet] applies `Modifier.heightIn(max = screenHeight × fraction)` to the
 * content wrapper Column. Recipe C (short content) callers can pass `0f` to disable
 * the cap and let the sheet size naturally.
 */
const val DefaultSheetContentMaxHeightFraction: Float = 0.8f

/**
 * 统一的 ModalBottomSheet 配方组件，固化容器色 / sheetState / title 行 / footer 行 /
 * 底部 inset / 内容区高度封顶的默认值。
 *
 * ## 三种 content 槽 recipe
 *
 * 所有 12 个 sheet 调用方归入以下三种模式之一。调用方只负责按 recipe 约束组织
 * [content] 槽；容器层配方（颜色、间距、标题、footer、高度封顶）由本组件保证一致。
 *
 * ### Recipe A — 单列表 sheet（pickers、session 切换器）
 * 内容区**恰好一个** `LazyColumn`——它是 content 路径中唯一的垂直可滚动体。
 * 标题经 scaffold 的 `title` 槽固定（不随列表滚动）。
 * 高度由 `contentMaxHeightFraction`（默认 0.8）封顶，LazyColumn 在封顶区内滚动。
 *
 * ```
 * AppBottomSheet(
 *     title = "...",
 *     onDismissRequest = { /*...*/ },
 *     // contentMaxHeightFraction 默认 0.8，无需调用方计算屏幕高度
 * ) {
 *     LazyColumn {
 *         items(...) { /* ListItem */ }
 *     }
 * }
 * ```
 *
 * ### Recipe B — 滚动内容 + 固定 footer
 * 当 sheet 有 [footer]（操作按钮栏）且内容较长时，content 内部用
 * `Modifier.verticalScroll(...)` 的滚动区。scaffold 的 content wrapper 自身用
 * `weight(1f, fill = false)`（见实现注 §content-weight）：title / footer / 底部
 * Spacer 先按自然高度测量，content 占剩余空间（短内容不撑高，长内容 bounded 到
 * 剩余高度内，footer 恒可见——在横屏 / 分屏 / 大字体等矮 sheet 场景也成立）。
 * 调用方 content 只需 `Column(Modifier.verticalScroll(rememberScrollState()))`，
 * **不要**自带 `weight` 或本地 `heightIn`。
 *
 * ```
 * AppBottomSheet(
 *     title = "...",
 *     footer = { /* Done / 确认 */ },
 *     onDismissRequest = { /*...*/ },
 * ) {
 *     Column(
 *         Modifier.verticalScroll(rememberScrollState())
 *     ) { /* items */ }
 * }
 * ```
 *
 * ### Recipe C — 短内容 sheet（无内部滚动）
 * 内容仅有 3-5 个静态元素（如 ListItem），不需要内部滚动。
 * content 路径中无可滚动体。sheet 按自然高度撑开。
 * `contentMaxHeightFraction` 可传 `0f` 禁用封顶。
 *
 * ```
 * AppBottomSheet(
 *     title = "...",
 *     onDismissRequest = { /*...*/ },
 * ) {
 *     ListItem(...)
 *     ListItem(...)
 *     ListItem(...)
 * }
 * ```
 *
 * ## Nested-scroll 契约
 *
 * content 路径中**至多一个**垂直可滚动体：
 * - Recipe A：恰好一个 `LazyColumn`
 * - Recipe B：恰好一个 `Modifier.verticalScroll(...)` 滚动区
 * - Recipe C：零个（短静态内容）；loading / empty / error 等状态分支亦为零个
 *
 * **严禁** `verticalScroll { LazyColumn { ... } }`（双重滚动 → 手势冲突 + scrim 位移不同步）。
 *
 * ## sheetState 策略
 *
 * 默认 `skipPartiallyExpanded = true`（配方固化）。调用方仅在以下情况 hoist 自己的 sheetState：
 * (a) footer 按钮需要 animate-dismiss（`sheetState.hide() → onDismiss`）——避免硬切；
 * (b) 需要 peek / 半展开行为。
 *
 * ## 高度封顶
 *
 * [contentMaxHeightFraction]（默认 [DefaultSheetContentMaxHeightFraction] = 0.8）将
 * 内容区封顶为 `screenHeight × fraction`。Recipe C 可传 `0f` 禁用。
 * 调用方不再需要自行 `LocalConfiguration.current.screenHeightDp` 计算。
 *
 * ## 显隐
 *
 * 本组件不做内部门控——调用方用 `if (visible) { AppBottomSheet(...) }` 控制。
 * `sheetState` 的生命周期与显隐自然绑定，无需额外的 `LaunchedEffect` 关闭动画协调。
 *
 * §inset-note：本组件**故意不**用 `WindowInsets.navigationBars` 吸收底部 inset——
 * 在本 App 的 AppCompat 主题下，Compose 的 `navigationBars()` 解析为 0（DecorView
 * 先消费了 inset，见 MainActivity.kt / AppShell.kt §bug-6.4 注释）。现有 sheet 统一用
 * `padding(bottom = 16.dp)` 作为底部留白，本组件对齐这一做法（参数化为
 * [bottomContentPadding]，默认 16dp，由 scaffold 在底部统一加 Spacer 实现）。若未来
 * 切到 EdgeToEdge 主题，再在此处接入 `windowInsetsPadding`。
 *
 * ⚠️ 双重 padding 警示：scaffold 已在底部加 `Spacer(height = bottomContentPadding)`
 * 留白，调用方**不要再在 content/footer 外层加自己的 bottom padding**，否则双重留白。
 *
 * @param onDismissRequest scrim/swipe dismiss + 程序化关闭时回调（与 [ModalBottomSheet] 一致）。
 * @param modifier 应用到 [ModalBottomSheet] 根的 modifier。
 * @param sheetState 默认 `skipPartiallyExpanded = true`。调用方如需半屏档可传
 *   `rememberModalBottomSheetState(skipPartiallyExpanded = false)`。
 *   仅在 footer 需 animate-dismiss 或需要 peek 时才 hoist（见上方「sheetState 策略」）。
 * @param containerColor 默认 [MaterialTheme.colorScheme.surfaceContainerLow]。
 * @param title 可选标题文本。非 null 时渲染 `titleLarge` + 水平 24dp/垂直 8dp padding。
 *   Recipe A 中标题固定不随列表滚动（scaffold title 槽）。
 * @param titleTrailing 可选的标题行右侧槽（[RowScope]）。非 null 且 [title] 也非 null
 *   时，标题行渲染为 `Row { Text(weight(1f)) + trailing() }`——用于放 close 按钮等。
 *   trailing 内容垂直对齐 `CenterVertically`；水平 padding 由 scaffold 统一处理。
 * @param footer 可选底部操作栏。非 null 时在其上方渲染 [HorizontalDivider] + 水平 16dp
 *   padding。用于「Done / 确认」类始终可见的操作按钮（Recipe B 的 footer 槽）。
 * @param bottomContentPadding 底部安全区留白，默认 16dp。scaffold 在底部统一加
 *   `Spacer(height = bottomContentPadding)` 实现。见文件头 §inset-note。
 *
 *   ⚠️ 双重 padding：scaffold 已加此底部 Spacer，调用方**不要再在 content/footer 外层
 *   加自己的 bottom padding**，否则双重留白。
 * @param contentMaxHeightFraction 内容区最大高度占屏幕高度的比例（默认 0.8 = 80%）。
 *   scaffold 内部对内容 wrapper Column 施加 `weight(1f, fill = false)` + `heightIn(max
 *   = screenHeight × fraction)`：title / footer / 底部 Spacer 先按自然高度测量，content
 *   占剩余空间但不超过屏高 fraction（保证 footer 在横屏/分屏/大字体下恒可见）。Recipe C
 *   可传 `0f` 禁用封顶。非法值（负数 / NaN / >1）会被 clamp 到 `0f..1f`。
 * @param content 内容槽（[ColumnScope]）。调用方按上述 recipe 约束组织内容；
 *   原语统一提供水平 8dp padding（`Dimens.spacing2`）。
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
    contentMaxHeightFraction: Float = DefaultSheetContentMaxHeightFraction,
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
            // §content-weight: wrapper 用 `weight(1f, fill = false)` 让 title / footer /
            // 底部 Spacer 先按自然高度测量，content 占剩余空间——短内容不撑高
            // （fill=false），长内容 bounded 到剩余高度内，footer 恒可见（横屏 / 分屏 /
            // 大字体等矮 sheet 场景也成立）。叠加 heightIn(max = 屏高 × fraction) 作
            // 绝对上限（避免 sheet 全展时 content 占满几乎整屏挤压 footer 的极端情况）。
            // contentMaxHeightFraction ≤ 0f 禁用封顶（Recipe C 自然高度）；NaN 当作 0f
            // 处理（NaN.coerceIn 仍是 NaN，故显式归零）；负数 / >1 clamp 到 0f..1f。
            val clampedFraction =
                if (contentMaxHeightFraction.isNaN()) 0f
                else contentMaxHeightFraction.coerceIn(0f, 1f)
            val contentHeightModifier = if (clampedFraction > 0f) {
                val screenHeightDp = LocalConfiguration.current.screenHeightDp
                Modifier.heightIn(max = (screenHeightDp * clampedFraction).dp)
            } else {
                Modifier
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(horizontal = Dimens.spacing2)
                    .then(contentHeightModifier),
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
