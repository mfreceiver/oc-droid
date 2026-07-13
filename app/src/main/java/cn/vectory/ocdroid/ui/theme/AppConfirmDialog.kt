package cn.vectory.ocdroid.ui.theme

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

// ─────────────────────────────────────────────────────────────────────────────
// WT0 共享原语：确认/破坏性对话框（`AppConfirmDialog`）。
//
// 项目内有 ~6 处手写的破坏性 `AlertDialog`：
//  - chat stop-confirm（`Composer.kt:342`）
//  - message revert confirm（`MessageCard.kt:368`）
//  - archive confirm（`SessionsScreen.kt:358`）
//  - disconnect-workdir（`FilesScreen.kt:416`）
//  - clear-data（`SettingsSections.kt:300`）
//  - host delete（`HostProfilesManagerScreen.kt:1116`）
//
// 它们的共同模式：`AlertDialog` + `title` + 可选 `text(body)` + 两个
// `TextButton`（confirm / dismiss），破坏性 confirm 用
// `MaterialTheme.colorScheme.error` 染色。本组件把这个模式固化下来，避免每
// 个调用点重复样板与漂移（曾有调用点忘记染 error 色）。
//
// **本 lane 只创建原语**——上述 6 个调用点不改，下游 lane（WT1 chat-sheets /
// WT5 settings 等）迁移时再 adopt。
//
// 与 `AppFormDialog` 的边界：本组件用于**纯文本**确认/破坏性对话框（title +
// 可选 body + 两按钮）。若 dialog 内含 `Switch` / 输入字段等可交互控件，请用
// `AppFormDialog`（`AlertDialog.text` 槽会吞 `Switch` 触摸事件，详见
// `AppFormDialog.kt` 文件头）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 统一的确认/破坏性对话框（WT0 公共原语）。
 *
 * 渲染一个 M3 [AlertDialog]，confirm 按钮在 [destructive] = true 时染
 * [MaterialTheme.colorScheme.error]，否则用默认色。详细背景见文件头注释。
 *
 * 用法（破坏性确认）：
 * ```
 * AppConfirmDialog(
 *     title = "Stop generation?",
 *     body = "The current run will be aborted.",
 *     confirmText = "Stop",
 *     onConfirm = onAbort,
 *     dismissText = "Cancel",
 *     onDismiss = { showDialog = false },
 * )
 * ```
 *
 * 用法（非破坏性确认，如普通 OK/Cancel）：
 * ```
 * AppConfirmDialog(
 *     title = "Apply changes?",
 *     confirmText = "Apply",
 *     onConfirm = onApply,
 *     dismissText = "Cancel",
 *     onDismiss = { showDialog = false },
 *     destructive = false,
 * )
 * ```
 *
 * @param title 对话框标题。
 * @param body 可选正文。null 时不渲染 `text` 槽。
 * @param confirmText confirm 按钮文本。
 * @param onConfirm confirm 按钮回调。
 * @param dismissText dismiss 按钮文本。
 * @param onDismiss dismiss 按钮回调。
 * @param destructive true（默认）→ confirm 按钮染 `colorScheme.error`；false →
 *   默认 TextButton 色（用于非破坏性确认）。
 * @param onDismissRequest scrim / 返回键关闭时的回调，默认与 [onDismiss] 同。
 *   若 confirm 触发后需要不同清理逻辑，调用方可以独立提供。
 */
@Composable
fun AppConfirmDialog(
    title: String,
    body: String? = null,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    onDismissRequest: () -> Unit = onDismiss,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = body?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        },
    )
}
