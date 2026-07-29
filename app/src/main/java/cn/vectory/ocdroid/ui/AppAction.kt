package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.chat.ExpandPartsOutcome

data class BundleStamp(
    val generation: Long,
    val endpointFp: String,
)

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
sealed interface AppAction {
    /**
     * materializeDraftSession success path: a freshly-created [session] is
     * wired into sessionList (upsert), chat.currentSessionId is set, the new
     * session is dropped from unread + its lastViewedTime is bumped to
     * [viewedAt], and composer.draftWorkdir is cleared.
     *
     * §B4: no open-tabs-list — list-detail has no tab strip; navigation to
     * the new session is the caller's job (navigateToChat / switchTo).
     *
     * Carries:
     *  - [session]: Session from `repository.createSession` (upserted).
     *  - [viewedAt]: wall-clock for lastViewedTime + pending-create stamp.
     */
    data class DraftSessionMaterialized(
        val session: Session,
        val viewedAt: Long,
    ) : AppAction

    /**
     * session.updated archived SSE branch (cross-client archive): upsert the
     * archived [session] via applyArchiveEviction, and IFF the archived
     * [session].id IS the currently-open chat session, clear
     * chat.currentSessionId + messages + partsByMessage (applyArchivedChatClear).
     * The "clear chat" decision is derived inside [reduce] from the snapshot
     * (NOT carried as a boolean) so the action stays pure data.
     *
     * §B4: no open-tabs-list — route-driven pop is the caller's job when the
     * archived id matches the active chat/{id} route.
     *
     * Carries:
     *  - [session]: the archived Session (full record — the reducer upserts it
     *    so the authoritative copy reflects the archived flag).
     */
    data class SessionArchived(
        val session: Session,
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
     * (`clearRecentWorkdirs` / `currentWorkdir` / `sessionCache`) and the
     * effect-bus emissions (`EvictGroup` / `ForceReconnect` /
     * `HostProfileSwitched`) stay at the call site — they are side-effects,
     * not state. The reducer only touches SharedStateStore slices.
     * §B4: open-tabs-list no longer exists; host switch forces popToSessions
     * at the call site.
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
     * default arg), by
     * [cn.vectory.ocdroid.ui.controller.SessionSwitcher.requestLatestScroll]
     * for the same-session "snap to latest on send / Chat-tab reselect" path
     * (which deliberately bypasses switchTo's same-session no-op guard), AND
     * by [cn.vectory.ocdroid.ui.ChatViewModel.requestScrollRestore] when the
     * parent route entry's ChatScaffold LaunchedEffect consumes a sub-agent
     * checkpoint from its SavedStateHandle (§chat-list-detail §11 / G6 B5 —
     * the parent re-entry path, behavior=Restore).
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

    // §chat-list-detail §11 / G6 (B5): the legacy per-child checkpoint
    // stored/consumed actions are REMOVED. Checkpoints now live on the
    // parent route entry's SavedStateHandle (keyed by childId via
    // [cn.vectory.ocdroid.ui.checkpointKeyForChild]); the consume side is
    // [cn.vectory.ocdroid.ui.consumeAnySubAgentCheckpoint]. The
    // [PendingScrollRequest] slot above remains the single scroll-intent
    // channel; the parent entry's ChatScaffold LaunchedEffect dispatches
    // [ScrollRequested] with behavior=Restore when it consumes a checkpoint.

    /**
     * FIX-A/C (archive-sync, review-blocker): atomic bulk-refresh commit.
     * Writes the merged session list AND — if the current session is among
     * the archived — clears chat via [applyArchivedChatClear] + does
     * unread/pending-question subtree cleanup (mirrors [SessionArchived]).
     * All in one committed aggregate state so no collector ever observes the
     * torn intermediate "sessions[current].isArchived == true AND
     * chat.currentSessionId == current".
     *
     * §B4: no open-tabs-list prune. Route-driven pop (when route id is
     * archived) is the caller's job; refresh itself does not invent a new
     * route / auto-select.
     *
     * Carries:
     *  - [sessions]: the full merged refresh result (authoritative server
     *    records plus any still-pending local-only records preserved by Q4).
     *  - [hasMoreSessions]: the pagination flag.
     *  - [confirmedServerIds]: ids from the raw REST response before merge;
     *    this is the only source allowed to confirm pending creates.
     *  - [sweepNow]: caller-captured wall-clock time used with
     *    [SessionListState.pendingCreatedAt] for the 30 s timeout.
     */
    data class BulkSessionsRefreshed(
        val sessions: List<Session>,
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
        val expectedRouteInstance: Long = 0L,
        val bundleStamp: BundleStamp? = null,
    ) : AppAction

    /**
     * T1b two-phase leading edge — fullText (SSC:1397). REPLACE into
     * streamingPartTexts + streamingReasoningPart + partsByMessage placeholder
     * + pendingFlushPartIds. A null [bundleStamp] is rejected by the reducer;
     * callers still schedule [scheduleDeltaFlush] after a successful dispatch.
     */
    data class PartFullTextReceived(
        val partId: String,
        val fullText: String,
        val partType: String,
        val messageId: String,
        val sessionId: String,
        val expectedRouteInstance: Long = 0L,
        val bundleStamp: BundleStamp? = null,
    ) : AppAction

    /**
     * T1b two-phase leading edge — delta (SSC:1436 / :1539). APPEND into
     * streamingPartTexts + streamingReasoningPart + partsByMessage placeholder
     * + pendingFlushPartIds. A null [bundleStamp] is rejected by the reducer.
     * Uses the 5-arg [applyPartDeltaLeadingEdge].
     */
    data class PartDeltaReceived(
        val partId: String,
        val delta: String,
        val partType: String,
        val messageId: String,
        val sessionId: String,
        val expectedRouteInstance: Long = 0L,
        val bundleStamp: BundleStamp? = null,
    ) : AppAction

    /** T1b trailing coalesce fullText REPLACE (SSC:1421). */
    data class FullTextBuffered(val partId: String, val text: String) : AppAction

    /** T1b trailing coalesce delta APPEND (SSC:1459 / :1552). */
    data class DeltaBuffered(val partId: String, val delta: String) : AppAction

    /**
     * T1b flush buffered delta/fullText into streamingPartTexts then clear the
     * 3 coalesce entries for [partId] (SSC:1850).
     *
     * [expectedRouteInstance] is the §7.2 freshness token captured at flush
     * time. The reducer also requires the live bundle stamp to match.
     * [sessionId] is the owning session, used by
     * the route-content CAS alongside the token.
     */
    data class CoalesceFlushedForPart(
        val partId: String,
        val expectedRouteInstance: Long = 0L,
        val sessionId: String? = null,
        val bundleStamp: BundleStamp,
    ) : AppAction

    /**
     * T1b drop [partId]'s buffers WITHOUT flushing (SSC:1864). Overlay preserved.
     */
    data class CoalesceClearedForPart(val partId: String) : AppAction

    /**
     * T1b clear ALL coalesce buffers (SSC:1877 clearDeltaBuffers). Does NOT
     * clear streamingPartTexts / streamingReasoningPart.
     */
    data object CoalesceBuffersCleared : AppAction

    /**
     * Clear token-stream ownership state for specified partIds.
     *
     * [expectedRouteInstance] is the §7.2 freshness token: the reducer accepts
     * IFF the live incarnation and bundle stamp match.
     * [sessionId] is the owning session, used by the route-content CAS
     * alongside the token.
     */
    data class ClearTokenStreamState(
        val partIds: Set<String>,
        val expectedRouteInstance: Long = 0L,
        val sessionId: String? = null,
        val bundleStamp: BundleStamp,
    ) : AppAction

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
        val expectedRouteInstance: Long = 0L,
        /** Session owning this asynchronous stream update, when known. */
        val sessionId: String? = null,
        val bundleStamp: BundleStamp,
    ) : AppAction

