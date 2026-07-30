package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.controller.applyAggregationOutcome
import cn.vectory.ocdroid.ui.controller.aggregationSignal
import cn.vectory.ocdroid.ui.controller.applySessionDiffIfAbsent
import cn.vectory.ocdroid.ui.controller.allSessionsById
import cn.vectory.ocdroid.ui.controller.StatusPollOrchestrator
import cn.vectory.ocdroid.ui.controller.loadCompleteSessionTrees
import cn.vectory.ocdroid.ui.controller.rootIdOf
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.SlimapiPermissionEntry
import cn.vectory.ocdroid.data.repository.SlimAggregationOutcome
import cn.vectory.ocdroid.data.repository.toPermissionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.RevertCutoff
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.data.model.toCacheEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.WorkdirPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import cn.vectory.ocdroid.ui.controller.SessionListRefreshOrchestrator
import cn.vectory.ocdroid.ui.controller.SessionMetadataCacheWriter

private val sessionMetadataCacheWriter = SessionMetadataCacheWriter()
private val sessionListRefreshOrchestrator = SessionListRefreshOrchestrator(
    cacheWriter = sessionMetadataCacheWriter,
    clockMs = System::currentTimeMillis,
)

/**
 * Persist a bounded session-metadata cache to [SettingsManager.sessionCache]
 * so the next cold start can reseed the session-list slice instantly (tabs,
 * title, workdir groups).
 *
 * §Q4-strict-sync: the filter is now ALL non-archived root sessions
 * (`parentId == null && !isArchived`), replacing the legacy
 * `open-ids / current-id / current-workdir-roots` tri-filter. The open/current
 * sessions naturally satisfy `parentId == null` (they are roots the user
 * navigated to), so they are still cached; but so are ALL other root sessions
 * the server returned, giving a fuller cold-start reseed. A cap
 * ([MainViewModelTimings.sessionCacheCap]) prevents ESP bloat for users with
 * many sessions — when exceeded, entries are trimmed by time.updated desc
 * (keep the most recently active).
 *
 * Fix #5: previously written only inside [launchLoadSessions].onSuccess,
 * which never re-runs on a plain `selectSession` (no message sent). After
 * opening an existing conversation and restarting, the tab vanished because
 * its Session metadata was missing from the cache. This helper is now also
 * called from [MainViewModel.selectSession] and [MainViewModel.sendMessage]
 * so opening/creating a conversation persists its metadata immediately.
 */

internal fun persistSessionCache(
    settingsManager: SettingsManager,
    sessions: List<Session>,
    currentId: String? = null,
    currentWorkdir: String? = null,
    revertCutoffs: Map<String, RevertCutoff>,
) = sessionMetadataCacheWriter.persistSessionCache(
    settingsManager = settingsManager,
    sessions = sessions,
    currentId = currentId,
    currentWorkdir = currentWorkdir,
    revertCutoffs = revertCutoffs,
)

internal fun launchLoadSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    settingsManager: SettingsManager,
    onSelectSession: (String) -> Unit,
    onLoadSessionStatus: () -> Unit,
    onLoadMessages: (String) -> Unit,
    emit: EventEmitter = EventEmitter { },
    expectedServerGroupFp: String? = null,
    currentServerGroupFp: (() -> String)? = null,
    onArchivedSessionsDetected: ((mergedSessions: List<Session>, hasMoreSessions: Boolean, confirmedServerIds: Set<String>, sweepNow: Long) -> Unit)? = null,
) = sessionListRefreshOrchestrator.launchLoadSessions(
    scope = scope,
    repository = repository,
    slices = slices,
    settingsManager = settingsManager,
    onSelectSession = onSelectSession,
    onLoadSessionStatus = onLoadSessionStatus,
    onLoadMessages = onLoadMessages,
    emit = emit,
    expectedServerGroupFp = expectedServerGroupFp,
    currentServerGroupFp = currentServerGroupFp,
    onArchivedSessionsDetected = onArchivedSessionsDetected,
)

internal fun launchLoadMoreSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    onSelectSession: (String) -> Unit,
    emit: EventEmitter = EventEmitter { },
) = sessionListRefreshOrchestrator.launchLoadMoreSessions(
    scope = scope,
    repository = repository,
    slices = slices,
    onSelectSession = onSelectSession,
    emit = emit,
)

