package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part

/**
 * Token-stream ownership state for streaming parts.
 */
enum class StreamOwnedState { STREAMING, DONE }

/**
 * §Stage-B §3.10 (opus SF-1): returns `true` when ANY part is currently
 * owned by an active token stream (streamOwned contains a STREAMING entry).
 * Used by the legacy SSE handler's single-owner guard to early-return when
 * a token stream owns the animated parts (prevents the legacy dual-write
 * from clobbering the token stream's live overlay).
 */
internal fun ChatState.hasActiveTokenStreamOwner(): Boolean =
    streamOwned.values.any { it == StreamOwnedState.STREAMING }

/**
 * §P0-E(b): queued durable error awaiting re-attach. messageAssistantId is the
 * last-assistant id captured at queue time when available (null when no assistant
 * existed yet). Used to avoid attaching a stale error to a newer assistant (B2/M5).
 *
 * §P0-E NARROWED: producer scaffolding only. The drain/consumer that would ATTACH
 * this to an assistant is DEFERRED to a post-P0-A task — it needs the GET/controller
 * wiring + the authority status writer to safely locate the errored assistant (B2:
 * session.error carries no messageId). Do NOT attach from a pure reducer without
 * that wiring.
 */
data class PendingChatError(
    val error: Message.MessageError,
    val routeInstance: Long,
    val messageAssistantId: String?,
)

/**
 * §P0-E(b): maximum size of [ChatState.pendingErrorReattach]. LRU eviction
 * by insertion order (LinkedHashMap iteration order = insertion order).
 */
internal const val PENDING_ERROR_REATTACH_MAX = 32

/**
 * §R-17 batch2: chat-domain state slice (RFC §2.2). Authoritative storage via
 * _chatFlow.update. The highest-frequency domain (SSE streaming deltas mutate
 * streamingPartTexts/messages many times per second). §R-17 batch2: error/success
 * events migrated to SharedFlow<UiEvent>.
 */
