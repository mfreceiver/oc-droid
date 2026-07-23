package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.util.LocaleMode
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.ThemeMode
import kotlinx.coroutines.flow.StateFlow

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

sealed class TunnelActivationState {
    data object Idle : TunnelActivationState()
    data object Loading : TunnelActivationState()
    data object Success : TunnelActivationState()
    data class Error(val message: String) : TunnelActivationState()
}

/**
 * §R18 Phase 2-I: replacement for the legacy `connectionPhase: String?`
 * (which used free-form strings "connecting"/"connected"/"disconnected"/
 * "reconnecting"/"reconnecting (attempt N/M)"). The sealed hierarchy lets the
 * compiler enforce exhaustive `when` branches at UI read sites and kills
 * typo-class bugs at write sites.
 *
 * Two distinct Reconnecting shapes coexist in the codebase:
 *  - [Reconnecting] — host-switch / cold-start immediate signal, no
 *    attempt counter (writers: HostProfileController host-switch reset,
 *    ConnectionActions.applySavedSettings cold-start signal).
 *  - [ReconnectingAttempt] — ConnectionCoordinator retry-loop probe with
 *    exponential backoff (writer: ConnectionCoordinator.testConnection on
 *    attempt > 1).
 *
 * Non-null (default [Idle]) on purpose: lets UI `when (phase)` branches be
 * exhaustive without an `else`. The previous `null` semantics (badge hidden,
 * empty-state plain text) map to [Idle].
 */
sealed class ConnectionPhase {
    /** No connection activity — initial state, or after a clean disconnect reset. */
    data object Idle : ConnectionPhase()
    /** First attempt of a connect probe is in flight (no retries yet). */
    data object Connecting : ConnectionPhase()
    /** Healthy connect established (server is reachable). */
    data object Connected : ConnectionPhase()
    /** Host-switch / cold-start reconnect signal, no attempt counter. */
    data object Reconnecting : ConnectionPhase()
    /** Retry-loop reconnect with backoff; carries the attempt counter for UI. */
    data class ReconnectingAttempt(val attempt: Int, val maxAttempts: Int) : ConnectionPhase()
    /**
     * §tofu R2: SSL/cert error against an unpinned endpoint — the coordinator
     * captured the leaf cert and is WAITING for the user's TOFU trust decision
     * (Accept once / Trust / Cancel). The retry loop is suspended (no retries
     * burn) and cold-start reconnect / SSE start are FROZEN while in this
     * phase. UI renders [cn.vectory.ocdroid.ui.settings.TofuTrustDialog].
     */
    data object AwaitingTofuTrust : ConnectionPhase()
    /** Probe failed terminally (retries exhausted or one-shot failure). */
    data object Disconnected : ConnectionPhase()
}

/**
 * §R-17 batch2: connection-domain state slice. Authoritative storage; no
 * AppState mirror. Field set strictly follows RFC R-17 §2.1.
 *
 * Write atomicity (RFC §4, strategy A): every mutation goes through a single
 * `writeConnection { ... }` (or a sequence of them where each
 * intermediate state is a legal UI state — never a `isConnected=true` paired
 * with an `Idle` `connectionPhase`). Do NOT rely on `Dispatchers.Main.immediate`
 * batching across separate `update` calls.
 */
