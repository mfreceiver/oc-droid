package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.WorkdirPaths
import cn.vectory.ocdroid.util.runSuspendCatching

/**
 * §Wave2.1-split-l2: shared types and free functions that were originally
 * part of AppCoreOrchestration.kt (~1581 LOC). The bulk of the orchestration
 * logic was extracted into 5 dedicated orchestrator classes:
 *  - [SessionOpener] (session opening / selection)
 *  - [RefreshOrchestrator] (hydration, refresh, catch-up)
 *  - [SendOrchestrator] (send dispatch, captured send)
 *  - [DraftSessionOrchestrator] (draft materialization, route adoption)
 *  - [CommandOrchestrator] (slash-command execution)
 *
 * This file retains the shared data types, free predicates, and
 * [resolveQuestionDirectory] — the one remaining cross-domain extension.
 */

// ════════════════════════════════════════════════════════════════════════════
// Shared data types (used by multiple orchestrators)
// ════════════════════════════════════════════════════════════════════════════

/**
 * §unified-nav (A5.1) + §blocker1: the IMMUTABLE send payload captured at
 * send-entry time, BEFORE materializeDraftSession. Do NOT re-read the composer
 * after createSession (the user may have typed more; the route adoption's
 * clearedChat may have wiped inputText). The captured values are what goes on
 * the wire.
 *
 * [agent] / [model] are the RESOLVED values (pendingAgent ?: infer ?: null)
 * at send-click, NOT a re-inference after the route flip.
 *
 */
data class CapturedSendPayload(
    val text: String,
    val attachments: List<ComposerImageAttachment>,
    val agent: String?,
    val model: Message.ModelInfo?,
)

/**
 * §unified-nav (A5.3) + §blocker2: the snapshot of the draft surface captured at
 * send-click time, used by [adoptMaterializedSessionRoute]'s CAS to decide
 * whether the user is STILL on the draft surface when createSession returns
 * (the network round-trip is a suspend point; the user may have navigated away
 * mid-create). If [stillOwnsDraftSurface] returns false against the live
 * committed [StoreState], the adoption is list-only (session upsert +
 * pendingCreate, NO route/nav/content transition) — the user left the draft
 * surface, so the route must NOT hijack the new screen.
 *
 * §blocker2: [hostProfileId] is captured from [HostState.currentHostProfileId]
 * (a [StoreState] field that IS updated on host switch — see
 * HostProfileController / ConnectionActions). A host/profile switch during the
 * createSession suspend boundary clears currentSessionId/content/draftWorkdir
 * but does NOT advance [StoreState.chatRouteInstance] or nav identity, so the
 * pre-blocker2 ownership predicate would still pass and the old-host response
 * would write into the WRONG host's state. Adding the host-identity comparison
 * makes a mid-create host switch fail the lease → list-only adoption.
 */
internal data class DraftRouteOrigin(
    val activeDestination: String?,
    val activeDestinationEpoch: Long,
    val requestedRoute: String,
    val requestedNavEpoch: Long,
    val routeInstance: Long,
    val profileId: String,
    /** §blocker2: the host profile id at send-click; compared inside the CAS. */
    val hostProfileId: String?,
) {
    /**
     * §unified-nav (A5.3) + §blocker2: returns true IFF the live committed
     * [before] still matches the snapshot captured at send-click. A mismatch
     * means the user navigated away mid-create (activeDestination/epoch changed,
     * OR currentSessionId is no longer null — another session was selected, OR
     * the nav identity changed — a route/navEpoch transition, OR the route-
     * instance token advanced — a chat open/close happened, OR the host identity
     * changed — a host/profile switch). In that case the adoption is list-only.
     */
    fun stillOwnsDraftSurface(before: StoreState): Boolean =
        before.nav.activeDestination == activeDestination &&
            before.nav.activeDestinationEpoch == activeDestinationEpoch &&
            before.chat.currentSessionId == null &&
            before.nav.lastRoute == requestedRoute &&
            before.nav.navEpoch == requestedNavEpoch &&
            before.chatRouteInstance == routeInstance &&
            before.host.currentHostProfileId == hostProfileId
}

/**
 * §unified-nav (A5.3): the result of [adoptMaterializedSessionRoute]. [adopted]
 * is true IFF the route/nav/content transition committed atomically. [routeInstance]
 * is the minted token (read from the committed snapshot) when adopted, null
 * otherwise. Callers gate the post-CAS ordering (A-E) on [adopted] / a non-null
 * [routeInstance].
 */
