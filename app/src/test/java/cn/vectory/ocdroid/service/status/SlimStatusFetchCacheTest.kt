package cn.vectory.ocdroid.service.status

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [SlimStatusFetchCache] — guards the cross-caller
 * cacheKey alignment that enables the 2N→1 background dedup (SlimApi P2).
 *
 * The critical regression that was broken before the fix: [StatusFetchService]
 * used `cacheKey = dir` (a workdir PATH) while [BackgroundUnreadPoller] used
 * `startHostId ?: "default"` (a host PROFILE ID). Since the cache checks
 * `cached.cacheKey == cacheKey`, these NEVER matched across the two callers
 * → every cross-caller lookup was a miss → 2N→1 dedup was completely broken.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlimStatusFetchCacheTest {

    /** TTL constant from the production code, used to test expiry boundaries. */
    private val ttl = SlimStatusFetchCache.TTL_MS

    @Test
    fun `same cacheKey within TTL returns cached result and calls repo once`() = runTest {
        var now = 0L
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSlimapiSessionsStatus(any()) } returns Result.success(
            mapOf("s1" to mockk(), "s2" to mockk()),
        )
        val cache = SlimStatusFetchCache(repo, clock = { now })

        // First call: should hit the repo
        val r1 = cache.fetchGlobal("/repo", "host-A")
        assertTrue("first call succeeds", r1.isSuccess)

        // Second call: within TTL, same cacheKey → cache hit, no second repo call
        now += ttl - 1 // just barely inside TTL
        val r2 = cache.fetchGlobal("/repo", "host-A")
        assertTrue("second call (cached) succeeds", r2.isSuccess)

        coVerify(exactly = 1) { repo.getSlimapiSessionsStatus(any()) }
    }

    @Test
    fun `same cacheKey after TTL expiry calls repo twice`() = runTest {
        var now = 0L
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSlimapiSessionsStatus(any()) } returns Result.success(
            mapOf("s1" to mockk()),
        )
        val cache = SlimStatusFetchCache(repo, clock = { now })

        cache.fetchGlobal("/repo", "host-A")
        now += ttl // exactly at TTL boundary: now - cached.timestampMs >= TTL_MS → miss
        cache.fetchGlobal("/repo", "host-A")

        coVerify(exactly = 2) { repo.getSlimapiSessionsStatus(any()) }
    }

    @Test
    fun `different cacheKey always causes a miss even well within TTL`() = runTest {
        var now = 0L
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSlimapiSessionsStatus(any()) } returns Result.success(
            mapOf("s1" to mockk()),
        )
        val cache = SlimStatusFetchCache(repo, clock = { now })

        // Call with host-A key
        cache.fetchGlobal("/repo", "host-A")
        // Call with host-B key (different host scope → must NOT reuse cache)
        now += 1 // well within TTL but different key → miss
        cache.fetchGlobal("/repo", "host-B")

        // This is the regression guard: before the fix, StatusFetchService used
        // cacheKey=dir (path) and BackgroundUnreadPoller used cacheKey=hostId,
        // so TWO different keys were used by the two callers → NEVER deduped.
        coVerify(exactly = 2) { repo.getSlimapiSessionsStatus(any()) }
    }

    @Test
    fun `failure is cached within TTL to prevent retry storms`() = runTest {
        var now = 0L
        var callCount = 0
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSlimapiSessionsStatus(any()) } coAnswers {
            callCount++
            Result.failure(RuntimeException("transient error #$callCount"))
        }
        val cache = SlimStatusFetchCache(repo, clock = { now })

        val r1 = cache.fetchGlobal("/repo", "host-A")
        assertTrue("first call returns failure", r1.isFailure)

        // Second call within TTL, same key → should return the cached failure
        now += 1
        val r2 = cache.fetchGlobal("/repo", "host-A")
        assertTrue("second call (cached failure) also returns failure", r2.isFailure)

        // Both callers hitting a transient error within 10s should NOT trigger
        // a retry storm — the cached failure suppresses the second network call.
        coVerify(exactly = 1) { repo.getSlimapiSessionsStatus(any()) }
        assertEquals("only 1 actual network call", 1, callCount)
    }

    @Test
    fun `concurrent single-flight coalesces simultaneous requests`() = runTest {
        var now = 0L
        val gate = CompletableDeferred<Unit>()
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        coEvery { repo.getSlimapiSessionsStatus(any()) } coAnswers {
            // Block until the gate completes — forces overlap between two coroutines
            gate.await()
            Result.success(mapOf("s1" to mockk()))
        }
        val cache = SlimStatusFetchCache(repo, clock = { now })

        // Launch two concurrent fetchGlobal calls for the same cacheKey.
        // The Mutex inside the cache serializes them; the second caller
        // waits for the first to complete, then sees the cached entry.
        val (r1, r2) = coroutineScope {
            val deferred1 = async { cache.fetchGlobal("/repo", "host-A") }
            val deferred2 = async { cache.fetchGlobal("/repo", "host-A") }
            // Brief yield to let both coroutines reach the mutex
            kotlinx.coroutines.yield()
            // Release the gate so the first caller completes
            gate.complete(Unit)
            deferred1.await() to deferred2.await()
        }

        assertTrue("first concurrent call succeeds", r1.isSuccess)
        assertTrue("second concurrent call succeeds (coalesced)", r2.isSuccess)
        // Only ONE actual repo call across both concurrent callers
        coVerify(exactly = 1) { repo.getSlimapiSessionsStatus(any()) }
    }
}
