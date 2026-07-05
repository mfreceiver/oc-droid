package cn.vectory.ocdroid.ui.chat

import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.util.DebugLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Shared render utilities for the chat surface ─────────────────────────
// Pure (non-@Composable) helpers + width constants consumed by the message
// list, the row dispatcher, and the various card composables. Kept here so
// every card file can reference them via same-package resolution without
// touching ChatMessageContent.kt.

/**
 * Width cap applied to every unified collapsible card (reasoning / tool / file
 * edit / sub-agent / merged tool-calls row). Keeps the chat readable on wide
 * screens by left-aligning the agent's "side-channels" instead of stretching
 * them wall-to-wall. Matches iOS's compact card width.
 */
internal val MAX_CARD_WIDTH = 220.dp

/**
 * Formats an epoch-millis timestamp as `HH:mm` (24h, locale-neutral numerics).
 * Used for the per-message footer caption that replaced the model label.
 */
internal fun formatHm(epochMs: Long): String = try {
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
} catch (e: Exception) {
    DebugLog.w("ChatRenderUtils", "formatHm failed: ${e.message}")
    ""
}

/**
 * Inserts a zero-width space (U+200B) after every `/` so that long file paths
 * (which contain no whitespace) become wrappable inside a [Text] with
 * `softWrap = true`. The ZWSP is invisible, so the rendered path looks
 * identical but can break across lines at path separators. Used by the
 * explored-file rows (#8) where full paths used to be clipped.
 */
internal fun String.wrappablePath(): String = replace("/", "/\u200B")
