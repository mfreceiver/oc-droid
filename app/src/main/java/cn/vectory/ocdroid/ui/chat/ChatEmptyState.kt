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

@Composable
internal fun ChatEmptyState(
    isConnected: Boolean,
    onConnect: () -> Unit,
    isConnecting: Boolean = false,
    connectionPhase: String? = null,
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
                val phase = connectionPhase?.takeIf { it.isNotBlank() && it != "connecting" }
                Text(
                    text = if (phase != null) {
                        stringResource(R.string.chat_reconnecting_with_phase, hostName, phase)
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