data class ConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val connectionPhase: ConnectionPhase = ConnectionPhase.Idle,
    val tunnelActivationState: TunnelActivationState = TunnelActivationState.Idle,
    /**
     * §O-C weak-network §4: stale indicator. `true` when the last metadata
     * refresh (cold-start / resync) failed (or we are serving cached data
     * because the network is flaky). The UI can observe this field to render
     * a "stale data" indicator (e.g. muted colors / a banner). Cleared
     * on every successful refresh.
     */
    val stale: Boolean = false,
    /**
     * §fix-3 (gro-1#2/gpt-2#2/max-1 M1): 非 null = 当前 host 的 mTLS 已开启但客户端
     * 证书材料缺失（ESP 无 p12/pw key）或损坏（试构建失败，见
     * [cn.vectory.ocdroid.data.repository.OpenCodeRepository.lastClientCertError]）。
     * 此时 SSL 已降级回 SystemDefault → stunnel 会拒连。显示本字段为红色 banner，让
     * 用户看到「证书加载失败」而非泛化「连接失败」。null = 无 mTLS 降级。每次
     * configure（切 host / 冷启 / 保存）重置。
     */
    val mtlsDegradedError: String? = null,
    /**
     * §tofu R2: non-null = the connection hit an SSL/cert error against an
     * endpoint with NO TOFU pin yet, the coordinator captured the leaf cert,
     * and is now WAITING for the user's trust decision (Accept once / Trust /
     * Cancel). The UI observes this field and renders [TofuTrustDialog]. While
     * non-null, the retry loop is SUSPENDED (no retries burn) AND
     * [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator.coldStartReconnect]
     * / [startSSE] are FROZEN (they early-return). Set by the coordinator on
     * capture; cleared by [ConnectionCoordinator.resolveTofuTrust] once the
     * decision is applied (then the loop re-probes with the new pin).
     */
    val pendingTofuCapture: cn.vectory.ocdroid.data.repository.OpenCodeRepository.TofuCaptureResult? = null,
    /**
     * §R8 slim-mode M2 自检：非 null = slimapi 版本不兼容（客户端版本不在
     * sidecar 公告的 accepted_client_versions 闭区间内）。值为三元组
     * (clientVersion, acceptedMin, acceptedMax)，供 UI 展示。fail-closed——
     * 不兼容时标记连接不可用，不静默报健康。
     */
    val slimapiVersionIncompatible: Triple<Int, Int, Int>? = null,
    /**
     * 镜像 [cn.vectory.ocdroid.data.repository.ServerCompatProfile.slimConnection]——
     * `true` = 当前连接的 live mode 为 slim（省流模式）。供 ServerStatusIconButton
     * 区分绿（标准服 / slim 服的非 slim 模式）与蓝（slim 模式活跃）。
     *
     * 写入时机：connect 成功写入 `isConnected=true` 的同时设置；断连时由红遮蔽
     * 但仍保持语义一致。默认 `false` 不破坏既有收集者。
     */
    val isSlimActive: Boolean = false,
)

/**
 * §R-17 M2: traffic-domain state slice. Authoritative storage lives in
 * [MainViewModel._trafficFlow]. Field set strictly follows RFC R-17 §2.9.
 * Only written by [MainViewModel.refreshTrafficStats] and read by the server
 * management dialog (ChatTopBar) — isolating it avoids recomposing unrelated
 * subscribers each time the counter is refreshed.
 */
data class TrafficState(
    val trafficSent: Long = 0L,
    val trafficReceived: Long = 0L,
    /** Epoch millis of the last traffic reset; 0 = never reset (UI then shows
     *  「自启用累计」). Stamped by [cn.vectory.ocdroid.util.TrafficTracker.reset]. */
    val resetAt: Long = 0L
) {
    /** Combined sent + received traffic, derived so it never drifts. */
    val totalTrafficBytes: Long
        get() = trafficSent + trafficReceived
}

/**
 * §R-17 batch2: composer-domain state slice. Authoritative storage; writes
 * via _composerFlow.update. Field set strictly follows RFC R-17 §2.5.
 *
 * This is the highest-frequency slice (`inputText` mutates on every keystroke)
 * and the primary reason the slice exists: consumers subscribe to
 * `composerFlow` directly, so keystrokes no longer recompose ChatTopBar.
 *
 * Write atomicity (RFC §4 strategy A): same model as [ConnectionState] —
 * every mutation goes through a single `writeComposer { ... }`. No dispatcher
 * batch reliance (RFC §9.2).
 */
/**
 * §1B (F.4): a single file reference attached to the composer — renders as a
 * removable `InputChip` above the input row and serialises downstream as a
 * `PartInput(type=text)` carrying the literal `File: <path>` payload (scheme
 * A — zero protocol change). `id` is a stable key so chip-removal can find
 * its way back to the right list entry even when paths repeat.
 */
data class ComposerFileReference(
    val path: String,
    val id: String = java.util.UUID.randomUUID().toString()
)

data class ComposerState(
    val inputText: String = "",
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    val sendingSessionIds: Set<String> = emptySet(),
    val draftWorkdir: String? = null,
    /**
     * §1B (F.4): file references attached to the composer (Phase 1B renders
     * the chip strip; the writer-side add/remove lives on
     * [cn.vectory.ocdroid.ui.controller.ComposerController]; the Add-menu
     * "Reference file" entry is still a Phase 2 deliverable so the
     * list stays empty in normal flow until then). Additive — no existing
     * writer reads it yet.
     */
    val fileReferences: List<ComposerFileReference> = emptyList()
)

