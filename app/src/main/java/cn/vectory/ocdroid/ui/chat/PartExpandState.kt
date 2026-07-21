package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.ExpandOutcome
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.isThinPlaceholder
import cn.vectory.ocdroid.data.repository.mergeFullBatchIntoLocal
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching

/*
 * §slimapi-client-impl-v1 §G6 (Task 15) — L4 per-part expand state +
 * usecase that drives the G6 batch-full fetch (T3) and the null-safe
 * merge (T8) for skeleton parts carrying the T1 markers
 * (`Part.hasFull == true && Part.omitted != null`).
 *
 * This is a NEW expand surface, layered alongside the chat's
 * `streamingPartTexts` slice. It MUST NOT be confused with the legacy
 * `expandedParts` / `onToggleExpand` mechanism (used by tool-call folds,
 * reasoning cards, sub-agent cards, patch accordions — the
 * collapse/expand affordance on existing tool surfaces). That legacy
 * mechanism is keyed by a single `String` and toggles a `Boolean`; this
 * one is keyed by [PartKey]`(messageId, partId)` and transitions through
 * a four-state hierarchy. The two coexist deliberately; this file does
 * NOT touch the legacy state.
 */

/**
 * T15 — per-part expand state for the "展开省略内容" affordance on
 * skeleton parts. The UI's affordance toggles `Idle → Loading`
 * (set synchronously on tap) and the [ExpandPartsUseCase] result drives
 * `Loading → Loaded | Failed(code)`.
 *
 *  - [Idle] — initial / reset. Part renders as a skeleton; the
 *    affordance is visible.
 *  - [Loading] — set by the affordance tap handler (T16) before
 *    invoking [ExpandPartsUseCase]. UI shows inline progress.
 *  - [Loaded] — terminal success. The owner message was fetched
 *    successfully AND the caller reconciled the fetched content into
 *    the live partsByMessage; UI re-renders from the cache. (The
 *    usecase's fetch-level candidate is promoted to a true terminal
 *    Loaded only after the ViewModel's live-slice reconciliation places
 *    the content.)
 *  - [Failed] — terminal failure. [code] carries the sidecar's
 *    machine-readable error code from `{"code":"…"}` when available
 *    (e.g. 503 `upstream_unavailable`); null on transport failure,
 *    per-message error inside the G6 envelope, or residual drop.
 *
 * Pure sealed interface (no Android / Compose / IO deps) so the
 * transition table is unit-testable in isolation — same discipline as
 * [cn.vectory.ocdroid.data.repository.ExpandOutcome] /
 * [cn.vectory.ocdroid.data.repository.StatusOutcome].
 */
sealed interface PartExpandState {
    /** Initial / reset. Affordance visible. */
    data object Idle : PartExpandState

    /** Tap landed; usecase in flight. UI shows inline progress. */
    data object Loading : PartExpandState

    /**
     * Terminal success: owner fetched AND live-slice reconciled. The
     * usecase's fetch-level candidate is promoted to this only after the
     * ViewModel reconciles the fetched content into the current
     * partsByMessage.
     */
    data object Loaded : PartExpandState

    /**
     * Terminal failure. [code] is the sidecar's machine-readable code
     * (`upstream_unavailable` / `response_too_large` / …) when the
     * outcome carried one; null on transport failure, per-message
     * envelope error, or residual drop.
     */
    data class Failed(val code: String?) : PartExpandState

    /** Budget exhaustion — skeleton preserved, retry affordance present. */
    data object Exhausted : PartExpandState
}

/**
 * T15 — identifier for a single expandable part. Mirrors the
 * `(messageId, partId)` merge key in T8's [mergeFullBatchIntoLocal]:
 * the merge replaces a local [Part] by matching BOTH its owning
 * `messageID` AND its own `id`, so the per-part expand state must be
 * keyed the same way to stay in lock-step with the merge.
 */
data class PartKey(val messageId: String, val partId: String)

