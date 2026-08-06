package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.repository.http.AuthFailureReason

/**
 * §sse-feedback-ux: user-facing SSE / live-updates status. A pure projection
 * of ([ConnectionPhase] + [ConnectionState.disconnectedSince] + the SSE
 * transport-delivery signal) — NOT a writable slice. The banner surfaces
 * [Disconnected] / [Disabled] persistently; the other variants are carried so
 * the derivation is exhaustive and available to future read sites (e.g. an
 * empty-state label) without a second pass.
 *
 *  - [Live]              — health Connected AND the SSE transport has proven
 *                          delivery (the breathing dot is green/blue). Live
 *                          updates are flowing.
 *  - [WaitingForStream]  — health Connected but the SSE transport has NOT yet
 *                          delivered a frame (the "stall" case: HTTP is up,
 *                          SSE is not). Kept distinct so a future surface can
 *                          distinguish "connected, waiting" from a hard down.
 *  - [Connecting]        — a connect probe is in flight.
 *  - [Reconnecting]      — host-switch / retry-loop reconnect signal; carries
 *                          the attempt counter when the phase provides one.
 *  - [AwaitingTofuTrust] — SSL/cert decision pending (the TOFU dialog overlays;
 *                          the banner stays silent — [showBanner] is false).
 *  - [Disconnected]      — terminal disconnect (retries exhausted / one-shot
 *                          failure). Carries [sinceMs] (the stamped transition
 *                          time) AND [now] (the derivation clock) so the
 *                          banner can render an elapsed-time label AND so the
 *                          emitted value varies each tick (the ViewModel ticker
 *                          re-derives every [SSE_FEEDBACK_TICK_MS]; because
 *                          [now] changes, distinctUntilChanged passes the tick
 *                          through ONLY while a disconnect is shown — when
 *                          healthy the equal [Live]/[Idle] emissions are
 *                          dropped, so there is zero churn on the happy path).
 *  - [Disabled]          — the debug `sse_disabled` flag is ON (REST-only by
 *                          user choice); surfaced so the banner explains
 *                          "live updates are off" instead of looking broken.
 *  - [Idle]              — no connection activity (initial / clean reset).
 */
/**
 * §sse-feedback-ux (§1.1): semantic category for the in-chat banner. Pure
 * derivation — what to show, separate from when to show it (handled by
 * [BannerHysteresisState] / [bannerHysteresisReducer]).
 *
 * Priority: AUTH_FAILURE (more specific/actionable) wins over REST_OUTAGE when
 * [ConnectionState.mtlsDegradedError] is non-null OR
 * [ConnectionState.authFailureReason] is non-null (upstream 401/403 via sidecar
 * envelope, or mTLS cert degradation).
 */
internal enum class BannerCategory { REST_OUTAGE, AUTH_FAILURE, SSE_STALLED, SSE_BOOTSTRAP_FAILED, USER_DISABLED }

sealed interface SseConnectionFeedback {
    data object Live : SseConnectionFeedback
    data object WaitingForStream : SseConnectionFeedback
    data object Connecting : SseConnectionFeedback
    data class Reconnecting(val attempt: Int?, val maxAttempts: Int?) : SseConnectionFeedback
    data object AwaitingTofuTrust : SseConnectionFeedback
    data class Disconnected(val sinceMs: Long, val now: Long) : SseConnectionFeedback
    data object Disabled : SseConnectionFeedback
    data object Idle : SseConnectionFeedback
    /** §sse-zombie-fix (v3 Bug B): SSE bootstrap failed but REST is healthy. */
    data class SseBootstrapFailed(val sinceMs: Long) : SseConnectionFeedback
}

