package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.LastErrorField
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionDigest
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ProbeResult
import cn.vectory.ocdroid.data.repository.SlimAuthoritativeCandidate
import cn.vectory.ocdroid.data.repository.SlimAuthoritativeCommitResult
import cn.vectory.ocdroid.data.repository.SlimAuthoritativeCommitter
import cn.vectory.ocdroid.data.repository.SlimDrainOutcome
import cn.vectory.ocdroid.data.repository.SlimSessionState
import cn.vectory.ocdroid.data.repository.SlimSinceStageAOutcome
import cn.vectory.ocdroid.data.repository.maxMessageTuple
import cn.vectory.ocdroid.data.repository.mergeSlimMessageSetWithConflict
import cn.vectory.ocdroid.data.repository.needsCatchUp
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.NavState
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.lenientJson
import cn.vectory.ocdroid.ui.reportNonFatalIssue
import cn.vectory.ocdroid.ui.routeChatSessionId
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

// ==========================================================================
// P4-A scaffold (§3 internal protocol + §4 ports/adapters + §4.3 class
// skeleton). Defined here so the port wiring compiles; NO production
// behavior moved yet (P4-B/P4-C). The SSC constructs `slimSessionReconciler`
// but does NOT call any of its methods from a production path — the frozen
// `reconcileSession` / `handleSessionDigest` / `reconcileDigest` /
// `performResyncCatchUp` stay byte-identical until P4-B/P4-C.
//
// See docs/ocmar/plans/2026-07-24-p4-slim-session-reconciler-design.md.
// ==========================================================================

// ── §3.1 Internal mode ───────────────────────────────────────────────────

/**
 * P4 §3.1: the reconciler-internal mode enum. SSC's nested [SessionSyncCoordinator.ReconcileMode]
 * maps to this at the façade (§3.1 `ReconcileMode.toSlimMode()` on SSC). Kept
 * distinct from SSC's enum so the reconciler has no compile-time dependency
 * on its host's nested types.
 */
internal enum class SlimReconcileMode { DIGEST_FOCUS, DIGEST_BACKGROUND, RESYNC }

/**
 * P4 §3.1: DIGEST_FOCUS + RESYNC may drive a REST fetch (slimapi /since or
 * cursor drain). DIGEST_BACKGROUND never fetches — it only refreshes the
 * row and returns a [SlimReconcileCommand.LaunchSlimResync] for SSC to run
 * the catch-up worker out-of-band.
 */
private fun SlimReconcileMode.mayFetch(): Boolean =
    this == SlimReconcileMode.DIGEST_FOCUS || this == SlimReconcileMode.RESYNC

/**
 * P4 §3.1: DIGEST_FOCUS + RESYNC may clear `dirty` on aligned/empty probe
 * outcomes. DIGEST_BACKGROUND NEVER clears dirty (the session isn't being
 * viewed; the catch-up worker owns the dirty clear when it actually fetches).
 */
private fun SlimReconcileMode.mayClearDirty(): Boolean =
    this == SlimReconcileMode.DIGEST_FOCUS || this == SlimReconcileMode.RESYNC

// ── §3.2 Internal result (9 variants, 1:1 with ReconcileResult) ──────────

/**
 * P4 §3.2: the reconciler-internal result hierarchy. Each variant carries
 * its `sid` so the dispatcher (SSC) can route side-effects without a
 * separate `resultSid` helper. SSC's façade maps this 1:1 to its frozen
 * [SessionSyncCoordinator.ReconcileResult] via `toFacadeResult()` (§3.2).
 */
internal sealed interface SlimReconcileResult {
    val sid: String

    data class Aligned(override val sid: String) : SlimReconcileResult
    data class Reconciled(
        override val sid: String,
        val items: List<MessageWithParts>,
    ) : SlimReconcileResult
    data class RefreshRow(override val sid: String) : SlimReconcileResult
    data class MarkDeleted(override val sid: String) : SlimReconcileResult
    data class ClearLocal(override val sid: String) : SlimReconcileResult
    data class Failure(override val sid: String) : SlimReconcileResult
    data class TimedOut(override val sid: String) : SlimReconcileResult
    data class NoRepository(override val sid: String) : SlimReconcileResult
    data class Stale(override val sid: String) : SlimReconcileResult
}

// ── §3.3 Returned coordinator command ────────────────────────────────────

/**
 * P4 §3.3: the command the reconciler returns to SSC. SSC is the SOLE
 * executor of `scope.launch { performSlimResync(...) }`; the reconciler
 * never launches coroutines.
 *
 *  - [None] — no SSC-level action required (result application is enough).
 *  - [LaunchSlimResync] — BACKGROUND needsCatchUp crossing point: SSC must
 *    launch the resync worker for [sessionsDirty]. `isManual` is false here
 *    (the digest path is never a manual trigger).
 */
internal sealed interface SlimReconcileCommand {
    data object None : SlimReconcileCommand
    data class LaunchSlimResync(
        val sessionsDirty: Set<String>,
        val isManual: Boolean,
    ) : SlimReconcileCommand
}

/**
 * P4 §3.3: the reconciler's synchronous return value — the domain result
 * PLUS the optional command SSC must execute. [command] defaults to [None]
 * (most result variants carry no command).
 */
internal data class SlimReconcileOutcome(
    val result: SlimReconcileResult,
    val command: SlimReconcileCommand = SlimReconcileCommand.None,
)

// ── §3.4 Attempt object ──────────────────────────────────────────────────

/**
 * P4 §3.4: the full reconcile attempt — the [outcome] PLUS the [token] that
 * was captured before the first suspend point and the [mode] the attempt
 * ran under. SSC consumes the command BEFORE applying the result (preserves
 * current ordering: BACKGROUND launch happened inside `reconcileSessionLocked`
 * before the caller applied the result).
 *
 * `token == null` is valid ONLY for [SlimReconcileResult.NoRepository].
 */
internal data class SlimReconcileAttempt(
    val outcome: SlimReconcileOutcome,
    val token: OpenCodeRepository.SlimCommitToken?,
    val mode: SlimReconcileMode,
    /** Route incarnation captured at workflow entry; 0L is legacy. */
    val routeInstance: Long = 0L,
)

// ── §3.5 Digest dispatch protocol ─────────────────────────────────────────

/**
 * P4 §3.5: SSC retains coroutine ownership; the synchronous digest
 * preparation step returns this decision. [Done] = no further work (digest
 * was a no-op / non-slim / malformed). [Reconcile] = SSC must launch a
 * digest-reconcile coroutine carrying the [SlimDigestReconcileRequest].
 */
internal sealed interface SlimDigestDecision {
    data object Done : SlimDigestDecision
    data class Reconcile(val request: SlimDigestReconcileRequest) : SlimDigestDecision
}

/**
 * P4 §3.5: the bundle handed from digest preparation (P4-C
 * `prepareSessionDigest`) to the digest-reconcile coroutine. Carries the
 * token captured before the first suspend so the reducer + reconcile body
 * share ONE token (the C-D3 v2 §1.8 invariant).
 */
internal data class SlimDigestReconcileRequest(
    val sid: String,
    val mode: SlimReconcileMode,
    val digest: SlimSessionDigest,
    val token: OpenCodeRepository.SlimCommitToken,
    val routeInstance: Long = 0L,
)

// ── §4.1 Repository/token port ────────────────────────────────────────────

/**
 * P4 §4.1: the narrow repository + token port the reconciler depends on.
 * ONE adapter ([OpenCodeSlimReconcileRepositoryPort]) wraps the real
 * [OpenCodeRepository] so we don't risk two repo incarnations. Every method
 * here is a 1:1 delegate to an existing `OpenCodeRepository` slim method.
 */
internal interface SlimReconcileRepositoryPort {
    fun captureCommitToken(): OpenCodeRepository.SlimCommitToken
    fun isCommitTokenCurrent(token: OpenCodeRepository.SlimCommitToken): Boolean
    fun commitIfTokenCurrent(
        token: OpenCodeRepository.SlimCommitToken,
        commit: () -> Unit,
    ): Boolean
    fun isStaleFailure(error: Throwable): Boolean
    fun getSessionState(sid: String): SlimSessionState?
    fun applyDigest(digest: SlimSessionDigest, token: OpenCodeRepository.SlimCommitToken)
    suspend fun probeLatest(sid: String): ProbeResult
    fun markDeleted(sid: String, token: OpenCodeRepository.SlimCommitToken): Boolean
    fun markFailure(sid: String, token: OpenCodeRepository.SlimCommitToken): Boolean
    fun clearLocal(sid: String, token: OpenCodeRepository.SlimCommitToken): Boolean
    fun markAligned(sid: String, token: OpenCodeRepository.SlimCommitToken): Boolean
    fun markDirty(sid: String, token: OpenCodeRepository.SlimCommitToken): Boolean
    /**
     * §11.1 fix-12b P1-2: UNCONDITIONALLY set `dirty = true` for [sid],
     * bypassing the [OpenCodeRepository.markSlimDirty] `needsReconcile`
     * gate. Used by the cache-retention failure paths in
     * [SlimSessionReconciler.applyCurrentReconcileResult] to re-ratchet
     * dirty when a non-focus `Reconciled` result's
     * [ControllerEffect.WriteSessionWindow] was dropped, filtered to
     * nothing, or arrived empty — so the next reconcile pass retries the
     * fetch instead of stranding the user with no cached window.
     *
     * Returns false (no-op) when the session has no state or token is
     * stale; still bound to the SlimCommitToken guard.
     */
    fun forceDirty(sid: String, token: OpenCodeRepository.SlimCommitToken): Boolean
    /**
     * §11.1 stage A: anchored `/since` staging fetch. Replaces the legacy
     * `fetchSince(...): Result<List<MessageWithParts>>` port, which returned
     * an authoritative-success path that stage A closes. The anchored `/since`
     * response is staging-only at every surface — the reconciler MUST NOT
     * advance the watermark / clear dirty / replace authoritative memory on
     * any [SlimSinceStageAOutcome] variant.
     *
     * §阶段B P0-4 (rev-ogpt MAJOR #1): this port is NO LONGER called from
     * the production `/since` reconcile path — [drainSlimSinceBounded]
     * (multi-page drain + P0-4 state machine) replaced it. Retained on the
     * interface for the Stage-A unit tests ([SlimSyncEngineStageATest] /
     * [OpenCodeRepositorySlimapiEndpointsTest]) which exercise the
     * single-page staging contract directly.
     */
    suspend fun fetchSinceStageA(
        sid: String,
        since: Long,
        token: OpenCodeRepository.SlimCommitToken,
    ): SlimSinceStageAOutcome