/**
 * §R-17 M3: file-browser-domain state slice. Authoritative storage lives in
 * [MainViewModel._fileFlow]. Field set strictly follows RFC R-17 §2.6.
 * Consumed only by FilesScreen + the AppShell file-overlay (AppShell is the
 * sole shell; the legacy PhoneLayout + USE_NEW_SHELL flag were removed in
 * the redesign); isolating it prevents file-open/close from recomposing
 * unrelated subscribers.
 */
data class FileState(
    val filePathToShowInFiles: String? = null,
    val filePreviewOriginRoute: String? = null,
    val fileBrowserOpen: Boolean = false,
    val fileBrowserWorkdir: String? = null
)

/**
 * §R-17 batch2: settings/global-preference state slice. Authoritative storage
 * via _settingsFlow.update. Field set strictly follows RFC R-17 §2.4 (error
 * is NOT here — it is a one-shot UiEvent on _uiEvents).
 *
 * `availableCommands` is a connect-time / host-switch config (not live state)
 * but RFC §2.4 groups it here rather than under composer.
 */
data class SettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * §P5a (Q5): user-facing language preference. Seeded from
     * [cn.vectory.ocdroid.util.SettingsManager.localeMode] at cold-start
     * (ConnectionActions) and mutated by [cn.vectory.ocdroid.ui.SettingsViewModel.setLocaleMode].
     * The locale itself is applied via [cn.vectory.ocdroid.util.AppLocaleController]
     * (AppCompatDelegate); this field is the reactive UI mirror so the
     * Appearance SegmentedButton shows the right selection.
     */
    val localeMode: LocaleMode = LocaleMode.SYSTEM,
    val markdownFontSizes: MarkdownFontSizes = MarkdownFontSizes(),
    val agents: List<AgentInfo> = emptyList(),
    // §chat-ux-batch T8 (B3): the legacy `selectedAgentName` field was deleted
    // here. T7 rewired agent selection to the TRANSIENT `pendingAgent` chat-
    // slice field (resolved `pending ?: infer ?: null` at send). The UI reads
    // the effective agent via that projection (ChatScaffold passes
    // `currentAgentName = effectiveAgent` to ChatTopBar); no settings-slice
    // mirror is needed.
    val providers: ProvidersResponse? = null,
    val availableCommands: List<CommandInfo> = emptyList(),
    /**
     * §model-selection: per-baseUrl disabled-model entries (format
     * `"$providerId/$modelId"`), projected from
     * [cn.vectory.ocdroid.util.SettingsManager.getDisabledModels] for the
     * current host. Used by Settings → Model management and the chat
     * quick-switch picker to hide unchecked models.
     */
    val disabledModels: Set<String> = emptySet(),
    /**
     * §ui-scale: user-adjustable UI scale factors (M3 LocalDensity override
     * pattern). [uiFontScale] multiplies fontScale (text only);
     * [uiContentScale] multiplies density (dp dimensions + sp text together).
     * Both default 1.0; clamped to SettingsManager.UI_SCALE_MIN–MAX. Seeded
     * from SettingsManager on connect; persisted via the setters in
     * MainViewModel. Read by MainActivity → OpenCodeTheme → LocalDensity.
     */
    val uiFontScale: Float = 1f,
    val uiContentScale: Float = 1f
)

// ── §Wave5b-Q13: scroll-position state machine ─────────────────────────
//
// (a) Switching into a session usually means "show the latest". The exceptions
//     are父→子 openSubAgent (records the parent's last viewport) and 子→父
//     returnToParent (restores that viewport). Every other switch path
//     (swipe / tab-strip / picker / Files / Sessions / create / fork / close-
//     delete-next / cold-start) lands at Latest.
// (b) The state machine is a SINGLE-SLOT ADT ([pendingScrollRequest]) instead
//     of the pre-Wave5b `pendingJumpToLatest: String?` + scattered bookkeeping.
//     A single slot means there is exactly ONE in-flight scroll intent at a
//     time, which makes priority / clearing-order races impossible (oracle
//     ruling: the prior boolean + ADT pair was mutually exclusive anyway).
// (c) The consumer (ChatMessageContent) clears via compare-and-clear on
//     [PendingScrollRequest.requestId] — a fast A→B→C cascade where A's
//     consumer finishes last cannot accidentally clear C's newer intent.

