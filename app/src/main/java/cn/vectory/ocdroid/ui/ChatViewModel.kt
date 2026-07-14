package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
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
        val fp = core.currentServerGroupFp()
        launchLoadMessages(
            scope = core.appScope,
            repository = core.repository,
            slices = core.store.slices,
            sessionId = sessionId,
            resetLimit = resetLimit,
            settingsManager = core.settingsManager,
            onCacheWindow = core.makeCacheHook(fp),
            emit = EventEmitter { event -> core.effectBus.tryEmitUiEvent(event) },
            // gpter 复审 final-fix: compound-key guard.
            expectedServerGroupFp = fp,
            currentServerGroupFp = core.currentServerGroupFp,
        )
    }

    fun loadMessages(sessionId: String) = loadMessages(sessionId, resetLimit = true)

    internal fun loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessagesWithRetry(core.appScope, sessionId, core.store.slices, resetLimit, ::loadMessages)
    }

    fun loadMoreMessages() {
        val sessionId = core.store.chatFlow.value.currentSessionId ?: return
        // glm-3 🟡#1 / gpter 复审 final-fix: single-read fp.
        val fp = core.currentServerGroupFp()
        launchLoadMoreMessages(
            scope = core.appScope,
            repository = core.repository,
            slices = core.store.slices,
            sessionId = sessionId,
            onCacheWindow = core.makeCacheHook(fp),
            // gpter 复审 final-fix: compound-key guard.
            expectedServerGroupFp = fp,
            currentServerGroupFp = core.currentServerGroupFp,
        )
    }

    /**
     * R-20 Phase 2: fill (or resume) a specific gap marker. Triggered by the
     * UI tap on a [cn.vectory.ocdroid.ui.chat.Entry.GapMarker] divider. Routes
     * to [cn.vectory.ocdroid.ui.chat.GapFillCoordinator.fillSingleGap] which
     * runs the 50-step backward fill under the session-level Mutex.
     *
     * **Replaces** the legacy `closeGap()` → `launchCloseGap` path (plan §3 N5).
     */
    fun fillGap(gapId: String) {
        val sessionId = core.store.chatFlow.value.currentSessionId ?: return
        val fp = core.currentServerGroupFp()
        core.appScope.launch {
            core.gapFillCoordinator.fillSingleGap(
                slices = core.store.slices,
                serverGroupFp = fp,
                sessionId = sessionId,
                gapId = gapId,
                onCacheWindow = core.makeCacheHook(fp),
            )
        }
    }

    fun compactSession() {
        val chatFlow = core.store.chatFlow
        if (chatFlow.value.isCompacting) return
        val sessionId = chatFlow.value.currentSessionId ?: run {
            core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_compact_no_session))
            return
        }
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
        val sessionId = chatFlow.value.currentSessionId ?: return
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
        val sessionId = core.store.chatFlow.value.currentSessionId ?: return
        val messageId = core.store.sessionListFlow.value.sessions.firstOrNull { it.id == sessionId }
            ?.revert?.messageId
            ?: core.store.chatFlow.value.revertCutoffs[sessionId]?.messageId
            ?: return
        core.appScope.launch { revertCutoffCoordinator.ensure(sessionId, messageId, retryFailed = force) }
    }

    fun abortSession() {
        val sessionId = core.store.chatFlow.value.currentSessionId ?: return
        // §R18 Phase 3 Wave 2 + Gate-3 fix (maxer): abort is a SERVER-STATE
        // mutation (POST /session/{id}/abort), not an ephemeral UI action. It
        // MUST outlive the VM — if the user backgrounds the app / navigates
        // away while the abort request is in flight, viewModelScope.cancel()
        // would cancel the HTTP call, the server never receives the abort, and
        // keeps streaming. Use appScope so the request completes regardless of
        // VM lifecycle. (Closure captures only core.repository + core.effectBus,
        // no VM self-ref → no P1-7 leak.)
        core.appScope.launch {
            core.repository.abortSession(sessionId)
                .onFailure { error ->
                    core.effectBus.tryEmitUiEvent(UiEvent.Error(R.string.error_abort_session_failed, listOf(errorMessageOrFallback(error, "unknown error"))))
                }
        }
    }

    fun refreshCurrentSession() {
        val sessionId = core.store.chatFlow.value.currentSessionId ?: return
        // §history-load-fix: guard against both load flags (see
        // performGlobalColdStartRefresh). A user loadMore in flight must also
        // block a manual refresh.
        if (core.store.chatFlow.value.isLoadingMessages || core.store.chatFlow.value.isLoadingMoreMessages) return
        core.performGlobalColdStartRefresh(currentId = sessionId)
        core.connectionCoordinator.testConnection(force = true, onSettled = { ok ->
            if (ok && !core.store.chatFlow.value.isLoadingMessages && !core.store.chatFlow.value.isLoadingMoreMessages) {
                core.effectBus.tryEmitUiEvent(UiEvent.Success(R.string.success_refreshed))
            }
        })
    }

    fun togglePartExpand(key: String, currentValue: Boolean) {
        core.composerController.togglePartExpand(key, currentValue)
    }

    /** §R-17 batch3d: routes to the composer controller that owns expandedParts. */
    fun clearExpandedParts() {
        core.composerController.clearExpandedParts()
    }

    /**
     * §WT2-taskB (Q6 locked): clear the "Sessions page entry → jump to latest"
     * intent. Called by [cn.vectory.ocdroid.ui.chat.ChatMessageList]'s
     * LaunchedEffect AFTER it has performed the scrollToItem(0) jump for the
     * pending session, so the intent fires exactly once per entry (does not
     * re-fire on recomposition / preview return for the same session).
     */
    fun clearPendingJumpToLatest() {
        core.store.dispatch(AppAction.PendingJumpToLatestSet(null))
    }

    /** Cross-domain: composer→chat→session creation lives in [AppCore]. */
    fun sendMessage() = core.sendMessage()

    /** Test-only visibility into the session-window cache size. */
    internal fun sessionWindowCacheSize(): Int = core.sessionSwitcher.sessionWindowCacheSize()
    internal fun peekSessionWindow(sessionId: String) = core.sessionSwitcher.peekSessionWindow(sessionId)
}
