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

/**
 * D4 anti-collision variant: 与 [agentTone] 不同，未知 agent 不再用 hash 落色，
 * 而是**始终走距离感知 maximin 选色**——对每个 palette 候选取它到最近占用色的
 * 距离，选该最小距离最大的候选（即「离所有占用色都尽量远」的候选），最大化相邻
 * agent 的视觉区分度。
 *
 * - 已知 agent（[oc.agentTones]）固定色，短路返回，保证跨会话稳定。
 * - 会话内已分配过的未知 agent（[assigned]）复用其首派发色，保证「同一 agent 名
 *   永远同色」。
 * - **占用色全集 = [oc.agentTones].values + [assigned].values**。两者都纳入距离
 *   约束，使贪心自动避开 agentTones 的视觉近邻色（如 ask 浅蓝 #6CB4EE vs
 *   palette[0] 钢蓝 #5B9BD5、docs 绿 #66BB6A vs palette[2] 绿 #70AD47 等），真正
 *   实现「相邻 agent 不撞色」。glmer 评审确认此取舍：丢失 palette 顺序稳定性
 *   换取视觉区分度（D4 注释已说明切换会话会重新分配，顺序稳定性本就非目标）。
 * - [assigned] 由调用方（会话级 `mutableStateMapOf`）维护并透传；本函数只读它，
 *   写回由调用方负责（避免在纯函数里产生副作用）。
 *
 * 跨会话稳定性来源：agent 名 → tone 的映射在同一会话内确定；切换会话时 [assigned]
 * 清空，新一轮 maximin 重新分配（相邻不撞色优先于跨会话一致）。
 */
internal fun agentTone(name: String, oc: OpencodeColors, assigned: Map<String, Color>): Color {
    val lower = name.trim().lowercase()
    // 已知 agent 固定色，不参与贪心
    oc.agentTones[lower]?.let { return it }
    // 会话内已分配过，复用（保证「同一 agent 名永远同色」）
    assigned[lower]?.let { return it }
    val palette = oc.colorPalette
    // 占用色全集：已知 agent 固定色 + 本会话已分配色。两者都纳入距离约束，
    // 使贪心自动避开 agentTones 的视觉近邻色（如 ask 浅蓝 vs palette[0] 钢蓝），
    // 真正实现「相邻 agent 不撞色」。glmer 评审确认此取舍：丢失 palette 顺序稳定性
    // 换取视觉区分度（D4 注释已说明切换会话重新分配，顺序稳定性本就非目标）。
    val occupied = (oc.agentTones.values.asSequence() + assigned.values.asSequence()).distinct().toList()
    // maximin：对每个 palette 候选 c 取它到最近占用色 u 的距离，选该最小距离最大的 c，
    // 即「离所有占用色都尽量远」的候选。
    return palette.maxBy { c ->
        occupied.minOfOrNull { u -> colorDistance(c, u) } ?: Float.MAX_VALUE
    }
}

/**
 * Squared Euclidean distance in RGB space (no sqrt needed for comparison).
 * Used by [agentTone] 的贪心最近邻回退来量化两个颜色的视觉差异。
 */
private fun colorDistance(a: Color, b: Color): Float {
    val dr = a.red - b.red
    val dg = a.green - b.green
    val db = a.blue - b.blue
    return dr * dr + dg * dg + db * db
}
