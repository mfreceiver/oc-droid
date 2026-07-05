package cn.vectory.ocdroid.util

import android.app.Application
import android.os.Build
import android.os.Process
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists uncaught exceptions (crash stack traces) to a file in the app's
 * external files dir so they can be retrieved WITHOUT adb — via a file manager
 * or `adb pull /sdcard/Android/data/cn.vectory.ocdroid/files/crashes/`.
 *
 * The previous default [Thread.UncaughtExceptionHandler] is still invoked
 * afterwards so the process dies normally (Android's "FATAL EXCEPTION" dialog /
 * restart behavior is preserved).
 *
 * Each crash is written as `crash-<yyyyMMdd-HHmmss>.txt`, capped to the most
 * recent 5 files to avoid unbounded growth.
 */
object CrashLogger {

    private const val MAX_FILES = 5
    private const val DIR = "crashes"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(app, thread, throwable)
            } catch (_: Throwable) {
                // Never let the logger itself mask the original crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(app: Application, thread: Thread, throwable: Throwable) {
        val dir = File(app.getExternalFilesDir(null), DIR).apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$timestamp.txt")

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("=== ocdroid crash ===")
            pw.println("Time: ${Date()}")
            pw.println("Thread: ${thread.name}")
            pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
            pw.println("App version: ${try { app.packageManager.getPackageInfo(app.packageName, 0).let { "${it.versionName} (${PackageInfoCompat.getLongVersionCode(it)})" } } catch (_: Throwable) { "unknown" }}")
            pw.println()
            pw.println("Runtime.maxMemory(): ${Runtime.getRuntime().maxMemory()}")
            pw.println("Runtime.totalMemory(): ${Runtime.getRuntime().totalMemory()}")
            pw.println("Runtime.freeMemory(): ${Runtime.getRuntime().freeMemory()}")
            pw.println("Process: pid=${Process.myPid()}")
            pw.println()
            pw.println("=== Stack trace ===")
            throwable.printStackTrace(pw)
            // Walk the full cause chain explicitly (some OOM crashes truncate).
            var cause: Throwable? = throwable.cause
            while (cause != null) {
                pw.println()
                pw.println("--- Caused by: ${cause::class.java.name}: ${cause.message} ---")
                cause.printStackTrace(pw)
                cause = cause.cause
            }
        }
        file.writeText(sw.toString())

        // Prune old crash files (keep newest MAX_FILES).
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)
            ?.forEach { runCatching { it.delete() } }
    }

    /** Human-readable path to the crash log directory, for showing in a debug screen. */
    fun crashDirDescription(app: Application): String =
        File(app.getExternalFilesDir(null), DIR).absolutePath
}
