package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.chat.ExpandPartsOutcome

/**
 * §A5-3 Phase B2: a pure-data sealed hierarchy describing the cross-slice
 * state transitions the app performs atomically. Each variant replaces a
 * pre-B2 site that scattered N `mutateXxx` / `writeXxx` calls across
 * [SharedStateStore]; that scattering produced N intermediate committed
 * aggregate states per logical transition (visible to any concurrent
 * `stateFlow` collector as torn reads — e.g. `sessionList` archived-but-
 * `chat.currentSessionId` still pointing at the archived id).
 *
 * The variants carry ONLY pure data — no transform lambdas, no `clearChat`
 * booleans derivable from the snapshot. The reducer ([reduce]) decides from
 * the current [StoreState] which fields to clear (e.g. the archived session
 * clears chat IFF it is the current one — derived inside [reduce], not
 * carried on the action).
 *
 * Behavior-preservation contract (the B2 gate): the field changes each
 * variant produces in [reduce] MUST match — field-for-field, no more no
 * less — the multi-`mutateXxx` sequence the corresponding pre-B2 site
 * performed. The existing site tests (AppCoreOrchestrationTest /
 * SessionSyncCoordinatorTest / HostProfileControllerTest) are the
 * behavior-equivalence proof; they MUST stay GREEN unchanged.
 *
 * What is NOT here (intentionally, oracle ruling): the network calls,
 * `settingsManager.*` writes, `persistSessionCache` / cache-eviction, and
 * effect-bus emissions (`EvictSession` / `EvictGroup` / `ForceReconnect` /
 * `HostProfileSwitched`) that surround each migrated site are NOT part of
 * the reducer. They stay at the call site (they are side-effects, not
 * state) and run AROUND the single [SharedStateStore.dispatch] that
 * commits the action. Likewise the intentional pre-network
 * `writeComposer { draftWorkdir = null }` in materializeDraftSession + its
 * failure-restoration writeComposer stay separate (oracle: those are
 * intentional, not part of the success-path atomic commit).
 */
internal sealed interface AppAction {
    /**
     * materializeDraftSession success path: a freshly-created [session] is
     * wired into sessionList (upsert + openSessionIds), chat.currentSessionId
     * is set, the new session is dropped from unread + its lastViewedTime is
     * bumped to [viewedAt], and composer.draftWorkdir is cleared.
     *
     * Carries exactly the data the reducer needs:
     *  - [session]: the Session returned by `repository.createSession` (the
     *    reducer upserts it into sessionList.sessions).
     *  - [openSessionIds]: the NEW full open-tabs list (caller computes
     *    `(settingsManager.openSessionIds.filterNot { it == session.id }
     *    + session.id).takeLast(8)` — the new tab joins from the RIGHT;
     *    the reducer just stores it — persistence is the caller's job).
     *  - [viewedAt]: `System.currentTimeMillis()` captured at the call site
     *    (the reducer writes both `unread.lastViewedTime` and the independent
     *    pending-create registration timestamp from this same clock value).
     */
    data class DraftSessionMaterialized(
        val session: Session,
        val openSessionIds: List<String>,
        val viewedAt: Long,
    ) : AppAction

    /**
     * session.updated archived SSE branch (cross-client archive): upsert the
     * archived [session] + replace openSessionIds (applyArchiveEviction), and
     * IFF the archived [session].id IS the currently-open chat session, clear
     * chat.currentSessionId + messages + partsByMessage (applyArchivedChatClear).
     * The "clear chat" decision is derived inside [reduce] from the snapshot
     * (NOT carried as a boolean) so the action stays pure data.
     *
     * Carries:
     *  - [session]: the archived Session (full record — the reducer upserts it
     *    so the authoritative copy reflects the archived flag).
     *  - [openSessionIds]: the NEW open-tabs list with this id filtered out
     *    (caller computes + persists; reducer just stores it).
     */
    data class SessionArchived(
        val session: Session,
        val openSessionIds: List<String>,
    ) : AppAction

