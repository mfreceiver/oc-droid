package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.controller.applyArchiveEviction
import cn.vectory.ocdroid.ui.controller.applyArchivedChatClear

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
     *    `(listOf(session.id) + settingsManager.openSessionIds).distinct()
     *    .take(8)`; the reducer just stores it — persistence is the caller's
     *    job).
     *  - [viewedAt]: `System.currentTimeMillis()` captured at the call site
     *    (the reducer writes `unread.lastViewedTime + (session.id to viewedAt)`).
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
    is AppAction.DraftSessionMaterialized -> state.copy(
        sessionList = state.sessionList.copy(
            sessions = upsertSession(state.sessionList.sessions, action.session),
            openSessionIds = action.openSessionIds,
        ),
        chat = state.chat.copy(
            currentSessionId = action.session.id,
        ),
        unread = state.unread.copy(
            unreadSessions = state.unread.unreadSessions - action.session.id,
            lastViewedTime = state.unread.lastViewedTime + (action.session.id to action.viewedAt),
        ),
        composer = state.composer.copy(
            draftWorkdir = null,
        ),
    )

    is AppAction.SessionArchived -> {
        // Apply archive eviction unconditionally (upsert archived + new openIds).
        // Apply the chat clear IFF the archived session IS the current one —
        // derived from the snapshot, not carried on the action.
        val isCurrent = state.chat.currentSessionId == action.session.id
        val newSessionList = state.sessionList.applyArchiveEviction(action.session, action.openSessionIds).first
        val newChat = if (isCurrent) state.chat.applyArchivedChatClear().first else state.chat
        state.copy(sessionList = newSessionList, chat = newChat)
    }

    is AppAction.HostStatePurged -> {
        // §slice-only-preserve: ChatState carries three fields NOT mirrored
        // to AppState (isCompacting / compactStartedAt / refreshNonce — see
        // HostProfileController.kt:475-479). Use .copy() on the existing
        // ChatState so they are preserved; only the AppState-represented
        // chat fields are reset.
        val (newChat, newSessionList, newUnread) = if (!action.preserveServerGroupData) {
            // Cross-group (异组 switch / delete active host): full purge of
            // per-server + per-profile state.
            Triple(
                state.chat.copy(
                    currentSessionId = null,
                    messages = emptyList(),
                    partsByMessage = emptyMap(),
                    streamingPartTexts = emptyMap(),
                    streamingReasoningPart = null,
                    isLoadingMessages = false,
                    isLoadingMoreMessages = false,
                ),
                state.sessionList.copy(
                    sessions = emptyList(),
                    directorySessions = emptyMap(),
                    openSessionIds = emptyList(),
                    sessionStatuses = emptyMap(),
                    sessionTodos = emptyMap(),
                    sessionDiffs = emptyMap(),
                ),
                state.unread.copy(
                    unreadSessions = emptySet(),
                    tempClearedUnread = emptySet(),
                    lastViewedTime = emptyMap(),
                ),
            )
        } else {
            // Same-group switch: per-server data (sessions / unread /
            // directorySessions / statuses / todos / diffs) preserved. Only
            // the streaming overlay is cleared (a stale delta from the old
            // profile's in-flight turn must NOT bleed into the new profile's
            // view).
            Triple(
                state.chat.copy(
                    streamingPartTexts = emptyMap(),
                    streamingReasoningPart = null,
                ),
                state.sessionList,
                state.unread,
            )
        }
        state.copy(
            chat = newChat,
            sessionList = newSessionList,
            unread = newUnread,
            // Per-profile UX — ALWAYS reset regardless of group.
            composer = state.composer.copy(draftWorkdir = null),
            settings = state.settings.copy(availableCommands = emptyList()),
            connection = state.connection.copy(serverVersion = null),
        )
    }

    is AppAction.WorkdirDraftStarted -> state.copy(
        chat = state.chat.copy(
            currentSessionId = null,
            messages = emptyList(),
            partsByMessage = emptyMap(),
            streamingPartTexts = emptyMap(),
            streamingReasoningPart = null,
            // §fix-draft-model-leak: clear currentModel so the prior session's
            // inferred model does NOT leak into the draft picker (the original
            // site did this as a SEPARATE writeChat after the first — folded
            // into one commit here).
            currentModel = null,
        ),
        sessionList = state.sessionList.copy(
            sessionTodos = emptyMap(),
        ),
        composer = state.composer.copy(
            inputText = "",
            imageAttachments = emptyList(),
            // §1B-FIX (I4): also clear fileReferences on draft-create so a
            // chip from the previous session's draft does not survive the
            // workdir switch.
            fileReferences = emptyList(),
            draftWorkdir = action.workdir,
        ),
    )
}
