package cn.vectory.ocdroid.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// ─────────────────────────────────────────────────────────────────────────────
// WT0 共享原语：section header（`AppSectionHeader`）。
//
// 项目内 section header 散落且不一致：
//  - `ui/settings/SettingsSections.kt:362` `SectionHeader`：`titleMedium`(16sp) +
//    `Spacer(8.dp)`，无显式 color（继承 onSurface）。
//  - `ui/chat/SessionPickerSheet.kt:269` private `SectionHeader`：`titleSmall`
//    (14sp) + `onSurfaceVariant` + `padding(h=24dp, v=8dp)`。
//
// 两者字号、颜色、padding 都不同——WT0 把它们统一为公共原语。规范选择
// `titleSmall + onSurfaceVariant + 16dp/8dp`：
//  - **titleSmall（14sp Medium）**：section header 是「分组标签」而非「卡片
//    标题」，视觉权重应低于内容卡片。`titleMedium`(16sp) 在密列表里太抢，且
//    与卡片内 `titleMedium` 同级会扁平化层级。`titleSmall` 是 M3 spec 对
//    "overline / group label" 场景的推荐档（M3 spec 把 group label 列为
//    titleSmall 的典型用途之一）。
//  - **onSurfaceVariant**：分组标签是「次级信息」（主信息是分组下的卡片
//    内容），用 variant 让标签自然下沉。
//  - **padding 16dp / 8dp**：与 M3 `ListItem` 默认水平 padding（16dp）对齐，
//    让 header 与下方列表项左对齐；8dp 垂直给标签留呼吸又不至于推开太远。
//
// 现状：上述两个 private `SectionHeader` 仍保留（WT1 chat-sheets / WT5
// settings 迁移时移除并 import 本组件）。**本 lane 不改这两个文件**。
//
// 可选 `trailing` 槽：部分 section header 右侧带一个动作（如「全部展开」
// 「管理…」按钮）。提供 RowScope slot，调用方按需传入；未传时退化为纯 Text，
// 与现有 private 实现的视觉一致。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 统一的 section header（WT0 公共原语）。
 *
 * 渲染 `MaterialTheme.typography.titleSmall` + `colorScheme.onSurfaceVariant` +
 * `padding(horizontal = Dimens.spacing4 /*16dp*/, vertical = Dimens.spacing2 /*8dp*/)`。
 * 可选 `trailing` 槽用于在右侧放一个动作（如「管理」按钮）。详细背景与样式
 * 选择见文件头注释与 `docs/specs/ui-style-spec.md` §2。
 *
 * 用法（无 trailing）：
 * ```
 * AppSectionHeader(text = "Recent sessions")
 * ```
 *
 * 用法（带 trailing 动作）：
 * ```
 * AppSectionHeader(
 *     text = "Models",
 *     trailing = {
 *         TextButton(onClick = onManage) { Text("Manage") }
 *     },
 * )
 * ```
 *
 * @param text 标题文本。
 * @param modifier 应用到外层（`Text` 或 `Row`）的 modifier。
 * @param trailing 可选的右侧动作槽（[RowScope]）。null 时退化为纯 `Text`，
 *   与现有 private section header 视觉一致。
 */
@Composable
fun AppSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    if (trailing == null) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(
                horizontal = Dimens.spacing4,  // 16dp — 与 ListItem 默认水平 padding 对齐
                vertical = Dimens.spacing2,    // 8dp
            ),
        )
    } else {
        Row(
            modifier = modifier.padding(
                horizontal = Dimens.spacing4,
                vertical = Dimens.spacing2,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
    }
}
