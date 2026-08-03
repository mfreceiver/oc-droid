package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.repository.http.AuthFailureReason

/**
 * §R18 Phase 2-I: replacement for the legacy `connectionPhase: String?`
 * (which used free-form strings "connecting"/"connected"/"disconnected"/
 * "reconnecting"/"reconnecting (attempt N/M)"). The sealed hierarchy lets the
 * compiler enforce exhaustive `when` branches at UI read sites and kills
 * typo-class bugs at write sites.
 *
 * Two distinct Reconnecting shapes coexist in the codebase:
 *  - [Reconnecting] — host-switch / cold-start immediate signal, no
 *    attempt counter (writers: HostProfileController host-switch reset,
 *    ConnectionActions.applySavedSettings cold-start signal).
 *  - [ReconnectingAttempt] — ConnectionCoordinator retry-loop probe with
 *    exponential backoff (writer: ConnectionCoordinator.testConnection on
 *    attempt > 1).
 *
 * Non-null (default [Idle]) on purpose: lets UI `when (phase)` branches be
 * exhaustive without an `else`. The previous `null` semantics (badge hidden,
 * empty-state plain text) map to [Idle].
 */
sealed class ConnectionPhase {
    /** No connection activity — initial state, or after a clean disconnect reset. */
    data object Idle : ConnectionPhase()
    /** First attempt of a connect probe is in flight (no retries yet). */
    data object Connecting : ConnectionPhase()
    /** Healthy connect established (server is reachable). */
    data object Connected : ConnectionPhase()
    /** Host-switch / cold-start reconnect signal, no attempt counter. */
    data object Reconnecting : ConnectionPhase()
    /** Retry-loop reconnect with backoff; carries the attempt counter for UI. */
    data class ReconnectingAttempt(val attempt: Int, val maxAttempts: Int) : ConnectionPhase()
    /**
     * §L7 TOFU removal: kept for binary compat with ChatEmptyState reference;
     * unused in production. Will be removed in a follow-up cleanup.
     */
    data object AwaitingTofuTrust : ConnectionPhase()
    /** Probe failed terminally (retries exhausted or one-shot failure). */
    data object Disconnected : ConnectionPhase()
    /**
     * §sse-disabled-debug-toggle: the DEBUG `sse_disabled` flag is ON — the
     * client is intentionally running REST-only (no SSE). Distinct from
     * [Disconnected] so the UI can surface "SSE disabled by debug toggle"
     * instead of a misleading "disconnected". Reached when
     * [cn.vectory.ocdroid.service.StreamingServiceLauncher.ensureStarted]
     * returns [cn.vectory.ocdroid.service.OwnershipRefusal.SseDisabled].
     */
    data object SseDisabled : ConnectionPhase()

    /**
     * §sse-zombie-fix (v3 Bug B): REST is healthy (ping succeeded, auth OK)
     * but the SSE event stream bootstrap failed (launcher returned
     * [cn.vectory.ocdroid.service.OwnershipRefusal.BootstrapFailed] / the
     * zombie reap path). Distinct from [Disconnected] (which pairs with
     * `isConnected=false` and a genuine REST/auth outage) so the banner can
     * honestly say "live updates interrupted — messaging still works" instead
     * of the misleading "服务器连接不上".
     *
     * All "SSE terminally down" behavioral consumers route through
     * [ConnectionPhase.isSseDown] (canonical classifier — issue #7 structural
     * fix), which covers both [Disconnected] and [SseBootstrapFailed].
     * `SseDisabled` is intentionally OUT of `isSseDown` (REST-only debug
     * toggle is a deliberate user mode, not an outage).
     */
    data object SseBootstrapFailed : ConnectionPhase()
}

/**
 * §sse-zombie-fix (v3 Bug B / issue #7): canonical "SSE event stream is
 * terminally down" classifier. All behavioral consumers (foreground catch-up,
 * digest-relay gating, metadata-poller fallback, disconnectedSince stamping,
 * 90s auto-unanchor) read THIS instead of ad-hoc `phase is Disconnected`
 * checks, so [SseBootstrapFailed] is uniformly treated as "SSE down, REST
 * still serving" across the codebase.
 *
 * [SseDisabled] is NOT included: the debug toggle is a deliberate REST-only
 * mode, not an outage — those consumers intentionally skip catch-up / fallback
 * shaping under `SseDisabled`.
 */
val ConnectionPhase.isSseDown: Boolean
    get() = this is ConnectionPhase.Disconnected || this is ConnectionPhase.SseBootstrapFailed

