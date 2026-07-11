// ChatPermissionCard.kt — the floating "Permission Required" card that the
// chat surface overlays when the agent requests an authorization. Pure
// relocation from ChatInputBar.kt; this composable is orthogonal to the input
// bar (it shares the chat surface but has no state/interaction with the
// composer), so it lives in its own file for clarity.
//
// §1C-FIX-⑧: per scheme E.4 the card surfaces the host / workdir / session
// / tool / target so the user knows which session is asking and which
// scope an "Always allow" would apply to. All copy is resource-backed
// (no hardcoded English); the metadata fields are passed in by the caller
// (StatusSlot → ChatScaffold) which already has access to the slices.

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.PermissionResponse

/**
 * §1C-FIX-⑧: the metadata fields surfaced per scheme E.4. The
 * caller (StatusSlot, which is in turn called by ChatScaffold) is
 * responsible for sourcing these from the slices (host.hostProfiles,
 * session.directory, session.displayName, permission.tool /
 * permission.metadata) and passing them in. Null fields render
 * their line as "(missing)" — the data is best-effort: a session
 * can be present without a host (e.g. cold-start pre-SSE), in
 * which case the host line is suppressed entirely (we do not show
 * a half-filled card; the metadata block is conditionally rendered
 * only when at least one field is present).
 *
 * @param hostName current host profile's display name (from
 *        HostState.currentHostProfileId → HostState.hostProfiles).
 * @param workdirBasename last path segment of the current session's
 *        directory, or the draft workdir if no session is open.
 *        Null when neither is set (suppresses the workdir line).
 * @param sessionName current session's display name (from
 *        Session.displayName). Null suppresses the session line
 *        (e.g. during the pre-session draft state).
 * @param toolName the permission's tool (e.g. "bash" / "edit" /
 *        "webfetch"). Null when the server didn't echo it; the
 *        tool line is suppressed.
 * @param target the permission's target — for file operations
 *        this is permission.metadata.filepath; for shell commands
 *        it would be the command line. The exact field is
 *        caller's choice (we just render what we get).
 */
internal data class ChatPermissionMetadata(
    val hostName: String?,
    val workdirBasename: String?,
    val sessionName: String?,
    val toolName: String?,
    val target: String?,
)

@Composable
internal fun ChatPermissionCard(
    permission: PermissionRequest,
    metadata: ChatPermissionMetadata,
    onRespond: (PermissionResponse) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.permission_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = permission.permission
                        ?: stringResource(R.string.permission_card_unknown_permission),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // §1C-FIX-⑧: scheme E.4 metadata block. Each line is
                // conditionally rendered when its field is non-null;
                // a card with no metadata fields at all (e.g. cold-
                // start, no host / session / tool) still shows the
                // title + permission name + buttons.
                Spacer(modifier = Modifier.size(8.dp))
                metadata.hostName?.let { host ->
                    Text(
                        text = stringResource(R.string.permission_card_metadata_host, host),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                metadata.workdirBasename?.let { workdir ->
                    Text(
                        text = stringResource(R.string.permission_card_metadata_workdir, workdir),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                metadata.sessionName?.let { session ->
                    Text(
                        text = stringResource(R.string.permission_card_metadata_session, session),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                metadata.toolName?.let { tool ->
                    Text(
                        text = stringResource(R.string.permission_card_metadata_tool, tool),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Target gets a monospace block when present (it can
                // be a file path or a command — both benefit from
                // monospace alignment).
                metadata.target?.let { target ->
                    Spacer(modifier = Modifier.size(4.dp))
                    SelectionContainer {
                        Text(
                            text = stringResource(R.string.permission_card_metadata_target, target),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = BundledMonoFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.size(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onRespond(PermissionResponse.REJECT) }) {
                        Text(
                            text = stringResource(R.string.permission_reject),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onRespond(PermissionResponse.ONCE) }) {
                        Text(
                            text = stringResource(R.string.permission_allow_once),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onRespond(PermissionResponse.ALWAYS) }) {
                        Text(
                            text = stringResource(R.string.permission_always_allow),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
