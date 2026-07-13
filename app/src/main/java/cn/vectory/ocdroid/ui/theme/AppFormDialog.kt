package cn.vectory.ocdroid.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

// ─────────────────────────────────────────────────────────────────────────────
// WT0 共享原语：表单对话框（`AppFormDialog`）。
//
// 为什么不用 `AlertDialog`：M3 `AlertDialog` 的 `text` 槽在内部塞了一个带
// scroll/layout 的容器，**会吞掉 `Switch` / 输入控件的触摸事件**——表现为
// 「Switch 点不动」。这是项目内 settings/model management 历史上踩过的坑（见
// `ui/settings/ModelManagementSection.kt:128-140` 的注释）。
//
// 解决方案（已被现网验证）：改用 `BasicAlertDialog` + `Surface` 自己拼容器——
// `BasicAlertDialog` 把内容完全交给调用方，触摸路由恢复正常。容器样式对齐
// `AlertDialog` 的默认外观（`AlertDialogDefaults.shape` / `containerColor` /
// `TonalElevation`）以保持视觉一致。本组件把这个验证过的模式固化下来。
//
// 现网 `ModelManagementSection.kt:ModelManagementDialog` 是参考实现——本组件
// 提炼其容器骨架，调用方只负责 `content`（表单控件）与可选的 title / 按钮。
//
// **本 lane 只创建原语**——上述 ModelManagementDialog 不改，WT5 settings 迁
// 移时再 adopt。
//
// 与 `AppConfirmDialog` 的边界：纯文本 + 两按钮的破坏性确认用
// `AppConfirmDialog`（基于 `AlertDialog`，更轻）；含 `Switch` / 输入字段等
// 可交互控件的表单 dialog 才用本组件（基于 `BasicAlertDialog` + `Surface`）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 统一的表单对话框（WT0 公共原语）。
 *
 * 基于 [BasicAlertDialog] + [Surface]（不基于 [androidx.compose.material3.AlertDialog]，
 * 因为 AlertDialog 的 `text` 槽会吞 `Switch` 触摸事件——见文件头注释）。容器
 * 样式对齐 [AlertDialogDefaults] 的 `shape` / `containerColor` / `TonalElevation`，
 * 内层 `Column` 带 `padding(Dimens.spacing6)` + `verticalScroll` + 高度封顶
 * （`screenHeight * 0.85f`），保证小屏 / 横屏 / 分屏下 confirm 按钮始终可达。
 *
 * 用法：
 * ```
 * AppFormDialog(
 *     onDismissRequest = { showDialog = false },
 *     title = "Model management",
 *     confirmButton = {
 *         TextButton(onClick = { showDialog = false }) { Text("Done") }
 *     },
 * ) {
 *     // 任意的 Switch / TextField / 列表……触摸事件正常派发
 *     Switch(checked = enabled, onCheckedChange = onToggle)
 * }
 * ```
 *
 * **布局说明**：
 *  - 整个 `Column`（含 title / content / buttons）一起 `verticalScroll`，溢出
 *    时整体滚动；`heightIn(max)` 封顶避免占满整屏。
 *  - 若需要 title / buttons 固定、只中间内容滚动（如现网 ModelManagementDialog
 *    的做法），调用方可在 [content] 槽内自行放一个 `Column(Modifier.weight(1f)
 *    .verticalScroll(...))`——但注意：在已 `verticalScroll` 的父 `Column` 里
 *    `weight(1f)` 不生效，需调用方按需调整本组件用法或提 issue。
 *
 * @param onDismissRequest scrim / 返回键 / 点击外部关闭时的回调。
 * @param title 可选标题。null 时不渲染标题行。
 * @param content 表单内容槽（[ColumnScope]）。任意的 `Switch` / `TextField` /
 *   `ListItem` 等都可放入——触摸事件由 [BasicAlertDialog] 透传，不会被吞。
 * @param confirmButton 可选的 confirm 按钮槽（右对齐）。null 时不渲染。
 * @param dismissButton 可选的 dismiss 按钮槽（confirm 左侧）。null 时不渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFormDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
    confirmButton: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            // usePlatformDefaultWidth = false + Surface 的 fillMaxWidth + 水平
            // 16dp padding 让 dialog 在大屏 / 小屏都拿到一致的宽度与边距（现网
            // ModelManagementDialog 的验证过的做法）。
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.spacing4)  // 16dp 屏幕侧边距
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f),
            shape = AlertDialogDefaults.shape,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            Column(
                modifier = Modifier
                    .padding(Dimens.spacing6)  // 24dp 内 padding（与 AppBottomSheet title 行对齐）
                    .verticalScroll(rememberScrollState()),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacing4))
                }

                content()

                if (confirmButton != null || dismissButton != null) {
                    Spacer(modifier = Modifier.height(Dimens.spacing4))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (dismissButton != null) {
                            dismissButton()
                        }
                        if (dismissButton != null && confirmButton != null) {
                            Spacer(modifier = Modifier.width(Dimens.spacing2))
                        }
                        confirmButton?.invoke()
                    }
                }
            }
        }
    }
}
