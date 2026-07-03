// ChatSessionTabStrip.kt — session tab strip (TopAppBar's second row) and the
// merged context/todo/agent dropdown that anchors on the ContextUsageRing.
// Split out of ChatTopBar.kt for readability; pure relocation, no behaviour
// change. Both composables receive ChatTopBarActions / state slices as params.

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

// Fill opacity for the selected tab background. Kept low so the workdir-hash
// tint is visible but the title text stays clearly readable on both light and
// dark surfaces.
private const val SELECTED_TAB_FILL_ALPHA = 0.22f

// Width constraints for each session tab. The 36dp-high strip is a compact
// secondary nav row, so tabs should be narrow enough to fit several on screen
// without dominating the TopAppBar, yet wide enough to show a truncated title
// plus the 24dp close affordance. 96dp is the practical minimum: it leaves
// room for the close target, a 2dp spacer, a few truncated characters and M3
// Tab's internal horizontal padding. 168dp caps a 15-character title so a
// single tab does not stretch far beyond its useful content.
private val TAB_MIN_WIDTH = 96.dp
private val TAB_MAX_WIDTH = 168.dp
private val TAB_ROW_EDGE_PADDING = 8.dp

/**
 * Shared title-truncation helper used by the title-slot parent breadcrumb
 * (ChatTopBar) and the session tab labels below. Emits an ellipsis when the
 * title exceeds [SESSION_TITLE_MAX_CHARS].
 */
internal fun truncateTitle(value: String): String =
    if (value.length <= SESSION_TITLE_MAX_CHARS) value
    else value.take(SESSION_TITLE_MAX_CHARS - 1) + "…"

/**
 * Pure selection resolver for the session tab strip (§problem-1). Returns the
 * session id that should read as "active" for highlight + scroll-centre:
 *  - [currentSessionId] when it is itself a root session present in
 *    [openSessions] (the normal case);
 *  - otherwise [parentSessionId] when that is a root session in [openSessions]
 *    (opening a sub-agent highlights its parent tab);
 *  - otherwise null (draft session with currentSessionId == null, or a
 *    multi-level orphan whose parent is itself a sub-agent not in openSessions).
 *
 * Extracted from the [SessionTabStrip] composable so the precedence + null
 * semantics are covered by JVM unit tests (EffectiveSelectedIdTest) instead of
 * only on-device verification. Pure: no Compose dependency.
 */
internal fun resolveEffectiveSelectedId(
    openSessions: List<Session>,
    currentSessionId: String?,
    parentSessionId: String?
): String? = when {
    currentSessionId != null && openSessions.any { it.id == currentSessionId } -> currentSessionId
    parentSessionId != null && openSessions.any { it.id == parentSessionId } -> parentSessionId
    else -> null
}

