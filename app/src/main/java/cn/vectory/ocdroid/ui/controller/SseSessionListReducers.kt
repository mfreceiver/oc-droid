package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.data.repository.SlimAggregationOutcome
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.ui.lenientJson
import cn.vectory.ocdroid.ui.upsertSession
import cn.vectory.ocdroid.ui.withUpdatedAtLeast
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Pure file-level session-list / aggregation reducers,
 * relocated verbatim from [SessionSyncCoordinator] (Wave 1'-B lane L1a).
 *
 * PURE RELOCATION — no logic or signature changes. Same package
 * (`cn.vectory.ocdroid.ui.controller`) so callers need no import edits;
 * [SessionSyncCoordinator] keeps the orchestration + token-gate call sites.
 * [SlimAggregationFold] + [aggregationSignal] live here so
 * [applyAggregationOutcome] and its private helper [upsertAndInvalidateTree]
 * stay co-located with their only callers.
 */

/**
 * I-2 v2 §3.5: directory-scoped apply for aggregation outcomes.
 *
 * Returns a [SlimAggregationFold] carrying BOTH the merged items AND
 * the derived [SlimAggregationSignal] (completeness + failedSources
 * + failureMessage) so the caller can update the slice's observable
 * signal alongside the list. The signal is computed via
 * [aggregationSignal] (top-level helper).
 *
 * - [SlimAggregationOutcome.Failure] → keep prior unchanged.
 * - [SlimAggregationOutcome.Success] with `authoritativeDirectories=null`
 *   → complete replacement (including empty).
 * - [SlimAggregationOutcome.Success] / [SlimAggregationOutcome.Partial]
 *   with non-null `authoritativeDirectories` → delete prior only from
 *   directories proven successful; failed/unknown dirs survive.
 *
 * C-D3 v2 §3.5 (defensive filter): fetched entries attributed to
 * directories OUTSIDE the proven-authoritative set are REJECTED.
 * Pre-fix, the directory filter only decided which PRIOR entries to
 * keep — a misbehaving sidecar could ship out-of-scope items and
 * pollute the local cache.
 *
 * Fetched entries win on duplicate IDs.
 *
 * T2 (slimapi v0.2.2 client-adapt): scope-directories gate. When
 * [SlimAggregationOutcome.Success.serverScope] (or
 * [SlimAggregationOutcome.Partial.serverScope]) reports
 * `directories == 0`, the sidecar's allowlist is not yet ready and the
 * (possibly empty) `items` are NOT authoritative — return prior
 * unchanged (same as `Failure`). `directories > 0` means authoritative;
 * `serverScope == null` (pre-0.2.2 sidecar / 503) preserves the original
 * behavior. See task-2-brief.md.
 */
internal fun <Wire, Ui> applyAggregationOutcome(
    prior: List<Ui>,
    outcome: SlimAggregationOutcome<Wire>,
    wireToUi: (Wire) -> Ui,
    uiId: (Ui) -> String,
    uiDirectory: (Ui) -> String?,
): SlimAggregationFold<Ui> {
    val signal = aggregationSignal(outcome)

    when (outcome) {
        is SlimAggregationOutcome.Failure -> {
            return SlimAggregationFold(
                items = prior,
                signal = signal,
            )
        }

        is SlimAggregationOutcome.Success -> {
            // T2: scope not ready → retain prior (do NOT clear stale).
            if (outcome.serverScope?.directories == 0) {
                return SlimAggregationFold(
                    items = prior,
                    signal = signal,
                )
            }

            val scope = outcome.authoritativeDirectories

            val fetched = outcome.items
                .map(wireToUi)
                .let { mapped ->
                    if (scope == null) {
                        mapped
                    } else {
                        // C-D3 v2 §3.5 defensive: reject out-of-scope
                        // envelope items.
                        mapped.filter { item -> uiDirectory(item) in scope }
                    }
                }

            if (scope == null) {
                return SlimAggregationFold(
                    items = fetched.distinctBy(uiId),
                    signal = signal,
                )
            }

            val result = LinkedHashMap<String, Ui>()

            prior.asSequence()
                .filter { item -> uiDirectory(item) !in scope }
                .forEach { item -> result[uiId(item)] = item }

            fetched.forEach { item -> result[uiId(item)] = item }

            return SlimAggregationFold(
                items = result.values.toList(),
                signal = signal,
            )
        }

        is SlimAggregationOutcome.Partial -> {
            // T2: scope not ready → retain prior (do NOT clear stale).
            // Mirrors the Success-branch gate.
            if (outcome.serverScope?.directories == 0) {
                return SlimAggregationFold(
                    items = prior,
                    signal = signal,
                )
            }

            val scope = outcome.authoritativeDirectories

            // A Partial item is accepted only from a proven-success
            // source. Items attributed to failed, unknown, or out-of-
            // request directories cannot replace local state.
            val fetched = outcome.items
                .map(wireToUi)
                .filter { item -> uiDirectory(item) in scope }

            val result = LinkedHashMap<String, Ui>()

            prior.asSequence()
                .filter { item -> uiDirectory(item) !in scope }
                .forEach { item -> result[uiId(item)] = item }

            fetched.forEach { item -> result[uiId(item)] = item }

            return SlimAggregationFold(
                items = result.values.toList(),
                signal = signal,
            )
        }
    }
}

// ── §R-17 batch5 → §R-19 Sprint 3 P2-4: pure state transforms for each SSE event branch ──
//
// Each function takes the prior slice value + the event payload and returns
// `Pair<State, List<SseSideEffect>>` — the new state AND a list of side
// effects the dispatcher should commit via `applySseSideEffects`. Pure: no
// effect emits, no coroutine launches, no settings writes. All DebugLog /
// diagnostic calls live in the dispatcher (the `when` branches above), NOT
// inside these functions, so the transforms stay pure + testable. Most return
// `emptyList()` (the transform is pure state-only); the dispatcher adds
// cross-slice effects (which need context the single-slice function doesn't
// have) and routes the combined list.
//
// §R-19 Sprint 3 P2-4: the signature unification (all return Pair) is the
// formalization step — the dispatcher's CAS pattern is now uniform across all
// 11 branches, and the effect sequencing is auditable in one place.

/**
 * session.created → upsert the parsed [Session] into BOTH [SessionListState.sessions]
 * AND every matching [SessionListState.directorySessions] bucket (two-store pattern
 * — see [upsertAndInvalidateTree]). Pure; effects empty (the dispatcher handles
 * parse-failure via ReportNonFatal).
 *
 * §Q4-strict-sync: also removes [session].id from [SessionListState.pendingCreateIds]
 * — the server just confirmed the session exists, so it is no longer "pending".
 * This is the SSE-side confirmation path; the REST refresh (launchLoadSessions)
 * does the same removal idempotently. Idempotent with the 30 s sweep (which is
 * a fallback for ids that never get confirmed).
 */
internal fun SessionListState.applySessionCreated(session: Session): Pair<SessionListState, List<SseSideEffect>> =
    upsertAndInvalidateTree(session).copy(
        pendingCreateIds = pendingCreateIds - session.id,
        pendingCreatedAt = pendingCreatedAt - session.id,
    ) to emptyList()

/**
 * session.updated (non-archived) → upsert the parsed [Session] into BOTH
 * [SessionListState.sessions] AND every matching [SessionListState.directorySessions]
 * bucket (two-store pattern — see [upsertAndInvalidateTree]). Pure; effects empty.
 *
 * §Q4-strict-sync: also removes [updated].id from [SessionListState.pendingCreateIds]
 * — a session.updated event proves the server knows about this session, so it
 * is no longer "pending" (even if session.created was missed). Idempotent with
 * applySessionCreated and the REST sweep.
 */
internal fun SessionListState.applySessionUpsert(updated: Session): Pair<SessionListState, List<SseSideEffect>> =
    upsertAndInvalidateTree(updated).copy(
        pendingCreateIds = pendingCreateIds - updated.id,
        pendingCreatedAt = pendingCreatedAt - updated.id,
    ) to emptyList()

private fun SessionListState.upsertAndInvalidateTree(session: Session): SessionListState {
    val updatedSessions = upsertSession(sessions, session)
    // §title-sync: also propagate the upsert into every directorySessions
    // bucket (conditional replace — do NOT append to buckets that don't
    // already contain this session). A session shown from a directory bucket
    // (SessionsScreen + chat header read the union of stores) otherwise keeps
    // a stale field (e.g. the server's auto-generated title) until a REST
    // refresh replaces the bucket. Mirrors applyMessageTimestampBump's
    // two-store pattern.
    val updatedDirectorySessions = directorySessions.mapValues { (_, list) ->
        list.map { s -> if (s.id == session.id) session else s }
    }
    val byId = allSessionsById(updatedSessions, updatedDirectorySessions, childSessions)
    val rootId = rootIdOf(session.id, byId)
    return copy(
        sessions = updatedSessions,
        directorySessions = updatedDirectorySessions,
        // An unresolved parent chain cannot be attributed safely. Invalidate
        // every completeness proof rather than risk a false "all descendants
        // idle" result until hydration reconnects the new node to its root.
        completeRootIds = if (rootId == null) emptySet() else completeRootIds - rootId,
        // §gpter-blocker: bump the invalidation epoch so any in-flight
        // hydration (started before this SSE event) drops its result at
        // commit instead of re-certifying the now-stale root.
        completenessEpoch = completenessEpoch + 1L,
    )
}

/**
 * §recent-sort-by-message: when a session receives a message, bump its
 * [Session.time.updated] to `max(current, [updated])` so the
 * "recent sessions" surfaces (sortedByDescending { time.updated }) reflect
 * actual message activity rather than just the server-side session metadata
 * timestamp. Mirrors [applyArchiveEviction]'s two-store pattern: BOTH the
 * top-level `sessions` list AND every `directorySessions` bucket are bumped
 * (a session can be present in either / both stores, and both feed the
 * SessionsScreen / SessionPickerSheet recent derivations).
 *
 * Idempotent + monotonic (see [Session.TimeInfo.withUpdatedAtLeast]) — repeated
 * bumps for the same message are a no-op, and out-of-order / replayed events
 * never go backwards. Pure.
 */
internal fun SessionListState.applyMessageTimestampBump(
    sessionId: String,
    updated: Long
): Pair<SessionListState, List<SseSideEffect>> {
    if (updated <= 0L) return this to emptyList()
    val newSessions = sessions.map { s ->
        if (s.id == sessionId) s.copy(time = s.time.withUpdatedAtLeast(updated)) else s
    }
    val newDirectorySessions = directorySessions.mapValues { (_, list) ->
        list.map { s ->
            if (s.id == sessionId) s.copy(time = s.time.withUpdatedAtLeast(updated)) else s
        }
    }
    return copy(sessions = newSessions, directorySessions = newDirectorySessions) to emptyList()
}

/**
 * session.updated (archived) → upsert the session AND rewrite [openSessionIds]
 * to the caller-supplied [newOpenIds] (with the archived id evicted). The
 * caller computes [newOpenIds] + persists it via SettingsManager (a side
 * effect that stays inline). Pure; effects empty.
 */
internal fun SessionListState.applyArchiveEviction(
    updated: Session,
    newOpenIds: List<String>
): Pair<SessionListState, List<SseSideEffect>> = copy(
    sessions = upsertSession(sessions, updated),
    directorySessions = directorySessions.mapValues { (_, list) ->
        list.map { session -> if (session.id == updated.id) updated else session }
    },
    openSessionIds = newOpenIds
) to emptyList()

/**
 * session.status → upsert the [sessionId] → [status] pair into
 * [SessionListState.sessionStatuses]. Pure; effects empty — the busy-current /
 * idle-current finalization side effects are computed by the dispatcher (they
 * depend on cross-slice state: chat.currentSessionId,
 * chat.streamingPartTexts).
 */
internal fun SessionListState.applySessionStatus(
    sessionId: String,
    status: SessionStatus
): Pair<SessionListState, List<SseSideEffect>> =
    copy(sessionStatuses = sessionStatuses + (sessionId to status)) to emptyList()

/**
 * question.asked → append [question] to [SessionListState.pendingQuestions]
 * iff its id is not already present (idempotent). Pure.
 *
 * Cluster A / Phase 2: when a duplicate id arrives WITH a non-null
 * [QuestionRequest.routeToken] and the existing entry has null, upgrade the
 * stored entry (slim SSE may re-deliver with the token after a REST-only
 * insert).
 */
internal fun SessionListState.applyQuestionAsked(question: QuestionRequest): Pair<SessionListState, List<SseSideEffect>> {
    val existingIdx = pendingQuestions.indexOfFirst { it.id == question.id }
    if (existingIdx < 0) {
        return copy(pendingQuestions = pendingQuestions + question) to emptyList()
    }
    val existing = pendingQuestions[existingIdx]
    if (existing.routeToken.isNullOrBlank() && !question.routeToken.isNullOrBlank()) {
        val upgraded = pendingQuestions.toMutableList().also { it[existingIdx] = question }
        return copy(pendingQuestions = upgraded) to emptyList()
    }
    return this to emptyList()
}

/**
 * permission.asked (slim SSE with routeToken) → append/upgrade
 * [SessionListState.pendingPermissions] by id. Pure. Legacy path still
 * relies on [SseSideEffect.LoadPendingPermissions] REST refresh.
 */
internal fun SessionListState.applyPermissionAsked(permission: PermissionRequest): Pair<SessionListState, List<SseSideEffect>> {
    val existingIdx = pendingPermissions.indexOfFirst { it.id == permission.id }
    if (existingIdx < 0) {
        return copy(pendingPermissions = pendingPermissions + permission) to emptyList()
    }
    val existing = pendingPermissions[existingIdx]
    if (existing.routeToken.isNullOrBlank() && !permission.routeToken.isNullOrBlank()) {
        val upgraded = pendingPermissions.toMutableList().also { it[existingIdx] = permission }
        return copy(pendingPermissions = upgraded) to emptyList()
    }
    return this to emptyList()
}

/**
 * Cluster A / Phase 2: map a slimapi aggregate question entry onto the legacy
 * [QuestionRequest] UI model, **preserving [SlimapiQuestionEntry.routeToken]**.
 */
internal fun SlimapiQuestionEntry.toQuestionRequest(): QuestionRequest =
    QuestionRequest(
        id = id,
        sessionId = sessionId,
        questions = questions,
        tool = tool,
        directory = directory,
        routeToken = routeToken,
    )

/**
 * I-2 v2 §3.4: derive an observable [SlimAggregationSignal] from a
 * [SlimAggregationOutcome]. Top-level so both
 * [SessionSyncCoordinator] and [cn.vectory.ocdroid.ui.launchLoadPendingPermissionsSlim]
 * use the SAME implementation.
 */
internal fun <T> aggregationSignal(
    outcome: SlimAggregationOutcome<T>,
): cn.vectory.ocdroid.ui.SlimAggregationSignal = when (outcome) {
    is SlimAggregationOutcome.Success -> cn.vectory.ocdroid.ui.SlimAggregationSignal(
        completeness = cn.vectory.ocdroid.ui.SlimAggregationCompleteness.COMPLETE,
    )

    is SlimAggregationOutcome.Partial -> cn.vectory.ocdroid.ui.SlimAggregationSignal(
        completeness = cn.vectory.ocdroid.ui.SlimAggregationCompleteness.INCOMPLETE,
        failedSources = outcome.errors.map { error ->
            cn.vectory.ocdroid.ui.SlimAggregationFailedSource(
                directory = error.directory,
                code = error.code,
            )
        },
    )

    is SlimAggregationOutcome.Failure -> cn.vectory.ocdroid.ui.SlimAggregationSignal(
        completeness = cn.vectory.ocdroid.ui.SlimAggregationCompleteness.FAILED,
        failureMessage = outcome.message,
    )
}

/**
 * I-2 v2 §3.5: shared model returned by [applyAggregationOutcome].
 * Carries BOTH the merged items AND the derived
 * [cn.vectory.ocdroid.ui.SlimAggregationSignal] so the caller updates
 * the slice's signal atomically with the list.
 */
internal data class SlimAggregationFold<Ui>(
    val items: List<Ui>,
    val signal: cn.vectory.ocdroid.ui.SlimAggregationSignal,
)

/**
 * Cluster A / Phase 2: parse a `permission.asked` SSE frame into a
 * [PermissionRequest], including optional [PermissionRequest.routeToken]
 * from slim properties. Returns null on malformed payload.
 */
internal fun parsePermissionAskedEvent(event: SSEEvent): PermissionRequest? {
    val properties = event.payload.properties ?: return null
    return runCatching {
        lenientJson.decodeFromJsonElement<PermissionRequest>(properties)
    }.getOrNull()
}

/**
 * question.replied / question.rejected → drop the pending question whose id
 * matches [requestId]. Pure.
 */
internal fun SessionListState.applyQuestionResolved(requestId: String): Pair<SessionListState, List<SseSideEffect>> =
    copy(pendingQuestions = pendingQuestions.filter { it.id != requestId }) to emptyList()

/**
 * todo.updated → upsert [todos] under [sessionId] in
 * [SessionListState.sessionTodos]. Pure.
 */
internal fun SessionListState.applyTodoUpdated(
    sessionId: String,
    todos: List<TodoItem>
): Pair<SessionListState, List<SseSideEffect>> =
    copy(sessionTodos = sessionTodos + (sessionId to todos)) to emptyList()

/**
 * §issue-1(1): session.diff → 替换 [sessionId] 的文件变更快照。服务端每次发布的是
 * 该会话当前的完整 diff 列表（非增量），故整体替换。Pure。
 */
internal fun SessionListState.applySessionDiff(
    sessionId: String,
    diffs: List<cn.vectory.ocdroid.data.model.FileDiff>
): Pair<SessionListState, List<SseSideEffect>> =
    copy(sessionDiffs = sessionDiffs + (sessionId to diffs)) to emptyList()

/**
 * §issue-1(1) REST 预取专用：仅当 [sessionId] 尚无 diff 条目时写入（stale-overwrite
 * 守卫，glmer-S1）。SSE [applySessionDiff] 是权威源、无条件覆盖；REST 仅乐观预取，若
 * SSE 已推过更新则跳过，避免在途 REST 旧结果覆盖更新的 SSE 快照。抽成纯函数以便单测
 * （maxer 复审：覆盖维度）。镜像上游 web client `diff()` 的 `if defined && !force return`。
 */
internal fun SessionListState.applySessionDiffIfAbsent(
    sessionId: String,
    diffs: List<cn.vectory.ocdroid.data.model.FileDiff>
): Pair<SessionListState, List<SseSideEffect>> =
    (if (sessionDiffs[sessionId] != null) this
    else copy(sessionDiffs = sessionDiffs + (sessionId to diffs))) to emptyList()

/**
 * session lifecycle completion (root busy→idle) → mark [sessionId] unread IF
 * it is not the currently-open session. Also used as the REST reconnect
 * backstop. Caller passes [currentSessionId] so this stays pure (no slice
 * read). Pure.
 */
internal fun UnreadState.applyMarkSessionUnread(
    sessionId: String,
    currentSessionId: String?
): Pair<UnreadState, List<SseSideEffect>> =
    (if (sessionId == currentSessionId) this
    else copy(unreadSessions = unreadSessions + sessionId)) to emptyList()