    /**
     * purgePerHostState (host switch / delete-active-host): the cross-slice
     * purge. The [preserveServerGroupData] flag matches the call-site
     * `sameGroup = previousFp == targetFp` decision — when true (same-group
     * switch) sessions / unread / session-window cache are PRESERVED (server-
     * identical data); when false (异组 switch / delete active host) the full
     * reset runs. Per-profile UX (composer.draftWorkdir /
     * settings.availableCommands / connection.serverVersion) is ALWAYS reset.
     *
     * What is NOT here (oracle): the SettingsManager writes
     * (`clearRecentWorkdirs` / `currentWorkdir` / `openSessionIds` /
     * `sessionCache`) and the effect-bus emissions (`EvictGroup` /
     * `ForceReconnect` / `HostProfileSwitched`) stay at the call site — they
     * are side-effects, not state. The reducer only touches SharedStateStore
     * slices.
     *
     * The reducer PRESERVES the three ChatState-only fields documented at
     * HostProfileController.kt:475-479 (isCompacting, compactStartedAt,
     * refreshNonce) — it uses `.copy()` on the existing ChatState, never a
     * fresh `ChatState()`.
     */
    data class HostStatePurged(
        val preserveServerGroupData: Boolean,
    ) : AppAction

    /**
     * createSessionInWorkdirForEffect ("new session in workdir X" draft
     * entry): clears chat (currentSessionId / messages / partsByMessage /
     * streaming overlays / currentModel — the prior session's inferred model
     * must NOT leak into the draft picker), clears sessionList.sessionTodos,
     * and resets composer (inputText / imageAttachments / fileReferences
     * cleared, draftWorkdir set to [workdir]).
     *
     * Carries only the [workdir] string — every other field is a constant
     * reset, so no need to parameterize.
     */
    data class WorkdirDraftStarted(
        val workdir: String,
    ) : AppAction

    /**
     * §Wave5b-Q13: the unified replacement for the pre-Wave5b
     * `PendingJumpToLatestSet`. Writes a fresh [PendingScrollRequest] to
     * `chat.pendingScrollRequest` UNCONDITIONALLY (a newer request always
     * supersedes any prior one — single-slot semantics, see
     * [PendingScrollRequest] kdoc).
     *
     * Issued by [cn.vectory.ocdroid.ui.controller.SessionSwitcher.switchTo]
     * inside the SAME mutateChat that flips currentSessionId (Latest for the
     * default arg, Restore(checkpoint) for returnToParent), AND by
     * [cn.vectory.ocdroid.ui.controller.SessionSwitcher.requestLatestScroll]
     * for the same-session "snap to latest on send / Chat-tab reselect" path
     * (which deliberately bypasses switchTo's same-session no-op guard).
     *
     * Single-slice / single-field write. Kept as a dispatched [AppAction]
     * (rather than a raw `mutateChat`) per the WT2 plan lineage so the intent
     * transition is observable on the aggregate stateFlow and unit-testable
     * via [AppActionReducerTest].
     */
    data class ScrollRequested(
        val requestId: Long,
        val targetSessionId: String,
        val behavior: ScrollBehavior,
    ) : AppAction

    /**
     * §Wave5b-Q13: COMPARE-AND-CLEAR of [PendingScrollRequest]. The reducer
     * clears `chat.pendingScrollRequest` IFF the live slot's requestId matches
     * [requestId]; otherwise it is a no-op.
     *
     * Why compare-and-clear (oracle ruling): in a fast A→B→C cascade, A's
     * consumer might finish LAST (effect relaunch ordering, page-slot
     * disposal timing). If A's `ScrollConsumed(A.requestId)` unconditionally
     * cleared the slot, it would wipe C's newer intent and leave C at the
     * default position. The id compare guarantees only the CURRENT intent is
     * clearable — a stale consumer's clear is silently dropped.
     */
    data class ScrollConsumed(
        val requestId: Long,
    ) : AppAction

    /**
     * §Wave5b-Q13: store the parent's [ScrollCheckpoint] under the child id
     * key. Issued by [cn.vectory.ocdroid.ui.SessionViewModel.openSubAgent]
     * BEFORE the inner selectSession(childId) so the entry is on file by the
     * time currentSessionId flips. Single-field write
     * (`chat.parentReturnCheckpoints + (childId to checkpoint)`).
     */
    data class ParentCheckpointStored(
        val childId: String,
        val checkpoint: ScrollCheckpoint,
    ) : AppAction

