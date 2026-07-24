// ServerStatusIconButton.kt — top-bar server-status affordance for the home
// hub (home-hub T2). Renders an IconButton (Icons.Default.Dns) wrapped in a BadgedBox whose
// small status dot mirrors the historical tag-0.7.6 ChatTopBar behaviour with
// a three-colour scheme for the connection + slim mode:
//   connected + slimActive  → SemanticColors.stateSlimFg()    (blue)
//   connected + !slimActive → SemanticColors.stateSuccessFg() (green)
//   connecting              → SemanticColors.stateInfoFg()    (amber/info)
//   idle                    → no dot
//   else                    → colorScheme.error (red)
//
// On click the composable opens the shared [ServerManagementDialog] (the popup
// that was relocated out of ChatTopBar in §0.8.2 P2 and had no consumer since).
// `onNavigateToSettings` is forwarded verbatim — the dialog already renders a
// Settings IconButton (Tier-A entry to the Settings tab) that calls it.

package cn.vectory.ocdroid.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.chat.ServerManagementDialog
import cn.vectory.ocdroid.ui.settings.DebugLogSection
import cn.vectory.ocdroid.ui.theme.AppBottomSheet
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.ui.theme.SemanticColors

/**
 * Home-hub top-bar server-status affordance.
 *
 * Renders `IconButton(Icons.Default.Dns)` inside a [BadgedBox] with a small
 * status dot whose colour derives from the connection phase and slim mode:
 *  - [isConnected] + [slimActive] → [SemanticColors.stateSlimFg] (blue, slim mode active)
 *  - [isConnected] + !slimActive → [SemanticColors.stateSuccessFg] (green, standard mode)
 *  - [isConnecting] → [SemanticColors.stateInfoFg] (amber/info, probe in flight)
 *  - [isIdle] → no dot (nothing rendered, mirroring the v0.7.6 `Idle` branch)
 *  - otherwise → [MaterialTheme.colorScheme.error] (Disconnected /
 *    Reconnecting / AwaitingTofuTrust)
 *
 * §breathing-indicator (item ①): when [isSseConnected] is true AND the dot is
 * in a connected colour (green/blue), the dot gently BREATHES (alpha + a tiny
 * scale pulse) to signal the SSE event stream is live. No pulse on amber/red/
 * idle, and no pulse when SSE is down — the three-colour semantics stay fully
 * intact; the breathing is ADDITIVE on the connected states only. The pulse
 * is intentionally calm (slow tween, reverse repeat) — a designer will review
 * the feel separately.
 *
 * On click the composable opens [ServerManagementDialog], forwarding every
 * connection / host field + [onNavigateToSettings] (the dialog's own
 * Settings IconButton consumes it). Callers (SessionsScreen home page) source
 * the fields from `connectionVM` / `hostVM` — same reads
 * ChatScaffold performs for ChatTopBarState.
 *
 * @param isConnected          true when the connection is healthy.
 * @param slimActive           true when slim mode is the active live mode
 *                              (serverCompatProfile.slimConnection mirror).
 * @param isConnecting         true while a connect probe is in flight.
 * @param isIdle               true when the phase is [ConnectionPhase.Idle]
 *                              (no dot rendered).
 * @param isSseConnected       §breathing-indicator: true when the SSE
 *                              transport has proven delivery (a valid frame
 *                              reached [cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner]).
 *                              Drives the breathing pulse on the connected
 *                              dot. INDEPENDENT of [isConnected] (which is
 *                              health-settle); the two can differ transiently.
 * @param hostProfiles         configured host profiles (dialog list).
 * @param currentHostProfileId the active host profile id (dialog highlight).
 * @param tunnelActivationState tunnel-activation slice (dialog button state).
 * @param showTunnelAuth       whether the dialog should render the tunnel button.
 * @param serverVersion        connected server version (dialog display).
 * @param onSelectHost         switch host profile (dialog row tap).
 * @param onRefresh            refresh messages (dialog refresh button).
 * @param onActivateTunnel     activate the tunnel for the current host.
 * @param onNavigateToSettings open the Settings tab (dialog settings button).
 * @param modifier             applied to the outer [BadgedBox].
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ServerStatusIconButton(
    isConnected: Boolean,
    slimActive: Boolean,
    isConnecting: Boolean,
    isIdle: Boolean,
    isSseConnected: Boolean,
    hostProfiles: List<HostProfile>,
    currentHostProfileId: String?,
    tunnelActivationState: TunnelActivationState,
    showTunnelAuth: Boolean,
    serverVersion: String?,
    onSelectHost: (String) -> Unit,
    onRefresh: () -> Unit,
    onActivateTunnel: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    // §debug-escape (P0 2-B): long-press the status button opens the debug-log
    // sheet — an escape hatch for when Settings navigation stalls or a connect
    // hangs. Single-tap still opens the server dialog (no behaviour change).
    var showDebugLog by remember { mutableStateOf(false) }

    // Dot colour resolution — three-colour scheme:
    // connected + slimActive → blue (slim mode active)
    // connected + !slimActive → green (standard mode)
    // connecting → amber/info (probe in flight, transient)
    // idle → no dot
    // everything else → red (Disconnected / Reconnecting / Awaiting…)
    val dotColor: Color? = when {
        isConnected && slimActive -> SemanticColors.stateSlimFg()
        isConnected -> SemanticColors.stateSuccessFg()
        isConnecting -> SemanticColors.stateInfoFg()
        isIdle -> null
        else -> MaterialTheme.colorScheme.error
    }

    // §breathing-indicator (item ①): the dot breathes ONLY while the SSE
    // transport is live AND the dot is in a connected (green/blue) state.
    // Gating on `isConnected && isSseConnected` keeps the breathing off the
    // amber/red/idle dots and off whenever SSE is down — so the three-colour
    // semantics stay fully intact and the pulse is a pure ADDITIVE "stream
    // alive" cue. rememberInfiniteTransition is created unconditionally
    // (Compose rule: composable calls must not be conditional); the breathing
    // values are simply not applied when [breathe] is false.
    //
    // §ui-style-spec §2: no scattered dp/numeric literals for the animation —
    // the durations + ranges live in the file-level SseBreathSpec object below
    // (animation constants, not dp dimensions).
    val breathe = isConnected && isSseConnected
    val breathTransition = rememberInfiniteTransition(label = "sseBreath")
    val breathAlpha by breathTransition.animateFloat(
        initialValue = SseBreathSpec.ALPHA_MIN,
        targetValue = SseBreathSpec.ALPHA_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(SseBreathSpec.DURATION_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sseBreathAlpha",
    )
    val breathScale by breathTransition.animateFloat(
        initialValue = SseBreathSpec.SCALE_MIN,
        targetValue = SseBreathSpec.SCALE_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(SseBreathSpec.DURATION_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sseBreathScale",
    )
    val dotAlpha = if (breathe) breathAlpha else 1f
    val dotScale = if (breathe) breathScale else 1f

    BadgedBox(
        modifier = modifier,
        badge = {
            if (dotColor != null) {
                // Small dot, sized + shaped to match the existing SessionStatusDot /
                // unread-dot primitives (SessionsScreen.kt). BadgedBox anchors this
                // slot at the top-end corner of the IconButton — the conventional
                // status-badge position.
                Box(
                    modifier = Modifier
                        // Offset inward so the dot sits on the icon's top-right
                        // corner instead of clipping past the IconButton bounds.
                        // §ui-style-spec §2: nearest Dimens token to 3dp (no 3dp
                        // token exists); spacing1 = 4dp.
                        .padding(end = Dimens.spacing1, top = Dimens.spacing1)
                        // §ui-style-spec §2: status-dot size uses the spacing
                        // token (no 8dp icon-size token; spacing2 = 8dp matches
                        // the SessionStatusDot / unread-dot primitives).
                        .size(Dimens.spacing2)
                        // §breathing-indicator: apply the calm alpha + scale
                        // pulse via graphicsLayer so the dot's colour stays the
                        // resolved three-colour value (no colour mutation). When
                        // [breathe] is false both read 1f → identity transform,
                        // so non-SSE-connected states render statically.
                        .graphicsLayer {
                            alpha = dotAlpha
                            scaleX = dotScale
                            scaleY = dotScale
                        }
                        .background(color = dotColor, shape = CircleShape)
                )
            }
        },
    ) {
        // Box replaces IconButton so combinedClickable can route tap → server
        // dialog and long-press → debug log. Touch target stays at M3's 48dp
        // minimum (Dimens.touchTargetMin) so the affordance is no smaller than
        // the original IconButton; combinedClickable supplies the default
        // ripple/indication, preserving the IconButton feel.
        Box(
            modifier = Modifier
                .size(Dimens.touchTargetMin)
                .combinedClickable(
                    onClick = { showDialog = true },
                    onLongClick = { showDebugLog = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Dns,
                // Reuses the server-dialog title string ("Servers") — the natural
                // a11y label for a button that opens the server management popup.
                contentDescription = stringResource(R.string.server_dialog_title),
            )
        }
    }

    if (showDialog) {
        ServerManagementDialog(
            hostProfiles = hostProfiles,
            currentHostProfileId = currentHostProfileId,
            tunnelActivationState = tunnelActivationState,
            showTunnelAuth = showTunnelAuth,
            serverVersion = serverVersion,
            onSelectHost = onSelectHost,
            onRefresh = onRefresh,
            onActivateTunnel = onActivateTunnel,
            onNavigateToSettings = onNavigateToSettings,
            onDismiss = { showDialog = false },
        )
    }

    // §debug-escape: B-layer AppBottomSheet rendering DebugLogSection (Hilt
    // EntryPoint self-sources SettingsManager, no callback plumbing needed).
    // hideHeader = true because the sheet title already says "Debug Log".
    if (showDebugLog) {
        AppBottomSheet(
            onDismissRequest = { showDebugLog = false },
            title = stringResource(R.string.debug_log_title),
        ) {
            DebugLogSection(hideHeader = true)
        }
    }
}

/**
 * §breathing-indicator (item ①): breathing-pulse constants for the SSE-
 * connected status dot. Intentionally CALM (slow tween + reverse repeat) so
 * the pulse reads as "stream alive" without being distracting. A designer
 * will separately review the feel; these are a spec-compliant basic pulse.
 *
 * Kept as a private file-level object (not `Dimens`, which is for dp/sp
 * geometry tokens) — these are animation timings + float ranges, not
 * dimensions. §ui-style-spec §2's "no scattered literals" rule applies to dp
 * geometry; animation constants live next to the animation they drive.
 */
private object SseBreathSpec {
    /** One half-cycle duration (initialValue → targetValue). Reverse repeat
     *  doubles the visible period, so a full breathe-in+breathe-out is 2×this. */
    const val DURATION_MS: Int = 1_600
    /** Min alpha during the pulse (dot dims to ~65%). */
    const val ALPHA_MIN: Float = 0.65f
    /** Max alpha (full opacity). */
    const val ALPHA_MAX: Float = 1f
    /** Min scale (dot shrinks to 90% — a subtle "inhale"). */
    const val SCALE_MIN: Float = 0.9f
    /** Max scale (resting size). */
    const val SCALE_MAX: Float = 1f
}
