package cn.vectory.ocdroid.ui.chat

import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.ui.SessionViewModel
import kotlinx.coroutines.launch

/**
 * §L5a (UI god-file split): the [ModalNavigationDrawer] wrapper + its owned
 * drawer-local state, extracted verbatim from ChatScaffold (no behaviour
 * change). Owns:
 *
 *  - `scope` (`rememberCoroutineScope`) — used only inside the drawer's
 *    new-session close animations (NOT shared with the rest of ChatScaffold;
 *    ChatScaffold's own `scope` remains for imagePicker / drawer-toggle).
 *  - `drawerInteractionLocked` — briefly locks all drawer interaction (rows,
 *    back, new-session) while the drawer is closing before the workdir picker
 *    shows. Moved INSIDE this composable because EVERY consumer of the flag
 *    lives in the drawer tree: the two write sites in
 *    `onStartNewSessionInDrawer` (set true before `drawerState.close()`,
 *    cleared in `finally`) and the one read site in `RecentSessionsDrawer`'s
 *    `interactionsEnabled = !drawerInteractionLocked`. `remember` (not
 *    Saveable) so a config change resets it.
 *  - `onStartNewSessionInDrawer` — moved verbatim; the `pendingWorkdirPick
 *    = true` write (≥2 workdirs branch) is the ONLY external side effect, and
 *    it is now routed through [onShowWorkdirPicker] because
 *    `pendingWorkdirPick` is owned by ChatScaffold (the
 *    `ChatOverlayHost.pendingWorkdirPick` slot reads it directly there).
 *
 * Parameters owned by ChatScaffold (NOT moved here):
 *  - `drawerState` — owned by ChatScaffold (the phone/`openDrawerAction`
 *    toggle + the drawer-close `BackHandler` compose in ChatScaffold AFTER
 *    the parent/root handlers — keeping the DrawerState there lets that
 *    ordering invariant stay local to ChatScaffold).
 *  - `closeDrawerAction` — owned by ChatScaffold (same BackHandler ordering
 *    reason; the row-select + drawer-header back paths delegate to it).
 *  - `recentWorkdirs` — owned by ChatScaffold (`settingsVM.recentWorkdirs`
 *    subscription); passed as a value.
 *  - `sessions` — the `recentSessionsForDrawer` derivation (sessions +
 *    directorySessions merged, distinctBy id, filtered parentId==null &&
 *    !isArchived, sorted by time.updated desc). Derivation STAYS in
 *    ChatScaffold (it reads `sessionList.sessions` /
 *    `sessionList.directorySessions`, which ChatScaffold already
 *    subscribes to); passed as a value here.
 *  - `sessionErrorsById` — sourced straight from
 *    `SessionListState.sessionErrorsById` by ChatScaffold; passed as a value.
 *
 * @param content the chat body (TopAppBar + SessionTabStrip + chat Surface
 *   + Composer). Composed verbatim inside the ModalNavigationDrawer's
 *   content slot — every local ChatScaffold read inside it is still in scope.
 */
