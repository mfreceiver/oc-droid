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

    /**
     * §omitted-content-card-gate: runtime toggle for the in-chat
     * "展开省略内容" (OmittedContentCard) affordance. Default OFF — the card
     * is hidden behind [SettingsManager.omittedContentCardEnabled] because in
     * practice all three of its forms proved net-negative value (a card
     * permanently hung under subagent task parts; an unclickable "生成中…"
     * skeleton during streaming; and expand calls that failed because G6
     * `/slimapi/messages/{sid}/full` is unreliable / skeleton part ids are
     * transient → orphan/residual/Failed). The underlying machinery
     * (`PartExpandState` / `ExpandPartsUseCase` / `expandMessagesFullBatch`)
     * is intentionally KEPT (NOT deleted) pending a reliable G6/sidecar
     * version; flip this ON to re-expose the UI outlet for evaluation.
     */
    var omittedContentCardEnabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_OMITTED_CONTENT_CARD, false)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_OMITTED_CONTENT_CARD, value).apply()

    /**
     * §sse-disabled-debug-toggle: DEBUG-only runtime toggle that forces the
     * client to REFUSE every SSE surface and run REST-only (degraded mode),
     * for testing the no-SSE fallback experience. Default OFF (zero production
     * impact). When ON:
     *  - [StreamingServiceLauncher.ensureStarted] short-circuits with
     *    [cn.vectory.ocdroid.service.OwnershipRefusal.SseDisabled] — NO FGS
     *    bootstrap, NO instance-level `/slimapi/events` connection.
     *  - [cn.vectory.ocdroid.ui.controller.sse.TokenStreamCoordinator.open]
     *    short-circuits — NO per-session `/slimapi/sessions/{sid}/stream`.
     *  - the connection phase surfaces [cn.vectory.ocdroid.ui.ConnectionPhase.SseDisabled].
     *
     * No in-app UI switch ships this (debug-only); flip via the ESP key
     * [KEY_SSE_DISABLED] (`sse_disabled=true`) or a future Debug sheet.
     */
    var sseDisabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_SSE_DISABLED, false)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_SSE_DISABLED, value).apply()

    companion object {
        /** §streaming-state-sync-diag: ESP key for [debugLogVerboseEnabled]. Default false. */
        internal const val KEY_DEBUG_LOG_VERBOSE = "debug_log_verbose"
        internal const val KEY_DEBUG_CARD_IDENTITY = "debug_card_identity"
        /** §omitted-content-card-gate: ESP key for [omittedContentCardEnabled]. Default false. */
        internal const val KEY_OMITTED_CONTENT_CARD = "omitted_content_card"
        /** §sse-disabled-debug-toggle: ESP key for [sseDisabled]. Default false. */
        internal const val KEY_SSE_DISABLED = "sse_disabled"
    }
}