/**
 * T15 — terminal outcome of an [ExpandPartsUseCase] invocation. The
 * usecase is non-mutating (it does NOT touch any StateFlow): it
 * returns this summary and the caller (T16 chat wiring) reconciles the
 * fetched content into the LIVE slice.
 *
 *  - [fetchedItems] — the RAW fetched [MessageWithParts] list on an
 *    [ExpandOutcome.Ok] (`outcome.items`); `null` on
 *    [ExpandOutcome.SessionMissing] / [ExpandOutcome.Failed] / empty.
 *    The caller reconciles these into the CURRENT live
 *    `partsByMessage` via a per-owner merge. [mergedLocal] is a PURE
 *    T8 result and is NOT written to the live slice wholesale.
 *  - [mergedLocal] — the PURE result of T8's [mergeFullBatchIntoLocal]
 *    over `(local, items)` on [ExpandOutcome.Ok]; retained for
 *    diagnostics/tests only. `null` on SessionMissing / Failed.
 *  - [states] — FETCH-LEVEL CANDIDATE [PartExpandState] for every
 *    eligible requested part (Loaded OR Failed). A `Loaded` candidate
 *    means "the owner message was fetched successfully"; it is NOT
 *    proof the live UI cache already holds the content. The caller
 *    (ViewModel) must reconcile [fetchedItems] into the CURRENT live
 *    partsByMessage before treating `Loaded` as visible success; if
 *    that reconciliation cannot place the content, the caller must
 *    keep retry visible ([PartExpandState.Failed]).
 *    The caller must have set [PartExpandState.Loading] on the same
 *    keys before invoking the usecase; this map is the "commit" half
 *    of the Loading → terminal transition.
 */
data class ExpandPartsOutcome(
    val fetchedItems: List<MessageWithParts>?,
    val mergedLocal: List<MessageWithParts>?,
    val states: Map<PartKey, PartExpandState>,
) {
    companion object {
        /**
         * No eligible parts (none had `hasFull && omitted && messageId`)
         * → the usecase short-circuited without invoking the repo.
         * Caller leaves the state map untouched.
         */
        val Empty: ExpandPartsOutcome = ExpandPartsOutcome(
            fetchedItems = null, mergedLocal = null, states = emptyMap(),
        )
    }
}

/**
 * T15 — drives a G6 expand for a batch of skeleton parts. Implements
 * the **residual rule** (T3 design note): `expandMessagesFullBatch`'s
 * internal 413-halve + >20-truncate can drop the back-half of requested
 * ids — those ids end up in NEITHER `items` NOR `failedIds`, and the
 * usecase MUST surface them as [PartExpandState.Failed] so the UI does
 * not hang in [PartExpandState.Loading] forever.
 *
 * # Flow
 *
 * 1. **Filter** parts: keep only those with `hasFull == true &&
 *    omitted != null && messageId != null` (the G6 batch endpoint keys
 *    on message ids — a part without one cannot be batch-expanded).
 * 2. **Collect** the deduped set of requested message ids (LinkedHashSet
 *    for deterministic ordering).
 * 3. **Call** T3's [OpenCodeRepository.expandMessagesFullBatch]
 *    (consume-only — unchanged).
 * 4. **Branch** on the outcome:
 *    - [ExpandOutcome.Ok] — expose raw `items` as
 *      [ExpandPartsOutcome.fetchedItems] and also fold them into `local`
 *      via T8's pure [mergeFullBatchIntoLocal] (retained as
 *      [ExpandPartsOutcome.mergedLocal] for diagnostics/tests ONLY — the
 *      caller does NOT write it wholesale). Per-part terminal state
 *      (FETCH-LEVEL CANDIDATE):
 *      * owner message fetched OK (`ownerMsgId` ∈ items' info.ids) ⇒
 *        candidate [PartExpandState.Loaded] for ALL parts — both
 *        thin_placeholder and non-thin-placeholder. This asserts the
 *        owner was fetched successfully, NOT that the live UI cache
 *        holds the content; the caller reconciles `items` into the
 *        CURRENT live partsByMessage before treating Loaded as visible
 *        success.
 *      * owner message ∈ `failedIds` → [PartExpandState.Failed]`(null)`
 *      * owner message ∈ residual (requested − items − failedIds) →
 *        [PartExpandState.Failed]`(null)` — the residual rule.
 *    - [ExpandOutcome.SessionMissing] — the session is gone upstream;
 *      all targets → [PartExpandState.Failed]`(null)` (the coordinator
 *      clears the local cache separately; this state is just so the UI
 *      does not stay in Loading). `mergedLocal = null`.
 *    - [ExpandOutcome.Failed] — all targets → [PartExpandState.Failed]`(code)`
 *      (the sidecar's machine-readable code from `{"code":"…"}`, or null
 *      on transport failure). `mergedLocal = null`.
 *
 * # CE discipline
 *
 * Wraps the body in [runSuspendCatching] so CancellationException
 * propagates (scope cancel / ViewModel clear) instead of being collapsed
 * into a `Result.failure`. Matches the T3 / T4 / T13 usecase pattern.
 *
 * Construction: plain Kotlin class (no Hilt — wired by the chat layer in
 * T16), matching the SlimStatusFanOut pattern. Tests construct it
 * directly with a mockk `OpenCodeRepository`.
 *
 * @param repository T3's `expandMessagesFullBatch` is consume-only.
 */
