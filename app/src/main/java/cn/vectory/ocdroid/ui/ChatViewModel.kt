package cn.vectory.ocdroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.isThinPlaceholder
import cn.vectory.ocdroid.util.DebugLog
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
        val capturedFp = core.currentServerGroupFp()

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
                if (core.currentServerGroupFp() != capturedFp) return@writeChat current

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
            if (core.currentServerGroupFp() != capturedFp) return@launch

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
                    if (core.currentServerGroupFp() != capturedFp) return@writeChat current

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

            // ── Owner resolution (captured local — mirrors foldOk) ──────────
            // The completion reducer reconciles fetched content into the
            // CURRENT live partsByMessage. Owner identity must match what
            // foldOk used so the Loaded candidate and the live write agree.
            val localByInfoId: Map<String, cn.vectory.ocdroid.data.model.MessageWithParts> =
                local.associateBy { it.info.id }
            val localOwnerByPartId: Map<String, String> = buildMap {
                local.forEach { lm -> lm.parts.forEach { p -> put(p.id, lm.info.id) } }
            }

            // ── P1: completion reducer (guarded atomic per-key) ───────────
            // A per-part patch miss is not sufficient evidence of success.
            // The reducer reconciles the raw fetched owner message into the
            // CURRENT live parts; mark Loaded only when that reconciliation
            // placed the fetched content or it was already present, otherwise
            // keep retry visible.
            core.writeChat { current ->
                // Compound identity guard against the exact CAS input.
                if (current.currentSessionId != sessionId) return@writeChat current
                if (core.currentServerGroupFp() != capturedFp) return@writeChat current

                var updatedPartsByMessage = current.partsByMessage
                val updatedStates = current.partExpandStates.toMutableMap()

                // Decides the terminal for a Loaded candidate whose live-slice
                // merge could NOT be performed: Loaded only if the current
                // target part is already a resolved (non-skeleton) part
                // (concurrent expand/SSE resolved it); otherwise Failed(null)
                // so retry stays visible. `ownerOverride` short-circuits owner
                // re-resolution when the caller already knows it.
                fun alreadyFullOrFailed(
                    key: cn.vectory.ocdroid.ui.chat.PartKey,
                    ownerOverride: String?,
                ): cn.vectory.ocdroid.ui.chat.PartExpandState {
                    val oid = ownerOverride
                        ?: localByInfoId[key.messageId]?.info?.id
                        ?: localOwnerByPartId[key.partId]
                        ?: return cn.vectory.ocdroid.ui.chat.PartExpandState.Failed(code = null)
                    val parts = current.partsByMessage[oid]
                        ?: return cn.vectory.ocdroid.ui.chat.PartExpandState.Failed(code = null)
                    val target = parts.firstOrNull { it.id == key.partId }
                        ?: return cn.vectory.ocdroid.ui.chat.PartExpandState.Failed(code = null)
                    // alreadyFull: target no longer carries the skeleton
                    // markers (hasFull != true OR omitted == null).
                    val alreadyFull = target.hasFull != true || target.omitted == null
                    return if (alreadyFull) {
                        cn.vectory.ocdroid.ui.chat.PartExpandState.Loaded
                    } else {
                        cn.vectory.ocdroid.ui.chat.PartExpandState.Failed(code = null)
                    }
                }

                // Only Loaded-outcome keys still Loading (CAS) can reconcile
                // content. Group them by resolved owner so each owner is
                // merged ONCE, then all its Loaded keys are finalised.
                val activeLoadedKeys: List<cn.vectory.ocdroid.ui.chat.PartKey> =
                    outcome.states.entries
                        .filter { (key, state) ->
                            state is cn.vectory.ocdroid.ui.chat.PartExpandState.Loaded &&
                                (current.partExpandStates[key]
                                    is cn.vectory.ocdroid.ui.chat.PartExpandState.Loading)
                        }
                        .map { it.key }

                val keysByOwner: Map<String?, List<cn.vectory.ocdroid.ui.chat.PartKey>> =
                    activeLoadedKeys.groupBy { key ->
                        localByInfoId[key.messageId]?.info?.id
                            ?: localOwnerByPartId[key.partId]
                    }

                // Per-key terminal decision for Loaded candidates.
                val loadedTerminal =
                    HashMap<cn.vectory.ocdroid.ui.chat.PartKey, cn.vectory.ocdroid.ui.chat.PartExpandState>()
                // Owners whose live-slice merge succeeded → write once each.
                val ownerMergedParts =
                    HashMap<String, List<cn.vectory.ocdroid.data.model.Part>>()

                keysByOwner.forEach { (ownerId, keys) ->
                    if (ownerId == null) {
                        // Cannot establish an owner — cannot reconcile. Keep
                        // retry visible unless the target is already full.
                        keys.forEach { key ->
                            loadedTerminal[key] = alreadyFullOrFailed(key, ownerOverride = null)
                        }
                        return@forEach
                    }
                    val fetchedOwner = outcome.fetchedItems
                        ?.firstOrNull { it.info.id == ownerId }
                    val currentParts = current.partsByMessage[ownerId]
                    if (fetchedOwner == null || currentParts == null) {
                        // Owner/fetched/live-slice missing at commit time —
                        // cannot reconcile. Keep retry visible unless the
                        // target is already full (concurrent resolve).
                        keys.forEach { key ->
                            loadedTerminal[key] = alreadyFullOrFailed(key, ownerOverride = ownerId)
                        }
                        return@forEach
                    }
                    // §content-loss-guard: a fetch that returns the owner
                    // message with NO parts carries no usable content and
                    // cannot have resolved any omitted target. Retain the
                    // skeleton markers and keep retry visible (unless a
                    // concurrent op already resolved the target). Marking
                    // Loaded here would strip the skeleton and hide the
                    // affordance with nothing committed — the v2 content-
                    // loss edge. A non-empty full-message fetch is treated
                    // as authoritative for all of the owner's omitted
                    // content (the server returns the complete message).
                    if (fetchedOwner.parts.isEmpty()) {
                        keys.forEach { key ->
                            loadedTerminal[key] = alreadyFullOrFailed(key, ownerOverride = ownerId)
                        }
                        return@forEach
                    }
                    // Live-slice merge of the raw fetched owner message.
                    val fetchedIds: HashSet<String> =
                        fetchedOwner.parts.mapTo(HashSet()) { it.id }
                    val merged = currentParts.toMutableList()
                    // 1. replace-by-id (append new fetched parts).
                    fetchedOwner.parts.forEach { fp ->
                        val idx = merged.indexOfFirst { cp -> cp.id == fp.id }
                        if (idx >= 0) merged[idx] = fp else merged.add(fp)
                    }
                    // 2. a successful expand resolves synthetic placeholders.
                    merged.removeAll { cp -> cp.isThinPlaceholder() }
                    // 3. drop each captured skeleton target that is still an
                    //    unresolved omitted marker AND whose id did not come
                    //    back in the fetched parts (part-id drift cleanup).
                    keys.forEach { key ->
                        merged.removeAll { cp ->
                            cp.id == key.partId &&
                                cp.id !in fetchedIds &&
                                cp.hasFull == true &&
                                cp.omitted != null
                        }
                    }
                    ownerMergedParts[ownerId] = merged.toList()
                    keys.forEach { key ->
                        loadedTerminal[key] = cn.vectory.ocdroid.ui.chat.PartExpandState.Loaded
                    }
                }

                // Write each successfully-merged owner once.
                ownerMergedParts.forEach { (ownerId, parts) ->
                    updatedPartsByMessage = updatedPartsByMessage + (ownerId to parts)
                }

                // Apply terminal states for every outcome key (CAS-filtered).
                outcome.states.forEach { (wireKey, outcomeState) ->
                    // P1: this old completion owns neither cache nor state
                    // once the key has left Loading. Skip completely.
                    if (current.partExpandStates[wireKey]
                        !is cn.vectory.ocdroid.ui.chat.PartExpandState.Loading
                    ) {
                        return@forEach
                    }

                    updatedStates[wireKey] = when (outcomeState) {
                        cn.vectory.ocdroid.ui.chat.PartExpandState.Loaded ->
                            // Reconciliation decision: Loaded only if the merge
                            // placed the content or it was already full;
                            // otherwise Failed(null) keeps retry visible.
                            loadedTerminal[wireKey]
                                ?: cn.vectory.ocdroid.ui.chat.PartExpandState.Failed(code = null)

                        is cn.vectory.ocdroid.ui.chat.PartExpandState.Failed -> {
                            outcomeState
                        }

                        // ExpandPartsUseCase contract says outcomes are terminal.
                        cn.vectory.ocdroid.ui.chat.PartExpandState.Idle,
                        cn.vectory.ocdroid.ui.chat.PartExpandState.Loading,
                        cn.vectory.ocdroid.ui.chat.PartExpandState.Exhausted -> {
                            cn.vectory.ocdroid.ui.chat.PartExpandState.Failed(code = null)
                        }
                    }
                }

                current.copy(
                    partsByMessage = updatedPartsByMessage,
                    partExpandStates = updatedStates,
                )
            }
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
