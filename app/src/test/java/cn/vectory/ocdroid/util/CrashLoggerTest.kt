package cn.vectory.ocdroid.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R18 Phase 5++ coverage: [CrashLogger] — uncaught-exception persistence to
 * the app's external files dir. Coverage gap before this file: 0/1 class,
 * 0/5 methods, 0/35 lines, 0/260 instructions.
 *
 * CrashLogger.install sets a new UncaughtExceptionHandler that delegates to
 * the previous one after writing the crash to a file. We verify:
 *  - install() installs a new handler (different from the previous one).
 *  - crashDirDescription() resolves to the crashes subdirectory path.
 *  - The handler writes a crash file when invoked with a chained cause.
 *  - The MAX_FILES cap (5) is honoured when many crashes are written.
 *
 * The test installs the handler and invokes it DIRECTLY (we don't throw
 * uncaught — that would tear down the test JVM). The previous handler is
 * captured and re-installed in teardown so the test framework's handler
 * (which reports test failures) stays intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrashLoggerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        // §note: see TrafficLoggerTest — Robolectric boots the real OpenCodeApp
        // whose Hilt graph needs AndroidKeyStore.
        FakeAndroidKeyStoreProvider.install()
        // Capture the existing handler so each test starts from a clean slate.
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    private fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test
    fun `crashDirDescription contains the crashes directory path`() {
        val desc = CrashLogger.crashDirDescription(context as Application)
        assertTrue(desc.contains("crashes"))
    }

    @Test
    fun `install replaces the default uncaught exception handler`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        CrashLogger.install(context as Application)
        val after = Thread.getDefaultUncaughtExceptionHandler()
        // The handler MUST have been replaced (install sets a new lambda).
        assertTrue("install must replace the handler", before !== after)
        restoreHandler()
    }

    @Test
    fun `handler writes a crash file when invoked`() {
        // Use a no-op previous handler so the test JVM is not torn down by
        // the rethrown exception.
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        CrashLogger.install(context as Application)

        // Invoke the handler directly (mimicking what the JVM does on an
        // uncaught exception).
        val thread = Thread.currentThread()
        val throwable = RuntimeException("test crash")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(thread, throwable)

        val dir = java.io.File(context.getExternalFilesDir(null), "crashes")
        val files = dir.listFiles()?.toList() ?: emptyList()
        assertTrue("expected at least one crash file", files.isNotEmpty())

        val first = files.first()
        val text = first.readText()
        assertTrue(text.contains("=== ocdroid crash ==="))
        assertTrue(text.contains("Thread:"))
        assertTrue(text.contains("Runtime.maxMemory()"))
        assertTrue(text.contains("=== Stack trace ==="))
        assertTrue(text.contains("RuntimeException"))
        assertTrue(text.contains("test crash"))

        restoreHandler()
    }

    @Test
    fun `handler walks the full cause chain`() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        CrashLogger.install(context as Application)

        val root = IllegalStateException("root cause")
        val mid = RuntimeException("mid cause", root)
        val outer = Exception("outer", mid)

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), outer)

        val dir = java.io.File(context.getExternalFilesDir(null), "crashes")
        val text = dir.listFiles()!!.maxByOrNull { it.lastModified() }!!.readText()
        // Both the outer + each "Caused by" entry must appear.
        assertTrue(text.contains("Caused by"))
        assertTrue(text.contains("root cause"))
        assertTrue(text.contains("mid cause"))

        restoreHandler()
    }

    @Test
    fun `handler prunes old crash files keeping only the most recent MAX_FILES`() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        CrashLogger.install(context as Application)

        val dir = java.io.File(context.getExternalFilesDir(null), "crashes")
        dir.mkdirs()

        // Write 7 distinct crashes; MAX_FILES = 5, so only the newest 5 survive.
        repeat(7) { i ->
            Thread.getDefaultUncaughtExceptionHandler()!!
                .uncaughtException(Thread.currentThread(), RuntimeException("crash-$i"))
            // Bump the file mtime so sortByDescending(lastModified) is stable
            // even when the writes happen within the same millisecond.
            Thread.sleep(15)
        }

        val remaining = dir.listFiles()?.toList() ?: emptyList()
        assertTrue(
            "expected at most 5 crash files (got ${remaining.size})",
            remaining.size <= 5,
        )

        restoreHandler()
    }

    @Test
    fun `handler tolerates a missing previous handler`() {
        // Set the previous handler to null (legal per the JDK contract).
        Thread.setDefaultUncaughtExceptionHandler(null)
        CrashLogger.install(context as Application)

        val handler = Thread.getDefaultUncaughtExceptionHandler()!!
        // Invoking it MUST NOT throw NullPointerException on the
        // `previous?.uncaughtException(...)` call.
        handler.uncaughtException(Thread.currentThread(), RuntimeException("tolerated"))

        restoreHandler()
    }
}
