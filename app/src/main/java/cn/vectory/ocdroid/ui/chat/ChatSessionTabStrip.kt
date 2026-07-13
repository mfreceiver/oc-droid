// ChatSessionTabStrip.kt — session tab strip (TopAppBar's second row).
//
// §nav-redesign (2026-07-13): restored from v0.7.6 to bring back the persistent
// horizontal "open sessions" tab strip beneath the ChatTopBar — the
// SessionPickerSheet (opened by tapping the title) and this strip now coexist:
//   - SessionTabStrip = the always-visible "browser-tab" row of up to 8 open
//     root sessions, with per-tab close-X, unread dot, "?" pending-question
//     marker, workdir-hash tint, and a vertical accent bar on the selected tab.
//   - SessionPickerSheet = the full list / search / archive surface, opened by
//     tapping the ChatTopBar title.
//
// PORTING NOTES: the v0.7.6 file also defined ContextMenuButton, resolveEffective-
// SelectedId, resolveSessionTabLayout, SessionTabLayout, and truncateTitle. Only
// SessionTabStrip + SessionTab + the two TAB_* layout constants are restored
// here; the four pure helpers were preserved in [SessionPickerHelpers.kt] when
// Phase 1B temporarily removed this strip, and are reused as-is (same package —
// no import needed). ContextMenuButton stays removed (its functionality now
// lives inside ChatTopBar as ContextMenuCluster).

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session

// Width constraints for each session tab. The 36dp-high strip is a compact
// secondary nav row, so tabs should be narrow enough to fit several on screen
// without dominating the TopAppBar, yet wide enough to show a truncated title
// plus the 24dp close affordance. 96dp is the practical minimum: it leaves
// room for the close target, a 2dp spacer, a few truncated characters and M3
// Tab's internal horizontal padding.
private val TAB_MIN_WIDTH = 96.dp
private val TAB_ROW_EDGE_PADDING = 8.dp

/**
 * §17 (restored 2026-07-13): persistent horizontal session tab strip rendered
 * as the TopAppBar's second row. Built on the M3 [PrimaryScrollableTabRow] +
 * [Tab] so we inherit the platform scroll physics and the standard touch
 * target for free. Each tab's `text` slot carries the (truncated) session
 * title, an unread dot when the session received an out-of-band message, a
 * pending-question "?" marker, and a close-X affordance (selected tab only).
 *
 * **Selection highlight + scroll position** ([parentSessionId]): the active
 * tab is resolved via [resolveEffectiveSelectedId] — currentSessionId when it
 * is a root session, otherwise the parent session (so opening a sub-agent
 * highlights its parent tab). The scroll-centre hint passed to M3 is
 * deliberately decoupled: it only follows explicit root-tab selection. When
 * the user opens a sub-agent the hint stays at the previously selected root
 * index, so the strip keeps its current scroll position instead of jumping to
 * the leftmost tab.
 *
 * **Indicator + colours**: the selected tab is marked by a vertical accent
 * bar at the left edge of its title plus Bold weight; there is no underline
 * and no background fill beyond the workdir-hash translucent tint. The bar
 * uses the workdir-hash colour ([workdirTone]). The selected title text uses
 * theme `onSurface` + Bold, unselected uses `onSurfaceVariant` + Normal.
 *
 * **Tab height**: each `Tab` is constrained to 36dp (via `Modifier.height`)
 * for a compact second-row strip; the inner close-X touch target and icon are
 * shrunk accordingly to fit. The M3 default 48dp is overridden because this
 * strip is a secondary navigation row beneath the TopAppBar, not the primary
 * tab surface.
 *
 * Per §17.2 the `openSessions` list is already filtered to root sessions
 * (parentId == null) by the caller, so sub-agent sessions never appear here —
 * the tab strip and the title-slot breadcrumb (§8) coexist without conflict.
 *
 * **Helpers reused from [SessionPickerHelpers]**: [resolveEffectiveSelectedId],
 * [resolveSessionTabLayout], [SessionTabLayout]. They stayed behind when Phase
 * 1B temporarily removed this strip; we just call them now.
 *
 * @param openSessions     root sessions to render as tabs (caller-pre-filtered
 *                         to parentId==null, non-archived; capped at 8).
 * @param currentSessionId the actually-current session id (may be a sub-agent
 *                         not present in [openSessions] — the highlight then
 *                         falls back to [parentSessionId]).
 * @param parentSessionId  the current session's parent (root) when viewing a
 *                         sub-agent; null otherwise.
 * @param currentWorkdir   the workdir to source the accent colour from (the
 *                         current session's directory, or the composer's draft
 *                         workdir as a fallback).
 * @param unreadSessions   session ids with unread out-of-band activity.
 * @param questionSessionIds ROOT session ids whose tree contains a pending
 *                         question (question.asked with no reply yet). The
 *                         "?" marker renders only on non-current sessions.
 *                         §task6: this is now root-aggregated by the caller
 *                         via [cn.vectory.ocdroid.ui.controller.questionRootIds]
 *                         — a sub-agent's question surfaces on its root tab.
 * @param actions          selection + close callbacks.
 */
