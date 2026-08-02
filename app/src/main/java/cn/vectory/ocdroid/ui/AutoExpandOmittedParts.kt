package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.chat.ExpandPartsUseCase
import cn.vectory.ocdroid.ui.chat.PartExpandState
import cn.vectory.ocdroid.ui.chat.PartKey
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * §defect-B-2B (Defect B part 2B): on session-message-load success,
 * automatically batch-expand the omitted tool output of the most recent
 * K=[recentMessageBudget] messages' `hasFull==true` skeleton parts, ONCE
 * per load, with a hard guard that suppresses auto-expand while the
 * session is actively streaming.
 *
 * # Why
 *
 * G6 root-cause: an expand in-flight WHILE SSE rewrites a part causes a
 * concurrent-overwrite orphan (the expand's reconcile races the stream's
 * partsByMessage rewrite, leaving a residual/orphan part). The MANUAL tap
 * path ([ChatViewModel.expandParts]) is user-driven and infrequent; the
 * systemic orphan came from auto-expanding the just-loaded window
 * unconditionally. Fixing this requires a streaming guard at the
 * auto-expand entry, not just better merge hygiene downstream.
 *
 * # Discipline
 *
 * Mirrors [ChatViewModel.expandParts] CAS discipline byte-for-byte, but
 * operates across the most-recent-K messages instead of a single tapped
 * message:
 *  1. Capture `currentProfileId()` ONCE (no TOCTOU).
 *  2. Single-read `chatFlow.value`.
 *  3. Session guard.
 *  4. **Active-write guard** (the G6 fix): bail if the session is actively
 *     being written — text/token streaming (`streamingPartTexts` non-empty OR
 *     a token-stream owner is STREAMING) OR a busy/retry tool turn
 *     (`SessionStatus.isBusy/isRetry`; SSE rewrites tool parts via
 *     `message.part.updated` even when no text overlay is live). Re-checked
 *     at the reconcile commit (after the network suspension) and reverted to
 *     Idle if the session went active mid-flight. The next idle load retries.
 *  5. `isLoadingMessages` mutex.
 *  6. Gather eligible parts from the most-recent-K messages (only Idle
 *     keys — skip Loading/Loaded/Failed/Exhausted; preserves Loaded
 *     parts and avoids duplicate in-flight requests).
 *  7. CAS-write `Loading` (session+fp guarded, only keys still non-Loading).
 *  8. Re-check identity after the CAS; abort if changed.
 *  9. [ExpandPartsUseCase] (CE discipline via runSuspendCatching).
 * 10. On success → [ChatState.reconcileExpandedPartsContent] against the
 *     LATEST chat (session+fp guarded inside the CAS; preserves Loaded,
 *     removes thin placeholders after merge, skips keys no longer Loading).
 * 11. On failure → CAS-mark `Failed(code=null)` ONLY for keys still Loading
 *     (session+fp guarded). Skeleton is NOT removed; no toast.
 *
 * Single-user product — no compat gating. Does NOT modify the manual tap
 * path ([ChatViewModel.expandParts]); does NOT add a ControllerEffect;
 * does NOT change the ExpandBatchEngine behavior (retired).
 */
