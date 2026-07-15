package cn.vectory.ocdroid.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.util.AppLocaleController
import cn.vectory.ocdroid.util.LocaleMode
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * remove-message-persistence Task 5: the R-20 Phase 4 cache-management
 * action surface (cacheRepository / cacheMaintenanceCoordinator / the
 * cache-listing + sweep handlers / the degraded flag) was removed together
 * with the SQLite persistence layer. The VM now only carries settings writes
 * + the project/workdir registry ([connectWorkdir] / [disconnectWorkdir]).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SharedStateStore,
    private val settingsManager: SettingsManager,
    /**
     * Profile store — for profile count derivation + current-profile fp
     * resolution in the workdir registry. Precise-injected so the VM never
     * reaches into AppCore for it.
     */
    private val hostProfileStore: HostProfileStore,
    /**
     * App-lifetime scope for the workdir-registry coroutines. Main.immediate-
     * bound (the slice writes back into the StateFlow that the UI reads on
     * the Main thread). Same scope every controller uses.
     */
    @UiApplicationScope private val appScope: CoroutineScope,
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
     */
    internal constructor(core: AppCore) : this(
        core.store,
        core.settingsManager,
        core.hostProfileStore,
        core.appScope,
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
     * [disconnectWorkdir] mutates [settingsManager] (removeRecentWorkdir),
     * which does not emit on [store.hostFlow] or [store.sessionListFlow] — so
     * without an explicit poke the derived [recentWorkdirs] flow would not
     * re-derive and the disconnected workdir would stay visible until an
     * unrelated flow ticked.
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
     * §grouping-rewrite 项 2: profile count for the active fp, surfaced so
     * [ConnectionProfileSection]'s stats line can render without a
     * synchronous store read on every recomposition. Built off the same
     * `store.hostFlow` + `store.sessionListFlow` triggers as [recentWorkdirs]
     * so an fp switch / session-list change re-derives the count. The fp is
     * derived the standard way (`serverGroupFp.ifBlank { id }`).
     *
     * `profilesInGroup(fp)` is synchronous (HostProfileStore is in-memory +
     * ESP-backed).
     *
     * remove-message-persistence Task 5: the sibling cached-session-count
     * flow was removed together with the cacheRepository surface (SQLite
     * persistence layer deletion).
     */
    val activeGroupProfileCount: StateFlow<Int> = combine(
        store.hostFlow,
        store.sessionListFlow,
    ) { host, _ ->
        val profile = hostProfileStore.currentProfile()
        val fp = profile.serverGroupFp.ifBlank { profile.id }
        hostProfileStore.profilesInGroup(fp).size
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

    fun disconnectWorkdir(workdir: String) {
        val wd = workdir.trim()
        if (wd.isEmpty()) return
        val profile = hostProfileStore.currentProfile()
        val fp = profile.serverGroupFp.ifBlank { profile.id }
        appScope.launch {
            settingsManager.removeRecentWorkdir(fp, wd)
            // §grouping-rewrite Round-2 C1 (+ Round-3 N2):
            // removeRecentWorkdir does not poke hostFlow/sessionListFlow, so
            // we bump the tick to force recentWorkdirs (and any other
            // derivation tied to it) to re-derive immediately. SessionsScreen's
            // workdirGroups drops the disconnected workdir on the next frame
            // instead of waiting for an unrelated emit.
            //
            // §grouping-rewrite Round-3 N2: `update { it + 1L }` is atomic
            // (CAS loop) vs the read-modify-write of `value = value + 1L` —
            // defensive against a future caller that fans disconnects out to
            // parallel coroutines.
            //
            // remove-message-persistence Task 5: the cacheRepository
            // .evictWorkdirInGroup call that used to sit here was removed with
            // the SQLite persistence layer; the persistent cache no longer
            // exists, so only the workdir-registry mutation remains.
            recentWorkdirsTick.update { it + 1L }
        }
    }

    /**
     * Register a project (workdir) WITHOUT creating a session — the project is
     * a first-class entity independent of the session system (nav redesign:
     * Files "添加新项目"). A project with zero sessions, or whose last session
     * was archived, still appears and is NOT removed; only [disconnectWorkdir]
     * removes it.
     *
     * Deliberately does NOT: change `currentWorkdir`, clear chat/draft, steal
     * the active session, or create a session. Contrast [SessionViewModel.
     * createSessionInWorkdir], which (despite its name) starts a draft +
     * registers + prefetches — that draft-hijack behaviour is wrong for the
     * Files "add project" entry, hence this dedicated action.
     *
     * No prefetch here: the per-workdir `directorySessions` fan-out
     * (`loadInitialData`) and the project-row expand both fetch on demand.
     */
    fun connectWorkdir(workdir: String) {
        val wd = workdir.trim()
        if (wd.isEmpty()) return
        val profile = hostProfileStore.currentProfile()
        val fp = profile.serverGroupFp.ifBlank { profile.id }
        appScope.launch {
            settingsManager.addRecentWorkdir(fp, wd)
            // Neither addRecentWorkdir pokes hostFlow/sessionListFlow, so bump
            // the tick to force recentWorkdirs (and buildWorkdirGroups) to
            // re-derive immediately — the new project row appears next frame.
            recentWorkdirsTick.update { it + 1L }
        }
    }
}