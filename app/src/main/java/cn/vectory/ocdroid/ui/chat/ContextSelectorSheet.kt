package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.util.WorkdirPaths
/**
 * §round-B ② (scheme D.5): one explicit Host → workdir scope chooser.
 *
 * Replaces the previous shell's "DNS icon + ServerManagementDialog" host
 * entry AND the buried SessionsScreen workdir picker (one surface, one
 * mental model — scheme P5-2).
 *
 * Behaviour changes vs the previous ContextSelectorSheet:
 *  - Workdir source is the **active host's recent workdirs**
 *    ([cn.vectory.ocdroid.ui.SettingsViewModel.recentWorkdirs]) — NOT
 *    `sessionList.sessions.map { it.directory }`. Recent-workdirs is the
 *    source of truth for "connected" (mirrors SessionsScreen's
 *    buildWorkdirGroups gate) and includes workdirs the user connected
 *    to but has not yet opened a session in.
 *  - Workdir selection calls back into [onSelectWorkdir]; the host
 *    (ChatScaffold) routes it through [resolveWorkdirSelection] →
 *    PRESERVE_CURRENT (current session already in the workdir) or
 *    MATERIALIZE_DRAFT (open a fresh scoped draft). The previous "pick
 *    the first session in that directory" semantics is GONE — it broke
 *    session scoping by re-selecting an arbitrary sibling.
 *  - The sheet body is **scrollable** (long workdir lists no longer
 *    overflow the ModalBottomSheet viewport).
 *  - "Manage hosts" entry routes to Settings → Hosts (scheme D.5 host
 *    management surface). ServerManagement (connect / refresh / tunnel /
 *    switch host) stays reachable there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSelectorSheet(
    profiles: List<HostProfile>,
    currentProfileId: String?,
    /** Recent workdirs for the active host (typically sourced from
     *  [cn.vectory.ocdroid.ui.SettingsViewModel.recentWorkdirs]). */
    recentWorkdirs: List<String>,
    currentWorkdir: String?,
    onSelectHost: (String) -> Unit,
    /** Invoked with the user's chosen workdir. The host decides
     *  PRESERVE_CURRENT vs MATERIALIZE_DRAFT via [resolveWorkdirSelection]
     *  — this sheet does NOT pick a session in the workdir. */
    onSelectWorkdir: (String) -> Unit,
    /** Open the Settings → Hosts management surface (ServerManagement
     *  parity: connect / refresh / tunnel / switch host). */
    onManageHosts: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentHost = profiles.firstOrNull { it.id == currentProfileId }
    // De-duplicate + preserve first-seen order so a workdir that appears
    // multiple times in recentWorkdirs renders once. §B1-fix⑤ (三评委共识):
    // dedup by the NORMALIZED key (so `/a` and `/a/` collapse to one row) but
    // keep the FIRST-seen raw string for display + callback (the server needs
    // the original path). The previous code computed `key = normalize(raw)`
    // but `seen.add(raw)` — so `/a` and `/a/` both passed the contains check
    // and rendered twice.
    val distinctWorkdirs = remember(recentWorkdirs) {
        val seen = LinkedHashMap<String, String>() // normalizedKey → first-seen raw
        for (raw in recentWorkdirs) {
            val key = WorkdirPaths.normalize(raw)
            if (key.isBlank()) continue
            if (!seen.containsKey(key)) seen[key] = raw
        }
        seen.values.toList()
    }
    val currentWorkdirBasename = currentWorkdir
        ?.split("/")
        ?.filter { it.isNotEmpty() }
        ?.lastOrNull()
        ?: currentWorkdir
        ?: ""

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            // Header — current context echo (scheme D.5).
            ListItem(
                headlineContent = {
                    Text(
                        text = currentHost?.displayName ?: stringResource(R.string.context_selector_no_host),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                supportingContent = {
                    Text(
                        text = currentWorkdirBasename.ifEmpty {
                            stringResource(R.string.context_selector_no_workdir)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
            HorizontalDivider()

            // Hosts section.
            Text(
                text = stringResource(R.string.context_selector_hosts),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            profiles.forEach { profile ->
                ListItem(
                    headlineContent = { Text(profile.displayName) },
                    supportingContent = {
                        Text(
                            text = profile.serverUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    trailingContent = {
                        RadioButton(
                            selected = profile.id == currentProfileId,
                            onClick = { onSelectHost(profile.id) },
                        )
                    },
                    modifier = Modifier.clickable { onSelectHost(profile.id) },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.context_selector_manage_hosts)) },
                leadingContent = {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingContent = {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                modifier = Modifier.clickable { onManageHosts() },
            )
            HorizontalDivider()

            // Workdirs section.
            Text(
                text = stringResource(R.string.context_selector_workdirs),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (distinctWorkdirs.isEmpty()) {
                Text(
                    text = stringResource(R.string.sessions_tab_no_workdirs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            } else {
                distinctWorkdirs.forEach { workdir ->
                    val basename = workdir
                        .split("/")
                        .filter { it.isNotEmpty() }
                        .lastOrNull()
                        ?: workdir
                    ListItem(
                        headlineContent = {
                            Text(
                                text = basename,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = workdir,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        trailingContent = {
                            RadioButton(
                                // §B1-fix⑤: normalize the selection check so the
                                // radio correctly highlights when currentWorkdir
                                // and the list entry differ only by surrounding
                                // slashes/whitespace.
                                selected = currentWorkdir != null &&
                                    WorkdirPaths.normalize(workdir) == WorkdirPaths.normalize(currentWorkdir),
                                onClick = { onSelectWorkdir(workdir) },
                            )
                        },
                        modifier = Modifier.clickable { onSelectWorkdir(workdir) },
                    )
                }
            }
        }
    }
}