internal fun launchAutoExpandOmittedParts(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    store: SharedStateStore,
    sessionId: String,
    currentProfileId: () -> String,
    recentMessageBudget: Int = DEFAULT_RECENT_MESSAGE_BUDGET,
    /**
     * §B4 round-2 (rev-gpt MAJOR): the route-instance token captured at
     * load-START (mirrors [launchLoadMessages.expectedRouteInstance]).
     * Threaded into every CAS dispatch point so a stale A→B→A incarnation
     * (same sessionId, prior token) cannot pollute the newer incarnation's
     * `partExpandStates`. Default `0L` → token guard is a no-op (legacy
     * callers / tests preserve prior behaviour).
     */
    expectedRouteInstance: Long = 0L,
) {
    // P4: capture host identity ONCE (no TOCTOU) — mirrors ChatViewModel.expandParts.
    val capturedFp = currentProfileId()
    scope.launch {
        // Step 2: single-read dispatch state (Main dispatcher — no suspension
        // between this read and the Loading CAS).
        val startState = store.chatFlow.value

        // Step 3: session guard.
        if (startState.currentSessionId != sessionId) return@launch

        // §B4 round-2 (rev-gpt MAJOR): route-token freshness guard. A stale
        // A→B→A incarnation (same sessionId, prior token) must NOT launch
        // auto-expand — its CAS writes would carry the stale token and the
        // downstream re-checks below would still let them through on a pure
        // session+fp match. Bail BEFORE any state mutation. Token=0 (legacy)
        // skips this guard (no route context — backward compat).
        if (expectedRouteInstance != 0L &&
            expectedRouteInstance != store.stateFlow.value.chatRouteInstance
        ) {
            return@launch
        }

        // Step 4 (§defect-B-2B / G6 root-cause fix): NEVER auto-expand while
        // the session is actively being written. An expand in-flight while SSE
        // rewrites a part produces a concurrent-overwrite orphan (G6). This
        // covers BOTH text/token streaming AND busy/retry tool turns (a tool
        // turn has no text overlay, but SSE still rewrites tool parts via
        // `message.part.updated`). The next idle load retries. Re-checked at
        // the commit (step 10) to also cover streaming that starts mid-flight.
        if (store.isSessionActivelyWriting(sessionId)) {
            return@launch
        }

        // Step 5: isLoadingMessages mutex — don't compete with another in-flight
        // load (its own post-load hook will run when it settles).
        if (startState.isLoadingMessages) return@launch

        // Step 6: gather eligible parts from the most-recent-K messages.
        // `messages` is oldest-first (ChatMessageList reverses it for the
        // reverseLayout display), so the most-recent window is the LAST
        // `recentMessageBudget` entries.
        val recentMessages = if (startState.messages.size <= recentMessageBudget) {
            startState.messages
        } else {
            startState.messages.takeLast(recentMessageBudget)
        }

        val local = ArrayList<MessageWithParts>()
        val partsToLoad = ArrayList<Part>()
        val keysToLoad = ArrayList<PartKey>()
        for (msg in recentMessages) {
            val msgParts = startState.partsByMessage[msg.id] ?: continue
            // Idle-filter: only Idle keys are auto-expanded. Loading
            // (in-flight tap / prior auto-expand), Loaded (already
            // resolved), Failed/Exhausted (terminal — leave retry to the
            // user) are all skipped.
            val eligible = msgParts.filter { part ->
                part.hasFull == true &&
                    part.omitted != null &&
                    part.messageId != null &&
                    startState.partExpandStates[PartKey(part.messageId!!, part.id)] is PartExpandState.Idle
            }
            if (eligible.isEmpty()) continue
            // local carries ALL parts of the owning message (single-read
            // snapshot) so the usecase's owner resolution + T8 merge see the
            // full message shape — mirrors ChatViewModel step ~491-499.
            local.add(MessageWithParts(info = msg, parts = msgParts))
            for (part in eligible) {
                partsToLoad.add(part)
                keysToLoad.add(PartKey(part.messageId!!, part.id))
            }
        }
        if (partsToLoad.isEmpty()) return@launch

        DebugLog.d(
            TAG,
            "auto-expand begin sessionId=$sessionId budget=$recentMessageBudget " +
                "messages=${recentMessages.size} eligibleParts=${partsToLoad.size}",
        )

        // Step 7: CAS-write Loading for the eligible keys (session+fp guarded,
        // only for keys still non-Loading — mirrors ChatViewModel step ~502-522).
        store.mutateChat { current ->
            if (current.currentSessionId != sessionId) return@mutateChat current
            if (currentProfileId() != capturedFp) return@mutateChat current
            // §B4 round-2 (rev-gpt MAJOR): route-token freshness CAS.
            if (expectedRouteInstance != 0L &&
                expectedRouteInstance != store.stateFlow.value.chatRouteInstance
            ) return@mutateChat current

            val loadingUpdates = keysToLoad
                .filter { key ->
                    current.partExpandStates[key] !is PartExpandState.Loading
                }
                .associateWith { PartExpandState.Loading }

            if (loadingUpdates.isEmpty()) {
                current
            } else {
                current.copy(partExpandStates = current.partExpandStates + loadingUpdates)
            }
        }

        // Step 8: abort if identity changed during the CAS (before network call).
        if (store.chatFlow.value.currentSessionId != sessionId) return@launch
        if (currentProfileId() != capturedFp) return@launch
        // §B4 round-2 (rev-gpt MAJOR): also re-check the route token (an A→B→A
        // switch mid-CAS bumps chatRouteInstance past expectedRouteInstance).
        if (expectedRouteInstance != 0L &&
            expectedRouteInstance != store.stateFlow.value.chatRouteInstance
        ) return@launch

        // Step 9: invoke usecase (non-mutating, CE discipline).
        val outcome = ExpandPartsUseCase(repository)
            .expandParts(
                sessionId = sessionId,
                local = local,
                parts = partsToLoad,
            )
            .getOrElse { error ->
                DebugLog.w(
                    TAG,
                    "auto-expand usecase threw sessionId=$sessionId " +
                        "keys=${keysToLoad.take(20)} " +
                        "cause=${error.javaClass.simpleName}: ${error.message}",
                )
                // Failure path: mark Failed(null) ONLY for keys still Loading
                // (session+fp guarded). Do NOT delete skeleton, do NOT toast.
                store.mutateChat { current ->
                    if (current.currentSessionId != sessionId) return@mutateChat current
                    if (currentProfileId() != capturedFp) return@mutateChat current
                    // §B4 round-2 (rev-gpt MAJOR): route-token freshness CAS.
                    if (expectedRouteInstance != 0L &&
                        expectedRouteInstance != store.stateFlow.value.chatRouteInstance
                    ) return@mutateChat current

                    val updatedStates = current.partExpandStates.toMutableMap()
                    keysToLoad.forEach { key ->
                        if (current.partExpandStates[key] is PartExpandState.Loading) {
                            updatedStates[key] = PartExpandState.Failed(code = null)
                        }
                    }
                    current.copy(partExpandStates = updatedStates)
                }
                return@launch
            }

        // Step 10: success — reconcile against the LATEST chat (session+fp
        // guarded inside the CAS). reconcileExpandedPartsContent preserves
        // Loaded parts (never regresses to Idle), removes thin placeholders
        // after merge, and skips keys no longer Loading (so a concurrent tap
        // that resolved a key is not clobbered).
        //
        // §G6 commit re-check (after the network suspension): the entry guard
        // (step 4) ran before this coroutine suspended, so the only window
        // where the session could have gone actively-writing is DURING the
        // ExpandPartsUseCase network call. If it did, discard the fetched
        // content and revert the keys Idle (no stuck spinner — the card stays
        // clickable; the next idle load retries). This closes the expand-vs-
        // SSE overlap that would otherwise re-open the G6 orphan.
        store.mutateChat { current ->
            if (current.currentSessionId != sessionId) return@mutateChat current
            if (currentProfileId() != capturedFp) return@mutateChat current
            // §B4 round-2 (rev-gpt MAJOR): route-token freshness CAS — a stale
            // A→B→A incarnation's success must NOT reconcile into the newer
            // incarnation's partExpandStates.
            if (expectedRouteInstance != 0L &&
                expectedRouteInstance != store.stateFlow.value.chatRouteInstance
            ) return@mutateChat current
            val status = store.sessionListFlow.value.sessionStatuses[sessionId]
            val activelyWriting = current.streamingPartTexts.isNotEmpty() ||
                current.hasActiveTokenStreamOwner() ||
                (status != null && (status.isBusy || status.isRetry))
            if (activelyWriting) {
                val reverted = keysToLoad
                    .filter { current.partExpandStates[it] is PartExpandState.Loading }
                    .associateWith { PartExpandState.Idle }
                if (reverted.isEmpty()) current
                else current.copy(partExpandStates = current.partExpandStates + reverted)
            } else {
                current.reconcileExpandedPartsContent(outcome, local, sessionId)
            }
        }

        DebugLog.d(
            TAG,
            "auto-expand done sessionId=$sessionId " +
                "loaded=${outcome.states.count { it.value is PartExpandState.Loaded }}/" +
                "${outcome.states.size}",
        )
    }
}

/**
 * §defect-B-2B / G6: true when the session is actively being written —
 * text/token streaming OR a busy/retry tool turn (SSE rewrites tool parts via
 * `message.part.updated` even when no text overlay is live). Auto-expand must
 * neither start nor commit while this is true, to avoid the expand-vs-SSE
 * concurrent-overwrite orphan.
 */
private fun SharedStateStore.isSessionActivelyWriting(sid: String): Boolean {
    val chat = chatFlow.value
    if (chat.streamingPartTexts.isNotEmpty() || chat.hasActiveTokenStreamOwner()) return true
    val status = sessionListFlow.value.sessionStatuses[sid]
    return status != null && (status.isBusy || status.isRetry)
}

private const val TAG = "AutoExpandOmitted"

/** §defect-B-2B: default most-recent-message window for auto-expand. */
internal const val DEFAULT_RECENT_MESSAGE_BUDGET: Int = 15
