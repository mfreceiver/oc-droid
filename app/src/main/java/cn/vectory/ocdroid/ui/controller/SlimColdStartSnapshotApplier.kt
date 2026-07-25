package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SlimapiPermissionEntry
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.SlimAggregationOutcome
import cn.vectory.ocdroid.data.repository.SlimColdStartSnapshot
import cn.vectory.ocdroid.data.repository.toPermissionRequest
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.util.DebugLog

// ==========================================================================
// P5 extraction: `SlimColdStartSnapshotApplier`. Owns both internal
// snapshot-application workflows (the no-token entry + the token-aware fold).
// Pure move from SSC — NO behavior change.
//
// # Merge seam (§3.2)
//
// Message merging delegates ONLY to [SlimSessionReconciler.mergeSlimMessagesIntoChat].
// NO copy of the merge / authoritative-decision logic lives in this file.
// The caller's `authoritative` Boolean is forwarded UNCHANGED (this class
// does NOT calculate authoritative mode).
//
// # Token gate (§3.2)
//
// Every state / effect / message commit runs inside ONE repository token
// commit block (`commitIfTokenCurrent`). A stale gate → [SlimSnapshotApplyResult.Rejected].
//
// See docs/ocmar/plans/2026-07-24-p5-slim-question-loader-design.md.
// ==========================================================================

// ── §2.2 Apply result (sealed) ────────────────────────────────────────────

/**
 * P5 §2.2: the snapshot fold's result. SSC's F5 wrappers map this 1:1 to
 * `Boolean` (`Applied` → true, `Rejected` → false).
 *
 *  - [Applied]: token commit block executed (incl. valid `complete=false`
 *    no-op — the gate was current, the block ran, it just retained prior
 *    state).
 *  - [Rejected]: no repository or stale token prevented the commit.
 *
 * Legacy slim-only misuse still throws [SlimOnlyStateWriteException] (NOT
 * [Rejected]) — the slim-only guard runs BEFORE the repository lookup.
 */
internal sealed interface SlimSnapshotApplyResult {
    data object Applied : SlimSnapshotApplyResult
    data object Rejected : SlimSnapshotApplyResult
}

// ── §2.2 SlimColdStartSnapshotApplier ─────────────────────────────────────

/**
 * P5 §2.2: owns both internal snapshot-application workflows; slim-only
 * guards; optional fresh token capture; atomic token commit gate; session
 * merge by fetched directory; question/permission aggregation folds/signals;
 * `complete=false` retention; discovery readiness rules; failure UI events;
 * message delegation to P4 reconciler. Owns **no mutable state**.
 *
 * # Symbol resolution (preemptive scoping)
 *
 * Every original SSC symbol reference resolves via:
 *  - (a) an injected port ([repository] / [store] / [effects]) or lambda
 *    ([supportsWatermarkResync]).
 *  - (b) the injected P4 [reconciler] (merge seam — one-way child dep).
 *  - (c) shared same-package helpers (`allSessionsById`,
 *    `filterArchivedSessionQuestions`, `applyAggregationOutcome`,
 *    `requireSlimOnlyStateWrite`, `SlimapiQuestionEntry::toQuestionRequest`).
 *  - (d) shared types (`DebugLog`, `R`, `UiEvent`, `Session`).
 * NO SSC reference/callback held. NO `CoroutineScope` held.
 */
