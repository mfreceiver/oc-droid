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
 * per-(profileId, sessionId) draft text map.
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
 * state is keyed by the composite `(profileId, sessionId)` so each
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
    // §B4 / chat-list-detail §9.1 D9 / §16: open-tabs-list persistence
    // removed. The legacy ESP key `open_session_ids` is left unread/unwritten
    // (one-way drop of tab list only — sessionCache still restores metadata).

    /**
     * Persisted projection of [cn.vectory.ocdroid.data.model.Session]
     * metadata, used to seed the session-list slice
     * ([cn.vectory.ocdroid.ui.SessionListState.sessions])
     * on cold start so title/workdir groups render instantly before the
     * server list is fetched. Written only from `launchLoadSessions`
     * onSuccess (bounded to root sessions). A server refresh later replaces
     * these with authoritative data.
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
     * R-20 Phase 5: per-(profileId, sessionId) draft text. The composite
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
    fun getDraftText(profileId: String, sessionId: String): String {
        val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null) ?: return ""
        return try {
            Json.decodeFromString<Map<String, String>>(json)[compositeSessionKey(profileId, sessionId)] ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ── Draft-text debounce ──────────────────────────────────────────────

    /**
     * §C1 / P1-gate-fix: per-(profileId, sessionId) pending mutations,
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
     * The two locks are nested in ONE direction only (persistLock →
     * debounceLock), by [performDraftWrite]'s §需求12 rev-6 blocker C barrier
     * re-check. This is deadlock-free because no code path nests them the
     * other way: [clearDraftsForProfile] uses two SEQUENTIAL standalone
     * `synchronized` blocks (never holds both at once), and [setDraftText] /
     * [flushDraftText] call [performDraftWrite] OUTSIDE debounceLock. See
     * [performDraftWrite] KDoc for the full wait-for-cycle proof.
     */
    private val persistLock = Any()

    /**
     * §需求12 rev-6 blocker C: profiles whose drafts have been bulk-cleared
     * ([clearDraftsForProfile]) but whose in-flight debounce write-back jobs
     * may still be racing to re-persist. Guarded by [debounceLock]. A
     * write-back job ([performDraftWrite]) re-checks this set INSIDE
     * [persistLock] right before its ESP write — if its profileId is present,
     * the write is dropped (the profile's drafts were cleared/deleted between
     * the job claiming its mutation and acquiring persistLock, so writing
     * would resurrect sensitive data the user expected gone).
     *
     * Entries are added by [clearDraftsForProfile] and removed by the NEXT
     * [setDraftText] for the same profile (a new write after a clear means the
     * profile was re-created / re-selected and its drafts should persist
     * again).
     */
    private val profilesUnderDeletionBarrier: MutableSet<String> = mutableSetOf()

    /** §C1: coalesced pending draft mutation (one entry per composite key). */
    private data class DraftMutation(
        val profileId: String,
        val sessionId: String,
        val text: String,
    )

    /**
     * §C1 / P1-gate-fix: sets the draft text for `(profileId, sessionId)`.
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
    fun setDraftText(profileId: String, sessionId: String, text: String) {
        // §需求12 rev-6 blocker C: a new write for a previously-cleared
        // profile means it's live again (re-created / re-selected after
        // deletion). Lift the deletion barrier BEFORE either write path so the
        // write persists normally. Lifted up here (not inside the debounce
        // block) so the null-scope direct-write path also clears the barrier
        // — otherwise a re-selected profile's first write would be dropped by
        // performDraftWrite's barrier re-check.
        synchronized(debounceLock) { profilesUnderDeletionBarrier.remove(profileId) }

        val scope = debounceScope
        if (scope == null) {
            // No debounce scope → immediate write (direct construction in tests).
            performDraftWrite(profileId, sessionId, text)
            return
        }
        val compositeKey = compositeSessionKey(profileId, sessionId)
        val mutation = DraftMutation(profileId, sessionId, text)
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
                if (m != null) performDraftWrite(m.profileId, m.sessionId, m.text)
            }
        }
    }

    /**
     * §C1 / §P1-gate-fix: cancels ALL pending debounce jobs and writes EVERY
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
        drained.forEach { performDraftWrite(it.profileId, it.sessionId, it.text) }
    }

    /**
     * §需求12 rev-4 blocker B / §需求12 rev-6 blocker C: removes ALL draft
     * entries whose composite key's profileId equals [profileId]. Used on
     * profile deletion so the deleted profile's drafts (potentially sensitive
     * unsent text) don't leak as orphans.
     *
     * ## rev-6 deletion barrier (3 steps, ordered to close EVERY
     * draft-resurrection window)
     *
     * A naive ESP-only clear leaks via the in-memory debounce path: a pending
     * write-back job (or one whose RMW is waiting on [persistLock]) fires
     * AFTER the clear and re-persists the deleted profile's draft. The barrier
     * closes all three windows:
     *
     *  1. **Under [debounceLock]**: mark the profile under barrier
     *     ([profilesUnderDeletionBarrier]) + cancel + remove ALL its pending
     *     mutations + debounce jobs. After this, no NEW write-back can be
     *     scheduled for this profile (the pending slot + timer are gone).
     *  2. **Under [persistLock]**: ESP map clear — removes already-persisted
     *     entries (whole-map RMW, atomic vs concurrent draft writes).
     *  3. **The claimed-but-waiting window**: a write-back job that already
     *     CLAIMED its mutation (removed it from [pendingDrafts] under
     *     [debounceLock]) before step 1 and is now waiting on [persistLock].
     *     Its [performDraftWrite] re-checks [profilesUnderDeletionBarrier]
     *     INSIDE [persistLock] right before the ESP write and DROPS the write
     *     if the profile is under barrier.
     *
     * No-op on blank [profileId] (defensive — caller in
     * [cn.vectory.ocdroid.util.SettingsManager.clearAllForProfile] should
     * always pass a real profile.id, which is a non-blank UUID) and on
     * corrupt/unparseable JSON (leave the user's data alone rather than risk
     * dropping entries on a parse error).
     *
     * ## Lock-ordering (no deadlock)
     *
     * Step 1 acquires [debounceLock] (standalone, released before step 2).
     * Step 2 acquires [persistLock] (standalone). They are SEQUENTIAL, not
     * nested. [performDraftWrite]'s barrier re-check nests [persistLock]→
     * [debounceLock] (briefly), but NO code path nests [debounceLock]→
     * [persistLock] (performDraftWrite is never called while holding
     * [debounceLock]; clearDraftsForProfile's two blocks are sequential).
     * Single nesting direction → no wait-for cycle → deadlock-free.
     */
    fun clearDraftsForProfile(profileId: String) {
        if (profileId.isBlank()) return
        // §需求12 rev-6 blocker C step 1: mark + cancel+remove ALL this
        // profile's pending mutations + debounce jobs under debounceLock.
        // After this no new write-back can be scheduled for this profile.
        synchronized(debounceLock) {
            profilesUnderDeletionBarrier.add(profileId)
            val toCancel = pendingDrafts.keys.filter {
                it.substringBefore(COMPOSITE_KEY_SEPARATOR) == profileId
            }
            toCancel.forEach { compositeKey ->
                debounceJobs.remove(compositeKey)?.cancel()
                pendingDrafts.remove(compositeKey)
            }
        }
        // §需求12 rev-6 blocker C step 2: ESP map clear under persistLock
        // (atomic whole-map RMW, mirrors performDraftWrite).
        synchronized(persistLock) {
            val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null) ?: return@synchronized
            val map: MutableMap<String, String> = try {
                Json.decodeFromString<Map<String, String>>(json).toMutableMap()
            } catch (e: Exception) {
                return@synchronized  // Corrupt — leave alone.
            }
            val toRemove = map.keys.filter { it.substringBefore(COMPOSITE_KEY_SEPARATOR) == profileId }
            if (toRemove.isEmpty()) return@synchronized
            toRemove.forEach { map.remove(it) }
            encryptedPrefs.edit().putString(KEY_SESSION_DRAFTS, Json.encodeToString(map)).apply()
        }
    }

    /**
     * §C1 / P1-gate-fix: mutable-freeze snapshot for testability. Returns
     * the pending mutation for the exact `(profileId, sessionId)`
     * composite key without consuming it, or null when that key has nothing
     * pending.
     */
    internal fun peekPendingDraft(profileId: String, sessionId: String): DraftMutationSnapshot? {
        val compositeKey = compositeSessionKey(profileId, sessionId)
        return synchronized(debounceLock) {
            pendingDrafts[compositeKey]?.let {
                DraftMutationSnapshot(it.profileId, it.sessionId, it.text)
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
                DraftMutationSnapshot(it.profileId, it.sessionId, it.text)
            }
        }

    /** §C1: immutable value class for exposing [DraftMutation] to tests. */
    internal data class DraftMutationSnapshot(
        val profileId: String,
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
     *  - §需求12 rev-6 blocker C: the barrier re-check nests a BRIEF
     *    [debounceLock] read INSIDE [persistLock] (persistLock → debounceLock,
     *    one direction). This is deadlock-free because NO code path nests the
     *    locks the OTHER way:
     *      · [clearDraftsForProfile] step 1 + step 2 are SEQUENTIAL standalone
     *        `synchronized` blocks (step 1 fully releases debounceLock before
     *        step 2 acquires persistLock) → never holds both at once.
     *      · [setDraftText] / [flushDraftText] acquire debounceLock (standalone)
     *        and call [performDraftWrite] OUTSIDE debounceLock.
     *      · [performDraftWrite] is NEVER called while its caller already holds
     *        debounceLock.
     *    Single nesting direction (persistLock→debounceLock) → no wait-for
     *    cycle → deadlock-free.
     *  - Why the barrier read is INSIDE persistLock (not before it): reading
     *    the boolean outside persistLock would leave a resurrection window —
     *    [clearDraftsForProfile] could run (set the barrier + clear ESP)
     *    BETWEEN the outside read and the persistLock acquire, then this RMW
     *    would write the draft back into the now-cleared map. Reading inside
     *    persistLock makes the barrier-check + ESP-write atomic w.r.t.
     *    [clearDraftsForProfile]'s step 2 (which also needs persistLock), so
     *    either the clear fully precedes us (barrier = true → drop) or fully
     *    follows us (its step 2 RMW removes whatever we wrote).
     */
    private fun performDraftWrite(profileId: String, sessionId: String, text: String) {
        synchronized(persistLock) {
            // §需求12 rev-6 blocker C step 3: deletion-barrier re-check. A
            // write-back job may have claimed its mutation (under debounceLock)
            // BEFORE [clearDraftsForProfile] ran, then waited on persistLock
            // while clearDraftsForProfile set the barrier + cleared ESP. By the
            // time we acquire persistLock here, this profile's drafts are gone
            // — writing would resurrect them. Re-check the barrier INSIDE
            // persistLock (brief nested debounceLock read) so the check + the
            // ESP RMW are atomic w.r.t. clearDraftsForProfile step 2.
            val underBarrier = synchronized(debounceLock) { profileId in profilesUnderDeletionBarrier }
            if (underBarrier) return@synchronized
            val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null)
            val map: MutableMap<String, String> = try {
                json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
            } catch (e: Exception) {
                mutableMapOf()
            }
            val key = compositeSessionKey(profileId, sessionId)
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

        // Legacy key left for documentation only — no longer read or written (§B4).
        @Suppress("unused")
        internal const val KEY_OPEN_SESSION_IDS = "open_session_ids"
        internal const val KEY_SESSION_CACHE = "session_cache"
        internal const val KEY_SESSION_DRAFTS = "session_drafts"

        /**
         * R-20 Phase 5: separator used in the composite `(profileId,
         * sessionId)` map key. NUL (\u0000) is chosen because profileId
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
        internal fun compositeSessionKey(profileId: String, sessionId: String): String =
            "$profileId$COMPOSITE_KEY_SEPARATOR$sessionId"
    }
}
