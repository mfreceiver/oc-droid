package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.SessionStatusLoadTrigger
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.isSseDown
import cn.vectory.ocdroid.ui.reportNonFatalIssue
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * §R-17 batch3d / god-file-decomposition P7: extracted orchestrator for the
 * status-load single-flight epoch + the slim/standard status fan-out.
 *
 * Owns the three status-loading free-functions moved from [SessionListActions]:
 * - [launchLoadSessionStatus] (entry, slim SWEEP short-circuit + standard legacy path)
 * - [launchLoadSessionStatusSlim] (slim COLD_START bulk path)
 * - [mergeStatusSnapshot] (pure §sse-rest-race merge)
 *
 * Single epoch owner (god-file-decomposition §5.3 single-owner rule): this
 * object is the ONE owner of [statusLoadEpoch]. No wrapper, no shadow copy.
 * Injected dependencies are passed per-call (no constructor state).
 *
 * 🔴 EPOCH-ORDER landmine is preserved verbatim inside [launchLoadSessionStatus]:
 * the slim SWEEP short-circuit MUST execute BEFORE
 * `statusLoadEpoch.incrementAndGet()` (see the in-method comment for the full rationale).
 */
internal object StatusPollOrchestrator {

    // §single-flight/epoch (groker🟡 v0.7.5): 并发触发(重连 + switchTo + loadSessions)时,
    // 每次发起递增 epoch; REST 返回时若 epoch 已被更新请求超越则丢弃本结果——避免后完成者
    // 把先完成者的 REST 写入误判为"SSE 在途变化"而保留(并发粘 busy 边角)。
    private val statusLoadEpoch = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 冻结方案 P0-1 (rev-1, transport-grounded): is the slim digest `status`
     * relay (the SSE-driven steady-state status source) currently EFFECTIVE —
     * i.e. may the slim foreground SWEEP be a zero-REST no-op because SSE is
     * delivering status?
     *
     * False (→ the sweep must fall through to the slim REST fan-out) when SSE
     * transport is NOT delivering frames. The PRIMARY signal is the
     * TRANSPORT-DELIVERY axis [SliceFlows.sseConnected] (mirror of
     * StoreState.isSseConnected): true iff the live
     * ServiceSseConnectionOwner collector has proven transport delivery with
     * at least one valid current-identity frame AND has not since torn down.
     * This axis goes false during the inter-retry gap and on every closing
     * path, even while [ConnectionPhase] may read `Reconnecting` /
     * `ReconnectingAttempt` — so the predicate MUST NOT infer transport health
     * from phase alone (that would short-circuit during a reconnect gap and
     * freeze status).
     *
     * The phase check is defensive: terminal phases (`SseDisabled` /
     * `Disconnected`) always have `isSseConnected == false` (their teardown
     * stamps it false), so they fall through to REST naturally; the explicit
     * phase guard documents intent and protects against a transient
     * `isSseConnected=true` read during teardown.
     *
     * Reads [SliceFlows.sseConnected] + [SliceFlows.connection] (synchronous
     * StateFlow `.value` off the aggregate), so this is safe to call on the
     * sweep entry path before any epoch bump.
     *
     * ## State-machine rules (transport-grounded, NOT phase-inferred):
     *
     * | Condition | Predicate | Behavior |
     * |-----------|-----------|----------|
     * | `isSseConnected == true` + any non-terminal phase | `true` | sweep no-op — digest relay is delivering |
     * | `isSseConnected == false` + `Connected` phase | `false` | REST fallback — transport down despite healthy phase |
     * | `isSseConnected == false` + `Reconnecting` | `false` | REST fallback — reconnect gap |
     * | `isSseConnected == false` + `ReconnectingAttempt` | `false` | REST fallback — reconnect gap |
     * | `isSseConnected == false` + `Connecting` | `false` | REST fallback — transport not yet up |
     * | `isSseConnected == false` + `SseDisabled` | `false` | REST fallback (terminal, debug toggle) |
     * | `isSseConnected == false` + `Disconnected` | `false` | REST fallback (terminal, retries exhausted) |
     *
     * The only legal short-circuit is when the transport itself reports
     * delivery (`isSseConnected == true`). Every other state — including
     * transient reconnect phases that the prior impl wrongly treated as
     * "SSE will deliver soon" — falls through to REST so status cannot freeze.
     */
    private fun sseDigestRelayEffective(slices: SliceFlows): Boolean {
        if (!slices.sseConnected) return false
        val phase = slices.connection.value.connectionPhase
        return phase !is ConnectionPhase.SseDisabled && !phase.isSseDown
    }


