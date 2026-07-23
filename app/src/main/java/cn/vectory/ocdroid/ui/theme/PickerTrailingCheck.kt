package cn.vectory.ocdroid.ui.theme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// ─────────────────────────────────────────────────────────────────────────────
// WT0 共享原语：单选 trailing 选中态（`PickerTrailingCheck`）。
//
// 项目内多处列表做「单选」语义（Agent / Model / Session / Workdir picker …），
// 选中态历史上各处手写：有的用 SmartToy / Memory icon，有的用 Check，size 也
// 不统一。WT0 把现网 chat-local `Composer.kt:PickerTrailingCheck` 提升为公共
// 原语，所有单选 picker 统一为 Check + primary 色。
//
// **始终渲染**（未选中时是同尺寸 `Spacer`）——保证 Check 出现/消失时左侧文本
// 宽度不跳动、各行左对齐齐平。
//
// 选中态标准（与 `docs/specs/ui-style-spec.md` §2 绑定）：
//  - 选中 = `Icons.Filled.Check` + `Dimens.iconSm`(18dp) + `colorScheme.primary`；
//  - 未选中 = 同尺寸 `Spacer`，无 tint；
//  - 无 per-item 选中底色（避免视觉噪点）。
//
// 现状：`ui/chat/Composer.kt:599` 仍保留同名 private 副本（WT1 chat-sheets 迁
// 移时移除并 import 本组件）。**本 lane 不改 Composer.kt**。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 单选 picker 的稳定 trailing 选中态槽（WT0 公共原语）。
 *
 * 选中时渲染 `Icons.Filled.Check`(18dp, [MaterialTheme.colorScheme.primary])；
 * 未选中时渲染同尺寸 `Spacer` 保持行宽稳定。详细背景见文件头注释。
 *
 * 用法（与 M3 `ListItem` 配合）：
 * ```
 * ListItem(
 *     headlineContent = { Text(name) },
 *     trailingContent = { PickerTrailingCheck(selected = isSelected) },
 *     modifier = Modifier.clickable { onSelect(item) },
 * )
 * ```
 *
 * @param selected 是否选中。true → Check+primary；false → 18dp Spacer。
 * @param modifier 应用到内部 `Icon` / `Spacer` 的 modifier（默认 `Modifier`）。
 *   尺寸**强制**为 [Dimens.iconSm]（18dp）——picker trailing 选中态的统一刻度，
 *   调用方不应覆盖；如需其它尺寸请用裸 `Icon` 而非本组件。
 */
@Composable
fun PickerTrailingCheck(selected: Boolean, modifier: Modifier = Modifier) {
    if (selected) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(Dimens.iconSm),
        )
    } else {
        Spacer(modifier.size(Dimens.iconSm))
    }
}