data class ChatState(
    val currentSessionId: String? = null,
    /**
     * §chat-list-detail §7.1 B0.5: the structural owner of the loaded chat
     * content for the route-driven chat/{id} render path. Nullable — null
     * means "no content committed (or cleared by navigation/close)". The
     * render guard is `content?.sessionId == routeId && content?.routeInstance
     * == chatRouteInstance` (P1 structural + P6 temporal). Construction is
     * atomic (sessionId welded to messages via [LoadedContent]'s data-class
     * ctor), so a torn "messages belong to X but content thinks Y" is
     * structurally unconstructable.
     *
     * B0.5 coexistence: the old flat fields ([messages] / [partsByMessage] /
     * etc.) remain the authority for the OLD bare-chat render path
     * (currentSessionId != null). This slot is the authority for the NEW
     * chat/{id} path only. B2 collapses them (the flat fields migrate INTO
     * [LoadedContent] and [currentSessionId] drops to a data-pointer).
     */
    val content: LoadedContent? = null,
    val messages: List<Message> = emptyList(),
    val revertCutoffs: Map<String, cn.vectory.ocdroid.data.model.RevertCutoff> = emptyMap(),
    val partsByMessage: Map<String, List<Part>> = emptyMap(),
    val streamingPartTexts: Map<String, String> = emptyMap(),
    /**
     * Token-stream ownership state per partId. Tracks whether a part is
     * currently being streamed (STREAMING) or has completed (DONE).
     * Cleared by [ClearTokenStreamState] action.
     */
    val streamOwned: Map<String, StreamOwnedState> = emptyMap(),
    /**
     * §slimapi-client-v1 §G6 (Task 16): per-part expand state for the
     * "展开省略内容" affordance on skeleton parts. Layered alongside
     * [streamingPartTexts] in the same chat slice — both are high-frequency
     * during SSE streaming, but only this one is mutated by user taps.
     *
     * Keyed by [PartKey]`(messageId, partId)`. States transition via the
     * expand action: Idle → Loading (tap) → Loaded | Failed (usecase outcome).
     * Terminal states persist across unrelated chat mutations — only an
     * explicit re-tap may change them.
     *
     * IMPORTANT: this map is SEPARATE from the legacy `expandedParts` /
     * `onToggleExpand` mechanism (tool-call folds, reasoning cards, sub-agent
     * cards, patch accordions). The two coexist deliberately.
     */
    val partExpandStates: Map<cn.vectory.ocdroid.ui.chat.PartKey, cn.vectory.ocdroid.ui.chat.PartExpandState> = emptyMap(),
    val streamingReasoningPart: Part? = null,
    val olderMessagesCursor: String? = null,
    // §F3-load-more: 默认 false，与 olderMessagesCursor=null 对称——避免任何
    // 路径在 cursor 缺失时仍显示"加载更多"按钮（点击会因 cursor=null 无反应）。
    val hasMoreMessages: Boolean = false,
    val isLoadingMessages: Boolean = false,
    /**
     * §history-load-fix: independent loading flag for USER-initiated "load more
     * history" ([launchLoadMoreMessages]). Decoupled from [isLoadingMessages]
     * (background reloads / catch-up) so a background load in flight NO LONGER
     * silently drops the user's "load more" click — the 0.6.0 "加载历史对话需要
     * 多次点击" regression (all three load paths shared [isLoadingMessages] as
     * a guard, so a catch-up holding it ~500ms swallowed the click). The actual
     * list mutation is serialized per-session via [MessageLoadCoordinator], so
     * a concurrent loadMore-prepend and loadMessages-replace cannot tear the
     * list. The load-more spinner binds to THIS flag (not [isLoadingMessages]),
     * so a background reload shows the clickable text while only a user
     * loadMore shows the spinner.
     */
    val isLoadingMoreMessages: Boolean = false,
    val staleNotice: Boolean = false,
    /**
     * §model-selection (V1-per-prompt): the model currently bound to the
     * active session for **display + compact**. Surfaced in the chat top-bar
     * context menu + the model picker dialog, and read by
     * [cn.vectory.ocdroid.ui.ChatViewModel.compactSession] for the compact
     * request body.
     *
     * §chat-ux-batch T8 (B3): this field is KEPT (not deleted) because
     * `compactSession` is a live reader. After T7, the per-send authority is
     * the TRANSIENT [pendingModel] (resolved `pending ?: infer ?: null` at
     * send). The per-session-storage reseed that used to feed this field
     * (legacy `SettingsManager.getModelForSession`) was deleted; the field is
     * now sourced purely from `inferCurrentModel(messages)` at load
     * ([cn.vectory.ocdroid.ui.MessageActions.launchLoadMessages]). The picker
     * feedback path runs through `pendingModel` (ComposerViewModel), so this
     * field is the load-time + compact-time mirror only.
     */
     val currentModel: Message.ModelInfo? = null,
     /**
      * §compact: true while a context compaction is in progress for the active
      * session. Set by [MainViewModel.compactSession], cleared when the session
      * transitions from busy → idle (compaction done) or on immediate failure.
      * While true, the compacting capsule is shown (no abort button) and chat
      * input is disabled.
      */
     val isCompacting: Boolean = false,
     /** §compact: System.currentTimeMillis when compaction started, for the
      * capsule timer and the idle-clear guard floor. */
     val compactStartedAt: Long = 0L,
     /**
     * §3-scroll-memory: monotonically incremented by
     * [MainViewModel.performGlobalColdStartRefresh] so the ChatScreen layer
     * observes a change and clears its hoisted per-session scroll-position
     * cache. Only consumed by ChatScreen via
     * [MainViewModel.chatFlow]; follows the same write-only-to-slice
     * pattern as [isCompacting] / [compactStartedAt].
     */
     val refreshNonce: Long = 0L,
     /**
      * §R-17 batch5: SSE delta coalescing buffers. Moved out of
      * [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator]'s private
      * mutableMapOf hidden state so the coalesce-window state is observable
      * (e.g. an idle reload can detect "deltas still buffered" before deciding
      * the overlay is empty).
      *
      * - [deltaBuffer]: accumulated delta text per partId (APPEND semantics;
      *   the previous StringBuilder → String conversion makes each entry
      *   immutable so CAS `update { }` is safe).
      * - [fullTextBuffer]: latest authoritative full text per partId (REPLACE
      *   semantics; fullText supersedes any concurrent delta accumulation).
      * - [pendingFlushPartIds]: partIds whose DELTA_COALESCE_MS flush window
      *   is still open. The actual `Job` references stay on the coordinator
      *   (a Job is neither serializable nor a value type — it is tied to the
      *   coordinator's CoroutineScope); this set is the observable mirror.
      */
     val deltaBuffer: Map<String, String> = emptyMap(),
     val fullTextBuffer: Map<String, String> = emptyMap(),
     val pendingFlushPartIds: Set<String> = emptySet(),
      /**
       * §Wave5b-Q13: single-slot "next scroll intent to consume" — the unified
       * replacement for the pre-Wave5b `pendingJumpToLatest: String?`. Written
       * by [cn.vectory.ocdroid.ui.controller.SessionSwitcher.switchTo] inside
       * the SAME mutateChat that flips currentSessionId (so the consumer always
       * observes a consistent pair). Consumed ONCE by
       * [cn.vectory.ocdroid.ui.chat.ChatMessageList]'s LaunchedEffect, which
       * then compare-and-clears by [PendingScrollRequest.requestId] (a fast
       * A→B→C cascade where A's consumer finishes last cannot wipe C's newer
       * intent — see [AppAction.ScrollConsumed] reducer guard).
       *
       * Behavior matrix (oracle-validated):
       *  - swipe / tab-strip / picker / Sessions page / Files page / create /
       *    fork / close-delete-next / cold-start / Chat-tab reselect / send →
       *    `Latest`. All these go through `switchTo(id)` default arg + the
       *    `requestLatestScroll(id)` helper on the send / Chat-reselect paths
       *    (same-session switchTo is a deliberate no-op).
       *  - 父→子 openSubAgent → child gets `Latest`. The parent's checkpoint is
       *    captured at click time and persisted on the parent route entry's
       *    [androidx.lifecycle.SavedStateHandle] (§chat-list-detail §11 / G6
       *    B5 — per-entry storage, NOT a global ChatState map).
       *  - 子→父 returnToParent → `Restore(checkpoint)` replayed by the parent
       *    entry's LaunchedEffect (reads + consumes the handle entry, dispatches
       *    [AppAction.ScrollRequested] with behavior=Restore).
       *
       * Cleared on host purge / draft materialize / current-session archive
       * (see [cn.vectory.ocdroid.ui.clearSessionData] +
       * [cn.vectory.ocdroid.ui.controller.applyArchivedChatClear] + the same-
       * group host-purge branch in [cn.vectory.ocdroid.ui.reduce]). The legacy
       * global checkpoint map is GONE (B5 §11): checkpoint lifecycle is now
       * bound to the route entry's SavedStateHandle, so pop-driven cleanup is
       * automatic.
       */
      val pendingScrollRequest: PendingScrollRequest? = null,
      // §chat-list-detail §11 / G6 (B5): the legacy per-child checkpoint
      // map field is REMOVED. Checkpoints now live on the parent route
      // entry's SavedStateHandle (keyed by childId via
      // [checkpointKeyForChild]); entry pop auto-cleans the handle, so the
      // three manual sweep sites (host-purge / archive subtree / draft
      // materialize) no longer need to touch checkpoints. The single-slot
      // [pendingScrollRequest] above is unchanged.
      /**
      * §chat-ux-batch T7 (B2): the user's just-picked agent for the active
      * session — TRANSIENT, consumed and cleared by [cn.vectory.ocdroid.ui.AppCoreOrchestration.dispatchSendMessage]
      * at send time. Null means "no explicit pick this turn → fall back to
      * inference from the transcript (`inferCurrentAgent`) or, if that also
      * yields null, send `agent=null` so the server applies its default".
      *
      * Replaces the legacy global `SettingsState.selectedAgentName` +
      * `SettingsManager.setAgentForSession` carry as the per-send authority
      * (those fields are kept unread by T7's send/picker paths; T8 deletes
      * them). Resolution at send:
      * `agent = pendingAgent ?: inferCurrentAgent(msgs, visibleAgents) ?: null`.
      *
      * `visibleAgents` MUST be `settings.agents.filter { it.isVisible }.map { it.name }.toSet()`
      * — opencode's `/agent` list includes hidden internal agents (compaction
      * / title) whose transcript presence would otherwise be inferred as the
      * current agent; the visible filter defeats that (T6 contract).
      */
     val pendingAgent: String? = null,
     /**
      * §chat-ux-batch T7 (B2): the user's just-picked model for the active
      * session — TRANSIENT, consumed and cleared by
      * [cn.vectory.ocdroid.ui.AppCoreOrchestration.dispatchSendMessage] at send
      * time. Null means "no explicit pick this turn → fall back to inference
      * from the latest visible assistant message's `resolvedModel` (`inferCurrentModel`)
      * or, if that also yields null, send `model=null` so the server applies
      * its default (server-side `prompt.ts:646` is the source of truth and
      * honors an explicit model when provided)".
      *
      * Replaces the legacy `ChatState.currentModel` + `SettingsManager.setModelForSession`
      * carry as the per-send authority (those fields are kept unread by T7's
      * send/picker paths; T8 deletes them). Resolution at send:
      * `model = pendingModel ?: inferCurrentModel(msgs, visibleAgents) ?: null`.
      */
     val pendingModel: Message.ModelInfo? = null,
     /** §P0-E(b): session-level queue for LastAssistantErrorAttached payloads that
      *  arrived while route didn't match or no assistant existed yet (R10 silent-drop
      *  fix). Keyed by sessionId. Bounded to PENDING_ERROR_REATTACH_MAX (LRU by
      *  insertion order). Payload-complete (no messageId assumed — B2).
      *
      *  §P0-E NARROWED: producer-only scaffolding (records payloads; no consumer
      *  yet). Wiring deferred to post-P0-A. */
     val pendingErrorReattach: Map<String, PendingChatError> = emptyMap(),
      /** §P0-E(c) TWO-PHASE ERROR RECOVERY: sessions that transitioned busy/retry →
       *  terminal-idle and need a GET-fallback to localize a potential durable error.
       *  Writer: AuthorityReducer (busy/retry→idle transition adds the sid).
       *  Consumer: ErrorRecoveryCoordinator (drains via getMessages GET when
       *  sessionErrorsById[sid] != null and last assistant lacks an error).
       *  Cleared on: ErrorLocalizationSettled, session delete/archive (subtree),
       *  host purge, bulk refresh archive. */
     val pendingErrorCheck: Set<String> = emptySet(),
 )
