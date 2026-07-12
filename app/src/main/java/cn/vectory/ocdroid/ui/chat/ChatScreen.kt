package cn.vectory.ocdroid.ui.chat

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel

/**
 * §B3: provides a [WindowSizeClass] computed once in [cn.vectory.ocdroid.MainActivity]
 * via the M3 entry point `calculateWindowSizeClass(activity)`. Screens read it
 * through this local instead of hand-rolling `LocalConfiguration.current.screenWidthDp >= N`
 * checks, which drift from the canonical M3 breakpoints (Compact <600,
 * Medium 600-839, Expanded ≥840). Nullable so previews / unit tests that run
 * without a provider fall back gracefully rather than crash.
 *
 * §1B: kept on the public surface because [ChatScaffold] reads it through
 * this local for the wide-screen card wrap (§B3 / `isWide` branch in
 * ChatScaffold). Removing the provider would break the wide-screen parity.
 */
val LocalWindowSizeClass = staticCompositionLocalOf<WindowSizeClass?> { null }

/**
 * §1B: Phase 1B thin shell. The previous monolithic ChatScreen
 * composable (TopAppBar + HorizontalPager + ChatInputBar + Toast/Question/
 * Permission overlays + status bar) was split in Phase 1B:
 *  - the chrome (TopAppBar, session-picker sheet, overflow menu) is now
 *    inside [ChatScaffold];
 *  - the composer (input row + Add menu + agent/model chips + file-reference
 *    chip strip) is now in [Composer];
 *  - the session picker is in [SessionPickerSheet];
 *  - the message list / streaming overlay / gap-paging / scroll anchoring
 *    / draft lifecycle / pending-question / pending-permission / TOFU
 *    surfaces are still inside [ChatScaffold] but their slice reads
 *    + derived values are unchanged.
 *
 * [ChatScreen] is preserved as a thin wrapper so the legacy AppShell
 * wiring (see AppShell.kt:111-122) continues to compile and route the
 * Chat destination to the new internal scaffold. The signature is
 * unchanged — same 6 VMs + 2 navigation callbacks. The migration to a
 * standalone "new shell" entry point (without the wrapper) lands in
 * Phase 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatVM: ChatViewModel,
    composerVM: ComposerViewModel,
    connectionVM: ConnectionViewModel,
    sessionVM: SessionViewModel,
    hostVM: HostViewModel,
    orchestratorVM: OrchestratorViewModel,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSessions: () -> Unit = {},
    /**
     * Opens the Chat-stack file preview. The workdir is the current
     * session's directory; the path is the specific file the user tapped in a
     * message (may be null when no specific file is targeted). ChatScaffold's
     * onChatFileClick passes the ACTUAL tapped path (not the session directory
     * — that was the §phase2 fix-6 regression that dropped the path).
     */
    onOpenChatFilePreview: (workdir: String?, path: String?) -> Unit = { _, _ -> },
    onOpenGitChanges: (String) -> Unit = {},
) {
    ChatScaffold(
        chatVM = chatVM,
        composerVM = composerVM,
        connectionVM = connectionVM,
        sessionVM = sessionVM,
        hostVM = hostVM,
        orchestratorVM = orchestratorVM,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToSessions = onNavigateToSessions,
        onOpenChatFilePreview = onOpenChatFilePreview,
        onOpenGitChanges = onOpenGitChanges,
    )
}

// §R-19 Sprint 2 #7(b): the four session-activity helpers + the
// CurrentSessionActivity data class were lifted verbatim into the top-level
// pure-functions file ChatActivityHelpers.kt (same package) so they can be
// covered by JVM unit tests (this file is excluded from kover coverage as a
// @Composable-heavy screen — see PickerProviderFilter.kt for the same
// extraction pattern).
