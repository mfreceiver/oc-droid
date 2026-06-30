package com.yage.opencode_client.ui.chat

import androidx.compose.ui.graphics.Color
import com.yage.opencode_client.ui.theme.LightOpencodeColors
import com.yage.opencode_client.ui.theme.OpencodeColors

// ── Sub-agent per-agent tone coloring ─────────────────────────────────
// R-27: 色源（4 个已知 agent 固定色 + 12 个 hash fallback 调色板）已迁移到
// [OpencodeColors]（明暗双套，深色提亮以保证在 #080808 背景上对比度 ≥ 3:1）。
//
// 这里保留的顶层 `agentTones` / `colorPalette` / 单参 [agentTone] 是 **light
// 默认**入口——非 Composable 上下文（如单测）仍可用，与原行为一致。
// UI 渲染路径请改用接受 [OpencodeColors] 的重载 [agentTone]，传入
// `LocalOpencodeColors.current`（即 `MaterialTheme.opencode`）以随主题切换。

/** Fixed tones for known agent types (mirrors web's agentTones map). Light default. */
internal val agentTones: Map<String, Color> get() = LightOpencodeColors.agentTones

/** Distinct mid-saturation palette for hash-based fallback coloring. Light default. */
internal val colorPalette: List<Color> get() = LightOpencodeColors.colorPalette

/**
 * Returns a consistent [Color] for the given agent [name]: a fixed tone for
 * known agent types (ask/build/docs/plan), or a hash-based palette color for
 * everything else. **Light default**——UI 代码请用 [agentTone] 的 [OpencodeColors]
 * 重载以随明暗主题切换。
 *
 * Uses [Math.floorMod] instead of Kotlin's `%` operator so that negative
 * hash values produced by Int overflow map to valid palette indices.
 */
internal fun agentTone(name: String): Color = agentTone(name, LightOpencodeColors)

/**
 * R-27 theme-aware variant: 从 [oc] 取 agentTones / colorPalette，使返回色随
 * 当前明暗主题切换（深色变体已整体提亮）。索引/hash 逻辑与单参重载完全一致——
 * agent id → tone index 映射保持稳定，只换色源。
 */
internal fun agentTone(name: String, oc: OpencodeColors): Color {
    val lower = name.trim().lowercase()
    oc.agentTones[lower]?.let { return it }
    val palette = oc.colorPalette
    val hash = lower.fold(0) { acc, c -> acc * 31 + c.code }
    return palette[Math.floorMod(hash, palette.size)]
}