    internal fun launchLoadSessionStatus(
        scope: CoroutineScope,
        repository: OpenCodeRepository,
        slices: SliceFlows,
        trigger: SessionStatusLoadTrigger = SessionStatusLoadTrigger.SWEEP,
        onComplete: (Boolean) -> Unit = {},
    ) {
        // 🔴 T-R1 方案A EPOCH ORDER (critical landmine): the slim SWEEP short-circuit
        // MUST happen BEFORE statusLoadEpoch.incrementAndGet(). The 4s foreground
        // sweep (UnreadSoakController.ACTIVE_REFRESH_INTERVAL_MS →
        // requestStatusRefresh → ControllerEffect.LoadSessionStatusWithCompletion →
        // here with trigger=SWEEP) is a no-op for status REST in slim connected
        // mode. If this no-op bumped the epoch FIRST, every 4s sweep bump would
        // supersede an in-flight COLD_START bulk, and the cold-start's epoch guard
        // below (myEpoch != statusLoadEpoch.get()) would discard its result — the
        // cold-start snapshot would be silently dropped forever.
        //
        // onComplete(true) is synchronous-safe: the sweep callback only touches
        // UnreadSoakController's Main-thread fields (no suspension, no launch), and
        // the caller runs on appScope (Main.immediate) — no thread hop, so it
        // completes well within the 15s timeout job.
        //
        // 🔴 冻结方案 P0-1 (rev-1, transport-grounded): the no-op is correct
        // ONLY when SSE transport is actually delivering frames — the slim
        // digest `status` relay (SessionSyncCoordinator.handleSessionDigest →
        // applySessionStatus) is the steady-state status source, so a periodic
        // sweep would be pure waste. When the transport is NOT delivering there
        // is NO digest relay to keep status fresh, so the sweep MUST fall
        // through to the existing slim REST fan-out
        // (launchLoadSessionStatusSlim below). This mirrors the §sse-rest-
        // fallback design: REST is the fallback transport whenever SSE cannot
        // deliver.
        //
        // The predicate reads the TRANSPORT-DELIVERY axis
        // (SliceFlows.sseConnected → StoreState.isSseConnected): the live
        // ServiceSseConnectionOwner collector has proven transport delivery.
        // This axis goes false during the inter-retry gap and on every closing
        // path, even while phase may read Reconnecting/ReconnectingAttempt —
        // so the predicate MUST NOT infer transport health from phase alone
        // (that would short-circuit during a reconnect gap and freeze status).
        // Terminal phases (SseDisabled / Disconnected) always have
        // isSseConnected == false (their teardown stamps it false), so they
        // fall through to REST naturally; the explicit phase guard is
        // defensive. The epoch-order landmine is preserved verbatim: the no-op
        // still runs BEFORE the epoch bump on the SSE-healthy path.
        if (repository.usesSlimStatusFanOut &&
            trigger == SessionStatusLoadTrigger.SWEEP &&
            sseDigestRelayEffective(slices)
        ) {
            onComplete(true)
            return
        }
        val myEpoch = statusLoadEpoch.incrementAndGet()
        // §R11: capture completenessEpoch BEFORE the network suspend so a
        // mid-flight session-list change (which bumps completenessEpoch)
        // invalidates the result — prevents a stale REST poll from applying
        // status to a changed session list.
        val completenessEpochAtStart = slices.sessionList.value.completenessEpoch
        val hostAtRequestStart = slices.host.value.currentHostProfileId
        // T-R1 (slimapi R1) 方案A: in slim mode the paths that reach here are
        // COLD_START (app/session/host-connect init) AND, per P0-1 above, a SWEEP
        // that fell through because SSE is effectively OFF (SseDisabled /
        // Disconnected — no digest relay to keep status fresh). Both route
        // through the slim bulk fetch (one call per workdir) and MUST NOT hit the
        // legacy /session/status + /api/session/active endpoints. Steady-state
        // status (SSE-healthy) arrives via the slim digest `status` relay
        // (SessionSyncCoordinator.handleSessionDigest → applySessionStatus).
        // Legacy transport behavior remains byte-for-byte unchanged.
        if (repository.usesSlimStatusFanOut) {
            launchLoadSessionStatusSlim(scope, repository, slices, myEpoch, hostAtRequestStart, completenessEpochAtStart, onComplete)
            return
        }
        scope.launch {
            var completionCalled = false
            fun complete(success: Boolean) {
                if (!completionCalled) {
                    completionCalled = true
                    onComplete(success)
                }
            }
            try {
                // §sse-rest-race: REST 发起前快照本地 status, onSuccess 时识别"REST 在途期间
                // 被 SSE 更新过的 session"——旧 REST 快照不得覆盖较新的 SSE 值。
                // §P0-A: this localBefore is carried INTO the authority reducer's
                // ApplySnapshot (pure in-flight protection); the reducer no longer
                // re-reads sessionStatuses to merge — it uses op.localBefore.
                val localBefore = slices.sessionList.value.sessionStatuses
                // §P0-A: requestStartMs captured at the caller (carried into the op
                // as the entries' updatedAtMs + coverage.lastSuccessTimeMs —
                // the reducer itself reads NO clock, staying pure).
                val requestStartMs = System.currentTimeMillis()
                // §verbose-diag-flood: capture the current-session id + its prior
                // status BEFORE the mutate so the post-mutate verbose log can do a
                // single scoped + deduped comparison (current-session only + actual
                // transition only). Reading these outside the mutate lambda avoids
                // double-logging on StateFlow CAS retries (the lambda can run more
                // than once).
                val diagCurrentSid = slices.chat.value.currentSessionId
                val diagPriorStatus = diagCurrentSid?.let { slices.sessionList.value.sessionStatuses[it] }
                // §toctou-identity: capture identityEpoch BEFORE the network
                // suspend so a mid-flight identity switch invalidates the response.
                val identityEpochAtStart = slices.store.stateFlow.value.identityEpoch
                val statusResult = repository.getSessionStatus()
                val activeResult = repository.getActiveSessionIds()
                val statuses = statusResult.getOrNull()
                var applied = false
                slices.store.mutateState { snapshot ->
                    // StateFlow.update may retry this transform after a CAS
                    // collision. Report the result of the final attempt only.
                    applied = false
                    val sl = snapshot.sessionList
                    // The status epoch and host identity jointly fence both REST
                    // responses. A host switch explicitly clears activeSessionIds;
                    // an old-host response must never repopulate that snapshot.
                    if (myEpoch != statusLoadEpoch.get() ||
                        snapshot.host.currentHostProfileId != hostAtRequestStart ||
                        // §R11: session list changed mid-flight — discard to
                        // prevent stale REST status from overwriting new sessions.
                        snapshot.sessionList.completenessEpoch != completenessEpochAtStart
                    ) {
                        DebugLog.d("Sync", "launchLoadSessionStatus: stale epoch/host/completeness, discarding snapshot")
                        return@mutateState snapshot
                    }
                    val authoritative = allSessionsById(
                        sl.sessions,
                        sl.directorySessions,
                        sl.childSessions,
                    )
                    val authoritativeIds = authoritative.keys
                    // §P0-A: status now flows through the PURE authority reducer.
                    // reduceAuthority is pure → safe + idempotent inside this CAS
                    // retry lambda. It updates authority + the sessionStatuses
                    // projection in the SAME copy; activeSessionIds (NOT an
                    // authority concern) is overlaid below.
                    val withStatus = if (statuses != null) {
                        val op = cn.vectory.ocdroid.ui.buildAuthorityApplySnapshot(
                            snapshot = statuses,
                            authoritativeSessions = authoritative,
                            authoritativeNodeIds = authoritativeIds,
                            coveredWorkdirs = authoritative.values.asSequence()
                                .map { it.directory }.filter { it.isNotBlank() }.toSet(),
                            partialFailureWorkdirs = emptySet(),
                            unmappedActiveIds = emptySet(),
                            lastSuccessTimeMs = requestStartMs,
                            scopeKey = slices.store.authorityScope(),
                            requestToken = cn.vectory.ocdroid.data.state.RequestToken(
                                hostProfileId = hostAtRequestStart,
                                requestStartMs = requestStartMs,
                                identityEpoch = identityEpochAtStart,
                            ),
                            localBefore = localBefore,
                        )
                        cn.vectory.ocdroid.ui.reduceAuthority(snapshot, op)
                    } else {
                        snapshot
                    }
                    // Fail-closed: a failed active fetch retains the previous
                    // snapshot. Both branches intersect the current tree so a
                    // deleted/archived session cannot remain active forever.
                    val nextActiveIds = activeResult.getOrNull()
                        ?.intersect(authoritativeIds)
                        ?: sl.activeSessionIds.intersect(authoritativeIds)
                    applied = true
                    withStatus.copy(
                        sessionList = withStatus.sessionList.copy(activeSessionIds = nextActiveIds),
                    )
                }
                // §streaming-state-sync-diag (runtime-gated, scoped+dedup):
                // attribute the optimistic-busy overwrite to the poller (vs SSE /
                // digest / optimistic-onSuccess). Scope to the current (open)
                // session AND log only on actual transition — the prior code
                // logged EVERY status entry for EVERY session on EVERY poll cycle
                // (idle→idle dominated the flood at ~500/sec). At most ONE line
                // per poll cycle now, and only when the current session's status
                // actually transitioned.
                if (applied && cn.vectory.ocdroid.util.DebugLog.verboseDiagEnabled && diagCurrentSid != null) {
                    val diagNewStatus = slices.sessionList.value.sessionStatuses[diagCurrentSid]
                    if (diagNewStatus != diagPriorStatus) {
                        cn.vectory.ocdroid.util.DebugLog.d(
                            "StatusDiag",
                            "poller status write sid=$diagCurrentSid oldType=${diagPriorStatus?.type} newType=${diagNewStatus?.type}",
                        )
                    }
                }
                statusResult
                    .onSuccess {
                    // §unread-soak: the REST status snapshot NO LONGER marks unread
                    // on the "busy→absent" edge. The [UnreadSoakController] sweep +
                    // pure [evaluateUnread] evaluator own the marking now — they
                    // consume the freshly-merged sessionStatuses (below) on the
                    // next foreground tick. The epoch-guarded merge still runs so
                    // the evaluator sees authoritative idle/busy state.
                    complete(applied)
                }
                    .onFailure { error ->
                    reportNonFatalIssue("MainViewModel", "Failed to load session status", error)
                    complete(false)
                }
                activeResult.onFailure { error ->
                    DebugLog.w("Sync", "Failed to load active sessions; retaining prior snapshot: ${error.message}")
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                complete(false)
                throw cancellation
            } catch (_: Throwable) {
                complete(false)
            }
        }
    }

    /**
     * T-R1 (slimapi R1) — slim-mode foreground status cold-start. Replaces the
     * legacy `/session/status` + `/api/session/active` fan-out that
     * [launchLoadSessionStatus] performs in legacy mode.
     *
     * In slim mode the steady-state status source is the slim digest `status`
     * relay ([SessionSyncCoordinator.handleSessionDigest] → [applySessionStatus]);
     * this helper provides the COLD-START snapshot (and a periodic correction on
     * each foreground sweep) by issuing one bulk
     * `GET /slimapi/sessions/status?directory=X` per known workdir (the sidecar
     * requires a single directory per call — see [OpenCodeApi.getSlimapiSessionsStatus])
     * and folding the merged map into [SessionListState.sessionStatuses] via the
     * same [normalizeAuthoritativeStatusSnapshot] + [mergeStatusSnapshot] discipline
     * the legacy path uses.
     *
     * Active-session ids are NOT polled here — slim activity is owned by the
     * digest relay + slim reconcile. The prior [SessionListState.activeSessionIds]
     * snapshot is preserved (intersected against the authoritative tree so a
     * deleted/archived session cannot remain active forever, matching the legacy
     * fail-closed semantics). The legacy `/api/session/active` endpoint is never
     * hit (T-R1 contract).
     *
     * No known directories yet (before the session list loads) → no-op success
     * (the digest relay + later sweeps cover status once sessions arrive).
     */
    private fun launchLoadSessionStatusSlim(
        scope: CoroutineScope,
        repository: OpenCodeRepository,
        slices: SliceFlows,
        myEpoch: Long,
        hostAtRequestStart: String?,
        completenessEpochAtStart: Long,
        onComplete: (Boolean) -> Unit,
    ) {
        scope.launch {
            var completionCalled = false
            fun complete(success: Boolean) {
                if (!completionCalled) {
                    completionCalled = true
                    onComplete(success)
                }
            }
            try {
                // §sse-rest-race: REST 发起前快照本地 status (mirrors the legacy path).
                // §P0-A: carried into the authority ApplySnapshot (pure in-flight merge).
                val localBefore = slices.sessionList.value.sessionStatuses
                val requestStartMs = System.currentTimeMillis()
                val sl = slices.sessionList.value
                val authoritative = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
                // Derive the distinct workdirs to query (sidecar requires one
                // directory per call). Empty before the session list loads.
                val directories = authoritative.values
                    .mapNotNull { it.directory.takeIf { d -> d.isNotBlank() } }
                    .toSet()
                // P0-D Unknown semantics: when no directories are known yet,
                // complete(true) fires with empty result → tree sessions get NO
                // sessionStatuses entry. Downstream readers project
                // sessionStatuses[sid] == null as "Unknown" (conservative —
                // NOT idle). The slim digest `status` relay + later foreground
                // sweeps fill entries once sessions/directories arrive.
                // Verified: UnreadSoak's subtree.all { sessionStatuses[it]?.isIdle == true }
                // is false for absent entries; busy/retry checks are inert for absent.
                if (directories.isEmpty()) {
                    complete(true)
                    return@launch
                }
                // Concurrent per-directory bulk fetch (bounded by the directory
                // count, which is small — typically 1–3). Each failure is tolerated
                // (the digest relay is the steady-state source); we fold whatever
                // succeeded into one merged map.
                //
                // §11.1 fix-10 P0-2: track anySuccess — if ALL directory
                // requests failed, we MUST NOT mutate the session statuses
                // (an empty merged map would be treated as "all idle",
                // masking the transport failure). Instead, preserve the
                // prior snapshot and complete(false).
                //
                // §11.1 fix-11a P0-1 (rev-ogpt): track SUCCESS / FAILURE per
                // directory — "partial failure" (some dirs succeeded, some
                // failed) MUST NOT let the failed-directory sessions be
                // normalized to idle. Only sessions whose directory fetch
                // SUCCEEDED are authoritative-normalized downstream;
                // failed-directory sessions preserve their prior
                // sessionStatuses (matches OpenCodeRepository
                // .getSlimapiSessionsStatus failure semantics: "keep prior
                // snapshot"). fix-10 closed the all-failed gap; this closes
                // the partial-failure gap.
                val successfulDirs = mutableSetOf<String>()
                // §toctou-identity: capture identityEpoch BEFORE the fan-out
                // suspend so a mid-flight identity switch invalidates the response.
                val identityEpochAtStart = slices.store.stateFlow.value.identityEpoch
                val merged: Map<String, SessionStatus> = coroutineScope {
                    val dirList = directories.toList()
                    val results = dirList.map { dir ->
                        async { repository.getSlimapiSessionsStatus(dir) }
                    }.awaitAll()
                    val acc = mutableMapOf<String, SessionStatus>()
                    dirList.zip(results).forEach { (dir, result) ->
                        result.getOrNull()?.let {
                            acc.putAll(it)
                            successfulDirs += dir
                        }
                    }
                    acc
                }
                // §11.1 fix-10 P0-2: if every directory request failed,
                // do NOT apply the empty merged as authoritative —
                // preserve the prior snapshot and signal failure so the
                // caller can retry / fall back.
                if (successfulDirs.isEmpty()) {
                    DebugLog.w("Sync", "launchLoadSessionStatusSlim: all directory requests failed — preserving prior snapshot")
                    complete(false)
                    return@launch
                }
                var applied = false
                slices.store.mutateState { snapshot ->
                    applied = false
                    val current = snapshot.sessionList
                    if (myEpoch != statusLoadEpoch.get() ||
                        snapshot.host.currentHostProfileId != hostAtRequestStart ||
                        // §R11: session list changed mid-flight — discard to
                        // prevent stale REST status from overwriting new sessions.
                        snapshot.sessionList.completenessEpoch != completenessEpochAtStart
                    ) {
                        DebugLog.d("Sync", "launchLoadSessionStatusSlim: stale epoch/host/completeness, discarding snapshot")
                        return@mutateState snapshot
                    }
                    val authoritative = allSessionsById(
                        current.sessions,
                        current.directorySessions,
                        current.childSessions,
                    )
                    val authoritativeIds = authoritative.keys
                    // §11.1 fix-11a P0-1 (rev-ogpt): sessions whose directory
                    // fetch FAILED must NOT be authoritative-normalized to
                    // idle. The authority reducer reproduces this exactly via
                    //   authoritativeNodeIds = coveredAuthoritativeIds (failed-dir
                    //   sessions excluded from idle-normalize) +
                    //   partialFailureWorkdirs = failed dirs (their entries keep
                    //   the prior bySid entry, status updated to the merged value).
                    // Sessions with a blank directory were never queried and retain
                    // their original normalize-to-idle behavior (they are in
                    // coveredAuthoritativeIds, unchanged).
                    val failedDirs = directories - successfulDirs
                    val failedDirSessionIds = authoritative.values
                        .asSequence()
                        .filter { s -> s.directory.isNotBlank() && s.directory !in successfulDirs }
                        .map { it.id }
                        .toSet()
                    val coveredAuthoritativeIds = authoritativeIds - failedDirSessionIds
                    val op = cn.vectory.ocdroid.ui.buildAuthorityApplySnapshot(
                        snapshot = merged,
                        authoritativeSessions = authoritative,
                        authoritativeNodeIds = coveredAuthoritativeIds,
                        coveredWorkdirs = successfulDirs,
                        partialFailureWorkdirs = failedDirs,
                        unmappedActiveIds = emptySet(),
                        lastSuccessTimeMs = requestStartMs,
                        scopeKey = slices.store.authorityScope(),
                        requestToken = cn.vectory.ocdroid.data.state.RequestToken(
                            hostProfileId = hostAtRequestStart,
                            requestStartMs = requestStartMs,
                            identityEpoch = identityEpochAtStart,
                        ),
                        localBefore = localBefore,
                    )
                    val withStatus = cn.vectory.ocdroid.ui.reduceAuthority(snapshot, op)
                    applied = true
                    // Slim activity is digest-relay-owned: preserve the prior
                    // activeSessionIds snapshot, fail-closed-intersected against
                    // the authoritative tree (a deleted/archived session cannot
                    // remain active forever — mirrors the legacy semantics).
                    withStatus.copy(
                        sessionList = withStatus.sessionList.copy(
                            activeSessionIds = current.activeSessionIds.intersect(authoritativeIds),
                        ),
                    )
                }
                complete(applied)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                complete(false)
                throw cancellation
            } catch (_: Throwable) {
                complete(false)
            }
        }
    }

    /**
     * §sse-rest-race 纯函数 (groker🟡 v0.7.5): 合并 REST 权威快照与本地状态。
     * - REST 快照整体替换: 清除 server 已 idle(快照缺失)的 stale busy (opencode status.ts:
     *   idle 时 data.delete, /session/status 只含 active)。
     * - 保护 REST 在途期间被 SSE 更新的 session: localAfter[id] != localBefore[id] → 保留
     *   SSE 新值, 避免慢 REST 旧快照覆盖较新 idle/busy。
     * 抽为纯函数便于表驱动矩阵单测。
     */
    internal fun mergeStatusSnapshot(
        localBefore: Map<String, SessionStatus>,
        localAfter: Map<String, SessionStatus>,
        restSnapshot: Map<String, SessionStatus>
    ): Map<String, SessionStatus> {
        val result = restSnapshot.toMutableMap()
        for ((id, after) in localAfter) {
            if (localBefore[id] != after) result[id] = after
        }
        return result
    }
}
