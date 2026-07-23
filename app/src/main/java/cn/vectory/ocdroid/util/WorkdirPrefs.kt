package cn.vectory.ocdroid.util

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * L4b domain split of [SettingsManager] — WORKDIR domain.
 *
 * Owns the current workdir (project directory) + its hot reactive mirror
 * [currentWorkdirFlow] + the per-(serverGroupFp) recent-workdirs MRU list.
 *
 * §L4b cluster 17: the five recent-workdirs CRUD methods now share a single
 * [isValidFp] guard (the pre-split `if (serverGroupFp.isBlank()) return`
 * literal, extracted verbatim into a boolean helper). Behavior is IDENTICAL
 * — a blank fp still silently returns / returns emptyList(); this is NOT a
 * kotlin `require{}` (which would throw). Only the guard expression was
 * factored; nothing else changed.
 *
 * §L4b lock monitor: the pre-split `synchronized(this)` blocks locked on the
 * SettingsManager instance; here they lock on this WorkdirPrefs instance.
 * This is safe because ALL recent-workdirs RMW methods moved together, so
 * they still serialize against one another on the same monitor. No external
 * code locked on the SettingsManager instance (verified pre-split).
 *
 * Behavior byte-identical to pre-split [SettingsManager]: same ESP instance,
 * same key strings, same `synchronized` boundaries, same [WorkdirPaths]
 * normalized-equivalence dedup, same storage-stays-original-form rule. NO
 * key renames.
 */