/**
 * §Wave5b-Q13: behavior to apply when consuming [PendingScrollRequest].
 *
 *  - [Latest]: scroll to item 0 (reverseLayout ⇒ 0 = newest) + arm
 *    `followBottom`. Used by every "explicit switch into a session" path
 *    (swipe, tab-strip, picker, Files, Sessions, create/fork, close-delete-
 *    next, cold-start, Chat-tab reselect, send).
 *  - [Restore]: scroll to the captured [ScrollCheckpoint] and DISarm
 *    `followBottom` unless the resolved anchor happens to land at the bottom.
 *    Used by 子→父 returnToParent (Android Back + breadcrumb). The checkpoint
 *    is captured synchronously by the Compose layer at the openSubAgent call
 *    site (NOT via the async savedPositions mirror — that cannot guarantee
 *    the last frame before navigation, per oracle).
 */
sealed interface ScrollBehavior {
    data object Latest : ScrollBehavior
    data class Restore(val checkpoint: ScrollCheckpoint) : ScrollBehavior
}

/**
 * §Wave5b-Q13: snapshot of the parent session's LazyListState at the moment
 * the user opened a sub-agent. Captured SYNCHRONOUSLY by the Compose layer
 * (ChatMessageContent's onOpenSubAgent wrapper) — never derived from the
 * async savedPositions mirror, which oracle ruled cannot be trusted to hold
 * the last pre-navigation frame.
 *
 *  - [anchorKey]: the stable key (message id / "streaming-reasoning" /
 *    "session-diff" / "load-more") of the FIRST VISIBLE item at capture time.
 *    Preferred over the raw index because message prepends / SSE appends /
 *    metadata-marker injection can shift indices without moving the user's
 *    real position. `null` if the LazyListState had no layout info at the
 *    capture moment (list still measuring) — the resolver then falls back to
 *    [fallbackIndex].
 *  - [fallbackIndex]: listState.firstVisibleItemIndex at capture time. Used
 *    only when [anchorKey] is null OR not present in the current LazyColumn
 *    body (clamped to [0, itemCount)).
 *  - [offset]: listState.firstVisibleItemScrollOffset at capture time. Paired
 *    with the resolved index for scrollToItem(index, offset).
 */
data class ScrollCheckpoint(
    val anchorKey: String?,
    val fallbackIndex: Int,
    val offset: Int,
)

/**
 * §Wave5b-Q13: the single-slot "next scroll intent to consume" intent.
 * Written by [SessionSwitcher.switchTo] in the SAME mutateChat that flips
 * currentSessionId (so the consumer always sees a consistent pair). Read and
 * compare-and-cleared by [cn.vectory.ocdroid.ui.chat.ChatMessageList]'s
 * LaunchedEffect.
 *
 *  - [requestId]: a monotonic id (System.nanoTime). The consumer compares
 *    this against the live slot's id when clearing; a mismatch means a newer
 *    request has superseded this one (fast A→B→C cascade where A's consumer
 *    finishes last) and the clear is a no-op.
 *  - [targetSessionId]: the session the consumer must be on to fire. When
 *    chatState.currentSessionId != targetSessionId, the consumer skips
 *    (e.g. a Restore intent for parent fired while still on child during a
 *    brief race — wait until the session switch lands).
 *  - [behavior]: Latest or Restore(checkpoint).
 */
data class PendingScrollRequest(
    val requestId: Long,
    val targetSessionId: String,
    val behavior: ScrollBehavior,
)

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
 * §R-17 batch2: chat-domain state slice (RFC §2.2). Authoritative storage via
 * _chatFlow.update. The highest-frequency domain (SSE streaming deltas mutate
 * streamingPartTexts/messages many times per second). §R-17 batch2: error/success
 * events migrated to SharedFlow<UiEvent>.
 */
