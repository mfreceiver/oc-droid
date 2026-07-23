package cn.vectory.ocdroid.ui

/**
 * §Stage-B §3.10 (opus SF-1 / grok S1): central policy for which part types
 * the token-stream path owns. When the token stream owns a part (Stage C/D
 * [streamOwned] == STREAMING), the legacy SSE dual-write path must NOT also
 * write that part's animated content — the token stream is the single owner.
 *
 * [animatedPartTypes] is the set of part types whose content is delivered
 * incrementally (token-by-token) and therefore subject to single-ownership.
 * A null or unknown type is NOT animated (static parts arrive whole via
 * the REST reload).
 *
 * NOTE: this is SEPARATE from the legacy [isStreamablePartType] predicate
 * (`text` OR `reasoning`). The legacy path continues to stream `reasoning`
 * via streamingPartTexts; the token-stream path (Stage C/D) initially owns
 * only `text`. The two coexist deliberately during the migration.
 */
internal object TokenStreamPolicy {
    val animatedPartTypes = setOf("text")

    fun isAnimated(type: String?): Boolean = type != null && type in animatedPartTypes
}
