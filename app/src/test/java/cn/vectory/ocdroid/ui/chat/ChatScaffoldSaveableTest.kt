package cn.vectory.ocdroid.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runAndroidComposeUiTest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import cn.vectory.ocdroid.MainViewModelTestBase
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.BannerHysteresisOwner
import cn.vectory.ocdroid.ui.BannerHysteresisState
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SettingsViewModel

/**
 * §oracle §1 / compose-p0 P0-1: saveable slot-positionality regression test for
 * the 4 inline `rememberSaveable` flags inside [ChatScaffold], plus the
 * `rememberSaveableStateHolder` at line ~1200.
 *
 * ## Risk context
 * [ChatScaffold] is the single-point state hub (1365 LOC). Four flags use
 * `rememberSaveable` — their slot positionality is NOT covered by any existing
 * test. A refactor that accidentally moves one behind a conditional (or shifts
 * its call position) would silently break the [SaveableStateHolder]'s slot
 * mapping — the saved state would no longer be restored, and pickers would
 * reset to their closed state on every config change / process death.
 *
 * Saveable flags under test (ChatScaffold.kt):
 *   - `showAgentPicker`    (line ~333) — `rememberSaveable { mutableStateOf(false) }`
 *   - `showModelPicker`    (line ~334) — `rememberSaveable { mutableStateOf(false) }`
 *   - `showSessionPicker`  (line ~335) — `rememberSaveable { mutableStateOf(false) }`
 *   - `pendingWorkdirPick` (line ~351) — `rememberSaveable { mutableStateOf(false) }`
 *
 * Negative controls (plain `remember`, must reset):
 *   - `errorDetail`        (line ~336)
 *   - `showTodoDialog`     (line ~342)
 *   - `showContextDialog`  (line ~343)
 *
 * ## Approach A (UI-gesture-driven) — showSessionPicker
 * **Confirmed UI path** (ChatScaffold.kt:917): title tap → `showSessionPicker = true`
 * → ChatOverlayHost renders `SessionPickerSheet` (ChatOverlayHost.kt:142-156).
 * SessionPickerSheet uses `AppBottomSheet` with title `R.string.recent_sessions_title`
 * ("Recent sessions"). This is the most reliable path because:
 * - The title Text is rendered with `Modifier.clickable(onClick = onTitleClick)`
 *   (ChatTopBar.kt:555) when `currentSession != null`.
 * - SessionPickerSheet renders as a direct conditional (`if (showSessionPicker)`),
 *   not inside a DropdownMenu (no separate window/popup).
 *
 * **What IS covered**
 * 1. **Smoke** — all-7-VM wiring + Robolectric layout path (catches
 *    `@Composable` signature regressions, missing `@OptIn` annotations,
 *    CompositionLocal absence, and Robolectric environment incompatibilities).
 * 2. **showSessionPicker survives save+restore** — seeds a valid session in the
 *    store, composes ChatScaffold, taps the title to set `showSessionPicker=true`
 *    (verified by SessionPickerSheet title appearing), then drives
 *    `emulateSaveAndRestore` and confirms the sheet is still rendered.
 *    This pins the `rememberSaveable` slot position for `showSessionPicker`.
 *
 * **Gap (not covered) — 3 remaining flags**
 * - `showAgentPicker` / `showModelPicker` (overflow DropdownMenu — needs
 *   `currentSession != null` + overflow-icon tap + menu-item tap; the overflow
 *   icon is an untagged Surface composing `ContextUsageRing`, making the
 *   gesture chain brittle without production testTags).
 * - `pendingWorkdirPick` (drawer ≥2-workdirs path — needs multiple connected
 *   workdirs set up in the store, a separate test surface).
 *
 * **Negative control caveat** (rev-ds §correctness YELLOW): this test does
 * NOT include an inline negative control proving the emulateSaveAndRestore
 * cycle actually applied restored state. The count-based gate
 * (`assertCountEquals(1)` before tap) only rules out a sticky-default false
 * positive; it does NOT rule out a vacuous cycle — if the cycle were a
 * no-op, `assertCountEquals(2)` would still pass because showSessionPicker
 * was never reset. The cycle's correctness is trusted from the framework
 * API plus the [ScrollManagerSaveableTest] template, whose plain-remember
 * negative control (lines 72-75 / 115-121) PROVES the environment runs a
 * real save+restore. Hardening this test to carry its own inline negative
 * control would require driving a plain-remember flag (e.g. showTodoDialog
 * via the overflow menu), which is currently untagged-brittle (same gap as
 * the 3 remaining flags below).
 *
 * Closing the remaining 3 flags requires either production testTags or
 * instrumentation tests (androidTest, not check.sh).
 */

