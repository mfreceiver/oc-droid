package com.yage.opencode_client.ui.chat

import androidx.compose.ui.graphics.Color

// ── Sub-agent per-agent tone coloring ─────────────────────────────────

/** Fixed tones for known agent types (mirrors web's agentTones map). */
internal val agentTones = mapOf(
    "ask" to Color(0xFF6CB4EE),
    "build" to Color(0xFFE8A838),
    "docs" to Color(0xFF66BB6A),
    "plan" to Color(0xFFAB7FD0)
)

/** Distinct mid-saturation palette for hash-based fallback coloring. */
internal val colorPalette = listOf(
    Color(0xFF5B9BD5), // steel blue
    Color(0xFFED7D31), // orange
    Color(0xFF70AD47), // green
    Color(0xFF9B72CF), // purple
    Color(0xFFE06666), // coral
    Color(0xFF4ECDC4), // teal
    Color(0xFFF4B942), // golden
    Color(0xFF6C8EBF), // slate blue
    Color(0xFFCC6B99), // rose
    Color(0xFF7EC8A0), // mint
    Color(0xFFD4A76A), // tan
    Color(0xFF8FBCBB)  // dark cyan
)

/**
 * Returns a consistent [Color] for the given agent [name]: a fixed tone for
 * known agent types (ask/build/docs/plan), or a hash-based palette color for
 * everything else.
 *
 * Uses [Math.floorMod] instead of Kotlin's `%` operator so that negative
 * hash values produced by Int overflow map to valid palette indices.
 */
internal fun agentTone(name: String): Color {
    val lower = name.trim().lowercase()
    agentTones[lower]?.let { return it }
    val hash = lower.fold(0) { acc, c -> acc * 31 + c.code }
    return colorPalette[Math.floorMod(hash, colorPalette.size)]
}