@Composable
internal fun ChatDrawerHost(
    drawerState: DrawerState,
    sessions: List<Session>,
    recentWorkdirs: List<String>,
    sessionErrorsById: Map<String, SlimSessionLastError>,
    sessionVM: SessionViewModel,
    closeDrawerAction: () -> Unit,
    onBackToHome: () -> Unit,
    onShowWorkdirPicker: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // §drawer-new-session: briefly locks all drawer interaction (rows, back,
    // new-session) while the drawer is closing before the workdir picker shows —
    // prevents a selectSession-vs-picker race if the user taps again during the
    // close animation. `remember` (not Saveable) so a config change resets it.
    var drawerInteractionLocked by remember { mutableStateOf(false) }

    // §drawer-new-session: reuses SessionsScreen's new-session flow. 0 workdirs
    // → button explicitly disabled via isStartNewSessionEnabled (no-op guard
    // kept defensively); 1 → lock + close drawer, then create the draft; ≥2 →
    // lock + close drawer, THEN show the project-picker AppBottomSheet
    // (awaited so the ModalBottomSheet does not overlap the closing
    // ModalNavigationDrawer). BOTH close paths lock all drawer interaction
    // during the close animation (prevents a selectSession-vs-picker / -create
    // race); the lock releases in a finally so it survives a cancelled close.
    // No onSwitchToChat() — already in Chat; createSessionInWorkdir clears chat
    // + composer into draft mode for the workdir.
    val onStartNewSessionInDrawer: () -> Unit = remember(
        recentWorkdirs,
        sessionVM,
        scope,
        drawerState,
        closeDrawerAction,
    ) {
        {
            when {
                recentWorkdirs.isEmpty() -> Unit
                recentWorkdirs.size == 1 -> {
                    // Capture the single workdir synchronously at tap time.
                    // recentWorkdirs is a state delegate re-read on each access,
                    // so evaluating .single() AFTER the ~300ms drawer-close
                    // animation could see a list that changed mid-flight (a
                    // workdir disconnected meanwhile, or the list became empty /
                    // multi → NoSuchElementException). The captured value is the
                    // one the user actually acted on; createSessionInWorkdir is
                    // idempotent-tolerant of a since-disconnected path (no crash).
                    val workdir = recentWorkdirs.single()
                    drawerInteractionLocked = true
                    scope.launch {
                        try {
                            drawerState.close()
                            sessionVM.createSessionInWorkdir(workdir)
                        } finally {
                            drawerInteractionLocked = false
                        }
                    }
                }
                else -> {
                    // Lock drawer interaction immediately so the user cannot tap a
                    // recent-session row / back button during the close animation
                    // (prevents a selectSession-vs-picker race). The picker is shown
                    // only after the drawer finishes closing (no modal-over-modal).
                    // finally guarantees the lock releases even if close() is
                    // cancelled mid-animation; on cancel the picker (inside try)
                    // stays hidden.
                    drawerInteractionLocked = true
                    scope.launch {
                        try {
                            drawerState.close()
                            onShowWorkdirPicker()
                        } finally {
                            drawerInteractionLocked = false
                        }
                    }
                }
            }
        }
    }

    // §home-hub T4 (C2): wrap the chat body in ModalNavigationDrawer. The
    // drawer is tablet-only by construction: the hamburger (Menu) button that
    // opens it renders ONLY on `isWide` form factors (ChatTopBar
    // navigationIcon branch). Phone has no Menu and no open gesture (see
    // gesturesEnabled below), so the drawer stays unreachable there (phone
    // uses ArrowBack → onBackToHome). Always wrapping (rather than
    // conditionally composing the Column twice) keeps the body a single tree —
    // the drawer content is cheap (a LazyColumn of recent root sessions) and
    // stays invisible/closed on phone.
    ModalNavigationDrawer(
        drawerState = drawerState,
        // §fix-drawer-scrim-dismiss + §fix-edge-swipe-conflict:
        // M3 ModalNavigationDrawer (material3 1.4.0 / composeBom 2025.12.00)
        // wires Scrim tap-to-dismiss as:
        //   onClose = { if (gesturesEnabled && confirmStateChange(Closed)) close() }
        // and Scrim always installs pointerInput+detectTapGestures when open
        // (NavigationDrawer.kt Scrim), so a content-level tap overlay CANNOT
        // receive scrim taps (Scrim is drawn ABOVE content). Constant
        // gesturesEnabled=false therefore both killed edge-open AND made
        // blank-area taps no-ops. Gate gestures on isOpen instead:
        //   - closed → false: no edge-swipe-to-open (HorizontalPager safe);
        //     drawer opens ONLY via hamburger (Menu).
        //   - open → true: Scrim tap + a11y dismiss + drag-to-close work.
        // BackHandler / row-select / new-session close paths are unchanged.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            RecentSessionsDrawer(
                // §opuser IMPORTANT-2: recent root non-archived sessions
                // (sessions + directorySessions merged, distinctBy id,
                // sorted by time.updated desc) — same projection as the home
                // page §2a Recently section. Tap = selectSession (stay in Chat).
                sessions = sessions,
                onSelect = { sessionId ->
                    sessionVM.selectSession(sessionId)
                    // §T4-C2: close the drawer after selecting so the user
                    // lands on the chosen conversation (stay in Chat, do NOT
                    // navigate away). selectSession is synchronous on the
                    // slice; the close animation runs concurrently.
                    closeDrawerAction()
                },
                onBackToHome = {
                    closeDrawerAction()
                    onBackToHome()
                },
                onStartNewSession = onStartNewSessionInDrawer,
                isStartNewSessionEnabled = recentWorkdirs.isNotEmpty(),
                interactionsEnabled = !drawerInteractionLocked,
                // §T17 slimapi v1 §6.1: pass the canonical per-session error
                // store straight through to the drawer rows. The row's
                // shouldShowSessionErrorIndicator helper looks up by session
                // id; absence (sid not in the map / recovered) → no indicator.
                sessionErrorsById = sessionErrorsById,
            )
        },
        content = content,
    )
}
