// SessionPickerHelpers.kt — pure JVM-testable helpers extracted from the
// now-removed `SessionTabStrip` / `ContextMenuButton` composables (Phase 1B
// removed the second-row session tab strip in favour of the new
// ModalBottomSheet `SessionPickerSheet`). The two selection / layout
// resolvers (resolveEffectiveSelectedId / resolveSessionTabLayout) and the
// shared title-truncation helper (truncateTitle) stay here so the existing
// JVM tests (EffectiveSelectedIdTest / SessionTabLayoutTest) keep finding
// the symbols; nothing in this file is a @Composable so it stays in the
// kover coverage set.

package cn.vectory.ocdroid.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.data.model.Session

private const val SESSION_TITLE_MAX_CHARS = 15

/**
 * Shared title-truncation helper used by the title-slot parent breadcrumb
 * in the new TopAppBar (and formerly by the session tab labels). Emits an
 * ellipsis when the title exceeds [SESSION_TITLE_MAX_CHARS].
 *
 * Kept as an `internal` top-level function (pure, no Compose dependency) so
 * the title-slot breadcrumb in [ChatTopBar] can reuse the same cap without
 * duplicating the constant. Was co-located with the (now-removed) session
 * tab strip; relocated here so the chat UI files that survive Phase 1B
 * remain free of the obsolete tab-strip surface.
 */
internal fun truncateTitle(value: String): String =
    if (value.length <= SESSION_TITLE_MAX_CHARS) value
    else value.take(SESSION_TITLE_MAX_CHARS - 1) + "…"

/**
 * Pure selection resolver for the (former) session tab strip. Returns the
 * session id that should read as "active" for highlight + scroll-centre:
 *  - [currentSessionId] when it is itself a root session present in
 *    [openSessions] (the normal case);
 *  - otherwise [parentSessionId] when that is a root session in [openSessions]
 *    (opening a sub-agent highlights its parent tab);
 *  - otherwise null (draft session with currentSessionId == null, or a
 *    multi-level orphan whose parent is itself a sub-agent not in openSessions).
 *
 * Extracted from the (now-removed) [SessionTabStrip] composable so the
 * precedence + null semantics are covered by JVM unit tests
 * (EffectiveSelectedIdTest) instead of only on-device verification. Pure: no
 * Compose dependency.
 *
 * §1B: kept on the public-internal surface even though the tab strip that
 * consumed it is gone — the function is still semantically meaningful for
 * any future list that needs the same precedence (e.g. the
 * SessionPickerSheet's "highlight the current session in Recent"). Tests
 * pin the contract; deleting the function would also delete the JVM unit
 * coverage on the contract.
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
 * Pure layout resolver for the (former) session tab strip's expand-then-
 * scroll behaviour. Returns the mode that should be used given
 * [contentWidth], [tabCount], and [minWidth].
 *
 * Extracted from the (now-removed) [SessionTabStrip] composable so the
 * mode decision is covered by JVM unit tests (SessionTabLayoutTest).
 * Pure: no Compose dependency on the @Composable surface.
 */
internal fun resolveSessionTabLayout(
    contentWidth: Dp,
    tabCount: Int,
    minWidth: Dp,
): SessionTabLayout = when {
    tabCount <= 0 -> SessionTabLayout.FitWeighted
    minWidth * tabCount > contentWidth -> SessionTabLayout.OverflowScroll
    else -> SessionTabLayout.FitWeighted
}

/**
 * Layout mode for the session tab strip (Phase 1B: kept for the same
 * contract-reason as [resolveSessionTabLayout] — the JVM test pins the
 * decision tree; the @Composable consumer is gone but the data class + sealed
 * type are part of the tested contract).
 */
sealed interface SessionTabLayout {
    /** Tabs cannot fit at their minimum width → horizontal scrolling. */
    data object OverflowScroll : SessionTabLayout

    /** Tabs fit and should expand evenly to fill the strip with [Modifier.weight]. */
    data object FitWeighted : SessionTabLayout
}
