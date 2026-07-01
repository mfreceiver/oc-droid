package cn.vectory.ocdroid.ui.chat

import androidx.compose.ui.graphics.Color
import cn.vectory.ocdroid.ui.theme.LightOpencodeColors
import cn.vectory.ocdroid.ui.theme.OpencodeColors

// ── Sub-agent per-agent tone coloring ─────────────────────────────────
// R-27: 色源（统一 16 色 hash 调色板）已迁移到 [OpencodeColors]（明暗双套，
// 深色提亮以保证在 #080808 背景上对比度 ≥ 3:1）。
//
// **统一 16 色源 [OpencodeColors.agentPalette]**：作为 agent name hash /
// workdir hash 的唯一色源。所有 hash-based 配色（[agentTone] / [workdirTone]）
// 都从这 16 色取值，保证两类哈希共用同一调色板基础。
//
// 单参 [agentTone] 是 **light 默认**入口——非 Composable 上下文（如单测）仍
// 可用，与原行为一致。UI 渲染路径请改用接受 [OpencodeColors] 的重载 [agentTone]，
// 传入 `LocalOpencodeColors.current`（即 `MaterialTheme.opencode`）以随主题切换。

/**
 * Returns a consistent [Color] for the given agent [name] by hashing the name
 * into the unified 16-color [OpencodeColors.agentPalette]. **Light default**
 * ——UI 代码请用 [agentTone] 的 [OpencodeColors] 重载以随明暗主题切换。
 *
 * Uses [Math.floorMod] instead of Kotlin's `%` operator so that negative
 * hash values produced by Int overflow map to valid palette indices.
 */
internal fun agentTone(name: String): Color = agentTone(name, LightOpencodeColors)

/**
 * Theme-aware variant: 直接 hash name → [oc.agentPalette]（统一 16 色源）。
 * 返回色随当前明暗主题切换（深色变体已整体提亮）。所有 agent 名（含
 * ask/build/docs/plan）统一走 hash，保留确定性即可。
 */
internal fun agentTone(name: String, oc: OpencodeColors): Color {
    val lower = name.trim().lowercase()
    val palette = oc.agentPalette
    val hash = lower.fold(0) { acc, c -> acc * 31 + c.code }
    return palette[Math.floorMod(hash, palette.size)]
}

/**
 * Returns a consistent [Color] for the given workdir/session [directory] by
 * hashing the path into the unified 16-color [oc.agentPalette]——与 [agentTone]
 * 共用同一调色板，使 agent name 配色与 session/workdir 配色落在同一 16 色
 * 基础上。用于 workdir 标识 / session chip 等「按目录区分」的视觉场景。
 *
 * 算法与 [agentTone] 完全一致：trim+lowercase 后做字符 hash，再用
 * [Math.floorMod] 映射到 palette 索引，保证同一 directory 永远同色。
 */
internal fun workdirTone(directory: String, oc: OpencodeColors): Color {
    val lower = directory.trim().lowercase()
    val palette = oc.agentPalette
    val hash = lower.fold(0) { acc, c -> acc * 31 + c.code }
    return palette[Math.floorMod(hash, palette.size)]
}