/**
 * §R-17 batch2: connection-domain state slice. Authoritative storage; no
 * AppState mirror. Field set strictly follows RFC R-17 §2.1.
 *
 * Write atomicity (RFC §4, strategy A): every mutation goes through a single
 * `writeConnection { ... }` (or a sequence of them where each
 * intermediate state is a legal UI state — never a `isConnected=true` paired
 * with an `Idle` `connectionPhase`). Do NOT rely on `Dispatchers.Main.immediate`
 * batching across separate `update` calls.
 */
data class ConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val connectionPhase: ConnectionPhase = ConnectionPhase.Idle,
    /**
     * §O-C weak-network §4: stale indicator. `true` when the last metadata
     * refresh (cold-start / resync) failed (or we are serving cached data
     * because the network is flaky). The UI can observe this field to render
     * a "stale data" indicator (e.g. muted colors / a banner). Cleared
     * on every successful refresh.
     */
    val stale: Boolean = false,
    /**
     * §fix-3 (gro-1#2/gpt-2#2/max-1 M1): 非 null = 当前 host 的 mTLS 已开启但客户端
     * 证书材料缺失（ESP 无 p12/pw key）或损坏（试构建失败，见
     * [cn.vectory.ocdroid.data.repository.OpenCodeRepository.lastClientCertError]）。
     * 此时 SSL 已降级回 SystemDefault → stunnel 会拒连。显示本字段为红色 banner，让
     * 用户看到「证书加载失败」而非泛化「连接失败」。null = 无 mTLS 降级。每次
     * configure（切 host / 冷启 / 保存）重置。
     */
    val mtlsDegradedError: String? = null,
    /**
     * §F2: non-null = the connection hit an upstream HTTP auth failure (401/403)
     * surfaced through the sidecar's `upstream_http_401` / `upstream_http_403`
     * envelope (wrong Basic Auth, server-side auth denial, revoked token).
     * Classified by [cn.vectory.ocdroid.data.repository.http.classifyAuthFailure]
     * from the probe's terminal exception + the parsed envelope code. The
     * banner unifies this with [mtlsDegradedError]: EITHER non-null drives
     * [BannerCategory.AUTH_FAILURE] (priority over REST_OUTAGE).
     *
     * null = no auth failure classified. Cleared on every successful connect
     * (mirrors the mtlsDegradedError reset discipline but on the connect-success
     * write rather than configure — this field is network-driven, mtlsDegradedError
     * is config-driven).
     *
     * Note: [mtlsDegradedError] (config path) continues to exist independently.
     * At the banner layer it is also treated as an AUTH_FAILURE reason alongside
     * [AuthFailureReason.MtlsDegraded], so the user sees one coherent category
     * regardless of which path surfaced the auth issue.
     */
    val authFailureReason: AuthFailureReason? = null,
    /**
     * §R8 slim-mode M2 自检：非 null = slimapi 版本不兼容（客户端版本不在
     * sidecar 公告的 accepted_client_versions 闭区间内）。值为三元组
     * (clientVersion, acceptedMin, acceptedMax)，供 UI 展示。fail-closed——
     * 不兼容时标记连接不可用，不静默报健康。
     */
    val slimapiVersionIncompatible: Triple<Int, Int, Int>? = null,
    /**
     * 镜像 [cn.vectory.ocdroid.data.repository.ServerCompatProfile.slimConnection]——
     * `true` = 当前连接的 live mode 为 slim（省流模式）。供 ServerStatusIconButton
     * 区分绿（标准服 / slim 服的非 slim 模式）与蓝（slim 模式活跃）。
     *
     * 写入时机：connect 成功写入 `isConnected=true` 的同时设置；断连时由红遮蔽
     * 但仍保持语义一致。默认 `false` 不破坏既有收集者。
     */
    val isSlimActive: Boolean = false,
    /** Active host connection params changed since boot — user must restart. */
    val restartRequired: Boolean = false,
    /**
     * §sse-rest-fallback (TODO 3): wall-clock ms of the transition INTO
     * [ConnectionPhase.Disconnected], or null when connected / never stamped.
     * Auto-stamped by [cn.vectory.ocdroid.ui.SharedStateStore.mutateConnection]
     * (the single connection-write chokepoint) on the phase transition so EVERY
     * writer (ConnectionCoordinator / healthProbe / SSE connection owner /
     * host-switch) records it consistently, and cleared on the way OUT of
     * Disconnected. Read by [cn.vectory.ocdroid.ui.performGlobalColdStartRefresh]
     * to auto-upgrade to an UNANCHORED fetch when SSE has been down long enough
     * that the slim /since watermark is likely stale (real-outage self-heal —
     * no manual refresh needed). A writer MAY set it explicitly (e.g. tests
     * simulating an old disconnect); the stamp respects non-null values.
     */
    val disconnectedSince: Long? = null,
)

data class ConnectionFormSettings(
    val serverUrl: String,
    val username: String,
    val password: String
)
