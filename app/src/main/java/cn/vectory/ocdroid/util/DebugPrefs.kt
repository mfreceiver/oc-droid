package cn.vectory.ocdroid.util

import android.content.SharedPreferences

/**
 * L4b domain split of [SettingsManager] — DEBUG domain.
 *
 * Owns the two runtime debug toggles: verbose-diag logging and the in-chat
 * debug-card identity overlay. Both default OFF (zero overhead / log noise
 * in release).
 *
 * Behavior byte-identical to pre-split [SettingsManager]: same ESP instance,
 * same key strings, same defaults. NO key renames.
 */
internal class DebugPrefs(
    private val encryptedPrefs: SharedPreferences,
) {
    /**
     * §streaming-state-sync-diag (release-enabling): runtime toggle for the
     * 5 verbose diagnostic tags (`SendDiag` / `SseDiag` / `StatusDiag` /
     * `DigestDiag` / `LayerDiag`). Default OFF — release users get zero log
     * noise / perf cost unless they flip the toggle in Settings → Debug.
     *
     * ESP-persisted Boolean mirroring [persistentNotificationEnabled]'s pattern.
     * On set, the caller (the Settings UI) ALSO writes
     * [DebugLog.verboseDiagEnabled] so the change takes effect immediately
     * without a restart; on app start [AppCore]'s init seeds
     * [DebugLog.verboseDiagEnabled] from this value.
     */
    var debugLogVerboseEnabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_DEBUG_LOG_VERBOSE, false)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_DEBUG_LOG_VERBOSE, value).apply()

    /**
     * §debug-card-identity: runtime toggle for the in-chat debug card identity
     * overlay. When ON, every chat card displays a small badge identifying the
     * rendering composable + source location + part metadata. Default OFF —
     * zero overhead in normal use.
     */
    var debugCardIdentityEnabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_DEBUG_CARD_IDENTITY, false)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_DEBUG_CARD_IDENTITY, value).apply()

    companion object {
        /** §streaming-state-sync-diag: ESP key for [debugLogVerboseEnabled]. Default false. */
        internal const val KEY_DEBUG_LOG_VERBOSE = "debug_log_verbose"
        internal const val KEY_DEBUG_CARD_IDENTITY = "debug_card_identity"
    }
}
