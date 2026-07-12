package cn.vectory.ocdroid.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.chat.workdirTone
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.util.WorkdirPaths

/**
 * §Q2 (P4b-A): a compact Workdir indicator for the Files and Git top bars.
 *
 * Renders `[hash-color FolderOpen icon] + workdir basename` — the whole
 * control is tinted with [workdirTone] of [currentWorkdir] so the user gets
 * a consistent color cue for the active project. A null [currentWorkdir]
 * shows a muted [Icons.Default.FolderOff] + placeholder (no tone).
 *
 * **§files-git-readonly-workdir**: when [readOnly] is true (the new default
 * for Files and Git), the control is a NON-INTERACTIVE indicator only. It
 * still shows the current workdir (hash color + path basename) so the user
 * knows which project the Files/Git pane is scoped to, but it has no click
 * affordance, no selection sheet, and no chevron / drop-arrow that would
 * suggest it is tappable. Per the user requirement, workdir switching now
 * happens ONLY by opening a session that belongs to that directory.
 *
 * When [readOnly] is false (legacy / future callers), the original behaviour
 * is preserved: click opens a [ModalBottomSheet] listing [recentWorkdirs]
 * (MRU). Each row: `[hash-color Folder icon] + basename + full path`. NO
 * section header. Tapping a row invokes [onSelect] and dismisses the sheet.
 * The current workdir row is highlighted with a trailing checkmark. An
 * empty [recentWorkdirs] shows a single non-interactive "no connected
 * projects" row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkdirControl(
    currentWorkdir: String?,
    recentWorkdirs: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    val tone: Color = currentWorkdir?.let { workdirTone(it) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant
    val basename = currentWorkdir
        ?.substringAfterLast('/')
        ?.ifBlank { currentWorkdir }
        ?: ""

    if (readOnly) {
        // §files-git-readonly-workdir: indicator-only. No onClick, no ripple,
        // no semantics Role.Button, no chevron. The row still conveys the
        // current workdir (icon + basename, hash-tinted) so the user can see
        // which project the Files/Git pane is scoped to.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .padding(horizontal = Dimens.spacing2, vertical = Dimens.spacing1)
                .semantics {
                    contentDescription = basename.ifBlank { "No workdir" }
                },
        ) {
            Icon(
                imageVector = if (currentWorkdir != null) Icons.Default.FolderOpen else Icons.Default.FolderOff,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(Dimens.iconSm),
            )
            Spacer(Modifier.width(Dimens.spacing1))
            Text(
                text = basename.ifBlank { stringResource(R.string.files_workdir_none) },
                color = tone,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    var showSheet by rememberSaveable { mutableStateOf(false) }
    val switchDesc = stringResource(R.string.files_workdir_switch)

    Surface(
        onClick = { showSheet = true },
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = tone,
        modifier = modifier.semantics { contentDescription = switchDesc },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Dimens.spacing2, vertical = Dimens.spacing1),
        ) {
            Icon(
                imageVector = if (currentWorkdir != null) Icons.Default.FolderOpen else Icons.Default.FolderOff,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(Dimens.iconSm),
            )
            Spacer(Modifier.width(Dimens.spacing1))
            Text(
                text = basename.ifBlank { stringResource(R.string.files_workdir_none) },
                color = tone,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            WorkdirSwitcherBody(
                currentWorkdir = currentWorkdir,
                recentWorkdirs = recentWorkdirs,
                onSelect = { wd ->
                    showSheet = false
                    onSelect(wd)
                },
            )
        }
    }
}

@Composable
private fun WorkdirSwitcherBody(
    currentWorkdir: String?,
    recentWorkdirs: List<String>,
    onSelect: (String) -> Unit,
) {
    // De-duplicate by normalized key (mirrors ContextSelectorSheet): `/a` and
    // `/a/` collapse to one row, but the first-seen raw string is kept for
    // display + callback (the server needs the original path).
    val distinctWorkdirs = remember(recentWorkdirs) {
        val seen = LinkedHashMap<String, String>() // normalizedKey → first-seen raw
        for (raw in recentWorkdirs) {
            val key = WorkdirPaths.normalize(raw)
            if (key.isBlank()) continue
            if (!seen.containsKey(key)) seen[key] = raw
        }
        seen.values.toList()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        if (distinctWorkdirs.isEmpty()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.files_workdir_none_connected)) },
                leadingContent = {
                    Icon(
                        Icons.Default.FolderOff,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.iconStd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        } else {
            distinctWorkdirs.forEach { wd ->
                val wdTone = workdirTone(wd)
                val wdBase = wd.substringAfterLast('/').ifBlank { wd }
                val isCurrent = currentWorkdir != null &&
                    WorkdirPaths.normalize(wd) == WorkdirPaths.normalize(currentWorkdir)
                ListItem(
                    headlineContent = {
                        Text(
                            text = wdBase,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = wd,
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
                            tint = wdTone,
                            modifier = Modifier.size(Dimens.iconStd),
                        )
                    },
                    trailingContent = {
                        if (isCurrent) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(Dimens.iconSm),
                            )
                        }
                    },
                    modifier = Modifier.clickable { onSelect(wd) },
                )
            }
        }
    }
}
