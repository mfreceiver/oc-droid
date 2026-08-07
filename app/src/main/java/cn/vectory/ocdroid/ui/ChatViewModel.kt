package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.ui.controller.allSessionsById
import cn.vectory.ocdroid.ui.controller.subtreeIds
import cn.vectory.ocdroid.util.DebugLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject

/**
 * * R-17 batch3 → batch3d: Chat-domain ViewModel. Owns the chat slice + the
 * message-window lifecycle (load / page / gap-close / streaming overlay),
 * plus the abort / compact / edit / refresh operations.
 *
 * **batch3d**: the method bodies that USED to live in [AppCore] (and were
 * exposed as `fun xxx() = core.xxx()` delegate shells) have been physically
 * moved HERE. The VM now calls its domain controller
 * ([AppCore.sessionSyncCoordinator], [AppCore.composerController]) and the
 * [MessageActions] / [CatchUpActions] free functions
 * directly — no more `core.<method>()` self-bypass.
 *
 * Cross-domain orchestration (`sendMessage` — composer→chat→session creation)
 * stays in [AppCore] (it spans 3 domains) and is surfaced via [sendMessage].
 *
 * State reads come from the shared [SharedStateStore] (slices are the sole
 * authoritative store; the VMs read each other's slices through it, never
 * through sibling VM references).
 *
 * Chat-screen affordances that touch OTHER domains (composer input, model
 * picker, permission/question responses, file preview) live on their own
 * domain VMs ([ComposerViewModel], [OrchestratorViewModel]) — ChatScreen
 * injects those VMs alongside this one (see the batch3d composable wiring).
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    internal val core: AppCore,
    private val bannerOwner: BannerHysteresisOwner,
) : ViewModel() {
    private val revertConversation = RevertConversation(core)
    private val revertCutoffCoordinator = RevertCutoffCoordinator(core)

    /**
     * §compact-watchdog-gen (Blocker-1 residual): monotonic generation token
     * scoping the compact watchdog to the exact `compactSession()` invocation
     * that armed it. Bumped:
     *  - at the top of [compactSession] (so the new attempt gets a fresh gen
     *    and any older still-pending watchdog now sees a mismatched gen);
     *  - inside [clearCompacting] when it actually clears (so a watchdog
     *    armed by the same attempt also goes stale once any clear path —
     *    SSE idle, deterministic failure, server-reject — has run).
     *
     * Without this token the read-timeout watchdog was a free-floating
     * coroutine that would happily clear a *newer* attempt's isCompacting
     * if the older attempt had been cleared (by SSE) inside the watchdog's
     * 180 s window — re-enabling the Composer mid-compaction. Main-thread
     * confined (all callers run on viewModelScope's Main dispatcher), so a
     * plain non-volatile Int is safe.
     */
    private var compactGeneration: Int = 0

    val chatFlow get() = core.chatFlow
    val sessionListFlow get() = core.sessionListFlow
    val unreadFlow get() = core.unreadFlow
    val expandedParts get() = core.expandedParts
    val connectionFlow get() = core.connectionFlow
    val composerFlow get() = core.composerFlow
    val settingsFlow get() = core.settingsFlow
    val fileFlow get() = core.fileFlow
    val trafficFlow get() = core.trafficFlow
    val hostFlow get() = core.hostFlow
    val uiEvents get() = core.uiEvents

    /**
     * §1B-FIX (I5): narrow projection of [chatFlow] exposing only
     * `currentModel`. Subscribers (Composer) recompose on
     * model-change only — NOT on every SSE streaming delta. The
     * underlying `chatFlow` emits the whole [ChatState] on each
     * `streamingPartTexts` mutation; collecting the whole flow would
     * force the Composer to recompose per token. `distinctUntilChanged`
     * drops equal-value emissions; the model field changes only on
     * model switch + initial inference from assistant message, both
     * low-frequency. Lazy-initialized so the first collector pays the
     * one-time map+distinctUntilChanged wiring cost; subsequent
     * collectors share the same Flow instance.
     */
    val currentModelFlow: kotlinx.coroutines.flow.Flow<cn.vectory.ocdroid.data.model.Message.ModelInfo?>
        by lazy {
            core.chatFlow
                .map { it.currentModel }
                .distinctUntilChanged()
        }

    /**
     * §C1/C2: banner visibility state driven by [BannerHysteresisOwner] —
     * process-scoped, no WhileSubscribed reset, deadline-accurate timing.
     */
    internal val bannerVisibility: StateFlow<BannerHysteresisState> = bannerOwner.state

    /** §R-17 batch3e: repository exposed so ChatMessageList can pass it down
     *  to MessageRow without touching `.core.` from a Composable. */
    val repository: OpenCodeRepository get() = core.repository

    // ── Chat-domain methods (bodies moved from AppCore) ─────────────────────

    fun loadMessages(sessionId: String, resetLimit: Boolean) {
        // §R-17 batch3d: body moved verbatim from AppCore; reaches the shared
        // store/controllers/free-functions directly instead of delegating back
        // to AppCore.loadMessages.
        // R-20 Phase 1: onCacheWindow routes through AppCore.makeCacheHook so
        // the in-memory LRU write is mirrored to the persistent encrypted
        // cache. fp captured at this call (current host) — see makeCacheHook
        // doc for the closure-capture rationale.
        //
        // glm-3 🟡#1 / gpter 复审 final-fix: single-read the profile into a
        // local val so the fp derivation (serverGroupFp.ifBlank { id }) reads
        // currentProfile() exactly once (avoids the theoretical TOCTOU where
        // two reads could see different profiles if a switch raced between
        // them). The same `fp` is used for the cache hook, the captured guard,
        // and the provider — all three are consistent by construction.
        //
        // §Stage-D2: token-stream busy-open lives in BOTH
        // [AppCore.loadMessagesForEffect] (the main production path) AND here
        // (side-door for retry / edit-and-rerun). Both gate via the shared
        // [shouldOpenTokenStream] predicate — see the open at line ~148.
        val fp = core.currentProfileId()
        launchLoadMessages(
            scope = core.appScope,
            repository = core.repository,
            slices = core.store.slices,
            sessionId = sessionId,
            resetLimit = resetLimit,
            onCacheWindow = core.makeCacheHook(fp),
            emit = EventEmitter { event -> core.effectBus.tryEmitUiEvent(event) },
            // gpter 复审 final-fix: compound-key guard.
            expectedProfileId = fp,
            currentProfileId = core.currentProfileId,
            // The VM is also a live route-aware entry point (refresh/retry),
            // not only a legacy bare-chat caller. Capture the active minted
            // token here so its completion updates LoadedContent as well.
            expectedRouteInstance = core.store.slices.routeInstanceFor(sessionId),
            // §11.1 fix-9 P0-7: SSE liveness predicate — see
            // [AppCore.loadMessagesForEffect] for rationale.
            isSseLive = { core.store.slices.sseConnected },
        )
        // §E3 ChatViewModel.loadMessages side-door open (D2 gate r2 S1): re-apply
        // the same open gate used in loadMessagesForEffect. Mid-generation retry /
        // edit-and-rerun that only hits this path also opens the stream (max-1 +
        // debounce makes any dual-open with loadMessagesForEffect harmless).
        if (shouldOpenTokenStream(
                core.serverCompatProfile.tokenStreamEnabled,
                core.store.slices.chat.value.currentSessionId,
                sessionId,
            )
        ) {
            core.tokenStreamCoordinator.open(sessionId, core.settingsManager.currentWorkdir, source = "chatvm-load")
        }
    }

    fun loadMessages(sessionId: String) = loadMessages(sessionId, resetLimit = true)

    internal fun loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessagesWithRetry(core.appScope, sessionId, core.store.slices, resetLimit, ::loadMessages)
    }

    fun loadMoreMessages() {
        val sessionId = core.store.chatFlow.value.currentSessionId ?: return
        val routeInstance = core.store.slices.routeInstanceFor(sessionId)
        // glm-3 🟡#1 / gpter 复审 final-fix: single-read fp.
        val fp = core.currentProfileId()
        launchLoadMoreMessages(
            scope = core.appScope,
            repository = core.repository,
            slices = core.store.slices,
            sessionId = sessionId,
            onCacheWindow = core.makeCacheHook(fp),
            // gpter 复审 final-fix: compound-key guard.
            expectedProfileId = fp,
            currentProfileId = core.currentProfileId,
            expectedRouteInstance = routeInstance,
        )
    }

    fun compactSession() {
        val chatFlow = core.store.chatFlow
        if (chatFlow.value.isCompacting) return
        val sessionId = chatFlow.value.currentSessionId ?: run {
            core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_compact_no_session))
            return
        }
        // §B2 rev-gpt: subagent 只读——禁止 compact 子会话
        val sl = core.sessionListFlow.value
        val sessionsById = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
        val targetSession = sessionId.let { sessionsById[it] }
        if (targetSession?.parentId != null) return
        val model = chatFlow.value.currentModel ?: run {
            core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_compact_no_model))
            return
        }
        // §compact-watchdog-gen (Blocker-1 residual): bump on every fresh
        // attempt so any older still-pending watchdog sees a mismatched gen
        // and no-ops. Captured into `gen` for the new attempt's own watchdog.
        val gen = ++compactGeneration
        core.writeChat { it.copy(isCompacting = true, compactStartedAt = System.currentTimeMillis()) }
        // §R18 Phase 3 Wave 2 (drift #6 / P1-7): user-triggered ephemeral op
        // (compact) → viewModelScope so it cancels cleanly on VM clear and the
        // captured `this@ChatViewModel` closure (loadMessages / core writes)
        // never outlives the VM.
        //
        // §compact-graded (Blocker-1): graded fire-and-forget. "Fire-and-forget"
        // means a *successful POST dispatch* counts as success — we do NOT
        // wait for the compaction result (SSE delivers it asynchronously and
        // clears `isCompacting` via the ChatScaffold idle hook). The previous
        // implementation used `runCatching { ... }` to swallow ALL failures,
        // which meant a transport failure / server-reject / connect-refused
        // left `isCompacting=true` forever and permanently disabled the
        // Composer. The four branches below restore user control on the
        // *deterministic* failure paths while keeping the SSE-driven happy
        // path untouched:
        //   1. accepted=true           → Info "in progress"; SSE clears flag.
        //   2. accepted=false (reject) → clear flag + Error (server said no).
        //   3. SocketTimeoutException  → Info "in progress" + watchdog. The
        //                                POST was likely accepted but the ACK
        //                                timed out — SSE may still deliver.
        //   4. other IOException       → clear flag + Error (POST never
        //                                reached the server; retry is safe).
        viewModelScope.launch {
            core.repository.summarizeSession(sessionId, model)
                .onSuccess { accepted ->
                    if (accepted) {
                        // Do NOT clear isCompacting — the ChatScaffold
                        // idle hook (session.status → idle) clears it when
                        // the server-side compaction finishes via SSE.
                        core.effectBus.tryEmitUiEvent(UiEvent.Info(R.string.info_compact_in_progress))
                    } else {
                        // Should not happen — summarizeSession turns body=false
                        // into Result.failure(SummarizeServerRejectedException).
                        // Treat defensively as a deterministic reject.
                        clearCompacting()
                        core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_compact_failed, listOf("rejected")))
                    }
                }
                .onFailure { error ->
                    when (error) {
                        // §compact-graded: deterministic server-reject — clear +
                        // Error so the user can retry with a different setup.
                        is OpenCodeRepository.SummarizeServerRejectedException -> {
                            clearCompacting()
                            core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_compact_failed, listOf(errorMessageOrFallback(error, "rejected"))))
                        }
                        // §compact-graded: read-timeout — POST was likely
                        // accepted (OkHttp read-timeout fired while waiting
                        // for the ACK); the server is still processing and
                        // SSE may deliver the result. Keep isCompacting=true
                        // so the Composer stays disabled mid-compaction, but
                        // arm the watchdog so we cannot lock forever if SSE
                        // also never delivers.
                        is SocketTimeoutException -> {
                            core.effectBus.tryEmitUiEvent(UiEvent.Info(R.string.info_compact_in_progress))
                        }
                        // §compact-graded: any other IOException (connect
                        // refused, DNS, TLS, HttpException(non-2xx wrapped)...)
                        // — POST never reached the server, SSE cannot deliver,
                        // clear + Error so the user can retry.
                        is IOException -> {
                            clearCompacting()
                            core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_compact_failed, listOf(errorMessageOrFallback(error, "network error"))))
                        }
                        // Anything thrown by summarizeSession that is NOT an
                        // IOException (e.g. the IllegalStateException from a
                        // malformed response). Conservatively clear so the
                        // user is not stuck; report as Error.
                        else -> {
                            clearCompacting()
                            core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_compact_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
                        }
                    }
                }
        }
        // §compact-graded (Blocker-1) watchdog: defence-in-depth against the
        // read-timeout branch (3) and any future path that leaves
        // isCompacting=true with no SSE clear. If the flag is still up after
        // WATCHDOG_MS, clear it + emit Info so the user knows the operation
        // stalled and can retry. SSE-driven normal clear in ChatScaffold is
        // unaffected: if it clears the flag first, the watchdog's recheck
        // sees isCompacting=false and is a no-op.
        //
        // §compact-watchdog-gen (Blocker-1 residual): the recheck ALSO checks
        // `compactGeneration == gen`. Without this guard, an older attempt's
        // watchdog (whose 180 s window straddled an SSE clear + a new user
        // attempt) would see isCompacting==true (the new attempt's flag) and
        // wrongly clear it + emit a spurious timeout. The gen check pins the
        // watchdog to its own attempt and turns every other path into a
        // no-op, even if isCompacting is later re-set by a newer attempt.
        viewModelScope.launch {
            delay(WATCHDOG_MS)
            if (compactGeneration == gen && core.store.chatFlow.value.isCompacting) {
                clearCompacting()
                core.effectBus.tryEmitUiEvent(UiEvent.Info(R.string.info_compact_timeout_retry))
            }
        }
    }

    companion object {
        /**
         * §compact-graded (Blocker-1): watchdog upper bound for the compact
         * fire-and-forget. The server-side compaction normally completes in
         * <30 s; SSE delivers the result and ChatScaffold clears
         * `isCompacting`. If 180 s elapse with the flag still up (read-
         * timeout branch where SSE also fails to deliver), the watchdog
         * clears it so the Composer cannot lock forever.
         *
         * Test-visible as a public const so unit tests can drive virtual time
         * to it without hardcoding the literal.
         */
        const val WATCHDOG_MS: Long = 180_000L

        /** §P0-F: abort 看门狗超时——server 未在该窗口内回送 idle/terminal → REST reconcile 兜底。 */
        internal const val ABORT_WATCHDOG_TIMEOUT_MS = 10_000L

        /** §P0-F 阻断1: process-unique monotonic abort token. 严格递增保证唯一，
         *  闭合 ABA（elapsedRealtime 毫秒在快速重试/长间隔下可能碰撞）。 */
        private val abortTokenSeq = java.util.concurrent.atomic.AtomicLong(0L)

        private const val TAG = "ChatViewModel"
    }

    fun clearCompacting() {
        if (core.store.chatFlow.value.isCompacting) {
            // §compact-watchdog-gen (Blocker-1 residual): bump the generation
            // on every actual clear so the SAME attempt's pending watchdog
            // (still sitting in its 180 s delay) sees a stale gen and no-ops.
            // This covers the SSE idle hook (ChatScaffold) and every direct
            // clearCompacting() call from the failure branches above.
            ++compactGeneration
            core.writeChat { it.copy(isCompacting = false, compactStartedAt = 0L) }
        }
    }

    fun editFromMessage(messageId: String) {
        val chatFlow = core.store.chatFlow
        // §chat-list-detail §11.3 / G6 (B5): read the session id from the
        // route param (navState.lastRoute), NOT the lagging flat
        // currentSessionId. The route flip commits BEFORE SessionSelected
        // flips currentSessionId, so a bare currentSessionId read can target
        // the PRIOR session during the transition window. The route id is
        // the sole identity authority post-B2.
        val sessionId = routeChatSessionId(core.store.navFlow.value.lastRoute)
            ?: chatFlow.value.currentSessionId
            ?: return
        // §B2 rev-gpt: subagent 只读——禁止编辑子会话消息
        val sl = core.sessionListFlow.value
        val sessionsById = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
        val targetSession = sessionId.let { sessionsById[it] }
        if (targetSession?.parentId != null) return
        val message = chatFlow.value.messages.firstOrNull { it.id == messageId && it.isUser } ?: return
        val draft = (chatFlow.value.partsByMessage[messageId] ?: emptyList()).firstOrNull { it.isText }?.text?.trim().orEmpty()
        if (draft.isBlank()) return

        // §R18 Phase 3 Wave 2 (drift #6 / P1-7): ephemeral edit-from-message
        // → viewModelScope. The closure captures `this@ChatViewModel` (via the
        // loadMessages call below), so binding to viewModelScope keeps the
        // captured method ref alive exactly as long as the VM.
        // §R-19 #9: P1-7 closure-self-ref guard added — bail out before the
        // captured loadMessages / slice writes if the VM was cleared while
        // repository.revertSession was in flight. Without the guard, the
        // closure would still hold a strong ref to the cleared VM until GC
        // (viewModelScope cancellation throws CancellationException out of the
        // launch body, which is correct, but the explicit guard documents the
        // no-op intent and is defensive against any future restructuring that
        // moves the body off viewModelScope).
        viewModelScope.launch {
            when (val outcome = revertConversation.execute(sessionId, messageId) { loadMessages(it, resetLimit = true) }) {
                is RevertOutcome.Failure -> core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_edit_message_failed, listOf(errorMessageOrFallback(outcome.error, "unknown error"))))
                RevertOutcome.Cancelled, is RevertOutcome.Success -> Unit
            }
        }
    }

    /** [force] is reserved for an explicit user retry after a terminal failure. */
    fun retryRevertCutoff(force: Boolean = false) {
        // §chat-list-detail §11.3 / G6 (B5): read the session id from the
        // route param (navState.lastRoute), NOT the lagging currentSessionId.
        // See [editFromMessage] for the transition-window rationale.
        val sessionId = routeChatSessionId(core.store.navFlow.value.lastRoute)
            ?: core.store.chatFlow.value.currentSessionId
            ?: return
        // §B2 rev-gpt: subagent 只读——禁止在子会话重试 revert
        val sl = core.sessionListFlow.value
        val sessionsById = allSessionsById(sl.sessions, sl.directorySessions, sl.childSessions)
        val targetSession = sessionId.let { sessionsById[it] }
        if (targetSession?.parentId != null) return
        val messageId = core.store.sessionListFlow.value.sessions.firstOrNull { it.id == sessionId }
            ?.revert?.messageId
            ?: core.store.chatFlow.value.revertCutoffs[sessionId]?.messageId
            ?: return
        core.appScope.launch { revertCutoffCoordinator.ensure(sessionId, messageId, retryFailed = force) }
    }

    /**
     * §chat-list-detail §11 / G6 (B5): replay a captured [ScrollCheckpoint]
     * as a Restore scroll intent. Called by the parent route entry's
     * ChatScaffold LaunchedEffect when it consumes a checkpoint from its own
     * SavedStateHandle on re-composition (i.e. the user popped back from a
     * child). The dispatch goes through the unified [AppAction.ScrollRequested]
     * slot — the consumer in ChatMessageList sees `behavior=Restore` and
     * applies it. The `Latest` default from openForRoute (if any) is
     * superseded by this newer requestId.
     */
    fun requestScrollRestore(targetSessionId: String, checkpoint: ScrollCheckpoint) {
        val requestId = System.nanoTime()
        core.store.dispatch(
            AppAction.ScrollRequested(
                requestId = requestId,
                targetSessionId = targetSessionId,
                behavior = ScrollBehavior.Restore(checkpoint),
            )
        )
    }

    /**
     * §B2 rev-gpt MAJOR 2: [sessionId] is the route-aware target. The
     * parameterized chat/{sessionId} route passes its route id
     * (chromeSessionId) so the abort cannot drift to a lagging flat
     * currentSessionId during the nav-flip → SessionSelected window. The
     * legacy bare-chat path omits it (null) → resolves flat currentSessionId
     * exactly as before.
     */
    fun abortSession(sessionId: String? = null) {
        val sid = sessionId ?: core.store.chatFlow.value.currentSessionId ?: return
        // §P0-F/R6 阻断1: idempotent guard — no double abort while one is in flight
        // (UI also disables via isAborting, but this is the authoritative guard).
        if (sid in core.sessionListFlow.value.abortPendingSessionIds) {
            DebugLog.i("Abort", "abortSession ignored — already pending sid=$sid")
            return
        }
        // §P0-F 阻断1/2: abort token = AtomicLong 唯一序号. Watchdog verifies it is
        // STILL the current entry at fire time → rejects a stale watchdog after an
        // ABA (cleared-then-re-added) re-abort (prevents clobbering a newer abort).
        val token = abortTokenSeq.incrementAndGet()
        // §P0-F 阻断7: capture connection-bundle identity at dispatch — the
        // watchdog's REST reconcile must NOT run against a different host OR a
        // same-group endpoint/bundle reconfigure for the same sid (cross-host /
        // cross-endpoint pollution). BundleStamp (generation + endpointFp) is
        // strictly stronger than serverGroupFp (P0-A made it available on StoreState).
        val bundle = captureAbortBundle()
        core.store.mutateSessionList { s ->
            s.copy(abortPendingSessionIds = s.abortPendingSessionIds + (sid to token))
        }
        // §R18: abort is a SERVER-STATE mutation; MUST outlive the VM → appScope.
        // §P0-F 阻断1: POST and watchdog run in PARALLEL from dispatch time. A hung
        // POST must NOT delay the watchdog — previously delay() ran AFTER the POST
        // returned, so a hanging POST disabled recovery entirely.
        core.appScope.launch {
            core.repository.abortSession(sid)
                .onSuccess {
                    // 方案A：不清锁，watchdog 全权管理锁生命周期
                    // 只发轻量 UI 提示让用户知道请求已发出
                    core.effectBus.tryEmitUiEvent(
                        UiEvent.Info(R.string.chat_abort_request_sent)
                    )
                }
                .onFailure { error ->
                    core.effectBus.tryEmitUiEvent(
                        UiEvent.Error(R.string.error_abort_session_failed, listOf(errorMessageOrFallback(error, "unknown error")))
                    )
                    // §P0-F 阻断3: release the lock on POST failure so the user can
                    // retry (no permanent lock). Token-guarded → won't clobber a
                    // newer re-abort.
                    clearAbortPendingIfToken(sid, token)
                }
            // NOTE: no watchdog here — it runs in its own parallel launch below.
        }
        core.appScope.launch {
            delay(ABORT_WATCHDOG_TIMEOUT_MS)
            reconcileStaleAbort(sid, token, bundle)
        }
    }

    /** §P0-F: clear abort-pending for [sid] ONLY if its stored token == [token]
     *  (ABA-safe; a newer re-abort with a different token is left intact). */
    private fun clearAbortPendingIfToken(sid: String, token: Long) {
        core.store.mutateSessionList { s ->
            if (s.abortPendingSessionIds[sid] == token) s.copy(abortPendingSessionIds = s.abortPendingSessionIds - sid)
            else s
        }
    }

    /** §P0-F: capture the live connection-bundle identity (generation + endpointFp)
     *  for the abort watchdog's identity fence. Strictly stronger than serverGroupFp
     *  alone — catches same-group endpoint/bundle reconfigure that leaves the group
     *  fingerprint unchanged. P0-A exposes [StoreState.liveBundleGeneration] /
     *  [StoreState.liveEndpointFp] on the store. */
    internal fun captureAbortBundle(): BundleStamp =
        core.store.stateFlow.value.let { BundleStamp(it.liveBundleGeneration, it.liveEndpointFp) }

    /**
     * §P0-F watchdog body (testable — no internal delay). Guards:
     *  - #1 ABA token: act only if THIS abort (token) is still current.
     *  - #2 二次校验（严重，rev-gpt）：suspend 返回后、任何 mutation 前，重验 token +
     *    identity。fetch 期间可能落入新 abort/send 或 host 切换——suspend 前的守卫不够。
     *  - #3 identity fence（BundleStamp = liveBundleGeneration + liveEndpointFp，
     *    P0-A）：catches cross-host switch AND same-group endpoint/bundle reconfigure
     *    (strictly stronger than the prior serverGroupFp-only fence).
     * Recovery（§P0-F 收窄保守）：
     *  - 只释放 abort-pending 锁。
     *  - 不在此 writeComposer{-sid}（长 suspend 间隙会误清新 send 的 POST 桥），
     *    不在此 applySessionStatus（陈旧快照覆盖风险）。权威 status 经正常 SSE/poller
     *    通道到达；R5 generation/ownership + status-apply gate 待 P0-A。
     *  - server 仍 busy / fetch 失败 → 发恢复提示；锁已释放，用户可重试（不永久锁）。
     */
    internal suspend fun reconcileStaleAbort(sid: String, expectedToken: Long, expectedBundle: BundleStamp) {
        // §#2 初始守卫（suspend 前）。
        if (core.sessionListFlow.value.abortPendingSessionIds[sid] != expectedToken) return
        // §#3 identity fence（BundleStamp = liveBundleGeneration + liveEndpointFp，P0-A）：
        // catches cross-host switch AND same-group endpoint/bundle reconfigure.
        if (captureAbortBundle() != expectedBundle) {
            clearAbortPendingIfToken(sid, expectedToken)
            return
        }
        val fetchResult = core.repository.getSessionStatus()  // ── SUSPEND POINT ──
        // §#2 二次校验（严重，rev-gpt）：suspend 返回后、任何 mutation 前，重验 token +
        // identity。fetch 期间可能落入新 abort/send 或 host 切换——suspend 前的守卫不够。
        if (core.sessionListFlow.value.abortPendingSessionIds[sid] != expectedToken) return
        if (captureAbortBundle() != expectedBundle) {
            clearAbortPendingIfToken(sid, expectedToken)
            return
        }
        val settled = if (fetchResult.isFailure) {
            false
        } else {
            fetchResult.getOrNull()?.get(sid)?.let { !it.isBusy && !it.isRetry } ?: true
        }
        // §#2/#6（收窄保守）：只释放 abort-pending 锁。
        // 不在此 writeComposer{-sid}（长 suspend 间隙会误清新 send 的 POST 桥），
        // 不在此 applySessionStatus（陈旧快照覆盖风险）。权威 status 经正常 SSE/poller
        // 通道到达；R5 generation/ownership + status-apply gate 待 P0-A。
        clearAbortPendingIfToken(sid, expectedToken)
        if (!settled) {
            // §P0-F 有限恢复：server 仍 busy / fetch 失败 → 发恢复提示；锁已释放，
            // 用户可重试（不永久锁）。
            core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_abort_pending_stuck))
        }
    }

    /**
     * 递归强制中止：深度优先 post-order——先中止全部子会话，最后中止父会话。
     * 使用本地 subtreeIds 枚举全树（O(N)，带环检测），冷启动时 fallback 到
     * 递归 getChildren 补全。单一 appScope.launch orchestration，不挂逐节点 watchdog。
     */
    fun abortSessionRecursive(sessionId: String? = null) {
        val sid = sessionId ?: core.store.chatFlow.value.currentSessionId ?: return

        // operation-level 幂等守卫：防止连点
        if (sid in core.sessionListFlow.value.abortPendingSessionIds) {
            DebugLog.i("Abort", "abortSessionRecursive ignored — already pending sid=$sid")
            return
        }

        // 捕获连接身份（BundleStamp）
        val bundle = captureAbortBundle()
        val token = abortTokenSeq.incrementAndGet()
        val maxNodes = 50

        // 登记锁（root-only）
        core.store.mutateSessionList { s ->
            s.copy(abortPendingSessionIds = s.abortPendingSessionIds + (sid to token))
        }

        core.appScope.launch {
            abortSessionRecursiveInternal(sid, token, bundle, maxNodes)
        }
        // 单个 operation-level watchdog：超时覆盖 fetch(10s/node) + abort(15s/node) 预算。
        // 递归完成时已主动清锁，watchdog 仅作兜底。
        core.appScope.launch {
            delay(ABORT_WATCHDOG_TIMEOUT_MS + maxNodes * (15_000L + 10_000L))
            reconcileStaleAbort(sid, token, bundle)
        }
    }

    private suspend fun abortSessionRecursiveInternal(
        rootId: String,
        token: Long,
        bundle: BundleStamp,
        maxNodes: Int,
    ) {
        // 1. 本地枚举子树
        val sl = core.sessionListFlow.value
        var subtreeIds = subtreeIds(rootId, sl.sessions, sl.directorySessions, sl.childSessions).toList()

        // 2. 冷启动 fallback：如果本地子树只有 root 自己，尝试 getChildren 补全。
        //    fetchSubtreeRecursive 使用独立的 visited 集合，不与主循环共享。
        if (subtreeIds.size <= 1) {
            val fetched = fetchSubtreeRecursive(rootId, maxNodes)
            subtreeIds = (subtreeIds + fetched).distinct()
        }

        // 3. 确认连接身份（suspend 前校验）
        if (captureAbortBundle() != bundle) {
            clearAbortPendingIfToken(rootId, token)
            return
        }

        // 4. post-order：treeIds 是前序 DFS（父先于子），reversed 保证子孙先于祖先
        //    （rootId 在最后）。按 maxNodes 截断迭代上限（含失败节点），确保 root 必达。
        val allOrdered = subtreeIds.reversed().filter { it != rootId } + listOf(rootId)
        val ordered = if (allOrdered.size > maxNodes) allOrdered.take(maxNodes - 1) + listOf(rootId) else allOrdered

        for (id in ordered) {
            // 每次迭代前验证 token 仍属于当前递归操作——锁被清除/替换后立即停止，
            // 防止旧协程继续 abort 新启动的运行。
            if (core.sessionListFlow.value.abortPendingSessionIds[rootId] != token) {
                DebugLog.w("Abort", "abortSessionRecursive: token superseded, stopping")
                break
            }
            try {
                withTimeout(15_000L) {
                    core.repository.abortSession(id)
                }
            } catch (e: TimeoutCancellationException) {
                DebugLog.w("Abort", "abortSessionRecursive: timeout for $id")
            } catch (e: CancellationException) {
                throw e  // 不吞 scope 取消
            } catch (e: Exception) {
                // best-effort：子失败不阻断后续
                DebugLog.w("Abort", "abortSessionRecursive: failed for $id: ${e.message}")
            }

            // suspend 后校验连接身份
            if (captureAbortBundle() != bundle) {
                DebugLog.w("Abort", "abortSessionRecursive: bundle changed, aborting remaining")
                break
            }
        }

        // 5. 递归完成后主动清锁（不依赖 watchdog）
        clearAbortPendingIfToken(rootId, token)
    }

    /**
     * 递归 getChildren 补全深层子节点（冷启动 fallback）。
     * 带环检测和最大深度。
     */
    private suspend fun fetchSubtreeRecursive(
        rootId: String,
        maxNodes: Int,
    ): List<String> {
        val result = mutableListOf<String>()
        val visited = HashSet<String>()  // 独立的环检测集合，不与主循环共享
        val queue = ArrayDeque<String>()
        queue.add(rootId)

        while (queue.isNotEmpty() && result.size < maxNodes) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue  // 环检测
            if (current != rootId) result.add(current)

            try {
                withTimeout(10_000L) {
                    val children = core.repository.getChildren(current).getOrDefault(emptyList())
                    children.forEach { child ->
                        if (child.id !in visited) queue.add(child.id)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                DebugLog.w("Abort", "fetchSubtreeRecursive: timeout for $current")
            } catch (e: CancellationException) {
                throw e  // 不吞 scope 取消
            } catch (e: Exception) {
                DebugLog.w("Abort", "fetchSubtreeRecursive: getChildren failed for $current: ${e.message}")
            }
        }

        return result
    }

    /**
     * §B2 rev-gpt MAJOR 1: [sessionId] is the route-aware target. The
     * parameterized chat/{sessionId} route passes its route id
     * (chromeSessionId) so the stale-notice refresh cannot drift to a lagging
     * flat currentSessionId during the nav-flip → SessionSelected window. The
     * legacy bare-chat path omits it (null) → resolves flat currentSessionId
     * exactly as before.
     */
    fun refreshCurrentSession(sessionId: String? = null) {
        val sid = sessionId ?: core.store.chatFlow.value.currentSessionId ?: return
        // §sse-rest-fallback (TODO 2): the staleNotice snackbar = "messages may
        // be stale" → treat as an SSE-disconnect RECOVERY: clear+UNANCHORED
        // re-fetch (forceInitialWindow=true, same ①②③ as performForceRefresh) so
        // a stale slim watermark cannot leave the just-cleared window empty. The
        // isLoading guard now lives inside performGlobalColdStartRefresh
        // (explicit=true → surfaces an Info feedback instead of a silent no-op);
        // ⑤ LoadSessions + the ④ health probe run ONLY when the clear+reload
        // actually happened (refreshed=true), so a suppressed refresh is not a
        // misleading partial action. The success toast fires when the reload +
        // probe both settled cleanly.
        val refreshed = core.performGlobalColdStartRefresh(
            currentId = sid,
            forceInitialWindow = true,
            explicit = true,
        )
        if (!refreshed) return
        core.effectBus.tryEmitEffect(ControllerEffect.LoadSessions)
        // 与 performForceRefresh / coldStartReconnect 对齐 retries=3：banner Refresh 走本入口，
        // 单次探测（retries=0）在网络抖动（DNS/连接中断）下必败，导致 banner 无法靠 banner
        // 刷新清除。retries=3 扛过抖动期（engine 退避 2s/5s/15s）。
        core.connectionCoordinator.testConnection(force = true, retries = 3, onSettled = { ok ->
            // §stale-session-guard (MINOR 2): suppress success toast when the
            // user has switched sessions during the retry window (retries=3 →
            // up to ~22s). The onSettled callback is captured at lambda creation
            // time; if currentSessionId no longer matches sid (captured at
            // refresh initiation), the feedback would mislead on the wrong session.
            if (ok && core.store.chatFlow.value.currentSessionId == sid
                && !core.store.chatFlow.value.isLoadingMessages
                && !core.store.chatFlow.value.isLoadingMoreMessages
            ) {
                core.effectBus.tryEmitUiEvent(UiEvent.Success(R.string.success_refreshed))
            }
        })
    }

    fun reconcilePendingQuestions() {
        core.effectBus.tryEmitEffect(ControllerEffect.LoadPendingQuestions)
    }

    fun togglePartExpand(key: String, currentValue: Boolean) {
        core.composerController.togglePartExpand(key, currentValue)
    }

    /** §R-17 batch3d: routes to the composer controller that owns expandedParts. */
    fun clearExpandedParts() {
        core.composerController.clearExpandedParts()
    }

    /**
     * §slimapi-client-v1 §G6 (Task 16 round-2): dispatches the G6 batch-full
     * expand for the tapped message's eligible parts.
     *
     * # C1 — compound identity gate + part-level compare-and-merge
     *
     * Request identity is `(serverGroupFp, sessionId)`, matching the existing
     * captured-provider pattern at [loadMessages]. Completion applies only
     * targeted part patches to the current cache — NEVER replaces the whole
     * `partsByMessage` or `messages` list.
     *
     * # Dispatch-time sequence
     *
     *  1. Capture `serverGroupFp` once.
     *  2. Read `chatFlow.value` once into `startState` (no repeated reads).
     *  3. Abort unless `startState.currentSessionId == sessionId`.
     *  4. Derive eligible keys and exclude keys already `Loading`.
     *  5. Build `local` entirely from `startState.messages` + `startState.partsByMessage`.
     *  6. In one `writeChat` reducer: recheck session + set Loading.
     *  7. Invoke [ExpandPartsUseCase.expandParts].
     *
     * # Completion-time identity check
     *
     *  1. Before state write: drop if `serverGroupFp` differs.
     *  2. Inside reducer: `if current.currentSessionId != requestedSessionId → return current`.
     *  3. Process only outcome keys still `Loading` (prevents delayed duplicate).
     *  4. For Loaded candidates: reconcile the raw fetched owner message
     *     (`outcome.fetchedItems`) into the CURRENT `partsByMessage` via a
     *     per-owner merge (replace-by-id + thin_placeholder removal +
     *     skeleton-drift cleanup). Never write `mergedLocal` wholesale.
     *  5. Mark a key `Loaded` only when that reconciliation placed the
     *     fetched content (or it was already full); otherwise `Failed(null)`
     *     keeps retry visible.
     *  6. Commit cache + terminal states in the same atomic `writeChat`.
     *
     * CE discipline: [runSuspendCatching] in [ExpandPartsUseCase] ensures
     * CancellationException propagates. Both success and failure paths are
     * guarded by identity checks.
     *
     * @param sessionId the active session id.
     * @param parts ALL parts of the tapped message (the usecase filters to
     *   eligible ones internally).
     */
    fun expandParts(sessionId: String, parts: List<cn.vectory.ocdroid.data.model.Part>) {
        // P4: capture host identity ONCE (no TOCTOU).
        val capturedFp = core.currentProfileId()
        // Capture route instance + connection epoch at invocation time. Completion
        // must validate these captured values; re-reading either one would let an
        // old response be accepted under a newer host/client generation.
        val capturedRouteInstance = core.store.slices.routeInstanceFor(sessionId)
        // §B3-retirement: ConnectionCapture replaces the retired slim-token shim.
        val capturedConnection = core.repository.captureConnection()

        viewModelScope.launch {
            // Step 2: single-read dispatch state (Main dispatcher — no suspension).
            val startState = core.store.chatFlow.value

            // Step 3: session guard.
            if (startState.currentSessionId != sessionId) return@launch

            // Step 4: derive eligible keys from the supplied row.
            val eligibleKeys = parts
                .filter { it.hasFull == true && it.omitted != null && it.messageId != null }
                .map { cn.vectory.ocdroid.ui.chat.PartKey(it.messageId!!, it.id) }
            if (eligibleKeys.isEmpty()) return@launch

            // Step 5: suppress duplicate requests for keys already Loading.
            val keysToLoad = eligibleKeys.filter { key ->
                startState.partExpandStates[key] !is cn.vectory.ocdroid.ui.chat.PartExpandState.Loading
            }
            if (keysToLoad.isEmpty()) return@launch

            // P3: send only newly claimed parts to T15.
            val keysToLoadSet = keysToLoad.toHashSet()
            val partsToLoad = parts.filter { part ->
                part.hasFull == true &&
                    part.omitted != null &&
                    part.messageId != null &&
                    cn.vectory.ocdroid.ui.chat.PartKey(
                        messageId = part.messageId,
                        partId = part.id,
                    ) in keysToLoadSet
            }
            if (partsToLoad.isEmpty()) return@launch

            // Step 6: build local from startState (single-read snapshot).
            val local = startState.partsByMessage.entries
                .mapNotNull { (msgId, msgParts) ->
                    val msg = startState.messages.firstOrNull { it.id == msgId }
                    if (msg != null) {
                        cn.vectory.ocdroid.data.model.MessageWithParts(info = msg, parts = msgParts)
                    } else {
                        null
                    }
                }

            // P4: set Loading in one atomic commit — recheck each key in CAS.
            core.writeChat { current ->
                if (current.currentSessionId != sessionId) return@writeChat current
                if (core.currentProfileId() != capturedFp) return@writeChat current

                val loadingUpdates = keysToLoad
                    .filter { key ->
                        (current.partExpandStates[key]
                            !is cn.vectory.ocdroid.ui.chat.PartExpandState.Loading)
                    }
                    .associateWith {
                        cn.vectory.ocdroid.ui.chat.PartExpandState.Loading
                    }

                if (loadingUpdates.isEmpty()) {
                    current
                } else {
                    current.copy(
                        partExpandStates = current.partExpandStates + loadingUpdates,
                    )
                }
            }

            // P4: abort if identity changed during dispatch (before network call).
            if (core.store.chatFlow.value.currentSessionId != sessionId) return@launch
            if (core.currentProfileId() != capturedFp) return@launch
            if (!core.repository.isConnectionCaptureCurrent(capturedConnection)) return@launch

            // Step 8: invoke usecase (non-mutating, CE discipline).
            val useCase = cn.vectory.ocdroid.ui.chat.ExpandPartsUseCase(core.repository)
            val outcome = useCase.expandParts(
                sessionId = sessionId,
                local = local,
                parts = partsToLoad,
            ).getOrElse {
                // Diagnostic: usecase threw (NOT a normal Failed outcome —
                // runSuspendCatching collapsed something unexpected, e.g.
                // CancellationException outside the usecase's own runSuspend
                // Catching, or an OOM/Json bug inside foldOk). Log exception
                // type + message + the wire keys we were about to load so a
                // "展开失败 (Failed null)" report can be distinguished from
                // the foldOk Branch 0/A/B/C paths (which do NOT throw).
                DebugLog.w(
                    TAG,
                    "expand usecase threw sessionId=$sessionId " +
                        "keys=${keysToLoad.take(20)} " +
                        "cause=${it.javaClass.simpleName}: ${it.message}",
                )
                // P2: guard delayed failure — only mark keys still Loading.
                core.writeChat { current ->
                    if (current.currentSessionId != sessionId) return@writeChat current
                    if (core.currentProfileId() != capturedFp) return@writeChat current
                    if (!core.repository.isConnectionCaptureCurrent(capturedConnection)) return@writeChat current

                    val updatedStates = current.partExpandStates.toMutableMap()
                    keysToLoad.forEach { key ->
                        if (current.partExpandStates[key]
                            is cn.vectory.ocdroid.ui.chat.PartExpandState.Loading
                        ) {
                            updatedStates[key] =
                                cn.vectory.ocdroid.ui.chat.PartExpandState.Failed(code = null)
                        }
                    }

                    current.copy(partExpandStates = updatedStates)
                }
                return@launch
            }

            // ── P1: completion commit (T1b ExpandedParts CAS fix / Strategy 2) ─
            // fp guard stays at the call site (reducer purity — no host-fp
            // access). Merge runs inside reduce via
            // ChatState.reconcileExpandedPartsContent against the LATEST chat
            // (state.update CAS loop), so concurrent SSE updates to other
            // owners are preserved — restores pre-Strategy-1 writeChat CAS.
            if (core.currentProfileId() != capturedFp) return@launch
            if (!core.repository.isConnectionCaptureCurrent(capturedConnection)) return@launch
            core.store.dispatch(
                AppAction.ExpandedPartsContentCommitted(
                    outcome = outcome,
                    local = local,
                    expectedSessionId = sessionId,
                    expectedRouteInstance = capturedRouteInstance,
                )
            )
        }
    }

    /**
     * §Wave5b-Q13: clear the active scroll intent (compare-and-clear by
     * requestId). Called by [cn.vectory.ocdroid.ui.chat.ChatMessageList]'s
     * LaunchedEffect AFTER it has performed the scroll (Latest or Restore) for
     * the pending request, so the intent fires exactly once per switch.
     *
     * If a newer request has superseded [requestId] between the consumer's
     * observe and clear (fast A→B→C cascade), the reducer's compare guard
     * drops this clear silently — the newer intent survives for its own
     * consumer.
     *
     * Replaces the pre-Wave5b `clearPendingJumpToLatest` (which was an
     * unconditional clear of the `pendingJumpToLatest: String?` slot).
     */
    fun consumeScrollRequest(requestId: Long) {
        core.store.dispatch(AppAction.ScrollConsumed(requestId))
    }

    /** Cross-domain: composer→chat→session creation lives in [AppCore]. */
    fun sendMessage() = core.sendMessage()

    /** Test-only visibility into the session-window cache size. */
    internal fun sessionWindowCacheSize(): Int = core.sessionSwitcher.sessionWindowCacheSize()
    internal fun peekSessionWindow(sessionId: String) = core.sessionSwitcher.peekSessionWindow(sessionId)
}
