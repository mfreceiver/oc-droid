package cn.vectory.ocdroid.data.cache

import androidx.room.withTransaction
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.CachedSessionWindow
import cn.vectory.ocdroid.ui.chat.Entry
import cn.vectory.ocdroid.ui.chat.GapFillState
import cn.vectory.ocdroid.ui.chat.GapMarker
import cn.vectory.ocdroid.ui.chat.withGaps
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R-20 Phase 1: facade over the encrypted [CacheDatabase].
 *
 * Interface signature is locked to plan §2 (multi-Phase contract). Phase 1
 * implements the verifyAndLoad / putSessionWindow / evict / clearAll core +
 * verifyFingerprint; Phase 2 (gap methods) and Phase 3 (eviction / sweep)
 * methods are declared but stubbed with safe defaults so callers wired early
 * do not crash (TODO comments mark each stub).
 *
 * **Compound-keyed**: every method takes `serverGroupFp` first, mirroring
 * the DB's compound PK `(server_group_fp, session_id[, message_id])`. The
 * caller (a `(ServerGroupFp) -> String` provider injected via Hilt — see
 * [cn.vectory.ocdroid.di.ControllerModule.provideCurrentServerGroupFp])
 * computes the fp; this repository never derives it from "current profile"
 * on its own (which would race a profile switch).
 *
 * `internal` modifier removed in Phase 1 because the cache layer is wired
 * into Hilt (whose @Provides methods must be public) and consumed by
 * [cn.vectory.ocdroid.ui.AppCore] + several controllers directly. This is an
 * app module (no external consumers), so `public` here is just the Kotlin
 * default, not a published API.
 */
interface CacheRepository {
    // ─────────── Phase 1 core ─────────────────────────────────────────────

    /**
     * Persist a session's message window under `(serverGroupFp, sessionId)`.
     * Atomically replaces any previously-cached window for the same key
     * (deletes old messages, upserts the session row, upserts the new
     * messages) inside a single Room transaction.
     *
     * @param createdAt server-side `Session.time.created` (NULLABLE — server
     *   can return null). Stored as the fingerprint for [verifyAndLoad] /
     *   [verifyFingerprint]; a null value disables fingerprint eviction
     *   (UnknownColdStart).
     * @param workdir the session's workdir (project directory) — Phase 5
     *   groups cached sessions by workdir for cross-connection sharing.
     */
    suspend fun putSessionWindow(
        serverGroupFp: String,
        sessionId: String,
        createdAt: Long?,
        workdir: String,
        window: CachedSessionWindow
    )

    /**
     * Atomic verify-and-load (plan §0 G4 + v4 round-3 glmer I-2).
     *
     * Single Room @Transaction eliminates the TOCTOU between "fingerprint
     * matches" and "load window" — a concurrent evict cannot land between
     * the two reads, which would otherwise let the caller hydrate a window
     * that was just evicted.
     *
     * Outcomes:
     *  - [HydrateResult.Verified] — fingerprint matches AND the cached
     *    window is non-empty; refreshes `lastVerifiedAt = now` (server just
     *    confirmed this session exists) and returns the window.
     *  - [HydrateResult.UnknownColdStart] — `createdAt == null` (server
     *    returned null), no cached row, OR the cached window is empty.
     *    Does NOT evict; the caller falls back to cold-start REST.
     *  - [HydrateResult.MismatchEvicted] — `createdAt != null` AND a cached
     *    row exists AND its stored `createdAt` differs from the argument.
     *    Evicts the cached row (the session identity changed) and signals
     *    the caller to cold-start.
     */
    suspend fun verifyAndLoad(
        serverGroupFp: String,
        sessionId: String,
        createdAt: Long?
    ): HydrateResult

    /**
     * Standalone fingerprint check (no body load). Used by SessionListActions
     * for cross-connection merge detection (Phase 5) and by ConnectionActions
     * cold-start self-consistency. Same three states as [verifyAndLoad].
     */
    suspend fun verifyFingerprint(
        serverGroupFp: String,
        sessionId: String,
        createdAt: Long?
    ): FingerprintResult

    /** Precise eviction: one session + its messages, scoped to `(fp, sid)`. */
    suspend fun evictSession(serverGroupFp: String, sessionId: String)