// (No test-only composables needed — Approach A uses UI gestures
// on the real ChatScaffold composable.)

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatScaffoldSaveableTest : MainViewModelTestBase() {

    @Before
    override fun setUp() {
        super.setUp()
        newCore()
    }

    /** Constructs a stub [ChatViewModel] with a relaxed [BannerHysteresisOwner]. */
    private fun stubChatVM(): ChatViewModel {
        val owner = mockk<BannerHysteresisOwner>(relaxed = true) {
            every { state } returns MutableStateFlow(BannerHysteresisState())
        }
        return ChatViewModel(core, owner)
    }

    // ── Test 1: Smoke ─────────────────────────────────────────────────────────

    /**
     * Smoke: [ChatScaffold] composes without crashing under stubbed VMs.
     *
     * Regressions this catches:
     * - A new required constructor parameter added to any of the 7 VMs without
     *   updating the test-only convenience constructor (catches `@Inject`
     *   signature drift).
     * - A missing `@OptIn(ExperimentalMaterial3Api::class)` on a composable
     *   in the tree (the `@OptIn` on ChatScaffold itself handles this, but a
     *   downstream composable that newly requires it would surface here).
     * - A Robolectric-incompatible platform call introduced in the composition
     *   path (e.g. `PackageManager`, `Context` method that Robolectric does
     *   not stub).
     * - A `CompositionLocal` absence (e.g. `LocalWindowSizeClass` — handled
     *   by null-safe fallbacks, but a new required local would crash here).
     *
     * Not a saveable-positionality test — purely the wiring barrier.
     */
    @Test
    fun `ChatScaffold composes without crashing under stub VMs`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val chatVM = stubChatVM()
            val composerVM = ComposerViewModel(core)
            val sessionVM = SessionViewModel(core)
            val orchestratorVM = OrchestratorViewModel(core)
            val connectionVM = ConnectionViewModel(core)
            val hostVM = HostViewModel(core)
            val settingsVM = SettingsViewModel(core)

            setContent {
                ChatScaffold(
                    chatVM = chatVM,
                    composerVM = composerVM,
                    connectionVM = connectionVM,
                    sessionVM = sessionVM,
                    hostVM = hostVM,
                    orchestratorVM = orchestratorVM,
                    settingsVM = settingsVM,
                    routeSessionId = null,
                )
            }
            waitForIdle()
        }

    // ── Test 2: showSessionPicker via title tap (Approach A) ───────────────────

    /**
     * Approach A: seeds a valid session in the store, composes [ChatScaffold],
     * taps the session title (ChatTopBar.kt:555 — Text with
     * `Modifier.clickable(onClick = onTitleClick)` → `showSessionPicker = true`),
     * confirms the SessionPickerSheet renders (title text "Recent sessions"),
     * then drives a save+restore cycle and confirms the sheet persists.
     *
     * This pins the `rememberSaveable` slot position for `showSessionPicker`
     * (ChatScaffold.kt:335). A refactor that wraps it in a conditional or
     * shifts its call order would cause it to reset to `false` after restore,
     * making the sheet disappear — caught by this test.
     *
     * ## Pre-condition
     * - [core.writeChat] sets `currentSessionId = "test-session"` so
     *   `chromeSessionId` resolves to a non-null value (ChatScaffold.kt:257).
     * - [core.writeSessionList] adds a matching `Session` so the top bar
     *   renders a clickable title with `displayName = "Test Session"`.
     *
     * ## Pre-restore gate
     * `onNodeWithText("Recent sessions").assertDoesNotExist()` before the tap
     * ensures the sheet is NOT initially visible — proving the post-restore
     * assertion is NOT a false positive from a sticky default.
     */
    @Test
    fun `showSessionPicker survives save and restore via title tap`() =
        runAndroidComposeUiTest<ComponentActivity> {
            // Seed store state: a valid currentSessionId + matching session.
            core.writeChat { it.copy(currentSessionId = "test-session") }
            core.writeSessionList {
                it.copy(sessions = listOf(Session(id = "test-session", directory = "/tmp/test", title = "Test Session")))
            }

            val restorationTester = StateRestorationTester(this)

            val chatVM = stubChatVM()
            val composerVM = ComposerViewModel(core)
            val sessionVM = SessionViewModel(core)
            val orchestratorVM = OrchestratorViewModel(core)
            val connectionVM = ConnectionViewModel(core)
            val hostVM = HostViewModel(core)
            val settingsVM = SettingsViewModel(core)

            restorationTester.setContent {
                ChatScaffold(
                    chatVM = chatVM,
                    composerVM = composerVM,
                    connectionVM = connectionVM,
                    sessionVM = sessionVM,
                    hostVM = hostVM,
                    orchestratorVM = orchestratorVM,
                    settingsVM = settingsVM,
                    routeSessionId = null,
                )
            }
            waitForIdle()

            // Gate: SessionPickerSheet MUST NOT exist before the tap.
            // The RecentSessionsDrawer header ALSO renders "Recent sessions"
            // (it is composed off-screen at l=-320 even when closed), so the
            // pre-tap count MUST be exactly 1 (drawer header only). When the
            // sheet opens, SessionPickerSheet's AppBottomSheet title adds a
            // 2nd "Recent sessions" node → count becomes 2. This count-based
            // gate is robust to the drawer-header string collision.
            onAllNodesWithText("Recent sessions").assertCountEquals(1)

            // Tap the session title → sets showSessionPicker = true.
            // `.onFirst()` disambiguates the ChatTopBar title (body content,
            // composed first in tree order) from the drawer's session row
            // (drawerContent, composed later) when both render "Test Session".
            onAllNodesWithText("Test Session").onFirst().performClick()
            waitForIdle()

            // Confirm SessionPickerSheet rendered (drawer header + sheet title = 2).
            onAllNodesWithText("Recent sessions").assertCountEquals(2)

            // Save → dispose → recreate-with-restored-state.
            restorationTester.emulateSaveAndRestore()
            waitForIdle()

            // ShowSessionPicker is rememberSaveable → MUST survive. If the flag
            // moved behind a conditional or its call position shifted, the slot
            // mapping breaks and showSessionPicker resets to false → count drops
            // back to 1 (drawer header only).
            onAllNodesWithText("Recent sessions").assertCountEquals(2)
        }
}