    /**
     * §阶段B P0-4 (rev-ogpt MAJOR #1): the AUTHORITATIVE anchored `/since`
     * drain. Replaces [fetchSinceStageA] as the production `/since` path
     * — multi-page drain with the P0-4 state machine (Success requires
     * `nextCursor == null && X-Since-Complete == true`; truncation retries
     * from the original anchor; Degraded on loop / zero-progress).
     *
     * Returns [SlimDrainOutcome] (Success / Partial / Degraded) directly so
     * the reconciler can fold each variant:
     *  - [SlimDrainOutcome.Success] → incremental merge + commit
     *    authoritative (the ONLY arm that may advance the watermark / clear
     *    dirty / replace visible content).
     *  - [SlimDrainOutcome.Partial] / [SlimDrainOutcome.Degraded] →
     *    preserve dirty, NO watermark advance (mirror the cold cursor
     *    drain's no-bump-on-partial contract).
     *
     * [OpenCodeRepository.StaleSlimCommitException] is THROWN by the drain
     * (NOT wrapped in a Partial); the reconciler catches it at the call
     * site and maps to Stale.
     */
    suspend fun drainSlimSinceBounded(
        sid: String,
        anchor: Long,
        token: OpenCodeRepository.SlimCommitToken,
    ): SlimDrainOutcome

