package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus

/**
 * §R-17 M5: context-usage summary for the most recent assistant message, shown
 * as a ring in the top bar. Moved out of the legacy `AppState.ContextUsage`
 * nested class now that AppState no longer carries the message/provider mirror
 * fields this computation reads. Pure value type — produced by
 * [computeContextUsage].
 */
data class ContextUsage(
    val percentage: Float,
    val totalTokens: Int,
    val contextLimit: Int,
    val providerId: String? = null,
    val modelId: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedReadTokens: Int? = null,
    val cachedWriteTokens: Int? = null,
    val cost: Double? = null
)

/**
 * §R-17 M5: pure helpers extracted verbatim from the former AppState derived
 * getters (visibleMessages / currentSession / currentHostProfile /
 * currentSessionStatus / contextUsage). These compute the cross-slice derived
 * views the UI needs directly from the authoritative slice values, so the
 * getters (which depended on the now-removed AppState mirror fields) could be
 * deleted without behaviour change.
 */

/** Finds the currently-selected session by id, or null. */
fun currentSession(sessions: List<Session>, currentSessionId: String?): Session? =
    currentSessionId?.let { id -> sessions.find { it.id == id } }

/** Finds the active host profile by id, or null. */
fun currentHostProfile(hostProfiles: List<HostProfile>, currentHostProfileId: String?): HostProfile? =
    currentHostProfileId?.let { id -> hostProfiles.find { it.id == id } }

/** Status badge for the currently-selected session, or null. */
fun currentSessionStatus(
    sessionStatuses: Map<String, SessionStatus>,
    currentSessionId: String?
): SessionStatus? = currentSessionId?.let { sessionStatuses[it] }

/**
 * §s3-markers: synthetic message roles that survive the `visibleMessages`
 * tool/system filter. Each role carries no `parts` and is rendered by the
 * chat list as an inline metadata marker instead of a [MessageRow]:
 *  - "agent-switched": inserted before a turn whose `agent` differs from the
 *    prior user/assistant turn (agent prefix chip).
 *  - "model-switched": inserted before a turn whose `resolvedModel` differs
 *    from the prior user/assistant turn (model prefix chip).
 *  - "compaction":     inserted at a context-compaction boundary (collapsible
 *    divider). Reserved for future server-driven compaction events; the
 *    client injector does not generate these today.
 */
internal val METADATA_MARKER_ROLES = setOf("agent-switched", "model-switched", "compaction")

/**
 * The filtered chat transcript: applies the revert cutoff (messages before the
 * revert point) then drops tool/system/environment messages so only user +
 * assistant turns render. §s3-markers: synthetic metadata-marker messages
 * ([METADATA_MARKER_ROLES]) survive the role filter so they can render
 * inline between turns. Verbatim move of the AppState.visibleMessages getter
 * + marker filter.
 */
fun visibleMessages(messages: List<Message>, currentSession: Session?): List<Message> {
    val revertMessageId = currentSession?.revert?.messageId
    val reverted = messages.filterBeforeRevert(revertMessageId)
    // Filter out system / tool / environment messages — only user and
    // assistant messages are shown in the chat transcript, EXCEPT the
    // synthetic metadata-marker messages (§s3-markers) which carry one of
    // the [METADATA_MARKER_ROLES] roles and have no real parts.
    return reverted.filter { !it.isToolRole || it.role in METADATA_MARKER_ROLES }
}

/**
 * §revert-fix: filters this list down to messages that come BEFORE the revert
 * point [revertMessageId], using a monotonic time comparison instead of string
 * id ordering. The opencode server issues message ids as strings (often UUIDs)
 * whose lexicographic order does NOT reflect creation order, so the previous
 * `id < revertMessageId` string comparison truncated the transcript at the
 * wrong point. This helper:
 *
 *  1. Resolves the revert threshold as the `time.created` of the message whose
 *     id == [revertMessageId]. The revert message ITSELF is always excluded
 *     (mirrors the pre-time-based `id < revertMessageId` semantics). Messages
 *     with no `time.created` are kept (cannot be ordered → conservative keep,
 *     EXCEPT the revert message which is dropped by id). A timestamped message
 *     is kept iff its `created < revertCreated`, OR its `created == revertCreated`
 *     AND its list position is strictly before the revert message's position
 *     (tie-break on index so the revert row is never kept).
 *  2. When the revert message has no `time.created`, falls back to list index
 *     order: keeps messages whose index is strictly before the revert message
 *     (excludes the revert point itself).
 *
 * If [revertMessageId] is null OR is absent from the list, the input is
 * returned unchanged (no cutoff can be applied).
 */
