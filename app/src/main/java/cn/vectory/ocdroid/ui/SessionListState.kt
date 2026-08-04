package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.data.model.TodoItem

/**
 * I-2 v2 §3.3: observable completeness signal for slimapi
 * `/questions` + `/permissions` aggregation folds. Stored on
 * [SessionListState] as `questionAggregationSignal` /
 * `permissionAggregationSignal` so the UI can render an "incomplete"
 * indicator or a retry button without re-fetching.
 *
 * Lifecycle: reset to defaults on cross-group
 * [AppAction.HostStatePurged] (mirrors `sessionErrorsById`); same-group
 * switches preserve the prior signal (server-identical data is still
 * authoritative).
 */
enum class SlimAggregationCompleteness {
    /** All requested directories returned successfully (or no errors). */
    COMPLETE,

    /**
     * Some requested directories returned an upstream error; items from
     * proven-successful directories are folded. [SlimAggregationSignal.failedSources]
     * carries the per-directory cause.
     */
    INCOMPLETE,

    /**
     * The whole aggregation call failed (transport / HTTP / decode).
     * Prior state is preserved; [SlimAggregationSignal.failureMessage]
     * carries the cause. Surfaces a UiEvent.Error toast.
     */
    FAILED,
}

/**
 * I-2 v2 §3.3: per-directory failed-source descriptor carried on
 * [SlimAggregationSignal.failedSources] when completeness is
 * [SlimAggregationCompleteness.INCOMPLETE].
 */
data class SlimAggregationFailedSource(
    val directory: String? = null,
    val code: String? = null,
)

/**
 * I-2 v2 §3.3: observable aggregation-completeness signal stored on
 * [SessionListState] for both `/questions` and `/permissions` folds.
 * Default is `COMPLETE` (no signal / fresh state).
 */
data class SlimAggregationSignal(
    val completeness: SlimAggregationCompleteness = SlimAggregationCompleteness.COMPLETE,
    val failedSources: List<SlimAggregationFailedSource> = emptyList(),
    val failureMessage: String? = null,
)

  /**
   * §R-17 M4: session-list-domain state slice (RFC §2.3). Authoritative storage
   * lives in [MainViewModel._sessionListFlow]. Low-frequency (loadSessions /
   * loadMore / SSE session.created/updated); isolating it stops SSE chat deltas
   * from recomposing SessionsScreen.
   *
   * §P0-A rev-gpt #8 B10 (non-data-class encapsulation): converted from a
   * `data class` to a regular `class`. [sessionStatuses] is a class-body `var`
   * with `private set` — NOT a constructor parameter — so the ONLY way to set
   * it is the `internal` [withProjection] method (called exclusively by
   * [reduceAuthority]). This compile-enforces the "sessionStatuses sole writer
   * = reduceAuthority" invariant: `SessionListState(sessionStatuses = …)` and
   * `.copy(sessionStatuses = …)` BOTH FAIL TO COMPILE. Manual `equals` /
   * `hashCode` / `toString` over ALL fields preserve `StoreState` value
   * equality (StoreState is a data class containing this).
   */
