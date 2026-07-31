package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.ui.SessionAttentionLevel
import cn.vectory.ocdroid.ui.computeSessionAttention
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.ui.theme.SessionAttentionBadge

/**
 * §P2-item2: persistent left session sidebar and drawer content pane. Extracted
 * from [RecentSessionsDrawer]'s body (no [ModalDrawerSheet] wrapper) so it can
 * be reused as a persistent sidebar on tablet landscape. The caller supplies
 * the wrapper ([ModalDrawerSheet] for the drawer, [Row] + [VerticalDivider]
 * for the tablet sidebar).
 *
 * Parameters mirror [RecentSessionsDrawer] (minus `interactionsEnabled`, which
 * the drawer handles by guarding callbacks).
 *
 * @param selectedSessionId when non-null, the row matching this id renders
 *   with a selection highlight ([MaterialTheme.colorScheme.secondaryContainer]).
 */
@Composable
internal fun RecentSessionsPane(
    sessions: List<Session>,
    onSelect: (String) -> Unit,
    onBackToHome: () -> Unit,
    onStartNewSession: () -> Unit = {},
    onRefreshSessions: () -> Unit = {},
    isStartNewSessionEnabled: Boolean = true,
    sessionErrorsByID: Map<String, SlimSessionLastError> = emptyMap(),
    selectedSessionId: String? = null,
    // §ui-badges: attention-level inputs for per-row session attention badge.
    unreadSessions: Set<String> = emptySet(),
    pendingInputSessionIds: Set<String> = emptySet(),
    sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // ── Header: Home affordance (leading) + new session (trailing) ─────
        ListItem(
            modifier = Modifier.height(Dimens.topBarHeight),
            leadingContent = {
                IconButton(onClick = onBackToHome) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.chat_back_to_home),
                        modifier = Modifier.size(Dimens.iconStd),
                    )
                }
            },
            headlineContent = {
                Text(
                    text = stringResource(R.string.recent_sessions_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefreshSessions) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.common_refresh),
                            modifier = Modifier.size(Dimens.iconStd),
                        )
                    }
                    IconButton(
                        onClick = onStartNewSession,
                        enabled = isStartNewSessionEnabled,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.sessions_new_session_fab),
                            modifier = Modifier.size(Dimens.iconStd),
                        )
                    }
                }
            },
        )

        // ── Recent sessions list ────────────────────────────────────────
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                items = sessions,
                key = { session -> session.id },
            ) { session ->
                val rowAttention = computeSessionAttention(
                    hasError = session.id in sessionErrorsByID,
                    hasPendingUserInput = session.id in pendingInputSessionIds,
                    isRetry = sessionStatuses[session.id]?.isRetry == true,
                    isUnread = session.id in unreadSessions,
                )
                RecentSessionRow(
                    session = session,
                    onClick = { onSelect(session.id) },
                    enabled = true,
                    attention = rowAttention,
                    selected = (session.id == selectedSessionId),
                )
            }
            item {
                Box(modifier = Modifier.padding(Dimens.spacing2))
            }
        }
    }
}

/**
 * §home-hub T4: tablet-only `ModalNavigationDrawer` content for the Chat
 * surface. Rendered inside a [ModalDrawerSheet] and surfaced by the hamburger
 * (Menu) button in [ChatTopBar]'s `navigationIcon` slot (tablet form factor,
 * ≥600dp width). Provides:
 *
 *  - a **header row** whose `leadingContent` is a Home affordance
 *    (`IconButton(ArrowBack)` → [onBackToHome] — the tablet analogue of the
 *    phone top-left ArrowBack so the user can leave Chat for the Home hub
 *    from inside the drawer) and whose `trailingContent` is a new-session
 *    affordance (`IconButton(Add)` → [onStartNewSession] — mirrors the
 *    SessionsScreen new-session FAB / header action);
 *  - a `LazyColumn` of recent sessions rendered via M3 [ListItem] +
 *    [Dimens] tokens (per `docs/specs/ui-style-spec.md` §2 — rows MUST use the
 *    shared `ListItem` primitive, spacing MUST use `Dimens`, no scattered
 *    `dp` literals).
 *
 * Row tap = [onSelect] (selects the session WITHOUT leaving Chat — mirrors
 * `SessionVM.selectSession`); the drawer is closed by the caller
 * ([ChatScaffold]) on selection so the user lands on the chosen conversation.
 *
 * The session list passed by [ChatScaffold] is the `recentSessionsForDrawer`
 * projection: `sessionList.sessions` ∪ `sessionList.directorySessions`
 * (flattened), `distinctBy { id }`, filtered to `parentId == null &&
 * !isArchived`, sorted by `time.updated` desc, with NO cap. This is the
 * SAME merged projection as the homepage §2a "Recently" section (sessions
 * + directorySessions, distinctBy id), NOT the root-sessions-only set the
 * SessionPickerSheet consumes.
 *
 * @param sessions recent root non-archived sessions (pre-projected by caller).
 * @param onSelect row-tap callback; receives the tapped session id. The
 *   caller is responsible for closing the drawer after a selection.
 * @param onBackToHome Home-affordance callback (header `leadingContent`
 *   IconButton) — pop to the Home hub. ChatScaffold forwards the same
 *   `onBackToHome` the phone ArrowBack uses.
 * @param onStartNewSession new-session callback (header `trailingContent`
 *   IconButton) — mirrors the SessionsScreen new-session flow. Defaults to
 *   `{}`.
 * @param isStartNewSessionEnabled gates the trailing Add button (disabled
 *   when no workdirs are connected, e.g. 0 `recentWorkdirs`).
 * @param interactionsEnabled gates ALL drawer interaction (header back +
 *   Add buttons and recent-session rows). The caller briefly clears this
 *   during the drawer-close transition before the workdir picker shows, to
 *   prevent a selectSession-vs-picker race. Defaults to `true`.
 * @param sessionErrorsById T17 slimapi v1 §6.1: the canonical per-session
 *   upstream-error store (sourced from `SessionListState.sessionErrorsById`
 *   by the caller). Drives the [SessionAttentionLevel.HardError] tier in the
 *   unified [SessionAttentionBadge]. Empty map / sid absent → no HardError.
 *   Defaults to an empty map so callers not surfacing T17 errors (or
 *   pre-T17 test fixtures) render unchanged.
 * @param unreadSessions §ui-badges: set of session ids with unread content.
 *   Defaults to empty set.
 * @param pendingInputSessionIds §ui-badges: set of session ids with pending
 *   user input (question or permission). Defaults to empty set.
 * @param sessionStatuses §ui-badges: current session statuses, used to derive
 *   the TransientRetry tier. Defaults to empty map.
 * @param modifier applied to the outer [ModalDrawerSheet].
 */
