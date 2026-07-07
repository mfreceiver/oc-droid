package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.data.cache.CacheRepository
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-20 Phase 5 — [attemptCrossGroupMerge] / [directoriesMatchOrIntersect]
 * coverage + the loadSessions → merge wiring.
 *
 * Plan §3 G1 三条件 (id + createdAt + directory + non-empty). The merge is
 * one-directional (otherFp → currentFp); a mismatch on any single condition
 * is NOT a merge signal. Idempotent via [HostProfileStore.mergeServerGroup].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListMergeTest {

    private lateinit var cacheRepository: CacheRepository
    private lateinit var hostProfileStore: HostProfileStore

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        cacheRepository = mockk(relaxed = true)
        hostProfileStore = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ───────────────── directoriesMatchOrIntersect ─────────────────

    @Test
    fun `directoriesMatchOrIntersect returns true for equal paths`() {
        assertTrue(directoriesMatchOrIntersect("/repo", "/repo"))
    }

    @Test
    fun `directoriesMatchOrIntersect returns true when trailing slash differs`() {
        assertTrue(directoriesMatchOrIntersect("/repo", "/repo/"))
        assertTrue(directoriesMatchOrIntersect("/repo/", "/repo"))
    }

    @Test
    fun `directoriesMatchOrIntersect returns true for parent-child`() {
        assertTrue(directoriesMatchOrIntersect("/repo/sub", "/repo"))
        assertTrue(directoriesMatchOrIntersect("/repo", "/repo/sub"))
    }

    @Test
    fun `directoriesMatchOrIntersect returns false for sibling dirs`() {
        // /repo vs /repo-other must NOT match (prefix-only would false-positive).
        assertFalse(directoriesMatchOrIntersect("/repo", "/repo-other"))
        assertFalse(directoriesMatchOrIntersect("/repo-other", "/repo"))
    }

    @Test
    fun `directoriesMatchOrIntersect returns false when either side is blank`() {
        assertFalse(directoriesMatchOrIntersect(null, "/repo"))
        assertFalse(directoriesMatchOrIntersect("", "/repo"))
        assertFalse(directoriesMatchOrIntersect("/repo", ""))
    }

    // ───────────────── attemptCrossGroupMerge — three-condition contract ─────────────────

    @Test
    fun `merge triggers when id + createdAt + dir + non-empty all match`() = runTest {
        val fetched = listOf(
            Session(id = "ses_x", directory = "/repo", title = "t", time = Session.TimeInfo(created = 1000L)),
        )
        val cached = CacheRepository.CachedSessionRow(
            serverGroupFp = "other-fp",
            sessionId = "ses_x",
            workdir = "/repo",
            createdAt = 1000L,
            newestCachedAt = 5000L, // non-empty
            lastVerifiedAt = 0L,
            messageCount = 1,
            hasExhaustedGap = false,
        )
        coEvery { cacheRepository.allServerGroupFps() } returns listOf("other-fp")
        coEvery { cacheRepository.listGroupSessions("other-fp") } returns listOf(cached)

        attemptCrossGroupMerge(cacheRepository, hostProfileStore, { "current-fp" }, fetched)

        coVerify(exactly = 1) { hostProfileStore.mergeServerGroup(from = "other-fp", into = "current-fp") }
        coVerify(exactly = 1) { cacheRepository.mergeCacheGroup(fromFp = "other-fp", intoFp = "current-fp") }
    }

    @Test
    fun `merge aborts before profile merge when live fp changes after cache work`() = runTest {
        var currentFp = "current-fp"
        val fetched = listOf(
            Session(id = "ses_x", directory = "/repo", title = "t", time = Session.TimeInfo(created = 1000L)),
        )
        val cached = CacheRepository.CachedSessionRow(
            serverGroupFp = "other-fp",
            sessionId = "ses_x",
            workdir = "/repo",
            createdAt = 1000L,
            newestCachedAt = 5000L,
            lastVerifiedAt = 0L,
            messageCount = 1,
            hasExhaustedGap = false,
        )
        coEvery { cacheRepository.allServerGroupFps() } returns listOf("other-fp")
        coEvery { cacheRepository.listGroupSessions("other-fp") } returns listOf(cached)
        coEvery { cacheRepository.mergeCacheGroup(any(), any()) } answers { currentFp = "new-fp" }

        attemptCrossGroupMerge(cacheRepository, hostProfileStore, { currentFp }, fetched)

        coVerify(exactly = 1) { cacheRepository.mergeCacheGroup(fromFp = "other-fp", intoFp = "current-fp") }
        coVerify(exactly = 0) { hostProfileStore.mergeServerGroup(any(), any()) }
    }

    @Test
    fun `merge cache failure aborts profile merge`() = runTest {
        val fetched = listOf(
            Session(id = "ses_x", directory = "/repo", title = "t", time = Session.TimeInfo(created = 1000L)),
        )
        val cached = CacheRepository.CachedSessionRow(
            serverGroupFp = "other-fp",
            sessionId = "ses_x",
            workdir = "/repo",
            createdAt = 1000L,
            newestCachedAt = 5000L,
            lastVerifiedAt = 0L,
            messageCount = 1,
            hasExhaustedGap = false,
        )
        coEvery { cacheRepository.allServerGroupFps() } returns listOf("other-fp")
        coEvery { cacheRepository.listGroupSessions("other-fp") } returns listOf(cached)
        coEvery { cacheRepository.mergeCacheGroup(any(), any()) } throws java.io.IOException("disk")

        try {
            attemptCrossGroupMerge(cacheRepository, hostProfileStore, { "current-fp" }, fetched)
        } catch (_: java.io.IOException) {
            // launchLoadSessions wraps this helper in runCatching; direct unit
            // test only verifies profile merge was not persisted after failure.
        }

        coVerify(exactly = 0) { hostProfileStore.mergeServerGroup(any(), any()) }
    }

    @Test
    fun `merge does NOT trigger when createdAt differs`() = runTest {
        val fetched = listOf(
            Session(id = "ses_x", directory = "/repo", title = "t", time = Session.TimeInfo(created = 1000L)),
        )
        val cached = CacheRepository.CachedSessionRow(
            serverGroupFp = "other-fp",
            sessionId = "ses_x",
            workdir = "/repo",
            createdAt = 9999L, // different createdAt
            newestCachedAt = 5000L,
            lastVerifiedAt = 0L,
            messageCount = 1,
            hasExhaustedGap = false,
        )
        coEvery { cacheRepository.allServerGroupFps() } returns listOf("other-fp")
        coEvery { cacheRepository.listGroupSessions("other-fp") } returns listOf(cached)

        attemptCrossGroupMerge(cacheRepository, hostProfileStore, { "current-fp" }, fetched)

        coVerify(exactly = 0) { hostProfileStore.mergeServerGroup(any(), any()) }
    }

    @Test
    fun `merge does NOT trigger when cached session is empty`() = runTest {
        val fetched = listOf(
            Session(id = "ses_x", directory = "/repo", title = "t", time = Session.TimeInfo(created = 1000L)),
        )
        val cached = CacheRepository.CachedSessionRow(
            serverGroupFp = "other-fp",
            sessionId = "ses_x",
            workdir = "/repo",
            createdAt = 1000L,
            newestCachedAt = 5000L, // metadata can look fresh even when empty
            lastVerifiedAt = 0L,
            messageCount = 0, // EMPTY — never had a message cached
            hasExhaustedGap = false,
        )
        coEvery { cacheRepository.allServerGroupFps() } returns listOf("other-fp")
        coEvery { cacheRepository.listGroupSessions("other-fp") } returns listOf(cached)

        attemptCrossGroupMerge(cacheRepository, hostProfileStore, { "current-fp" }, fetched)

        coVerify(exactly = 0) { hostProfileStore.mergeServerGroup(any(), any()) }
    }

    @Test
    fun `merge does NOT trigger when directories do not intersect`() = runTest {
        val fetched = listOf(
            Session(id = "ses_x", directory = "/repo-a", title = "t", time = Session.TimeInfo(created = 1000L)),
        )
        val cached = CacheRepository.CachedSessionRow(
            serverGroupFp = "other-fp",
            sessionId = "ses_x",
            workdir = "/repo-b", // sibling, not parent-child
            createdAt = 1000L,
            newestCachedAt = 5000L,
            lastVerifiedAt = 0L,
            messageCount = 1,
            hasExhaustedGap = false,
        )
        coEvery { cacheRepository.allServerGroupFps() } returns listOf("other-fp")
        coEvery { cacheRepository.listGroupSessions("other-fp") } returns listOf(cached)

        attemptCrossGroupMerge(cacheRepository, hostProfileStore, { "current-fp" }, fetched)

        coVerify(exactly = 0) { hostProfileStore.mergeServerGroup(any(), any()) }
    }

    @Test
    fun `merge does NOT trigger when fetched session has null createdAt`() = runTest {
        val fetched = listOf(
            Session(id = "ses_x", directory = "/repo", title = "t", time = Session.TimeInfo(created = null)),
        )
        val cached = CacheRepository.CachedSessionRow(
            serverGroupFp = "other-fp",
            sessionId = "ses_x",
            workdir = "/repo",
            createdAt = 1000L,
            newestCachedAt = 5000L,
            lastVerifiedAt = 0L,
            messageCount = 1,
            hasExhaustedGap = false,
        )
        coEvery { cacheRepository.allServerGroupFps() } returns listOf("other-fp")
        coEvery { cacheRepository.listGroupSessions("other-fp") } returns listOf(cached)

        attemptCrossGroupMerge(cacheRepository, hostProfileStore, { "current-fp" }, fetched)

        coVerify(exactly = 0) { hostProfileStore.mergeServerGroup(any(), any()) }
    }

    // ───────────────── scope / iteration semantics ─────────────────

    @Test
    fun `merge skips the current fp itself`() = runTest {
        // allServerGroupFps includes "current-fp"; the loop must skip it
        // (a profile merging into itself is a degenerate no-op even though
        // mergeServerGroup guards it too).
        val fetched = listOf(
            Session(id = "ses_x", directory = "/repo", title = "t", time = Session.TimeInfo(created = 1000L)),
        )
        coEvery { cacheRepository.allServerGroupFps() } returns listOf("current-fp")

        attemptCrossGroupMerge(cacheRepository, hostProfileStore, { "current-fp" }, fetched)

        coVerify(exactly = 0) { cacheRepository.listGroupSessions("current-fp") }
        coVerify(exactly = 0) { hostProfileStore.mergeServerGroup(any(), any()) }
    }

    @Test
    fun `merge fires exactly once - first match wins, later candidates skipped`() = runTest {
        val fetched = listOf(
            Session(id = "ses_x", directory = "/repo", title = "t", time = Session.TimeInfo(created = 1000L)),
        )
        val cached1 = CacheRepository.CachedSessionRow(
            serverGroupFp = "other-fp-1",
            sessionId = "ses_x",
            workdir = "/repo",
            createdAt = 1000L,
            newestCachedAt = 5000L,
            lastVerifiedAt = 0L,
            messageCount = 1,
            hasExhaustedGap = false,
        )
        val cached2 = CacheRepository.CachedSessionRow(
            serverGroupFp = "other-fp-2",
            sessionId = "ses_x",
            workdir = "/repo",
            createdAt = 1000L,
            newestCachedAt = 5000L,
            lastVerifiedAt = 0L,
            messageCount = 1,
            hasExhaustedGap = false,
        )
        coEvery { cacheRepository.allServerGroupFps() } returns listOf("other-fp-1", "other-fp-2")
        coEvery { cacheRepository.listGroupSessions("other-fp-1") } returns listOf(cached1)
        coEvery { cacheRepository.listGroupSessions("other-fp-2") } returns listOf(cached2)

        attemptCrossGroupMerge(cacheRepository, hostProfileStore, { "current-fp" }, fetched)

        // Exactly one merge (the first matching fp wins; the second is not even
        // queried because the loop returns after the first match).
        coVerify(exactly = 1) { hostProfileStore.mergeServerGroup(any(), any()) }
        coVerify(exactly = 1) { hostProfileStore.mergeServerGroup(from = "other-fp-1", into = "current-fp") }
    }

    @Test
    fun `merge is a no-op when allServerGroupFps is empty`() = runTest {
        coEvery { cacheRepository.allServerGroupFps() } returns emptyList()

        attemptCrossGroupMerge(
            cacheRepository,
            hostProfileStore,
            { "current-fp" },
            listOf(Session(id = "ses_x", directory = "/repo", title = "t")),
        )

        coVerify(exactly = 0) { hostProfileStore.mergeServerGroup(any(), any()) }
    }

    @Test
    fun `merge is a no-op when fetched sessions is empty`() = runTest {
        coEvery { cacheRepository.allServerGroupFps() } returns listOf("other-fp")

        attemptCrossGroupMerge(cacheRepository, hostProfileStore, { "current-fp" }, emptyList())

        coVerify(exactly = 0) { hostProfileStore.mergeServerGroup(any(), any()) }
    }

    @Test
    fun `merge swallows listGroupSessions failure and continues to next fp`() = runTest {
        val fetched = listOf(
            Session(id = "ses_x", directory = "/repo", title = "t", time = Session.TimeInfo(created = 1000L)),
        )
        val cachedGood = CacheRepository.CachedSessionRow(
            serverGroupFp = "good-fp",
            sessionId = "ses_x",
            workdir = "/repo",
            createdAt = 1000L,
            newestCachedAt = 5000L,
            lastVerifiedAt = 0L,
            messageCount = 1,
            hasExhaustedGap = false,
        )
        coEvery { cacheRepository.allServerGroupFps() } returns listOf("broken-fp", "good-fp")
        coEvery { cacheRepository.listGroupSessions("broken-fp") } throws java.io.IOException("disk error")
        coEvery { cacheRepository.listGroupSessions("good-fp") } returns listOf(cachedGood)

        attemptCrossGroupMerge(cacheRepository, hostProfileStore, { "current-fp" }, fetched)

        // Did not bail on the broken-fp exception; carried on to good-fp.
        coVerify(exactly = 1) { hostProfileStore.mergeServerGroup(from = "good-fp", into = "current-fp") }
    }

    @Test
    fun `merge is a no-op when currentServerGroupFp is blank`() = runTest {
        attemptCrossGroupMerge(
            cacheRepository,
            hostProfileStore,
            { "" },
            listOf(Session(id = "ses_x", directory = "/repo", title = "t")),
        )
        // Defensive guard: a blank fp would corrupt the merge direction.
        coVerify(exactly = 0) { cacheRepository.allServerGroupFps() }
    }
}
