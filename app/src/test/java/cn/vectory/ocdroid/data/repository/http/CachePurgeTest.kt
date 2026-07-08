package cn.vectory.ocdroid.data.repository.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * §key-leak-purge (P1 1A'): pins the one-time OkHttp-cache purge. The pure
 * [shouldCommitPurge] truth table locks the fail-safe contract (marker never
 * written while the cache dir may still hold key-bearing residue);
 * [applyCachePurgeIfNeeded] covers the I/O success paths.
 */
class CachePurgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // --- pure decision: shouldCommitPurge truth table ---

    @Test
    fun `shouldCommitPurge commits when marker absent and dir gone`() {
        assertTrue(shouldCommitPurge(markerAlreadyPresent = false, cacheDirGone = true))
    }

    @Test
    fun `shouldCommitPurge skips when marker already present`() {
        assertFalse(shouldCommitPurge(markerAlreadyPresent = true, cacheDirGone = true))
        assertFalse(shouldCommitPurge(markerAlreadyPresent = true, cacheDirGone = false))
    }

    @Test
    fun `shouldCommitPurge skips when dir remains present (fail-safe)`() {
        // Core fail-safe contract: never write the marker while key-bearing
        // residue may still be on disk — the next launch must retry the purge.
        assertFalse(shouldCommitPurge(markerAlreadyPresent = false, cacheDirGone = false))
    }

    // --- I/O: applyCachePurgeIfNeeded ---

    @Test
    fun `applyCachePurgeIfNeeded short-circuits to true when marker already present`() {
        val dir = tmp.newFolder("okhttp")
        val marker = tmp.newFile("okhttp-cache-purged-v1")
        File(dir, "journal").writeText("x") // dir has content
        assertTrue(applyCachePurgeIfNeeded(marker, dir))
        assertTrue( // dir untouched once marker is present
            File(dir, "journal").exists()
        )
    }

    @Test
    fun `applyCachePurgeIfNeeded commits marker on fresh install (no cache dir)`() {
        val dir = File(tmp.root, "okhttp") // does not exist
        val marker = File(tmp.root, "okhttp-cache-purged-v1")
        assertTrue(applyCachePurgeIfNeeded(marker, dir))
        assertTrue(marker.exists())
    }

    @Test
    fun `applyCachePurgeIfNeeded deletes cache dir and commits marker`() {
        val dir = tmp.newFolder("okhttp")
        File(dir, "journal").writeText("x") // content (would-be residue)
        val marker = File(tmp.root, "okhttp-cache-purged-v1")
        assertTrue(applyCachePurgeIfNeeded(marker, dir))
        assertFalse(dir.exists()) // purged
        assertTrue(marker.exists()) // marked
    }
}
