package cn.vectory.ocdroid.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R-17 batch3: singleton container for the 9 AppState slices + nav + uiEvents.
 *
 * HiltViewModels cannot inject each other (ViewModels are not @Inject-able
 * dependencies), so the slices that USED to live in MainViewModel's private
 * fields now live in this @Singleton. Every domain ViewModel injects the same
 * instance and reads/writes its own slice through the per-slice helpers. This
 * preserves the original semantics (single authoritative MutableStateFlow per
 * domain) while letting the VMs stay decoupled.
 *
 * The bundle-style access ([connectionFlow]/[chatFlow]/etc.) preserves the
 * original controller constructor signatures that take a [SliceFlows] bundle
 * — the controllers do not need to change.
 */
@Singleton
class SharedStateStore @Inject constructor() {
    val connectionFlow: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState())
    val trafficFlow: MutableStateFlow<TrafficState> = MutableStateFlow(TrafficState())
    val composerFlow: MutableStateFlow<ComposerState> = MutableStateFlow(ComposerState())
    val fileFlow: MutableStateFlow<FileState> = MutableStateFlow(FileState())
    val settingsFlow: MutableStateFlow<SettingsState> = MutableStateFlow(SettingsState())
    val chatFlow: MutableStateFlow<ChatState> = MutableStateFlow(ChatState())
    val sessionListFlow: MutableStateFlow<SessionListState> = MutableStateFlow(SessionListState())
    val unreadFlow: MutableStateFlow<UnreadState> = MutableStateFlow(UnreadState())
    val hostFlow: MutableStateFlow<HostState> = MutableStateFlow(HostState())
    val expandedParts: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap())

    /** Nav slice (not part of [SliceFlows] bundle). Seeded by OrchestratorVM. */
    val navFlow: MutableStateFlow<NavState> = MutableStateFlow(NavState())

    /** Bundle view (preserves the [SliceFlows] data class for controller ctors). */
    val slices: SliceFlows = SliceFlows(
        connection = connectionFlow,
        traffic = trafficFlow,
        composer = composerFlow,
        file = fileFlow,
        settings = settingsFlow,
        chat = chatFlow,
        sessionList = sessionListFlow,
        unread = unreadFlow,
        host = hostFlow,
    )
}