data class ChatState(
    val currentSessionId: String? = null,
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
       *  - 父→子 openSubAgent → child gets `Latest` AND the parent's checkpoint
       *    is stored in [parentReturnCheckpoints] for the eventual return.
       *  - 子→父 returnToParent → `Restore(checkpoint)` from the stored entry.
       *
       * Cleared alongside [parentReturnCheckpoints] on host purge (both same-
       * group and cross-group), draft materialize, and current-session archive
       * (see [cn.vectory.ocdroid.ui.clearSessionData] +
       * [cn.vectory.ocdroid.ui.controller.applyArchivedChatClear] + the same-
       * group host-purge branch in [cn.vectory.ocdroid.ui.reduce]).
       */
      val pendingScrollRequest: PendingScrollRequest? = null,
      /**
       * §Wave5b-Q13: navigation-return map for 子→父 restore. Keyed by the
       * CHILD session id (the current session id at the moment of
       * openSubAgent). When the user navigates back from child to parent
       * (Android Back or breadcrumb), [cn.vectory.ocdroid.ui.SessionViewModel.returnToParent]
       * reads `parentReturnCheckpoints[currentSessionId]` and dispatches
       * `Restore(checkpoint)` to the parent's consumer.
       *
       * Stored in the SAME dispatch as the parent-checkpoint capture
       * ([AppAction.ParentCheckpointStored]) so there is no torn intermediate
       * where the child is current but the parent's checkpoint is not yet on
       * file. Consumed (entry removed) by [AppAction.ParentCheckpointConsumed]
       * when returnToParent fires. Cleared entirely on host purge / draft
       * materialize / current-session archive.
       *
       * Why a separate map (not embedded in PendingScrollRequest): the
       * pending-scroll slot is the NEXT intent to consume on the active
       * session, whereas this map is a navigation backstack that survives
       * across multiple in-flight sessions. Folding them would force a single
       * child-deep navigation, breaking the "swipe between roots while inside
       * a sub-agent" case.
       */
      val parentReturnCheckpoints: Map<String /*childId*/, ScrollCheckpoint> = emptyMap(),
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
 )

 /**
  * §R-17 M4: session-list-domain state slice (RFC §2.3). Authoritative storage
 * lives in [MainViewModel._sessionListFlow]. Low-frequency (loadSessions /
 * loadMore / SSE session.created/updated); isolating it stops SSE chat deltas
 * from recomposing SessionsScreen.
 */
data class SessionListState(
    val sessions: List<Session> = emptyList(),
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
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
    val openSessionIds: List<String> = emptyList(),
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
     * §fix-close-all-residual / §fix-close-all-no-first: gates open-tab
     * restore in [launchLoadSessions] when currentSessionId is null.
     * Auto-select is restore-only (last live id still in openSessionIds) —
     * never invents a session from `sessions.first()`. Empty openSessionIds
     * always wins (user closed every tab / cold start with no tabs). Pre-fix,
     * every session-list refresh re-fired a first()-select when current was
     * null, resurrecting the earliest server session after close-all. Flipped
     * to true on the first successful [launchLoadSessions] commit; reset on
     * cross-group host purge (empty open tabs still prevent first()).
     */
    val hasCompletedInitialLoad: Boolean = false,
)

/**
 * §R-17 M4: unread-domain state slice (RFC §2.7). Authoritative storage lives
 * in [MainViewModel._unreadFlow]. Drives the unread badge; depends on session
 * status + chat currentSessionId + foreground (cross-domain, see RFC §4).
 *
 * §unread-soak: the single [unreadSessions] set is the source of truth for
 * ALL unread indicators (bottom badge, Sessions cards, picker dots, tab strip,
 * files card). Its POPULATION logic changed — a root now becomes unread ONLY
 * when (a) the root AND ALL its descendants are idle, (b) that all-idle state
 * has persisted ≥[UNREAD_SOAK_MS], (c) the root is not the currently-open
 * session, AND (d) the user has not viewed the root since it went idle. UIs
 * keep reading [unreadSessions] as before; only the producer changed (the
 * [cn.vectory.ocdroid.ui.controller.UnreadSoakController] sweep + the pure
 * [cn.vectory.ocdroid.ui.controller.evaluateUnread] evaluator).
 */
data class UnreadState(
    val unreadSessions: Set<String> = emptySet(),
    val lastViewedTime: Map<String, Long> = emptyMap(),
    /**
     * §unread-soak: rootId → epoch-ms when it first entered the all-idle state
     * in the current soak cycle. Drives [evaluateUnread]'s ≥[UNREAD_SOAK_MS]
     * soak gate. Cleared on busy (root or any descendant) → resets the soak;
     * retained after completion/current viewing as edge memory, and cleared
     * by the next busy/unknown transition (then re-soaks on busy→idle).
     * Set/cleared by [cn.vectory.ocdroid.ui.controller.UnreadSoakController]
     * via [cn.vectory.ocdroid.ui.controller.evaluateUnread].
     */
    val idleSince: Map<String, Long> = emptyMap()
)

