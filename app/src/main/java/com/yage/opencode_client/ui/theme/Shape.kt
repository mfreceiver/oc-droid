package com.yage.opencode_client.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// R-25 Shape 系统：项目内 33+ 处 `RoundedCornerShape(4/6/8/10/12.dp)` 散落
// 无规范。这里集中定义 5 档圆角 token，通过 [AppShapes] 接入 M3
// `MaterialTheme.shapes`，让新代码用 `MaterialTheme.shapes.small` 等语义化
// 入口而非裸 dp 字面量。本 lane **不**强制批量迁移现有组件（风险大且与本 lane
// 范围不符），仅提供规范 + 下发；高频组件迁移留作 R-25 follow-up（见报告）。
//
// 档位选定依据：扫全代码库 35 处 RoundedCornerShape 用量统计——
//   4.dp  -> extraSmall（极少数 chip / 小标签 clip）
//   6.dp  -> small     （Tool card 主流圆角，~20 处）
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