internal data class MaterializedRouteAdoption(
    val sessionId: String,
    val routeInstance: Long?,
    val adopted: Boolean,
)

// ════════════════════════════════════════════════════════════════════════════
// resolveQuestionDirectory (remaining cross-domain extension on AppCore)
// ════════════════════════════════════════════════════════════════════════════

/**
 * §R18 Phase 2-E step 1 → §issue-1 Phase 2a Fix A: resolves the directory
 * header to attach to a question reply/reject for [requestId]. Now `suspend`
 * so it can fetch the parent session from the server when it is missing
 * locally (the schema confirms `question.asked` carries NO directory field,
 * so the fetch is the only way to recover the workdir for a not-yet-local
 * session).
 *
 * Resolution order:
 *  1. The pending question's parent session's directory IF the session is
 *     already in `sessions ∪ directorySessions` with a non-blank directory
 *     (handles cross-workdir routing; no network). Unchanged from Phase 1.
 *  2. Otherwise `GET /session/{sessionId}` (already Skip-Dir), then a CONDITIONAL
 *     CAS: inside `writeSessionList` (which reads the latest state atomically),
 *     re-check the session — if a fresher entry was hydrated by another load/SSE
 *     during the network wait (non-blank dir), keep it and return ITS directory;
 *     otherwise upsert `fetched` into `sessions` (so this + later resolves hit
 *     branch 1) and return `fetched.directory`. Mirrors [openSessionFromDeepLink].
 *  3. fetch fail / null / blank directory → `null`. `null` means "let the
 *     server self-lookup via process.cwd()" — an INTENTIONAL degrade (observable
 *     via DebugLog + Fix C's UiEvent) rather than the old silent wrong-value
 *     bug where `currentWorkdir` was returned even when it mismatched the
 *     question's real workdir. The `settingsManager.currentWorkdir` fallback is
 *     deliberately DROPPED from this function.
 *
 * suspend-safe: the only production callers are
 * [OrchestratorViewModel.replyQuestion] + [OrchestratorViewModel.rejectQuestion],
 * both inside `viewModelScope.launch`.
 */
internal suspend fun AppCore.resolveQuestionDirectory(requestId: String): String? {
    val pending = store.sessionListFlow.value.pendingQuestions.firstOrNull { it.id == requestId }
    val sessionId = pending?.sessionId
    if (sessionId == null) {
        DebugLog.d("Question", "resolveQuestionDirectory req=$requestId sid=null(no pending) branch=3(no-pending) return=null")
        return null
    }
    val session = (
        store.sessionListFlow.value.sessions +
            store.sessionListFlow.value.directorySessions.values.flatten()
        ).firstOrNull { it.id == sessionId && !it.directory.isNullOrBlank() }
    if (session != null) {
        DebugLog.d(
            "Question",
            "resolveQuestionDirectory req=$requestId sid=$sessionId parentFound=true dir=\"${session.directory}\" branch=1(session.directory) return=\"${session.directory}\""
        )
        return session.directory
    }
    val fetched = runSuspendCatching { repository.getSession(sessionId).getOrNull() }.getOrNull()
    if (fetched == null || fetched.directory.isNullOrBlank()) {
        DebugLog.d(
            "Question",
            "resolveQuestionDirectory req=$requestId sid=$sessionId parentFound=false dir=${session?.directory ?: "null"} branch=3(fetch-fail/null) fetchedDir=${fetched?.directory ?: "null"} return=null"
        )
        return null
    }
    val fetchedDir = fetched.directory
    val resolved: String?
    val casPath: String
    val st = store.sessionListFlow.value
    val current = (st.sessions + st.directorySessions.values.flatten())
        .firstOrNull { it.id == sessionId && !it.directory.isNullOrBlank() }
    if (current != null) {
        resolved = current.directory
        casPath = "fetch-hit-fresher-kept"
    } else {
        resolved = fetchedDir
        casPath = "fetch-hit-cached"
        store.dispatch(AppAction.SessionUpserted(fetched))
    }
    DebugLog.d(
        "Question",
            "resolveQuestionDirectory req=$requestId sid=$sessionId parentFound=false dir=${session?.directory ?: "null"} branch=2($casPath) fetchedDir=\"${fetchedDir}\" return=\"${resolved}\""
    )
    return resolved
}

// ════════════════════════════════════════════════════════════════════════════
// Free predicates / utilities (no AppCore coupling)
// ════════════════════════════════════════════════════════════════════════════