/**
 * §17: persistent horizontal session tab strip rendered as the TopAppBar's
 * second row. Replaces the former title-slot dropdown switcher. Built on the
 * M3 [PrimaryScrollableTabRow] + [Tab] so we inherit the platform scroll physics
 * and the standard touch target for free. Each tab's `text` slot carries the
 * (truncated) session title, an unread dot when the session received an
 * out-of-band message, and a close-X affordance.
 *
 * **Selection highlight + scroll position** ([parentSessionId]): the active tab
 * is resolved via [effectiveSelectedId] — currentSessionId when it is a root
 * session, otherwise the parent session (so opening a sub-agent highlights its
 * parent tab). The scroll-centre hint passed to M3 is deliberately decoupled:
 * it only follows explicit root-tab selection. When the user opens a sub-agent
 * the hint stays at the previously selected root index, so the strip keeps its
 * current scroll position instead of jumping to the leftmost tab.
 *
 * **Indicator + colours**: the M3 default indicator is disabled
 * (`indicator = {}`); the selected tab self-draws a translucent filled
 * background tinted with the workdir-hash colour ([workdirTone]). Unselected
 * tabs keep their plain background. The selected title text uses theme
 * `onSurface` + SemiBold, unselected uses `onSurfaceVariant`.
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
    // Accent colour: current session's workdir hash. Falls back to the v2
    // accentText when no session is active so the selected tab still tints
    // with a sane colour.
    //
    // Note (§problem-1 review follow-up): when viewing a sub-agent, the
    // currentSession is the child, so this colour comes from the child's
    // workdir while the highlighted parent tab's own unread dot uses the
    // parent's workdir hash (see dotColor below). opencode sub-agents share
    // their parent's workdir, so the two coincide in practice; the
    // theoretical mismatch only matters for a multi-workdir nesting that does
    // not occur. Keeping child-workdir here (rather than plumbing the parent
    // workdir through) is the lower-risk choice and matches the existing
    // call site which already passes currentSession.directory.
    val accentColor = currentWorkdir?.let { workdirTone(it, oc) } ?: oc.accentText

    // §problem-1 fix: resolve which tab reads as "active". currentSessionId may
    // belong to a sub-agent not present in openSessions (§17.2 filters to root
    // sessions) — fall back to the parent session so opening a sub-agent
    // highlights its parent tab instead of erroneously landing on tab 0.
    // Draft sessions (currentSessionId == null) and multi-level orphans
    // highlight nothing (null). Extracted as [resolveEffectiveSelectedId] so the
    // selection semantics have a JVM unit test (EffectiveSelectedIdTest) rather
    // than being verifiable only on-device.
    val effectiveSelectedId: String? =
        resolveEffectiveSelectedId(openSessions, currentSessionId, parentSessionId)
    // §fix-1: decouple the scroll-centre hint from sub-agent highlight.
    // [effectiveSelectedId] drives the visible per-tab highlight (parent tab
    // stays highlighted while a sub-agent is open). The hint passed to M3,
    // however, must only change on explicit root-tab selection; otherwise
    // opening a sub-agent would make the strip jump to the leftmost tab.
    // Remember the last selected root index and reuse it while currentSessionId
    // is not itself a root session.
    val currentRootIndex = openSessions.indexOfFirst { it.id == currentSessionId }
    val parentRootIndex = openSessions.indexOfFirst { it.id == parentSessionId }
    var lastRootIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentRootIndex) {
        if (currentRootIndex >= 0) lastRootIndex = currentRootIndex
    }
    val scrollHintIndex = when {
        currentRootIndex >= 0 -> currentRootIndex
        lastRootIndex in openSessions.indices -> lastRootIndex
        parentRootIndex >= 0 -> parentRootIndex
        else -> 0
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Decide layout mode by comparing the minimum-width demand of all tabs
        // against the content width offered by the TopAppBar (strip width minus
        // the preserved 8dp edge padding). Only when the tabs cannot even fit
        // at their minimum width do we fall back to horizontal scrolling;
        // otherwise we expand them evenly to fill the strip.
        val contentWidth = (maxWidth - TAB_ROW_EDGE_PADDING * 2).coerceAtLeast(0.dp)
        val overflow = TAB_MIN_WIDTH * openSessions.size > contentWidth

        if (overflow) {
            PrimaryScrollableTabRow(
                selectedTabIndex = scrollHintIndex,
                modifier = Modifier.fillMaxWidth(),
                // This is the TopAppBar's second row, so drop the default
                // surface fill + bottom divider — the strip must blend with
                // the bar above.
                containerColor = Color.Transparent,
                divider = {},
                // contentColor tints the (now-empty) default indicator slot
                // and the tab ripple; keep the accentColour for consistency.
                contentColor = accentColor,
                // §fix-2: disable the M3 default indicator — the selected tab
                // draws a translucent filled background instead of an
                // underline, so there is no full-width indicator to span the
                // close button.
                indicator = {},
                // Match the former LazyRow's 8dp horizontal content padding
                // instead of the M3 default 52dp, which would push the
                // first/last tabs far from the strip edges.
                edgePadding = TAB_ROW_EDGE_PADDING
            ) {
                openSessions.forEach { session ->
                    SessionTab(
                        session = session,
                        isSelected = session.id == effectiveSelectedId,
                        accentColor = accentColor,
                        unreadSessions = unreadSessions,
                        actions = actions,
                        modifier = Modifier.width(TAB_MIN_WIDTH)
                    )
                }
            }
        } else {
            val perTab = contentWidth / openSessions.size
            val expandsToMax = perTab >= TAB_MAX_WIDTH

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TAB_ROW_EDGE_PADDING),
                horizontalArrangement = if (expandsToMax) {
                    Arrangement.SpaceEvenly
                } else {
                    Arrangement.Start
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                openSessions.forEach { session ->
                    SessionTab(
                        session = session,
                        isSelected = session.id == effectiveSelectedId,
                        accentColor = accentColor,
                        unreadSessions = unreadSessions,
                        actions = actions,
                        modifier = if (expandsToMax) {
                            Modifier.width(TAB_MAX_WIDTH)
                        } else {
                            Modifier.width(perTab)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Single session tab shared by the scrollable and expanding layouts. The caller
 * supplies the width modifier; everything else (36dp height, selection fill,
 * title + unread dot + close-X content) stays identical in both modes.
 */
@Composable
private fun SessionTab(
    session: Session,
    isSelected: Boolean,
    accentColor: Color,
    unreadSessions: Set<String>,
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier
) {
    val oc = MaterialTheme.opencode
    Tab(
        selected = isSelected,
        onClick = { actions.onSelectSession(session.id) },
        // Compact 36dp height — this is a secondary nav row, not the primary
        // tab surface. Modifier.height overrides the M3 default 48dp
        // (heightIn min) by clamping both min and max. §fix-2: selected tabs
        // get a translucent filled background tinted with the workdir-hash
        // colour; unselected tabs stay transparent so the strip blends with
        // the TopAppBar surface.
        modifier = modifier
            .height(36.dp)
            .background(
                color = if (isSelected) {
                    accentColor.copy(alpha = SELECTED_TAB_FILL_ALPHA)
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(8.dp)
            ),
        // §fix-2: text colour stays theme onSurface / onSurfaceVariant (weight
        // differentiates selection); the hash colour now lives on the filled
        // tab background + unread dot.
        selectedContentColor = MaterialTheme.colorScheme.onSurface,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // §problem-10: unread dot uses the *session's own* workdir
                // hash, not the global currentWorkdir accent, so each dot
                // keeps a stable colour as the user switches tabs. 5dp fits
                // the compact 36dp row.
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
                Text(
                    text = truncateTitle(session.displayName),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isSelected) FontWeight.SemiBold
                    else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(2.dp))
                // §fix-3: compact close affordance. 24dp touch target is a
                // deliberate compromise — noticeably narrower than the prior
                // 28dp (so each tab keeps room for the title) while staying
                // within the small-target range reviewers accept for a
                // secondary, non-data-destructive affordance.
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = { actions.onCloseSession(session.id) }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.common_close),
                        modifier = Modifier.size(11.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
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
