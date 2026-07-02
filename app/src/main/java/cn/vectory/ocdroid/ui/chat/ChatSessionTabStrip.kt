// ChatSessionTabStrip.kt — session tab strip (TopAppBar's second row) and the
// merged context/todo/agent dropdown that anchors on the ContextUsageRing.
// Split out of ChatTopBar.kt for readability; pure relocation, no behaviour
// change. Both composables receive ChatTopBarActions / state slices as params.

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.ContextUsage
import cn.vectory.ocdroid.ui.theme.opencode

private const val SESSION_TITLE_MAX_CHARS = 15

/**
 * Shared title-truncation helper used by the title-slot parent breadcrumb
 * (ChatTopBar) and the session tab labels below. Emits an ellipsis when the
 * title exceeds [SESSION_TITLE_MAX_CHARS].
 */
internal fun truncateTitle(value: String): String =
    if (value.length <= SESSION_TITLE_MAX_CHARS) value
    else value.take(SESSION_TITLE_MAX_CHARS - 1) + "…"

/**
 * §17: persistent horizontal session tab strip rendered as the TopAppBar's
 * second row. Replaces the former title-slot dropdown switcher. Built on the
 * M3 [PrimaryScrollableTabRow] + [Tab] so we inherit the platform scroll-to-centre
 * behaviour and the standard touch target for free. Each tab's `text` slot
 * carries the (truncated) session title, an unread dot when the session
 * received an out-of-band message, and a close-X affordance.
 *
 * **Selection highlight** ([parentSessionId]): the active tab is resolved via
 * [effectiveSelectedId] — currentSessionId when it is a root session, otherwise
 * the parent session (so opening a sub-agent highlights its parent tab rather
 * than erroneously landing on tab 0). `selectedTabIndex` passed to M3 is only a
 * scroll-centre hint (clamped, never -1); the visible highlight is driven
 * per-tab so a null effective id draws no highlight at all.
 *
 * **Indicator + colours** (§problem-3/4): the M3 default indicator is disabled
 * (`indicator = {}`); each selected tab self-draws a 2dp underline whose width
 * matches the measured title width (so it hugs the title and never spans the
 * close button). The underline and each unread dot use a workdir-hash tone
 * ([workdirTone]); selected title text uses theme `onSurface` + SemiBold,
 * unselected uses `onSurfaceVariant`.
 *
 * **Tab height**: each `Tab` is constrained to 36dp (via `Modifier.height`)
 * for a compact second-row strip; the inner close-X touch target and icon are
 * shrunk accordingly to fit. The M3 default 48dp is overridden because this
 * strip is a secondary navigation row beneath the TopAppBar, not the primary
 * tab surface.
 *
 * Per §17.2 the `openSessions` list is already filtered to root sessions
 * (parentId == null) by ChatScreen, so sub-agent sessions never appear here —
 * the tab strip and the title-slot breadcrumb (§8) coexist without conflict.
 *
 * The former trailing "+" affordance (which opened the Sessions page) has been
 * removed: Sessions is now reached via the [androidx.compose.material3.TopAppBar]'s
 * `navigationIcon` (left of the title).
 */
