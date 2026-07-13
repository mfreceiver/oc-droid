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
// 与 `AppConfirmDialog` 的边界：纯文本 + 两按钮的破坏性确认用
// `AppConfirmDialog`（基于 `AlertDialog`，更轻）；含 `Switch` / 输入字段等
// 可交互控件的表单 dialog 才用本组件（基于 `BasicAlertDialog` + `Surface`）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 统一的表单对话框（WT0 公共原语）。
 *
 * 基于 [BasicAlertDialog] + [Surface]（不基于 [androidx.compose.material3.AlertDialog]，
 * 因为 AlertDialog 的 `text` 槽会吞 `Switch` 触摸事件——见文件头注释）。容器
 * 样式对齐 [AlertDialogDefaults] 的 `shape` / `containerColor` / `TonalElevation`；
 * `Surface` 用 `heightIn(max = screenHeight * 0.85f)` 封顶避免占满整屏，小屏 /
 * 横屏 / 分屏下 confirm 按钮始终可达。
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
 * **布局结构（§review-AB 固定 footer 修复）**：
 *  - 外层 `Surface`（`heightIn(max = screenHeight*0.85f)` 封顶）内是一个
 *    **不滚动**的外层 `Column`，依次为：[title] → 滚动的 [content] → 按钮行。
 *  - [title] 与 confirm/dismiss 按钮恒可见（钉在外层 Column 头尾）；只有
 *    [content] 包在 `Column(Modifier.weight(1f, fill = false).verticalScroll(...))` 里滚动。
 *  - 把按钮挪出滚动区后，`weight(1f)` 重新合法（外层 Column 无 `verticalScroll`）；
 *    长表单（Host 编辑器 mTLS+Advanced、Model 管理）的 Save/Done/Test 按钮不再
 *    被滚出视野，恢复了固定 action bar 的体验。
 *  - **§glm-2 🟡-1（review-round-2 fix）**：`fill = false` 是关键——`weight(1f)`
 *    默认 `fill = true` 会把 content Column **强制撑满**其分配高度，使短表单
 *    （如 Model 管理 provider 较少时）也渲染到 ~85% 屏高、content 与钉底按钮间
 *    出现大块空白。`fill = false` 把分配高度降级为**上限**而非强制填充：短表单
 *    → content 取自然高度 → Surface wrap → dialog 紧凑；长表单 → content 触顶
 *    并 `verticalScroll` 滚动 → footer 仍钉底。footer 的空间在外层 Column 的
 *    weight 算法中始终被预留（content 是唯一的 weighted child），故不会滚走。
 *
 * @param onDismissRequest scrim / 返回键 / 点击外部关闭时的回调。
 * @param title 可选标题。null 时不渲染标题行。
 * @param confirmButton 可选的 confirm 按钮槽（右对齐）。null 时不渲染。
 * @param dismissButton 可选的 dismiss 按钮槽（confirm 左侧）。null 时不渲染。
 * @param content 表单内容槽（[ColumnScope]），放在最后一个参数以支持尾随 lambda。
 *   任意的 `Switch` / `TextField` / `ListItem` 等都可放入——触摸事件由
 *   [BasicAlertDialog] 透传，不会被吞。内容会单独滚动；title 与按钮保持固定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFormDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    confirmButton: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
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
                // §review-AB: 高度封顶挂在 Surface 上（外层）——Surface 是
                // heightIn(max) 的边界，内层 non-scrolling Column 据此拿到
                // bounded 高度，content 的 weight(1f) 才能正常解析。
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.85f),
            shape = AlertDialogDefaults.shape,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            // §review-AB: 外层 Column **不**滚动——title + content + buttons
            // 三段平铺，content 用 weight(1f) 吃掉剩余高度并自行滚动，使
            // title 与按钮行恒可见（修复长表单上 Save/Done 被滚出视野的回归）。
            Column(
                modifier = Modifier.padding(Dimens.spacing6),  // 24dp 内 padding（与 AppBottomSheet title 行对齐）
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacing4))
                }

                // 滚动区——仅中间 content 滚动。weight(1f, fill = false) 在外层
                // 非滚动 Column 下合法：把 title 与按钮之间的剩余高度作为**上限**
                // 而非强制填充（见文件头 §glm-2 🟡-1）——短表单保持紧凑，长表单
                // 触顶后自行滚动；footer 空间由 weight 算法预留故恒可见。
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }

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
