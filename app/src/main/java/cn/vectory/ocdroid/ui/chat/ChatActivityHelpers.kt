package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SessionStatus

/**
 * §R-19 Sprint 2 #7(b): pure (non-@Composable) helpers that derive the
 * "what is the current session doing right now" capsule text shown above the
 * chat transcript. Extracted verbatim from the bottom of `ChatScreen.kt`,
 * where co-locating these JVM-testable helpers inside a `@Composable`-heavy
 * file hid them from kover unit-test coverage (ChatScreenKt is excluded from
 * the coverage report — see [PickerProviderFilter] for the same pattern).
 *
 * All four functions are pure: they take only their explicit parameters, hold
 * no Composable / view-model / state capture, and produce deterministic
 * output. They are called from a `remember(...)` block in ChatScreen that
 * tracks the inputs (chat.currentSessionId, curSessionStatus, visible
 * messages, partsByMessage, streamingReasoningPart, streamingPartTexts).
 *
 * Decision order at runtime (see [bestSessionActivityText]):
 *   1. explicit status.message (server-reported status text) wins outright;
 *   2. else the most recent *running* tool part for this session → tool label;
 *   3. else a streaming reasoning part for this session → "Thinking - <topic>";
 *   4. else the last part of the session's most recent message → label;
 *   5. else "Retrying" (status.isRetry) or "Thinking" (generic fallback).
 */

internal data class CurrentSessionActivity(
    val text: String,
    val startedAtMillis: Long?,
)

internal fun currentSessionActivity(
    sessionId: String?,
    status: SessionStatus?,
    messages: List<Message>,
    partsByMessage: Map<String, List<Part>>,
    streamingReasoningPart: Part?,
    streamingPartTexts: Map<String, String>,
): CurrentSessionActivity? {
    val sid = sessionId ?: return null
    val startedAt = messages.lastOrNull { it.sessionId == sid && it.isUser }?.time?.created
    val text = bestSessionActivityText(sid, status, messages, partsByMessage, streamingReasoningPart, streamingPartTexts)
    return CurrentSessionActivity(text = text, startedAtMillis = startedAt)
}

internal fun bestSessionActivityText(
    sessionId: String,
    status: SessionStatus?,
    messages: List<Message>,
    partsByMessage: Map<String, List<Part>>,
    streamingReasoningPart: Part?,
    streamingPartTexts: Map<String, String>,
): String {
    status?.message?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    messages.asReversed().forEach { message ->
        if (message.sessionId != sessionId) return@forEach
        (partsByMessage[message.id] ?: emptyList()).asReversed()
            .firstOrNull { it.isTool && it.stateDisplay == "running" }
            ?.let { part -> formatStatusFromPart(part)?.let { return it } }
    }

    if (streamingReasoningPart?.sessionId == sessionId) {
        val key = streamingReasoningPart.id
        return formatThinkingFromReasoningText(streamingPartTexts[key].orEmpty())
    }

    messages.asReversed().forEach { message ->
        if (message.sessionId != sessionId) return@forEach
        (partsByMessage[message.id] ?: emptyList()).asReversed().firstOrNull()?.let { part ->
            formatStatusFromPart(part)?.let { return it }
        }
    }

    return status?.takeIf { it.isRetry }?.let { "Retrying" } ?: "Thinking"
}

internal fun formatStatusFromPart(part: Part): String? {
    if (part.isTool) {
        val base = when (part.tool) {
            "task" -> "Delegating"
            "todowrite", "todoread" -> "Planning"
            "read" -> "Gathering context"
            "list", "grep", "glob" -> "Searching codebase"
            "webfetch" -> "Searching web"
            "edit", "write" -> "Making edits"
            "bash" -> "Running commands"
            else -> null
        }
        val topic = (part.toolReason ?: part.toolInputSummary)?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            base != null && topic != null -> "$base - $topic"
            base != null -> base
            else -> null
        }
    }

    if (part.isReasoning) return formatThinkingFromReasoningText(part.text.orEmpty())
    if (part.isText) return "Gathering thoughts"
    return null
}

internal fun formatThinkingFromReasoningText(text: String): String {
    val topic = Regex("^\\*\\*([^*]+)\\*\\*").find(text.trim())?.groupValues?.getOrNull(1)?.trim()
    return if (!topic.isNullOrEmpty()) "Thinking - $topic" else "Thinking"
}
