package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SessionStatus

/*
 * §slimapi-client-impl-v1 §G6 (merge) + §G2 (status outcome mapping)
 * (Task 8) — pure state-derive primitives the reconcile layer (T7) uses
 * to fold an expand-full batch into the local message cache and to map
 * a per-session [StatusOutcome] (T4) into a slim internal action.
 *
 * Both functions are deliberately pure (no suspend / no retrofit / no
 * Android deps) so they stay unit-testable in isolation — the same
 * discipline as [SlimapiResync] (T6) / [SlimapiExpandOutcome] (T3) /
 * [StatusOutcome] (T4).
 */

/**
 * §G6 (Task 8) — internal action the reconcile layer (T7) translates a
 * per-session [StatusOutcome] (T4) into. Mirrors the G2 status outcome
 * table:
 *
 * | StatusOutcome     | MappedStatusAction | notes |
 * | ---               | ---                | ---   |
 * | [SessionMissing]  | [ClearLocal]       | session gone upstream → caller clears local cache + removes from list |
 * | [Success]         | [ApplyStatus]      | 200 + status payload → caller stores status (idle/busy/retry preserved as-is per T4-C2) |
 * | [Retry]           | [Retry]            | transient sidecar/upstream/transport fault → caller backs off and retries |
 * | [DirectoryError]  | [Warn]`(null)`     | deterministic directory misconfiguration → caller prompts the user; no sidecar `code` on source so [Warn.code] is null |
 * | [UpstreamWarn]    | [Warn]`(code)`     | upstream HTTP failure surfaced via sidecar → alert, keep local; [Warn.code] carried for observability |
 *
 * Deliberately minimal: there is intentionally NO `ResyncAction` variant —
 * reconcile always Rebuilds on a slim-digest update, so a "should we
 * resync?" decision value here would be dead. The caller of
 * [mapStatusOutcome] retains the source [StatusOutcome]'s `sessionId`
 * (the outcome carries it on every variant); the mapped action does not
 * duplicate it. Same boundary-purity discipline as [StatusOutcome] /
 * [ExpandOutcome]: no `retrofit2.Response` / HTTP / `okhttp3.*` types on
 * its surface.
 */
sealed interface MappedStatusAction {
    /**
     * `SessionMissing` → clear the local cache for the session and remove
     * it from the session list (the session no longer exists upstream).
     */
    data object ClearLocal : MappedStatusAction

    /**
     * `Success(status)` → store the raw [SessionStatus] as-is (T4-C2:
     * idle is NOT folded to ClearLocal here; the reconcile layer
     * cross-checks an idle status against the session list before
     * trusting it).
     */
    data class ApplyStatus(val status: SessionStatus) : MappedStatusAction

    /**
     * `Retry` → transient sidecar/upstream/transport fault → caller
     * backs off and retries the status fetch.
     */
    data object Retry : MappedStatusAction

    /**
     * `DirectoryError` (code=null — source has no code) or `UpstreamWarn`
     * (code carried from the source outcome's sidecar envelope) → alert
     * the user, keep local cache. [code] is null on the directory-error
     * path; non-null on the upstream-warn path when the sidecar supplied
     * a machine-readable error code.
     */
    data class Warn(val code: String?) : MappedStatusAction
}

/**
 * §G6 (Task 8 — T8-C1) — fold a fetched-full batch ([fullItems], the
 * `items` carried by [ExpandOutcome.Ok] from T3) into the current local
 * cache ([local]), replacing each local [Part] with its full counterpart
 * keyed by `(normalizedMessageId, partId)`.
 *
 * **Normalization:** a [Part] whose [Part.messageId] is null is treated
 * as belonging to the [MessageWithParts.info].[Message.id] that owns it.
 * This is the null-safe key — both the local and the full sides fall
 * back to the same owner id when the wire-level `messageID` is missing,
 * so a both-sided null pair still matches on `(ownerId, partId)`.
 *
 * **Algorithm (locked in task-8-brief):**
 *
 * 1. Index `fullItems` by `info.id` → `Map<messageId, Map<partId, Part>>`.
 *    Within a single message, `associateBy { partId }` keeps the LAST
 *    duplicate part id (pinned behaviour — callers must not rely on the
 *    first).
 * 2. For each local message, if no full fetch landed for it, return it
 *    unchanged (partial-batch case).
 * 3. For each local part, look up the replacement by `partId`; if found
 *    AND the normalized messageId matches, swap; otherwise keep the
 *    local part (`?: lp` fallback preserves untouched parts).
 *
 * Pure — no IO / no Android deps. Caller (T7 reconcile / T15 usecase)
 * is responsible for writing the returned list back into the cache.
 */