    suspend fun fetchInitialWindow(
        sid: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<List<MessageWithParts>>

    /**
     * §11.1 fix-6 P0-4: authoritative commit for a complete full/cursor
     * drain candidate. The ONLY path that may advance localApplied* / clear
     * dirty / replace visible content — atomically inside the committer's
     * token-guarded critical section.
     *
     * The reconciler constructs a [SlimAuthoritativeCandidate] from the
     * drained items (using [maxMessageTuple] for the localApplied* pair) and
     * calls this method. Only a [SlimAuthoritativeCommitResult.Committed]
     * result permits the reconciler to return [SlimReconcileResult.Reconciled]
     * (items for UI merge). All other results map to Stale/Failure.
     */
    suspend fun commitAuthoritative(
        candidate: SlimAuthoritativeCandidate,
    ): SlimAuthoritativeCommitResult

    /**
     * §11.1 fix-8 P1-6: snapshot the current authoritative message list for
     * [sid] — the input to [mergeSlimMessageSet] when constructing a fresh
     * [SlimAuthoritativeCandidate]. Returns an empty list when no
     * authoritative view exists yet (cold path). The list is a defensive
     * copy; the caller may mutate freely.
     *
     * The reconciler uses this to merge the drain items onto the existing
     * authoritative set with `complete = true` semantics (missing ≠ deleted,
     * older tuple ignored, equal-tuple-different-parts kept authoritative).
     */
    fun captureAuthoritativeMessages(sid: String): List<MessageWithParts>

    /**
     * Token-bound probe entry.  The one-argument method remains the frozen
     * compatibility seam for existing test ports; real adapters validate the
     * operation token on both sides of this network suspension.
     */
    suspend fun probeLatest(
        sid: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): ProbeResult = probeLatest(sid)
}

/**
 * P4 §4.1: the sole [SlimReconcileRepositoryPort] adapter. Each method
 * delegates 1:1 to the corresponding slim method on [delegate].
 *
 * Verified against `OpenCodeRepository.kt`:
 *  - `captureSlimCommitToken()` / `isSlimCommitTokenCurrent(token)` /
 *    `commitIfSlimTokenCurrent(token, commit)` / `getSlimSessionState(sid)` /
 *    `applySlimDigest(digest, token)` / `probeLatestSlim(sid)` /
 *    `markSlimSessionDeleted(sid, token)` / `markSlimReconcileFailure(sid, token)` /
 *    `clearSlimLocalMessages(sid, token)` / `markSlimReconcileAligned(sid, token)` /
 *    `markSlimDirty(sid, token)` / `forceSlimDirty(sid, token)` all exist with these exact signatures.
 *  - `getSlimapiMessagesSince(sessionId, since, limit?, before?, token)` — the
 *    adapter passes `token = token` (named) and lets `limit` / `before`
 *    default to null, matching current call-site usage.
 *  - `fetchSlimInitialWindowBounded(sessionId, token)` matches.
 *  - `StaleSlimCommitException` is the typed stale-marker exception.
 */
internal class OpenCodeSlimReconcileRepositoryPort(
    private val delegate: OpenCodeRepository,
) : SlimReconcileRepositoryPort {
    override fun captureCommitToken(): OpenCodeRepository.SlimCommitToken =
        delegate.captureSlimCommitToken()

    override fun isCommitTokenCurrent(token: OpenCodeRepository.SlimCommitToken): Boolean =
        delegate.isSlimCommitTokenCurrent(token)

    override fun commitIfTokenCurrent(
        token: OpenCodeRepository.SlimCommitToken,
        commit: () -> Unit,
    ): Boolean = delegate.commitIfSlimTokenCurrent(token, commit)

    override fun isStaleFailure(error: Throwable): Boolean =
        error is OpenCodeRepository.StaleSlimCommitException

    override fun getSessionState(sid: String): SlimSessionState? =
        delegate.getSlimSessionState(sid)

    override fun applyDigest(
        digest: SlimSessionDigest,
        token: OpenCodeRepository.SlimCommitToken,
    ) {
        delegate.applySlimDigest(digest, token)
    }

    override suspend fun probeLatest(sid: String): ProbeResult =
        delegate.probeLatestSlim(sid)

    override suspend fun probeLatest(
        sid: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): ProbeResult {
        // Keep the existing probe facade (and its compatibility/test seam),
        // but make the crossing suspend explicitly token-bound.  The captured
        // ClientBundle is used by the subsequent token-threaded slim fetch;
        // this pre/post gate ensures a probe from A cannot drive B state.
        delegate.requireSlimTokenCurrent(token)
        val result = delegate.probeLatestSlim(sid)
        delegate.requireSlimTokenCurrent(token)
        return result
    }

    override fun markDeleted(sid: String, token: OpenCodeRepository.SlimCommitToken): Boolean =
        delegate.markSlimSessionDeleted(sid, token)

    override fun markFailure(sid: String, token: OpenCodeRepository.SlimCommitToken): Boolean =
        delegate.markSlimReconcileFailure(sid, token)

    override fun clearLocal(sid: String, token: OpenCodeRepository.SlimCommitToken): Boolean =
        delegate.clearSlimLocalMessages(sid, token)

    override fun markAligned(sid: String, token: OpenCodeRepository.SlimCommitToken): Boolean =
        delegate.markSlimReconcileAligned(sid, token)

    override fun markDirty(sid: String, token: OpenCodeRepository.SlimCommitToken): Boolean =
        delegate.markSlimDirty(sid, token)

    override fun forceDirty(sid: String, token: OpenCodeRepository.SlimCommitToken): Boolean =
        delegate.forceSlimDirty(sid, token)

    override suspend fun fetchSinceStageA(
        sid: String,
        since: Long,
        token: OpenCodeRepository.SlimCommitToken,
    ): SlimSinceStageAOutcome =
        delegate.fetchSinceForStageA(sessionId = sid, since = since, token = token)

    // §阶段B P0-4 (rev-ogpt MAJOR #1): production `/since` drain façade.
    override suspend fun drainSlimSinceBounded(
        sid: String,
        anchor: Long,
        token: OpenCodeRepository.SlimCommitToken,
    ): SlimDrainOutcome =
        delegate.drainSlimSinceBounded(sessionId = sid, anchor = anchor, token = token)

    override suspend fun fetchInitialWindow(
        sid: String,
        token: OpenCodeRepository.SlimCommitToken,
    ): Result<List<MessageWithParts>> =
        delegate.fetchSlimInitialWindowBounded(sid, token)

    override suspend fun commitAuthoritative(
        candidate: SlimAuthoritativeCandidate,
    ): SlimAuthoritativeCommitResult =
        delegate.commitAuthoritative(candidate)

    override fun captureAuthoritativeMessages(sid: String): List<MessageWithParts> =
        delegate.captureAuthoritativeMessages(sid)
}

// ── §4.2 Store/chat mutation port ─────────────────────────────────────────

/**
 * P4 §4.2: the local-state-mutation port. Every cross-domain effect stays
 * on SSC's single [SlimEffectsPort]; this port is for in-process slice /
 * settings writes only.
 */
internal interface SlimReconcileStorePort {
    fun currentChat(): ChatState
    /** Active parameterized-route token, or 0L for the legacy surface. */
    fun routeInstanceFor(sessionId: String): Long = 0L
    fun currentSessionList(): SessionListState
    fun mutateSessionList(transform: (SessionListState) -> SessionListState)
    fun dispatch(action: AppAction)
    /** §B4: lastRoute string for route-id archive/delete decisions. */
    fun lastRoute(): String
    fun mutateNav(transform: (NavState) -> NavState)
    fun clearPersistedCurrentSession()
    fun forceNavigateToSessions()
}

/**
 * P4 §4.2: the default adapter over the coordinator's [SliceFlows] +
 * [SettingsManager]. §B4: open-tabs-list persistence removed.
 */
internal class DefaultSlimReconcileStorePort(
    private val slices: SliceFlows,
    private val settingsManager: SettingsManager,
) : SlimReconcileStorePort {
    override fun currentChat(): ChatState = slices.chat.value
    override fun routeInstanceFor(sessionId: String): Long = slices.routeInstanceFor(sessionId)
    override fun currentSessionList(): SessionListState = slices.sessionList.value
    override fun mutateSessionList(transform: (SessionListState) -> SessionListState) {
        slices.mutateSessionList(transform)
    }
    override fun dispatch(action: AppAction) {
        slices.store.dispatch(action)
    }
    override fun lastRoute(): String = slices.store.stateFlow.value.nav.lastRoute
    override fun mutateNav(transform: (NavState) -> NavState) {
        slices.store.mutateNav(transform)
    }
    override fun clearPersistedCurrentSession() {
        settingsManager.currentSessionId = null
    }
    override fun forceNavigateToSessions() {
        settingsManager.lastRoute = NavRoute.Sessions.route
        slices.store.mutateNav {
            it.copy(
                lastRoute = NavRoute.Sessions.route,
                navEpoch = it.navEpoch + 1L,
            )
        }
    }
}

// ── §4.3 Reconciler ─────────────────────────────────────────────────────

/**
 * P4 §4.3 + P4-B: the extracted slim reconciler. Owns the per-sid reconcile
 * state machine (probe → fetch → fold), the digest reducer apply, and the
 * slim merge / authoritative policy. Bodies moved VERBATIM from SSC, adapted
 * to the injected ports/lambdas (per §5 audit table).
 *
 * # Returned-command protocol (§1, §3.3)
 *
 * Every reconcile entry returns a [SlimReconcileAttempt] (or the digest path
 * a [SlimReconcileOutcome]) carrying an optional [SlimReconcileCommand].
 * SSC is the SOLE executor of the worker launch — the reconciler never
 * launches a coroutine. The BACKGROUND needsCatchUp branch returns
 * [SlimReconcileCommand.LaunchSlimResync]; SSC interprets it via
 * `executeSlimReconcileCommand`.
 *
 * **Exclusions (per §4.3 + §11 acceptance)**: no `SessionSyncCoordinator`;
 * no `CoroutineScope`; no `SlimResyncCadence`; no `currentEpoch`; no
 * worker/`performSlimResync` callback; no second `Mutex` array; no second
 * effect bus.
 *
 * **Symbol resolution (preemptive scoping — §5)**: every original SSC symbol
 * reference resolves via (a) an injected port ([repository]/[store] /
 * [effects] / [stripeLock]), (b) an injected lambda
 * ([supportsWatermarkResync] / [currentServerGroupFp] / [reportNonFatal]),
 * or (c) a shared top-level helper (`needsCatchUp` from `SlimapiProbe.kt`,
 * `requireSlimOnlyStateWrite`, `DebugLog`, `AppAction`, `ControllerEffect`).
 * NO direct SSC-method calls. The ONLY control-flow back to SSC is the
 * returned [SlimReconcileCommand].
 */
internal class SlimSessionReconciler(
    private val repository: SlimReconcileRepositoryPort?,
    private val store: SlimReconcileStorePort,
    private val stripeLock: StripeLock,
    private val effects: SlimEffectsPort,
    private val supportsWatermarkResync: () -> Boolean,
    private val currentServerGroupFp: () -> String,
    private val reconcileDispatcher: CoroutineDispatcher,
    private val digestJson: Json = lenientJson,
    private val tag: String = "SessionSyncCoordinator",
    private val reportNonFatal: (String) -> Unit = { reportNonFatalIssue(tag, it) },
) {
    /**
     * §B4 / §10: if [sessionId] is the active chat/{id} route or residual
     * currentSessionId, CloseDetail + force popToSessions. Used after
     * SessionArchived dispatch so the list-detail pane cannot stay on a
     * deleted/archived conversation.
     */
    private fun popDetailIfActive(sessionId: String) {
        val routeId = routeChatSessionId(store.lastRoute())
        val currentId = store.currentChat().currentSessionId
        if (routeId != sessionId && currentId != sessionId) return
        store.dispatch(AppAction.CloseDetail)
        store.clearPersistedCurrentSession()
        store.forceNavigateToSessions()
    }

    // ── §5: applyDigestLastErrorToBanner (StorePort.mutateSessionList) ────

    /**
     * P4-B (moved from SSC): three-state `lastError` fold into the
     * session-list's `sessionErrorsById` map. VERBATIM body; only the
     * slice mutation routes through [store.mutateSessionList].
     *
     * # NOT a banner abstraction (T12-C4)
     *
     * The map is written directly. There is no
     * `repository.applySessionErrorBanner` / `sessionBanners` indirection.
     */
    private fun applyDigestLastErrorToBanner(sid: String, field: LastErrorField) {
        when (field) {
            LastErrorField.Omitted -> { /* no-op — preserve prior banner */ }
            LastErrorField.Cleared -> {
                store.mutateSessionList { s ->
                    if (sid !in s.sessionErrorsById) s
                    else s.copy(sessionErrorsById = s.sessionErrorsById - sid)
                }
            }
            is LastErrorField.Set -> {
                val banner = field.error
                store.mutateSessionList { s ->
                    s.copy(sessionErrorsById = s.sessionErrorsById + (sid to banner))
                }
            }
        }
    }

    // ── §5 + §6.3: prepareSessionDigest (DIGEST PREP — synchronous, no launch) ─

    /**
     * P4-C (moved from SSC's `handleSessionDigestImpl`, §6.3): the
     * synchronous digest-event PREPARATION step. Does NOT launch a
     * coroutine (SSC owns the launch). Does NOT call any SSC method —
     * every symbol resolves via an injected port ([repository]/[store] /
     * [effects]) or lambda ([supportsWatermarkResync] /
     * [currentServerGroupFp] / [reportNonFatal]) or a shared helper
     * (`applySessionStatus`, `applyMessageTimestampBump`, `DebugLog`).
     *
     * # Responsibilities (verbatim from the original body)
     *
     *  - Decode the digest via [digestJson] (NON-COERCING — T6 invariant;
     *    the [SlimSessionDigest.lastError] three-state Omitted/Cleared/Set
     *    is faithful ONLY under non-coercing Json).
     *  - Status badge fold ([SlimSessionDigest.status] → sessionStatuses).
     *  - Archived / deleted eviction (open-tabs + session list +
     *    [ControllerEffect.EvictSession]).
     *  - Lightweight `applyMessageTimestampBump` so recent-sort reflects
     *    activity.
     *  - Mode selection (DIGEST_FOCUS if the digest's sid is the currently-
     *    open chat tab; DIGEST_BACKGROUND otherwise).
     *  - **Token capture BEFORE the first suspend point** (§6.3 / §11:
     *    "One token captured before first suspend + threaded unchanged").
     *    This method is synchronous; the suspend happens inside SSC's
     *    launched `reconcileDigest(request)`. The captured token rides in
     *    the returned [SlimDigestReconcileRequest] so the reducer apply, the
     *    fetch, and the final commit gate all see the SAME token (the C-D3
     *    v2 §1.8 invariant).
     *
     * # `applySseSideEffects(ReportNonFatal)` → `reportNonFatal` (§5 audit)
     *
     * The original body's `applySseSideEffects(listOf(SseSideEffect.ReportNonFatal(msg)))`
     * call is the ONLY direct SSC-helper call in the digest parser (per §5
     * audit). It is replaced here by the injected [reportNonFatal] lambda
     * (which defaults to `reportNonFatalIssue(tag, it)` — the exact behavior
     * of `applySseSideEffectsImpl`'s `ReportNonFatal` branch: SSC L923
     * `reportNonFatalIssue(tag, effect.message)`). The digest parser no
     * longer routes through the side-effect bus.
     *
     * # Control-flow back to SSC
     *
     * Returns [SlimDigestDecision.Done] (no-op / non-slim / malformed /
     * no-repo) or [SlimDigestDecision.Reconcile] (SSC launches the
     * digest-reconcile coroutine carrying the request). The ONLY control-
     * flow back to SSC is this returned decision.
     *
     * §CE-discipline (R-14): the [digestJson] decode is synchronous (no CE
     * possible) so the [runCatching] here is safe. The suspend path lives
     * in [reconcileDigest] / [reconcileSessionLocked].
     */
    internal fun prepareSessionDigest(event: SSEEvent): SlimDigestDecision {
        val props = event.payload.properties
        if (props == null) {
            reportNonFatal("Ignoring session.digest with null properties")
            return SlimDigestDecision.Done
        }
        val digest = runCatching {
            digestJson.decodeFromJsonElement<SlimSessionDigest>(props)
        }.getOrNull()
        if (digest == null || digest.sessionId.isBlank()) {
            reportNonFatal("Ignoring invalid session.digest payload")
            return SlimDigestDecision.Done
        }
        DebugLog.d(
            "Sync",
            "session.digest sid=${digest.sessionId} status=${digest.status} " +
                "updatedAt=${digest.updatedAt} messageId=${digest.messageId} " +
                "archived=${digest.archived} deleted=${digest.deleted}",
        )
        // Status badge (slim stand-in for session.status).
        digest.status?.takeIf { it.isNotBlank() }?.let { statusType ->
            // §streaming-state-sync-diag (runtime-gated, scoped): record each
            // slim-digest status fold so we can attribute optimistic-busy
            // overwrites. Scope to the current (open) session — non-current
            // sessions' status folds carry no streaming-send signal.
            if (DebugLog.verboseDiagEnabled &&
                digest.sessionId == store.currentChat().currentSessionId
            ) {
                DebugLog.d(
                    "StatusDiag",
                    "slim digest status write sid=${digest.sessionId} status=$statusType",
                )
            }
            store.mutateSessionList {
                it.applySessionStatus(digest.sessionId, SessionStatus(type = statusType)).first
            }
        }
        // Task 12 round-2 (I1 fix): the digest's three-state `lastError`
        // fold into [SessionListState.sessionErrorsById] is routed THROUGH
        // the per-sid stripe — see [reconcileDigest] (the fold runs inside
        // the stripe, before the reconcile body).
        //
        // Archived / deleted eviction (slim stand-in for session.updated archived).
        // §B4: no open-tabs-list prune; route pop when route/current matches.
        if (digest.deleted == true || (digest.archived != null && digest.archived > 0L)) {
            val currentList = store.currentSessionList()
            val archivedSession = Session(
                id = digest.sessionId,
                directory = digest.directory
                    ?: currentList.sessions.firstOrNull { it.id == digest.sessionId }?.directory
                    ?: event.directory
                    ?: "",
                time = Session.TimeInfo(
                    archived = digest.archived?.takeIf { it > 0L } ?: if (digest.deleted == true) 1L else null,
                ),
            )
            store.dispatch(AppAction.SessionArchived(session = archivedSession))
            popDetailIfActive(digest.sessionId)
            effects.tryEmitEffect(
                ControllerEffect.EvictSession(currentServerGroupFp(), digest.sessionId)
            )
        } else if (digest.directory != null || digest.updatedAt != null) {
            // Lightweight session list touch so recent-sort reflects activity.
            val bumpAt = digest.updatedAt ?: 0L
            if (bumpAt > 0L) {
                store.mutateSessionList { s ->
                    s.applyMessageTimestampBump(digest.sessionId, bumpAt).first
                }
            }
        }
        val repo = repository
        if (repo == null) {
            DebugLog.d("Sync", "session.digest: no repository wired — skip reconcile")
            return SlimDigestDecision.Done
        }
        val sid = digest.sessionId
        // T11 round-2 (oracle I4): select ReconcileMode based on whether
        // this is the currently-open chat tab. FOCUS may fetch + clear
        // dirty; BACKGROUND never clears dirty and never fetches (only
        // refreshes the row).
        val mode = if (sid == store.currentChat().currentSessionId) {
            SlimReconcileMode.DIGEST_FOCUS
        } else {
            SlimReconcileMode.DIGEST_BACKGROUND
        }
        // §streaming-state-sync-diag (runtime-gated, scoped): entry context
        // for the reconcile decision. Logs the digest's updatedAt + the prior
        // localApplied watermark so we can confirm the
        // "updatedAt-not-advancing → fetch skipped" hypothesis. Scope to the
        // current (open) session — non-current digests go through BACKGROUND
        // mode (no FETCH decision) and carry no streaming-send signal.
        if (DebugLog.verboseDiagEnabled &&
            sid == store.currentChat().currentSessionId
        ) {
            val priorLocalApplied = repo.getSessionState(sid)?.localAppliedUpdatedAt
            val priorRemote = repo.getSessionState(sid)?.remoteUpdatedAt
            DebugLog.d(
                "DigestDiag",
                "digest entry sid=$sid updatedAt=${digest.updatedAt} " +
                    "priorLocalApplied=$priorLocalApplied priorRemote=$priorRemote mode=$mode " +
                    "messageId=${digest.messageId}",
            )
        }
        // T11 round-3 (oracle workflow-serialization): the reducer apply
        // + reconcile body run inside the SAME per-sid stripe lock so
        // competing digest / reconcile triggers for this sid serialize
        // end-to-end (see [reconcileDigest]).
        //
        // §6.3: capture the token BEFORE the first suspend point (this
        // method is synchronous; the launch lives in SSC). The captured
        // token rides in the [SlimDigestReconcileRequest] so the reducer
        // apply, the fetch, and the final commit gate all see the SAME
        // token — the C-D3 v2 §1.8 "single entry token, no recapture"
        // invariant, pinned by the P4-A §8.3 characterization test.
        val commitToken = repo.captureCommitToken()
        val request = SlimDigestReconcileRequest(
            sid = sid,
            mode = mode,
            digest = digest,
            token = commitToken,
            routeInstance = store.routeInstanceFor(sid),
        )
        // SSC owns the coroutine launch + command interpretation; the
        // reconciler performs the reducer apply + reconcile body + UI fold
        // when SSC calls [reconcileDigest] with this request. The returned
        // command (propagated UNCHANGED from `reconcileSessionLocked` —
        // critically NOT flattened, so the BACKGROUND `LaunchSlimResync`
        // survives) is interpreted by SSC BEFORE applying the result.
        return SlimDigestDecision.Reconcile(request)
    }

    // ── §5 + §6.4: reconcileDigest (repo port + StripeLock + dispatcher) ─

    /**
     * P4-B (moved from SSC, §6.4 EXACT): the digest-driven per-sid workflow
     * entry. Acquires the stripe ONCE for the WHOLE workflow (banner commit
     * + reducer apply + reconcile body) so competing digest / reconcile
     * triggers for the same sid serialize end-to-end.
     *
     * PROPAGATES the command from [reconcileSessionLocked] — does NOT
     * flatten to RefreshRow (that would silently lose the
     * [SlimReconcileCommand.LaunchSlimResync] emitted by the BACKGROUND
     * needsCatchUp branch).
     *
     * # Network IO discipline
     *
     * The stripe lock CAN be held across network IO (per-sid, bounded by
     * the resync sweep's Semaphore(4)). The REPO `slimStateLock` is the one
     * that must stay in-memory-only.
     *
     * @throws NullPointerException if [repository] is null — the caller
     *   (SSC digest prep) is required to have a non-null repo before
     *   building the [SlimDigestReconcileRequest] (the token in the request
     *   can only have been captured with a live repo).
     */
    internal suspend fun reconcileDigest(request: SlimDigestReconcileRequest): SlimReconcileAttempt {
        // request.token is non-null → caller guaranteed repository != null.
        val repo = repository!!
        // UI banner mutation happens before the worker lock.
        val bannerCommitted = withContext(Dispatchers.Main.immediate) {
            repo.commitIfTokenCurrent(request.token) {
                applyDigestLastErrorToBanner(request.sid, request.digest.lastError)
            }
        }
        if (!bannerCommitted) {
            return SlimReconcileAttempt(
                outcome = SlimReconcileOutcome(SlimReconcileResult.Stale(request.sid)),
                token = request.token,
                mode = request.mode,
                routeInstance = request.routeInstance,
            )
        }
        val outcome = withContext(reconcileDispatcher) {
            stripeLock.stripeFor(request.sid).withLock {
                if (!repo.isCommitTokenCurrent(request.token)) {
                    return@withLock SlimReconcileOutcome(SlimReconcileResult.Stale(request.sid))
                }

                repo.applyDigest(request.digest, request.token)

                if (!repo.isCommitTokenCurrent(request.token)) {
                    return@withLock SlimReconcileOutcome(SlimReconcileResult.Stale(request.sid))
                }

                reconcileSessionLocked(
                    sid = request.sid,
                    mode = request.mode,
                    token = request.token,
                    snapshotCurrentSessionId = store.currentChat().currentSessionId,
                )
            }
        }
        return SlimReconcileAttempt(
            outcome = outcome,
            token = request.token,
            mode = request.mode,
            routeInstance = request.routeInstance,
        )
    }

    // ── §5 + §6.2: reconcileSession (internal entry) ─────────────────────

    /**
     * P4-B (moved from SSC, §6.2): the reconciler-internal entry that
     * serves BOTH digest-driven updates AND resync catch-up (via
     * [reconcileSessionWithToken]). Returns a [SlimReconcileAttempt]
     * carrying the captured token + outcome + mode so SSC can execute the
     * command BEFORE applying the result (preserves current ordering: the
     * BACKGROUND launch happened inside `reconcileSessionLocked` before the
     * caller applied the result).
     *
     * Captures the commit token ONCE before the first suspend point and
     * threads it through every nested suspend surface + commit gate (the
     * C-D3 v2 §1.8 "single entry token, no recapture" invariant — pinned
     * by the P4-A §8.3 characterization test).
     */
    internal suspend fun reconcileSession(sid: String, mode: SlimReconcileMode): SlimReconcileAttempt {
        val repo = repository ?: return SlimReconcileAttempt(
            outcome = SlimReconcileOutcome(SlimReconcileResult.NoRepository(sid)),
            token = null,
            mode = mode,
        )
        if (!supportsWatermarkResync()) return SlimReconcileAttempt(
            outcome = SlimReconcileOutcome(SlimReconcileResult.NoRepository(sid)),
            token = null,
            mode = mode,
        )

        // C-D3 v2 §1.8: public workflow entry captures once before its
        // first suspend point. Every nested suspend call and every commit
        // surface receives this exact token — NO recapture inside.
        val routeInstance = store.routeInstanceFor(sid)
        val token = repo.captureCommitToken()

        val outcome = reconcileSessionWithToken(
            sid = sid,
            mode = mode,
            token = token,
            isStillCurrent = { true },
        )
        return SlimReconcileAttempt(
            outcome = outcome,
            token = token,
            mode = mode,
            routeInstance = routeInstance,
        )
    }

    // ── §5 + §6.5: reconcileSessionWithToken (stripe + dispatcher) ───────

    /**
     * P4-B (moved from SSC, §6.5): shared reconcile body that threads an
     * externally-supplied token through the stripe + reconcile body. Used
     * by [reconcileSession] (public entry, captures fresh) and by SSC's
     * `performResyncCatchUp` (uses the orchestrator's entry token, no
     * recapture).
     *
     * Returns the locked [SlimReconcileOutcome] (result + command)
     * UNCHANGED — does NOT flatten the command. SSC executes the command
     * outside the stripe if needed (RESYNC currently never produces a
     * command, but the protocol is exhaustive + future-safe).
     */
    internal suspend fun reconcileSessionWithToken(
        sid: String,
        mode: SlimReconcileMode,
        token: OpenCodeRepository.SlimCommitToken,
        isStillCurrent: () -> Boolean,
    ): SlimReconcileOutcome {
        val repo = repository ?: return SlimReconcileOutcome(SlimReconcileResult.NoRepository(sid))
        return withContext(reconcileDispatcher) {
            stripeLock.stripeFor(sid).withLock {
                // C-D3 v2 §1.8: re-check both predicates under the stripe. A host
                // switch between the entry capture and the stripe acquisition
                // surfaces as Stale (NOT NoRepository — the repo IS wired, but
                // the token predates the rotation).
                if (!isStillCurrent() || !repo.isCommitTokenCurrent(token)) {
                    return@withLock SlimReconcileOutcome(SlimReconcileResult.Stale(sid))
                }

                reconcileSessionLocked(
                    sid = sid,
                    mode = mode,
                    token = token,
                    snapshotCurrentSessionId = null,
                )
            }
        }
    }

    // ── §5 + §6.4: reconcileSessionLocked (THE crossing point) ──────────

    /**
     * P4-B (moved from SSC): the reconciler body, called under the per-sid
     * stripe lock. Reads the latest [SlimSessionState], probes, and
     * dispatches to the appropriate T6 primitive via the repository port's
     * public mutators.
     *
     * # 🔴 THE crossing point (BACKGROUND → LaunchSlimResync)
     *
     * The BACKGROUND needsCatchUp branch (`!mode.mayFetch()`) returns
     * [SlimReconcileOutcome] with [SlimReconcileResult.RefreshRow] PLUS
     * [SlimReconcileCommand.LaunchSlimResync]`(setOf(sid), isManual=false)`.
     * It does NOT directly launch a coroutine, does NOT read `currentEpoch`,
     * does NOT reference `performSlimResync`. SSC is the SOLE executor of
     * the resync worker launch.
     *
     * The unused `val gen = currentEpoch()` at the original SSC L1730 /
     * L1822 was REMOVED (the worker's internal cadence guard reads
     * `currentEpoch()` itself inside `performSlimResync`).
     *
     * @throws NullPointerException if [repository] is null — every caller
     *   ([reconcileSession], [reconcileSessionWithToken], [reconcileDigest])
     *   null-checks before calling.
     */
    private suspend fun reconcileSessionLocked(
        sid: String,
        mode: SlimReconcileMode,
        token: OpenCodeRepository.SlimCommitToken,
        snapshotCurrentSessionId: String?,
    ): SlimReconcileOutcome {
        // All callers null-check repository before calling.
        val repo = repository!!

        // T11 round-3: the slim-mode guard lives HERE (not just at the
        // [reconcileSession] public entry) so the [reconcileDigest]
        // workflow path (which calls this body directly under the
        // stripe) ALSO short-circuits for legacy non-slim mode.
        if (!supportsWatermarkResync()) return SlimReconcileOutcome(SlimReconcileResult.NoRepository(sid))

        val state = repo.getSessionState(sid) ?: SlimSessionState(sessionId = sid)
        // §stale-crash-fix-2 (2026-07-26): probeLatest calls
        // requireSlimTokenCurrent(token) which throws StaleSlimCommitException
        // if the incarnation rotated between the token capture (prepareSessionDigest
        // / reconcileSession entry) and this point (after acquiring the stripe lock).
        // The TOCTOU gap means ANY session.digest SSE event arriving after a host
        // reconfigure (common when switching back from background) hits this path.
        // Without this catch, the throw escapes reconcileSessionLocked →
        // reconcileDigest → handleSessionDigest's scope.launch (no catch) →
        // UiApplicationScope (SupervisorJob + Dispatchers.Main.immediate, no
        // CoroutineExceptionHandler) → app crash. This is the SAME class of bug
        // as the per-sid catch fixed earlier in SessionSyncCoordinator:1632, but
        // on a different entry path (digest vs resync). Catching here covers ALL
        // three callers (digest, reconcileSession, reconcileSessionWithToken)
        // because they all funnel through reconcileSessionLocked.
        val probe = try {
            repo.probeLatest(sid, token)
        } catch (e: OpenCodeRepository.StaleSlimCommitException) {
            DebugLog.d("Sync", "reconcileSessionLocked sid=$sid stale token — incarnation rotated (TOCTOU)")
            return SlimReconcileOutcome(SlimReconcileResult.Stale(sid))
        }

        // ── probe failure branches (uniform across all modes) ────────────
        if (!probe.ok) {
            if (probe.httpStatus == 404) {
                // Session gone upstream → mark deleted (all modes).
                if (!repo.markDeleted(sid, token)) {
                    return SlimReconcileOutcome(SlimReconcileResult.Stale(sid))
                }
                DebugLog.i("Sync", "reconcileSession sid=$sid mode=$mode 404 → markDeleted")
                return SlimReconcileOutcome(SlimReconcileResult.MarkDeleted(sid))
            }
            // Transport / network failure → keep dirty (all modes).
            if (!repo.markFailure(sid, token)) {
                return SlimReconcileOutcome(SlimReconcileResult.Stale(sid))
            }
            DebugLog.i(
                "Sync",
                "reconcileSession sid=$sid mode=$mode probe failed httpStatus=${probe.httpStatus} → keep dirty",
            )
            return SlimReconcileOutcome(SlimReconcileResult.Failure(sid))
        }

        // ── probe ok + empty branch ──────────────────────────────────────
        if (probe.empty) {
            val localHas = state.localAppliedMessageId != null ||
                state.localAppliedUpdatedAt != null
            if (localHas) {
                // FOCUS/RESYNC: clear local + clear dirty.
                // BACKGROUND: no-op (keep dirty; do NOT clear local — the
                // local cache is the user's open tab's history).
                if (mode.mayClearDirty()) {
                    if (!repo.clearLocal(sid, token)) {
                        return SlimReconcileOutcome(SlimReconcileResult.Stale(sid))
                    }
                    DebugLog.i("Sync", "reconcileSession sid=$sid mode=$mode probe empty + local-has → clearLocal")
                    return SlimReconcileOutcome(SlimReconcileResult.ClearLocal(sid))
                }
                DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode probe empty + local-has → BACKGROUND no-op (keep dirty)")
                return SlimReconcileOutcome(SlimReconcileResult.RefreshRow(sid))
            }
            // Local-empty + probe-empty: aligned.
            // FOCUS/RESYNC: clear dirty.
            // BACKGROUND: no-op (keep dirty).
            if (mode.mayClearDirty()) {
                if (!repo.markAligned(sid, token)) {
                    return SlimReconcileOutcome(SlimReconcileResult.Stale(sid))
                }
                DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode probe empty + local-empty → aligned")
                return SlimReconcileOutcome(SlimReconcileResult.Aligned(sid))
            }
            DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode probe empty + local-empty → BACKGROUND no-op")
            return SlimReconcileOutcome(SlimReconcileResult.RefreshRow(sid))
        }

        // ── probe ok + non-empty: needsCatchUp decision ─────────────────
        val catchUp = needsCatchUp(
            probe = probe,
            localAppliedId = state.localAppliedMessageId,
            localAppliedTs = state.localAppliedUpdatedAt,
        )
        if (!catchUp) {
            // §streaming-state-sync-diag (runtime-gated, scoped): probe says
            // caught up → no FETCH. Scope to current session (resync catch-up
            // runs this body for every dirty sid — non-current sids carry no
            // streaming-send signal).
            if (DebugLog.verboseDiagEnabled &&
                sid == snapshotCurrentSessionId
            ) {
                DebugLog.d(
                    "DigestDiag",
                    "digest sid=$sid remoteUpdatedAt=${state.remoteUpdatedAt} " +
                        "priorWatermark=${state.localAppliedUpdatedAt} decision=skip reason=aligned mode=$mode",
                )
            }
            if (mode.mayClearDirty()) {
                if (!repo.markAligned(sid, token)) {
                    return SlimReconcileOutcome(SlimReconcileResult.Stale(sid))
                }
                DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode probe aligned → clear dirty")
                return SlimReconcileOutcome(SlimReconcileResult.Aligned(sid))
            }
            DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode probe aligned → BACKGROUND no clear")
            return SlimReconcileOutcome(SlimReconcileResult.RefreshRow(sid))
        }

        // ── needsCatchUp: FOCUS/RESYNC fetch; BACKGROUND refresh only ────
        if (!mode.mayFetch()) {
            // §streaming-state-sync-diag (runtime-gated, scoped): BACKGROUND
            // mode → no FETCH (refresh row only).
            if (DebugLog.verboseDiagEnabled &&
                sid == snapshotCurrentSessionId
            ) {
                DebugLog.d(
                    "DigestDiag",
                    "digest sid=$sid remoteUpdatedAt=${state.remoteUpdatedAt} " +
                        "priorWatermark=${state.localAppliedUpdatedAt} decision=skip reason=BACKGROUND mode=$mode",
                )
            }
            DebugLog.d("Sync", "reconcileSession sid=$sid mode=$mode BACKGROUND needsCatchUp → schedule resync")
            // P4-B §3.3: BACKGROUND crossing point — return LaunchSlimResync.
            // SSC is the SOLE executor of performSlimResync (its internal
            // cadence guard owns the scheduling decision). NO direct launch,
            // NO currentEpoch. The previously-unused `val gen = currentEpoch()`
            // was REMOVED (performSlimResync reads currentEpoch() itself).
            return SlimReconcileOutcome(
                result = SlimReconcileResult.RefreshRow(sid),
                command = SlimReconcileCommand.LaunchSlimResync(
                    sessionsDirty = setOf(sid),
                    isManual = false,
                ),
            )
        }

        // §streaming-state-sync-diag (runtime-gated, scoped): FETCH decision —
        // about to call /slimapi/messages/since.
        if (DebugLog.verboseDiagEnabled &&
            sid == snapshotCurrentSessionId
        ) {
            DebugLog.d(
                "DigestDiag",
                "digest sid=$sid remoteUpdatedAt=${state.remoteUpdatedAt} " +
                    "priorWatermark=${state.localAppliedUpdatedAt} decision=FETCH mode=$mode",
            )
        }

        // Watermark-branched fetch (oracle I2):
        //   - localAppliedUpdatedAt != null → anchored `/since` drain
        //     (§阶段B P0-4 state machine: multi-page Success requires
        //     `nextCursor == null && X-Since-Complete == true`; truncation
        //     retries; Degraded on loop / zero-progress).
        //   - localAppliedUpdatedAt == null → bounded NO-ANCHOR cursor
        //     drain façade (cold path).
        return if (state.localAppliedUpdatedAt != null) {
            // §阶段B P0-4 (rev-ogpt MAJOR #1): production `/since` path is
            // the multi-page drain façade (NOT the staging-only Stage-A
            // single-page fetch). The drain's P0-4 state machine decides
            // Success vs Partial vs Degraded. StaleSlimCommitException is
            // thrown by the drain on stale incarnation (NOT wrapped in a
            // Partial); caught here and mapped to Stale (mirrors the
            // probeLatest TOCTOU catch above).
            val anchor = state.localAppliedUpdatedAt!!
            val outcome = try {
                repo.drainSlimSinceBounded(sid, anchor, token)
            } catch (e: OpenCodeRepository.StaleSlimCommitException) {
                DebugLog.d("Sync", "reconcileSessionLocked sid=$sid /since drain stale — incarnation rotated (TOCTOU)")
                return SlimReconcileOutcome(SlimReconcileResult.Stale(sid))
            }
            val folded = foldSinceDrainFetch(sid, mode, outcome, token)
            SlimReconcileOutcome(folded)
        } else {
            // Cold path: no local watermark yet. Use the bounded cursor
            // drain façade (reuses T5's drainSlimapiMessagesBounded). This is
            // the authoritative path — full/cursor drain Success may advance
            // the watermark (and stage-A fix-4 drives commitAuthoritative).
            val result = repo.fetchInitialWindow(sid, token)
            val folded = foldRestFetch(sid, mode, result, token)
            SlimReconcileOutcome(folded)
        }
    }

    // ── §11.1 stage A: foldStageAFetch (anchored /since staging path) ──────

    /**
     * §11.1 stage A: fold the anchored `/since` [SlimSinceStageAOutcome] into
     * a [SlimReconcileResult]. The anchored `/since` response is staging-only
     * — this fold MUST NOT advance the watermark / clear dirty / call
     * bumpSlimBookmarkFromItems / markSlimReconcileAligned / onReconcileSuccess
     * / clearLocal. Staged items MUST NOT leak into the UI merge path
     * (fix-6 P0-1); they are temporary staging/diagnostics only.
     *
     *  - [SlimSinceStageAOutcome.Staged] → [SlimReconcileResult.RefreshRow]
     *    (NO items carried to UI merge; the staged items are temporary
     *    staging-only). The watermark is NOT advanced. dirty stays whatever
     *    it was. Only a complete full/cursor candidate committed via
     *    [SlimAuthoritativeCommitter.commitAuthoritative] may merge items.
     *  - [SlimSinceStageAOutcome.Incomplete] → [SlimReconcileResult.RefreshRow]
     *    (keep dirty; no items; no watermark advance).
     *  - [SlimSinceStageAOutcome.Failed] → [SlimReconcileResult.Failure] (mark
     *    failure preserves dirty) unless the token went stale (Stale).
     *
     * **§阶段B P0-4 (rev-ogpt MAJOR #1): DEAD CODE in production.** The
     * production `/since` path now uses [foldSinceDrainFetch] (multi-page
     * drain + P0-4 state machine). This fold is retained only for the
     * Stage-A port's binary-compat surface ([fetchSinceStageA] is still on
     * the interface for the unit tests of the single-page staging contract).
     */
    private suspend fun foldStageAFetch(
        sid: String,
        mode: SlimReconcileMode,
        outcome: SlimSinceStageAOutcome,
        token: OpenCodeRepository.SlimCommitToken,
    ): SlimReconcileResult {
        val repo = repository ?: return SlimReconcileResult.NoRepository(sid)
        return when (outcome) {
            is SlimSinceStageAOutcome.Staged -> {
                // §11.1 fix-6 P0-1: Staged items are temporary staging-only.
                // They MUST NOT leak into the UI merge path. Only a complete
                // full/cursor candidate committed via commitAuthoritative may
                // produce a Reconciled result that carries items to the chat.
                DebugLog.d(
                    tag,
                    "reconcileSession sid=$sid mode=$mode stage-A /since staged " +
                        "items=${outcome.items.size} completeHeader=${outcome.completeHeader} " +
                        "→ staging-only (no UI merge)",
                )
                if (!repo.isCommitTokenCurrent(token)) SlimReconcileResult.Stale(sid)
                else SlimReconcileResult.RefreshRow(sid)
            }

            is SlimSinceStageAOutcome.Incomplete -> {
                // null body or other incompleteness — keep dirty, no items.
                DebugLog.d(
                    tag,
                    "reconcileSession sid=$sid mode=$mode stage-A /since incomplete " +
                        "reason=${outcome.reason} → keep dirty",
                )
                SlimReconcileResult.RefreshRow(sid)
            }

            is SlimSinceStageAOutcome.Failed -> {
                if (!repo.isCommitTokenCurrent(token)) {
                    SlimReconcileResult.Stale(sid)
                } else {
                    val marked = repo.markFailure(sid, token)
                    if (!marked) {
                        SlimReconcileResult.Stale(sid)
                    } else {
                        DebugLog.w(
                            tag,
                            "reconcileSession sid=$sid mode=$mode stage-A /since failed: ${outcome.cause.message}",
                        )
                        SlimReconcileResult.Failure(sid)
                    }
                }
            }
        }
    }

    // ── §阶段B P0-4: foldSinceDrainFetch (anchored /since drain path) ──────

    /**
     * §阶段B P0-4 (rev-ogpt MAJOR #1): fold the anchored `/since` drain
     * [SlimDrainOutcome] into a [SlimReconcileResult]. This is the
     * PRODUCTION `/since` path — the multi-page drain with the P0-4 state
     * machine (Success requires `nextCursor == null &&
     * X-Since-Complete == true`; truncation retries; Degraded on loop /
     * zero-progress).
     *
     *  - [SlimDrainOutcome.Success] → construct a [SlimAuthoritativeCandidate]
     *    from the merged items and drive [commitAuthoritative]. This is the
     *    ONLY arm that may advance the watermark / clear dirty / replace
     *    visible content. Only a [SlimAuthoritativeCommitResult.Committed]
     *    result permits [SlimReconcileResult.Reconciled] (items for UI merge).
     *
     *    # Incremental merge contract (frozen protocol)
     *
     *    The drained items are merged onto the current authoritative set
     *    via [mergeSlimMessageSetWithConflict]`(..., complete = true)`. The
     *    `/since` anchored scan is best-effort INCREMENTAL — a message
     *    present in authoritative but absent from the drained set is NOT a
     *    deletion (frozen: "missing message ≠ deleted"). The `complete =
     *    true` arm already implements this exact contract (per the §11.4
     *    "missing message is not interpreted as deletion" test): union
     *    merge with newer-wins-per-id, missing-from-incoming RETAINED, and
     *    same-tuple-different-parts kept authoritative + hasConflict flag
     *    set. The hasConflict flag threads into the commit's atomic dirty
     *    decision so a same-tuple parts divergence forces a retry.
     *
     *    (NB: `complete = false` in [mergeSlimMessageSetWithConflict] is a
     *    documented no-op that drops incoming — it does NOT implement the
     *    incremental union contract that the `/since` drain requires, so we
     *    use `complete = true` which has the correct union + missing-retained
     *    semantics.)
     *
     *  - [SlimDrainOutcome.Partial] → mid-walk HTTP / transport / timeout /
     *    protocol failure (truncation Partials are retried inside the drain
     *    and only surface as Degraded after the retry budget is exhausted).
     *    [SlimDrainOutcome.items] is staging/diagnostics only and is
     *    DROPPED. preserve dirty, NO watermark advance. Mirror the cold
     *    cursor drain's no-bump-on-partial: stale token → Stale; otherwise
     *    markFailure → Failure.
     *
     *  - [SlimDrainOutcome.Degraded] → loop / zero-progress / truncation-
     *    retries-exhausted. Same contract as Partial: preserve dirty, NO
     *    watermark advance.
     *
     * C-D3 v2 §1.9: Stale ≠ Failure. A stale token result must NOT call
     * markFailure (that pollutes error state for the new incarnation).
     *
     * CE discipline: a non-timeout [CancellationException] carried as a
     * Partial/Degraded cause is re-thrown (structured cancellation). Only
     * [TimeoutCancellationException] (the 30 s wall-clock bound) maps to
     * Failure.
     */
    private suspend fun foldSinceDrainFetch(
        sid: String,
        mode: SlimReconcileMode,
        outcome: SlimDrainOutcome,
        token: OpenCodeRepository.SlimCommitToken,
    ): SlimReconcileResult {
        val repo = repository ?: return SlimReconcileResult.NoRepository(sid)
        return when (outcome) {
            is SlimDrainOutcome.Success -> {
                if (!repo.isCommitTokenCurrent(token)) return SlimReconcileResult.Stale(sid)
                // §阶段B P0-4 incremental merge: drained items merged onto
                // the existing authoritative set. Missing-from-incoming ids
                // MUST be RETAINED (frozen protocol: "missing message ≠
                // deleted"). mergeSlimMessageSetWithConflict(complete = true)
                // implements this exact contract — see the §11.4 "no
                // tombstone" test. The hasConflict flag is threaded into
                // the commit's atomic dirty decision.
                val authoritative = repo.captureAuthoritativeMessages(sid)
                val mergeResult = mergeSlimMessageSetWithConflict(
                    authoritative = authoritative,
                    incoming = outcome.items,
                    complete = true,
                )
                val merged = mergeResult.messages
                val (ts, id) = maxMessageTuple(merged)
                    ?.let { it.first to it.second } ?: (null to null)
                val candidate = SlimAuthoritativeCandidate(
                    sessionId = sid,
                    token = token,
                    messages = merged,
                    localAppliedUpdatedAt = ts,
                    localAppliedMessageId = id,
                    hasConflict = mergeResult.hasConflict,
                )
                when (val commitResult = repo.commitAuthoritative(candidate)) {
                    is SlimAuthoritativeCommitResult.Committed ->
                        SlimReconcileResult.Reconciled(sid, merged)
                    is SlimAuthoritativeCommitResult.StaleToken ->
                        SlimReconcileResult.Stale(sid)
                    is SlimAuthoritativeCommitResult.CacheWriteFailed -> {
                        DebugLog.w(
                            tag,
                            "reconcileSession sid=$sid mode=$mode /since drain commit CacheWriteFailed: " +
                                "${commitResult.cause.message}",
                        )
                        SlimReconcileResult.Failure(sid)
                    }
                    is SlimAuthoritativeCommitResult.MergeRejected -> {
                        DebugLog.w(
                            tag,
                            "reconcileSession sid=$sid mode=$mode /since drain commit MergeRejected: " +
                                "${commitResult.reason}",
                        )
                        SlimReconcileResult.Failure(sid)
                    }
                }
            }

            is SlimDrainOutcome.Partial -> {
                // CE propagation: a non-timeout CE mid-walk must escape
                // (structured cancellation), NOT be collapsed to Failure.
                if (outcome.cause is kotlinx.coroutines.CancellationException &&
                    outcome.cause !is kotlinx.coroutines.TimeoutCancellationException
                ) {
                    throw outcome.cause
                }
                if (!repo.isCommitTokenCurrent(token)) {
                    SlimReconcileResult.Stale(sid)
                } else {
                    val marked = repo.markFailure(sid, token)
                    if (!marked) {
                        SlimReconcileResult.Stale(sid)
                    } else {
                        DebugLog.w(
                            tag,
                            "reconcileSession sid=$sid mode=$mode /since drain Partial: ${outcome.cause.message}",
                        )
                        SlimReconcileResult.Failure(sid)
                    }
                }
            }

            is SlimDrainOutcome.Degraded -> {
                // Same contract as Partial: preserve dirty, NO watermark
                // advance. CE propagation first (same as Partial).
                if (outcome.cause is kotlinx.coroutines.CancellationException &&
                    outcome.cause !is kotlinx.coroutines.TimeoutCancellationException
                ) {
                    throw outcome.cause
                }
                if (!repo.isCommitTokenCurrent(token)) {
                    SlimReconcileResult.Stale(sid)
                } else {
                    val marked = repo.markFailure(sid, token)
                    if (!marked) {
                        SlimReconcileResult.Stale(sid)
                    } else {
                        DebugLog.w(
                            tag,
                            "reconcileSession sid=$sid mode=$mode /since drain Degraded: ${outcome.cause.message}",
                        )
                        SlimReconcileResult.Failure(sid)
                    }
                }
            }
        }
    }

    // ── §5: foldRestFetch (repo port) ────────────────────────────────────

    /**
     * P4-B (moved from SSC): shared fold for the full/cursor drain path.
     *
     * §11.1 fix-6 P0-4: the full/cursor drain success path MUST construct a
     * [SlimAuthoritativeCandidate] and drive
     * [SlimAuthoritativeCommitter.commitAuthoritative] — the ONLY path that
     * may advance localApplied* / clear dirty / replace visible content.
     * Only a [SlimAuthoritativeCommitResult.Committed] result permits a
     * [SlimReconcileResult.Reconciled] (items for UI merge). All other
     * committer results map to Stale/Failure.
     *
     * §11.1 fix-8 P1-6: the candidate's [SlimAuthoritativeCandidate.messages]
     * is constructed via [mergeSlimMessageSet]`(..., complete = true)` — the
     * drain produces a phase-B-frozen complete cursor snapshot, so the
     * complete-merge contract applies. The incoming drain items are merged
     * onto the current authoritative set (read from the repo's
     * [OpenCodeRepository.captureCurrentVisibleMessages] equivalent — fetched
     * via [captureAuthoritativeMessages]) so that:
     *   - missing-from-incoming ids are RETAINED (no tombstone → no spurious
     *     deletion),
     *   - older same-id tuples in incoming do NOT overwrite newer authoritative,
     *   - equal-tuple-different-parts does NOT silently overwrite (kept for
     *     a later full/cursor reconcile).
     *
     * C-D3 v2 §1.9: Stale ≠ Failure. A stale cursor result must NOT call
     * markSlimReconcileFailure (that pollutes error state for the new
     * incarnation). Only real transport failures map to Failure.
     */
    private suspend fun foldRestFetch(
        sid: String,
        mode: SlimReconcileMode,
        result: Result<List<MessageWithParts>>,
        token: OpenCodeRepository.SlimCommitToken,
    ): SlimReconcileResult {
        val repo = repository ?: return SlimReconcileResult.NoRepository(sid)

        return result.fold(
            onSuccess = { items ->
                if (!repo.isCommitTokenCurrent(token)) return@fold SlimReconcileResult.Stale(sid)
                // §11.1 fix-8 P1-6: merge the drain items onto the current
                // authoritative set via mergeSlimMessageSet(complete = true).
                // The drain produces a complete cursor snapshot (terminal
                // page reached, no cap hit), so the complete-merge contract
                // applies. Missing-from-incoming ids are retained (no
                // tombstone), older tuples ignored, equal-tuple-different-
                // parts kept authoritative.
                val authoritative = repo.captureAuthoritativeMessages(sid)
                val mergeResult = mergeSlimMessageSetWithConflict(
                    authoritative = authoritative,
                    incoming = items,
                    complete = true,
                )
                val merged = mergeResult.messages
                // §11.1 fix-6 P0-4: construct candidate + commit atomically.
                // The drain no longer bumps the bookmark internally — the
                // watermark advance happens inside commitAuthoritative.
                // The candidate carries the MERGED messages; the watermark
                // tuple is derived from the merged set so the watermark
                // corresponds to a real message in the committed view.
                val (ts, id) = maxMessageTuple(merged)
                    ?.let { it.first to it.second } ?: (null to null)
                val candidate = SlimAuthoritativeCandidate(
                    sessionId = sid,
                    token = token,
                    messages = merged,
                    localAppliedUpdatedAt = ts,
                    localAppliedMessageId = id,
                    // §11.1 fix-10 P1-1 / rev-ogpt P1-1: thread the merge's
                    // conflict signal into the commit's atomic dirty decision.
                    // The commit's replaceLocalAppliedAndClearDirtyLocked will
                    // set dirty=true UNCONDITIONALLY when hasConflict=true —
                    // inside the SAME critical section that writes localApplied*,
                    // no separate markDirty post-write (which was a NO-OP when
                    // needsReconcile returned false on an aligned watermark,
                    // which is exactly the same-tuple-conflict case).
                    hasConflict = mergeResult.hasConflict,
                )
                when (val commitResult = repo.commitAuthoritative(candidate)) {
                    is SlimAuthoritativeCommitResult.Committed -> {
                        // §11.1 fix-10 P1-1 / rev-ogpt P1-1: the conflict's
                        // dirty decision is now ATOMIC with the commit — no
                        // separate markDirty call here. The commit's critical
                        // section already set dirty=true iff mergeResult.hasConflict
                        // (or remote > localApplied via P0-4). Removing the
                        // post-commit markDirty closes the P1-1 hole: the prior
                        // markSlimDirty was gated by needsReconcile, which
                        // returns FALSE on a same-tuple conflict (remote ==
                        // localApplied), so the dirty flag never landed.
                        SlimReconcileResult.Reconciled(sid, merged)
                    }
                    is SlimAuthoritativeCommitResult.StaleToken ->
                        SlimReconcileResult.Stale(sid)
                    is SlimAuthoritativeCommitResult.CacheWriteFailed -> {
                        DebugLog.w(
                            tag,
                            "reconcileSession sid=$sid mode=$mode commit CacheWriteFailed: " +
                                "${commitResult.cause.message}",
                        )
                        SlimReconcileResult.Failure(sid)
                    }
                    is SlimAuthoritativeCommitResult.MergeRejected -> {
                        DebugLog.w(
                            tag,
                            "reconcileSession sid=$sid mode=$mode commit MergeRejected: " +
                                "${commitResult.reason}",
                        )
                        SlimReconcileResult.Failure(sid)
                    }
                }
            },
            onFailure = { error ->
                if (repo.isStaleFailure(error) || !repo.isCommitTokenCurrent(token)) {
                    return@fold SlimReconcileResult.Stale(sid)
                }

                val marked = repo.markFailure(sid, token)
                if (!marked) {
                    SlimReconcileResult.Stale(sid)
                } else {
                    DebugLog.w(
                        tag,
                        "reconcileSession sid=$sid mode=$mode REST failed: ${error.message}",
                    )
                    SlimReconcileResult.Failure(sid)
                }
            },
        )
    }

    // ── §5 + §6.2: applyReconcileResult (repo port + store port + dispatcher) ─

    /**
     * P4-B (moved from SSC): fold a [SlimReconcileResult] into UI side
     * effects that can't live inside the repository's pure state-derive
     * layer. Does NOT execute commands (the caller — SSC — executes the
     * command BEFORE calling this; preserves current ordering: the
     * BACKGROUND launch happened inside `reconcileSessionLocked` before the
     * caller applied the result).
     *
     * Accepts result/token/mode only. The prior `snapshot:
     * ResyncUiSnapshot?` parameter (the T2 Phase-3 OUTER focus-snapshot
     * gate) was REMOVED — it was retained for ABI only and is unused (the
     * chat-merge self-gates inside [applyCurrentReconcileResult]).
     *
     * C-D3 v2 §1.10: Stale is a clean no-op. The token gate is
     * all-or-nothing — a stale token between fetch and commit → full no-op
     * + Stale.
     */
    internal suspend fun applyReconcileResult(
        result: SlimReconcileResult,
        token: OpenCodeRepository.SlimCommitToken,
        mode: SlimReconcileMode = SlimReconcileMode.DIGEST_BACKGROUND,
        expectedRouteInstance: Long = 0L,
    ): SlimReconcileResult {
        val repo = repository ?: return SlimReconcileResult.NoRepository(result.sid)

        // C-D3 v2 §1.10: Stale is a clean no-op (no slice / cache / effect
        // commit, no Failure pollution).
        if (result is SlimReconcileResult.Stale) return result

        val stillCurrent = withContext(reconcileDispatcher) {
            repo.isCommitTokenCurrent(token)
        }
        // rev-grok rule #1: the token gate is all-or-nothing. A stale /
        // superseded token (between fetch and commit) → full no-op + Stale.
        // This is the ONLY branch that returns Stale from a non-Stale input.
        if (!stillCurrent) return SlimReconcileResult.Stale(result.sid)

        val committed = withContext(Dispatchers.Main.immediate) {
            repo.commitIfTokenCurrent(token) {
                val liveSessionId = store.currentChat().currentSessionId
                applyCurrentReconcileResult(
                    result = result,
                    token = token,
                    liveSessionId = liveSessionId,
                    mode = mode,
                    expectedRouteInstance = expectedRouteInstance,
                )
            }
        }
        return if (committed) result else SlimReconcileResult.Stale(result.sid)
    }

    /**
     * P4-B: convenience overload accepting the full [attempt] (façade +
     * digest path). `token == null` is valid ONLY for NoRepository (no repo
     * to capture from); in that case the primary overload early-returns
     * NoRepository without dereferencing the token.
     */
    internal suspend fun applyReconcileResult(attempt: SlimReconcileAttempt): SlimReconcileResult {
        val token = attempt.token
            // token == null ⇒ NoRepository path — mirror the primary
            // overload's early-return (no repo to commit against).
            ?: return SlimReconcileResult.NoRepository(attempt.outcome.result.sid)
        return applyReconcileResult(
            result = attempt.outcome.result,
            token = token,
            mode = attempt.mode,
            expectedRouteInstance = attempt.routeInstance,
        )
    }

    // ── §5: applyCurrentReconcileResult (repo port + store port + SlimEffectsPort) ─

    /**
     * P4-B (moved from SSC): the current-reincarnation body. Runs inside
     * the `commitIfTokenCurrent` atomic region — every branch stays
     * synchronous (no network, no delay, no blocking IO).
     *
     * Branches:
     *  - [SlimReconcileResult.MarkDeleted] → drop from open-tabs +
     *    `SessionArchived` dispatch + `EvictSession` effect.
     *  - [SlimReconcileResult.ClearLocal] → wipe chat slice's message cache
     *    if current session (slim-only write guard) + `EvictSession` effect.
     *  - [SlimReconcileResult.Reconciled] → merge into chat if current
     *    session (authoritative decision via [isAuthoritativeSlimMerge]);
     *    otherwise write to sessionWindowCache via effect + re-ratchet dirty
     *    if the cache-retention step dropped the items.
     *  - Other variants → no extra UI work.
     */
    private fun applyCurrentReconcileResult(
        result: SlimReconcileResult,
        token: OpenCodeRepository.SlimCommitToken,
        liveSessionId: String? = store.currentChat().currentSessionId,
        mode: SlimReconcileMode = SlimReconcileMode.DIGEST_BACKGROUND,
        expectedRouteInstance: Long = 0L,
    ) {
        when (result) {
            is SlimReconcileResult.MarkDeleted -> {
                val sid = result.sid
                val currentList = store.currentSessionList()
                val directory = currentList.sessions
                    .firstOrNull { it.id == sid }
                    ?.directory
                    .orEmpty()

                // §B4: no open-tabs-list prune.
                store.dispatch(
                    AppAction.SessionArchived(
                        session = Session(
                            id = sid,
                            directory = directory,
                            time = Session.TimeInfo(archived = 1L),
                        ),
                    )
                )
                popDetailIfActive(sid)

                effects.tryEmitEffect(
                    ControllerEffect.EvictSession(currentServerGroupFp(), sid),
                )
            }

            is SlimReconcileResult.ClearLocal -> {
                val sid = result.sid

                // T1d P1-3: slim-only ClearLocal path — fail-fast in legacy
                // mode before any chat wipe (structural; normal legacy never
                // reaches ClearLocal because reconcile early-returns).
                requireSlimOnlyStateWrite(supportsWatermarkResync(), "clear-local")

                if (liveSessionId == sid) {
                    // T1b: content-only wipe (messages + partsByMessage);
                    // streaming overlay / cursor / model preserved.
                    // Use the workflow token captured before the REST work;
                    // recapturing here would let a stale A result adopt a
                    // newer A incarnation after A→B→A.
                    val routeInstance = expectedRouteInstance
                    if (routeInstance > 0L) {
                        store.dispatch(
                            AppAction.SlimChatContentClearedForRoute(
                                expectedRouteInstance = routeInstance,
                                sessionId = sid,
                            )
                        )
                    } else {
                        store.dispatch(AppAction.SlimChatContentCleared)
                    }
                }

                effects.tryEmitEffect(
                    ControllerEffect.EvictSession(currentServerGroupFp(), sid),
                )
            }

            is SlimReconcileResult.Reconciled -> {
                if (liveSessionId == result.sid) {
                    // §Stage-B §3.4 (grok MF-1): thread the authoritative
                    // decision through mergeSlimMessagesIntoChat so an
                    // actively-streaming token stream's owned parts are
                    // preserved on a skeleton merge, and cleared on an
                    // authoritative (resync / idle) merge.
                    val authoritative = isAuthoritativeSlimMerge(
                        mode = mode,
                        sid = result.sid,
                        sessionStatuses = store.currentSessionList().sessionStatuses,
                    )
                    mergeSlimMessagesIntoChat(
                        items = result.items,
                        authoritative = authoritative,
                        expectedRouteInstance = expectedRouteInstance,
                        sessionId = result.sid,
                    )
                }
                // T11 round-2 (oracle D1 — cache-coupled non-focus resync):
                // if this Reconciled was for a NON-current session, write
                // the fetched items to sessionWindowCache so a later
                // switchTo finds them without a re-fetch. For the CURRENT
                // session, items were already merged into the chat slice
                // inside foldRestFetch — no extra work.
                //
                // T11 round-3 (oracle D1 part a — retention-bound dirty):
                // the dirty clear already happened inside
                // `bumpSlimBookmarkFromItems` (via `onReconcileSuccess`)
                // during the fetch. If the cache-retention step below
                // fails (empty result / filtered-to-nothing / bus drop),
                // we RE-RATCHET dirty via forceDirty so the next
                // pass retries the fetch. Without this binding, dirty
                // could clear without a retained window — leaving the
                // user with no cached messages and no scheduled retry.
                //
                // §11.1 fix-12b P1-2 (rev-ogpt): the prior code called
                // `markDirty`, which is gated by `needsReconcile`. In
                // the normal steady state (authoritative commit just
                // landed, remote aligned, no conflict), `needsReconcile`
                // returns FALSE, so `markDirty` returned `true` but left
                // `dirty = false` — a semantic NO-OP. `forceDirty`
                // bypasses that gate, unconditionally setting `dirty`
                // (still under the SlimCommitToken guard). The other
                // `markDirty` call sites (the committer path) are
                // unaffected: their conflict decision is now atomic with
                // the commit via `SlimAuthoritativeCandidate.hasConflict`,
                // which is why this method was retained in fix-11b for
                // exactly this retention path.
                val sid = result.sid
                val isCurrent = liveSessionId == sid

                if (!isCurrent && result.items.isNotEmpty()) {
                    val messages = result.items
                        .map { it.info }
                        .filter { it.id.isNotEmpty() }

                    val partsByMessage = result.items
                        .filter {
                            it.info.id.isNotEmpty() && it.parts.isNotEmpty()
                        }
                        .associate { it.info.id to it.parts }

                    if (messages.isNotEmpty()) {
                        val retained = effects.tryEmitEffect(
                            ControllerEffect.WriteSessionWindow(
                                serverGroupFp = currentServerGroupFp(),
                                sessionId = sid,
                                messages = messages,
                                partsByMessage = partsByMessage,
                            )
                        )

                        if (!retained) {
                            DebugLog.w(tag, "applyReconcileResult sid=$sid WriteSessionWindow dropped → re-ratchet dirty (forceDirty)")
                            repository?.forceDirty(sid, token)
                        }
                    } else {
                        DebugLog.w(tag, "applyReconcileResult sid=$sid filtered-to-nothing → re-ratchet dirty (forceDirty)")
                        repository?.forceDirty(sid, token)
                    }
                } else if (!isCurrent && result.items.isEmpty()) {
                    DebugLog.d(tag, "applyReconcileResult sid=$sid empty non-focus result → re-ratchet dirty (forceDirty)")
                    repository?.forceDirty(sid, token)
                }
                // For the CURRENT session, items are already merged into
                // the chat slice (retention = chat slice itself); no
                // extra dirty work needed.
            }

            is SlimReconcileResult.RefreshRow,
            is SlimReconcileResult.Aligned,
            is SlimReconcileResult.Failure,
            is SlimReconcileResult.TimedOut,
            is SlimReconcileResult.NoRepository,
            is SlimReconcileResult.Stale -> {
                // C-D3 v2 §1.10: Stale is also caught by the entry guard;
                // reproduced here to keep the `when` exhaustive without
                // an `else`.
                // State already updated inside reconcileSession; no extra UI work.
            }
        }
    }

    // ── §5 + §7.1: mergeSlimMessagesIntoChat (store port + supportsWatermarkResync) ─

    /**
     * P4-B (moved from SSC): merge [MessageWithParts] skeletons into the
     * open chat slice by messageID (patch-if-found + insert-if-absent for
     * messages; parts map overwritten per fetched id). Routes through
     * [AppAction.SlimMessagesMerged] → `mergeSlimMessages`.
     *
     * §Stage-B §3.4: [authoritative] threads the splice/merge contract —
     * see `mergeSlimMessages` + [isAuthoritativeSlimMerge].
     *
     * P4 §7.1: SSC's cold-start snapshot path delegates here (no duplicate
     * impl); the method is therefore `internal`, not `private`.
     */
    internal fun mergeSlimMessagesIntoChat(
        items: List<MessageWithParts>,
        authoritative: Boolean = false,
        expectedRouteInstance: Long? = null,
        sessionId: String? = null,
    ) {
        if (items.isEmpty()) return
        // T1d P1-2: slim-only state write — fail-fast in legacy mode.
        requireSlimOnlyStateWrite(supportsWatermarkResync(), "merge-slim-messages")
        val targetSessionId = sessionId ?: store.currentChat().currentSessionId
        val routeInstance = expectedRouteInstance
            ?: targetSessionId?.let(store::routeInstanceFor)
            ?: 0L
        store.dispatch(
            AppAction.SlimMessagesMerged(
                items = items,
                authoritative = authoritative,
                expectedRouteInstance = routeInstance,
                sessionId = targetSessionId,
            )
        )
    }
}

// ── §5 + §8.4: isAuthoritativeSlimMerge (pure, file-level) ──────────────

/**
 * P4-B (moved from SSC, expression UNCHANGED): decide whether a slim
 * reconcile merge should be authoritative (fetched content wins, owned
 * streaming parts cleared) or skeleton (owned streaming parts preserved).
 *
 * Returns `true` (authoritative) when ANY of:
 *  - [forceAuthoritative] is set (explicit override).
 *  - [mode] is [SlimReconcileMode.RESYNC] (the resync sweep is a forced
 *    catch-up — fetched content is authoritative even if the session
 *    happens to be busy at merge time).
 *  - The session's status is unknown (`null`) — fail-safe: treat an
 *    unknown session as idle/authoritative.
 *  - The session is idle (`!isBusy && !isRetry`) — no active token
 *    stream to preserve.
 *
 * Returns `false` (skeleton / preserve) ONLY when the session is
 * actively busy or retrying (an in-flight token stream may own parts
 * whose streamed text must be preserved).
 *
 * Pinned by the §8.4 policy tests (`SlimSessionReconcilerTest`). The
 * expression is moved byte-for-byte; only the [mode] type changed from
 * SSC's `ReconcileMode` to the reconciler-internal [SlimReconcileMode]
 * (§3.1 mapping at the façade).
 */
internal fun isAuthoritativeSlimMerge(
    mode: SlimReconcileMode,
    sid: String,
    sessionStatuses: Map<String, SessionStatus>,
    forceAuthoritative: Boolean = false,
): Boolean = forceAuthoritative || mode == SlimReconcileMode.RESYNC || run {
    val st = sessionStatuses[sid]
    st == null || (!st.isBusy && !st.isRetry)
}