    /**
     * §Wave5b-Q13: remove the [childId] entry from
     * `chat.parentReturnCheckpoints`. Issued by
     * [cn.vectory.ocdroid.ui.SessionViewModel.returnToParent] in the SAME
     * sequence as the inner `switchTo(parentId, Restore(checkpoint))` so the
     * entry is cleaned even if the user re-opens the child later (no stale
     * checkpoint leak). The reducer removes only the matching key (no-op if
     * absent — defensive against a double-consume).
     */
    data class ParentCheckpointConsumed(
        val childId: String,
    ) : AppAction

    /**
     * FIX-A/C (archive-sync, review-blocker): atomic bulk-refresh commit.
     * Replaces the torn two-step (`mutateSessionList` then
     * `onCurrentSessionArchived` → separate dispatch) with a SINGLE dispatch
     * that writes the merged session list AND prunes [openSessionIds] of EVERY
     * archived id (FIX-A — not just current) AND — if the current session is
     * among the archived — clears chat via [applyArchivedChatClear] + does
     * unread/pending-question subtree cleanup (mirrors [SessionArchived]'s
     * cleanup for the current session). All in one committed aggregate state
     * so no collector ever observes the torn intermediate
     * "sessions[current].isArchived == true AND chat.currentSessionId == current".
     *
     * Non-current archived ids: the [openSessionIds] prune alone is sufficient
     * (SSE parity — the SSE path's observable effect for non-current archived
     * sessions is the openIds prune; the per-session unread/questions cleanup
     * there is defensive and the bulk path's prune closes the ghost-tab hole).
     *
     * Carries:
     *  - [sessions]: the full merged refresh result (authoritative server
     *    records plus any still-pending local-only records preserved by Q4).
     *  - [openSessionIds]: the NEW open-tabs list with ALL archived ids pruned
     *    (caller computes + persists; reducer just stores it).
     *  - [hasMoreSessions]: the pagination flag (mirrors the mutateSessionList
     *    field the non-archive path writes).
     *  - [confirmedServerIds]: ids from the raw REST response before merge;
     *    this is the only source allowed to confirm pending creates.
     *  - [sweepNow]: caller-captured wall-clock time used with
     *    [SessionListState.pendingCreatedAt] for the 30 s timeout.
     */
    data class BulkSessionsRefreshed(
        val sessions: List<Session>,
        val openSessionIds: List<String>,
        val hasMoreSessions: Boolean,
        /** Ids from the raw REST response, before pending-local preservation. */
        val confirmedServerIds: Set<String>,
        /** Wall-clock time captured by the REST success path for timeout sweep. */
        val sweepNow: Long,
    ) : AppAction

    /**
     * T1a: toggle (or set) a collapsible card's expand state under [key].
     * Mirrors the pre-T1a `mutateExpandedParts { it + (key to expanded) }`
     * write 1:1 — including `expanded = false`, which SETS the key to false
     * rather than removing it (`minus(key)` is intentionally NOT used).
     */
    data class PartExpansionToggled(val key: String, val expanded: Boolean) : AppAction

    /**
     * T1a: clear all collapsible-card expand state (session switch / explicit
     * reset). Mirrors the pre-T1a `mutateExpandedParts { emptyMap() }` write 1:1.
     */
    data object ExpandedPartsCleared : AppAction

    // ── T1b: streaming-path ownership (streamingPartTexts family) ───────────

    /**
     * T1b two-phase placeholder phase 1 (SSC:1362). Injects a typed Part into
     * partsByMessage + sets streamingReasoningPart when type is reasoning.
     * Does NOT write streamingPartTexts (phase 2 does that).
     */
    data class PartPlaceholderEnsured(
        val partType: String,
        val partId: String,
        val messageId: String,
        val sessionId: String,
    ) : AppAction

    /**
     * T1b two-phase leading edge — fullText (SSC:1397). REPLACE into
     * streamingPartTexts + streamingReasoningPart + partsByMessage placeholder
     * + pendingFlushPartIds. Caller still schedules [scheduleDeltaFlush].
     */
    data class PartFullTextReceived(
        val partId: String,
        val fullText: String,
        val partType: String,
        val messageId: String,
        val sessionId: String,
    ) : AppAction

