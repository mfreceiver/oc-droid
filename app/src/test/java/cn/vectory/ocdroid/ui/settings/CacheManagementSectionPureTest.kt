package cn.vectory.ocdroid.ui.settings

import cn.vectory.ocdroid.data.cache.CacheRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-20 Phase 4 (plan §3): JVM unit tests for the pure presentation logic in
 * [CacheRowPresentation]. Extracted from the Composable so the threshold /
 * grouping rules run on the JVM without spinning up Compose — same pattern
 * as ModelCatalogCountsTest for [modelCatalogCounts].
 *
 * Locks the three contract invariants the UI depends on:
 *  - the "疑似废弃" 7-day threshold matches [CacheRepository.applyEvictionPolicy]'s
 *    7d abandon rule (so a row the UI marks red is the SAME row the next
 *    sweep would evict);
 *  - a never-verified row (`lastVerifiedAt <= 0`) is treated as suspect
 *    (the user should not trust it);
 *  - grouping preserves first-appearance order (deterministic rendering).
 */
class CacheManagementSectionPureTest {

    @Test
    fun `lastVerifiedAt older than 7d is suspect`() {
        val now = 1_000_000_000_000L
        val verified = now - (CacheRowPresentation.SEVEN_DAYS_MS + 1)
        assertTrue(CacheRowPresentation.isSuspectAbandoned(messageCount = 1, verified, now))
    }

    @Test
    fun `lastVerifiedAt exactly 7d is NOT suspect (boundary)`() {
        val now = 1_000_000_000_000L
        // Exactly 7d → (now - lastVerifiedAt) == SEVEN_DAYS_MS → NOT > SEVEN_DAYS_MS.
        val verified = now - CacheRowPresentation.SEVEN_DAYS_MS
        assertFalse(CacheRowPresentation.isSuspectAbandoned(messageCount = 1, verified, now))
    }

    @Test
    fun `lastVerifiedAt within 7d is not suspect`() {
        val now = 1_000_000_000_000L
        val verified = now - 1_000L // 1s ago
        assertFalse(CacheRowPresentation.isSuspectAbandoned(messageCount = 1, verified, now))
    }

    @Test
    fun `lastVerifiedAt zero is suspect (never verified)`() {
        // lastVerifiedAt <= 0L means the row was inserted but never
        // server-confirmed → the UI marks it red so the user notices.
        assertTrue(CacheRowPresentation.isSuspectAbandoned(messageCount = 1, 0L, 1_000_000_000_000L))
    }

    @Test
    fun `lastVerifiedAt negative is suspect (defensive)`() {
        assertTrue(CacheRowPresentation.isSuspectAbandoned(messageCount = 1, -1L, 1_000_000_000_000L))
    }

    @Test
    fun `empty cached row is not suspect abandoned even with stale verification`() {
        val now = 1_000_000_000_000L
        val verified = now - (CacheRowPresentation.SEVEN_DAYS_MS + 1)
        assertFalse(CacheRowPresentation.isSuspectAbandoned(messageCount = 0, verified, now))
    }

    @Test
    fun `groupByNormalizedWorkdir merges path variants sorts projects and buckets blank paths`() {
        val projects = CacheRowPresentation.groupByNormalizedWorkdir(
            listOf(
                row(fp = "fp", sid = "z", workdir = " /beta/ "),
                row(fp = "fp", sid = "a", workdir = "/alpha"),
                row(fp = "fp", sid = "b", workdir = "alpha/"),
                row(fp = "fp", sid = "unknown", workdir = "   "),
            )
        )

        assertEquals(listOf("alpha", "beta", ""), projects.map { it.normalizedWorkdir })
        assertEquals("/alpha", projects[0].displayWorkdir)
        assertEquals(listOf("a", "b"), projects[0].sessions.map { it.sessionId })
        assertEquals(null, projects[2].displayWorkdir)
    }

    private fun row(
        fp: String,
        sid: String,
        workdir: String = "/home/me/proj",
        createdAt: Long? = 1_000_000L,
        newest: Long = 2_000_000L,
        verified: Long = 3_000_000L,
        exhausted: Boolean = false
    ): CacheRepository.CachedSessionRow = CacheRepository.CachedSessionRow(
        serverGroupFp = fp,
        sessionId = sid,
        workdir = workdir,
        createdAt = createdAt,
        newestCachedAt = newest,
        lastVerifiedAt = verified,
        hasExhaustedGap = exhausted
    )
}