// §single-flight/epoch (groker🟡 v0.7.5): statusLoadEpoch moved to
// [StatusPollOrchestrator] (god-file-decomposition P7, single-owner rule). The
// slim SWEEP short-circuit + incrementAndGet ordering landmine is preserved
// verbatim there. This file retains only the thin free-function delegates.
// §single-flight/epoch (groker🟡 v0.7.5) — legacy comment preserved for context:
// 并发触发(重连 + switchTo + loadSessions)时, 每次发起递增 epoch; REST 返回时若 epoch 已被
// 更新请求超越则丢弃本结果——避免后完成者把先完成者的 REST 写入误判为"SSE 在途变化"
// 而保留(并发粘 busy 边角)。

// FIX-D (gpter #2, review-blocker): single-flight epoch for the session-LIST
// load (launchLoadSessions). Mirrors [statusLoadEpoch]'s pattern: concurrent
// calls (reconnect + foreground catch-up + manual refresh) each increment at
// launch; a stale response whose epoch has been superseded is dropped BEFORE
// any write/persist/archive-callback — critical now that the archive callback
// (FIX-A/C) is destructive (prunes openIds + clears chat). Without this, a
// slow stale response could trigger the destructive eviction AFTER a newer
// response already updated the state.
//
// Threading: all launchLoadSessions calls run on the same scope (Main.immediate,
// serial). The epoch check at the top of onSuccess is sufficient — the writes
// (mutateSessionList / persist / callback) happen synchronously after that
// check with no suspension in between (remove-message-persistence Task 6
// removed the prior post-verifyFingerprint re-check), so no TOCTOU is
// possible on this dispatcher.

/**
 * T-R1 (slimapi R1) 方案A: distinguishes the foreground 4s SWEEP (a no-op for
 * status REST in slim connected mode — status arrives via the digest relay +
 * the one-time cold-start bulk) from a COLD_START (app/session/host-connect
 * init → one bulk `GET /slimapi/sessions/status` per workdir). Legacy mode
 * ignores the distinction (no digest relay → always polls).
 */
internal enum class SessionStatusLoadTrigger { SWEEP, COLD_START }

internal fun launchLoadSessionStatus(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    trigger: SessionStatusLoadTrigger = SessionStatusLoadTrigger.SWEEP,
    onComplete: (Boolean) -> Unit = {},
) = StatusPollOrchestrator.launchLoadSessionStatus(
    scope = scope,
    repository = repository,
    slices = slices,
    trigger = trigger,
    onComplete = onComplete,
)

/**
 * §sse-rest-race 纯函数 (groker🟡 v0.7.5): 合并 REST 权威快照与本地状态。
 * - REST 快照整体替换: 清除 server 已 idle(快照缺失)的 stale busy (opencode status.ts:
 *   idle 时 data.delete, /session/status 只含 active)。
 * - 保护 REST 在途期间被 SSE 更新的 session: localAfter[id] != localBefore[id] → 保留
 *   SSE 新值, 避免慢 REST 旧快照覆盖较新 idle/busy。
 * 抽为纯函数便于表驱动矩阵单测。
 *
 * god-file-decomposition P7: body moved to [StatusPollOrchestrator.mergeStatusSnapshot];
 * this top-level free function is a thin delegate (F8: signature unchanged) so
 * [SessionTreeHydrator] + the internal [launchLoadChildSessions] caller keep
 * working unchanged.
 */
internal fun mergeStatusSnapshot(
    localBefore: Map<String, SessionStatus>,
    localAfter: Map<String, SessionStatus>,
    restSnapshot: Map<String, SessionStatus>
): Map<String, SessionStatus> = StatusPollOrchestrator.mergeStatusSnapshot(
    localBefore = localBefore,
    localAfter = localAfter,
    restSnapshot = restSnapshot,
)

/**
 * §issue-1(1): 打开会话时拉取该会话的文件变更快照（GET /session/{id}/diff，
 * 已带 X-Opencode-Skip-Dir，无需 directory）。结果写入 SessionListState.sessionDiffs，
 * 驱动聊天内 SessionDiffCard。SSE session.diff 会随后增量覆盖，故失败仅记录不报错。
 * 与 [launchLoadSessionStatus] 同构（per-call scope + slice mutation）。 */
internal fun launchLoadSessionDiff(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
) = sessionListRefreshOrchestrator.launchLoadSessionDiff(
    scope = scope,
    repository = repository,
    slices = slices,
    sessionId = sessionId,
)

/**
 * §R-17 batch3d: free-function extraction of the former AppCore.loadChildSessions
 * body. Both [SessionViewModel.loadChildSessions] and AppCore's effect-dispatch
 * handler call this so the body lives once (the VM is not a delegate shell —
 * it calls this domain helper directly, never `core.<method>()`).
 */