internal class SlimColdStartSnapshotApplier(
    private val repository: SlimReconcileRepositoryPort?,
    private val store: SlimReconcileStorePort,
    private val effects: SlimEffectsPort,
    private val reconciler: SlimSessionReconciler,
    private val supportsWatermarkResync: () -> Boolean,
) {
    /**
     * P5 (moved from SSC first overload): slim-only guard BEFORE repository
     * lookup + token capture. Captures a fresh token and delegates to the
     * token-aware overload.
     *
     * Returns [Applied] if the snapshot landed (or was a valid no-op for
     * null pieces); [Rejected] if no repository or stale token prevented
     * the commit.
     */
    internal fun apply(snapshot: SlimColdStartSnapshot): SlimSnapshotApplyResult {
        // T1d P1-1: fail-fast before any repo / token / fold work when not slim.
        requireSlimOnlyStateWrite(supportsWatermarkResync(), "cold-start-snapshot")
        val repo = repository ?: return SlimSnapshotApplyResult.Rejected

        // C-D3 v2 §3.6: token-gated snapshot fold. The token is captured
        // at this entry; if the caller already has a workflow token
        // (the resync worker), it routes through the token-taking overload
        // below.
        val sessionId = store.currentChat().currentSessionId
        val routeInstance = sessionId?.let(store::routeInstanceFor) ?: 0L
        return apply(
            snapshot = snapshot,
            token = repo.captureCommitToken(),
            expectedRouteInstance = routeInstance,
        )
    }

    /**
     * P5 (moved from SSC token-aware overload): token-aware cold-start
     * snapshot fold.
     *
     * Returns [Rejected] when the gate rejects (caller MUST abort the
     * surrounding workflow — e.g. the resync worker returns emptyMap).
     * Returns [Applied] when the snapshot landed (or was a no-op for null
     * pieces / `complete=false`).
     *
     * All slice + effect commits run inside ONE [commitIfTokenCurrent]
     * atomic region so a configure() rotation between cold-start fetch
     * return and slice commit cannot write a stale snapshot under a new
     * host.
     *
     * §Stage-B §3.4: [authoritative] controls the messages-merge contract
     * — default `false` (cold-start skeleton: preserve any in-flight token-
     * stream owned parts). Resync / watchdog callers pass `true` (the
     * snapshot's messages are the authoritative final view). This class
     * forwards [authoritative] UNCHANGED to the P4 merge seam.
     */
    internal fun apply(
        snapshot: SlimColdStartSnapshot,
        token: OpenCodeRepository.SlimCommitToken,
        authoritative: Boolean = false,
        expectedRouteInstance: Long? = null,
    ): SlimSnapshotApplyResult {
        // T1d P1-1: same entry guard on the token-taking overload (direct
        // callers / tests must not bypass via token path).
        requireSlimOnlyStateWrite(supportsWatermarkResync(), "cold-start-snapshot")
        val repo = repository ?: return SlimSnapshotApplyResult.Rejected
        val sessionId = store.currentChat().currentSessionId
        val routeInstance = expectedRouteInstance
            ?: sessionId?.let(store::routeInstanceFor)
            ?: 0L

        val committed = repo.commitIfTokenCurrent(token) {
            DebugLog.i(
                "Sync",
                "applySlimColdStartSnapshot sessions=${snapshot.sessions?.size ?: "null"} " +
                    "questions=${snapshot.questions::class.simpleName} " +
                    "permissions=${snapshot.permissions::class.simpleName} " +
                    "messages=${snapshot.messages?.size ?: "null"} " +
                    "complete=${snapshot.complete} discoveryDirectories=${snapshot.discoveryDirectories} " +
                    "discoveryReady=${snapshot.discoveryReady}",
            )

            // O-C weak-network §1: when the server marks the snapshot as
            // incomplete (X-Complete: false), the sidecar couldn't assemble the
            // full snapshot on a flaky/lossy network. In that case, DO NOT
            // full-replace (or even merge) — keep the existing page entirely
            // to avoid wiping the user's current view with a partial snapshot.
            if (snapshot.complete == false) {
                DebugLog.w(
                    "Sync",
                    "applySlimColdStartSnapshot snapshot.complete=false — keeping prior page intact",
                )
                return@commitIfTokenCurrent
            }

            val sessions = snapshot.sessions
            if (sessions != null) {
                // rev-F: if discoveryReady == false, treat empty/null sessions as
                // "not ready" — do NOT authority-empty wipe the session list.
                val isDiscoveryReady = snapshot.discoveryReady ?: true
                if (isDiscoveryReady || sessions.isNotEmpty()) {
                    // C-D3 v2 §3.6 (sessions-merge fix): fold the fetched
                    // sessions with MERGE semantics, mirroring the retain-prior
                    // pattern used by `applyAggregationOutcome`'s Success branch.
                    //
                    // Root cause this guards against: the slim cold-start fetch
                    // (coldStartSlimSync / SSE first-frame) does NOT pass a
                    // session limit, and the sidecar defaults to returning only
                    // the most recent 100 sessions. A FULL REPLACE here would
                    // therefore substitute the prior list (e.g. 374 sessions
                    // accumulated across directories) with just those 100 —
                    // making the entire session list "vanish" on cold start /
                    // SSE reconnect. The merge below restricts the fetched
                    // payload to overwriting ONLY the directories it actually
                    // covers; sessions in every other directory survive.
                    //
                    // `directory` is non-null on Session (data class), so the
                    // `mapNotNull` / null-directory defensive filters are a no-op
                    // today; left in place to stay correct if the field ever
                    // becomes nullable.
                    val byDirectory = sessions.groupBy { it.directory }
                    val fetchedDirs = sessions.mapNotNull { it.directory }.toSet()
                    store.mutateSessionList { s ->
                        if (fetchedDirs.isEmpty()) {
                            // Defensive: fetched sessions carry no directory
                            // (legacy / malformed payload) → fall back to the
                            // historical FULL REPLACE so we don't silently pin
                            // the entire list to stale entries.
                            s.copy(
                                sessions = sessions,
                                directorySessions = byDirectory,
                            )
                        } else {
                            val priorKept = s.sessions.filter { it.directory == null || it.directory !in fetchedDirs }
                            val mergedSessions = (priorKept + sessions).distinctBy { it.id }
                            val mergedByDir = s.directorySessions.filterKeys { it !in fetchedDirs } + byDirectory
                            s.copy(
                                sessions = mergedSessions,
                                directorySessions = mergedByDir,
                            )
                        }
                    }
                }
                // If isDiscoveryReady=false && sessions.isNotEmpty(), the merge
                // above still applies (non-empty sessions are merged normally).
                // The !isDiscoveryReady guard only prevents emptiness-triggered wipe.
            }

            // I-2: questions + permissions use typed aggregation outcome.
            store.mutateSessionList { s ->
                val sessionsById = allSessionsById(s.sessions, s.directorySessions, s.childSessions)

                val questionFold = applyAggregationOutcome(
                    prior = s.pendingQuestions,
                    outcome = snapshot.questions,
                    wireToUi = SlimapiQuestionEntry::toQuestionRequest,
                    uiId = QuestionRequest::id,
                    uiDirectory = QuestionRequest::directory,
                )

                val permissionFold = applyAggregationOutcome(
                    prior = s.pendingPermissions,
                    outcome = snapshot.permissions,
                    wireToUi = SlimapiPermissionEntry::toPermissionRequest,
                    uiId = PermissionRequest::id,
                    uiDirectory = PermissionRequest::directory,
                )

                s.copy(
                    pendingQuestions = filterArchivedSessionQuestions(
                        questionFold.items,
                        sessionsById,
                    ),
                    pendingPermissions = permissionFold.items,
                    questionAggregationSignal = questionFold.signal,
                    permissionAggregationSignal = permissionFold.signal,
                )
            }

            val msgs = snapshot.messages
            if (msgs != null) {
                // P5 §3.2: delegate to the P4 reconciler (no duplicate impl).
                // Forward caller's `authoritative` UNCHANGED — this class does
                // NOT calculate authoritative mode.
                reconciler.mergeSlimMessagesIntoChat(
                    items = msgs,
                    authoritative = authoritative,
                    expectedRouteInstance = routeInstance,
                    sessionId = sessionId,
                )
            }

            // I-2: a whole-call Failure surfaces a toast. Partial
            // incompleteness is observable via the signal — no toast on
            // every resync.
            if (snapshot.questions is SlimAggregationOutcome.Failure) {
                effects.tryEmitUiEvent(
                    UiEvent.Error(R.string.error_slim_questions_fetch_failed)
                )
            }
            if (snapshot.permissions is SlimAggregationOutcome.Failure) {
                effects.tryEmitUiEvent(
                    UiEvent.Error(R.string.error_slim_permissions_fetch_failed)
                )
            }
        }
        return if (committed) SlimSnapshotApplyResult.Applied else SlimSnapshotApplyResult.Rejected
    }
}