    /** Group-scoped eviction (异组 host switch / profile delete). */
    suspend fun evictGroup(serverGroupFp: String)

    /** Nuke everything (manual 全清 button + reset). */
    suspend fun clearAll()

    /**
     * Phase 1 helper (maxer I11): a `message.updated` SSE event for a
     * currently-cached session appended a NEW message — persist it so the
     * cache stays in sync without a full `putSessionWindow` round-trip.
     *
     * Skips silently if no cached session row exists (cold-start sessions
     * do not proactively build a cache — only already-cached sessions stay
     * fresh). Phase 2 may refactor this into the gap-aware path.
     */
    suspend fun appendMessageIfSessionCached(
        serverGroupFp: String,
        sessionId: String,
        message: Message,
        parts: List<Part>
    )

    // ─────────── Phase 2 stubs (gap-aware layout) ─────────────────────────
    // Declared for signature stability; bodies land in Phase 2.

    /**
     * Phase 2: gap-aware non-contiguous message layout. Assembles the cached
     * messages for `(serverGroupFp, sessionId)` interleaved with any open
     * [GapMarker]s (sorted by upperBoundary message time ascending). Returns
     * null when no cached session row exists (cold start).
     */
    suspend fun loadSessionLayout(
        serverGroupFp: String,
        sessionId: String
    ): CachedSessionLayout?

    // ─────────── Phase 2: per-gap cursor + state ──────────────────────────
    // Plan §2 signatures. openGap returns the synthetic gapId; the other
    // methods are keyed by it. appendOlderSlice + resolveGap are atomic
    // (single Room transaction) per the GapMarker invariant (plan §3).

    /**
     * Open a new gap marker. Persists a row with the given boundaries + cursor
     * and `state = Idle`. Returns the generated synthetic [GapMarkerEntity.gapId].
     */
    suspend fun openGap(
        serverGroupFp: String,
        sessionId: String,
        lowerAnchorMessageId: String,
        upperBoundaryMessageId: String,
        initialNextBeforeCursor: String
    ): String

    /**
     * Append one backward-fill step for [gapId]. **Single Room transaction**
     * (plan §3 GapMarker invariant):
     *  1. insert the older messages (upsert, dedup by compound PK);
     *  2. if [BackfillAlgorithm.stepCoversAnchor]-equivalent (an older message
     *     id equals ANY open gap's lowerAnchor in this session) → resolve that
     *     gap (delete its marker) inside the SAME transaction — this covers
     *     both the target gap and any cross-gap overlap (one append bridging a
     *     neighbouring gap's anchor);
     *  3. else if [returnedCursor] == null → set the gap's state = Exhausted
     *     (history ended below the gap; UI shows "无法补齐");
     *  4. else → advance `upperBoundaryMessageId = older.oldest().id` +
     *     `nextBeforeCursor = returnedCursor` + `state = Idle` so the next
     *     tap/user-step pages further back.
     *
     * @param returnedCursor the `X-Next-Cursor` from the step's REST response
     *   (null ⇒ history exhausted).
     */
    suspend fun appendOlderSlice(
        gapId: String,
        older: List<Message>,
        partsByMessage: Map<String, List<Part>>,
        returnedCursor: String?
    )

    /** Set the gap's fill state (Idle/Filling/Exhausted/Error). */
    suspend fun setGapState(gapId: String, state: GapFillState)

    /** Atomically resolve (delete) the gap marker — the two slices are contiguous. */
    suspend fun resolveGap(gapId: String)

    /** All open gap markers for `(serverGroupFp, sessionId)`, in upperBoundary-time order. */
    suspend fun gapsOf(serverGroupFp: String, sessionId: String): List<GapMarker>

    // ─────────── Phase 3 stubs (eviction + sweep) ─────────────────────────
    // Declared for signature stability; bodies land in Phase 3. These are
    // interface-level declarations (dser review #8): prior code only had a
    // comment, forcing Phase 3 to change the interface (breaking the locked
    // contract). The impl stubs TODO — they never run in Phase 1 (no caller).

    /** Phase 3: evict orphan sessions (cached but NOT in the alive set). Caller
     *  MUST prove the alive set is complete (plan §3 Phase 3 N4). */
    suspend fun sweepOrphansWithCompleteAliveSet(
        serverGroupFp: String,
        aliveSessionIds: Set<String>
    ): EvictionReport