    // ── T1b: conversation-path ownership (messages + partsByMessage) ───────

    /**
     * T1b message.updated patch-if-found / insert-if-absent (SSC:1270).
     * Reducer: [applyMessageUpdated]. Found flag for DebugLog / cache-append
     * side-effects stays at the call site (computed from prior snapshot).
     */
    data class MessageUpdatedApplied(
        val message: Message,
        val expectedRouteInstance: Long = 0L,
        /** Session owning this asynchronous SSE update, when known. */
        val sessionId: String? = null,
    ) : AppAction

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
        /** Non-zero only when the merge belongs to the active route. */
        val expectedRouteInstance: Long = 0L,
        val sessionId: String? = null,
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
        /** Non-zero only for the route-owned load-more path. */
        val expectedRouteInstance: Long = 0L,
        val sessionId: String? = null,
    ) : AppAction

    /**
     * T1b AppCore:631 VerifyAndHydrate cached-window inject (4 fields).
     */
    data class ChatWindowHydrated(
        val messages: List<Message>,
        val partsByMessage: Map<String, List<Part>>,
        val olderMessagesCursor: String?,
        val hasMoreMessages: Boolean,
        /** Non-zero only for route-owned cache hydration. */
        val expectedRouteInstance: Long = 0L,
        val sessionId: String? = null,
    ) : AppAction

    /**
     * T1b SessionSwitcher.switchTo compound chat clear (15 field writes in
     * ONE dispatch). Composer stays on a separate mutateComposer at the
     * call site.
     */
    data class SessionSelected(
        val sessionId: String,
        val pendingScrollRequest: PendingScrollRequest,
        /** Route token already minted by navigateToChat, when route-aware. */
        val routeInstance: Long? = null,
    ) : AppAction

    /**
     * T1b ClearLocal arm (SSC:2794). Clears messages + partsByMessage ONLY
     * (streaming overlay / cursor / model preserved).
     */
    data object SlimChatContentCleared : AppAction

    /** Route-owned counterpart of the legacy [SlimChatContentCleared] action. */
    data class SlimChatContentClearedForRoute(
        val expectedRouteInstance: Long,
        val sessionId: String,
    ) : AppAction

    /**
     * B-P0-2 (MAJOR 4): a single message was deleted upstream and the
     * R2 /full reconcile confirmed it via HTTP 404 (OR the token stream
     * delivered a `message.removed` frame). The reducer evicts the
     * message from `messages` + `partsByMessage`; the per-message
     * watermark entry was already removed by
     * the in-memory slim state under the slim commit token guard (the
     * onMessageGone wiring drives BOTH the watermark removal AND this dispatch).
     *
     * The [cn.vectory.ocdroid.data.repository.maxMessageTuple] cache is
     * NOT a separate structure — it is derived on demand from
     * `messages` (the merger scans the list). Evicting the message
     * here drops its tuple automatically on the next derivation.
     *
     * `sessionId` is informational (the eviction is by `messageId`);
     * it is retained for diagnostic logging + future per-session
     * accounting.
     *
     * §Stage-B C5 (CRITICAL): superseded by [MessageRemovedConfirmed],
     * which carries the §7.2 route token + bundle stamp required for the
     * freeze protocol (route-owned transcript + LoadedContent dual
     * projection MUST be guarded by route token + bundle stamp; an
     * async `/full` 404 / token `message.removed` MUST NOT mutate
     * transcript state when there is no active route). The legacy call
     * site (ControllerModule.onMessageGone) is migrated by a parallel
     * lane; the reducer here retains source compatibility and ALSO
     * clears the streaming overlay (matches the new contract).
     */
    @Deprecated(
        "Use MessageRemovedConfirmed (carries route token + bundle stamp per the freeze protocol).",
        replaceWith = ReplaceWith(
            "MessageRemovedConfirmed(sessionId, messageId, expectedRouteInstance = 0L, bundleStamp = bundleStamp)",
        ),
    )
    data class MessageRemovedFromFull(
        val sessionId: String,
        val messageId: String,
    ) : AppAction

    /**
     * §Stage-B C5 (CRITICAL): `/full` 200 Reconciled merge for a SINGLE
     * message — non-authoritative (token-stream-owned STREAMING parts
     * are preserved; the server skeleton text="" is dropped). The
     * reducer applies [ChatState.mergeSlimMessages] with
     * `authoritative=false` over a singleton list AND keeps the flat +
     * [LoadedContent] projections consistent in the same reducer pass
     * (the freeze protocol: every transcript mutation runs in one
     * committed aggregate state).
     *
     * [expectedRouteInstance] + [bundleStamp] are captured at the
     * request trigger and threaded UNCHANGED across the entire fetch —
     * the reducer CAS-rejects any dispatch whose token / bundle stamp
     * no longer matches the live incarnation.
     */
    data class SlimFullMessageReconciled(
        val sessionId: String,
        val message: MessageWithParts,
        val expectedRouteInstance: Long,
        val bundleStamp: BundleStamp,
    ) : AppAction

    /**
     * §Stage-B C5 (CRITICAL): source-agnostic removal confirmation —
     * either an R2 `/full` HTTP 404 for [messageId] OR a token stream
     * `message.removed` frame. The reducer evicts the message from
     * `messages` + `partsByMessage` AND clears ALL streaming-overlay
     * state owned by that message's parts (streamOwned /
     * streamingPartTexts / deltaBuffer / fullTextBuffer /
     * pendingFlushPartIds / streamingReasoningPart) so a late straggler
     * frame cannot resurrect ghost text. The eviction runs in ONE
     * committed aggregate state across BOTH the flat projection AND
     * [LoadedContent] (the freeze protocol's dual-projection invariant).
     *
     * [expectedRouteInstance] + [bundleStamp] are captured at the
     * trigger and threaded UNCHANGED. A dispatch with
     * `expectedRouteInstance == 0L` (no active route) is REJECTED —
     * per the freeze protocol, async `/full` 404 / token removal MUST
     * NOT write route-owned transcript state when there is no active
     * route (only watermark/repository state may be cleaned, and that
     * cleanup lives outside this reducer).
     */
    data class MessageRemovedConfirmed(
        val sessionId: String,
        val messageId: String,
        val expectedRouteInstance: Long,
        val bundleStamp: BundleStamp,
    ) : AppAction

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
    data class LastAssistantErrorAttached(
        val error: Message.MessageError,
        val expectedRouteInstance: Long = 0L,
        val sessionId: String? = null,
    ) : AppAction

    /**
     * T1b residual: CatchUpActions probe-page merge. 4-field set only
     * (messages + partsByMessage + isLoadingMessages=false + staleNotice=false).
     * Distinct from [MessagesMerged] (8 fields incl. streaming/cursor/model).
     */
    data class CatchUpMessagesMerged(
        val messages: List<Message>,
        val partsByMessage: Map<String, List<Part>>,
        /** Non-zero only for the route-owned catch-up path. */
        val expectedRouteInstance: Long = 0L,
        val sessionId: String? = null,
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
        val expectedRouteInstance: Long = 0L,
    ) : AppAction

    // ── T1c: sessionList ownership (sessions + co-written fields) ─

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

    // §B4: OpenTabsChanged(removed) removed with open-tabs-list (D9).

    /**
     * T1c: REST archive/restore of a single session id (one loop iteration of
     * launchSetSessionArchived). Map-replaces [session] into sessions /
     * directorySessions / childSessions by id; stores caller-computed
     * [pendingQuestions]; subtracts [activeSessionIdsToRemove] from
     * activeSessionIds. Cross-slice mutateUnread / mutateChat / ChatCleared
     * stay at the call site. §B4: no open-tabs-list field.
     */
    data class SessionArchivedLocal(
        val session: Session,
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
     * T1c: launchLoadSessions NON-archive success path. sessionList copy.
     * Distinct from [BulkSessionsRefreshed] (does NOT intersect
     * activeSessionIds / archive-subtree cleanup).
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

    // ── §chat-list-detail §12 B0: atomic SelectConversation / CloseDetail /
    //    DetailMissing (scaffolding — inert until B0.5/B1 wire dispatch).

    /**
     * §chat-list-detail §6 D10 / §12 B0: atomic select of session
     * [sessionId] under route incarnation [routeInstance]. The single-
     * dispatch replacement for the pre-refactor closeSession / switchTo
     * scattered-commit sequence — the reducer commits the new incarnation
     * atomically so no collector ever observes a torn "currentSessionId
     * flipped but messages still from the prior session" intermediate.
     *
     * B0 scaffolding: this action EXISTS in the sealed hierarchy and the
     * [reduce] dispatch, but NO existing flow dispatches it yet (B0.5/B1
     * wire the call sites). [routeInstance] is the §7.2 freshness token
     * minted by [cn.vectory.ocdroid.ui.OrchestratorViewModel.navigateToChat]
     * at navigation time; the reducer stamps it onto
     * [StoreState.chatRouteInstance].
     */
    data class SelectConversation(
        val sessionId: String,
        val routeInstance: Long,
    ) : AppAction

    /**
     * §chat-list-detail §6 D10 / §12 B0: close the detail pane (return to
     * the session list). The atomic counterpart to [SelectConversation]:
     * the reducer tears down the active route incarnation in a single
     * dispatch so a late content load (carrying the prior incarnation's
     * token) is rejected by the §7.2 freshness CAS.
     *
     * B0 scaffolding — not yet dispatched by any flow.
     */
    data object CloseDetail : AppAction

    /**
     * §chat-list-detail §5 P4 / §12 B0: the requested [sessionId] is gone
     * (deleted / archived / never existed / ill-formed). The reducer
     * advances [StoreState.chatRouteInstance] to [routeInstance] so any
     * in-flight load for the missing session is dropped by the freshness
     * CAS, and (B2+) the render gate shows the Missing placeholder rather
     * than another session's transcript.
     *
     * B0 scaffolding — not yet dispatched by any flow.
     */
    data class DetailMissing(
        val sessionId: String,
        val routeInstance: Long,
    ) : AppAction

    /**
     * §chat-list-detail §7.1/§7.2 B0.5-rework: commit loaded chat content for
     * the chat/{id} render path with the §7.2 freshness CAS. Carries the
     * COMPLETE computed payload (enough to populate BOTH [LoadedContent] AND
     * the flat mirror in one atomic reducer pass). The reducer accepts IFF
     * [expectedRouteInstance] == the live [StoreState.chatRouteInstance] AND
     * [sessionId] == [ChatState.currentSessionId] — a stale load (the A→B→A
     * race) loses the CAS and is silently dropped (P6). On accept, the reducer
     * commits LoadedContent + the flat fields atomically (no torn intermediate).
     *
     * Replaces [MessagesMerged] for the route-aware path (the load pipeline
     * dispatches this when `expectedRouteInstance > 0`); MessagesMerged still
     * serves the legacy bare-chat path. The token [expectedRouteInstance] is
     * captured at load-START (in [ControllerEffect.VerifyAndHydrate]) and
     * threaded UNCHANGED across the entire REST suspension — it guards the
     * ENTIRE completion transaction (content commit, isLoadingMessages clear,
     * error emission, cache write, auto-expand), not just the content CAS.
     */
    data class ChatContentLoaded(
        val sessionId: String,
        val expectedRouteInstance: Long,
        val messages: List<Message> = emptyList(),
        val partsByMessage: Map<String, List<Part>> = emptyMap(),
        val streamingPartTexts: Map<String, String> = emptyMap(),
        val streamingReasoningPart: Part? = null,
        val olderMessagesCursor: String? = null,
        val hasMoreMessages: Boolean = false,
        val currentModel: Message.ModelInfo? = null,
        val authoritative: Boolean = false,
        /**
         * L3 (slimapi-v2 §D): optional host/endpoint CAS stamp captured at
         * reload-launch time. When non-null, [acceptsBundle] rejects the
         * dispatch if the live bundle generation/endpoint no longer matches
         * (host switch mid-flight). Null preserves legacy behavior (the
         * pre-L3 call sites that do not capture a stamp are accepted
         * unconditionally — backward compatible).
         */
        val bundleStamp: BundleStamp? = null,
    ) : AppAction

    /** §P11: published when ClientBundle changes — StoreState stamp update (atomic CAS). */
    data class BundlePublished(
        val generation: Long,
        val endpointFp: String,
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
internal fun reduce(
    state: StoreState,
    action: AppAction,
): StoreState {
    if (!action.acceptsBundle(state)) return state
    return when (action) {
    is AppAction.BundlePublished -> {
        when {
            action.generation < state.liveBundleGeneration -> state
            action.generation == state.liveBundleGeneration &&
                state.liveEndpointFp.isNotEmpty() &&
                action.endpointFp != state.liveEndpointFp -> state
            else -> state.copy(
                liveBundleGeneration = action.generation,
                liveEndpointFp = action.endpointFp,
            )
        }
    }
    is AppAction.DraftSessionMaterialized -> reduceDraftSessionMaterialized(state, action)
    is AppAction.SessionArchived -> reduceSessionArchived(state, action)
    is AppAction.HostStatePurged -> reduceHostStatePurged(state, action)
    is AppAction.WorkdirDraftStarted -> reduceWorkdirDraftStarted(state, action)
    is AppAction.ScrollRequested -> reduceScrollRequested(state, action)
    is AppAction.ScrollConsumed -> reduceScrollConsumed(state, action)
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
    is AppAction.SlimChatContentClearedForRoute -> reduceSlimChatContentClearedForRoute(state, action)
    is AppAction.MessageRemovedFromFull -> reduceMessageRemovedFromFull(state, action)
    is AppAction.SlimFullMessageReconciled -> reduceSlimFullMessageReconciled(state, action)
    is AppAction.MessageRemovedConfirmed -> reduceMessageRemovedConfirmed(state, action)
    is AppAction.ChatCleared -> reduceChatCleared(state, action)
    is AppAction.LastAssistantErrorAttached -> reduceLastAssistantErrorAttached(state, action)
    is AppAction.CatchUpMessagesMerged -> reduceCatchUpMessagesMerged(state, action)
    is AppAction.ColdStartChatReset -> reduceColdStartChatReset(state, action)
    is AppAction.ExpandedPartsContentCommitted -> reduceExpandedPartsContentCommitted(state, action)
    is AppAction.SessionUpserted -> reduceSessionUpserted(state, action)
    is AppAction.SessionCreatedLocal -> reduceSessionCreatedLocal(state, action)
    is AppAction.SessionArchivedLocal -> reduceSessionArchivedLocal(state, action)
    is AppAction.SessionDeletedLocal -> reduceSessionDeletedLocal(state, action)
    is AppAction.SessionStatusPatched -> reduceSessionStatusPatched(state, action)
    is AppAction.SessionsRefreshedLocal -> reduceSessionsRefreshedLocal(state, action)
    is AppAction.SessionsPageAppended -> reduceSessionsPageAppended(state, action)
    is AppAction.SessionTreeHydrated -> reduceSessionTreeHydrated(state, action)
    is AppAction.SelectConversation -> reduceSelectConversation(state, action)
    is AppAction.CloseDetail -> reduceCloseDetail(state, action)
    is AppAction.DetailMissing -> reduceDetailMissing(state, action)
    is AppAction.ChatContentLoaded -> reduceChatContentLoaded(state, action)
    }
}

private fun AppAction.acceptsBundle(state: StoreState): Boolean {
    // L3 (slimapi-v2 §D): ChatContentLoaded carries an OPTIONAL bundle stamp.
    // null = legacy caller → accept unconditionally (backward compat); non-null
    // → require the live bundle generation/endpoint to match (host CAS).
    if (this is AppAction.ChatContentLoaded) {
        val expected = bundleStamp ?: return true
        return state.liveBundleGeneration == expected.generation &&
            state.liveEndpointFp == expected.endpointFp
    }
    val expected = when (this) {
        is AppAction.PartPlaceholderEnsured -> bundleStamp
        is AppAction.CoalesceFlushedForPart -> bundleStamp
        is AppAction.ClearTokenStreamState -> bundleStamp
        is AppAction.TokenStreamPartUpdated -> bundleStamp
        is AppAction.PartFullTextReceived -> bundleStamp
        is AppAction.PartDeltaReceived -> bundleStamp
        is AppAction.SlimFullMessageReconciled -> bundleStamp
        is AppAction.MessageRemovedConfirmed -> bundleStamp
        else -> return true
    } ?: return false
    return state.liveBundleGeneration == expected.generation &&
        state.liveEndpointFp == expected.endpointFp
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
    // §chat-list-detail §7.1 B0.5: clear the LoadedContent slot alongside
    // the flat fields — a host-purge / draft-create must NOT leave stale
    // content for the chat/{id} render path. Additive (new field).
    content = null,
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
    // §Wave5b-Q13: clear the unified scroll slot (replaces the prior
    // `pendingJumpToLatest = null` clear). The slot references a target
    // session id that is being cleared.
    // §chat-list-detail §11 / G6 (B5): the legacy per-child checkpoint
    // backstack clear is GONE — checkpoints now live on per-route-entry
    // SavedStateHandle, so a draft-create / host-purge does not need to
    // (and CANNOT) sweep them from ChatState. The entries die with their
    // owning route entry when it pops (host-switch / leave-to-Sessions).
    pendingScrollRequest = null,
    // PRESERVED (chrome, NOT per-session — kept via .copy() above):
    // isCompacting, compactStartedAt, refreshNonce.
)