/**
 * §issue-1 Phase 2a Fix B (§rev-ds round-2 FIX 1): the ONE shared workdir-set
 * computation for the LEGACY pending-question fan-out. Used by RefreshOrchestrator
 * to pass the full pre-P3 directory set to ForegroundCatchUpController (the slim
 * path ignores this argument and makes a single global call).
 *
 * Unions `directorySessionKeys` + `currentWorkdir` + `recentWorkdirs`, normalizes
 * each via [WorkdirPaths.normalizeDirectory], filters blank, and distincts.
 */
internal fun computeQuestionFanOutWorkdirs(
    directorySessionKeys: Set<String>,
    currentWorkdir: String?,
    recentWorkdirs: List<String>,
): List<String> =
    (directorySessionKeys + listOfNotNull(currentWorkdir) + recentWorkdirs)
        .filter { it.isNotBlank() }
        .map { WorkdirPaths.normalizeDirectory(it) }
        .distinct()

/**
 * §token-stream-open-gate: the shared predicate for whether to open the
 * per-session token stream when loading messages.
 */
internal fun shouldOpenTokenStream(
    tokenStreamEnabled: Boolean,
    currentSessionId: String?,
    targetSessionId: String,
): Boolean = tokenStreamEnabled && currentSessionId == targetSessionId

/**
 * §sse-rest-fallback (TODO 3): how long SSE must stay terminally
 * [ConnectionPhase.Disconnected] before the AUTOMATIC cold-start upgrade
 * to an UNANCHORED fetch.
 */
internal const val SSE_DISCONNECT_UNANCHORED_THRESHOLD_MS = 90_000L

/**
 * §sse-rest-fallback (TODO 3): pure predicate — should the AUTOMATIC cold-start
 * upgrade to an UNANCHORED (since=0L) fetch?
 */
internal fun shouldAutoUnanchorOnColdStart(
    phase: cn.vectory.ocdroid.ui.ConnectionPhase,
    disconnectedSince: Long?,
    now: Long,
    thresholdMs: Long = SSE_DISCONNECT_UNANCHORED_THRESHOLD_MS,
): Boolean = phase.isSseDown &&
    disconnectedSince != null &&
    (now - disconnectedSince) >= thresholdMs

/**
 * §sse-feedback-ux: cadence at which the in-chat disconnect banner refreshes
 * its elapsed-time label.
 */
internal const val SSE_FEEDBACK_TICK_MS = 30_000L

// §Wave2.1-split-l2: extension function shims for functions moved into
// orchestrator classes. These preserve the original AppCore extension
// signatures so AppCoreOrchestrationTest continues to compile.
// Each shim accesses the orchestrator via AppCore's `internal lateinit var`
// fields (initialized by Hilt's @Inject method postInject).

internal fun AppCore.catchUpAfterDisconnectOrForeground(sessionId: String) =
    refreshOrchestrator.catchUpAfterDisconnectOrForeground(sessionId)

internal fun AppCore.loadMessagesForEffect(
    sessionId: String,
    resetLimit: Boolean,
    forceInitialWindow: Boolean = false,
    expectedRouteInstance: Long = 0L,
) = refreshOrchestrator.loadMessagesForEffect(sessionId, resetLimit, forceInitialWindow, expectedRouteInstance)

internal fun AppCore.materializeDraftSession(
    capturedPayload: CapturedSendPayload? = null,
    capturedCommandText: String? = null,
    commandPost: (suspend (sessionId: String) -> Unit)? = null,
) = draftSessionOrchestrator.materializeDraftSession(capturedPayload, capturedCommandText, commandPost)

/**
 * §grouping-rewrite Round-2 D2 (+ Round-3 N1): classify a `/command` POST
 * failure for the UI.
 */
internal fun classifyCommandPostError(error: Throwable, cmd: String): UiEvent {
    if (error is java.net.SocketTimeoutException) {
        val msg = error.message?.lowercase().orEmpty()
        val isConnectSide = "connect" in msg || "failed to connect" in msg
        if (!isConnectSide) {
            return UiEvent.Info(R.string.command_submitted_processing)
        }
    }
    return UiEvent.Error(
        R.string.error_command_failed,
        listOf(cmd, errorMessageOrFallback(error, "unknown error"))
    )
}

/** §unified-nav B: max fetch attempts for the title retry loop. */
internal const val TITLE_RETRY_MAX_ATTEMPTS = 6