internal fun launchLoadChildSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    sessionId: String,
    tag: String,
) {
    scope.launch {
        try {
            val before = slices.sessionList.value
            val byId = allSessionsById(before.sessions, before.directorySessions, before.childSessions)
            val rootId = rootIdOf(sessionId, byId) ?: sessionId
            // The effect can race the root list load. The children endpoint is
            // still addressable by id, so hydrate from a minimal placeholder;
            // metadata will be supplied by the normal session-list refresh.
            val root = byId[rootId] ?: Session(id = rootId, directory = "")
            // §gpter-blocker: capture the completeness epoch BEFORE hydration
            // starts. If an invalidation (SSE session.created/updated or a REST
            // structural replace) bumps the epoch mid-flight, the commit below
            // drops the result (fail-closed) so a stale snapshot can never
            // re-certify a root whose tree was invalidated.
            val epochAtStart = before.completenessEpoch
            // §toctou-identity: capture the current host identity BEFORE any
            // suspend so the post-fetch guard can detect a host switch mid-flight.
            val hostProfileIdAtStart = slices.host.value.currentHostProfileId
            // §toctou-identity: capture identityEpoch BEFORE any suspend so a
            // mid-flight identity switch invalidates the stale response.
            val identityEpochAtStart = slices.store.stateFlow.value.identityEpoch
            val hydration = loadCompleteSessionTrees(repository, listOf(root))
            if (rootId in hydration.completeRootIds) {
                val statusBefore = slices.sessionList.value.sessionStatuses
                // §P0-A: requestStartMs captured at the caller (carried into the
                // authority op — the reducer stays pure).
                val requestStartMs = System.currentTimeMillis()
                val statusSnapshot = repository.getSessionStatus().getOrNull()
                slices.store.mutateState { snapshot ->
                    // §gpter-blocker: the tree was invalidated mid-flight —
                    // drop the stale result. The root stays incomplete so the
                    // next tick re-hydrates against the fresh tree.
                    val it = snapshot.sessionList
                    if (it.completenessEpoch != epochAtStart ||
                        slices.host.value.currentHostProfileId != hostProfileIdAtStart
                    ) return@mutateState snapshot
                    val nextChildren = it.childSessions + hydration.childrenByParent
                    val authoritative = allSessionsById(it.sessions, it.directorySessions, nextChildren)
                    // §P0-A: status via PURE reduceAuthority (authority + projection
                    // in this same CAS). reduceAuthority owns normalize + the REST
                    // in-flight merge (op.localBefore=statusBefore).
                    val withStatus = if (statusSnapshot != null) {
                        val op = buildAuthorityApplySnapshot(
                            snapshot = statusSnapshot,
                            authoritativeSessions = authoritative,
                            authoritativeNodeIds = authoritative.keys,
                            coveredWorkdirs = authoritative.values.asSequence()
                                .map { dir -> dir.directory }.filter { dir -> dir.isNotBlank() }.toSet(),
                            partialFailureWorkdirs = emptySet(),
                            unmappedActiveIds = emptySet(),
                            lastSuccessTimeMs = requestStartMs,
                            scopeKey = slices.store.authorityScope(),
                            requestToken = cn.vectory.ocdroid.data.state.RequestToken(
                                hostProfileId = hostProfileIdAtStart,
                                requestStartMs = requestStartMs,
                                identityEpoch = identityEpochAtStart,
                            ),
                            localBefore = statusBefore,
                        )
                        reduceAuthority(snapshot, op)
                    } else {
                        snapshot
                    }
                    withStatus.copy(
                        sessionList = withStatus.sessionList.copy(
                            childSessions = nextChildren,
                            completeRootIds = it.completeRootIds + rootId,
                        ),
                    )
                }
            } else {
                reportNonFatalIssue(tag, "Failed to load complete child tree for $rootId")
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            cn.vectory.ocdroid.util.DebugLog.w(tag, "loadChildSessions suppressed: ${e.message}")
        }
    }
}

/**
 * §R-17 batch3d: free-function extraction of the former AppCore.loadPendingQuestions
 * body. Merges the freshly-fetched [questions] with any locally-held ones the
 * server didn't return (matches the original semantics: byGet wins, existing
 * fills gaps). Called by [SessionViewModel.loadPendingQuestions] and by
 * AppCore's effect-dispatch handler.
 */
internal fun launchLoadPendingQuestions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    directory: String?,
    tag: String,
) {
    scope.launch {
        repository.getPendingQuestions(directory)
            .onSuccess { questions ->
                slices.mutateSessionList { currentState ->
                    val byGet = questions.associateBy { it.id }
                    val existing = currentState.pendingQuestions.associateBy { it.id }
                    val merged = (byGet + existing.filterKeys { it !in byGet }).values.toList()
                    currentState.copy(pendingQuestions = merged)
                }
            }
            .onFailure { error -> android.util.Log.w(tag, "Failed to load questions: ${error.message}") }
    }
}