class SessionListState internal constructor(
    val sessions: List<Session> = emptyList(),
    /**
     * Process-wide sessions whose server drain fiber is currently running.
     * Maintained by the status poller. Fetch failures retain the last snapshot
     * (fail-closed); host transitions clear it explicitly.
     */
    val activeSessionIds: Set<String> = emptySet(),
    val expandedSessionIds: Set<String> = emptySet(),
    val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
    val hasMoreSessions: Boolean = true,
    val isLoadingMoreSessions: Boolean = false,
    val isRefreshingSessions: Boolean = false,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val pendingQuestions: List<QuestionRequest> = emptyList(),
    val childSessions: Map<String, List<Session>> = emptyMap(),
    /** Roots whose complete descendant tree was fetched successfully. */
    val completeRootIds: Set<String> = emptySet(),
    /**
     * §gpter-blocker (v097 review-fix): monotonic completeness invalidation
     * epoch. Bumped by every structural mutation that invalidates cached
     * completeness proofs ([upsertAndInvalidateTree] on SSE session.created /
     * session.updated, and the REST structural replaces in
     * [launchLoadSessions] / [launchLoadMoreSessions]). Hydration paths
     * capture this value at START and, at COMMIT, only re-certify roots if
     * the epoch is unchanged — an in-flight hydration that straddled an
     * invalidation is dropped (fail-closed) so a stale snapshot can never
     * re-add a root to [completeRootIds] after the tree was invalidated.
     */
    val completenessEpoch: Long = 0L,
    val directorySessions: Map<String, List<Session>> = emptyMap(),
    // §B4 / chat-list-detail §9.1 D9: open-tabs-list removed. List-detail has
    // a single detail pane driven by route id (`chat/{id}`); there is no tab
    // strip open-set to maintain. Legacy ESP key is ignored on read (§16).
    val sessionTodos: Map<String, List<TodoItem>> = emptyMap(),
    /** §issue-1(1): per-session 文件变更快照（session.diff SSE / GET /session/{id}/diff）。
     *  key = sessionId，value = 该会话累计的 FileDiff 列表。仅在打开会话时拉取 +
     *  SSE 增量更新；驱动聊天内 SessionDiffCard。 */
    val sessionDiffs: Map<String, List<cn.vectory.ocdroid.data.model.FileDiff>> = emptyMap(),
    /**
     * Task 12 (slimapi v1 §2 / §6.1 + §G2 session.error semantics): the
     * canonical per-session upstream-error banner store. Keyed by sessionId;
     * value is the latest [SlimSessionLastError] the sidecar surfaced for
     * that session. UIs (StatusSlot / SessionRetryCard / chat row banner)
     * read this map directly — there is NO separate banner abstraction.
     *
     * # Producers (this slice's [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator])
     *
     *  - `session.error` SSE with a `sessionID` →
     *    `sessionErrorsById[sid] = SlimSessionLastError(...)` (durable
     *    banner). A session.error WITHOUT a `sessionID` is routed to a
     *    global toast only and does NOT touch this map (the sidecar
     *    signals a session-less failure; no banner to render).
     *  - `session.digest` with `lastError` three-state:
     *    [cn.vectory.ocdroid.data.model.LastErrorField.Set] →
     *    `sessionErrorsById[sid] = error`;
     *    [cn.vectory.ocdroid.data.model.LastErrorField.Cleared] →
     *    `sessionErrorsById - sid` (sidecar signals recovery);
     *    [cn.vectory.ocdroid.data.model.LastErrorField.Omitted] → no
     *    change (debounce tick without lastError must not strand an
     *    active banner).
     *
     * # Idempotency / concurrency (T12-C3)
     *
     * Writes go through `MutableStateFlow.update` (CAS) so concurrent
     * by-sid writes serialize per-key — duplicate Set or duplicate
     * session.error frames leave the map in the same state as a single
     * application. The T11 per-sid stripe further serializes the
     * digest-driven reconcile workflow (which advances the repo's own
     * lastError merge), so digest + session.error for the same sid
     * compose without interleaving inside the reconcile body.
     *
     * # NOT a banner abstraction (T12-C4)
     *
     * The map is the canonical store. There is no
     * `repository.applySessionErrorBanner` / `sessionBanners` indirection
     * — the coordinator writes the map directly via `mutateSessionList`.
     *
     * # Lifecycle cleanup (final-gate I-3)
     *
     * T12 owns the producer path (SET on error, REMOVE on `lastError =
     * Cleared`). Three lifecycle reducers ADDITIONALLY drop entries so the
     * map cannot leak across host / archive / delete boundaries (review-
     * final-rev-gpt-20260719081038 §2):
     *
     *  - [cn.vectory.ocdroid.ui.AppAction.HostStatePurged] (cross-group):
     *    `sessionErrorsById = emptyMap()` — old host's sid→error cannot
     *    survive a host switch; a root-id collision on the new host would
     *    otherwise render the prior host's banner. Same-group switches
     *    PRESERVE the map (server-identical data is still authoritative).
     *  - [cn.vectory.ocdroid.ui.AppAction.SessionArchived]: prunes the
     *    archived subtree's entries (defensive — covers descendants that
     *    did NOT receive their own archive event). Atomically committed
     *    with the archive so collectors never observe a stale "archived
     *    but still errored" torn state.
     *  - [cn.vectory.ocdroid.ui.launchDeleteSession]: prunes the deleted
     *    subtree's entries in the same `mutateSessionList` block as the
     *    sessions / pendingQuestions purge.
     *
     * These lifecycle reductions do NOT change T12's set/remove logic;
     * they close the retention gaps the final-gate review identified.
     */
    val sessionErrorsById: Map<String, SlimSessionLastError> = emptyMap(),
    /**
     * I-2 v2 §3.3: observable completeness signal for the latest
     * slimapi `/questions` aggregation fold. Drives UI affordances
     * (retry button, "1 directory unavailable" banner). Reset to
     * [SlimAggregationSignal] (COMPLETE) on cross-group
     * [AppAction.HostStatePurged] so the prior host's signal cannot
     * leak into the new host.
     */
    val questionAggregationSignal: SlimAggregationSignal = SlimAggregationSignal(),
    /**
     * I-2 v2 §3.3: observable completeness signal for the latest
     * slimapi `/permissions` aggregation fold. Mirrors
     * [questionAggregationSignal].
     */
    val permissionAggregationSignal: SlimAggregationSignal = SlimAggregationSignal(),
    /**
     * §Q4-strict-sync: ids of sessions freshly created locally (via
     * [cn.vectory.ocdroid.ui.AppCore.materializeDraftSession] /
     * [cn.vectory.ocdroid.ui.launchCreateSession]) that have NOT yet been
     * confirmed by an authoritative server refresh or a matching
     * session.created / session.updated SSE.
     *
     * Drives [cn.vectory.ocdroid.ui.mergeRefreshedSessionsPreservingLocalActivity]:
     * the final sessions list is `authoritative ∪ local.filter { id in
     * pendingCreateIds }`. This replaces the legacy open/current-id ghost
     * retention (which kept locally-opened sessions alive indefinitely even
     * after the server deleted them). A pending id is removed the moment it
     * surfaces in a REST refresh or an SSE event, or after a 30 s sweep
     * (trust the server).
     *
     * Cleared on host switch / reset (see [AppAction.HostStatePurged]) so
     * pending ids from host A cannot ghost into host B's list.
     */
    val pendingCreateIds: Set<String> = emptySet(),
    /**
     * Wall-clock registration time for every id in [pendingCreateIds]. This is
     * deliberately independent of [Session.time]: locally-created sessions may
     * have no server creation timestamp yet. Entries are added and removed in
     * lockstep with [pendingCreateIds].
     */
    val pendingCreatedAt: Map<String, Long> = emptyMap(),
    /**
     * §B4 / chat-list-detail §10 cold-start: flipped true on the first
     * successful [launchLoadSessions] commit; reset on cross-group host
     * purge. Pre-B4 this gated open-tab auto-select; post-B4 auto-select
     * from open-tabs-list is gone (route is always Sessions on cold start).
     * The flag still marks "first refresh completed" for load UI / residual
     * current-clear decisions that must not invent sessions.first().
     */
    val hasCompletedInitialLoad: Boolean = false,
    /**
     * §P0-F (R5/R6): sessions with an in-flight abort POST (client-only flag,
     * NOT server status). Key = sessionId, value = unique monotonic token
     * ([java.util.concurrent.atomic.AtomicLong.incrementAndGet]) used by the
     * abort watchdog for ABA-safe token verification. Distinct from
     * [ComposerState.sendingSessionIds] (the POST-send short-bridge, R5).
     *
     * Lifecycle: set by [ChatViewModel.abortSession] at dispatch; cleared by
     * [applySessionStatus] when the server delivers a non-running status (idle
     * / terminal), by [ChatViewModel.abortSession] onFailure, by the watchdog
     * on timeout, and by lifecycle reducers (EvictSession / archive / delete /
     * cross-group host purge). Distinct from [ComposerState.sendingSessionIds]
     * (the POST-send short-bridge, R5).
     */
    val abortPendingSessionIds: Map<String, Long> = emptyMap(),
) {
    /**
     * §P0-A rev-gpt #8 B10 factory gate (revised): [sessionStatuses] is a
     * class-body `var` with `private set` — it is NOT a constructor parameter,
     * so a `SessionListState(sessionStatuses = …)` call FAILS TO COMPILE.
     * The ONLY way to set it is the `internal` [withProjection] method
     * (called exclusively by [reduceAuthority]). Same-module code CANNOT
     * bypass the sole-writer gate by passing it to the constructor.
     */
    var sessionStatuses: Map<String, SessionStatus> = emptyMap()
        private set

    /**
     * §P0-A rev-gpt #8 B10: manual `copy` that accepts EVERY field EXCEPT
     * [sessionStatuses]. A `.copy(sessionStatuses = …)` call FAILS TO COMPILE
     * (no such param) — that IS the sole-writer gate. Use [withProjection] to
     * set sessionStatuses (called exclusively by [reduceAuthority]).
     */
    fun copy(
        sessions: List<Session> = this.sessions,
        activeSessionIds: Set<String> = this.activeSessionIds,
        expandedSessionIds: Set<String> = this.expandedSessionIds,
        loadedSessionLimit: Int = this.loadedSessionLimit,
        hasMoreSessions: Boolean = this.hasMoreSessions,
        isLoadingMoreSessions: Boolean = this.isLoadingMoreSessions,
        isRefreshingSessions: Boolean = this.isRefreshingSessions,
        pendingPermissions: List<PermissionRequest> = this.pendingPermissions,
        pendingQuestions: List<QuestionRequest> = this.pendingQuestions,
        childSessions: Map<String, List<Session>> = this.childSessions,
        completeRootIds: Set<String> = this.completeRootIds,
        completenessEpoch: Long = this.completenessEpoch,
        directorySessions: Map<String, List<Session>> = this.directorySessions,
        sessionTodos: Map<String, List<TodoItem>> = this.sessionTodos,
        sessionDiffs: Map<String, List<cn.vectory.ocdroid.data.model.FileDiff>> = this.sessionDiffs,
        sessionErrorsById: Map<String, SlimSessionLastError> = this.sessionErrorsById,
        questionAggregationSignal: SlimAggregationSignal = this.questionAggregationSignal,
        permissionAggregationSignal: SlimAggregationSignal = this.permissionAggregationSignal,
        pendingCreateIds: Set<String> = this.pendingCreateIds,
        pendingCreatedAt: Map<String, Long> = this.pendingCreatedAt,
        hasCompletedInitialLoad: Boolean = this.hasCompletedInitialLoad,
        abortPendingSessionIds: Map<String, Long> = this.abortPendingSessionIds,
    ): SessionListState = SessionListState(
        sessions = sessions,
        activeSessionIds = activeSessionIds,
        expandedSessionIds = expandedSessionIds,
        loadedSessionLimit = loadedSessionLimit,
        hasMoreSessions = hasMoreSessions,
        isLoadingMoreSessions = isLoadingMoreSessions,
        isRefreshingSessions = isRefreshingSessions,
        pendingPermissions = pendingPermissions,
        pendingQuestions = pendingQuestions,
        childSessions = childSessions,
        completeRootIds = completeRootIds,
        completenessEpoch = completenessEpoch,
        directorySessions = directorySessions,
        sessionTodos = sessionTodos,
        sessionDiffs = sessionDiffs,
        sessionErrorsById = sessionErrorsById,
        questionAggregationSignal = questionAggregationSignal,
        permissionAggregationSignal = permissionAggregationSignal,
        pendingCreateIds = pendingCreateIds,
        pendingCreatedAt = pendingCreatedAt,
        hasCompletedInitialLoad = hasCompletedInitialLoad,
        abortPendingSessionIds = abortPendingSessionIds,
    ).also { copy ->
        copy.sessionStatuses = this.sessionStatuses
    }

    /**
     * §P0-A rev-gpt #8 B10: the SOLE way to set [sessionStatuses]. `internal`
     * — called exclusively by [reduceAuthority] (the sole writer of the
     * authority projection). Returns a copy with just sessionStatuses changed.
     */
    internal fun withProjection(sessionStatuses: Map<String, SessionStatus>): SessionListState =
        // §MN-P3 (U-MN3): delegate to [copy] (propagates this.sessionStatuses via
        // its .also block below at :995-997), then OVERWRITE with the new projection.
        // Replaces the duplicated 20+ field enumeration that drifted from copy().
        copy().also { it.sessionStatuses = sessionStatuses }

    /**
     * §P0-A rev-gpt #8 B10: manual `equals` over ALL fields (including
     * [sessionStatuses]). Required because losing data-class `equals` would
     * break [StoreState] value equality (StoreState is a data class containing
     * this field) → breaks assertEquals in tests + StateFlow.distinctUntilChanged.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionListState) return false
        return sessions == other.sessions &&
            sessionStatuses == other.sessionStatuses &&
            activeSessionIds == other.activeSessionIds &&
            expandedSessionIds == other.expandedSessionIds &&
            loadedSessionLimit == other.loadedSessionLimit &&
            hasMoreSessions == other.hasMoreSessions &&
            isLoadingMoreSessions == other.isLoadingMoreSessions &&
            isRefreshingSessions == other.isRefreshingSessions &&
            pendingPermissions == other.pendingPermissions &&
            pendingQuestions == other.pendingQuestions &&
            childSessions == other.childSessions &&
            completeRootIds == other.completeRootIds &&
            completenessEpoch == other.completenessEpoch &&
            directorySessions == other.directorySessions &&
            sessionTodos == other.sessionTodos &&
            sessionDiffs == other.sessionDiffs &&
            sessionErrorsById == other.sessionErrorsById &&
            questionAggregationSignal == other.questionAggregationSignal &&
            permissionAggregationSignal == other.permissionAggregationSignal &&
            pendingCreateIds == other.pendingCreateIds &&
            pendingCreatedAt == other.pendingCreatedAt &&
            hasCompletedInitialLoad == other.hasCompletedInitialLoad &&
            abortPendingSessionIds == other.abortPendingSessionIds
    }

    /** §P0-A rev-gpt #8 B10: manual `hashCode` over ALL fields (mirrors [equals]). */
    override fun hashCode(): Int {
        var result = sessions.hashCode()
        result = 31 * result + sessionStatuses.hashCode()
        result = 31 * result + activeSessionIds.hashCode()
        result = 31 * result + expandedSessionIds.hashCode()
        result = 31 * result + loadedSessionLimit
        result = 31 * result + hasMoreSessions.hashCode()
        result = 31 * result + isLoadingMoreSessions.hashCode()
        result = 31 * result + isRefreshingSessions.hashCode()
        result = 31 * result + pendingPermissions.hashCode()
        result = 31 * result + pendingQuestions.hashCode()
        result = 31 * result + childSessions.hashCode()
        result = 31 * result + completeRootIds.hashCode()
        result = 31 * result + completenessEpoch.hashCode()
        result = 31 * result + directorySessions.hashCode()
        result = 31 * result + sessionTodos.hashCode()
        result = 31 * result + sessionDiffs.hashCode()
        result = 31 * result + sessionErrorsById.hashCode()
        result = 31 * result + questionAggregationSignal.hashCode()
        result = 31 * result + permissionAggregationSignal.hashCode()
        result = 31 * result + pendingCreateIds.hashCode()
        result = 31 * result + pendingCreatedAt.hashCode()
        result = 31 * result + hasCompletedInitialLoad.hashCode()
        result = 31 * result + abortPendingSessionIds.hashCode()
        return result
    }

    /** §P0-A rev-gpt #8 B10: manual `toString` (matches prior data-class behavior). */
    override fun toString(): String =
        "SessionListState(sessions=$sessions, sessionStatuses=$sessionStatuses, " +
            "activeSessionIds=$activeSessionIds, expandedSessionIds=$expandedSessionIds, " +
            "loadedSessionLimit=$loadedSessionLimit, hasMoreSessions=$hasMoreSessions, " +
            "isLoadingMoreSessions=$isLoadingMoreSessions, isRefreshingSessions=$isRefreshingSessions, " +
            "pendingPermissions=$pendingPermissions, pendingQuestions=$pendingQuestions, " +
            "childSessions=$childSessions, completeRootIds=$completeRootIds, " +
            "completenessEpoch=$completenessEpoch, directorySessions=$directorySessions, " +
            "sessionTodos=$sessionTodos, sessionDiffs=$sessionDiffs, " +
            "sessionErrorsById=$sessionErrorsById, " +
            "questionAggregationSignal=$questionAggregationSignal, " +
            "permissionAggregationSignal=$permissionAggregationSignal, " +
            "pendingCreateIds=$pendingCreateIds, pendingCreatedAt=$pendingCreatedAt, " +
            "hasCompletedInitialLoad=$hasCompletedInitialLoad, " +
            "abortPendingSessionIds=$abortPendingSessionIds)"

    companion object {
        /** §P0-A rev-gpt #8 B10: public no-arg factory (delegates to the internal
         *  constructor with all defaults). Matches the pre-B10 `SessionListState()` usage. */
        operator fun invoke(): SessionListState = SessionListState()

        /** §P0-A rev-gpt #8 B10 r2 (sole-writer gate, revised): public
         *  factory for named-arg construction. [sessionStatuses] is NOT a
         *  constructor parameter (it is a class-body `var` with `private set`),
         *  so a `SessionListState(sessionStatuses = …)` call FAILS TO COMPILE
         *  (the gate). The ONLY way to set a non-empty sessionStatuses is the
         *  `internal` [withProjection] (called exclusively by [reduceAuthority]);
         *  this factory always seeds `sessionStatuses = emptyMap()`. */
        operator fun invoke(
            sessions: List<Session> = emptyList(),
            activeSessionIds: Set<String> = emptySet(),
            expandedSessionIds: Set<String> = emptySet(),
            loadedSessionLimit: Int = cn.vectory.ocdroid.ui.MainViewModelTimings.sessionPageSize,
            hasMoreSessions: Boolean = true,
            isLoadingMoreSessions: Boolean = false,
            isRefreshingSessions: Boolean = false,
            pendingPermissions: List<PermissionRequest> = emptyList(),
            pendingQuestions: List<QuestionRequest> = emptyList(),
            childSessions: Map<String, List<Session>> = emptyMap(),
            completeRootIds: Set<String> = emptySet(),
            completenessEpoch: Long = 0L,
            directorySessions: Map<String, List<Session>> = emptyMap(),
            sessionTodos: Map<String, List<TodoItem>> = emptyMap(),
            sessionDiffs: Map<String, List<cn.vectory.ocdroid.data.model.FileDiff>> = emptyMap(),
            sessionErrorsById: Map<String, SlimSessionLastError> = emptyMap(),
            questionAggregationSignal: SlimAggregationSignal = SlimAggregationSignal(),
            permissionAggregationSignal: SlimAggregationSignal = SlimAggregationSignal(),
            pendingCreateIds: Set<String> = emptySet(),
            pendingCreatedAt: Map<String, Long> = emptyMap(),
            hasCompletedInitialLoad: Boolean = false,
            abortPendingSessionIds: Map<String, Long> = emptyMap(),
        ): SessionListState = SessionListState(
            sessions = sessions,
            activeSessionIds = activeSessionIds,
            expandedSessionIds = expandedSessionIds,
            loadedSessionLimit = loadedSessionLimit,
            hasMoreSessions = hasMoreSessions,
            isLoadingMoreSessions = isLoadingMoreSessions,
            isRefreshingSessions = isRefreshingSessions,
            pendingPermissions = pendingPermissions,
            pendingQuestions = pendingQuestions,
            childSessions = childSessions,
            completeRootIds = completeRootIds,
            completenessEpoch = completenessEpoch,
            directorySessions = directorySessions,
            sessionTodos = sessionTodos,
            sessionDiffs = sessionDiffs,
            sessionErrorsById = sessionErrorsById,
            questionAggregationSignal = questionAggregationSignal,
            permissionAggregationSignal = permissionAggregationSignal,
            pendingCreateIds = pendingCreateIds,
            pendingCreatedAt = pendingCreatedAt,
            hasCompletedInitialLoad = hasCompletedInitialLoad,
            abortPendingSessionIds = abortPendingSessionIds,
        )
    }
}
