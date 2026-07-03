package cn.vectory.ocdroid.ui.chat

import androidx.compose.ui.graphics.Color
import cn.vectory.ocdroid.ui.theme.SemanticColors

// ── Sub-agent per-agent tone coloring ─────────────────────────────────
// 统一 16 色源 [SemanticColors.agentPalette]：作为 agent name hash / workdir hash
// 的唯一色源。所有 hash-based 配色（[agentTone] / [workdirTone]）都从这 16 色取值。
//
// Phase 2 改造：原 agentTone/workdirTone 接受 OpencodeColors 参数以随明暗主题切换
// 调色板。OpencodeColors 层已拆除——agentPalette 现为 SemanticColors 固定常量（单一
// 值，R-27 明暗双套合并，用户已接受深色对比度回归）。故函数不再需要主题参数。
//
// Uses [Math.floorMod] instead of Kotlin's `%` operator so that negative
// hash values produced by Int overflow map to valid palette indices.

/**
 * Returns a consistent [Color] for the given agent [name] by hashing the name
 * into the unified 16-color [SemanticColors.agentPalette].
 *
 * 确定性：同一 name 永远同色（hash + floorMod）。
 */
internal fun agentTone(name: String): Color {
    val lower = name.trim().lowercase()
    val palette = SemanticColors.agentPalette
    val hash = lower.fold(0) { acc, c -> acc * 31 + c.code }
    return palette[Math.floorMod(hash, palette.size)]
}

/**
 * Returns a consistent [Color] for the given workdir/session [directory] by
 * hashing the path into [SemanticColors.agentPalette]——与 [agentTone] 共用同一
 * 调色板，使 agent name 配色与 session/workdir 配色落在同一 16 色基础上。
 *
 * 算法与 [agentTone] 完全一致：trim+lowercase 后字符 hash，再用 [Math.floorMod]
 * 映射到 palette 索引，保证同一 directory 永远同色。
 */
internal fun workdirTone(directory: String): Color {
    val lower = directory.trim().lowercase()
    val palette = SemanticColors.agentPalette
    val hash = lower.fold(0) { acc, c -> acc * 31 + c.code }
    return palette[Math.floorMod(hash, palette.size)]
}