@Composable
internal fun RecentSessionsDrawer(
    sessions: List<Session>,
    onSelect: (String) -> Unit,
    onBackToHome: () -> Unit,
    onStartNewSession: () -> Unit = {},
    onRefreshSessions: () -> Unit = {},
    isStartNewSessionEnabled: Boolean = true,
    interactionsEnabled: Boolean = true,
    sessionErrorsById: Map<String, SlimSessionLastError> = emptyMap(),
    // §ui-badges: attention-level inputs for per-row session attention badge.
    unreadSessions: Set<String> = emptySet(),
    pendingInputSessionIds: Set<String> = emptySet(),
    sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(modifier = modifier) {
        RecentSessionsPane(
            sessions = sessions,
            onSelect = { sessionId -> if (interactionsEnabled) onSelect(sessionId) },
            onBackToHome = { if (interactionsEnabled) onBackToHome() },
            onStartNewSession = onStartNewSession,
            onRefreshSessions = { if (interactionsEnabled) onRefreshSessions() },
            isStartNewSessionEnabled = isStartNewSessionEnabled && interactionsEnabled,
            sessionErrorsByID = sessionErrorsById,
            selectedSessionId = null,
            unreadSessions = unreadSessions,
            pendingInputSessionIds = pendingInputSessionIds,
            sessionStatuses = sessionStatuses,
            modifier = Modifier,
        )
    }
}

/**
 * §home-hub T4: a single recent-session row inside [RecentSessionsDrawer].
 * Rendered via the M3 [ListItem] primitive (ui-style-spec.md §2 — rows MUST
 * use `ListItem`, not hand-rolled `Row` + padding). Leading workdir-tone dot
 * reuses the [workdirTone] helper already shared with [SessionPickerSheet]
 * (same package) so the colour anchor ties the row to its project.
 *
 * §ui-badges: trailing slot renders a unified [SessionAttentionBadge] resolved
 * by [computeSessionAttention] from per-session flags (error / pending input /
 * retry / unread), replacing the previous standalone error indicator.
 */
@Composable
internal fun RecentSessionRow(
    session: Session,
    onClick: () -> Unit,
    enabled: Boolean = true,
    attention: SessionAttentionLevel = SessionAttentionLevel.None,
    selected: Boolean = false,
) {
    val tone = remember(session.directory) { workdirTone(session.directory) }
    ListItem(
        headlineContent = {
            Text(
                text = session.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val workdirBase = session.directory
                .split("/")
                .filter { it.isNotEmpty() }
                .lastOrNull()
                .orEmpty()
            if (workdirBase.isNotEmpty()) {
                Text(
                    text = workdirBase,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            // §WT1 parity (SessionPickerRow): workdir-tone dot is the visual
            // anchor tying the row to its project colour.
            Box(
                modifier = Modifier
                    .size(Dimens.iconXs)
                    .clip(CircleShape)
                    .background(tone)
            )
        },
        trailingContent = {
            // §ui-badges: unified attention badge replaces the error indicator.
            // Priority: HardError > PendingUserInput > TransientRetry >
            // Unread > None.
            SessionAttentionBadge(level = attention)
        },
        // §P2-item2 (rev-gpt gate fix): drive the selection highlight via ListItem's
        // `colors` containerColor. An external Modifier.background() is painted over by
        // ListItem's own opaque container layer, hiding the highlight. Using the colors
        // param is the M3-contract way to set the row container color, so the
        // secondaryContainer highlight renders deterministically (no emulator needed to
        // trust the API). Text colors stay default (onSurface contrasts fine on the
        // muted secondaryContainer in both light/dark themes).
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

// §ui-badges: shouldShowSessionErrorIndicator removed — replaced by
// computeSessionAttention (pure, in SessionAttention.kt) + SessionAttentionBadge.