/**
 * §sse-feedback-ux: pure derivation of [SseConnectionFeedback] from the
 * authoritative connection inputs. Pure (all inputs are params, no store /
 * no clock side-effect) so it is unit-testable with a controlled clock and
 * exhaustively covers every [ConnectionPhase] variant (no `else` — the
 * compiler forces an update when a new phase is added, mirroring
 * [ConnectionPhase.displayTextForEmptyState]).
 *
 * The single source of truth for [ConnectionState.disconnectedSince] is the
 * auto-stamper in [SharedStateStore.mutateConnection]
 * ([stampDisconnectedSince]); this function only READS it. When the stamper
 * has not yet run (e.g. a test that sets Disconnected directly without a
 * timestamp), it falls back to [now] so the elapsed label is "just now"
 * rather than crashing on null arithmetic.
 */
internal fun deriveSseConnectionFeedback(
    phase: ConnectionPhase,
    disconnectedSince: Long?,
    sseConnected: Boolean,
    now: Long,
    /** §1.1: mTLS cert/credential degradation — null = no auth issue.
     *  Read by [SseConnectionFeedback.bannerCategory] for AUTH_FAILURE / REST_OUTAGE
     *  disambiguation. Kept as a param so this function stays PURE. */
    mtlsDegradedError: String? = null,
    /** §F2: network-driven auth failure (upstream 401/403 via sidecar envelope).
     *  Null = no auth failure classified. Kept as a param (pure pass-through)
     *  so [SseConnectionFeedback.bannerCategory] can unify this with
     *  [mtlsDegradedError] for AUTH_FAILURE disambiguation. */
    authFailureReason: AuthFailureReason? = null,
): SseConnectionFeedback = when (phase) {
    ConnectionPhase.Idle -> SseConnectionFeedback.Idle
    ConnectionPhase.Connecting -> SseConnectionFeedback.Connecting
    ConnectionPhase.Connected -> if (sseConnected) SseConnectionFeedback.Live else SseConnectionFeedback.WaitingForStream
    ConnectionPhase.Reconnecting -> SseConnectionFeedback.Reconnecting(attempt = null, maxAttempts = null)
    is ConnectionPhase.ReconnectingAttempt -> SseConnectionFeedback.Reconnecting(attempt = phase.attempt, maxAttempts = phase.maxAttempts)
    ConnectionPhase.AwaitingTofuTrust -> SseConnectionFeedback.AwaitingTofuTrust
    // §banner-stuck-on-recover fix: a live SSE frame is STRICTLY stronger
    // evidence that the server is reachable than a REST probe, so when
    // sseConnected=true it overrides the terminal REST-axis phases.
    // Background: connectionPhase is written ONLY by the REST health probe
    // (ConnectionHealthProbe — the sole writer of ConnectionPhase.Connected,
    // confirmed by ServiceSseConnectionOwnerTest "SSE owner does NOT write
    // Connected"). When the server drops and SSE self-heals via SSEClient's
    // internal retryWhen (which writes ONLY sseConnected, never the phase),
    // the phase stays pinned at Disconnected / SseBootstrapFailed even though
    // the transport has recovered → the banner's category input never becomes
    // null → the hysteresis state machine never reaches Hidden → the banner
    // is stuck. Without this guard the manual refresh cannot clear it either:
    // testConnection re-probes, but if SSE bootstrap races and Refuses at
    // that instant the phase is re-stamped terminal (§degraded-connected-fix).
    // Treating sseConnected=true as Live here lets BannerHysteresisOwner's
    // combine(connectionFlow, sseConnectedFlow) re-derive category=null the
    // moment the transport recovers, regardless of how it recovered.
    //
    // SseDisabled is INTENTIONALLY excluded: it is a user-driven debug toggle
    // (REST-only by choice); user intent wins even if sseConnected is true.
    //
    // AUTH_GATE (rev-ogpt #1): the Live override is GATED on
    // authFailureReason == null && mtlsDegradedError == null. A live SSE frame
    // proves reachability but does NOT prove the credentials/cert are valid —
    // an existing SSE connection can keep delivering heartbeats AFTER REST has
    // started returning 401/403 (ConnectionHealthProbe stamps Disconnected +
    // authFailureReason while the SSE transport is still up). Without this gate
    // the override would swallow AUTH_FAILURE (Live.bannerCategory() is null
    // unconditionally), violating the AUTH_FAILURE > REST_OUTAGE priority
    // contract and hiding an actionable auth/cert problem. Auth failures stay
    // surfaced until a fresh testConnection clears authFailureReason on REST
    // success.
    //
    // Trade-off (accepted, pre-existing transport limitation — NOT introduced
    // by this change): sseConnected is a liveness flag, not a freshness token.
    // During SSEClient's internal retryWhen window no code flips it false
    // (markRetrying exists but is unused), so after a REAL outage begins it can
    // stay true for the retry budget (nominally ~3 min, worst-case longer if an
    // attempt establishes TCP but delivers no events). In that window a genuine
    // REST_OUTAGE banner is delayed until the budget exhausts → terminal drop →
    // sseConnected=false. This is bounded in practice and self-healing; per-op
    // REST failures still surface their own error feedback meanwhile. The AUTH
    // GATE above ensures the high-priority AUTH_FAILURE case is never masked by
    // a stale SSE flag. A proper fix (flip the shared transport axis to false
    // on retry + add a first-frame/readiness timeout) is tracked as a separate
    // follow-up — out of scope for this targeted banner-stuck patch.
    ConnectionPhase.Disconnected ->
        if (sseConnected && authFailureReason == null && mtlsDegradedError == null) SseConnectionFeedback.Live
        else SseConnectionFeedback.Disconnected(sinceMs = disconnectedSince ?: now, now = now)
    ConnectionPhase.SseBootstrapFailed ->
        if (sseConnected && authFailureReason == null && mtlsDegradedError == null) SseConnectionFeedback.Live
        else SseConnectionFeedback.SseBootstrapFailed(sinceMs = disconnectedSince ?: now)
    ConnectionPhase.SseDisabled -> SseConnectionFeedback.Disabled
}