    /**
     * T1b two-phase leading edge — delta (SSC:1436 / :1539). APPEND into
     * streamingPartTexts + streamingReasoningPart + partsByMessage placeholder
     * + pendingFlushPartIds. Uses the 5-arg [applyPartDeltaLeadingEdge].
     */
    data class PartDeltaReceived(
        val partId: String,
        val delta: String,
        val partType: String,
        val messageId: String,
        val sessionId: String,
    ) : AppAction

    /** T1b trailing coalesce fullText REPLACE (SSC:1421). */
    data class FullTextBuffered(val partId: String, val text: String) : AppAction

    /** T1b trailing coalesce delta APPEND (SSC:1459 / :1552). */
    data class DeltaBuffered(val partId: String, val delta: String) : AppAction

    /**
     * T1b flush buffered delta/fullText into streamingPartTexts then clear the
     * 3 coalesce entries for [partId] (SSC:1850).
     */
    data class CoalesceFlushedForPart(val partId: String) : AppAction

    /**
     * T1b drop [partId]'s buffers WITHOUT flushing (SSC:1864). Overlay preserved.
     */
    data class CoalesceClearedForPart(val partId: String) : AppAction

    /**
     * T1b clear ALL coalesce buffers (SSC:1877 clearDeltaBuffers). Does NOT
     * clear streamingPartTexts / streamingReasoningPart.
     */
    data object CoalesceBuffersCleared : AppAction

    /** Clear token-stream ownership state for specified partIds. */
    data class ClearTokenStreamState(val partIds: Set<String>) : AppAction

    /**
     * §Stage-D1 §3.8 / §5.8: bridge from the [cn.vectory.ocdroid.data.repository.TokenStreamReducer]
     * working state into [ChatState.streamingPartTexts] + [ChatState.streamOwned].
     * Emitted by [cn.vectory.ocdroid.ui.controller.sse.TokenStreamCoordinator] for each
     * snapshot/delta frame after the pure reducer folds it, so the UI's overlay
     * (the single-owner guard + Stage-A clear) reflects the live token buffer.
     *
     *  - [state] == [StreamOwnedState.STREAMING]: the part is animating; its
     *    text is the live token buffer.
     *  - [state] == [StreamOwnedState.DONE]: the part finalized; its text is
     *    the terminal snapshot. The entry persists in `streamingPartTexts`
     *    until a subsequent [ClearTokenStreamState] / authoritative reload
     *    clears it (matches the existing legacy streaming-overlay lifecycle).
     *
     * Replaces into the maps (NOT append) — the coordinator already joined the
     * reducer's accumulated text before dispatching, so the value carried here
     * IS the authoritative accumulated buffer for [partId].
     */
    data class TokenStreamPartUpdated(
        val partId: String,
        val text: String,
        val state: StreamOwnedState,
    ) : AppAction

    // ── T1b: conversation-path ownership (messages + partsByMessage) ───────

    /**
     * T1b message.updated patch-if-found / insert-if-absent (SSC:1270).
     * Reducer: [applyMessageUpdated]. Found flag for DebugLog / cache-append
     * side-effects stays at the call site (computed from prior snapshot).
     */
    data class MessageUpdatedApplied(val message: Message) : AppAction

    /**
     * T1b slim reconcile merge (SSC:3307 mergeSlimMessagesIntoChat).
     * Reducer: [mergeSlimMessages] — patch-or-append message + replace parts.
     *
     * §Stage-B §3.4: [authoritative] controls the splice/merge contract.
     * - `false` (default, skeleton/non-authoritative): token-stream-owned
     *   STREAMING parts are PRESERVED (their streamed text in
     *   streamingPartTexts is the live source of truth; the server skeleton
     *   text="" is dropped). This is the safe default for cold-start /
     *   periodic merges where an in-flight token stream may own animated
     *   parts.
     * - `true` (resync / watchdog / forced): the fetched items are the
     *   authoritative final view — owned STREAMING parts are substituted by
     *   the fetched content and their ownership state is cleared.
     */
    data class SlimMessagesMerged(
        val items: List<MessageWithParts>,
        val authoritative: Boolean = false,
    ) : AppAction

