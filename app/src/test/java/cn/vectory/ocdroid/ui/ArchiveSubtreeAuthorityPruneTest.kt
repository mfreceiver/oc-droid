package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.ScopeKey
import cn.vectory.ocdroid.data.state.SessionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §P0-A rev-gpt #2 / #7 / #8 (SITES lane): the SSE-driven archive
 * ([reduceSessionArchived]) and the bulk-refresh archive
 * ([reduceBulkSessionsRefreshed]) MUST prune the WHOLE archived subtree from
 * `authority.bySid` and recompute the `sessionList.sessionStatuses` projection
 * via [reduceAuthority] (the SOLE writer of sessionStatuses). Pre-fix these two
 * paths removed/archived the session in the list but left its entry in
 * `authority.bySid`, so an archived session stayed Busy in the aggregator's
 * derived view forever.
 *
 * This mirrors the LOCAL archive/delete subtree tests
 * ([SessionListFieldsReducer] / `AppActionReducerTest`) but covers the two
 * server-driven paths they do NOT (SSE `session.updated (archived)` +
 * `BulkSessionsRefreshed`). Deliberately NOT in [AuthorityReducerTest] /
 * [cn.vectory.ocdroid.service.status.StatusAggregatorImplTest] (CORE lane owns
 * those files).
 *
 * §B10 (#4): also pins the sole-writer gate — the public `SessionListState`
 * factory seeds `sessionStatuses = emptyMap()` and `copy(...)` has no
 * `sessionStatuses` param, so [SessionListState.withProjection] (called only by
 * [reduceAuthority]) is the only way to set a non-empty projection.
 */
class ArchiveSubtreeAuthorityPruneTest {

    private val parentSid = "parent"
    private val childSid = "child"
    private val otherSid = "other"

    private val busy = SessionStatus(type = "busy")
    private val idle = SessionStatus(type = "idle")

    /** A Busy authority entry (the pre-archive state of an archived session). */
    private fun busyEntry(workdir: String? = null) = SessionEntry(
        status = busy,
        serverRound = null,
        optimisticClaim = null,
        origin = EntryOrigin.REST,
        updatedAtMs = 0L,
        workdir = workdir,
    )

    // ═══════════════════════════════════════════════════════════════════════
    // #2 SSE archive (reduceSessionArchived) — subtree prune of authority
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `SSE archive prunes the whole subtree from authority bySid and the projection`() {
        // parent(archived root) + child(parentId=parent) form a subtree; `other`
        // is unrelated. Seed authority.bySid Busy for parent + child, idle for
        // other. The projection mirrors the seeded bySid (sole-writer invariant).
        val parentSes = Session(id = parentSid, directory = "/parent")
        val childSes = Session(id = childSid, directory = "/child", parentId = parentSid)
        val otherSes = Session(id = otherSid, directory = "/other")
        val prior = StoreState.initial().copy(
            authority = AuthorityState(
                bySid = mapOf(
                    parentSid to busyEntry("/parent"),
                    childSid to busyEntry("/child"),
                    otherSid to SessionEntry(
                        status = idle, serverRound = null, optimisticClaim = null,
                        origin = EntryOrigin.REST,
                        updatedAtMs = 0L, workdir = "/other",
                    ),
                ),
            ),
            sessionList = SessionListState(
                sessions = listOf(parentSes, childSes, otherSes),
                childSessions = mapOf(parentSid to listOf(childSes)),
            ).withProjection(mapOf(parentSid to busy, childSid to busy, otherSid to idle)),
        )

        val out = reduce(prior, AppAction.SessionArchived(parentSes))

        // authority.bySid: parent + child (the WHOLE subtree) pruned; other survives.
        assertFalse("parent pruned from authority.bySid", out.authority.bySid.containsKey(parentSid))
        assertFalse("child pruned from authority.bySid (subtree)", out.authority.bySid.containsKey(childSid))
        assertTrue("unrelated other survives in authority.bySid", out.authority.bySid.containsKey(otherSid))
        assertEquals("authority retained exactly the unrelated entry", 1, out.authority.bySid.size)

        // sessionStatuses projection mirrors the pruned authority (the archived
        // subtree is NOT stuck Busy — the regression this test guards against).
        assertFalse("parent pruned from projection", out.sessionList.sessionStatuses.containsKey(parentSid))
        assertFalse("child pruned from projection (subtree)", out.sessionList.sessionStatuses.containsKey(childSid))
        assertEquals("other survives in projection as idle", idle, out.sessionList.sessionStatuses[otherSid])
    }

    // ═══════════════════════════════════════════════════════════════════════
    // #2 Bulk-refresh archive (reduceBulkSessionsRefreshed) — subtree prune
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `bulk archive prunes the whole subtree from authority bySid and the projection`() {
        // The merged refresh list carries an archived parent (time.archived>0);
        // its child (parentId=parent) did NOT get its own archive event but
        // MUST still be pruned from authority (defensive subtree cleanup).
        val parentSes = Session(
            id = parentSid, directory = "/parent",
            time = Session.TimeInfo(archived = 1_000L),
        )
        val childSes = Session(id = childSid, directory = "/child", parentId = parentSid)
        val otherSes = Session(id = otherSid, directory = "/other")
        val prior = StoreState.initial().copy(
            authority = AuthorityState(
                bySid = mapOf(
                    parentSid to busyEntry("/parent"),
                    childSid to busyEntry("/child"),
                    otherSid to SessionEntry(
                        status = idle, serverRound = null, optimisticClaim = null,
                        origin = EntryOrigin.REST,
                        updatedAtMs = 0L, workdir = "/other",
                    ),
                ),
            ),
            sessionList = SessionListState(
                sessions = listOf(parentSes, childSes, otherSes),
                childSessions = mapOf(parentSid to listOf(childSes)),
            ).withProjection(mapOf(parentSid to busy, childSid to busy, otherSid to idle)),
        )

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(parentSes, childSes, otherSes),
                hasMoreSessions = false,
                confirmedServerIds = setOf(parentSid, childSid, otherSid),
                sweepNow = 0L,
            ),
        )

        // authority.bySid: the archived parent + its child pruned; other survives.
        assertFalse("parent pruned from authority.bySid", out.authority.bySid.containsKey(parentSid))
        assertFalse("child pruned from authority.bySid (subtree)", out.authority.bySid.containsKey(childSid))
        assertTrue("unrelated other survives in authority.bySid", out.authority.bySid.containsKey(otherSid))
        assertEquals("authority retained exactly the unrelated entry", 1, out.authority.bySid.size)

        // sessionStatuses projection mirrors the pruned authority.
        assertFalse("parent pruned from projection", out.sessionList.sessionStatuses.containsKey(parentSid))
        assertFalse("child pruned from projection (subtree)", out.sessionList.sessionStatuses.containsKey(childSid))
        assertEquals("other survives in projection as idle", idle, out.sessionList.sessionStatuses[otherSid])
    }

    // ═══════════════════════════════════════════════════════════════════════
    // #4 B10 sole-writer gate
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `B10 sole-writer gate - public factory seeds empty sessionStatuses and copy excludes it`() {
        // The public all-args factory MUST NOT accept sessionStatuses: it seeds
        // the constructor default (emptyMap). (A `.copy(sessionStatuses = …)`
        // call FAILS TO COMPILE — no such param — that IS the gate; this test
        // pins the behavioural contract the gate enforces.)
        val seeded = SessionListState(
            sessions = listOf(Session(id = "s1", directory = "/p")),
            activeSessionIds = setOf("s1"),
        )
        assertEquals(
            "factory never seeds sessionStatuses (sole-writer gate)",
            emptyMap<String, SessionStatus>(),
            seeded.sessionStatuses,
        )

        // The ONLY way to set a non-empty sessionStatuses is withProjection.
        val projected = seeded.withProjection(mapOf("s1" to busy))
        assertEquals("withProjection sets sessionStatuses", busy, projected.sessionStatuses["s1"])
        assertEquals("withProjection is the sole non-empty writer", 1, projected.sessionStatuses.size)

        // copy() preserves the projection it was given — it has NO sessionStatuses
        // param, so it cannot reset/alter the projection.
        val copied = projected.copy(activeSessionIds = emptySet())
        assertEquals(
            "copy() preserves the projection (does not touch sessionStatuses)",
            busy,
            copied.sessionStatuses["s1"],
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // #5 resolveScopeKey derivation (real scope)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `resolveScopeKey returns empty scope on cold start (no host)`() {
        val state = StoreState.initial()
        assertEquals(ScopeKey("", ""), state.resolveScopeKey())
    }

    @Test
    fun `resolveScopeKey uses profile id when serverGroupFp is blank`() {
        // §需求12: serverGroupFp field is deleted; resolveScopeKey always
        // returns profile.id (the former blank-fallback is now the only path).
        val profileId = "prof-uuid-1"
        val state = StoreState.initial().copy(
            host = HostState(
                hostProfiles = listOf(HostProfile(id = profileId, name = "p1", serverUrl = "http://h")),
                currentHostProfileId = profileId,
            ),
            liveEndpointFp = "ep-fp-1",
        )
        val scope = state.resolveScopeKey()
        assertEquals("uses profile id", profileId, scope.profileId)
        assertEquals("endpointFp from liveEndpointFp", "ep-fp-1", scope.endpointFp)
    }

    @Test
    fun `resolveScopeKey uses profile id`() {
        // §需求12阶段3: under 需求12 profileId == profile.id always, so
        // resolveScopeKey returns profile.id directly (the former
        // `serverGroupFp.ifBlank { id }` normalization collapsed).
        val profileId = "prof-uuid-2"
        val state = StoreState.initial().copy(
            host = HostState(
                hostProfiles = listOf(HostProfile(
                    id = profileId, name = "p2", serverUrl = "http://h",
                )),
                currentHostProfileId = profileId,
            ),
            liveEndpointFp = "ep-fp-2",
        )
        val scope = state.resolveScopeKey()
        assertEquals("uses current profile id", profileId, scope.profileId)
        assertEquals("endpointFp from liveEndpointFp", "ep-fp-2", scope.endpointFp)
    }

    @Test
    fun `resolveScopeKey ignores non-current profile`() {
        val currentId = "prof-current"
        val otherId = "prof-other"
        val state = StoreState.initial().copy(
            host = HostState(
                hostProfiles = listOf(
                    HostProfile(id = otherId, name = "other", serverUrl = "http://h"),
                    HostProfile(id = currentId, name = "current", serverUrl = "http://h"),
                ),
                currentHostProfileId = currentId,
            ),
        )
        assertEquals("scope uses current profile id", currentId, state.resolveScopeKey().profileId)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // #6 SSE/bulk archive with real authority scope (host configured)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `SSE archive with configured host prunes subtree using real scope`() {
        val profileId = "prof-arch"
        val parentSes = Session(id = parentSid, directory = "/parent")
        val childSes = Session(id = childSid, directory = "/child", parentId = parentSid)
        val otherSes = Session(id = otherSid, directory = "/other")
        val prior = StoreState.initial().copy(
            host = HostState(
                hostProfiles = listOf(HostProfile(id = profileId, name = "a", serverUrl = "http://h")),
                currentHostProfileId = profileId,
            ),
            liveEndpointFp = "ep-arch",
            authority = AuthorityState(
                bySid = mapOf(
                    parentSid to busyEntry("/parent"),
                    childSid to busyEntry("/child"),
                    otherSid to SessionEntry(
                        status = idle, serverRound = null, optimisticClaim = null,
                        origin = EntryOrigin.REST,
                        updatedAtMs = 0L, workdir = "/other",
                    ),
                ),
            ),
            sessionList = SessionListState(
                sessions = listOf(parentSes, childSes, otherSes),
                childSessions = mapOf(parentSid to listOf(childSes)),
            ).withProjection(mapOf(parentSid to busy, childSid to busy, otherSid to idle)),
        )

        // Smoke: scope is non-empty when host is configured.
        assertTrue("scope has non-empty profileId", prior.resolveScopeKey().profileId.isNotEmpty())
        assertTrue("scope has non-empty endpointFp", prior.resolveScopeKey().endpointFp.isNotEmpty())

        val out = reduce(prior, AppAction.SessionArchived(parentSes))

        assertFalse("parent pruned from authority.bySid", out.authority.bySid.containsKey(parentSid))
        assertFalse("child pruned from authority.bySid", out.authority.bySid.containsKey(childSid))
        assertTrue("other survives in authority.bySid", out.authority.bySid.containsKey(otherSid))
    }

    @Test
    fun `bulk archive with configured host prunes subtree using real scope`() {
        val profileId = "prof-bulk"
        val parentSes = Session(
            id = parentSid, directory = "/parent",
            time = Session.TimeInfo(archived = 1_000L),
        )
        val childSes = Session(id = childSid, directory = "/child", parentId = parentSid)
        val otherSes = Session(id = otherSid, directory = "/other")
        val prior = StoreState.initial().copy(
            host = HostState(
                hostProfiles = listOf(HostProfile(id = profileId, name = "b", serverUrl = "http://h")),
                currentHostProfileId = profileId,
            ),
            liveEndpointFp = "ep-bulk",
            authority = AuthorityState(
                bySid = mapOf(
                    parentSid to busyEntry("/parent"),
                    childSid to busyEntry("/child"),
                    otherSid to SessionEntry(
                        status = idle, serverRound = null, optimisticClaim = null,
                        origin = EntryOrigin.REST,
                        updatedAtMs = 0L, workdir = "/other",
                    ),
                ),
            ),
            sessionList = SessionListState(
                sessions = listOf(parentSes, childSes, otherSes),
                childSessions = mapOf(parentSid to listOf(childSes)),
            ).withProjection(mapOf(parentSid to busy, childSid to busy, otherSid to idle)),
        )

        assertTrue("scope has non-empty profileId", prior.resolveScopeKey().profileId.isNotEmpty())
        assertTrue("scope has non-empty endpointFp", prior.resolveScopeKey().endpointFp.isNotEmpty())

        val out = reduce(
            prior,
            AppAction.BulkSessionsRefreshed(
                sessions = listOf(parentSes, childSes, otherSes),
                hasMoreSessions = false,
                confirmedServerIds = setOf(parentSid, childSid, otherSid),
                sweepNow = 0L,
            ),
        )

        assertFalse("parent pruned from authority.bySid", out.authority.bySid.containsKey(parentSid))
        assertFalse("child pruned from authority.bySid", out.authority.bySid.containsKey(childSid))
        assertTrue("other survives in authority.bySid", out.authority.bySid.containsKey(otherSid))
    }
}