// ════════════════════════════════════════════════════════════════════════════
// §sse-feedback-ux (§1.3): Banner hysteresis — grace / min-display / recover-hide
// anti-flash reducer. Separates "WHAT to show" (pure [bannerCategory]) from
// "WHEN to show" (stateful hysteresis with a controllable clock).
// ════════════════════════════════════════════════════════════════════════════

/**
 * §1.3: Tunable timing parameters for the banner hysteresis reducer.
 * These are ANTI-FLASH constants, NOT data-recovery thresholds (do NOT
 * reuse [SSE_DISCONNECT_UNANCHORED_THRESHOLD_MS]).
 *
 * @param showGraceMs       disconnect must sustain this long before showing
 *                          (transient blip → never shown). Default 5s.
 * @param minDisplayMs      once shown, must remain visible at least this long
 *                          (anti one-frame flash). Default 3s.
 * @param recoverHideDelayMs after recovery, delay this long before hiding
 *                          (anti flapping on intermittent connectivity).
 *                          Default 5s.
 */
internal data class BannerHysteresisConfig(
    val showGraceMs: Long = 5_000L,
    val minDisplayMs: Long = 3_000L,
    val recoverHideDelayMs: Long = 5_000L,
)

/**
 * §C3: Input to the hysteresis reducer — carries both the semantic category
 * and the captured auth reason (mtlsDegradedError) at the moment the category
 * was established. The reducer preserves this payload through transitions
 * so the displayed info is always a coherent snapshot.
 */
internal data class BannerCategoryInput(
    val category: BannerCategory,
    val authReason: String?,
)

/**
 * §1.3: UI-facing visibility — what the banner composable reads.
 * [Hidden] = render nothing; [Showing] = render with the given category.
 *
 * §C3: [Showing] carries [authReason] as part of a coherent payload.
 */