    /**
     * T1b MessageActions:351 full field-set merge. Writes 8 fields in ONE
     * dispatch (messages + partsByMessage + isLoadingMessages=false +
     * streamingPartTexts + streamingReasoningPart + olderMessagesCursor +
     * hasMoreMessages + currentModel). isLoadingMessages is unconditionally
     * false (not carried on the action).
     *
     * §Stage-B §3.10 (grok S3 / bgpt SF-2): [authoritative] controls the
     * streamOwned overlay-clear contract. The overlay-clear decision itself
     * (streamingPartTexts) is computed at the call site (MessageActions
     * derives `newStreamingTexts` from resetLimit + streamingFinalized +
     * overlayFinalized); this flag mirrors that decision for streamOwned:
     * when `true`, the reducer clears streamOwned entries for the fetched
     * part ids (the loaded content is authoritative). When `false`
     * (skeleton / streaming-preserving load), streamOwned is preserved.
     * Default `false` is additive — existing call sites that don't pass it
     * preserve streamOwned (byte-for-byte legacy parity when streamOwned
     * is empty).
     */
    data class MessagesMerged(
        val messages: List<Message>,
        val partsByMessage: Map<String, List<Part>>,
        val streamingPartTexts: Map<String, String>,
        val streamingReasoningPart: Part?,
        val olderMessagesCursor: String?,
        val hasMoreMessages: Boolean,
        val currentModel: Message.ModelInfo?,
        val authoritative: Boolean = false,
    ) : AppAction

    /**
     * T1b MessageActions:552 loadMore prepend. isLoadingMoreMessages is
     * unconditionally false (not carried on the action).
     */
    data class MessagesPrepended(
        val messages: List<Message>,
        val partsByMessage: Map<String, List<Part>>,
        val olderMessagesCursor: String?,
        val hasMoreMessages: Boolean,
    ) : AppAction

    /**
     * T1b AppCore:631 VerifyAndHydrate cached-window inject (4 fields).
     */
    data class ChatWindowHydrated(
        val messages: List<Message>,
        val partsByMessage: Map<String, List<Part>>,
        val olderMessagesCursor: String?,
        val hasMoreMessages: Boolean,
    ) : AppAction

    /**
     * T1b SessionSwitcher.switchTo compound chat clear (15 field writes in
     * ONE dispatch). Composer stays on a separate mutateComposer at the
     * call site.
     */
    data class SessionSelected(
        val sessionId: String,
        val pendingScrollRequest: PendingScrollRequest,
    ) : AppAction

    /**
     * T1b ClearLocal arm (SSC:2794). Clears messages + partsByMessage ONLY
     * (streaming overlay / cursor / model preserved).
     */
    data object SlimChatContentCleared : AppAction

    // ── T1b residual: bypass write sites on §2.3 target fields ─────────────

    /**
     * T1b residual: 3-field chat clear used by SessionListActions /
     * SessionMutationActions / SessionViewModel (close / archive / empty-tabs).
     * Distinct from [SlimChatContentCleared] (preserves currentSessionId) and
     * [HostStatePurged] (clears streaming / cursor / model too).
     */
    data object ChatCleared : AppAction

    /**
     * T1b residual: SSC session.error SSE — attach [error] to the LAST
     * assistant message. No-op when no assistant exists or it already has an
     * error (byte-for-byte with the pre-residual mutateChat at SSC:1706-1712).
     */
    data class LastAssistantErrorAttached(val error: Message.MessageError) : AppAction

    /**
     * T1b residual: CatchUpActions probe-page merge. 4-field set only
     * (messages + partsByMessage + isLoadingMessages=false + staleNotice=false).
     * Distinct from [MessagesMerged] (8 fields incl. streaming/cursor/model).
     */
    data class CatchUpMessagesMerged(
        val messages: List<Message>,
        val partsByMessage: Map<String, List<Part>>,
    ) : AppAction

    // ── T1b writeChat-bypass: last two target-field writeChat sites ────────