/** A part whose `id` starts with "thin_placeholder_" is a skeleton
 * injected by the server when the thin message has no real renderable
 * part. Such parts must never be matched per-part-id — instead the
 * entire message's parts list is replaced with the full fetch's parts
 * (message-level whole replace). See [mergeFullBatchIntoLocal] for
 * the implementation and [PartExpandState.foldOk] for the Loaded
 * judgment.
 */
fun Part.isThinPlaceholder(): Boolean = id.startsWith("thin_placeholder_")

/**
 * §G6 (Task 8 — T8-C1) — fold a fetched-full batch ([fullItems], the
 * `items` carried by [ExpandOutcome.Ok] from T3) into the current local
 * cache ([local]), replacing each local [Part] with its full counterpart
 * keyed by `(normalizedMessageId, partId)`.
 *
 * **Normalization:** a [Part] whose [Part.messageId] is null is treated
 * as belonging to the [MessageWithParts.info].[Message.id] that owns it.
 * This is the null-safe key — both the local and the full sides fall
 * back to the same owner id when the wire-level `messageID` is missing,
 * so a both-sided null pair still matches on `(ownerId, partId)`.
 *
 * **Behavior with thin placeholders (rev F / CLIENT_CHANGES):**
 * If a local message has **any** part whose [id] starts with
 * `"thin_placeholder_"`, the entire `parts` list of that local message
 * is replaced wholesale with the fetched full message's `parts` list
 * (message-level whole replace). The per-part-id lookup is skipped for
 * such messages, because the placeholder id never appears in the full
 * response.
 *
 * **Otherwise** (no placeholder parts on that local message), the
 * existing algorithm applies: for each local part, look up the
 * replacement by `partId` in the fetched full message's parts; if
 * found AND the normalized messageId matches, swap in the full part;
 * otherwise keep the local part (`?: lp` fallback preserves untouched
 * parts).
 *
 * Pure — no IO / no Android deps. Caller (T7 reconcile / T15 usecase)
 * is responsible for writing the returned list back into the cache.
 */
fun mergeFullBatchIntoLocal(
    local: List<MessageWithParts>,
    fullItems: List<MessageWithParts>,
): List<MessageWithParts> {
    fun Part.normMsg(owner: String) = (messageId ?: owner)
    val fullByMsg: Map<String, Map<String, Part>> =
        fullItems.associate { it.info.id to it.parts.associateBy { p -> p.id } }
    return local.map { lm ->
        val fullForMsg = fullByMsg[lm.info.id] ?: return@map lm
        // Rev F (CLIENT_CHANGES): if any local part is a thin placeholder,
        // replace the entire parts list with the full fetch's parts (message-level replace).
        if (lm.parts.any { it.isThinPlaceholder() }) {
            return@map lm.copy(parts = fullForMsg.values.toList())
        }
        // Otherwise (real part ids): per-part triple-match replacement.
        lm.copy(
            parts = lm.parts.map { lp ->
                fullForMsg[lp.id]
                    ?.takeIf { it.normMsg(lm.info.id) == lp.normMsg(lm.info.id) }
                    ?: lp
            }
        )
    }
}

/**
 * §G2 (Task 8 — T8-C2) — translate a per-session [StatusOutcome] (T4)
 * into the slim internal [MappedStatusAction] the reconcile layer (T7)
 * pattern-matches on. See [MappedStatusAction] for the branch table.
 *
 * Exhaustive `when` (compiler-enforced): adding a new [StatusOutcome]
 * variant without updating this mapping is a compile error.
 *
 * Pure — no IO / no Android deps.
 */
fun mapStatusOutcome(outcome: StatusOutcome): MappedStatusAction = when (outcome) {
    is StatusOutcome.SessionMissing -> MappedStatusAction.ClearLocal
    is StatusOutcome.Success -> MappedStatusAction.ApplyStatus(outcome.status)
    is StatusOutcome.Retry -> MappedStatusAction.Retry
    is StatusOutcome.DirectoryError -> MappedStatusAction.Warn(code = null)
    is StatusOutcome.UpstreamWarn -> MappedStatusAction.Warn(code = outcome.code)
}