internal fun List<Message>.filterBeforeRevert(revertMessageId: String?): List<Message> {
    if (revertMessageId == null) return this
    val revertIndex = indexOfFirst { it.id == revertMessageId }
    if (revertIndex < 0) return this
    val revertCreated = this[revertIndex].time?.created
    return if (revertCreated != null) {
        // time-based: 保留 created < revertCreated 的，或 created == revertCreated 但列表
        // 位置在 revert 之前的；排除 revert 自身（idx == revertIndex）。
        filterIndexed { idx, msg ->
            idx != revertIndex && (
                msg.time?.created == null ||
                msg.time!!.created < revertCreated ||
                (msg.time!!.created == revertCreated && idx < revertIndex)
            )
        }
    } else {
        // index fallback: 按列表位置截断，排除 revert 自身
        subList(0, revertIndex)
    }
}

/**
 * §s3-markers: walks the (already visible, oldest-first) [messages] list and
 * inserts a synthetic [Message] (role ∈ [METADATA_MARKER_ROLES]) BEFORE
 * every turn whose `agent` or `resolvedModel` differs from the previous
 * USER-or-ASSISTANT message. Returns the interleaved list.
 *
 * Marker semantics:
 *  - agent-switched: emitted when the new message's `agent` is non-null and
 *    differs from the prior tracked agent; label = the new agent name.
 *  - model-switched: emitted when the new message's `resolvedModel` is
 *    non-null and differs from the prior tracked model; label = the new
 *    model's `modelId` (the UI formats a friendly display name).
 *
 * Skip the very first assignment — i.e. do not emit a marker for the first
 * non-null value (the user only cares about CHANGES). Comparison is on
 * non-null values only: a transition null → non-null is not a change; a
 * transition X → Y (both non-null, X != Y) emits one marker immediately
 * before the differing message. Existing marker roles in the input are
 * passed through untouched (they carry no agent/model so they don't affect
 * the tracked values).
 *
 * Pure / deterministic; safe to re-run on every recomposition.
 */
fun injectMetadataMarkers(messages: List<Message>): List<Message> {
    if (messages.isEmpty()) return messages
    val out = ArrayList<Message>(messages.size + 4)
    var trackedAgent: String? = null
    var trackedModel: Message.ModelInfo? = null
    var haveSeenAgent = false
    var haveSeenModel = false
    for (current in messages) {
        // Only user / assistant turns drive the comparison; markers and
        // other roles (already filtered out by visibleMessages) are skipped.
        if (current.isUser || current.isAssistant) {
            val newAgent = current.agent
            val newModel = current.resolvedModel

            if (newAgent != null && haveSeenAgent && newAgent != trackedAgent) {
                out.add(
                    Message(
                        id = "marker-agent-${current.id}",
                        role = "agent-switched",
                        agent = newAgent
                    )
                )
            }
            if (newAgent != null) {
                trackedAgent = newAgent
                haveSeenAgent = true
            }

            if (newModel != null && haveSeenModel && newModel != trackedModel) {
                out.add(
                    Message(
                        id = "marker-model-${current.id}",
                        role = "model-switched",
                        model = newModel,
                        modelId = newModel.modelId,
                        providerId = newModel.providerId
                    )
                )
            }
            if (newModel != null) {
                trackedModel = newModel
                haveSeenModel = true
            }
        }
        out.add(current)
    }
    return out
}


/**
 * Context-usage percentage for the most recent assistant message that carries
 * usable token totals, resolved against the provider/model context limit.
 * Returns null (with a debug log) when any required piece is missing — mirrors
 * the original AppState.contextUsage getter exactly. Verbatim move.
 */
fun computeContextUsage(messages: List<Message>, providers: ProvidersResponse?): ContextUsage? {
    val lastAssistant = messages.lastOrNull { it.isAssistant && tokenTotal(it.tokens) != null }
        ?: return logContextUsageUnavailable("no assistant message with usable tokens; messages=${messages.size}")
    val tokens = lastAssistant.tokens
        ?: return logContextUsageUnavailable("latest assistant has no tokens; messages=${messages.size}")
    val total = tokenTotal(tokens)
        ?: return logContextUsageUnavailable("assistant tokens have no usable totals; tokens=$tokens")
    val model = lastAssistant.resolvedModel
        ?: return logContextUsageUnavailable("assistant message has no resolved model; message=${lastAssistant.id}")
    val key = "${model.providerId}/${model.modelId}"
    val index = providers?.providers?.flatMap { provider ->
        provider.models.flatMap { (modelKey, providerModel) ->
            listOfNotNull(
                "${provider.id}/$modelKey" to providerModel,
                providerModel.id.takeIf { it.isNotEmpty() }?.let { "${provider.id}/$it" to providerModel },
                providerModel.resolvedProviderId?.let { resolvedProvider ->
                    providerModel.id.takeIf { it.isNotEmpty() }?.let { modelId -> "$resolvedProvider/$modelId" to providerModel }
                }
            )
        }
    }?.toMap() ?: emptyMap()
    val providerModel = index[key] ?: index.entries
        .filter { it.key.substringAfter('/') == model.modelId }
        .takeIf { it.size == 1 }
        ?.first()
        ?.value
    val limit = providerModel?.limit?.context
        ?: return logContextUsageUnavailable("no context limit for $key; providerModelKeys=${index.keys.take(12)}")
    if (limit <= 0) return logContextUsageUnavailable("non-positive context limit for $key: $limit")
    return ContextUsage(
        percentage = (total.toFloat() / limit.toFloat()).coerceIn(0f, 1f),
        totalTokens = total,
        contextLimit = limit,
        providerId = model.providerId,
        modelId = model.modelId,
        inputTokens = tokens.input,
        outputTokens = tokens.output,
        reasoningTokens = tokens.reasoning,
        cachedReadTokens = tokens.cache?.read,
        cachedWriteTokens = tokens.cache?.write,
        cost = lastAssistant.cost
    )
}