    /** Phase 3: mark-only degraded sweep — alive set incomplete. Only refreshes
     *  lastVerifiedAt for confirmed-alive sessions; does NOT delete. */
    suspend fun markSeenAliveOnly(
        serverGroupFp: String,
        seenAliveSessionIds: Set<String>
    )

    /** Phase 3: LRU-50 (per fp) + newestCachedAt>30d + lastVerifiedAt>7d. */
    suspend fun applyEvictionPolicy(): EvictionReport

    /** Phase 3: high-level daily sweep entry — alive completeness internalized
     *  (CacheMaintenanceCoordinator enumerates + 24h dedup). Caller does NOT
     *  pass aliveSessionIds. */
    suspend fun dailySweepIfNeeded(serverGroupFp: String): DailySweepReport
}

/**
 * R-20 Phase 3 (plan §2): eviction report returned by [CacheRepository.sweepOrphansWithCompleteAliveSet]
 * and [CacheRepository.applyEvictionPolicy].
 *
 * Declared in the interface file so Phase 1's signature contract is complete
 * (dser review #8); the methods are stubbed in [CacheRepositoryImpl] and never
 * run until Phase 3.
 */
data class EvictionReport(
    val evictedCount: Int,
    val keptCount: Int,
    val orphanIds: List<String>
)

/**
 * R-20 Phase 3 (plan §2): daily sweep report — alive completeness internalized
 * (plan v4 freegpt 建议2). [completeness] drives the Complete→evict vs
 * Incomplete→mark-only degradation path.
 */
data class DailySweepReport(
    val serverGroupFp: String,
    val completeness: AliveCompleteness,
    val verifiedAliveCount: Int,
    val evictedSessionIds: List<String>,
    val suspiciousSessionIds: List<String>
)

/** R-20 Phase 3 (plan §2): alive-set completeness for daily sweep degradation. */
enum class AliveCompleteness { Complete, Incomplete }

/**
 * R-20 Phase 2 (plan §2): gap-aware non-contiguous message layout — the
 * [CacheRepository.loadSessionLayout] return type. [entries] is the
 * [Entry]-list (Message + GapMarker) ordered oldest-first with markers at
 * their seams; [oldestCursor]/[newestMessageId] mirror CachedSessionWindow
 * for the loadMessages merge path.
 */
data class CachedSessionLayout(
    val serverGroupFp: String,
    val sessionId: String,
    val entries: List<Entry>,
    val oldestCursor: String?,
    val newestMessageId: String?
) {
    /**
     * Phase-1-compatible projection: the messages only (gaps dropped), with a
     * synthetic [CachedSessionWindow] envelope. Used by paths that pre-date
     * the gap-aware model (e.g. the in-memory LRU mirror). When there are no
     * gaps this is equivalent to the Phase-1 window.
     */
    fun toCachedSessionWindow(partsByMessage: Map<String, List<Part>>): CachedSessionWindow {
        val messages = entries.mapNotNull { (it as? Entry.Message)?.message }
        return CachedSessionWindow(
            messages = messages,
            partsByMessage = partsByMessage,
            olderMessagesCursor = oldestCursor,
            hasMoreMessages = oldestCursor != null
        )
    }
}

/** Atomic verify-and-load outcome (plan §2 HydrateResult). */
sealed interface HydrateResult {
    data class Verified(val window: CachedSessionWindow) : HydrateResult
    data object UnknownColdStart : HydrateResult
    data object MismatchEvicted : HydrateResult
}

/** Standalone fingerprint outcome (plan §2 FingerprintResult). */
sealed interface FingerprintResult {
    data object Verified : FingerprintResult
    data object UnknownColdStart : FingerprintResult
    data object MismatchEvicted : FingerprintResult
}

/**
 * R-20 Phase 1: Hilt @Singleton implementation. Uses [CacheDatabase.withTransaction]
 * (androidx.room) for atomic verify+load + atomic putSessionWindow replace.
 *
 * **Message ↔ Entity conversion**: the `parts` column is a JSON blob of
 * `List<Part>` serialized with [CacheJson]. The cache layer is the sole
 * reader/writer of the blob, so a forward-compatible Json (ignoreUnknownKeys)
 * is sufficient — if a future Part variant adds a field, older cache rows
 * still deserialize (maxer H10).
 */
