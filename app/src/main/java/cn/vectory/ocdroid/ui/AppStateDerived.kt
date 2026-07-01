package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.ProvidersResponse
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
 * The filtered chat transcript: applies the revert cutoff (messages before the
 * revert point) then drops tool/system/environment messages so only user +
 * assistant turns render. Verbatim move of the AppState.visibleMessages getter.
 */
fun visibleMessages(messages: List<Message>, currentSession: Session?): List<Message> {
    val revertMessageId = currentSession?.revert?.messageId
    val reverted = if (revertMessageId == null) {
        messages
    } else {
        messages.filter { message -> message.id < revertMessageId }
    }
    // Filter out system / tool / environment messages — only user and
    // assistant messages are shown in the chat transcript.
    return reverted.filter { !it.isToolRole }
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
