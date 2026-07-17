package cn.vectory.ocdroid.di

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * Bug-1 (notification regeneration): durable storage for the "already-
 * notified" IDLE dedup key set.
 *
 * **Why idle-only**: the user-reported regression was that idle roots got
 * re-notified after process death. Persisting the IDLE dedup set (a root
 * that was notified-idle in a prior process must not re-notify on the
 * first background poll after restart) closes that. The DECISION dedup set
 * (`"perm:<id>"` / `"q:<id>"`) is intentionally **NOT** persisted — the
 * original "process death = only GC, so still-pending blocking items
 * correctly re-remind" contract is preserved (a permission/question that
 * was pending at process death SHOULD re-remind after restart; only idle
 * re-notification was the bug). Persisting decisions would also grow
 * unbounded across the app's lifetime (a permission id is never reused).
 *
 * **Mirror + ADD/REMOVE model**: the prior API did `saveIdleKeys(snapshot-
 * Posted().keys)` — a full-set snapshot taken at every notify (Main.
 * immediate publish path) AND every post-prune (Default dispatcher). Two
 * dispatchers racing a full-snapshot write could transiently drop a key,
 * AND each save allocated + serialized the full set. The new API mutates
 * a single in-memory [MutableSet] mirror under a [synchronized] lock via
 * [addPostedIdle] / [removePostedIdle]; only the delta is logically
 * applied, and the disk write under the lock always reflects the mirror
 * state at that instant (no lost-update race between the two dispatchers).
 *
 * **Single load**: the [idleMirror] is loaded ONCE at construction from
 * [prefs] — no per-call disk read. The seed step at [AppLifecycleMonitor]
 * init calls [snapshotIdle] once; subsequent mutations go through the
 * mirror under the lock.
 *
 * **Plain SharedPreferences**: this store is a **plain** dedicated
 * [android.content.SharedPreferences] file (NOT
 * `EncryptedSharedPreferences`). The keys are NOT secrets — they are
 * constructed from `(serverId, workdir, rootId)` and contain no user
 * content. Using plain SharedPreferences keeps the notification path off
 * the KeyStore/crypto hot path (an intentional, documented choice: the
 * dedup state must be writable on every notify without risking
 * KeyStore-unavailable failures aborting the notification).
 *
 * **Stale `posted_decision` key**: prior installs persisted decision keys
 * under `posted_decision`. Those are intentionally ignored now (decision
 * dedup is in-memory only). The leftover is harmless — nothing reads it,
 * and it is not re-written by this class.
 *
 * Thread safety: every public mutation + the snapshot read take the
 * [lock] monitor, so the Main.immediate notify path and the Default
 * post-prune path cannot interleave a read-modify-write on [idleMirror].
 * SharedPreferences' `apply()` is itself thread-safe; the lock is what
 * makes the mirror + apply atomic with respect to concurrent callers.
 */
internal class NotificationDedupStore(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Single in-memory mirror of the persisted `posted_idle` set, loaded
     * ONCE at construction. Every mutation goes through [addPostedIdle] /
     * [removePostedIdle] under [lock]; the disk write under the same lock
     * always reflects the mirror's state at that instant.
     */
    private val lock = Any()
    private val idleMirror: MutableSet<String> =
        prefs.getStringSet(KEY_IDLE, emptySet()).orEmpty().toMutableSet()

    /**
     * Returns the idle dedup keys currently in the mirror (a snapshot
     * copy). Used at [AppLifecycleMonitor] init to seed the in-memory
     * [NotificationDedup] BEFORE any claim can fire. Safe to call from any
     * thread; the returned set is an immutable snapshot.
     */
    fun snapshotIdle(): Set<String> = synchronized(lock) { idleMirror.toSet() }

    /**
     * Adds [key] to the idle mirror under [lock] and persists the new
     * mirror if (and only if) the key was newly added. Best-effort: any
     * persistence failure is logged and swallowed so the notification path
     * never throws into the poller / publish boundary.
     */
    fun addPostedIdle(key: String) {
        synchronized(lock) {
            if (idleMirror.add(key)) applyIdle()
        }
    }

    /**
     * Removes [key] from the idle mirror under [lock] and persists the new
     * mirror if (and only if) the key was actually present. Best-effort:
     * any persistence failure is logged and swallowed. Used by the
     * post-prune path to keep the persisted mirror in sync with keys that
     * were pruned from the in-memory [NotificationDedup] (additive model —
     * never a full-snapshot rewrite).
     */
    fun removePostedIdle(key: String) {
        synchronized(lock) {
            if (idleMirror.remove(key)) applyIdle()
        }
    }

    /**
     * Writes the current [idleMirror] snapshot to [prefs]. MUST be called
     * under [lock] so the serialized set matches the mirror state at the
     * instant of the call. The `toSet()` copy defends against the
     * SharedPreferences's internal reference-keeping (a mutable set handed
     * to `putStringSet` would be aliased).
     */
    private fun applyIdle() {
        try {
            prefs.edit { putStringSet(KEY_IDLE, idleMirror.toSet()) }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist idle dedup (mirror)", t)
        }
    }

    private companion object {
        const val TAG = "NotifDedupStore"
        const val PREFS_NAME = "ocdroid_notif_dedup"
        const val KEY_IDLE = "posted_idle"
    }
}
