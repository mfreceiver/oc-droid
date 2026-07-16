package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.controller.allSessionsById
import cn.vectory.ocdroid.ui.controller.rootIdOf
import cn.vectory.ocdroid.ui.controller.treeIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

internal val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * T5-review I4 — the canonical `Json` for embedded SSE payload decoders
 * (`parseSessionStatusEvent` / `parseQuestionAskedEvent`).
 *
 * The prior `parseSessionStatusEvent` used the default `Json` (strict —
 * fails on unknown keys + explicit nulls) and `parseQuestionAskedEvent`
 * used [lenientJson] (only `ignoreUnknownKeys=true`). Neither matched the
 * binding contract that production SSE payloads carry unknown future
 * fields, can omit nullable fields, and benefit from coerced defaults.
 * This single canonical instance unifies the config:
 *  - `explicitNulls = false` — omitted fields decode to null defaults.
 *  - `ignoreUnknownKeys = true` — server-side additions don't drop events.
 *  - `coerceInputValues = true` — explicit JSON nulls on non-nullable
 *    properties with defaults coerce to those defaults; unknown enum
 *    values coerce to the default enum entry; out-of-range numerics
 *    coerce to their bounds. It does NOT coerce wrong types (a JSON
 *    array where a String is expected still throws → the parser returns
 *    null via the enclosing runCatching, as the ViewModelSupportParseTest
 *    "fails strict deserialization" case pins).
 *  - `encodeDefaults = true` — round-trip stable (encoder symmetry).
 *
 * Used ONLY by the two SSE payload parsers (the broader `lenientJson`
 * stays untouched for the SessionSyncCoordinator's other paths).
 */
internal val ssePayloadJson = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

// R-09: NOISY_SSE_LOG_EVENTS 已下沉到 data/api/SseLogFilter.kt（消除 data 层对 ui
// 层的反向依赖）。UI 侧使用处（MainViewModelSyncActions）改为正向 import
// cn.vectory.ocdroid.data.api.NOISY_SSE_LOG_EVENTS。

internal object MainViewModelTimings {
    const val sessionPageSize = 10
    /**
     * Full-load limit for the initial global session fetch. The Sessions tab
     * now shows ALL root sessions across every connected project (flat, by
     * time.updated desc, no load-more UI) — so the initial fetch must pull a
     * generous page rather than the 10-row [sessionPageSize] preview.
     *
     * 500 is a pragmatic "effectively all" cap: connected projects' sessions
     * are ALSO covered comprehensively by the per-workdir `directorySessions`
     * fan-out (see `loadInitialData`), so the global list is supplementary.
     * `hasMoreSessions` evaluates false at this size, so load-more never
     * triggers from the UI. Named (not a magic number) so it is tunable.
     */
    const val sessionFullLoadLimit = 500
    const val messageRetryDelayMs = 400L
    /**
     * Initial message page size when opening a session.
     *
     * Tuned for the post-LSP-diagnostics-cleanup payload: with `state.metadata.
     * diagnostics` stripped (server `lsp:false` + DB purge + client-side strip),
     * measured per-message sizes are p50=3.3KB / p90=17.8KB / p99=81KB /
     * max=840KB. A page of 40 messages is therefore ~712KB typical (p90) and
     * ~3.2MB at p99 — both well under the 32MB ResponseSizeGuard. The
     * pathological all-outliers case (40 × 840KB = 33.6MB) would exceed the
     * guard, but 40 messages all hitting the observed maximum is a low-
     * probability scenario.
     *
     * Acceptance: this failure mode is explicitly accepted (product decision
     * confirmed). If the guard ever rejects an initial response, the existing
     * error/retry UI surfaces it to the user — the value is never silently
     * reduced. 40 gives the user full recent context on open without paging
     * for typical sessions; deeper history via loadMoreMessages.
     */
    const val initialMessagePageSize = 40
    /**
     * Tail page size for catch-up (reconnect / foreground return). Runs
     * frequently, so kept smaller than the initial page; 10 messages ≈ 180KB
     * (p90), negligible per-reconnect cost while pulling more recent context
     * than the old fixed 4.
     */
    const val catchUpMessagePageSize = 10
    /**
     * Catch-up probe: 拉最新几条用于断线补齐. A dedicated 5-message probe is
     * fetched and merged into the slice (resetLimit=false semantics) so the
     * newest messages land without a full reload.
     */
    const val catchUpProbePageSize = 5
    /**
     * Page size for manual "load more history" paging. A typical page stays
     * well under the 32MB guard (30 × p99(81KB) ≈ 2.4MB). If a pathological page
     * ever trips the guard, loadMore surfaces the error and the user can retry
     * — it never blocks opening the session.
     */
    const val historyMessagePageSize = 30
    /** Delay before the one-shot title refresh after a new session's first
     *  message (see MainViewModel.scheduleTitleRefreshAfterFirstMessage). The
     *  server generates the title asynchronously in prompt-loop step 1; 5s is
     *  enough for the small/cheap title model to finish in practice. */
    const val titleRefreshDelayMs = 5000L
    /**
     * §Q4-strict-sync: how long a freshly-created session's id stays in
     * [SessionListState.pendingCreateIds] before the next successful REST
     * refresh sweeps it (trust the server — if the id has not propagated to
     * the listing within this window, it likely never will). The sweep runs
     * inline inside launchLoadSessions/launchLoadMoreSessions onSuccess
     * (no dedicated coroutine) by comparing `now - session.time.created`
     * against this threshold. 30 s matches the server's typical propagation
     * latency.
     */
    const val pendingCreateTimeoutMs = 30_000L
    /**
     * §Q4-strict-sync: cap on the number of root-session metadata entries
     * [persistSessionCache] writes to SharedPreferences (was: open + current +
     * currentWorkdir roots only; now: ALL non-archived root sessions). 200 is
     * a pragmatic ESP-inflation guard — beyond this, entries are trimmed by
     * time.updated desc (keep the most recently active). Product-accepted for
     * users with >500 sessions (the cap drops oldest metadata; sessions are
     * always re-fetchable from the server).
     */
    const val sessionCacheCap = 200
}

internal data class SessionCreatedEvent(
    val session: Session
)

internal data class SessionStatusEvent(
    val sessionId: String,
    val status: SessionStatus
)

internal data class MessagePartDeltaEvent(
    val sessionId: String,
    val messageId: String?,
    val partId: String?,
    val partType: String,
    val delta: String?,
    /** Full accumulated text from the server's part object, when present.
     *  More reliable than delta accumulation; the server can send this
     *  intermittently as a sync point.  When non-null, replaces the
     *  streaming text for this part entirely (overwriting prior deltas). */
    val text: String? = null
)

internal fun errorMessageOrFallback(throwable: Throwable?, fallback: String): String {
    Log.e("OC_ERROR", "error surfaced to UI", throwable)
    val message = throwable?.message?.trim().orEmpty()
    return if (message.isEmpty()) fallback else message
}

internal fun parseSessionCreatedEvent(event: SSEEvent): SessionCreatedEvent? {
    val sessionJson = event.payload.getJsonObject("session") ?: return null
    return runCatching {
        // lenientJson (ignoreUnknownKeys = true): the server's Session.Info
        // carries fields absent from the local Session model (model/agent/...);
        // the strict default Json would throw and silently drop the event —
        // which is exactly why session titles stopped updating over SSE.
        SessionCreatedEvent(lenientJson.decodeFromString<Session>(sessionJson.toString()))
    }.getOrNull()
}

internal fun parseSessionUpdatedEvent(event: SSEEvent): Session? {
    val sessionJson = event.payload.getJsonObject("info")
        ?: event.payload.getJsonObject("session")
        ?: return null
    return runCatching {
        lenientJson.decodeFromString<Session>(sessionJson.toString())
    }.getOrNull()
}

internal fun upsertSession(sessions: List<Session>, session: Session): List<Session> {
    return listOf(session) + sessions.filter { it.id != session.id }
}

internal fun bumpSessionUpdated(sessions: List<Session>, sessionId: String, updated: Long): List<Session> {
    return sessions.map { session ->
        if (session.id == sessionId) {
            session.copy(time = session.time.withUpdatedAtLeast(updated))
        } else {
            session
        }
    }
}

/**
 * Merge a refreshed server snapshot of sessions ([refreshed]) into the local
 * cached list ([local]) while preserving sessions the user is actively using.
 *
 * Time/title reconciliation (the [base] pass) is unchanged: when the local
 * copy of a session carries a strictly-newer `time.updated` (e.g. it was just
 * bumped by a session.updated SSE that brought the server-authoritative
 * title), prefer the local title and lift the remote time so a concurrent
 * full refresh does not clobber it.
 *
 * §Q4-strict-sync: the [preserve] pass now keeps a local-only session IFF its
 * id is in [pendingCreateIds] (the "just created, not yet confirmed by the
 * server" set). This is STRICTER than the legacy `currentSessionId ||
 * openSessionIds` rule: a session that was opened locally but is NOT in the
 * server's authoritative listing AND is NOT pending-create will now drop
 * naturally on refresh (the "ghost after server-side cleanup" fix). The final
 * list is `authoritative ∪ local.filter { id in pendingCreateIds }`.
 *
 * [currentSessionId] and [openSessionIds] are retained in the signature for
 * call-site compatibility but are NO LONGER used in the preserve filter —
 * pendingCreateIds is the sole authority. (They were kept to minimise the
 * call-site diff; a future cleanup can drop them.)
 */
internal fun mergeRefreshedSessionsPreservingLocalActivity(
    refreshed: List<Session>,
    local: List<Session>,
    currentSessionId: String?,
    openSessionIds: Set<String>,
    pendingCreateIds: Set<String> = emptySet(),
): List<Session> {
    val localById = local.associateBy { it.id }
    val refreshedIds = refreshed.map { it.id }.toSet()
    val base = refreshed.map { remote ->
        val localSession = localById[remote.id]
        val localUpdated = localSession?.time?.updated
        val remoteUpdated = remote.time?.updated
        if (localSession != null && localUpdated != null && (remoteUpdated == null || localUpdated > remoteUpdated)) {
            // §Q4-strict-sync semantic 2 (per-id fresher-wins): the local copy
            // carries a strictly-newer time.updated (e.g. it was just bumped by
            // a send-message that elevated it above the server's stale listing
            // snapshot). Keep the LOCAL session entirely — its time.updated is
            // already the freshest, and using the local object preserves any
            // local-side state (title from SSE, revert cutoff, etc.) that a
            // stale server response would null out.
            //
            // This does NOT re-introduce ghosts: ghosts are sessions the server
            // DELETED (absent from the refreshed list entirely). Those are
            // governed by the preserve pass below (pendingCreateIds only).
            // Semantic 2 only applies to ids the server DID return (in-refreshed);
            // it just picks the fresher OBJECT for each such id.
            //
            // §revert-cutoff (A3-1): using localSession preserves the local
            // revert field too — a stale refresh that omits `revert` cannot
            // null it out (fail-closed: keep whichever side carries a revert,
            // and since the local copy is fresher, it wins outright).
            // §Q2-cold-start: agent/model are server-authoritative and never
            // mutated locally; if the fresher local copy is missing them (e.g.
            // it came from a pre-fix cache entry), backfill from the remote
            // snapshot so the context menu isn't left blank after the refresh.
            if ((localSession.agent == null && remote.agent != null) ||
                (localSession.model == null && remote.model != null)) {
                localSession.copy(
                    agent = localSession.agent ?: remote.agent,
                    model = localSession.model ?: remote.model,
                )
            } else {
                localSession
            }
        } else {
            remote
        }
    }
    // §Q4-strict-sync semantic 1 (strict ghost removal): preserve ONLY
    // pending-create ids not in the refreshed (authoritative) set. This is
    // the formula:
    //   final = authoritative ∪ local.filter { id in pendingCreateIds }
    val preserve = local.filter {
        it.id !in refreshedIds && it.id in pendingCreateIds
    }
    return base + preserve
}

/**
 * Returns a copy of this TimeInfo whose [updated] field is at least [updated]
 * (monotonic — never goes backwards). Used by the recent-sessions sort bump
 * (§recent-sort-by-message) and by the merge path that preserves a strictly-
 * newer local updated vs a stale server snapshot.
 *
 * Internal so the SSE-fold pure functions in [SessionSyncCoordinator] can
 * reuse the same monotonic bump semantics (single source of truth).
 */
internal fun Session.TimeInfo?.withUpdatedAtLeast(updated: Long): Session.TimeInfo {
    val currentUpdated = this?.updated
    return (this ?: Session.TimeInfo()).copy(
        updated = if (currentUpdated == null || updated > currentUpdated) updated else currentUpdated
    )
}

internal fun nextSessionFetchLimit(current: Int, pageSize: Int = MainViewModelTimings.sessionPageSize): Int {
    return maxOf(current, pageSize) + maxOf(pageSize, 1)
}

/**
 * §1C-FIX-⑦ / scheme D.1: count of pending permission + question requests
 * from sessions OTHER than [currentSessionId]. Drives the Sessions nav
 * badge in [cn.vectory.ocdroid.ui.shell.AppShell].
 *
 * The Chat StatusSlot renders the CURRENT session's pending items (P5-7
 * session-scope filter), so the badge is the only surface for cross-session
 * pending items. Counting ALL pending here would double-count the current
 * session (its items would appear BOTH in the StatusSlot AND inflate the
 * badge).
 *
 * §task6 tree aggregation (question only):
 *  - **permission** keeps the precise `sessionId != currentSessionId` rule.
 *    A sub-agent's permission is its OWN permission (the user must see the
 *    exact session that wants to run the tool — aggregating to root would
 *    hide which descendant needs consent).
 *  - **question** switches to tree-aware: a question whose session is
 *    anywhere inside the current session's ROOT tree counts as "current"
 *    (it will surface on the current root's QuestionCard via
 *    [cn.vectory.ocdroid.ui.controller.questionsInTree], so it must NOT
 *    inflate the badge). Questions outside the current tree count as cross-
 *    session.
 *
 * Edge case: `currentSessionId == null` (no session open) → currentTree is
 * empty → every question is cross-session (matches the pre-tree behaviour
 * where null excluded nothing). Unknown currentSessionId (not in byId) is
 * treated identically: rootIdOf returns null → empty tree → all questions
 * count, which matches the user's mental model ("I can't be looking at a
 * session I don't know about").
 */
internal fun crossSessionPendingCount(
    state: SessionListState,
    currentSessionId: String?
): Int {
    val byId = allSessionsById(state.sessions, state.directorySessions, state.childSessions)
    val currentRoot = currentSessionId?.let { rootIdOf(it, byId) }
    val currentTree = currentRoot?.let { treeIds(it, byId) }.orEmpty()
    val permissions = state.pendingPermissions.count { it.sessionId != currentSessionId }
    val questions = state.pendingQuestions.count { it.sessionId !in currentTree }
    return permissions + questions
}

internal fun parseSessionStatusEvent(event: SSEEvent): SessionStatusEvent? {
    val sessionId = event.payload.getString("sessionID") ?: return null
    val statusJson = event.payload.getJsonObject("status") ?: return null
    return runCatching {
        SessionStatusEvent(
            sessionId = sessionId,
            // T5-review I4: canonical SSE-payload Json (was default Json —
            // strict, failed on unknown keys). Unknown status fields now
            // tolerated; nullable omissions decode to defaults.
            status = ssePayloadJson.decodeFromString<SessionStatus>(statusJson.toString())
        )
    }.getOrNull()
}

internal fun parseMessagePartDeltaEvent(event: SSEEvent): MessagePartDeltaEvent? {
    val sessionId = event.payload.getString("sessionID") ?: return null
    val partObj = event.payload.getJsonObject("part")
    val messageId = (partObj?.get("messageID") as? JsonPrimitive)?.content
    val partId = (partObj?.get("id") as? JsonPrimitive)?.content
    val partType = (partObj?.get("type") as? JsonPrimitive)?.content ?: "text"
    val partText = (partObj?.get("text") as? JsonPrimitive)?.content
    return MessagePartDeltaEvent(
        sessionId = sessionId,
        messageId = messageId,
        partId = partId,
        partType = partType,
        delta = event.payload.getString("delta"),
        text = partText
    )
}

internal fun parseQuestionAskedEvent(event: SSEEvent): QuestionRequest? {
    val properties = event.payload.properties ?: return null
    return runCatching {
        // T5-review I4: canonical SSE-payload Json (was lenientJson — only
        // ignoreUnknownKeys; missing explicitNulls/coerce/encodeDefaults).
        ssePayloadJson.decodeFromString<QuestionRequest>(properties.toString())
    }.getOrNull()
}

internal fun reasoningPartOrNull(partType: String, partId: String, messageId: String, sessionId: String): Part? {
    return if (partType == "reasoning") {
        Part(id = partId, messageId = messageId, sessionId = sessionId, type = "reasoning")
    } else {
        null
    }
}

/**
 * Whether a part [type] is streamed through `streamingPartTexts` and rendered by
 * PartView's TextPart / ReasoningCard. Other types (tool, patch, file,
 * step-start, step-finish, …) are NOT streamed this way — their content arrives
 * via the REST reload as a full Part. Centralized so the part.updated and
 * part.delta handlers agree, and so a future non-streamable type doesn't
 * silently accumulate dead text in the streaming overlay.
 */
internal fun isStreamablePartType(type: String): Boolean = type == "text" || type == "reasoning"

internal fun reportNonFatalIssue(tag: String, message: String, throwable: Throwable? = null) {
    if (throwable != null) {
        Log.w(tag, message, throwable)
    } else {
        Log.w(tag, message)
    }
}

