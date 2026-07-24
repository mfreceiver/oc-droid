package cn.vectory.ocdroid.util

import android.content.SharedPreferences
import android.util.Log
import cn.vectory.ocdroid.data.model.SessionCacheEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * L4b domain split of [SettingsManager] — SESSION domain.
 *
 * Owns the cold-start session-list seeding surface: the browser-tab style
 * open-session id list, the persisted session-metadata cache, and the
 * per-(serverGroupFp, sessionId) draft text map.
 *
 * §L4b ESP-key ownership: this class owns the [COMPOSITE_KEY_SEPARATOR]
 * constant and the [compositeSessionKey] builder used by the drafts map
 * AND by [MigrationHelper.rewriteSessionMapLegacyToFp]. The public API
 * `SettingsManager.COMPOSITE_KEY_SEPARATOR` is re-exported from here so
 * the test fixture `SettingsManagerMigrationTest` keeps resolving.
 *
 * Behavior byte-identical to pre-split [SettingsManager]: same ESP
 * instance, same key strings, same NUL-separated composite encoding, same
 * JSON parse-fallback defaults. NO key renames.
 *
 * ## Draft-text debounce (C1 / P1-gate-fix)
 * [setDraftText] is a HOT PATH invoked on every keystroke. When
 * [debounceScope] is non-null, writes are coalesced and deferred ~500ms to
 * avoid an AES-GCM EncryptedSharedPreferences write per character. Pending
 * state is keyed by the composite `(serverGroupFp, sessionId)` so each
 * session/host gets its OWN debounce timer — interleaved edits across keys
 * never clobber each other (per-key isolation). Callers that need immediate
 * persistence call [flushDraftText] (session switch / tab close /
 * clear-on-send / app background). When [debounceScope] is null (e.g.
 * direct construction in unit tests), writes are immediate — the old
 * behavior.
 */