    /**
     * T1b writeChat-bypass: AppCoreOrchestration.performGlobalColdStartRefresh
     * 8-field chat reset (streaming + content + cursor + loadMore flag).
     * Does NOT clear currentSessionId / currentModel / pending* /
     * partExpandStates / isLoadingMessages. refreshNonce++ stays a separate
     * writeChat at the call site (non-target).
     */
    data object ColdStartChatReset : AppAction

    /**
     * T1b ExpandedParts CAS fix (Strategy 2): expandParts completion commit.
     * Carries the raw [outcome] + captured [local] (not pre-merged maps).
     * [reduce] runs [ChatState.reconcileExpandedPartsContent] against the
     * **latest** chat inside `state.update` CAS — concurrent SSE updates to
     * unrelated owners are preserved. fp guard stays at the call site;
     * session guard is inside the pure reconcile.
     */
    data class ExpandedPartsContentCommitted(
        val outcome: ExpandPartsOutcome,
        val local: List<MessageWithParts>,
        val expectedSessionId: String,
    ) : AppAction

    // ── T1c: sessionList ownership (sessions + openSessionIds + co-written fields) ─

    /**
     * T1c: simple session upsert (sessions only). Covers fork / rename / child
     * upsert / switchTo target upsert / revert / question-dir-resolve / SSE
     * session.created/updated (non-archived). Reducer delegates to [upsertSession].
     */
    data class SessionUpserted(val session: Session) : AppAction

    /**
     * T1c: REST create success (SessionMutationActions launchCreateSession).
     * Writes sessions + pendingCreateIds + pendingCreatedAt in ONE dispatch.
     * Distinct from [DraftSessionMaterialized] (does NOT touch chat/unread).
     */
    data class SessionCreatedLocal(
        val session: Session,
        val registeredAt: Long,
    ) : AppAction

    /**
     * T1c: openSessionIds-only write (closeSession / switchTo open-tab append).
     * Caller computes the full list; reducer just stores it.
     */
    data class OpenSessionIdsChanged(val openSessionIds: List<String>) : AppAction

    /**
     * T1c: REST archive/restore of a single session id (one loop iteration of
     * launchSetSessionArchived). Map-replaces [session] into sessions /
     * directorySessions / childSessions by id; stores caller-computed
     * [openSessionIds] / [pendingQuestions]; subtracts
     * [activeSessionIdsToRemove] from activeSessionIds. Cross-slice
     * mutateUnread / mutateChat / ChatCleared stay at the call site.
     */
    data class SessionArchivedLocal(
        val session: Session,
        val openSessionIds: List<String>,
        val pendingQuestions: List<QuestionRequest>,
        val activeSessionIdsToRemove: Set<String>,
    ) : AppAction

    /**
     * T1c: REST delete success — purge the deleted subtree. Reducer derives
     * all 5 filter fields from [removedIds].
     */
    data class SessionDeletedLocal(
        val removedIds: Set<String>,
    ) : AppAction

    /**
     * T1c: launchSendMessage onSuccess optimistic busy write. Reducer
     * delegates sessions to [bumpSessionUpdated].
     */
    data class SessionStatusPatched(
        val sessionId: String,
        val updatedTimestamp: Long,
        val status: SessionStatus,
    ) : AppAction

    /**
     * T1c: launchLoadSessions NON-archive success path. 9-field sessionList
     * copy. Distinct from [BulkSessionsRefreshed] (does NOT overwrite
     * openSessionIds / intersect activeSessionIds / archive-subtree cleanup).
     */
    data class SessionsRefreshedLocal(
        val sessions: List<Session>,
        val hasMoreSessions: Boolean,
        val pendingCreateIds: Set<String>,
        val pendingCreatedAt: Map<String, Long>,
    ) : AppAction

    /**
     * T1c: launchLoadMoreSessions success path. 8-field copy (includes
     * loadedSessionLimit; does NOT touch isRefreshingSessions /
     * hasCompletedInitialLoad).
     */
    data class SessionsPageAppended(
        val sessions: List<Session>,
        val loadedSessionLimit: Int,
        val hasMoreSessions: Boolean,
        val pendingCreateIds: Set<String>,
        val pendingCreatedAt: Map<String, Long>,
    ) : AppAction