@Composable
internal fun SessionTabStrip(
    openSessions: List<Session>,
    currentSessionId: String?,
    parentSessionId: String?,
    currentWorkdir: String?,
    unreadSessions: Set<String>,
    questionSessionIds: Set<String> = emptySet(),
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier
) {
    // Empty-list guard: PrimaryScrollableTabRow indexes its indicator by
    // selectedTabIndex against the tab list, so rendering it with zero tabs
    // would trip an out-of-bounds when drawing the indicator.
    if (openSessions.isEmpty()) return

    // Accent colour: current session's workdir hash. Falls back to the theme
    // primary when no session is active so the selected tab still tints with
    // a sane colour.
    val accentColor = currentWorkdir?.let { workdirTone(it) }
        ?: MaterialTheme.colorScheme.primary

    // §problem-1 fix: resolve which tab reads as "active". currentSessionId
    // may belong to a sub-agent not present in openSessions (caller filters
    // to root sessions) — fall back to the parent session so opening a
    // sub-agent highlights its parent tab instead of erroneously landing on
    // tab 0. Draft sessions (currentSessionId == null) and multi-level
    // orphans highlight nothing (null). Helper lives in SessionPickerHelpers.
    val effectiveSelectedId: String? =
        resolveEffectiveSelectedId(openSessions, currentSessionId, parentSessionId)
    // §fix-1: decouple the scroll-centre hint from sub-agent highlight.
    // [effectiveSelectedId] drives the visible per-tab highlight (parent tab
    // stays highlighted while a sub-agent is open). The hint passed to M3,
    // however, must only change on explicit root-tab selection; otherwise
    // opening a sub-agent would make the strip jump to the leftmost tab.
    val currentRootIndex = openSessions.indexOfFirst { it.id == currentSessionId }
    val parentRootIndex = openSessions.indexOfFirst { it.id == parentSessionId }
    var lastRootIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(currentRootIndex) {
        if (currentRootIndex >= 0) lastRootIndex = currentRootIndex
    }
    val scrollHintIndex = when {
        currentRootIndex >= 0 -> currentRootIndex
        lastRootIndex >= 0 -> lastRootIndex
        parentRootIndex >= 0 -> parentRootIndex
        else -> 0
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val contentWidth = (maxWidth - TAB_ROW_EDGE_PADDING * 2).coerceAtLeast(0.dp)
        when (
            resolveSessionTabLayout(
                contentWidth = contentWidth,
                tabCount = openSessions.size,
                minWidth = TAB_MIN_WIDTH,
            )
        ) {
            SessionTabLayout.OverflowScroll -> {
                PrimaryScrollableTabRow(
                    selectedTabIndex = scrollHintIndex,
                    modifier = Modifier.fillMaxWidth(),
                    // This is the TopAppBar's second row, so drop the default
                    // surface fill + bottom divider — the strip must blend
                    // with the bar above.
                    containerColor = Color.Transparent,
                    divider = {},
                    contentColor = accentColor,
                    // §chat-session-tab-selected: the active tab is marked by
                    // a vertical accent bar inside the tab label (plus Bold
                    // weight) instead of the native SecondaryIndicator
                    // underline, so we intentionally drop the indicator here.
                    indicator = {},
                    // Match the former LazyRow's 8dp horizontal content
                    // padding instead of the M3 default 52dp, which would push
                    // the first/last tabs far from the strip edges.
                    edgePadding = TAB_ROW_EDGE_PADDING
                ) {
                    openSessions.forEach { session ->
                        SessionTab(
                            session = session,
                            isSelected = session.id == effectiveSelectedId,
                            currentSessionId = currentSessionId,
                            accentColor = accentColor,
                            unreadSessions = unreadSessions,
                            questionSessionIds = questionSessionIds,
                            actions = actions,
                            modifier = Modifier.width(TAB_MIN_WIDTH)
                        )
                    }
                }
            }
            SessionTabLayout.FitWeighted -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TAB_ROW_EDGE_PADDING),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    openSessions.forEach { session ->
                        SessionTab(
                            session = session,
                            isSelected = session.id == effectiveSelectedId,
                            currentSessionId = currentSessionId,
                            accentColor = accentColor,
                            unreadSessions = unreadSessions,
                            questionSessionIds = questionSessionIds,
                            actions = actions,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single session tab shared by the scrollable and expanding layouts. The
 * caller supplies the width modifier; everything else (36dp height, title +
 * unread dot / "?" + close-X content) stays identical in both modes.
 */
@Composable
private fun SessionTab(
    session: Session,
    isSelected: Boolean,
    currentSessionId: String?,
    accentColor: Color,
    unreadSessions: Set<String>,
    questionSessionIds: Set<String>,
    actions: ChatTopBarActions,
    modifier: Modifier = Modifier
) {
    // item 1: workdir-hash translucent background. A stable per-directory tint
    // (via workdirTone) at low alpha — stronger when selected (0.18) so the
    // active tab reads as a distinct surface, fainter otherwise (0.08) to
    // hint the workdir identity without competing with the title. Only
    // applied when directory is non-empty (avoid hashing the empty string
    // into an arbitrary palette slot). Combined with the selected vertical
    // bar + Bold weight, this is additive — no removal of prior selection
    // signals.
    val tabBackground = if (session.directory.isNotEmpty()) {
        workdirTone(session.directory).copy(alpha = if (isSelected) 0.18f else 0.08f)
    } else {
        Color.Transparent
    }
    Tab(
        selected = isSelected,
        // item 3: re-selecting the already-active tab is a complete no-op so
        // it cannot trigger a full reload (UI-layer guard; SessionSwitcher
        // .switchTo is the second layer). §item3: 用 currentSessionId(实际当前
        // 会话)而非 isSelected(基于 effectiveSelectedId, 子 agent 时父 tab 高亮).
        // 否则查看子 agent 时点父 tab 会被 !isSelected 拦死, 无法回父根会话.
        // 已打开本会话(id==current)再点 = no-op.
        onClick = { if (session.id != currentSessionId) actions.onSelectSession(session.id) },
        // Compact 36dp height — this is a secondary nav row, not the primary
        // tab surface. Modifier.height overrides the M3 default 48dp.
        modifier = modifier
            .height(36.dp)
            .background(tabBackground),
        // §chat-session-tab-selected: the selection signal is the workdir-hash
        // background tint (item 1) + the vertical accent bar at the title's
        // left edge + Bold weight (see text slot below). Text colour:
        // onSurface / onSurfaceVariant.
        selectedContentColor = MaterialTheme.colorScheme.onSurface,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // item 4 + §problem-10: the leading slot shows one of three
                // states. A pending question ("?" glyph) takes priority and
                // is shown ONLY on non-current sessions — the current
                // session's question already surfaces via QuestionCard in the
                // chat list, so a tab marker there would be redundant. The
                // "?" uses the session's own workdir-hash colour for per-tab
                // stability. Otherwise the pre-existing 5dp unread dot (also
                // workdir-hash coloured) is shown; nothing renders when
                // neither applies.
                val hasQuestion = shouldShowQuestionMarker(
                    isSelected = isSelected,
                    questionSessionIds = questionSessionIds,
                    sessionId = session.id,
                )
                when {
                    hasQuestion -> {
                        Text(
                            text = "?",
                            color = workdirTone(session.directory),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    session.id in unreadSessions -> {
                        val dotColor = workdirTone(session.directory)
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(5.dp)
                                .background(color = dotColor, shape = CircleShape)
                        )
                    }
                }
                // §chat-session-tab-selected: the title + vertical accent bar
                // form a single centred group. The bar sits immediately left
                // of the text (4dp gap) so it reads as part of the label, not
                // as a detached strip at the tab edge. The group is centred
                // within the remaining middle space, while the unread dot and
                // close-X stay pinned to the outer edges.
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Vertical accent bar that replaces the removed underline +
                    // translucent fill. Always rendered as a constant-width
                    // placeholder so the group's total width never changes
                    // when isSelected flips — the title keeps a stable
                    // position, no horizontal jitter. Visually "shown when
                    // selected, transparent when not", keeping the crisp
                    // active signal.
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (isSelected) accentColor else Color.Transparent)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        // The tab label bypasses the shared 15-char cap used
                        // by ChatTopBar's breadcrumb: the tab's width is the
                        // limiting factor, so we let the text fill that width
                        // and rely on maxLines=1 + TextOverflow.Ellipsis for
                        // genuine overflow truncation. This gives wide tabs
                        // a longer title and keeps the close-X pinned to the
                        // right edge.
                        text = session.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelected) FontWeight.Bold
                        else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(2.dp))
                // item 2: close affordance renders ONLY on the selected tab.
                // Unselected tabs omit it entirely (no reserved placeholder),
                // so the weighted title Row reclaims the 24dp and shows a
                // longer name. The selected tab is already reinforced by the
                // vertical bar + Bold + workdir background, so giving up that
                // title space while selected is an acceptable trade-off.
                // §fix-3: 24dp touch target is a deliberate compromise —
                // noticeably narrower than the prior 28dp (so each tab keeps
                // room for the title) while staying within the small-target
                // range reviewers accept for a secondary, non-destructive
                // affordance.
                if (isSelected) {
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
        }
    )
}