/**
 * §R-17 M4: host-profile-domain state slice (RFC §2.8). Authoritative storage
 * lives in [MainViewModel._hostFlow]. Very low write frequency (save/switch/
 * import); consumed by ChatTopBar (server dialog) + SettingsScreen.
 */
data class HostState(
    val hostProfiles: List<HostProfile> = emptyList(),
    val currentHostProfileId: String? = null
)

/**
 * §Per-session message cache: `CachedSessionWindow` lives in
 * `ui.controller` (relocated back from `data.cache.contract` in
 * remove-message-persistence Task 6 — the SQLite cache that owned the
 * data-layer dependency ring is gone). See
 * [cn.vectory.ocdroid.ui.controller.CachedSessionWindow].
 *
 * remove-message-persistence Task 4: the non-contiguous gap mechanism
 * (the chat-slice gap field, the gap-fill coordinator, the gap-detection
 * algorithm, the gap-aware rendering pipeline) was deleted. Catch-up now
 * always merges the fetched window (no divider / backfill); manual "load
 * more" paging covers older history. See
 * `docs/features/persistent-chat-cache-plan.md` for the original plan
 * (now superseded by the persistence-removal sequence).
 */

data class ConnectionFormSettings(
    val serverUrl: String,
    val username: String,
    val password: String
)

/**
 * §R-17 batch2 → §R18 Phase 4 (P0-9): bundle view over the nine domain slices.
 * Passed to Actions free functions and controllers.
 *
 * Originally a `data class` holding the nine `MutableStateFlow`s directly so
 * free helpers could `.update { }` them. P0-9 write convergence moved every
 * `MutableStateFlow` behind [SharedStateStore]'s private field + public
 * [SharedStateStore.mutateXxx] helper; this bundle now exposes the matching
 * read-only [StateFlow] views + per-slice [mutateXxx] write funnels that
 * delegate to the store. Callers that used `slices.mutateChat { ... }` now
 * use `slices.mutateChat { ... }`; reads (`slices.chat.value`) are unchanged.
 *
 * `internal` constructor pins creation to [SharedStateStore] so the bundle
 * cannot be assembled against foreign flows.
 *
 * §R-17 batch2 step e final: all writes via the per-slice `mutateXxx` helpers
 * (CAS) MUST run on Dispatchers.Main.immediate (caller convention) to
 * preserve cross-slice consistency within a single frame.
 */
class SliceFlows internal constructor(internal val store: SharedStateStore) {
    val connection: StateFlow<ConnectionState> get() = store.connectionFlow
    val traffic: StateFlow<TrafficState> get() = store.trafficFlow
    val composer: StateFlow<ComposerState> get() = store.composerFlow
    val file: StateFlow<FileState> get() = store.fileFlow
    val settings: StateFlow<SettingsState> get() = store.settingsFlow
    val chat: StateFlow<ChatState> get() = store.chatFlow

    /** §history-load-fix: per-session message-mutation lock (see
     *  [SharedStateStore.messageLoadCoordinator]). */
    val messageLoadCoordinator: MessageLoadCoordinator get() = store.messageLoadCoordinator
    val sessionList: StateFlow<SessionListState> get() = store.sessionListFlow
    val unread: StateFlow<UnreadState> get() = store.unreadFlow
    val host: StateFlow<HostState> get() = store.hostFlow

    fun mutateConnection(transform: (ConnectionState) -> ConnectionState) = store.mutateConnection(transform)
    fun mutateTraffic(transform: (TrafficState) -> TrafficState) = store.mutateTraffic(transform)
    fun mutateComposer(transform: (ComposerState) -> ComposerState) = store.mutateComposer(transform)
    fun mutateFile(transform: (FileState) -> FileState) = store.mutateFile(transform)
    fun mutateSettings(transform: (SettingsState) -> SettingsState) = store.mutateSettings(transform)
    fun mutateChat(transform: (ChatState) -> ChatState) = store.mutateChat(transform)
    fun mutateSessionList(transform: (SessionListState) -> SessionListState) = store.mutateSessionList(transform)
    fun mutateUnread(transform: (UnreadState) -> UnreadState) = store.mutateUnread(transform)
    fun mutateHost(transform: (HostState) -> HostState) = store.mutateHost(transform)
}
