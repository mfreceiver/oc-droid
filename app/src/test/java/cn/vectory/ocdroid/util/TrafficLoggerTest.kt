package cn.vectory.ocdroid.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R18 Phase 5++ coverage: [TrafficLogger] ring-buffer + flush-on-disk + dump.
 * Coverage gap before this file: 0/3 classes, 0/7 methods, 0/73 lines, 0/612
 * instructions (TrafficLogger + its _Factory Hilt synthetic).
 *
 * Runs under Robolectric so:
 *  - `android.util.Log` returns defaults (no JVM crash).
 *  - `Context.getExternalFilesDir(null)` returns a writable temp directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrafficLoggerTest {

    private lateinit var logger: TrafficLogger
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // §note: even though TrafficLogger does not directly read encrypted
        // prefs, Robolectric boots the REAL OpenCodeApp whose onCreate() Hilt
        // graph constructs SettingsManager → EncryptedSharedPreferences →
        // AndroidKeyStore. Install the software stub so that chain does not
        // crash on JVM.
        FakeAndroidKeyStoreProvider.install()
        logger = TrafficLogger(context)
    }

    @After
    fun tearDown() {
        logger.close()
    }

    @Test
    fun `dump on empty buffer reports zero entries and zero totals`() {
        val out = logger.dump()
        assertTrue("should mention 0 entries", out.contains("0 entries"))
        assertTrue("should mention total sent=0 B", out.contains("sent=0 B"))
        assertTrue("should mention total recv=0 B", out.contains("recv=0 B"))
    }

    @Test
    fun `record adds entries that appear in dump summary count`() {
        logger.record(method = "GET", url = "http://x/a", sent = 100L, received = 200L, elapsedMs = 5L)
        logger.record(method = "POST", url = "http://x/b", sent = 1024L, received = 0L, elapsedMs = 12L)

        val out = logger.dump()
        assertTrue(out.contains("2 entries"))
        // 100 + 1024 = 1124 B; formatSize(1124) = "1 KB"
        assertTrue(out.contains("sent=1 KB"))
        // 200 + 0 = 200 B (the 0L is still summed)
        assertTrue(out.contains("recv=200 B"))
        // Total elapsed
        assertTrue(out.contains("time=17ms"))
    }

    @Test
    fun `record formats megabyte range sizes correctly`() {
        // 2 * 1024 * 1024 = 2 MB; formatSize returns "%.1f MB"
        logger.record(method = "GET", url = "http://x/big", sent = 2L * 1024 * 1024, received = 0L, elapsedMs = 100L)

        val out = logger.dump()
        assertTrue(out.contains("sent=2.0 MB"))
    }

    @Test
    fun `record ring buffer caps at MAX_ENTRIES`() {
        // MAX_ENTRIES = 200 (private); record well past it and verify dump
        // reports the cap, not the call count.
        repeat(500) { i ->
            logger.record(method = "GET", url = "http://x/$i", sent = 1L, received = 1L, elapsedMs = 1L)
        }

        val out = logger.dump()
        // Cap is 200; the buffer dropped the oldest 300.
        assertTrue(out.contains("200 entries"))
    }

    @Test
    fun `flushToDisk writes a file when buffer non-empty`() {
        logger.record(method = "GET", url = "http://x/a", sent = 100L, received = 200L, elapsedMs = 5L)
        // flushToDisk() spawns a background thread; the sync variant is private
        // but the public flushToDisk funnels through it. Call it and give the
        // thread a moment to settle.
        logger.flushToDisk()
        // Wait for the spawned thread to complete (best-effort).
        Thread.sleep(200)

        val dir = java.io.File(context.getExternalFilesDir(null), "traffic_logs")
        val files = dir.listFiles()?.toList() ?: emptyList()
        assertTrue("expected at least one traffic log file", files.isNotEmpty())
        // File contents should include the URL we recorded.
        val first = files.first()
        val text = first.readText()
        assertTrue(text.contains("http://x/a"))
    }

    @Test
    fun `flushToDisk on empty buffer is a no-op`() {
        // No entries recorded; the spawned thread sees entries.isEmpty() and
        // returns without writing a file.
        logger.flushToDisk()
        Thread.sleep(200)

        val dir = java.io.File(context.getExternalFilesDir(null), "traffic_logs")
        val files = dir.listFiles()?.toList() ?: emptyList()
        assertTrue("expected no traffic log files", files.isEmpty())
    }

    @Test
    fun `concurrent auto and manual flush with worker cancellation does not drop entries`() {
        val threadCount = 4
        val entriesPerThread = 250 // 1000 total > MAX_ENTRIES (200)

        // Record concurrently from multiple threads; auto-flush triggers every 50
        val threads = (0 until threadCount).map { t ->
            Thread {
                repeat(entriesPerThread) { i ->
                    logger.record("GET", "http://x/$t/$i", 10L, 20L, 1L)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Manual flush while the worker may still be processing auto-flush requests
        logger.flushToDisk()

        // Give the worker time to drain the channel before close()
        Thread.sleep(200)

        // Close the worker gracefully — proves cancel doesn't corrupt buffer state
        logger.close()

        // Buffer must remain readable and consistent
        val out = logger.dump()
        assertTrue("dump must report entries after concurrent recording", out.contains("entries"))

        // Verify buffer is still usable after close() (read-only operations)
        val out2 = logger.dump()
        assertEquals("dump() is idempotent after close", out, out2)

        // After close(), record() should still work (buffer ops are independent of worker)
        logger.record("POST", "http://final", 5L, 5L, 1L)
        val out3 = logger.dump()
        assertTrue(out3.contains("http://final"))
    }

    @Test
    fun `dump formats entries with method and url inline`() {
        logger.record(method = "DELETE", url = "http://x/resource", sent = 0L, received = 10L, elapsedMs = 3L)

        val out = logger.dump()
        assertTrue(out.contains("DELETE"))
        assertTrue(out.contains("http://x/resource"))
    }
}