internal sealed class BannerVisibility {
    data object Hidden : BannerVisibility()
    data class Showing(
        val category: BannerCategory,
        /** §C3: captured auth reason, non-null only for AUTH_FAILURE. */
        val authReason: String?,
        val sinceMs: Long,
    ) : BannerVisibility()
}

/**
 * §1.3: Internal phase of the hysteresis state machine. Tracks intermediate
 * states (PendingShow/PendingHide) that are invisible to the UI but carry
 * timing information for the reducer transitions.
 *
 * §C3: All phases that carry a category now also carry [authReason] for a
 * coherent displayed payload (no torn-read between visibility and feedback).
 */
internal sealed class BannerHysteresisPhase {
    data object Hidden : BannerHysteresisPhase()
    data class PendingShow(
        val category: BannerCategory,
        val authReason: String?,
        val atMs: Long,
    ) : BannerHysteresisPhase()
    data class Showing(
        val category: BannerCategory,
        val authReason: String?,
        val sinceMs: Long,
    ) : BannerHysteresisPhase()
    data class PendingHide(
        val category: BannerCategory,
        val authReason: String?,
        val atMs: Long,
        val sinceMs: Long,
    ) : BannerHysteresisPhase()
}

/**
 * §1.3: Combined state of the hysteresis reducer. [visibility] is what the UI
 * checks; [phase] is the internal machine state that drives transitions.
 */
internal data class BannerHysteresisState(
    val visibility: BannerVisibility = BannerVisibility.Hidden,
    internal val phase: BannerHysteresisPhase = BannerHysteresisPhase.Hidden,
)

/**
 * §1.3: Pure hysteresis state-machine reducer. Drives the "when to show"
 * logic with grace / min-display / recover-hide delay, independent of the
 * "what category" logic ([bannerCategory]).
 *
 * State machine transitions (implemented exactly — this is the anti-flash contract):
 * The `authReason` field is pure payload — it is carried through transitions
 * but NEVER influences the transition logic (which only checks null-ness of
 * [input]):
 *
 * ```
 * Hidden        + input!=null          → PendingShow(now)                    // grace starts; NOT visible
 * PendingShow   + input!=null & now≥at+grace → Showing(now)                 // grace elapses → visible
 * PendingShow   + input==null          → Hidden                              // recovered within grace: never shown
 * PendingShow   + input!=null & now<at+grace → PendingShow(at)              // stay in grace, update payload
 * Showing       + input!=null          → Showing(since)                      // stay; update payload
 * Showing       + input==null & now≥since+minDisplay → PendingHide(now)      // min-display met, start hide delay
 * Showing       + input==null & now<since+minDisplay → Showing               // min-display NOT met: keep showing
 * PendingHide   + input!=null          → Showing(since)                      // re-show (anti flap)
 * PendingHide   + input==null & now≥at+recoverDelay → Hidden
 * PendingHide   + input==null & now<at+recoverDelay → PendingHide
 * ```
 *
 * §C3: [input] carries both the semantic [BannerCategory] and the
 * [BannerCategoryInput.authReason] captured at the moment the category was
 * established. The reducer preserves `authReason` through all transitions
 * so the displayed payload is always a coherent snapshot (no torn reads
 * between visibility and the underlying feedback).
 *
 * Inject a controllable [now] clock for testability. Pure — no side effects.
 *
 * @param prev    previous state.
 * @param input   current banner category input (null = not banner-worthy right now).
 * @param now     current wall-clock ms (injected for testability).
 * @param config  timing parameters.
 */
