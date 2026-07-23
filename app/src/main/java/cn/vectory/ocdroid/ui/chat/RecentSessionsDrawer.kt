package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.Dimens

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
 *   by the caller). Read by [shouldShowSessionErrorIndicator] to decide
 *   whether a row renders the small error indicator. Empty map / sid
 *   absent → no indicator (matches the StatusSlot LastError gate contract).
 *   Defaults to an empty map so callers not surfacing T17 errors (or
 *   pre-T17 test fixtures) render unchanged.
 * @param modifier applied to the outer [ModalDrawerSheet].
 */
@Composable
internal fun RecentSessionsDrawer(
    sessions: List<Session>,
    onSelect: (String) -> Unit,
    onBackToHome: () -> Unit,
    onStartNewSession: () -> Unit = {},
    isStartNewSessionEnabled: Boolean = true,
    interactionsEnabled: Boolean = true,
    sessionErrorsById: Map<String, SlimSessionLastError> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(modifier = modifier) {
        // ── Header: Home affordance (leading) + new session (trailing) ─────
        // §home-hub T4: the drawer's own "back to home" entry moved to
        // `leadingContent`. Uses ArrowBack (AutoMirrored for RTL) to mirror
        // the phone top-left affordance's semantics — "leave chat" —
        // consistent with the phone ArrowBack's contentDescription
        // (chat_back_to_home). §drawer-new-session: `trailingContent` now
        // hosts the new-session Add IconButton (mirrors SessionsScreen's
        // new-session flow). Per ui-style-spec.md §2 the row primitive is M3
        // `ListItem` (leading = Home/back IconButton, headline = app-name
        // titleMedium, trailing = new-session IconButton); IconButton content
        // uses Dimens.iconStd (24dp). This satisfies MINOR-1 (no hand-rolled
        // Row for the header).
        ListItem(
            leadingContent = {
                IconButton(
                    onClick = onBackToHome,
                    enabled = interactionsEnabled,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.chat_back_to_home),
                        modifier = Modifier.size(Dimens.iconStd),
                    )
                }
            },
            headlineContent = {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                IconButton(
                    onClick = onStartNewSession,
                    enabled = isStartNewSessionEnabled && interactionsEnabled,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.sessions_new_session_fab),
                        modifier = Modifier.size(Dimens.iconStd),
                    )
                }
            },
        )

        // ── Section header ──────────────────────────────────────────────
        // Reuses the shared AppSectionHeader primitive (ui-style-spec.md §2):
        // titleSmall + onSurfaceVariant + 16/8dp padding.
        AppSectionHeader(text = stringResource(R.string.home_section_recent))

        // ── Recent sessions list ────────────────────────────────────────
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                items = sessions,
                key = { session -> session.id },
            ) { session ->
                RecentSessionRow(
                    session = session,
                    onClick = { onSelect(session.id) },
                    enabled = interactionsEnabled,
                    // §T17: indicator fires iff this session has a SET
                    // lastError in the canonical T12 store (sid absent →
                    // no indicator). Pure check, hoisted out of the
                    // composable for testability.
                    showErrorIndicator = shouldShowSessionErrorIndicator(
                        sessionId = session.id,
                        sessionErrorsById = sessionErrorsById,
                    ),
                )
            }
            // Trailing breathing room so the last row is not flush against
            // the navigation-gesture inset.
            item {
                Box(modifier = Modifier.padding(Dimens.spacing2))
            }
        }
    }
}

/**
 * §home-hub T4: a single recent-session row inside [RecentSessionsDrawer].
 * Rendered via the M3 [ListItem] primitive (ui-style-spec.md §2 — rows MUST
 * use `ListItem`, not hand-rolled `Row` + padding). Leading workdir-tone dot
 * reuses the [workdirTone] helper already shared with [SessionPickerSheet]
 * (same package) so the colour anchor ties the row to its project.
 *
 * Deliberately leaner than `SessionPickerRow` (no question / retry / unread
 * badges, no selection check): the drawer's [RecentSessionsDrawer] signature
 * does not carry `currentSessionId` (T4 contract), so a selection glyph would
 * be misleading; cross-session state indicators already surface via the
 * restored SessionTabStrip + StatusSlot in the chat body. The row shows only
 * identity: display name (headline) + workdir basename (supporting).
 *
 * §T17 slimapi v1 §6.1: when [showErrorIndicator] is true (the session has a
 * SET lastError in `SessionListState.sessionErrorsById`), the row renders a
 * small `ErrorOutline` icon in `trailingContent` tinted to `colorScheme.error`.
 * The indicator is a non-blocking visual cue (the full banner renders in the
 * chat surface's StatusSlot when the user opens the session). Minimal
 * acceptable styling — a @designer pass may tune color/size; the structural
 * contract (indicator present iff [showErrorIndicator]) is the T17 gate.
 */
@Composable
private fun RecentSessionRow(
    session: Session,
    onClick: () -> Unit,
    enabled: Boolean = true,
    showErrorIndicator: Boolean = false,
) {
    val tone = remember(session.directory) { workdirTone(session.directory) }
    ListItem(
        headlineContent = {
            Text(
                text = session.displayName,
                style = MaterialTheme.typography.bodyLarge,
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
            // §T17: per-session upstream-error indicator. Renders iff
            // showErrorIndicator (caller-derived from sessionErrorsById).
            // Anchored in trailingContent to preserve the row's existing
            // identity layout (workdir dot + name + workdir basename) —
            // the cue is additive, no redesign of the row.
            if (showErrorIndicator) {
                val errorLabel = stringResource(R.string.chat_session_error_label)
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = errorLabel,
                    modifier = Modifier.size(Dimens.iconSm),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

/**
 * T17 slimapi v1 §6.1: pure resolver backing the per-row error indicator.
 * Returns true iff [sessionId] has a SET entry in the canonical T12 store
 * ([sessionErrorsById]). Absent sid OR empty map → false (no indicator) —
 * matches the StatusSlot LastError gate contract: the map holds SET errors
 * only (T12 REMOVES on recovery), absence = no banner / no indicator.
 *
 * Extracted from the composable so the null-safety contract is
 * JVM-unit-testable without a Compose harness — see
 * `RecentSessionErrorIndicatorTest`.
 */
internal fun shouldShowSessionErrorIndicator(
    sessionId: String,
    sessionErrorsById: Map<String, SlimSessionLastError>,
): Boolean = sessionErrorsById[sessionId] != null
