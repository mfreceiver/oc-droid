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
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.Dimens

/**
 * §home-hub T4: tablet-only `ModalNavigationDrawer` content for the Chat
 * surface. Rendered inside a [ModalDrawerSheet] and surfaced by the hamburger
 * (Menu) button in [ChatTopBar]'s `navigationIcon` slot (tablet form factor,
 * ≥600dp width). Provides:
 *
 *  - a **header row** with a Home affordance (`IconButton(ArrowBack)` →
 *    [onBackToHome]) — the tablet analogue of the phone top-left ArrowBack —
 *    so the user can leave Chat for the Home hub from inside the drawer;
 *  - a `LazyColumn` of recent sessions rendered via M3 [ListItem] +
 *    [Dimens] tokens (per `docs/ui-style-spec.md` §2 — rows MUST use the
 *    shared `ListItem` primitive, spacing MUST use `Dimens`, no scattered
 *    `dp` literals).
 *
 * Row tap = [onSelect] (selects the session WITHOUT leaving Chat — mirrors
 * `SessionVM.selectSession`); the drawer is closed by the caller
 * ([ChatScaffold]) on selection so the user lands on the chosen conversation.
 *
 * The session list passed by [ChatScaffold] is already pre-filtered to
 * **root + non-archived** sessions (built from `topBarState.openSessions`
 * → `openSessionIds` resolved through the union store, filtered
 * `parentId == null && !isArchived`), matching the homepage §2a "recent
 * sessions" projection.
 *
 * @param sessions recent root non-archived sessions (pre-projected by caller).
 * @param onSelect row-tap callback; receives the tapped session id. The
 *   caller is responsible for closing the drawer after a selection.
 * @param onBackToHome Home-affordance callback (header IconButton) — pop to
 *   the Home hub. Defaults to `{}`; ChatScaffold forwards the same
 *   `onBackToHome` the phone ArrowBack uses.
 * @param modifier applied to the outer [ModalDrawerSheet].
 */
@Composable
internal fun RecentSessionsDrawer(
    sessions: List<Session>,
    onSelect: (String) -> Unit,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(modifier = modifier) {
        // ── Header: Home affordance ──────────────────────────────────────
        // §home-hub T4: the drawer's own "back to home" entry. Uses ArrowBack
        // (AutoMirrored for RTL) to mirror the phone top-left affordance's
        // semantics — "leave chat" — consistent with the phone ArrowBack's
        // contentDescription (chat_back_to_home). Per ui-style-spec.md §2 the
        // row primitive is M3 `ListItem` (headline = app-name titleMedium,
        // trailing = the Home/back IconButton); IconButton content uses
        // Dimens.iconStd (24dp). This satisfies MINOR-1 (no hand-rolled Row
        // for the header).
        ListItem(
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
                IconButton(onClick = onBackToHome) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.chat_back_to_home),
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
 */
@Composable
private fun RecentSessionRow(
    session: Session,
    onClick: () -> Unit,
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
        modifier = Modifier.clickable(onClick = onClick),
    )
}