    /**
     * T1c: SessionTreeHydrator.request commit. Epoch-guarded: if live
     * completenessEpoch != [epochAtStart] → full no-op. Else merges
     * [childSessionsDelta] / [completeRootIdsDelta] and replaces
     * sessionStatuses.
     */
    data class SessionTreeHydrated(
        val epochAtStart: Long,
        val childSessionsDelta: Map<String, List<Session>>,
        val completeRootIdsDelta: Set<String>,
        val sessionStatuses: Map<String, SessionStatus>,
    ) : AppAction
}

/**
 * §A5-3 Phase B2: the PURE reducer that turns a [StoreState] + [AppAction]
 * into a new [StoreState]. No effects, no settings writes, no network, no
 * emit — it ONLY returns a new [StoreState]. The single committed aggregate
 * is then written atomically by [SharedStateStore.dispatch] via ONE
 * `state.update { reduce(it, action) }` (one CAS, one emission), which is
 * the atomicity mechanism — concurrent `stateFlow` collectors observe a
 * single transition with no torn intermediate states.
 *
 * Field-change parity: each branch replicates the EXACT field writes the
 * corresponding pre-B2 site did. See the per-variant kdocs on [AppAction]
 * for the mapping + the call sites (AppCoreOrchestration.kt /
 * SessionSyncCoordinator.kt / HostProfileController.kt).
 */
internal fun reduce(state: StoreState, action: AppAction): StoreState = when (action) {
    is AppAction.DraftSessionMaterialized -> reduceDraftSessionMaterialized(state, action)
    is AppAction.SessionArchived -> reduceSessionArchived(state, action)
    is AppAction.HostStatePurged -> reduceHostStatePurged(state, action)
    is AppAction.WorkdirDraftStarted -> reduceWorkdirDraftStarted(state, action)
    is AppAction.ScrollRequested -> reduceScrollRequested(state, action)
    is AppAction.ScrollConsumed -> reduceScrollConsumed(state, action)
    is AppAction.ParentCheckpointStored -> reduceParentCheckpointStored(state, action)
    is AppAction.ParentCheckpointConsumed -> reduceParentCheckpointConsumed(state, action)
    is AppAction.BulkSessionsRefreshed -> reduceBulkSessionsRefreshed(state, action)
    is AppAction.PartExpansionToggled -> reducePartExpansionToggled(state, action)
    is AppAction.ExpandedPartsCleared -> reduceExpandedPartsCleared(state, action)
    is AppAction.PartPlaceholderEnsured -> reducePartPlaceholderEnsured(state, action)
    is AppAction.PartFullTextReceived -> reducePartFullTextReceived(state, action)
    is AppAction.PartDeltaReceived -> reducePartDeltaReceived(state, action)
    is AppAction.FullTextBuffered -> reduceFullTextBuffered(state, action)
    is AppAction.DeltaBuffered -> reduceDeltaBuffered(state, action)
    is AppAction.CoalesceFlushedForPart -> reduceCoalesceFlushedForPart(state, action)
    is AppAction.CoalesceClearedForPart -> reduceCoalesceClearedForPart(state, action)
    is AppAction.CoalesceBuffersCleared -> reduceCoalesceBuffersCleared(state, action)
    is AppAction.ClearTokenStreamState -> reduceClearTokenStreamState(state, action)
    is AppAction.TokenStreamPartUpdated -> reduceTokenStreamPartUpdated(state, action)
    is AppAction.MessageUpdatedApplied -> reduceMessageUpdatedApplied(state, action)
    is AppAction.SlimMessagesMerged -> reduceSlimMessagesMerged(state, action)
    is AppAction.MessagesMerged -> reduceMessagesMerged(state, action)
    is AppAction.MessagesPrepended -> reduceMessagesPrepended(state, action)
    is AppAction.ChatWindowHydrated -> reduceChatWindowHydrated(state, action)
    is AppAction.SessionSelected -> reduceSessionSelected(state, action)
    is AppAction.SlimChatContentCleared -> reduceSlimChatContentCleared(state, action)
    is AppAction.ChatCleared -> reduceChatCleared(state, action)
    is AppAction.LastAssistantErrorAttached -> reduceLastAssistantErrorAttached(state, action)
    is AppAction.CatchUpMessagesMerged -> reduceCatchUpMessagesMerged(state, action)
    is AppAction.ColdStartChatReset -> reduceColdStartChatReset(state, action)
    is AppAction.ExpandedPartsContentCommitted -> reduceExpandedPartsContentCommitted(state, action)
    is AppAction.SessionUpserted -> reduceSessionUpserted(state, action)
    is AppAction.SessionCreatedLocal -> reduceSessionCreatedLocal(state, action)
    is AppAction.OpenSessionIdsChanged -> reduceOpenSessionIdsChanged(state, action)
    is AppAction.SessionArchivedLocal -> reduceSessionArchivedLocal(state, action)
    is AppAction.SessionDeletedLocal -> reduceSessionDeletedLocal(state, action)
    is AppAction.SessionStatusPatched -> reduceSessionStatusPatched(state, action)
    is AppAction.SessionsRefreshedLocal -> reduceSessionsRefreshedLocal(state, action)
    is AppAction.SessionsPageAppended -> reduceSessionsPageAppended(state, action)
    is AppAction.SessionTreeHydrated -> reduceSessionTreeHydrated(state, action)
}