@Singleton
class CacheRepositoryImpl @Inject constructor(
    private val dao: CacheDao,
    private val gapDao: GapMarkerDao,
    private val db: CacheDatabase
) : CacheRepository {

    override suspend fun putSessionWindow(
        serverGroupFp: String,
        sessionId: String,
        createdAt: Long?,
        workdir: String,
        window: CachedSessionWindow
    ) {
        val now = System.currentTimeMillis()
        val newestMsgTime = window.messages.maxOfOrNull { it.time?.created ?: now } ?: now
        db.withTransaction {
            // Replace the cached message set atomically: delete + upsert in
            // one transaction so a mid-replace reader never sees a half-
            // populated window.
            dao.deleteSessionMessages(serverGroupFp, sessionId)
            dao.upsertSession(
                CachedSessionEntity(
                    serverGroupFp = serverGroupFp,
                    sessionId = sessionId,
                    createdAt = createdAt,
                    // newestCachedAt is "newest cached message time"
                    // (plan §0). MAX() against the previous value covers the
                    // edge case of a putSessionWindow with an OLDER window
                    // (e.g. a loadMore cached a tail with an earlier newest
                    // than a previous full snapshot) — we keep the newest
                    // known, not the latest-written.
                    newestCachedAt = newestMsgTime,
                    lastVerifiedAt = now,
                    workdir = workdir
                )
            )
            if (window.messages.isNotEmpty()) {
                dao.upsertMessages(window.messages.map { it.toEntity(serverGroupFp, sessionId, window.partsByMessage[it.id]) })
            }
        }
    }

    override suspend fun verifyAndLoad(
        serverGroupFp: String,
        sessionId: String,
        createdAt: Long?
    ): HydrateResult {
        // glmer I-2 / plan §0 G4: single @Transaction eliminates TOCTOU.
        // Without it, a concurrent evictSession could land between the
        // fingerprint check and the window read, letting us hydrate a
        // just-evicted window (stale data) — or worse, evict our own
        // just-loaded window out from under the caller.
        return db.withTransaction {
            // Cold-start short-circuit (momo 实测: server returns createdAt=null).
            // NO evict, NO hydrate — caller falls back to REST.
            if (createdAt == null) return@withTransaction HydrateResult.UnknownColdStart

            val cachedCreatedAt = dao.cachedCreatedAt(serverGroupFp, sessionId)
            when {
                // §review-fix #4 (glm-3 🟠#2): `cachedCreatedAt == null`
                // covers BOTH "no cached row" AND "row exists but the
                // createdAt column is null" (first putSessionWindow happened
                // while the server returned null — the DAO returns null for
                // both cases). Either way we cannot fingerprint-compare →
                // UnknownColdStart (NO evict). This is the symmetric
                // counterpart to the input-null short-circuit above. The
                // prior code's `cachedCreatedAt != createdAt -> evict`
                // branch could never reach this case (this branch wins
                // first), but the explicit comment + assertion locks the
                // invariant against a future re-ordering of the `when`.
                cachedCreatedAt == null -> HydrateResult.UnknownColdStart
                // Both sides non-null AND differ → the session identity
                // changed since we cached it (server recreated the session
                // with the same id but a different createdAt, or the fp
                // collision is real). Evict inside this same transaction so
                // a concurrent reader cannot observe the now-stale row.
                cachedCreatedAt != createdAt -> {
                    dao.deleteSession(serverGroupFp, sessionId)
                    dao.deleteSessionMessages(serverGroupFp, sessionId)
                    HydrateResult.MismatchEvicted
                }
                else -> {
                    // Fingerprint matches: refresh lastVerifiedAt (the server
                    // just confirmed this session exists — counts toward the
                    // 7d abandon rule in Phase 3) and load the window.
                    dao.touchLastVerifiedAt(serverGroupFp, sessionId, System.currentTimeMillis())
                    val msgs = dao.messages(serverGroupFp, sessionId)
                    if (msgs.isEmpty()) {
                        // Row exists but no messages cached yet (e.g. it was
                        // evicted by an LRU pass that left the session row).
                        // Cold-start rather than hydrating an empty view.
                        HydrateResult.UnknownColdStart
                    } else {
                        HydrateResult.Verified(msgs.toWindow())
                    }
                }
            }
        }
    }

    override suspend fun verifyFingerprint(
        serverGroupFp: String,
        sessionId: String,
        createdAt: Long?
    ): FingerprintResult = db.withTransaction {
        if (createdAt == null) return@withTransaction FingerprintResult.UnknownColdStart
        val cachedCreatedAt = dao.cachedCreatedAt(serverGroupFp, sessionId)
        when {
            // §review-fix #4 (symmetry): cached createdAt null (no row OR
            // null column) → cannot compare → UnknownColdStart (no evict).
            cachedCreatedAt == null -> FingerprintResult.UnknownColdStart
            cachedCreatedAt != createdAt -> {
                dao.deleteSession(serverGroupFp, sessionId)
                dao.deleteSessionMessages(serverGroupFp, sessionId)
                FingerprintResult.MismatchEvicted
            }
            else -> {
                dao.touchLastVerifiedAt(serverGroupFp, sessionId, System.currentTimeMillis())
                FingerprintResult.Verified
            }
        }
    }

    override suspend fun evictSession(serverGroupFp: String, sessionId: String) {
        db.withTransaction {
            dao.deleteSession(serverGroupFp, sessionId)
            dao.deleteSessionMessages(serverGroupFp, sessionId)
            gapDao.deleteSessionGaps(serverGroupFp, sessionId)
        }
    }

    override suspend fun evictGroup(serverGroupFp: String) {
        db.withTransaction {
            dao.deleteGroupSessions(serverGroupFp)
            dao.deleteGroupMessages(serverGroupFp)
            gapDao.deleteGroupGaps(serverGroupFp)
        }
    }

    override suspend fun clearAll() {
        db.withTransaction {
            dao.clearAllSessions()
            dao.clearAllMessages()
            gapDao.clearAllGaps()
        }
    }

    override suspend fun appendMessageIfSessionCached(
        serverGroupFp: String,
        sessionId: String,
        message: Message,
        parts: List<Part>
    ) {
        // Only persist if the session is already cached — a cold-start
        // session has no cache row, and proactively creating one from a
        // single SSE message would fragment the cache.
        val now = System.currentTimeMillis()
        db.withTransaction {
            val existing = dao.session(serverGroupFp, sessionId) ?: return@withTransaction
            dao.upsertMessages(listOf(message.toEntity(serverGroupFp, sessionId, parts)))
            val msgTime = message.time?.created ?: now
            if (msgTime > existing.newestCachedAt) {
                dao.bumpNewestCachedAt(serverGroupFp, sessionId, msgTime)
            }
        }
    }

    // ─────────── Phase 2 (gap-aware layout + per-gap ops) ─────────────────

    override suspend fun loadSessionLayout(
        serverGroupFp: String,
        sessionId: String
    ): CachedSessionLayout? = db.withTransaction {
        // Read messages + gaps in one transaction so the assembled layout is
        // consistent (a concurrent appendOlderSlice cannot land between the two
        // reads and produce a marker whose boundary message is not yet present).
        dao.session(serverGroupFp, sessionId) ?: return@withTransaction null
        val msgs = dao.messages(serverGroupFp, sessionId)
        val gaps = gapDao.gaps(serverGroupFp, sessionId)
        if (msgs.isEmpty() && gaps.isEmpty()) return@withTransaction null
        val messages = msgs.map { it.toMessage() }
        val gapMarkers = gaps.map { it.toDomain() }
        val entries = messages.withGaps(gapMarkers)
        CachedSessionLayout(
            serverGroupFp = serverGroupFp,
            sessionId = sessionId,
            entries = entries,
            // oldestCursor: derived from the oldest cached message id + time
            // (the server cursor is base64url({id,time_ms}) — but Phase 1
            // never persisted a server cursor for the cache window, so we
            // surface null here and let loadMessages re-establish it via the
            // resetLimit=false merge. This matches the Phase-1 toWindow path.)
            oldestCursor = null,
            newestMessageId = messages.maxByOrNull { it.time?.created ?: -1L }?.id
        )
    }

    override suspend fun openGap(
        serverGroupFp: String,
        sessionId: String,
        lowerAnchorMessageId: String,
        upperBoundaryMessageId: String,
        initialNextBeforeCursor: String
    ): String {
        val now = System.currentTimeMillis()
        val gapId = UUID.randomUUID().toString()
        gapDao.upsertGap(
            GapMarkerEntity(
                gapId = gapId,
                serverGroupFp = serverGroupFp,
                sessionId = sessionId,
                lowerAnchorMessageId = lowerAnchorMessageId,
                upperBoundaryMessageId = upperBoundaryMessageId,
                nextBeforeCursor = initialNextBeforeCursor.takeIf { it.isNotEmpty() },
                state = GapFillState.Idle.name,
                createdAt = now,
                updatedAt = now
            )
        )
        return gapId
    }

    override suspend fun appendOlderSlice(
        gapId: String,
        older: List<Message>,
        partsByMessage: Map<String, List<Part>>,
        returnedCursor: String?
    ) {
        if (older.isEmpty() && returnedCursor != null) {
            // Nothing new but more history remains — just advance the cursor.
            // (Rare; kept for completeness.) §fix-#4: read the gap row inside
            // the same transaction as the advance (single-transaction invariant
            // — a concurrent resolveGap cannot land between the read here and
            // the advance below, which would otherwise resurrect a resolved
            // gap's cursor).
            db.withTransaction {
                val g = gapDao.gap(gapId) ?: return@withTransaction
                gapDao.advanceBoundary(
                    gapId = gapId,
                    upperBoundary = g.upperBoundaryMessageId,
                    cursor = returnedCursor,
                    state = GapFillState.Idle.name,
                    now = System.currentTimeMillis()
                )
            }
            return
        }
        val now = System.currentTimeMillis()
        // §fix-#4 (gpter #4 TOCTOU): the gap row + sessionGaps reads were
        // previously OUTSIDE this transaction, weakening the single-transaction
        // invariant (a concurrent resolveGap could land between the read and
        // the transaction → upsert/resolve against a stale gap snapshot). Move
        // BOTH reads inside so the snapshot is consistent with the writes. The
        // early `target == null` return now lives inside the transaction; the
        // outer function delegates the whole atomic read + insert + resolve /
        // advance / exhaust block to one Room @Transaction.
        db.withTransaction {
            val target = gapDao.gap(gapId)
            if (target == null) {
                // Gap already resolved (e.g. a concurrent overlap resolve, or a
                // stale step after the user dismissed it). We cannot upsert the
                // messages without the (fp, sessionId) compound key, and a
                // resolved gap means the slices are already contiguous — drop
                // the step.
                return@withTransaction
            }
            // ① insert the older slice.
            if (older.isNotEmpty()) {
                dao.upsertMessages(
                    older.map {
                        it.toEntity(target.serverGroupFp, target.sessionId, partsByMessage[it.id])
                    }
                )
            }
            // ② cross-gap overlap + anchor resolution: if any older id equals
            // the lowerAnchor of ANY open gap in this session, that gap is now
            // bridged → resolve it inside this same transaction. This covers
            // both the target gap and any neighbouring gap whose anchor this
            // append happened to reach (plan §3 "跨 gap overlap → 同事务 resolve").
            val olderIds = older.map { it.id }.toHashSet()
            val sessionGaps = gapDao.gaps(target.serverGroupFp, target.sessionId)
            // Resolve the target gap if its anchor was reached ...
            val targetResolved = target.lowerAnchorMessageId in olderIds
            if (targetResolved) {
                gapDao.deleteGap(gapId)
            }
            // ... and resolve every sibling gap whose anchor this append covered
            // (cross-gap overlap — one backward step may bridge a neighbour).
            for (g in sessionGaps) {
                if (g.gapId != gapId && g.lowerAnchorMessageId in olderIds) {
                    gapDao.deleteGap(g.gapId)
                }
            }
            if (targetResolved) return@withTransaction
            // ③ cursor exhausted below the gap → state = Exhausted.
            if (returnedCursor == null) {
                gapDao.setState(gapId, GapFillState.Exhausted.name, now)
                return@withTransaction
            }
            // ④ advance boundary + cursor, state = Idle.
            val newBoundary = older.minByOrNull { it.time?.created ?: Long.MAX_VALUE }
                ?.id ?: target.upperBoundaryMessageId
            gapDao.advanceBoundary(
                gapId = gapId,
                upperBoundary = newBoundary,
                cursor = returnedCursor,
                state = GapFillState.Idle.name,
                now = now
            )
        }
    }

    override suspend fun setGapState(gapId: String, state: GapFillState) {
        gapDao.setState(gapId, state.name, System.currentTimeMillis())
    }

    override suspend fun resolveGap(gapId: String) {
        // Atomic per plan §3. Single-statement DELETE is itself atomic in
        // SQLite; the withTransaction wrapper documents the invariant and
        // keeps the call site uniform with appendOlderSlice.
        db.withTransaction {
            gapDao.deleteGap(gapId)
        }
    }

    override suspend fun gapsOf(serverGroupFp: String, sessionId: String): List<GapMarker> =
        gapDao.gaps(serverGroupFp, sessionId).map { it.toDomain() }

    // ─────────── Phase 3 stubs (impl) ──────────────────────────────────────
    // TODO("Phase 3") — these never run in Phase 1/2 (no caller). Phase 3 fills
    // them with the real LRU/age/alive logic. Declared so the interface
    // signature is locked (dser review #8).

    override suspend fun sweepOrphansWithCompleteAliveSet(
        serverGroupFp: String,
        aliveSessionIds: Set<String>
    ): EvictionReport = TODO("Phase 3: sweepOrphansWithCompleteAliveSet")

    override suspend fun markSeenAliveOnly(
        serverGroupFp: String,
        seenAliveSessionIds: Set<String>
    ): Unit = TODO("Phase 3: markSeenAliveOnly")

    override suspend fun applyEvictionPolicy(): EvictionReport =
        TODO("Phase 3: applyEvictionPolicy")

    override suspend fun dailySweepIfNeeded(serverGroupFp: String): DailySweepReport =
        TODO("Phase 3: dailySweepIfNeeded")

    // ─────────── helpers ──────────────────────────────────────────────────

    private fun Message.toEntity(serverGroupFp: String, sessionId: String, parts: List<Part>?): CachedMessageEntity {
        // time: prefer created, fall back to completed, fall back to now.
        // The cache MUST be able to sort messages even if the server omits
        // both — a missing sort key would otherwise break the ASC ordering
        // contract.
        val now = System.currentTimeMillis()
        val time = time?.created ?: time?.completed ?: now
        return CachedMessageEntity(
            serverGroupFp = serverGroupFp,
            sessionId = sessionId,
            messageId = id,
            time = time,
            role = role,
            parts = CacheJson.encodeToString(ListSerializer(Part.serializer()), parts ?: emptyList())
        )
    }

    private fun List<CachedMessageEntity>.toWindow(): CachedSessionWindow {
        // Reconstruct the split-store view the UI expects: messages are the
        // info list, partsByMessage is parsed from the per-row JSON blob.
        // (Phase 2's gap-aware layout will not use this path — it adds gap
        // markers between messages.)
        val messages = map { it.toMessage() }
        val partsByMessage = associateBy(
            keySelector = { it.messageId },
            valueTransform = { row ->
                runCatching { CacheJson.decodeFromString(ListSerializer(Part.serializer()), row.parts) }
                    .getOrElse { emptyList() }
            }
        )
        return CachedSessionWindow(
            messages = messages,
            partsByMessage = partsByMessage,
            // Phase 1 has no older-cursor concept — loadMessages will
            // re-establish the cursor via resetLimit=false (cache hit) merge.
            olderMessagesCursor = null,
            hasMoreMessages = true
        )
    }

    private fun CachedMessageEntity.toMessage(): Message = Message(
        id = messageId,
        sessionId = sessionId,
        role = role,
        time = Message.TimeInfo(created = time)
    )

    private fun GapMarkerEntity.toDomain(): GapMarker = GapMarker(
        gapId = gapId,
        lowerAnchorMessageId = lowerAnchorMessageId,
        upperBoundaryMessageId = upperBoundaryMessageId,
        nextBeforeCursor = nextBeforeCursor,
        // Forward-compatible state decode: an unknown column value (future
        // state added without a migration) falls back to Idle.
        fillState = runCatching { GapFillState.valueOf(state) }.getOrDefault(GapFillState.Idle)
    )
}
