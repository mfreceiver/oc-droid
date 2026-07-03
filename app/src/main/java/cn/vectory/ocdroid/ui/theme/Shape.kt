package cn.vectory.ocdroid.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// R-25 Shape 系统：项目内原 32+ 处 `RoundedCornerShape(4/6/8/10/12.dp)` 散落
// 无规范。这里集中定义 5 档圆角 token，通过 [AppShapes] 接入 M3
// `MaterialTheme.shapes`。Phase 4 已完成全部 33 处均匀圆角的批量迁移到
// `MaterialTheme.shapes.<slot>`；保留的 4 处 `RoundedCornerShape(50)`（胶囊/pill
// 形，非 token 候选）直接用字面量。
//
// 档位选定依据：扫全代码库用量统计——
//   4.dp  -> extraSmall（极少数 chip / 小标签 clip）
//   6.dp  -> small     （Tool card 主流圆角，~16 处）
//   8.dp  -> medium    （通用卡片 / Surface / TopBar 元素）
//   10.dp -> large     （ChatScreen 卡片、ChatInputBar 输入框）
//   12.dp -> extraLarge（QuestionCard / Attachment chip / 大圆角容器）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * App-wide Material 3 [Shapes]。在 [OpenCodeTheme] 的 `MaterialTheme(shapes = ...)`
 * 下发，新代码优先用 `MaterialTheme.shapes.<slot>` 而非 `RoundedCornerShape(dp)`。
 *
 * 档位值固定（不随 window size class 变），与现有散落字面量保持视觉一致。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)