internal class WorkdirPrefs(
    private val encryptedPrefs: SharedPreferences,
) {
    // §reactive-workdir: hot, observable mirror of [currentWorkdir]. Seeded from
    // ESP at construction so cold-start collectors see the persisted value
    // immediately (not null). Every setter write updates it; the only bypass is
    // [SettingsManager.clearAllLocalData] (batched direct removes), which
    // re-syncs via [resetWorkdirMirror] at its tail.
    private val _currentWorkdirFlow = MutableStateFlow<String?>(
        encryptedPrefs.getString(KEY_CURRENT_WORKDIR, null)
    )
    val currentWorkdirFlow: StateFlow<String?> = _currentWorkdirFlow.asStateFlow()

    /**
     * The workdir (project directory) the user last connected to. Restored on
     * cold start so repository queries are re-scoped to the same project and
     * its directory-scoped sessions are re-fetched — without this, restart
     * resets currentDirectory to null and the connected project "vanishes".
     */
    var currentWorkdir: String?
        get() = encryptedPrefs.getString(KEY_CURRENT_WORKDIR, null)
        set(value) {
            encryptedPrefs.edit().putString(KEY_CURRENT_WORKDIR, value).apply()
            // §reactive-workdir: keep the flow mirror in sync so the
            // Git → Changes pane (and any other collector) reacts to
            // workdir changes without manual refresh.
            _currentWorkdirFlow.value = value
        }

    /**
     * §reactive-workdir: called only by [SettingsManager.clearAllLocalData],
     * which bypasses the setter (batched direct .remove()s) and is
     * GUARANTEED to have removed [KEY_CURRENT_WORKDIR] (it is not in the
     * preserved-keys whitelist). Direct null assignment eliminates a
     * theoretical re-read race for no benefit — identical to pre-split.
     */
    internal fun resetWorkdirMirror() {
        _currentWorkdirFlow.value = null
    }

    /**
     * R-20 Phase 5: the set of workdirs the user has recently connected to
     * (MRU order), keyed per [serverGroupFp] so the right set survives a cold
     * start for the active host AND so two profiles reaching the same server
     * (same fp) share the workdir-discovery memory. Without this, a non-
     * current workdir whose sessions fall outside the global
     * `getSessions(limit=10)` first page vanishes from the Sessions screen
     * after restart (the "one of my frequent projects randomly disappeared"
     * bug). Capped at [MAX_RECENT_WORKDIRS].
     *
     * Plan §3 Phase 5 (v4): the legacy global `recent_workdirs` single key is
     * migrated to `recent_workdirs_<fp>` once per fp by
     * [MigrationHelper.migrateLegacyKeysToFp] (idempotent via the
     * `cache_migration_v1_done_<fp>` flag) — see
     * [cn.vectory.ocdroid.ui.ConnectionActions.applySavedSettings].
     *
     * §files-git-readonly-workdir: the legacy `files_last_workdir` field (the
     * last workdir explicitly browsed in Files/Git) was removed when the
     * Files/Git WorkdirControl became a read-only indicator — workdir now
     * follows the active session's directory exclusively, so there is no
     * longer a "browsed-but-not-current" workdir to persist. The storage key
     * `files_last_workdir` is intentionally NOT reclaimed here (a leftover
     * ESP entry is harmless); it is reclaimed by [SettingsManager.clearAllLocalData].
     */
    fun getRecentWorkdirs(serverGroupFp: String): List<String> {
        if (!isValidFp(serverGroupFp)) return emptyList()
        return synchronized(this) {
            // §R18 Phase 4 (P2-9) Gate-4 fix (maxer): lock the read too so it
            // cannot observe a value mid-flight from a concurrent
            // addRecentWorkdir write (the getter is otherwise a separate
            // critical section from the write-side RMW). SharedPreferences
            // getString is itself atomic, but without this lock a reader
            // (e.g. loadInitialData computing the fan-out workdir set) could
            // base its decision on a list that addRecentWorkdir is about to
            // change. Synchronized(this) pairs with addRecentWorkdir's lock.
            val json = encryptedPrefs.getString(recentWorkdirsKey(serverGroupFp), null)
                ?: return@synchronized emptyList()
            try {
                Json.decodeFromString<List<String>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse recent workdirs for fp=$serverGroupFp, using empty", e)
                emptyList()
            }
        }
    }

    fun setRecentWorkdirs(serverGroupFp: String, workdirs: List<String>) {
        if (!isValidFp(serverGroupFp)) return
        val json = Json.encodeToString(workdirs)
        encryptedPrefs.edit().putString(recentWorkdirsKey(serverGroupFp), json).apply()
    }

    /**
     * Prepends [workdir] to the [serverGroupFp]'s recent-workdirs list (MRU),
     * deduplicating and capping at [MAX_RECENT_WORKDIRS]. Called when the user
     * connects a project via `createSessionInWorkdir` so the workdir survives
     * restart even after it is later superseded as [currentWorkdir]. Blank/
     * whitespace-only entries are ignored.
     *
     * §R18 Phase 4 (P2-9): the read-filter-write across [getRecentWorkdirs] is
     * wrapped in `synchronized(this)` to make the sequence atomic. Without
     * this, two concurrent callers can both read the old list, both prepend,
     * and the second write clobbers the first — losing the first caller's
     * workdir (read-modify-write race). SharedPreferences itself is process-
     * thread-safe but the read→filter→write compound is not.
     *
     * §grouping-rewrite Round-6 F4: dedup is now by NORMALIZED EQUIVALENCE
     * via [WorkdirPaths.normalize] (symmetric with [removeRecentWorkdir]'s
     * normalized match per C4). Pre-F4 the dedup was exact-trimmed, so
     * adding "/proj-a" then "proj-a/" would store BOTH — they normalize
     * together but compared unequal under raw `==`. The asymmetry vs
     * removeRecentWorkdir (which normalized) meant the disconnect would
     * remove both via normalized match but a re-add could leave a stale
     * twin. Both sides now agree: add removes any existing entry whose
     * normalized form matches the new entry's, then prepends the new
     * entry's stored form.
     *
     * **Storage stays ORIGINAL-form** (the trimmed string the server
     * returned) — NOT the normalized key. The server needs the real path
     * for `getSessionsForDirectory`; normalizing on store would break the
     * cold-start fan-out. Only the comparison is normalized.
     */
    fun addRecentWorkdir(serverGroupFp: String, workdir: String) {
        if (!isValidFp(serverGroupFp)) return
        val storedForm = workdir.trim()
        if (storedForm.isEmpty()) return
        val normalizedKey = WorkdirPaths.normalize(storedForm)
        synchronized(this) {
            val updated = (listOf(storedForm) + getRecentWorkdirs(serverGroupFp).filter {
                WorkdirPaths.normalize(it) != normalizedKey
            }).take(MAX_RECENT_WORKDIRS)
            setRecentWorkdirs(serverGroupFp, updated)
        }
    }

    /**
     * Remove [workdir] from the [serverGroupFp]'s recent-workdirs list.
     *
     * §grouping-rewrite Round-4 C4: matching is by NORMALIZED EQUIVALENCE, not
     * exact trimmed string. The display dir passed in here comes from
     * `buildWorkdirGroups`'s output, which is often the absolute leading-slash
     * form taken from a live session (e.g. `/proj-a`), while recent_workdirs
     * may persist a slash variant the server originally returned (e.g.
     * `proj-a/`). Under exact-string matching the disconnect would silently
     * fail to match → the variant would persist → the normalized visible key
     * would still exist → the workdir would reappear after disconnect (the
     * disconnect dialog's "将从列表移除" promise broken for variant cases).
     *
     * Both the incoming [workdir] and each stored entry are funnelled through
     * [WorkdirPaths.normalize] — the SAME normalization
     * `buildWorkdirGroups`/`SessionsScreen` uses for visibility gating — so
     * the disconnect pipeline is closed end-to-end:
     *
     *   display dir → disconnectWorkdir → removeRecentWorkdir (normalized)
     *   → removed → recentWorkdirsTick bumped → buildWorkdirGroups re-derives
     *   → workdir no longer in visible set → hidden.
     *
     * Storage behaviour is unchanged: `getRecentWorkdirs` still returns the
     * ORIGINAL stored forms (server-facing paths); only the comparison is
     * normalized. `addRecentWorkdir` also still stores the trimmed original
     * (not the normalized form) — see its KDoc for why.
     */
    fun removeRecentWorkdir(serverGroupFp: String, workdir: String) {
        if (!isValidFp(serverGroupFp)) return
        val targetNormalized = WorkdirPaths.normalize(workdir)
        if (targetNormalized.isEmpty()) return
        synchronized(this) {
            val updated = getRecentWorkdirs(serverGroupFp).filter { stored ->
                WorkdirPaths.normalize(stored) != targetNormalized
            }
            setRecentWorkdirs(serverGroupFp, updated)
        }
    }

    /**
     * R-20 Phase 5: clears the [serverGroupFp]'s recent-workdirs list. Used
     * by [cn.vectory.ocdroid.ui.controller.HostProfileController.purgePerHostState]
     * on a DIFFERENT-group switch (the old fp's workdirs are meaningless on
     * the new server). Same-group switches preserve the list (the new
     * profile reaches the same server).
     */
    fun clearRecentWorkdirs(serverGroupFp: String) {
        if (!isValidFp(serverGroupFp)) return
        encryptedPrefs.edit().remove(recentWorkdirsKey(serverGroupFp)).apply()
    }

    /**
     * §L4b cluster 17 (refactor-duplication-and-functions-backlog.md #17):
     * factored guard shared by the recent-workdirs CRUD family. Returns
     * `true` iff [fp] is a usable serverGroupFingerprint key suffix.
     *
     * BEHAVIOR-PRESERVING: this is the exact pre-split predicate
     * `!serverGroupFp.isBlank()` lifted into a helper. It does NOT throw
     * (deliberately not kotlin `require{}`) — each caller still does its
     * own early return / emptyList(), identical to the original literals
     * at the pre-split lines 259/281/317/357/376.
     */
    private fun isValidFp(fp: String): Boolean = !fp.isBlank()

    companion object {
        private const val TAG = "SettingsManager"

        internal const val KEY_CURRENT_WORKDIR = "current_workdir"
        internal const val KEY_RECENT_WORKDIRS = "recent_workdirs"
        /**
         * §files-git-readonly-workdir: REMOVED. The `files_last_workdir` field
         * (last workdir explicitly browsed in Files/Git) was dead code after
         * the Files/Git WorkdirControl became a read-only indicator. The
         * constant is intentionally kept commented-out as a tombstone so a
         * future "let me re-add this" pass sees the rationale instead of
         * re-deriving the same dead concept.
         */
        // internal const val KEY_FILES_LAST_WORKDIR = "files_last_workdir"
        /**
         * Cap for [getRecentWorkdirs] — bounds cold-start directory-fetch fan-out.
         *
         * §grouping-rewrite 项 5 (spec decision): 30 — the recent-workdir list
         * doubles as the persistent "Connected projects" surface (0-live wds
         * are retained as placeholders so the user can re-enter / disconnect
         * them), so the cap is sized for navigation memory rather than fetch
         * fan-out alone.
         */
        internal const val MAX_RECENT_WORKDIRS = 30

        /** R-20 Phase 5: per-fp recent-workdirs key. */
        internal fun recentWorkdirsKey(serverGroupFp: String): String =
            "recent_workdirs_$serverGroupFp"
    }
}
