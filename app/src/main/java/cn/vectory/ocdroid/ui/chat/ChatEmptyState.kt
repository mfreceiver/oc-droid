// ChatEmptyState.kt — the empty-state placeholder shown by ChatScreen when
// there is no active session or the server connection is down/in-flight.
// Pure relocation from ChatTopBar.kt; visibility stays `internal` (used by
// ChatScreen).

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.ConnectionPhase

@Composable
internal fun ChatEmptyState(
    isConnected: Boolean,
    onConnect: () -> Unit,
    isConnecting: Boolean = false,
    connectionPhase: ConnectionPhase = ConnectionPhase.Idle,
    hostName: String = ""
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isConnecting) {
                // Cold-start reconnect in flight: show a spinner + phase text
                // instead of the bare connect button. The button stays reserved
                // for the truly-disconnected case below.
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 4.dp
                )
                Spacer(modifier = Modifier.height(16.dp))
                // §R18 Phase 2-I: connectionPhase is now a sealed ConnectionPhase.
                // The legacy filter `takeIf { it.isNotBlank() && it != "connecting" }`
                // is re-expressed via [ConnectionPhase.displayTextForEmptyState]
                // which returns null exactly for the phases the old filter
                // excluded (blank/"connecting"/legacy null→Idle).
                val phaseText = connectionPhase.displayTextForEmptyState()
                Text(
                    text = if (phaseText != null) {
                        stringResource(R.string.chat_reconnecting_with_phase, hostName, phaseText)
                    } else {
                        stringResource(R.string.chat_reconnecting, hostName)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    if (isConnected) stringResource(R.string.chat_select_or_create_session) else stringResource(R.string.chat_connect_to_server),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (!isConnected) {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(stringResource(R.string.chat_connect))
                    }
                }
            }
        }
    }
}

/**
 * §R18 Phase 2-I + 2-G: sealed→localized-display-string mapping for the
 * empty-state "Reconnecting to $host ($phase)" interpolation. Returns null
 * for phases where the legacy `takeIf { it.isNotBlank() && it != "connecting" }`
 * filter suppressed the phase text (i.e. blank, plain "connecting", or the
 * legacy null sentinel — now [ConnectionPhase.Idle]).
 *
 * `@Composable` so the phase text is resolved via [androidx.compose.res.stringResource]
 * and honours the system locale (maxer+glmer Gate-2 review: the prior English-
 * only literal leaked "reconnecting (attempt N/M)" to Chinese users). Exhaustive
 * `when` (no `else`) so the compiler forces an update when a new ConnectionPhase
 * variant is added.
 */
@Composable
private fun ConnectionPhase.displayTextForEmptyState(): String? = when (this) {
    is ConnectionPhase.Idle -> null
    is ConnectionPhase.Connecting -> null
    is ConnectionPhase.Connected -> null
    is ConnectionPhase.Reconnecting -> stringResource(R.string.chat_phase_reconnecting)
    is ConnectionPhase.ReconnectingAttempt -> stringResource(R.string.chat_phase_reconnecting_attempt, attempt, maxAttempts)
    is ConnectionPhase.Disconnected -> stringResource(R.string.chat_phase_disconnected)
    is ConnectionPhase.AwaitingTofuTrust -> null  // §tofu R2: the TOFU dialog overlays; empty-state stays quiet.
}
