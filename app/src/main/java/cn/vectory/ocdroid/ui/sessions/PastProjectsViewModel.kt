package cn.vectory.ocdroid.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.data.model.DirectoryEntry
import cn.vectory.ocdroid.data.repository.DirectoriesErrorCause
import cn.vectory.ocdroid.data.repository.DirectoriesOutcome
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.util.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named

/**
 * §slimapi-directories — UI state for the "past projects" picker sheet.
 *
 *  - [Loading]: fetch in flight; [previousItems] keeps the last render visible
 *    (with a spinner) so retries never blank the sheet (#7).
 *  - [Success]: server-authoritative list. [stale]=true when `discoveryComplete`
 *    was false (incomplete) OR the result was recovered from a Dropped window.
 *  - [Degraded]: MRU fallback (slim + old sidecar, or non-slim). Labeled
 *    "本机最近项目" by the UI; [stale]=true when recovered from Dropped.
 *  - [Error]: transient/non-thin failure; [previousItems] retained, retry shown.
 *    NEVER produced for 404 `thin_route_not_found` (that is [Degraded]).
 *  - [Empty]: authoritative empty (items=[] && discoveryComplete=true, slim only).
 */
internal sealed interface DirectoriesUiState {
    class Loading(val previousItems: List<DirectoryEntry>?) : DirectoriesUiState
    class Success(
        val items: List<DirectoryEntry>,
        val isComplete: Boolean,
        val stale: Boolean = false,
    ) : DirectoriesUiState
    class Degraded(val items: List<DirectoryEntry>, val stale: Boolean = false) : DirectoriesUiState
    class Error(val previousItems: List<DirectoryEntry>?, val cause: DirectoriesErrorCause) : DirectoriesUiState
    object Empty : DirectoriesUiState
}

/** Renderable snapshot retaining source + completeness (B3) across reloads/Dropped. */
private sealed interface RenderSnapshot {
    class Server(val items: List<DirectoryEntry>, val isComplete: Boolean) : RenderSnapshot
    class Mru(val items: List<DirectoryEntry>) : RenderSnapshot
}

/**
 * §slimapi-directories — backs the "查看既往项目" sheet. Loads
 * `GET /slimapi/directories` via [OpenCodeRepository.getDirectories] and renders
 * the result through [DirectoriesUiState].
 *
 * **Concurrency model** (the load-bearing contract, v8):
 *  - `loadGen` ([AtomicLong]) — newer-wins for rapid same-connection reloads.
 *  - `identityStore.currentIdentity` — observed reactively; any change (incl.
 *    same-epoch `bindIfCurrent` rebind and the reconfigure null-window) cancels
 *    in-flight work, isolates history per connection (`lastRender = null`), and
 *    auto-reloads when the sheet is visible.
 *  - **Atomic commit**: the UI state write runs INSIDE
 *    [OpenCodeRepository.commitIfConnectionCaptureCurrent]`({ cap -> … })`, which
 *    re-validates epoch + identity + generation + endpointFp under the repo
 *    monitor + identityStore lock. This closes the repo-return → VM-write TOCTOU
 *    (incl. bundle-generation rotation that leaves epoch+identity unchanged); a
 *    non-atomic VM-side identity read could not.
 */
@HiltViewModel
class PastProjectsViewModel @Inject constructor(
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val identityStore: ConnectionIdentityStore,
    @Named("currentProfileId") private val profileIdProvider: () -> String,
) : ViewModel() {

    private val _state = MutableStateFlow<DirectoriesUiState>(DirectoriesUiState.Loading(null))
    internal val state: StateFlow<DirectoriesUiState> = _state.asStateFlow()

    private var lastRender: RenderSnapshot? = null
    private val loadGen = AtomicLong(0)
    private var loadJob: Job? = null
    private var sheetVisible = false

    init {
        viewModelScope.launch {
            identityStore.currentIdentity.collect { id ->
                loadGen.incrementAndGet()
                loadJob?.cancel()
                lastRender = null
                _state.value = DirectoriesUiState.Loading(null)
                if (sheetVisible && id != null) loadPastDirectories()
            }
        }
    }

    /** Called by the sheet's visible/dismiss lifecycle. */
    fun onSheetVisible(visible: Boolean) {
        sheetVisible = visible
        if (!visible) {
            loadGen.incrementAndGet()
            loadJob?.cancel()
        } else {
            loadPastDirectories()
        }
    }

    fun loadPastDirectories() {
        loadGen.incrementAndGet()
        loadJob?.cancel()
        val myGen = loadGen.get()
        loadJob = viewModelScope.launch {
            _state.value = DirectoriesUiState.Loading(renderItems())
            val result = repository.getDirectories()
            // Atomic commit: re-validate BOTH the mode snapshot AND the 4
            // connection-stamp fields under the repo monitor + identityStore lock
            // (commitDirectoriesIfCurrent). Closes the repo-return→VM-write TOCTOU
            // incl. bundle-generation-only rotation that leaves epoch+identity
            // unchanged but resets the capability flag.
            val wrote = repository.commitDirectoriesIfCurrent(result) {
                if (myGen == loadGen.get()) applyOutcome(result.outcome)
            }
            if (!wrote && myGen == loadGen.get()) handleDropped()
        }
    }

    private fun applyOutcome(outcome: DirectoriesOutcome) {
        when (outcome) {
            is DirectoriesOutcome.ServerList -> {
                val sorted = outcome.items.sortedWith(
                    compareByDescending<DirectoryEntry> { it.lastUpdated ?: 0L }
                        .thenBy { it.directory },
                )
                if (outcome.isComplete || lastRender == null) {
                    lastRender = RenderSnapshot.Server(sorted, outcome.isComplete)
                }
                _state.value = when {
                    outcome.isComplete && sorted.isEmpty() -> DirectoriesUiState.Empty
                    else -> DirectoriesUiState.Success(
                        items = if (outcome.isComplete) sorted else (renderItems() ?: sorted),
                        isComplete = outcome.isComplete,
                        stale = !outcome.isComplete,
                    )
                }
            }
            DirectoriesOutcome.Degraded -> {
                val mru = settingsManager.getRecentWorkdirs(profileIdProvider()).map { it.toMruEntry() }
                lastRender = RenderSnapshot.Mru(mru)
                _state.value = DirectoriesUiState.Degraded(mru)
            }
            is DirectoriesOutcome.Error -> {
                _state.value = DirectoriesUiState.Error(renderItems(), outcome.cause)
            }
            DirectoriesOutcome.Dropped -> handleDropped()
        }
    }

    /** Connection changed mid-request: restore last render (marked stale) or error+retry. */
    private fun handleDropped() {
        _state.value = when (val snap = lastRender) {
            is RenderSnapshot.Server -> DirectoriesUiState.Success(snap.items, snap.isComplete, stale = true)
            is RenderSnapshot.Mru -> DirectoriesUiState.Degraded(snap.items, stale = true)
            null -> DirectoriesUiState.Error(renderItems(), DirectoriesErrorCause.ConnectionChanged)
        }
    }

    private fun renderItems(): List<DirectoryEntry>? = when (val snap = lastRender) {
        is RenderSnapshot.Server -> snap.items
        is RenderSnapshot.Mru -> snap.items
        null -> null
    }

    private fun String.toMruEntry(): DirectoryEntry = DirectoryEntry(
        directory = this,
        title = null,
        lastUpdated = null,
        activeRootSessionCount = 0,
        archivedRootSessionCount = 0,
        archivedOnly = false,
    )
}