class ExpandPartsUseCase(
    private val repository: OpenCodeRepository,
) {
    /**
     * @param sessionId target session id.
     * @param local current local cache (passed in so the T8 merge is a
     *   pure function of (local, items); the usecase stays stateless).
     * @param parts parts the user tapped "展开" on. Only those with
     *   `hasFull == true && omitted != null && messageId != null` are
     *   actually expanded; the rest are filtered out (caller leaves
     *   their state untouched).
     */
    suspend fun expandParts(
        sessionId: String,
        local: List<MessageWithParts>,
        parts: List<Part>,
    ): Result<ExpandPartsOutcome> = runSuspendCatching {
        // Step 1: filter to eligible parts.
        val targets = parts.filter { it.hasFull == true && it.omitted != null && it.messageId != null }
        if (targets.isEmpty()) {
            // Diagnostic: usecase is about to short-circuit WITHOUT calling
            // G6 — if the user reported "展开失败", this WARN proves the
            // tap never reached the network at all (eligibility filter
            // dropped every part: missing hasFull / omitted / messageId).
            DebugLog.w(
                TAG,
                "expand targets empty (no hasFull&&omitted&&messageId part) " +
                    "sessionId=$sessionId partsCount=${parts.size}",
            )
            return@runSuspendCatching ExpandPartsOutcome.Empty
        }

        // Step 2: dedup requested message ids (LinkedHashSet → deterministic order).
        val requestedMsgIds: Set<String> = targets.mapTo(LinkedHashSet()) { it.messageId!! }

        // Step 3: T3 consume-only call.
        val outcome = repository.expandMessagesFullBatch(sessionId, requestedMsgIds)

        // Step 4: branch on outcome.
        when (outcome) {
            is ExpandOutcome.Ok -> foldOk(local, targets, outcome)
            is ExpandOutcome.SessionMissing -> {
                val states = targets.associate {
                    PartKey(it.messageId!!, it.id) to PartExpandState.Failed(code = null)
                }
                ExpandPartsOutcome(fetchedItems = null, mergedLocal = null, states = states)
            }
            is ExpandOutcome.Failed -> {
                val state = if (outcome.exhausted) PartExpandState.Exhausted
                else PartExpandState.Failed(code = outcome.code)
                val states = targets.associate {
                    PartKey(it.messageId!!, it.id) to state
                }
                ExpandPartsOutcome(fetchedItems = null, mergedLocal = null, states = states)
            }
        }
    }

    /**
     * Ok-branch fold: pure helper extracted so the residual rule is
     * readable in one place (and unit-testable in isolation if a future
     * refactor splits it out). Not `private` so tests can pin it via
     * the public usecase surface only — kept `internal` for symmetry
     * with the SlimStatusFanOut pure-fold discipline.
     */
    private fun foldOk(
        local: List<MessageWithParts>,
        targets: List<Part>,
        outcome: ExpandOutcome.Ok,
    ): ExpandPartsOutcome {
        val itemByMsg: Map<String, MessageWithParts> = outcome.items.associateBy { it.info.id }
        val itemMsgIds: Set<String> = itemByMsg.keys
        val failedMsgIds: Set<String> = outcome.failures.map { it.messageId }.toSet()

        // T8 pure merge — fold fetched-full items into local.
        val merged = mergeFullBatchIntoLocal(local, outcome.items)

        // ── Owner resolution (round-3 fix) ───────────────────────────────
        //
        // T8's `mergeFullBatchIntoLocal` iterates the LOCAL list by
        // `lm.info.id`: a local part `lp` is owned by EXACTLY the
        // `MessageWithParts` whose `parts` list contains it, and T8
        // uses that `lm.info.id` as the owner for BOTH the
        // `fullByMsg[lm.info.id]` lookup AND the
        // `normMsg(lm.info.id)` guard (`SlimapiMessageMerge.kt:106-111`).
        //
        // Round-2 used `part.messageId!!` (the wire field) as owner,
        // which is usually equal to `lm.info.id` but is NOT guaranteed
        // to be — `Part.messageId` is the wire claim, not proof of the
        // local owner. When the wire field is stale/mismatched, T15's
        // Loaded judgment diverges from T8's actual replacement.
        //
        // Resolution mirrors T8's `lm.info.id` owner:
        //  - Primary: the local message whose `info.id` matches the
        //    part's wire `messageId` (the common case).
        //  - Fallback: the local message whose `parts` contain this
        //    part id (T8-faithful structural ownership — what T8's
        //    outer iteration sees when the wire field is wrong).
        //  - Orphan (neither matches): no local owner → Failed (can't
        //    establish T8-equivalent owner identity; defensive).
        val localByInfoId: Map<String, MessageWithParts> = local.associateBy { it.info.id }
        val localOwnerByPartId: Map<String, String> = buildMap {
            local.forEach { lm -> lm.parts.forEach { p -> put(p.id, lm.info.id) } }
        }
        fun resolveOwner(part: Part): String? {
            part.messageId?.let { wire -> localByInfoId[wire]?.let { return it.info.id } }
            return localOwnerByPartId[part.id]
        }

        // Per-part terminal state (residual rule applied).
        //
        // Loaded judgment (FETCH-LEVEL CANDIDATE): owner message fetched
        // OK ⇒ candidate Loaded for ALL parts (both thin_placeholder and
        // non-thin-placeholder). This is a FETCH-level verdict only — it
        // asserts the full owner message was retrieved successfully, NOT
        // that the live UI cache already holds the content. The caller
        // (ViewModel completion reducer) must reconcile `outcome.items`
        // into the CURRENT live partsByMessage before treating this as
        // visible success; if that reconciliation cannot place the content
        // (owner/current slice gone at commit time), the caller keeps
        // retry visible (Failed).
        //
        // The skeleton marker part's own `(id, normalizedMessageId)` is
        // NOT a guaranteed stable key into the fetched full message
        // (skeleton/streaming/thin_placeholder parts carry transient
        // ids), so a per-part triple-match at THIS layer is a false
        // negative. Genuine fetch failures (orphan / failedIds / residual)
        // are caught by the dedicated branches below. `owner` is sourced
        // from the LOCAL `lm.info.id` (via [resolveOwner]).
        val states: Map<PartKey, PartExpandState> = buildMap {
            targets.forEach { part ->
                val key = PartKey(messageId = part.messageId!!, partId = part.id)
                val ownerMsgId = resolveOwner(part)
                val state: PartExpandState = when {
                    // Branch 0 (round-3): orphan — no local owner can be
                    // established for this part. T8 would never see it
                    // (it's not in any lm.parts), so T8-equivalent merge
                    // is undefined; surface as Failed defensively.
                    //
                    // Diagnostic: log the partId + the wire messageId claim
                    // + a bounded summary of local's known owners so we can
                    // tell "part.messageId stale / not in local" (the
                    // common round-2 regression) from "local cache evicted"
                    // when triaging a "展开失败 (Failed null)" report.
                    ownerMsgId == null -> {
                        DebugLog.w(
                            TAG,
                            "expand resolveOwner null partId=${part.id} " +
                                "part.messageId=${part.messageId} " +
                                "localMsgIds(${localByInfoId.keys.size})=${localByInfoId.keys.take(20)} " +
                                "localPartIds(${localOwnerByPartId.keys.size})=${localOwnerByPartId.keys.take(20)}",
                        )
                        PartExpandState.Failed(code = null)
                    }
                    // Branch A: owner message fetched OK ⇒ candidate
                    // Loaded for ALL parts (fetch-level verdict only).
                    // The full owner message was fetched successfully;
                    // the caller (ViewModel) must reconcile the fetched
                    // content into the CURRENT live partsByMessage before
                    // treating this as visible success. Candidate Loaded
                    // here regardless of whether the part's own partId
                    // verbatim reappears in the fetched message — a
                    // per-part-id triple-match is too strict for skeleton
                    // marker / thin_placeholder parts (transient ids) and
                    // produced a false Failed(null) on a successful fetch.
                    ownerMsgId in itemMsgIds -> {
                        // DEBUG (normal success path): note the partId, owner,
                        // thin-placeholder flag, and fetched part count for
                        // diagnostics. Not WARN — this is the expected outcome.
                        val fetchedPartCount = itemByMsg.getValue(ownerMsgId).parts.size
                        DebugLog.d(
                            TAG,
                            "expand foldOk A Loaded partId=${part.id} part.messageId=${part.messageId} " +
                                "ownerMsgId=$ownerMsgId isThinPlaceholder=${part.isThinPlaceholder()} " +
                                "fetchedPartCount=$fetchedPartCount " +
                                "itemMsgIds(${itemMsgIds.size})=${itemMsgIds.take(20)}",
                        )
                        PartExpandState.Loaded
                    }
                    // Branch B: per-message failure in the G6 envelope's errors[].
                    ownerMsgId in failedMsgIds -> {
                        DebugLog.w(
                            TAG,
                            "expand foldOk B partId=${part.id} part.messageId=${part.messageId} " +
                                "ownerMsgId=$ownerMsgId (in failedMsgIds) " +
                                "itemMsgIds(${itemMsgIds.size})=${itemMsgIds.take(20)} " +
                                "failedMsgIds(${failedMsgIds.size})=${failedMsgIds.take(20)}",
                        )
                        PartExpandState.Failed(code = null)
                    }
                    // Branch C: residual — requested but dropped by 413-halve /
                    // 20-truncate. T3 design note: without this rule the part
                    // hangs in Loading forever.
                    else -> {
                        DebugLog.w(
                            TAG,
                            "expand foldOk C partId=${part.id} part.messageId=${part.messageId} " +
                                "ownerMsgId=$ownerMsgId (residual — not in items nor failedIds) " +
                                "itemMsgIds(${itemMsgIds.size})=${itemMsgIds.take(20)} " +
                                "failedMsgIds(${failedMsgIds.size})=${failedMsgIds.take(20)}",
                        )
                        PartExpandState.Failed(code = null)
                    }
                }
                put(key, state)
            }
        }
        return ExpandPartsOutcome(
            fetchedItems = outcome.items,
            mergedLocal = merged,
            states = states,
        )
    }

    private companion object {
        private const val TAG = "PartExpandState"
    }
}
