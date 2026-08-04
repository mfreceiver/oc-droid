package cn.vectory.ocdroid.ui

import androidx.annotation.MainThread
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.ui.controller.ComposerController
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * §Wave2.1-split-l2: draft-session materialization orchestrator.
 * Owns [materializeDraftSession] and its private helpers (captureDraftRouteOrigin,
 * adoptMaterializedSessionRoute, materializeListOnly, startMaterializedRouteHydration).
 *
 * CRITICAL: the A–E post-CAS ordering (materializeDraftSession lines after
 * `if (adoption.adopted)`) is a correctness invariant — do NOT reorder or
 * parallelize the steps.
 *
 * Depends on: core-five + composerController + SendOrchestrator + RefreshOrchestrator.
 *
 * ~330 LOC extracted from AppCoreOrchestration.kt.
 */
@Singleton
internal class DraftSessionOrchestrator @Inject constructor(
    private val store: SharedStateStore,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val effectBus: SharedEffectBus,
    @UiApplicationScope private val appScope: CoroutineScope,
    @Named("currentProfileId") private val currentProfileId: () -> String,
    private val composerController: ComposerController,
    private val sessionSwitcher: SessionSwitcher,
    private val sendOrchestrator: SendOrchestrator,
    private val refreshOrchestrator: RefreshOrchestrator,
) {

    /**
     * §unified-nav A5.5: shared entry called by AppCore.sendMessage() when the
     * user is in draft mode (draftWorkdir != null, no current session). Captures
     * the send payload and delegates to [materializeDraftSession].
     */
    @MainThread
    fun sendMessageViaDraft() {
        val chatState = store.chatFlow.value
        val visibleAgents = store.settingsFlow.value.agents
            .filter { it.isVisible }
            .map { it.name }
            .toSet()
        val payload = CapturedSendPayload(
            text = store.composerFlow.value.inputText.trim(),
            attachments = store.composerFlow.value.imageAttachments.toList(),
            agent = chatState.pendingAgent ?: inferCurrentAgent(chatState.messages, visibleAgents),
            model = chatState.pendingModel ?: inferCurrentModel(chatState.messages, visibleAgents),
            fileReferences = store.composerFlow.value.fileReferences.toList(),
        )
        materializeDraftSession(capturedPayload = payload)
    }

    /**
     * §bug2 → §unified-nav A5: Shared draft-session materialization. Detects draft
     * mode (composer has a draftWorkdir but no current session yet), captures the
     * [DraftRouteOrigin] snapshot, clears the draft, creates a new session, then
     * SYNCHRONOUSLY adopts the route via [adoptMaterializedSessionRoute] (the
     * aggregate CAS that fixes item 10-A), runs the post-CAS ordering (A-E), and
     * finally dispatches the captured send payload OR the command.
     *
     * Exactly ONE of [capturedPayload] / [commandPost] is non-null:
     *  - [capturedPayload] (send path from [sendMessage]): step D runs
     *    [SendOrchestrator.dispatchCapturedSend].
     *  - [commandPost] (command path from [CommandOrchestrator]): step D runs
     *    the suspend command callback on appScope.
     *
     * [capturedCommandText] is the raw composer inputText at command-click time
     * (command path only). On adoption SUCCESS, the composer inputText is cleared
     * ONLY if its current value still equals [capturedCommandText] (compare-and-
     * clear — the user may have typed new content during the createSession suspend
     * boundary). If the user typed more, the newer content is PRESERVED.
     *
     * # Adoption success (routeInstance != null) — post-CAS strict ordering:
     *  A. settingsManager.lastRoute = "chat/$sid"   (persistence side-effect)
     *  B. persistSessionCache(...)
     *  C. startMaterializedRouteHydration(adoption)  (DIRECT call, NOT effect bus)
     *  D. dispatchCapturedSend(sid, payload, token)  OR  commandPost(sid)
     *  E. scheduleTitleRefreshAfterFirstMessage(sid)
     *
     * # Adoption failed (user navigated away mid-create):
     *  - do NOT set route/nav/content (the CAS was list-only);
     *  - still POST the captured payload as a background send for the new sid
     *    (do NOT hijack the current UI);
     *  - draftWorkdir is NOT restored (the user left the draft surface — that is
     *    WHY adoption failed).
     *
     * # createSession failure:
     *  - emit UiEvent.Error;
     *  - restore draftWorkdir WITH ownership guard (only if still on the draft
     *    surface — otherwise the user navigated away and the draft is gone).
     */
    @MainThread
    fun materializeDraftSession(
        capturedPayload: CapturedSendPayload? = null,
        capturedCommandText: String? = null,
        commandPost: (suspend (sessionId: String) -> Unit)? = null,
    ) {
        val draftWorkdir = store.composerFlow.value.draftWorkdir ?: return
        val origin = captureDraftRouteOrigin()
        store.mutateComposer { it.copy(draftWorkdir = null) }
        appScope.launch {
            repository.createSession(title = null, directory = draftWorkdir)
                .onSuccess { session ->
                    val now = System.currentTimeMillis()
                    val adoption = adoptMaterializedSessionRoute(session, now, origin)
                    if (adoption.adopted) {
                        val token = adoption.routeInstance!!
                        // A. persistence side-effect.
                        settingsManager.lastRoute = "chat/${session.id}"
                        // B. persist the session cache.
                        persistSessionCache(
                            settingsManager = settingsManager,
                            sessions = store.sessionListFlow.value.sessions,
                            currentId = session.id,
                            currentWorkdir = settingsManager.currentWorkdir,
                            revertCutoffs = store.chatFlow.value.revertCutoffs,
                        )
                        // C. DIRECT route hydration (NOT effect bus).
                        val fp = currentProfileId()
                        startMaterializedRouteHydration(adoption, fp)
                        // D. dispatch the captured payload (send) OR the command.
                        if (capturedPayload != null) {
                            sendOrchestrator.dispatchCapturedSend(session.id, capturedPayload, token)
                        } else if (commandPost != null) {
                            val commandTextMatches = capturedCommandText != null &&
                                store.composerFlow.value.inputText.trim() == capturedCommandText.trim()
                            if (commandTextMatches) {
                                composerController.setInputText("")
                            }
                            appScope.launch { commandPost(session.id) }
                        }
                        // E. schedule the title refresh (bounded retry, item 10-B).
                        refreshOrchestrator.scheduleTitleRefreshAfterFirstMessage(session.id)
                    } else {
                        val hostChanged = store.hostFlow.value.currentHostProfileId != origin.hostProfileId ||
                            currentProfileId() != origin.profileId
                        if (hostChanged) {
                            DebugLog.w(
                                "Materialize",
                                "adoption failed (host switched mid-create): sid=${session.id} send DROPPED (would cross-host)",
                            )
                            effectBus.tryEmitUiEvent(
                                UiEvent.Error(
                                    R.string.error_create_session_in_workdir_failed,
                                    listOf(draftWorkdir, "host switched"),
                                ),
                            )
                        } else if (capturedPayload != null) {
                            DebugLog.i(
                                "Materialize",
                                "adoption failed (user navigated away): sid=${session.id} still background-sending",
                            )
                            sendOrchestrator.launchBackgroundSendForMaterializeFailure(session.id, capturedPayload)
                        } else if (commandPost != null) {
                            DebugLog.i(
                                "Materialize",
                                "adoption failed (user navigated away): sid=${session.id} still executing command",
                            )
                            appScope.launch { commandPost(session.id) }
                        }
                    }
                }
                .onFailure { error ->
                    if (origin.stillOwnsDraftSurface(store.stateFlow.value)) {
                        store.mutateComposer { it.copy(draftWorkdir = draftWorkdir) }
                    }
                    effectBus.tryEmitUiEvent(
                        UiEvent.Error(
                            R.string.error_create_session_in_workdir_failed,
                            listOf(draftWorkdir, error.message ?: "unknown error"),
                        ),
                    )
                }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun captureDraftRouteOrigin(): DraftRouteOrigin {
        val nav = store.navFlow.value
        return DraftRouteOrigin(
            activeDestination = nav.activeDestination,
            activeDestinationEpoch = nav.activeDestinationEpoch,
            requestedRoute = nav.lastRoute,
            requestedNavEpoch = nav.navEpoch,
            routeInstance = store.stateFlow.value.chatRouteInstance,
            profileId = currentProfileId(),
            hostProfileId = store.hostFlow.value.currentHostProfileId,
        )
    }

    @MainThread
    private fun adoptMaterializedSessionRoute(
        session: Session,
        viewedAt: Long,
        origin: DraftRouteOrigin,
    ): MaterializedRouteAdoption {
        val route = "chat/${session.id}"
        val committed = store.mutateStateAndGet { before ->
            if (!origin.stillOwnsDraftSurface(before)) {
                materializeListOnly(before, session, viewedAt)
            } else {
                val token = before.chatRouteInstance + 1L
                val materialized = reduceDraftSessionMaterialized(
                    before,
                    AppAction.DraftSessionMaterialized(session, viewedAt),
                )
                val clearedChat = materialized.chat.clearLoadedChatPayload()
                materialized.copy(
                    chatRouteInstance = token,
                    chat = clearedChat.copy(
                        currentSessionId = session.id,
                        content = LoadedContent(sessionId = session.id, routeInstance = token),
                    ),
                    nav = materialized.nav.copy(
                        lastRoute = route,
                        navEpoch = materialized.nav.navEpoch + 1L,
                    ),
                )
            }
        }
        val adopted = committed.nav.lastRoute == route &&
            committed.chat.currentSessionId == session.id &&
            committed.chat.content?.sessionId == session.id &&
            committed.chat.content?.routeInstance == committed.chatRouteInstance
        return MaterializedRouteAdoption(
            sessionId = session.id,
            routeInstance = if (adopted) committed.chatRouteInstance else null,
            adopted = adopted,
        )
    }

    private fun materializeListOnly(before: StoreState, session: Session, viewedAt: Long): StoreState = before.copy(
        sessionList = before.sessionList.copy(
            sessions = upsertSession(before.sessionList.sessions, session),
            pendingCreateIds = before.sessionList.pendingCreateIds + session.id,
            pendingCreatedAt = before.sessionList.pendingCreatedAt + (session.id to viewedAt),
        ),
    )

    @MainThread
    private fun startMaterializedRouteHydration(adoption: MaterializedRouteAdoption, fp: String) {
        val token = adoption.routeInstance ?: return
        refreshOrchestrator.loadMessagesForEffect(
            sessionId = adoption.sessionId,
            resetLimit = true,
            forceInitialWindow = true,
            expectedRouteInstance = token,
        )
    }
}