/**
 * §R-17 batch3d: free-function extraction of the former AppCore.loadPendingPermissions
 * body. Called by [SessionViewModel.loadPendingPermissions] and by AppCore's
 * effect-dispatch handler.
 *
 * §rev-grok fix1 (permissions routeToken): the previous full-replace
 * (`it.copy(pendingPermissions = permissions)`) silently wiped the
 * `routeToken` that slim SSE `permission.asked` had folded into the
 * existing entry — because [SlimapiPermissionEntry.toPermissionRequest]
 * historically dropped it. The slim respond path then saw `routeToken=null`
 * and fell back to the legacy endpoint (contract §2/B2 violation). The
 * merge now preserves a known-good routeToken across a null-token REST
 * refresh (intersection only — see rule 3 below).
 *
 * §rev-grok 9.5 🟠1 (ghost cleanup): the Fix1 union-with-existing rule
 * ("existing fills gaps") created ghost permissions — a permission the
 * server resolved / expired without the client receiving the resolve
 * event stayed in pendingPermissions forever. This function now mirrors
 * the AUTHORITATIVE reconcile pattern of
 * [SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs] (the full-sweep
 * analog, NOT the single-workdir-optimistic `launchLoadPendingQuestions`
 * which intentionally keeps existing to avoid flicker on a narrow scope).
 *
 * Three merge rules:
 *
 *  1. **Membership is REST-authoritative** (de-ghost): every fetched entry
 *     is added (dedup by id); an id present at poll-start that REST did
 *     NOT return is dropped (server no longer lists it → resolved/expired).
 *  2. **Race-window preservation**: a `permission.asked` that lands DURING
 *     the poll — i.e. present in the post-fetch slice but NOT in [startIds]
 *     (the pre-poll snapshot) and NOT in the REST response — is preserved.
 *     Without this, a fresh arrival the REST sweep hadn't yet observed
 *     would be silently dropped (race loss).
 *  3. **Token never downgrades** (Fix1 protection): on the intersection
 *     (REST entry AND existing entry), take REST as the baseline, BUT if
 *     REST's routeToken is null/blank and the existing entry has a
 *     non-null token, preserve the existing token (slim SSE → REST race).
 *
 * Defensive: in slim mode, a final entry with a null routeToken logs WARN
 * (the slim respond path cannot route it correctly — surfaces the issue
 * instead of silently degrading to the legacy endpoint).
 *
 * C-D3 v2 §2.3: in slim mode, delegates to [launchLoadPendingPermissionsSlim]
 * which captures ONE token before the network suspend + guards the slice /
 * signal / UiEvent commits inside a single `commitIfSlimTokenCurrent` block.
 * The legacy non-slim path is unchanged.
 */
internal fun launchLoadPendingPermissions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    effects: SharedEffectBus,
    tag: String,
) = PermissionRefreshOrchestrator.launchLoadPendingPermissions(
    scope = scope,
    repository = repository,
    slices = slices,
    effects = effects,
    tag = tag,
)

/**
 * §R-17 batch3d: free-function extraction of the former AppCore.loadAgents body.
 *
 * §chat-ux-batch T8 (B3): the legacy selectedAgentName reconciliation (drop
 * the global pick if the agent is no longer offered by the server, else
 * keep + persist) was deleted here. T7 rewired agent selection to the
 * TRANSIENT pendingAgent chat-slice field, so the global pick no longer
 * exists; loadAgents now just writes the freshly-fetched list to the
 * settings slice. Called by AppCore's effect-dispatch handler.
 */
internal fun launchLoadAgents(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    tag: String,
) {
    scope.launch {
        repository.getAgents()
            .onSuccess { agents ->
                slices.mutateSettings { it.copy(agents = agents) }
            }
            .onFailure { error -> reportNonFatalIssue(tag, "Failed to load agents", error) }
    }
}

// §grouping-rewrite Round-2 #5: `directoriesMatchOrIntersect(...)` was here
// (Phase 5 G1 condition b helper) — sole caller was `attemptCrossGroupMerge`,
// which item 1 of this rewrite deleted. Removed as dead code.