/**
 * §model-selection: infers the model bound to the current session by picking
 * the most recent assistant message's [Message.resolvedModel]. Returns null
 * when there are no assistant messages or none carries a model.
 */
fun inferCurrentModel(messages: List<Message>): Message.ModelInfo? =
    messages.lastOrNull { it.isAssistant }?.resolvedModel

/**
 * §model-selection: resolves a human-readable display name for a model
 * against the providers catalog. Searches the catalog for a matching
 * `providerId` / `modelId` and returns its [ProviderModel.name]; falls back
 * to the raw modelId when the model is not found OR its name is null, and
 * returns the empty string when [currentModel] itself is null.
 *
 * Used by the chat top-bar context menu so the model entry reads as a
 * friendly name (e.g. "GPT-5") instead of the raw id when possible.
 */
fun resolveModelDisplayName(
    currentModel: Message.ModelInfo?,
    providers: ProvidersResponse?
): String {
    if (currentModel == null) return ""
    val match = providers?.providers?.firstOrNull { it.id == currentModel.providerId }?.let { provider ->
        provider.models.entries.firstOrNull { (modelKey, model) ->
            modelKey == currentModel.modelId || model.id == currentModel.modelId
        }?.value
    }
    return match?.name?.takeIf { it.isNotEmpty() } ?: currentModel.modelId
}

/**
 * §stale-question: returns true iff [part] is a `question` tool part stuck
 * in the "running" state WITHOUT a matching live [QuestionRequest] in
 * [pending]. opencode 1.17.12 stores pending questions in-memory (server
 * `question/index.ts:71`); on server restart / interrupted run the message
 * history still has the part in "running" but `GET /question` returns empty
 * for it → the UI renders a perpetual spinner. This predicate lets the chat
 * list render such parts in a terminal "Interrupted" state instead.
 *
 * A part is stale iff ALL hold:
 *  - [Part.isTool] is true.
 *  - [Part.tool] (lowercased) equals `"question"`.
 *  - [Part.stateDisplay] equals `"running"`.
 *  - NO entry in [pending] has `tool.messageId == part.messageId` AND
 *    `tool.callId == part.callId`.
 *
 * **Conservative null handling**: a part missing `messageId` or `callId`
 * returns FALSE (NOT stale) — "unknown → leave the spinner, don't mis-kill".
 * Mis-rendering a possibly-live question as "Interrupted" is worse than
 * leaving a spinner on an ambiguous part. The underlying assumption that
 * tool-originated questions populate `tool:{messageID,callID}` is verified
 * (https://github.com/sst/opencode/blob/v1.17.12/packages/opencode/src/tool/question.ts#L27),
 * so a question part without those fields is genuinely unexpected and should
 * be left alone rather than terminal-rendered.
 */
fun isStaleQuestionPart(part: Part, pending: List<QuestionRequest>): Boolean {
    if (!part.isTool) return false
    if (part.tool?.lowercase() != "question") return false
    if (part.stateDisplay != "running") return false
    // Conservative: unknown → not stale (don't mis-kill a possibly-live part).
    val partMessageId = part.messageId ?: return false
    val partCallId = part.callId ?: return false
    return pending.none { q ->
        val tool = q.tool ?: return@none false
        tool.messageId == partMessageId && tool.callId == partCallId
    }
}

private fun logContextUsageUnavailable(reason: String): ContextUsage? {
    runCatching { Log.d("AppState", "contextUsage unavailable: $reason") }
    return null
}

private fun tokenTotal(tokens: Message.TokenInfo?): Int? {
    if (tokens == null) return null
    tokens.total?.takeIf { it > 0 }?.let { return it }
    return listOfNotNull(
        tokens.input,
        tokens.output,
        tokens.reasoning,
        tokens.cache?.read,
        tokens.cache?.write
    ).sum().takeIf { it > 0 }
}
