package cn.vectory.ocdroid.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.data.cache.CacheMaintenanceCoordinator
import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.data.cache.DailySweepReport
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.util.AppLocaleController
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.LocaleMode
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * §R18 Phase 3 Wave 3 (P2-6): Settings-domain ViewModel. Owns the user-
 * facing settings writes (theme mode, markdown font sizes, UI font/content
 * scale). Split out of [OrchestratorViewModel] so the latter no longer
 * carries the settings role (it was overloaded with settings + traffic +
 * permission + nav + file + cross-domain orchestration).
 *
 * §R-19 Sprint 3 P2-5: this VM no longer injects [AppCore]. Its precise
 * dependency surface is the settings slice ([SharedStateStore]'s
 * [SharedStateStore.settingsFlow] / [SharedStateStore.mutateSettings]) +
 * [SettingsManager] (the persistence side-effect). The VM cannot reach any
 * other slice/controller — it has no compile-time handle to them. Reads
 * delegate to the same authoritative [SharedStateStore] singleton every
 * other VM exposes; writes funnel through [SharedStateStore.mutateSettings]
 * + [SettingsManager] exactly as they did when the methods lived on
 * [OrchestratorViewModel] — pure relocation, zero behaviour change.
 *
 * R-20 Phase 4 (plan §3): gains the cache-management action surface — the
 * manual evict / sweep handlers used by
 * [cn.vectory.ocdroid.ui.settings.CacheManagementSection]. The cache deps
 * ([cacheRepository] + [cacheMaintenanceCoordinator] + [hostProfileStore])
 * are precise-injected here so the VM stays testable without dragging in
 * AppCore. The cache listing state is owned here (one StateFlow the UI
 * collects) so a single `refreshCacheListing()` round-trip updates every
 * visible row.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SharedStateStore,
    private val settingsManager: SettingsManager,
    /**
     * R-20 Phase 4: persistent chat cache — for [clearSession] /
     * [clearProject] / [clearAll] + the cache-listing refresh. Precise-
     * injected (R-19 P2-5 pattern) so this VM does not need a handle to
     * AppCore; the same @Singleton instance every controller uses.
     */
    private val cacheRepository: CacheRepository,
    /**
     * R-20 Phase 4: the daily-sweep driver — for the "scan orphans now"
     * action. The coordinator owns alive-set completeness + 24h dedup; the
     * VM is a thin wrapper that surfaces [DailySweepReport] to the UI.
     */
    private val cacheMaintenanceCoordinator: CacheMaintenanceCoordinator,
    /**
     * R-20 Phase 4: profile store — for cache-listing member counts.
     * Precise-injected so the VM never reaches into AppCore for it.
     */
    private val hostProfileStore: HostProfileStore,
    /**
     * R-20 Phase 4: app-lifetime scope for the cache-listing refresh
     * coroutine. Main.immediate-bound (the slice writes back into the
     * StateFlow that the UI reads on the Main thread). Same scope every
     * controller uses.
     */
    @UiApplicationScope private val appScope: CoroutineScope,
    /**
     * R-20 Phase 0/1 (dser I-1, plan §3 Phase 4): true iff the cache fell
     * back to the in-memory substitute (encrypted DB open failed twice).
     * Surfaces as a "cache degraded" warning in CacheManagementSection.
     */
    @Named("cacheDegraded") private val cacheDegraded: Boolean,
    @Named("currentServerGroupFp") private val currentServerGroupFpProvider: () -> String,
    /**
     * §P5a (Q5): application Context for [setLocaleMode] →
     * [AppLocaleController.apply] (needed to resolve the real system locale
     * in SYSTEM mode via LocaleManagerCompat). Precise-injected (Hilt
     * @ApplicationContext) — the canonical Hilt pattern for VMs that need a
     * Context handle.
     */
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /**
     * §R-19 P2-5 test-only convenience constructor: lets existing tests that
     * build a full [AppCore] (via [cn.vectory.ocdroid.MainViewModelTestBase])
     * keep instantiating this VM with `SettingsViewModel(core)` while the
     * production Hilt graph uses the primary [@Inject constructor] above
     * (precise-injected). The secondary forwards the same deps the
     * production binding would; Hilt ignores it (not @Inject-annotated).
     *
     * R-20 Phase 4: builds a real [CacheMaintenanceCoordinator] from the
     * AppCore's already-wired cache repository + REST repository +
     * settingsManager. AppCore does not own a coordinator field (it's
     * injected straight into [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator]);
     * constructing one here is fine because the coordinator is stateless
     * across calls (24h dedup is in [SettingsManager], not in the coordinator).
     */
    internal constructor(core: AppCore) : this(
        core.store,
        core.settingsManager,
        core.cacheRepository,
        CacheMaintenanceCoordinator(
            core.cacheRepository,
            core.repository,
            core.settingsManager,
            core.currentServerGroupFp,
        ),
        core.hostProfileStore,
        core.appScope,
        false,
        core.currentServerGroupFp,
        core.appContext,
    )

    /** Read accessor — same authoritative slice [OrchestratorViewModel] and
     *  the other domain VMs expose (all delegate to [SharedStateStore]). Kept
     *  here so SettingsScreen can read settings off its own VM without
     *  reaching into another domain. */
    val settingsFlow get() = store.settingsFlow

    /** The group served by the currently-connected repository. */
    val currentServerGroupFp: String get() = currentServerGroupFpProvider()

    /**
     * §vcs-section: read-only accessor for the current workdir (the absolute
     * path the active session is bound to). This is a plain read of the
     * existing [settingsManager.currentWorkdir] field — it is NOT routing VCS
     * data through this VM (the VCS fetch + state lives entirely inside the
     * Workspace → Changes pane's local state). Surfaced here purely so other
     * domains have a way to read the workdir input without reaching into
     * another domain; mirrors how every controller reads
     * `settingsManager.currentWorkdir` directly.
     */
    val currentWorkdir: String? get() = settingsManager.currentWorkdir

    /**
     * §reactive-workdir: observable workdir so consumers (Workspace → Changes
     * pane, ContextSelectorSheet, etc.) react to workdir changes (session
     * switch, profile switch, disconnect) without a manual refresh. The plain
     * [currentWorkdir] getter is kept for existing snapshot reads (zero blast
     * radius); new reactive consumers collect this.
     */
    val currentWorkdirFlow: StateFlow<String?> = settingsManager.currentWorkdirFlow

    // §files-git-readonly-workdir: the legacy `filesLastWorkdir` field +
    // `setFilesLastWorkdir` setter (the last workdir explicitly browsed in
    // Files/Git) were removed when the Files/Git WorkdirControl became a
    // read-only indicator. workdir now follows the active session's directory
    // exclusively, so there is no longer a "browsed-but-not-current" workdir
    // to persist. See [SettingsManager] for the storage-side cleanup.

    /**
     * §grouping-rewrite Round-2 C1: disconnect reactivity trigger.
     * [disconnectWorkdir] mutates [settingsManager] (removeRecentWorkdir) +
     * [cacheRepository] (evictWorkdirInGroup), neither of which emits on
     * [store.hostFlow] or [store.sessionListFlow] — so without an explicit
     * poke the derived [recentWorkdirs] flow would not re-derive and the
     * disconnected workdir would stay visible until an unrelated flow ticked.
     *
     * Implemented as a monotonic counter (rather than a SharedFlow<Unit>)
     * because `combine` waits for ALL sources to emit at least once before
     * producing; a non-replaying SharedFlow would therefore delay the initial
     * recentWorkdirs value until the first disconnect, breaking cold-start
     * rendering. A StateFlow<Long> starting at 0 emits immediately and on
     * every bump.
     */
    private val recentWorkdirsTick = MutableStateFlow(0L)

    val recentWorkdirs: StateFlow<List<String>> = combine(
        store.hostFlow,
        store.sessionListFlow,
        recentWorkdirsTick,
    ) { host, _, _ ->
        // host (HostState) only re-triggers this flow on host changes; the fp
        // is read from the store the same way every other call site does
        // (ComposerController/ConnectionActions/SessionViewModel…).
        // The tick re-triggers on disconnectWorkdir (C1 fix).
        val profile = hostProfileStore.currentProfile()
        val fp = profile.serverGroupFp.ifBlank { profile.id }
        settingsManager.getRecentWorkdirs(fp)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * §grouping-rewrite 项 2: profile count + cached-session count for the
     * active fp, surfaced so [ConnectionProfileSection]'s stats line can
     * render without a synchronous store read on every recomposition. Built
     * off the same `store.hostFlow` + `store.sessionListFlow` triggers as
     * [recentWorkdirs] so an fp switch / session-list change re-derives the
     * counts. The fp is derived the standard way
     * (`serverGroupFp.ifBlank { id }`).
     *
     * `profilesInGroup(fp)` is synchronous (HostProfileStore is in-memory +
     * ESP-backed); `listGroupSessions(fp)` is suspend (Room read), so the
     * cached-session derivation runs in the combine's suspend context.
     */
    val activeGroupProfileCount: StateFlow<Int> = combine(
        store.hostFlow,
        store.sessionListFlow,
    ) { host, _ ->
        val profile = hostProfileStore.currentProfile()
        val fp = profile.serverGroupFp.ifBlank { profile.id }
        hostProfileStore.profilesInGroup(fp).size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val activeGroupCachedSessionCount: StateFlow<Int> = combine(
        store.hostFlow,
        store.sessionListFlow,
    ) { host, _ ->
        val profile = hostProfileStore.currentProfile()
        val fp = profile.serverGroupFp.ifBlank { profile.id }
        cacheRepository.listGroupSessions(fp).size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setThemeMode(mode: ThemeMode) {
        settingsManager.themeMode = mode
        store.mutateSettings { it.copy(themeMode = mode) }
    }

    /**
     * §P5a (Q5): persist the user's language choice + apply it immediately
     * via [AppLocaleController.apply] (AppCompatDelegate → MainActivity
     * recreate, since `locale` is not in MainActivity's configChanges →
     * instant effect). SYSTEM mode re-resolves the real system locale at
     * apply time (zh→zh, en→en, other→zh).
     */
    fun setLocaleMode(mode: LocaleMode) {
        settingsManager.localeMode = mode
        store.mutateSettings { it.copy(localeMode = mode) }
        AppLocaleController.apply(appContext, mode)
    }

    fun setMarkdownFontSizes(sizes: MarkdownFontSizes) {
        settingsManager.markdownFontSizes = sizes
        store.mutateSettings { it.copy(markdownFontSizes = sizes) }
    }

    fun setUiFontScale(scale: Float) {
        val clamped = scale.coerceIn(SettingsManager.UI_SCALE_MIN, SettingsManager.UI_SCALE_MAX)
        settingsManager.uiFontScale = clamped
        store.mutateSettings { it.copy(uiFontScale = clamped) }
    }

    fun setUiContentScale(scale: Float) {
        val clamped = scale.coerceIn(SettingsManager.UI_SCALE_MIN, SettingsManager.UI_SCALE_MAX)
        settingsManager.uiContentScale = clamped
        store.mutateSettings { it.copy(uiContentScale = clamped) }
    }

    // ─────────── R-20 Phase 4: cache management ───────────────────────────

    /** Whether the cache fell back to non-persistent storage (UI warning). */
    val isCacheDegraded: Boolean get() = cacheDegraded

    /**
     * The cache listing's online status. The "scan orphans now" action is
     * disabled while the SSE is not connected — a sweep without a live
     * connection cannot enumerate the alive set (every cached session would
     * look orphaned), so the UI greys out the button + shows a hint. Read
     * from [SharedStateStore.connectionFlow] (the authoritative source every
     * other UI uses).
     */
    val isOnline: Boolean
        get() = store.connectionFlow.value.isConnected

    /**
     * Cache listing state — one entry per distinct `server_group_fp`, with
     * its cached-session rows (newest-first) + the host profiles still
     * pointing at that fp (for the copy-on-split row action). Refreshed on
     * demand by [refreshCacheListing]; the UI collects this flow.
     */
    private val _cacheListing = MutableStateFlow<CacheListingState>(CacheListingState.Loading)
    val cacheListing: StateFlow<CacheListingState> = _cacheListing.asStateFlow()

    /**
     * Last sweep outcome (null until the first [sweepNow] completes). The UI
     * surfaces the report inline so the user sees what got evicted / marked.
     */
    private val _lastSweep = MutableStateFlow<DailySweepReport?>(null)
    val lastSweep: StateFlow<DailySweepReport?> = _lastSweep.asStateFlow()

    /**
     * §P5b-B (Q8): total byte size of all cached message payloads. Surfaced
     * in the 清除数据 section as "已缓存数据 XXX MB". Refreshed alongside
     * [cacheListing] by [refreshCacheListing]; 0 until the first refresh.
     */
    private val _cachedDataBytes = MutableStateFlow(0L)
    val cachedDataBytes: StateFlow<Long> = _cachedDataBytes.asStateFlow()

    /**
     * Refresh the cache listing from the persistent store. Idempotent —
     * safe to call on Settings screen enter + after every mutation. Runs on
     * [appScope] (Main.immediate) so the StateFlow write lands on the same
     * thread the collector reads.
     *
     * Sets the state to [CacheListingState.Loading] synchronously so the UI
     * can show a progress indicator before the first result lands.
     */
    fun refreshCacheListing() {
        _cacheListing.value = CacheListingState.Loading
        appScope.launch {
            val state = runCatching {
                val fps = cacheRepository.allServerGroupFps()
                if (fps.isEmpty()) {
                    CacheListingState.Empty
                } else {
                    CacheListingState.Loaded(
                        groups = fps.map { fp ->
                            CacheGroupListing(
                                serverGroupFp = fp,
                                profiles = hostProfileStore.profilesInGroup(fp),
                                sessions = cacheRepository.listGroupSessions(fp),
                            )
                        }
                    )
                }
            }.getOrElse {
                DebugLog.e(TAG, "refreshCacheListing failed", it)
                CacheListingState.Error(it.message ?: it::class.simpleName.orEmpty())
            }
            _cacheListing.value = state
            // §P5b-B (Q8): best-effort refresh of the cached-data byte total
            // (used by the 清除数据 section). A failure here does NOT affect
            // the listing — the section falls back to "0 B".
            val bytes = runCatching { cacheRepository.totalCachedPayloadBytes() }
                .onFailure { DebugLog.e(TAG, "totalCachedPayloadBytes failed", it) }
                .getOrDefault(0L)
            _cachedDataBytes.value = bytes
        }
    }

    /**
     * Manually evict a single cached session (plan §3 Phase 4 "clearSession"
     * action). Routes to [CacheRepository.evictSession] (cascade deletes the
     * session row + messages + gaps inside one Room transaction). Refreshes
     * the listing afterwards so the UI drops the row.
     */
    fun clearSession(serverGroupFp: String, sessionId: String) {
        appScope.launch {
            runCatching { cacheRepository.evictSession(serverGroupFp, sessionId) }
                .onFailure { DebugLog.e(TAG, "clearSession failed fp=$serverGroupFp sid=$sessionId", it) }
            refreshCacheListing()
        }
    }

    /**
     * Manually evict every cached session for one project (workdir) under
     * one server group (plan §3 Phase 4 "clearProject" action). Routes to
     * [CacheRepository.evictWorkdirInGroup] — NOT [CacheRepository.clearAll]
     * (clearAll is the nuke button; this preserves every other workdir in
     * the same group).
     */
    fun clearProject(serverGroupFp: String, workdir: String) {
        appScope.launch {
            runCatching { cacheRepository.evictWorkdirInGroup(serverGroupFp, workdir) }
                .onFailure { DebugLog.e(TAG, "clearProject failed fp=$serverGroupFp workdir=$workdir", it) }
            refreshCacheListing()
        }
    }

    /**
     * Nuke the entire cache (plan §3 Phase 4 "全清" button). Routes to
     * [CacheRepository.clearAll] — the ONLY cache-busting call that crosses
     * server-group boundaries (every other action is fp-scoped or
     * workdir-scoped). The UI gates this behind a confirmation dialog.
     */
    fun clearAll() {
        appScope.launch {
            runCatching { cacheRepository.clearAll() }
                .onFailure { DebugLog.e(TAG, "clearAll failed", it) }
            refreshCacheListing()
        }
    }

    /**
     * Trigger the daily orphan sweep right now (plan §3 Phase 4 "sweepNow"
     * action). Routes to [CacheMaintenanceCoordinator.dailySweepIfNeeded];
     * the alive-completeness degradation lives inside the coordinator.
     * Surfaces the resulting [DailySweepReport] via [lastSweep] so the UI can
     * show what got evicted / marked.
     *
     * §grouping-rewrite Round-2 C2: passes `force=true` so the manual button
     * bypasses the 24h epoch-day dedup — the auto connect-sweep would
     * otherwise make this button a no-op for the rest of the day. The
     * coordinator still stamps the epoch after a forced run, so the next
     * auto connect-sweep within the same day dedups normally.
     *
     * **Offline guard**: the caller (CacheManagementSection) MUST disable
     * the button when `isOnline == false`; this method does NOT re-check
     * (the coordinator would degrade to mark-only on its own, but a
     * pre-emptive UI disable avoids a pointless background round-trip).
     */
    fun sweepNow(serverGroupFp: String) {
        appScope.launch {
            val report = runCatching {
                cacheMaintenanceCoordinator.dailySweepIfNeeded(serverGroupFp, force = true)
            }.onFailure { DebugLog.e(TAG, "sweepNow failed fp=$serverGroupFp", it) }.getOrNull()
            if (report != null) _lastSweep.value = report
            refreshCacheListing()
        }
    }

    /**
     * §grouping-rewrite 项 3: "Sweep all groups" — fan out a safe maintenance
     * pass across every distinct fp currently in use. The coordinator fully
     * sweeps only the connected group; every other group receives fp-scoped
     * local LRU/age eviction only. Derived from the UNION of:
     *  - profile-derived fps ([hostProfileStore.profiles] → distinct
     *    `serverGroupFp`), so an fp with profiles but no cached sessions still
     *    gets its LRU/age pass; AND
     *  - cache-derived fps ([cacheRepository.allServerGroupFps]), so an fp
     *    with cached sessions but no live profile (e.g. all profiles for that
     *    group were just deleted, leaving orphan cache rows) is not missed.
     *
     * §grouping-rewrite Round-2 C2: passes `force=true` per fp (manual button
     * bypasses 24h dedup). The last completed report (whichever group finishes
     * last) is surfaced via [lastSweep] for the inline result line; intermediate
     * reports are collapsed to avoid popup jitter.
     *
     * §grouping-rewrite Round-3 #4: the union fixes a gap where the popup
     * listing (cache-derived via [refreshCacheListing] → [cacheRepository.
     * allServerGroupFps]) showed a group that this sweep would have skipped
     * (because its profile had been deleted but its cache rows remained).
     */
    fun sweepAllGroups() {
        appScope.launch {
            val profileFps = hostProfileStore.profiles()
                .map { it.serverGroupFp.ifBlank { it.id } }
                .toSet()
            val cacheFps = runCatching { cacheRepository.allServerGroupFps() }
                .onFailure { DebugLog.e(TAG, "sweepAllGroups: allServerGroupFps failed", it) }
                .getOrDefault(emptyList())
                .toSet()
            val fps = (profileFps + cacheFps).distinct()
            val reports = mutableListOf<DailySweepReport>()
            for (fp in fps) {
                val report = runCatching {
                    cacheMaintenanceCoordinator.dailySweepIfNeeded(fp, force = true)
                }.onFailure { DebugLog.e(TAG, "sweepAllGroups failed fp=$fp", it) }.getOrNull()
                if (report != null) reports += report
            }
            if (reports.isNotEmpty()) {
                _lastSweep.value = DailySweepReport(
                    serverGroupFp = "",
                    completeness = if (reports.all { it.completeness == cn.vectory.ocdroid.data.cache.AliveCompleteness.Complete }) {
                        cn.vectory.ocdroid.data.cache.AliveCompleteness.Complete
                    } else {
                        cn.vectory.ocdroid.data.cache.AliveCompleteness.Incomplete
                    },
                    verifiedAliveCount = reports.sumOf { it.verifiedAliveCount },
                    evictedSessionIds = reports.flatMap { it.evictedSessionIds },
                    suspiciousSessionIds = reports.flatMap { it.suspiciousSessionIds },
                )
            }
            refreshCacheListing()
        }
    }

    fun disconnectWorkdir(workdir: String) {
        val wd = workdir.trim()
        if (wd.isEmpty()) return
        val profile = hostProfileStore.currentProfile()
        val fp = profile.serverGroupFp.ifBlank { profile.id }
        appScope.launch {
            settingsManager.removeRecentWorkdir(fp, wd)
            runCatching { cacheRepository.evictWorkdirInGroup(fp, wd) }
                .onFailure { DebugLog.e(TAG, "disconnectWorkdir failed fp=$fp workdir=$wd", it) }
            // §grouping-rewrite Round-2 C1 (+ Round-3 N2): neither
            // removeRecentWorkdir nor evictWorkdirInGroup pokes hostFlow/
            // sessionListFlow, so we bump the tick to force recentWorkdirs
            // (and any other derivation tied to it) to re-derive immediately.
            // SessionsScreen's workdirGroups drops the disconnected workdir
            // on the next frame instead of waiting for an unrelated emit.
            //
            // §grouping-rewrite Round-3 N2: `update { it + 1L }` is atomic
            // (CAS loop) vs the read-modify-write of `value = value + 1L` —
            // defensive against a future caller that fans disconnects out to
            // parallel coroutines.
            recentWorkdirsTick.update { it + 1L }
            refreshCacheListing()
        }
    }

    private companion object {
        private const val TAG = "SettingsViewModel"
    }
}

/**
 * R-20 Phase 4: cache-listing state machine for
 * [SettingsViewModel.cacheListing]. Sealed so the Compose renderer is
 * exhaustive (Loading / Empty / Loaded / Error each get their own branch).
 */
sealed interface CacheListingState {
    /** Initial / refreshing — the listing is being read. */
    data object Loading : CacheListingState
    /** No cached sessions at all — the cache is empty. */
    data object Empty : CacheListingState
    /** Read failed — surface the message so the user knows it wasn't their
     *  action that did nothing. */
    data class Error(val message: String) : CacheListingState
    /** Loaded successfully — one entry per distinct server-group. */
    data class Loaded(val groups: List<CacheGroupListing>) : CacheListingState
}

/**
 * R-20 Phase 4: one server-group's cache listing — the fp + the host
 * profiles still pointing at it (for the copy-on-split row action) + the
 * cached-session rows (newest-first). The UI renders one section per entry.
 */
data class CacheGroupListing(
    val serverGroupFp: String,
    val profiles: List<cn.vectory.ocdroid.data.model.HostProfile>,
    val sessions: List<CacheRepository.CachedSessionRow>,
)
