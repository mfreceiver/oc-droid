// FormatUtils.kt — small UI-facing formatting helpers shared across packages
// (settings, chat). Extracted to dedupe the byte-formatter that previously
// lived as private `formatBytes` / `formatTrafficBytes` in SettingsSections.kt
// and ChatServerManagementDialog.kt. Pure relocation + unification; no logic
// change.

package cn.vectory.ocdroid.ui.util

import java.util.Locale

/**
 * Formats [bytes] as a human-readable size. < 1 KiB shows bytes; < 1 MiB shows
 * KB; < 1 GiB shows MB; otherwise GB. Locale.US enforces ASCII digits and a
 * period decimal separator so the output is stable regardless of device locale.
 *
 * This is the single canonical byte formatter for UI presentation; the traffic
 * counters in both Settings and the server-management dialog render through it.
 */
internal fun formatBytes(bytes: Long): String {
    val unit = 1024L
    if (bytes < unit) return "$bytes B"
    val kb = bytes.toDouble() / unit
    if (kb < unit) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / unit
    if (mb < unit) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / unit
    return String.format(Locale.US, "%.2f GB", gb)
}
