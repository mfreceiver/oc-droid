package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.isEffectivelyRenderableEmpty
import cn.vectory.ocdroid.data.repository.isThinPlaceholder
import cn.vectory.ocdroid.ui.chat.ExpandPartsOutcome
import cn.vectory.ocdroid.ui.chat.PartExpandState
import cn.vectory.ocdroid.ui.chat.PartKey

/**
 * T1b ExpandedParts CAS fix: pure reconcile of expandParts completion against
 * the **latest** [ChatState]. Invoked from [reduce] inside
 * `state.update { … }`, so a concurrent SSE mutation that changes
 * [ChatState.partsByMessage] between the call-site dispatch and the CAS
 * commit is visible here (the update loop re-runs reduce against the new
 * state) — restores the pre-Strategy-1 writeChat CAS semantics.
 *
 * Session guard: if [expectedSessionId] != [ChatState.currentSessionId],
 * returns `this` unchanged. The host-fp guard stays at the ChatViewModel
 * call site (reducer purity — no host-profile access).
 *
 * Byte-for-byte with the pre-fix ChatViewModel:567-764 merge body, with
 * `this` standing in for the CAS `current` snapshot.
 */
internal fun ChatState.reconcileExpandedPartsContent(
    outcome: ExpandPartsOutcome,
    local: List<MessageWithParts>,
    expectedSessionId: String,
): ChatState {
    if (currentSessionId != expectedSessionId) return this

    // Owner resolution (captured local — mirrors foldOk / ChatViewModel).
    val localByInfoId: Map<String, MessageWithParts> = local.associateBy { it.info.id }
    val localOwnerByPartId: Map<String, String> = buildMap {
        local.forEach { lm -> lm.parts.forEach { p -> put(p.id, lm.info.id) } }
    }

    var updatedPartsByMessage = partsByMessage
    val updatedStates = partExpandStates.toMutableMap()

    // Decides the terminal for a Loaded candidate whose live-slice merge
    // could NOT be performed: Loaded only if the current target part is
    // already a resolved (non-skeleton) part (concurrent expand/SSE resolved
    // it); otherwise Failed(null) so retry stays visible. `ownerOverride`
    // short-circuits owner re-resolution when the caller already knows it.
    fun alreadyFullOrFailed(
        key: PartKey,
        ownerOverride: String?,
    ): PartExpandState {
        val oid = ownerOverride
            ?: localByInfoId[key.messageId]?.info?.id
            ?: localOwnerByPartId[key.partId]
            ?: return PartExpandState.Failed(code = null)
        val parts = partsByMessage[oid]
            ?: return PartExpandState.Failed(code = null)
        val target = parts.firstOrNull { it.id == key.partId }
            ?: return PartExpandState.Failed(code = null)
        // alreadyFull: target no longer carries the skeleton markers
        // (hasFull != true OR omitted == null) AND the owner's current parts
        // actually render something. An owner whose only parts are still-
        // omitted/blank markers is not a visible success — guard the v3
        // content-loss edge where the concurrent state is itself effectively
        // empty (would otherwise hide the affordance with nothing committed).
        val alreadyFull =
            (target.hasFull != true || target.omitted == null) &&
                !isEffectivelyRenderableEmpty(parts)
        return if (alreadyFull) {
            PartExpandState.Loaded
        } else {
            PartExpandState.Failed(code = null)
        }
    }

    // Only Loaded-outcome keys still Loading can reconcile content. Group
    // them by resolved owner so each owner is merged ONCE, then all its
    // Loaded keys are finalised.
    val activeLoadedKeys: List<PartKey> =
        outcome.states.entries
            .filter { (key, state) ->
                state is PartExpandState.Loaded &&
                    (partExpandStates[key] is PartExpandState.Loading)
            }
            .map { it.key }

    val keysByOwner: Map<String?, List<PartKey>> =
        activeLoadedKeys.groupBy { key ->
            localByInfoId[key.messageId]?.info?.id
                ?: localOwnerByPartId[key.partId]
        }

    // Per-key terminal decision for Loaded candidates.
    val loadedTerminal = HashMap<PartKey, PartExpandState>()
    // Owners whose live-slice merge succeeded → write once each.
    val ownerMergedParts = HashMap<String, List<Part>>()

    keysByOwner.forEach { (ownerId, keys) ->
        if (ownerId == null) {
            // Cannot establish an owner — cannot reconcile. Keep retry
            // visible unless the target is already full.
            keys.forEach { key ->
                loadedTerminal[key] = alreadyFullOrFailed(key, ownerOverride = null)
            }
            return@forEach
        }
        val fetchedOwner = outcome.fetchedItems
            ?.firstOrNull { it.info.id == ownerId }
        val currentParts = partsByMessage[ownerId]
        if (fetchedOwner == null || currentParts == null) {
            // Owner/fetched/live-slice missing at commit time — cannot
            // reconcile. Keep retry visible unless the target is already
            // full (concurrent resolve).
            keys.forEach { key ->
                loadedTerminal[key] = alreadyFullOrFailed(key, ownerOverride = ownerId)
            }
            return@forEach
        }
        // §content-loss-guard: a fetch that returns the owner message with
        // NO parts carries no usable content and cannot have resolved any
        // omitted target. Retain the skeleton markers and keep retry
        // visible (unless a concurrent op already resolved the target).
        // Marking Loaded here would strip the skeleton and hide the
        // affordance with nothing committed — the v2 content-loss edge.
        // A non-empty full-message fetch is treated as authoritative for
        // all of the owner's omitted content (the server returns the
        // complete message).
        if (fetchedOwner.parts.isEmpty()) {
            keys.forEach { key ->
                loadedTerminal[key] = alreadyFullOrFailed(key, ownerOverride = ownerId)
            }
            return@forEach
        }
        // Live-slice merge of the raw fetched owner message.
        val fetchedIds: HashSet<String> =
            fetchedOwner.parts.mapTo(HashSet()) { it.id }
        val merged = currentParts.toMutableList()
        // 1. replace-by-id (append new fetched parts).
        fetchedOwner.parts.forEach { fp ->
            val idx = merged.indexOfFirst { cp -> cp.id == fp.id }
            if (idx >= 0) merged[idx] = fp else merged.add(fp)
        }
        // 2. a successful expand resolves synthetic placeholders.
        merged.removeAll { cp -> cp.isThinPlaceholder() }
        // 3. drop each captured skeleton target that is still an unresolved
        //    omitted marker AND whose id did not come back in the fetched
        //    parts (part-id drift cleanup).
        keys.forEach { key ->
            merged.removeAll { cp ->
                cp.id == key.partId &&
                    cp.id !in fetchedIds &&
                    cp.hasFull == true &&
                    cp.omitted != null
            }
        }
        // §content-loss-guard-v3: a NON-empty fetch can still consist entirely
        // of omitted/blank parts (hasFull=true, omitted!=null, text=null).
        // Those survive the merge (id ∈ fetchedIds, so the step-3 skeleton
        // cleanup keeps them) and would be marked Loaded, hiding the
        // OmittedContentCard while the owner's merged parts stay effectively-
        // empty → the whole message is dropped by the render filter ("text +
        // affordance both disappear"). Treat a non-renderable merge exactly
        // like a no-content fetch: do NOT commit the merged (empty) parts
        // (retain the original skeleton so retry stays possible) and keep
        // retry visible.
        val mergedHasRenderableContent = !isEffectivelyRenderableEmpty(merged)
        if (!mergedHasRenderableContent) {
            keys.forEach { key ->
                loadedTerminal[key] = alreadyFullOrFailed(key, ownerOverride = ownerId)
            }
            return@forEach
        }
        ownerMergedParts[ownerId] = merged.toList()
        keys.forEach { key ->
            // Per-key Loaded only when this key's live target in `merged` is
            // no longer an omitted marker. (target == null is a VALID success:
            // the skeleton was cleaned up and real fetched content replaced
            // it — that's why the owner-level mergedHasRenderableContent check
            // carries the decision, not the per-key target alone.)
            val target = merged.firstOrNull { it.id == key.partId }
            val resolved = target == null ||
                target.hasFull != true ||
                target.omitted == null
            loadedTerminal[key] = if (resolved) {
                PartExpandState.Loaded
            } else {
                PartExpandState.Failed(code = null)
            }
        }
    }

    // Write each successfully-merged owner once.
    ownerMergedParts.forEach { (ownerId, parts) ->
        updatedPartsByMessage = updatedPartsByMessage + (ownerId to parts)
    }

    // Apply terminal states for every outcome key (live-state filtered).
    outcome.states.forEach { (wireKey, outcomeState) ->
        // P1: this old completion owns neither cache nor state once the key
        // has left Loading. Skip completely.
        if (partExpandStates[wireKey] !is PartExpandState.Loading) {
            return@forEach
        }

        updatedStates[wireKey] = when (outcomeState) {
            PartExpandState.Loaded ->
                // Reconciliation decision: Loaded only if the merge placed
                // the content or it was already full; otherwise Failed(null)
                // keeps retry visible.
                loadedTerminal[wireKey]
                    ?: PartExpandState.Failed(code = null)

            is PartExpandState.Failed -> {
                outcomeState
            }

            // ExpandPartsUseCase contract says outcomes are terminal.
            PartExpandState.Idle,
            PartExpandState.Loading,
            PartExpandState.Exhausted -> {
                PartExpandState.Failed(code = null)
            }
        }
    }

    return copy(
        partsByMessage = updatedPartsByMessage,
        partExpandStates = updatedStates,
    )
}
