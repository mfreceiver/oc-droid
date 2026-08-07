package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.repository.InteractionRepository
import cn.vectory.ocdroid.data.repository.SessionRepository
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.ui.controller.ComposerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * §Wave2.1-split-l2: slash-command execution orchestrator.
 * Owns [executeCommand] and its private helpers (createSessionForEffect,
 * createSessionInWorkdirForEffect).
 *
 * Depends on: core-five + composerController + DraftSessionOrchestrator + SessionOpener.
 *
 * ~220 LOC extracted from AppCoreOrchestration.kt.
 */
@Singleton
class CommandOrchestrator @Inject constructor(
    private val store: SharedStateStore,
    private val sessionRepository: SessionRepository,
    private val interactionRepository: InteractionRepository,
    private val settingsManager: SettingsManager,
    private val effectBus: SharedEffectBus,
    @UiApplicationScope private val appScope: CoroutineScope,
    @Named("currentProfileId") private val currentProfileId: () -> String,
    private val composerController: ComposerController,
    private val draftSessionOrchestrator: DraftSessionOrchestrator,
    private val sessionOpener: SessionOpener,
) {

    /**
     * `/clear` (composer reset + fresh session) and other slash commands.
     * Cross-domain: composer (input clear) + session-list (create) + chat
     * (target session). Routes the slash command to the right primitive.
     */
    fun executeCommand(command: String, arguments: String) {
        val cmd = command.removePrefix("/").trim().lowercase(Locale.getDefault())
        if (cmd.isEmpty()) return
        when (cmd) {
            "clear" -> {
                composerController.setInputText("")
                val workdir = settingsManager.currentWorkdir
                    ?: currentSession(
                        store.sessionListFlow.value.sessions,
                        store.chatFlow.value.currentSessionId,
                    )?.directory
                if (workdir != null) {
                    settingsManager.currentWorkdir = workdir
                    settingsManager.addRecentWorkdir(currentProfileId(), workdir)
                    createSessionInWorkdirForEffect(workdir)
                } else {
                    createSessionForEffect()
                }
            }
            else -> {
                val existing = store.chatFlow.value.currentSessionId
                val commandDirectory = (
                    currentSession(store.sessionListFlow.value.sessions, existing)?.directory
                        ?: store.composerFlow.value.draftWorkdir
                        ?: settingsManager.currentWorkdir
                    )
                if (existing != null) {
                    composerController.setInputText("")
                    appScope.launch {
                        interactionRepository.executeCommand(existing, cmd, arguments, directory = commandDirectory)
                            .onFailure { error ->
                                effectBus.tryEmitUiEvent(classifyCommandPostError(error, cmd))
                            }
                    }
                } else if (store.composerFlow.value.draftWorkdir != null) {
                    val capturedCommandText = store.composerFlow.value.inputText
                    draftSessionOrchestrator.materializeDraftSession(
                        capturedCommandText = capturedCommandText,
                        commandPost = { sid ->
                            interactionRepository.executeCommand(sid, cmd, arguments, directory = commandDirectory)
                                .onFailure { error ->
                                    effectBus.tryEmitUiEvent(classifyCommandPostError(error, cmd))
                                }
                        },
                    )
                } else {
                    composerController.setInputText("")
                    effectBus.tryEmitUiEvent(
                        UiEvent.Error(R.string.chat_command_no_session, listOf(cmd))
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun createSessionForEffect(title: String? = null) {
        launchCreateSession(
            scope = appScope,
            repository = sessionRepository,
            slices = store.slices,
            title = title,
            onSelectSession = { sessionOpener.selectSessionForEffect(it) },
            emit = EventEmitter { event -> effectBus.tryEmitUiEvent(event) },
            directory = settingsManager.currentWorkdir,
        )
    }

    private fun createSessionInWorkdirForEffect(workdir: String) {
        val wd = workdir.trim()
        store.dispatch(AppAction.WorkdirDraftStarted(workdir = wd))
        appScope.launch {
            sessionRepository.getSessionsForDirectory(wd)
                .onSuccess { sessions ->
                    store.mutateSessionList {
                        it.copy(directorySessions = it.directorySessions + (wd to sessions))
                    }
                }
        }
    }
}