internal fun bannerHysteresisReducer(
    prev: BannerHysteresisState,
    input: BannerCategoryInput?,
    now: Long,
    config: BannerHysteresisConfig = BannerHysteresisConfig(),
): BannerHysteresisState {
    val nextPhase: BannerHysteresisPhase = when (val p = prev.phase) {
        is BannerHysteresisPhase.Hidden -> {
            if (input != null) {
                BannerHysteresisPhase.PendingShow(
                    category = input.category,
                    authReason = input.authReason,
                    atMs = now,
                )
            } else {
                BannerHysteresisPhase.Hidden
            }
        }

        is BannerHysteresisPhase.PendingShow -> {
            if (input == null) {
                // Recovered within grace — never shown
                BannerHysteresisPhase.Hidden
            } else if (now >= p.atMs + config.showGraceMs) {
                // Grace elapsed → promote to Showing
                BannerHysteresisPhase.Showing(
                    category = input.category,
                    authReason = input.authReason,
                    sinceMs = now,
                )
            } else {
                // Still within grace period — stay PendingShow, update payload
                BannerHysteresisPhase.PendingShow(
                    category = input.category,
                    authReason = input.authReason,
                    atMs = p.atMs,
                )
            }
        }

        is BannerHysteresisPhase.Showing -> {
            if (input != null) {
                // Stay showing; update payload if changed (REST_OUTAGE↔AUTH_FAILURE)
                BannerHysteresisPhase.Showing(
                    category = input.category,
                    authReason = input.authReason,
                    sinceMs = p.sinceMs,
                )
            } else if (now >= p.sinceMs + config.minDisplayMs) {
                // Min-display met → start hide delay
                BannerHysteresisPhase.PendingHide(
                    category = p.category,
                    authReason = p.authReason,
                    atMs = now,
                    sinceMs = p.sinceMs,
                )
            } else {
                // Min-display NOT met — keep showing
                BannerHysteresisPhase.Showing(
                    category = p.category,
                    authReason = p.authReason,
                    sinceMs = p.sinceMs,
                )
            }
        }

        is BannerHysteresisPhase.PendingHide -> {
            if (input != null) {
                // Recovered during hide delay — re-show (anti flap), preserve original sinceMs
                BannerHysteresisPhase.Showing(
                    category = input.category,
                    authReason = input.authReason,
                    sinceMs = p.sinceMs,
                )
            } else if (now >= p.atMs + config.recoverHideDelayMs) {
                // Hide delay elapsed → fully hidden
                BannerHysteresisPhase.Hidden
            } else {
                // Still within hide delay — wait
                BannerHysteresisPhase.PendingHide(
                    category = p.category,
                    authReason = p.authReason,
                    atMs = p.atMs,
                    sinceMs = p.sinceMs,
                )
            }
        }
    }

    // Derive UI-facing visibility from the phase
    val nextVisibility: BannerVisibility = when (nextPhase) {
        is BannerHysteresisPhase.Hidden -> BannerVisibility.Hidden
        is BannerHysteresisPhase.PendingShow -> BannerVisibility.Hidden
        is BannerHysteresisPhase.Showing ->
            BannerVisibility.Showing(
                category = nextPhase.category,
                authReason = nextPhase.authReason,
                sinceMs = nextPhase.sinceMs,
            )
        is BannerHysteresisPhase.PendingHide ->
            // Still visible during hide delay (anti-flap)
            BannerVisibility.Showing(
                category = nextPhase.category,
                authReason = nextPhase.authReason,
                sinceMs = nextPhase.sinceMs,
            )
    }

    return BannerHysteresisState(visibility = nextVisibility, phase = nextPhase)
}

/**
 * §C1: Computes the next wall-clock deadline at which the hysteresis state
 * machine needs to re-evaluate. Returns null when no pending deadline exists
 * (Showing and Hidden have no fixed timeouts — they wait for external events).
 *
 * Used by [BannerHysteresisOwner] to schedule a focused delay at the exact
 * deadline, replacing the old 30s coarse ticker for hysteresis timing.
 */