internal class SessionPrefs(
    private val encryptedPrefs: SharedPreferences,
    /** When non-null, draft writes are debounced on this scope. */
    private val debounceScope: CoroutineScope? = null,
) {
    /**
     * "Open" (not closed) session IDs in open-order (most recently opened first).
     * Replaces the previous MRU [recentSessionIds] model with a browser-tab style
     * list: opening/switching a session prepends it; closing (x) removes it.
     * Capped at 8 entries.
     */
    var openSessionIds: List<String>
        get() {
            val json = encryptedPrefs.getString(KEY_OPEN_SESSION_IDS, null) ?: return emptyList()
            return try {
                Json.decodeFromString<List<String>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse open session IDs, using empty", e)
                emptyList()
            }
        }
        set(value) {
            val json = Json.encodeToString(value)
            encryptedPrefs.edit().putString(KEY_OPEN_SESSION_IDS, json).apply()
        }

    /**
     * Persisted projection of [cn.vectory.ocdroid.data.model.Session]
     * metadata, used to seed the session-list slice
     * ([cn.vectory.ocdroid.ui.SessionListState.sessions])
     * on cold start so tabs/title/workdir groups render instantly before the
     * server list is fetched. Written only from `launchLoadSessions`
     * onSuccess (bounded to open/current/workdir-relevant entries). A server
     * refresh later replaces these with authoritative data.
     */
    var sessionCache: List<SessionCacheEntry>
        get() {
            val json = encryptedPrefs.getString(KEY_SESSION_CACHE, null) ?: return emptyList()
            return try {
                Json.decodeFromString<List<SessionCacheEntry>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse session cache, using empty", e)
                emptyList()
            }
        }
        set(value) {
            val json = Json.encodeToString(value)
            encryptedPrefs.edit().putString(KEY_SESSION_CACHE, json).apply()
        }

    /**
     * R-20 Phase 5: per-(serverGroupFp, sessionId) draft text. The composite
     * map key is `"<fp>\u0000<sessionId>"` (NUL separator — fp is a UUID /
     * branded string that never contains NUL, so the split is unambiguous).
     *
     * Plan §3 Phase 5 (v4 freegpt #4): sessionId is a branded `ses_xxxx`
     * string, NOT a UUID — clone/reset servers can collide. A bare sessionId
     * key would let a draft typed on server A's `ses_xyz` leak into server B
     * when B happens to issue the same id. The composite key eliminates the
     * cross-server collision. Drafts contain unsent text (potentially
     * sensitive) so the isolation is privacy-critical.
     *
     * Legacy storage: a single global `session_drafts` JSON map keyed by
     * bare sessionId. [MigrationHelper.migrateLegacyKeysToFp] rewrites every
     * legacy entry to `"<currentFp>\u0000<sessionId>"` once per fp
     * (idempotent).
     *
     * **Read-through**: always reads directly from EncryptedSharedPreferences
     * (latest committed value). The debounce only affects the write side.
     */
    fun getDraftText(serverGroupFp: String, sessionId: String): String {
        val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null) ?: return ""
        return try {
            Json.decodeFromString<Map<String, String>>(json)[compositeSessionKey(serverGroupFp, sessionId)] ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ── Draft-text debounce ──────────────────────────────────────────────

    /**
     * §C1 / P1-gate-fix: per-(serverGroupFp, sessionId) pending mutations,
     * keyed by [compositeSessionKey]. Each composite key has its OWN
     * [DraftMutation] (in [pendingDrafts]) AND its OWN debounce [Job] (in
     * [debounceJobs]) so rapid writes to DIFFERENT sessions/hosts never
     * clobber each other.
     *
     * The prior single-slot design (one global AtomicReference + one Job)
     * lost a draft for key A when key B was written before A's timer fired
     * — it overwrote the one pending slot regardless of key. Keying the
     * pending state by [compositeSessionKey] restores the mandated per-key
     * isolation (privacy-critical: drafts are unsent, potentially sensitive
     * text scoped to a specific host+session).
     *
     * Guarded by [debounceLock]; [performDraftWrite] (disk IO) always runs
     * OUTSIDE the lock.
     */
    private val pendingDrafts: MutableMap<String, DraftMutation> = mutableMapOf()

    /** §C1 / P1-gate-fix: per-key debounce jobs. Guarded by [debounceLock]. */
    private val debounceJobs: MutableMap<String, Job> = mutableMapOf()

    /** Lock for [pendingDrafts] + [debounceJobs] management. */
    private val debounceLock = Any()

    /**
     * §P1-gate-persist: serializes the whole-map read-modify-write of the
     * shared `session_drafts` JSON in [performDraftWrite]. Distinct from
     * [debounceLock]: debounceLock guards the in-memory per-key pending
     * state/job map; persistLock guards the ESP whole-map RMW so two
     * independent debounce jobs firing concurrently on Dispatchers.Default
     * cannot read the same old JSON snapshot and lose one another's update.
     *
     * The two locks are NEVER nested: [performDraftWrite] is invoked OUTSIDE
     * [debounceLock] (in the per-key write-back job and in [flushDraftText]'s
     * drain loop), so there is a single lock-ordering → no deadlock.
     */
    private val persistLock = Any()

    /** §C1: coalesced pending draft mutation (one entry per composite key). */
    private data class DraftMutation(
        val serverGroupFp: String,
        val sessionId: String,
        val text: String,
    )

    /**
     * §C1 / P1-gate-fix: sets the draft text for `(serverGroupFp, sessionId)`.
     * When [debounceScope] is non-null, the ESP write is deferred ~500ms and
     * coalesces with any subsequent call FOR THE SAME composite key (rapid
     * typing produces one write per session). Writes to OTHER keys are fully
     * independent — each key has its own pending mutation + timer, so
     * interleaved edits across sessions/hosts never lose data.
     *
     * Blank text still removes the map entry (same semantic as pre-debounce).
     * Call [flushDraftText] for immediate persistence.
     *
     * Thread-safety: the pending mutation + job for THIS key are mutated
     * under [debounceLock]; only THIS key's job is cancelled/replaced (other
     * keys' jobs are untouched). The write-back ([performDraftWrite]) runs on
     * [debounceScope] OUTSIDE the lock.
     */
    fun setDraftText(serverGroupFp: String, sessionId: String, text: String) {
        val scope = debounceScope
        if (scope == null) {
            // No debounce scope → immediate write (direct construction in tests).
            performDraftWrite(serverGroupFp, sessionId, text)
            return
        }
        val compositeKey = compositeSessionKey(serverGroupFp, sessionId)
        val mutation = DraftMutation(serverGroupFp, sessionId, text)
        synchronized(debounceLock) {
            pendingDrafts[compositeKey] = mutation
            // Cancel ONLY this key's prior timer; other keys keep running.
            debounceJobs[compositeKey]?.cancel()
            debounceJobs[compositeKey] = scope.launch {
                delay(DEBOUNCE_MS)
                // Atomically claim THIS key's mutation. Re-check that we are
                // still the registered job: a newer write for the SAME key
                // cancels us (the delay resumes cancelled → body skipped);
                // but if that cancellation raced our resume, the job entry
                // now points at the new job, so the === self check makes us
                // bail out and let the newer job own the write. Either way a
                // different key's mutation is never touched.
                val self = coroutineContext[Job]
                val m = synchronized(debounceLock) {
                    if (debounceJobs[compositeKey] === self) {
                        debounceJobs.remove(compositeKey)
                        pendingDrafts.remove(compositeKey)
                    } else {
                        null
                    }
                }
                // performDraftWrite runs OUTSIDE debounceLock (no IO under the
                // pending-state lock). Its own whole-map RMW is serialized under
                // persistLock so concurrent per-key write-backs cannot lose
                // updates on the shared session_drafts JSON.
                if (m != null) performDraftWrite(m.serverGroupFp, m.sessionId, m.text)
            }
        }
    }

    /**
     * §C1 / P1-gate-fix: cancels ALL pending debounce jobs and writes EVERY
     * pending draft (across all composite keys) to ESP immediately. Used by
     * app-background (AppLifecycleMonitor.onEnterBackground) and any
     * transition that must guarantee all in-flight drafts are durable
     * (session switch / tab close / clear-on-send). No-op when nothing is
     * pending.
     *
     * Safe to call from any thread. Job cancellation + map drain happen
     * under [debounceLock]; the ESP writes ([performDraftWrite],
     * `SharedPreferences.edit().putString().apply()` — async disk IO) run
     * OUTSIDE the lock.
     */
    fun flushDraftText() {
        val drained: List<DraftMutation> = synchronized(debounceLock) {
            // Cancel every pending debounce job so a late fire can't write a
            // stale value after we've already drained it.
            debounceJobs.values.forEach { it.cancel() }
            debounceJobs.clear()
            val all = pendingDrafts.values.toList()
            pendingDrafts.clear()
            all
        }
        // Write each pending mutation OUTSIDE debounceLock. Each write's RMW is
        // serialized under persistLock inside performDraftWrite, so even though
        // these are sequential here, the per-key write-back jobs (running
        // concurrently on Dispatchers.Default) are also race-safe.
        drained.forEach { performDraftWrite(it.serverGroupFp, it.sessionId, it.text) }
    }

    /**
     * §C1 / P1-gate-fix: mutable-freeze snapshot for testability. Returns
     * the pending mutation for the exact `(serverGroupFp, sessionId)`
     * composite key without consuming it, or null when that key has nothing
     * pending.
     */
    internal fun peekPendingDraft(serverGroupFp: String, sessionId: String): DraftMutationSnapshot? {
        val compositeKey = compositeSessionKey(serverGroupFp, sessionId)
        return synchronized(debounceLock) {
            pendingDrafts[compositeKey]?.let {
                DraftMutationSnapshot(it.serverGroupFp, it.sessionId, it.text)
            }
        }
    }

    /**
     * §C1 / P1-gate-fix: snapshot of EVERY pending mutation across all keys,
     * for testability (cross-key isolation assertions). Empty when clean.
     */
    internal fun peekAllPendingDrafts(): List<DraftMutationSnapshot> =
        synchronized(debounceLock) {
            pendingDrafts.values.map {
                DraftMutationSnapshot(it.serverGroupFp, it.sessionId, it.text)
            }
        }

    /** §C1: immutable value class for exposing [DraftMutation] to tests. */
    internal data class DraftMutationSnapshot(
        val serverGroupFp: String,
        val sessionId: String,
        val text: String,
    )

    /**
     * §C1 / §P1-gate-persist: performs the actual EncryptedSharedPreferences
     * write. Same logic, same JSON round-trip, same blank-text-removes-entry
     * semantics as the original [setDraftText] body — the ONLY change for the
     * persistence-race fix is that the ENTIRE read→mutate→write of the shared
     * `session_drafts` JSON map is now serialized under [persistLock].
     *
     * Why this lock is needed: each key's debounce write-back job runs
     * independently on `@ApplicationScope` (Dispatchers.Default, multi-
     * threaded). The RMW here is unlocked otherwise → two jobs reading the
     * same old JSON snapshot and each writing back a single-key update would
     * lose the other's update (lost-update race on the shared key).
     * [persistLock] makes the whole-map RMW atomic across all callers
     * (per-key write-back jobs AND [flushDraftText]'s drain loop).
     *
     * Lock-ordering / deadlock safety:
     *  - `synchronized` (JVM monitor) is used (not a coroutine Mutex) because
     *    this method is also called from the non-suspending [flushDraftText]
     *    (invoked on the main thread). A suspend lock is not viable there.
     *  - The critical section is short: ESP getString + a putString.apply().
     *    `apply()` is async-to-disk but synchronous to ESP's in-memory cache,
     *    so blocking a Default thread is negligible.
     *  - There is NO suspension point inside `synchronized` → a coroutine
     *    cannot be cancelled mid-critical-section (no orphaned-lock risk).
     *  - [persistLock] is NEVER acquired while holding [debounceLock]
     *    ([performDraftWrite] runs outside debounceLock), and [debounceLock]
     *    is NEVER acquired while holding [persistLock] → single ordering, no
     *    deadlock.
     */
    private fun performDraftWrite(serverGroupFp: String, sessionId: String, text: String) {
        synchronized(persistLock) {
            val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null)
            val map: MutableMap<String, String> = try {
                json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
            } catch (e: Exception) {
                mutableMapOf()
            }
            val key = compositeSessionKey(serverGroupFp, sessionId)
            if (text.isBlank()) {
                map.remove(key)
            } else {
                map[key] = text
            }
            encryptedPrefs.edit().putString(KEY_SESSION_DRAFTS, Json.encodeToString(map)).apply()
        }
    }

    companion object {
        private const val TAG = "SettingsManager"

        /** §C1: debounce window for coalescing rapid draft writes. */
        internal const val DEBOUNCE_MS = 500L

        internal const val KEY_OPEN_SESSION_IDS = "open_session_ids"
        internal const val KEY_SESSION_CACHE = "session_cache"
        internal const val KEY_SESSION_DRAFTS = "session_drafts"

        /**
         * R-20 Phase 5: separator used in the composite `(serverGroupFp,
         * sessionId)` map key. NUL (\u0000) is chosen because serverGroupFp
         * is a UUID / branded string (Phase 0 guarantees nonblank + the
         * HostProfile decode normalize step never produces one containing
         * NUL), so `"$fp\u0000$sessionId"` is an unambiguous reversible
         * encoding — no fp value can collide with a sessionId prefix.
         *
         * Public so tests + [MigrationHelper.rewriteSessionMapLegacyToFp]
         * share the constant.
         */
        const val COMPOSITE_KEY_SEPARATOR = '\u0000'

        /**
         * R-20 Phase 5: builds the composite map key for per-(fp, sessionId)
         * storage (drafts / agents / models). See [COMPOSITE_KEY_SEPARATOR].
         */
        internal fun compositeSessionKey(serverGroupFp: String, sessionId: String): String =
            "$serverGroupFp$COMPOSITE_KEY_SEPARATOR$sessionId"
    }
}