/**
 * §fix-leak-window (release-gate fix B): reset ALL per-session [ChatState]
 * fields, preserving only the 3 chrome fields (isCompacting /
 * compactStartedAt / refreshNonce — NOT per-session; they survive a host
 * purge / draft reset and are documented at HostProfileController.kt:475-479).
 *
 * Closes the cross-host / draft leak window: pre-B2
 * `purgePerHostState` (cross-group) + `createSessionInWorkdir*` left
 * `currentModel` / `olderMessagesCursor` / `hasMoreMessages`
 * / `staleNotice` / `revertCutoffs` / `deltaBuffer` / `fullTextBuffer` /
 * `pendingFlushPartIds` stale — verified via
 * `git show e190cce^:app/src/main/java/cn/vectory/ocdroid/ui/controller/HostProfileController.kt`
 * (purgePerHostState) and
 * `git show e190cce^:app/src/main/java/cn/vectory/ocdroid/ui/AppCoreOrchestration.kt`
 * (createSessionInWorkdirForEffect): NEITHER cleared these fields. Fix B is
 * therefore a deliberate IMPROVEMENT, not a missed regression — a stale
 * model / cursor from the prior host or session no longer bleeds
 * into the new view. Uses `.copy()` so the 3 chrome fields are preserved.
 */
internal fun ChatState.clearSessionData(): ChatState = copy(
    currentSessionId = null,
    messages = emptyList(),
    revertCutoffs = emptyMap(),
    partsByMessage = emptyMap(),
    streamingPartTexts = emptyMap(),
    streamOwned = emptyMap(),
    streamingReasoningPart = null,
    // §slimapi-client-v1 §G6 (Task 16 round-2): clear per-part expand states
    // on transcript clear (host purge, archive, draft materialize).
    partExpandStates = emptyMap(),
    olderMessagesCursor = null,
    hasMoreMessages = false,
    isLoadingMessages = false,
    isLoadingMoreMessages = false,
    staleNotice = false,
    currentModel = null,
    deltaBuffer = emptyMap(),
    fullTextBuffer = emptyMap(),
    pendingFlushPartIds = emptySet(),
    // §chat-ux-batch T7 (B2): clear the TRANSIENT pending picks too — they
    // are per-session by contract ("no cross-session carry"). Without this, a
    // pending agent/model picked in session A would leak into the new draft
    // (or into a freshly-purged host view), defeating the pending contract.
    pendingAgent = null,
    pendingModel = null,
    // §Wave5b-Q13: clear the unified scroll slot + the parent-return
    // backstack (replaces the prior `pendingJumpToLatest = null` clear).
    // The slot references a target session id that is being cleared; the
    // backstack is per-session navigation context that has no meaning after
    // a draft-create or cross-host purge.
    pendingScrollRequest = null,
    parentReturnCheckpoints = emptyMap(),
    // PRESERVED (chrome, NOT per-session — kept via .copy() above):
    // isCompacting, compactStartedAt, refreshNonce.
)