@Composable
internal fun SessionTabStrip(
    openSessions: List<Session>,
    currentSessionId: String?,
    parentSessionId: String?,
    currentWorkdir: String?,
    unreadSessions: Set<String>,
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier
) {
    // Empty-list guard: PrimaryScrollableTabRow indexes its indicator by
    // selectedTabIndex against the tab list, so rendering it with zero tabs
    // would trip an out-of-bounds when drawing the indicator.
    if (openSessions.isEmpty()) return

    val oc = MaterialTheme.opencode
    // Indicator/accent colour: current session's workdir hash. Falls back to
    // the v2 accentText when no session is active so the indicator still
    // renders with a sane colour.
    val accentColor = currentWorkdir?.let { workdirTone(it, oc) } ?: oc.accentText

    // §problem-1 fix: resolve which tab reads as "active". currentSessionId may
    // belong to a sub-agent not present in openSessions (§17.2 filters to root
    // sessions) — fall back to the parent session so opening a sub-agent
    // highlights its parent tab instead of erroneously landing on tab 0.
    // Draft sessions (currentSessionId == null) and multi-level orphans
    // highlight nothing (null).
    val effectiveSelectedId: String? = when {
        currentSessionId != null && openSessions.any { it.id == currentSessionId } -> currentSessionId
        parentSessionId != null && openSessions.any { it.id == parentSessionId } -> parentSessionId
        else -> null
    }
    // PrimaryScrollableTabRow needs a non-negative selectedTabIndex to drive
    // its scroll-to-centre; clamp to a valid index. The visible highlight is
    // driven per-tab by effectiveSelectedId (not this index), so an off match
    // here only affects initial scroll position — never pass -1 to M3 (its
    // indicator indexing is version-sensitive and may crash on negative).
    val scrollHintIndex = openSessions
        .indexOfFirst { it.id == effectiveSelectedId }
        .let { if (it >= 0) it else 0 }

    PrimaryScrollableTabRow(
        selectedTabIndex = scrollHintIndex,
        modifier = modifier.fillMaxWidth(),
        // This is the TopAppBar's second row, so drop the default surface
        // fill + bottom divider — the strip must blend with the bar above.
        containerColor = Color.Transparent,
        divider = {},
        // contentColor tints the (now-empty) default indicator slot and the
        // tab ripple; keep the accentColour for visual consistency.
        contentColor = accentColor,
        // §problem-4: disable the M3 default indicator — each tab draws its own
        // title-width underline (see below) so the underline no longer spans
        // the full tab width including the close button.
        indicator = {},
        // Match the former LazyRow's 8dp horizontal content padding instead
        // of the M3 default 52dp (TabRowDefaults.ScrollableTabRowEdgePadding),
        // which would push the first/last tabs far from the strip edges.
        edgePadding = 8.dp
    ) {
        openSessions.forEach { session ->
            val isSelected = session.id == effectiveSelectedId
            // Measured title width in pixels; per-tab and keyed by session.id so
            // each tab remembers its own measurement across recompositions.
            var titleWidthPx by remember(session.id) { mutableIntStateOf(0) }
            Tab(
                selected = isSelected,
                onClick = { actions.onSelectSession(session.id) },
                // Compact 36dp height — this is a secondary nav row, not the
                // primary tab surface. Modifier.height overrides the M3
                // default 48dp (heightIn min) by clamping both min and max.
                modifier = Modifier.height(36.dp),
                // §problem-3: selected/unselected text now use theme onSurface /
                // onSurfaceVariant (weight differentiates selection); the hash
                // colour lives only on the indicator underline + unread dot.
                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // §problem-10: unread dot uses the *session's own*
                        // workdir hash, not the global currentWorkdir accent,
                        // so each dot keeps a stable colour as the user
                        // switches tabs. 5dp fits the compact 36dp row.
                        if (session.id in unreadSessions) {
                            val dotColor = session.directory
                                ?.let { workdirTone(it, oc) }
                                ?: oc.accentText
                            Box(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(5.dp)
                                    .background(color = dotColor, shape = CircleShape)
                            )
                        }
                        // Title + self-drawn indicator wrapped together so the
                        // underline hugs the title's left/right edges exactly
                        // (§problem-4). First layout pass reports titleWidthPx
                        // == 0 → indicator skipped; it appears on the next
                        // frame once the width is known. Keyed per session.id
                        // above, an already-composed tab keeps its measured
                        // width even when it later becomes selected.
                        Column {
                            Text(
                                text = truncateTitle(session.displayName),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.SemiBold
                                else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { titleWidthPx = it.size.width }
                            )
                            if (isSelected && titleWidthPx > 0) {
                                Box(
                                    modifier = Modifier
                                        .width(
                                            with(LocalDensity.current) { titleWidthPx.toDp() }
                                        )
                                        .height(2.dp)
                                        .background(accentColor, RoundedCornerShape(1.dp))
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        // Close affordance. R-12 (WCAG 2.5.5): the visual X
                        // stays small but the touch target is enlarged as far
                        // as the 36dp row allows (28dp box). The Tab's own
                        // onClick is the broader 36dp touch target, so the X
                        // only needs to win the inner tap. Compose's clickable
                        // consumes the pointer event, so tapping the X fires
                        // onClose without also triggering the Tab's onClick
                        // (standard nested-clickable behaviour).
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clickable(onClick = { actions.onCloseSession(session.id) }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.common_close),
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    }
}

/**
 * Single anchor + dropdown that folds the former Agent TextButton, Todo icon,
 * and Context ring into one menu (#8). The anchor reuses [ContextUsageRing] so
 * the live context pressure is always visible; tapping it opens a three-row
 * dropdown whose items delegate to the existing dialogs.
 */
@Composable
internal fun ContextMenuButton(
    usage: ContextUsage?,
    todos: List<TodoItem>,
    selectedAgentName: String,
    currentModelName: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onContextClick: () -> Unit,
    onTodoClick: () -> Unit,
    onAgentClick: () -> Unit,
    onModelClick: () -> Unit
) {
    Box {
        // The ring is the trigger; the previous standalone onClick (which opened
        // the context dialog directly) is removed — the dropdown is the anchor.
        // R-12 (WCAG 2.5.5): enlarge the click target to 44dp (AAA threshold)
        // while keeping the ring visual at its tuned 28dp size (ChatUiTuning).
        // The ring is centered inside the larger transparent Surface so the
        // visual density of the actions cluster is unchanged.
        Surface(
            onClick = onToggleExpand,
            shape = RoundedCornerShape(50),
            color = Color.Transparent,
            modifier = Modifier.size(44.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                ContextUsageRing(usage = usage)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { if (expanded) onToggleExpand() }
        ) {
            // 1. Context — "{pct}% {total}/{limit}", compact counts.
            DropdownMenuItem(
                text = {
                    Text(
                        if (usage != null) {
                            val pct = (usage.percentage * 100).toInt()
                            "$pct% ${usage.totalTokens / 1000}/${usage.contextLimit / 1000}"
                        } else {
                            stringResource(R.string.chat_context)
                        }
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.DonutLarge,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = onContextClick
            )
            // 2. Todo — "{completed}/{total}", always shown (including 0/0).
            DropdownMenuItem(
                text = {
                    val completed = todos.count { it.isCompleted }
                    val total = todos.size
                    Text("$completed/$total")
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Checklist,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = onTodoClick
            )
            // 3. Agent — selected agent name; opens the standalone picker dialog.
            DropdownMenuItem(
                text = { Text(selectedAgentName) },
                leadingIcon = {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = onAgentClick
            )
            // 4. Model — §model-selection: current model display name; opens
            // the standalone model picker dialog.
            DropdownMenuItem(
                text = {
                    Text(
                        currentModelName.ifEmpty { stringResource(R.string.chat_model_picker_title) }
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = onModelClick
            )
        }
    }
}