internal fun computeHysteresisDeadlineMs(
    state: BannerHysteresisState,
    now: Long,
    config: BannerHysteresisConfig = BannerHysteresisConfig(),
): Long? {
    // §b4-rev2 🔴1 fix: Showing MUST schedule a re-evaluation at sinceMs+minDisplayMs.
    // The reducer holds the banner in Showing while category==null but now<since+minDisplay
    // (min-display not yet met — anti one-frame flash). Without a deadline here, a recovery
    // that lands inside the min-display window would leave the banner stuck in Showing
    // forever: no category event fires (connection is healthy), no ticker drives the
    // reducer, so the since+minDisplay transition to PendingHide never triggers.
    // The deadline is the min-display expiry; when it fires, the owner re-runs the reducer
    // with a fresh `now` that (now ≥ since+minDisplay) drives Showing→PendingHide.
    return when (val p = state.phase) {
        is BannerHysteresisPhase.PendingShow -> p.atMs + config.showGraceMs
        is BannerHysteresisPhase.PendingHide -> p.atMs + config.recoverHideDelayMs
        is BannerHysteresisPhase.Showing -> p.sinceMs + config.minDisplayMs
        is BannerHysteresisPhase.Hidden -> null
    }
}

/**
 * §sse-feedback-ux (§1.1): should the in-chat banner EVER be considered for
 * this feedback? True for terminal disconnect / SSE stall / user-disabled —
 * the transient / healthy / decision-pending variants stay silent so the
 * banner never cries wolf on a brief network blip or while the TOFU dialog
 * is handling a cert decision.
 *
 * NOTE: this only determines WHETHER the feedback is "banner-worthy" at all.
 * The actual VISIBILITY (debounce / grace / min-display / recover-hide) is
 * governed by [BannerHysteresisState] / [bannerHysteresisReducer]. The
 * auth-vs-outage distinction (REST_OUTAGE vs AUTH_FAILURE) is decided by
 * [bannerCategory], NOT by showBanner.
 *
 * §1.1 漏报 fix: [WaitingForStream] now returns true — previously silent.
 */
internal val SseConnectionFeedback.showBanner: Boolean
    get() = this is SseConnectionFeedback.Disconnected ||
        this is SseConnectionFeedback.Disabled ||
        this is SseConnectionFeedback.WaitingForStream ||
        this is SseConnectionFeedback.SseBootstrapFailed

/**
 * §sse-feedback-ux (§1.1): what semantic category does this feedback represent?
 * Pure function of [SseConnectionFeedback] + the auth signals. Returns
 * null when the feedback is NOT banner-worthy (Live / Connecting / Reconnecting
 * / AwaitingTofuTrust / Idle) — the caller interprets null as "no banner".
 *
 * Priority rule: AUTH_FAILURE wins over REST_OUTAGE when EITHER [mtlsDegradedError]
 * is non-null (config-driven mTLS cert degradation) OR [authFailureReason] is
 * non-null (network-driven upstream 401/403 via sidecar envelope). Both signals
 * are unified — mTLS is one auth-failure reason among several.
 *
 * §1.1 漏报 fix: [WaitingForStream] → [BannerCategory.SSE_STALLED].
 */
internal fun SseConnectionFeedback.bannerCategory(
    mtlsDegradedError: String?,
    authFailureReason: AuthFailureReason? = null,
): BannerCategory? = when (this) {
    is SseConnectionFeedback.Disabled -> BannerCategory.USER_DISABLED
    is SseConnectionFeedback.WaitingForStream -> BannerCategory.SSE_STALLED
    is SseConnectionFeedback.SseBootstrapFailed -> BannerCategory.SSE_BOOTSTRAP_FAILED
    is SseConnectionFeedback.Disconnected ->
        if (authFailureReason != null || mtlsDegradedError != null) BannerCategory.AUTH_FAILURE else BannerCategory.REST_OUTAGE
    // Live / Connecting / Reconnecting / AwaitingTofuTrust / Idle → no banner
    is SseConnectionFeedback.Live -> null
    is SseConnectionFeedback.Connecting -> null
    is SseConnectionFeedback.Reconnecting -> null
    is SseConnectionFeedback.AwaitingTofuTrust -> null
    is SseConnectionFeedback.Idle -> null
}
