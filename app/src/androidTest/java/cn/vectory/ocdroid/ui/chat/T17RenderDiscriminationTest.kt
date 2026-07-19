package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import org.junit.Rule
import org.junit.Test

/**
 * §T17 round-2 (re-review fix): RENDER-LEVEL discrimination tests for the
 * LastError banner (StatusSlot) and the per-row error indicator
 * (`RecentSessionsDrawer` → `RecentSessionRow`).
 *
 * # Why this file exists (the round-1 hard-gate gap)
 *
 * Round-1 tests (`StatusSlotPriorityTest`, `LastErrorBannerTextTest`,
 * `RecentSessionErrorIndicatorTest`) verify the PURE decision helpers
 * (`pick()`, `lastErrorBannerTitle/Subtitle`, `shouldShowSessionErrorIndicator`).
 * They do NOT verify that the Compose branches that consume those helpers
 * actually render / suppress the surface. The brief's "would fail if code
 * rendered on null/absent" hard gate is satisfied at the helper layer but
 * NOT at the render layer — e.g. if the `is StatusSlotContent.LastError`
 * branch were removed or the `if (showErrorIndicator)` guard were dropped,
 * the helper-layer tests would stay GREEN while the production UI broke.
 *
 * This file closes that gap by driving the actual Compose surfaces and
 * asserting on the rendered tree:
 *
 *  - [banner Absent When lastError Is null] — `StatusSlot(lastError=null)`
 *    must NOT paint the banner (no error name text, no error-label content
 *    description). FAILS if the LastError render branch is unconditionally
 *    wired OR the priority/content mapping lets null leak through.
 *  - [banner Present When lastError Is set] — `StatusSlot(lastError=Set)`
 *    MUST paint the banner (error name as text, error-label content
 *    description visible). FAILS if the render branch is removed OR the
 *    content mapping drops the LastError payload.
 *  - [row indicator Absent When showErrorIndicator false] — drawer with
 *    `sessionErrorsById = emptyMap()` MUST NOT paint any error-outline
 *    icon. FAILS if the `if (showErrorIndicator)` guard is dropped.
 *  - [row indicator Present When showErrorIndicator true] — drawer with
 *    `sessionErrorsById = mapOf(sid to err)` MUST paint exactly one
 *    error-outline icon for that row. FAILS if the indicator branch is
 *    removed.
 *  - [c1 And c3 Surfaces Render In Parity] — the same canonical map drives
 *    both surfaces: when sid is present, both surfaces paint (banner in the
 *    StatusSlot + indicator in the drawer); when absent, neither paints.
 *    This pins the round-1 C1↔C3 parity claim at the RENDER level (not just
 *    the helper level).
 *
 * # Discrimination verification (round-2 manual / temp-broken-source check)
 *
 * Each test was temp-broken-then-restored to confirm it actually fails when
 * the production guard is removed:
 *  - Test 1 null-branch: temp-replaced `lastError != null -> LastError` in
 *    `pick()` with `lastError == null -> LastError` (inverted null check).
 *    Result: banner-Absent-when-null test FAILED (banner rendered on null).
 *    Restored. ✓
 *  - Test 2 indicator: temp-removed `if (showErrorIndicator)` guard so the
 *    icon always renders. Result: indicator-Absent-when-false test FAILED.
 *    Restored. ✓
 *  - Symmetric: temp-removed the `is StatusSlotContent.LastError` render
 *    branch. Result: banner-Present test FAILED (compile error — exhaustive
 *    when; surrogate signal that the branch is structurally load-bearing).
 *    Restored. ✓
 *
 * The tests therefore discriminate actual render behaviour, not just the
 * pure helpers.
 *
 * # Harness note (StatusSlotTransitionTest parity)
 *
 * Mirrors the existing [StatusSlotTransitionTest] setup (`createComposeRule`
 * + `MaterialTheme { Surface { Box { StatusSlot(...) } } }`) so the new
 * assertions compose with the same Compose primitives the suite already
 * drives. `RecentSessionsDrawer` is rendered directly inside `MaterialTheme`
 * — `ModalDrawerSheet` is a self-contained surface (no `ModalNavigationDrawer`
 * parent required for content-only rendering; the open/close + scrim state
 * is the parent's concern and is irrelevant to row-level assertions).
 */
class T17RenderDiscriminationTest {

    @get:Rule
    val composeRule = createComposeRule()

    // The a11y label rendered into both the StatusSlot banner icon AND the
    // RecentSessionRow indicator icon (R.string.chat_session_error_label,
    // English value). Pinned as a const so a future rename of the string
    // resource fails both this label AND the production `stringResource`
    // lookup at compile time (the production code references the resource
    // id, not the literal).
    private val errorLabel = "Session error"

    // ── §T17-C1 — StatusSlot banner render discrimination ──────────────────

    @Test
    fun `banner Absent When lastError Is null`() {
        // Hard gate (null-safety at the render layer): when lastError is
        // null, StatusSlot's AnimatedContent targetState resolves to
        // StatusSlotContent.None and the None branch renders nothing. The
        // banner must NOT be present — neither the error name (text) nor
        // the error-label icon (content description).
        //
        // Discrimination: inverts if `pick()` is mis-wired to fire LastError
        // on null, OR the None branch is changed to render the banner.
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier) {
                        StatusSlot(
                            permission = null,
                            question = null,
                            sessionStatus = null,
                            isCompacting = false,
                            currentActivityText = null,
                            currentActivityStartedAtMillis = null,
                            compactStartedAt = 0L,
                            isConnecting = false,
                            lastError = null,
                            permissionMetadata = ChatPermissionMetadata(
                                hostName = null,
                                workdirBasename = null,
                                sessionName = null,
                                toolName = null,
                                target = null,
                            ),
                            onRespondPermission = { _: PermissionResponse -> },
                            onReplyQuestion = { _: String, _: List<List<String>>, _: () -> Unit -> },
                            onRejectQuestion = { _: String, _: () -> Unit -> },
                            questionQueuePosition = 1,
                            questionQueueTotal = 1,
                            onAbort = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // The banner would paint the error code as Text + the error-outline
        // Icon with contentDescription = "Session error". Assert neither is
        // present. Using a placeholder error code that could ONLY come from
        // a real LastError payload (the production banner title is
        // lastError.name verbatim — see lastErrorBannerTitle).
        composeRule.onNodeWithText("upstream_error").assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(errorLabel, substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `banner Present When lastError Is set`() {
        // Hard gate (positive render): when lastError is SET, StatusSlot's
        // priority resolves to LastError and the render branch paints the
        // banner: error.name as Text + the error-outline Icon with
        // contentDescription = "Session error" + the server-scrubbed message.
        //
        // Discrimination: fails if the `is StatusSlotContent.LastError`
        // render branch is removed (the exhaustive `when` would not compile,
        // surrogate-failing the test) OR if the content mapping drops the
        // payload (`lastError?.let { StatusSlotContent.LastError(it) }`
        // replaced with `StatusSlotContent.None`).
        val lastError = SlimSessionLastError(
            name = "upstream_error",
            message = "provider returned 503",
            at = 1_700_000_000_000L,
        )
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier) {
                        StatusSlot(
                            permission = null,
                            question = null,
                            sessionStatus = null,
                            isCompacting = false,
                            currentActivityText = null,
                            currentActivityStartedAtMillis = null,
                            compactStartedAt = 0L,
                            isConnecting = false,
                            lastError = lastError,
                            permissionMetadata = ChatPermissionMetadata(
                                hostName = null,
                                workdirBasename = null,
                                sessionName = null,
                                toolName = null,
                                target = null,
                            ),
                            onRespondPermission = { _: PermissionResponse -> },
                            onReplyQuestion = { _: String, _: List<List<String>>, _: () -> Unit -> },
                            onRejectQuestion = { _: String, _: () -> Unit -> },
                            questionQueuePosition = 1,
                            questionQueueTotal = 1,
                            onAbort = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Title (error code, verbatim — lastErrorBannerTitle contract).
        composeRule.onNodeWithText("upstream_error").assertIsDisplayed()
        // Subtitle (server-scrubbed message — lastErrorBannerSubtitle contract).
        composeRule.onNodeWithText("provider returned 503").assertIsDisplayed()
        // Banner icon (a11y label).
        composeRule
            .onNodeWithContentDescription(errorLabel, substring = true)
            .assertIsDisplayed()
    }

    // ── §T17-C3 — RecentSessionRow indicator render discrimination ─────────

    @Test
    fun `row indicator Absent When showErrorIndicator false`() {
        // Hard gate at the row layer: when `sessionErrorsById` does NOT
        // contain the row's sid, the row's `showErrorIndicator` resolves to
        // false (via `shouldShowSessionErrorIndicator`) and the
        // `trailingContent { if (showErrorIndicator) Icon(...) }` branch
        // suppresses the icon. The drawer MUST NOT paint any error-outline
        // icon for that row.
        //
        // Discrimination: fails if the `if (showErrorIndicator)` guard is
        // dropped (icon always renders) OR if `showErrorIndicator` is
        // hardwired to true in the row call site.
        val session = Session(id = "sess-a", directory = "/tmp/work-a")
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    RecentSessionsDrawer(
                        sessions = listOf(session),
                        onSelect = {},
                        onBackToHome = {},
                        // Empty map → every row's showErrorIndicator is false.
                        sessionErrorsById = emptyMap(),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithContentDescription(errorLabel, substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `row indicator Present When showErrorIndicator true`() {
        // Hard gate at the row layer (positive render): when
        // `sessionErrorsById[sid]` is SET, the row's `showErrorIndicator`
        // resolves to true and the `trailingContent` paints the
        // error-outline icon. Exactly ONE such icon — the other rows (and
        // the drawer header) MUST NOT paint the same icon.
        //
        // Discrimination: fails if the indicator branch is removed OR if the
        // lookup is mis-wired (e.g. always returns false regardless of map
        // contents). The `assertCountEquals(1)` clause ALSO catches a
        // future regression where every row paints the icon unconditionally
        // (the drawer here has exactly one row, but the assertion pattern
        // composes correctly when fixtures add a second non-error row).
        val sessionWithError = Session(id = "sess-err", directory = "/tmp/work-err")
        val sessionWithoutError = Session(id = "sess-ok", directory = "/tmp/work-ok")
        val map = mapOf(
            "sess-err" to SlimSessionLastError(name = "upstream_error", message = null),
            // sess-ok intentionally absent — its row must NOT show the indicator.
        )
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    RecentSessionsDrawer(
                        sessions = listOf(sessionWithError, sessionWithoutError),
                        onSelect = {},
                        onBackToHome = {},
                        sessionErrorsById = map,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Exactly one error-outline icon: the sess-err row paints it,
        // the sess-ok row does not, the drawer header does not.
        composeRule
            .onAllNodesWithContentDescription(errorLabel, substring = true)
            .assertCountEquals(1)
    }

    // ── §T17 parity — C1 (banner) ↔ C3 (row indicator) at the render layer ─

    @Test
    fun `c1 And c3 Surfaces Render In Parity`() {
        // Round-1 review gap (IMPORTANT-1): the `RecentSessionErrorIndicatorTest`
        // parity test exercises the two PURE helpers against the same map but
        // does not drive the Compose surfaces. This test renders BOTH
        // `StatusSlot` and `RecentSessionsDrawer` against the SAME canonical
        // map and asserts:
        //   (a) when sid is present, BOTH surfaces paint their respective
        //       indicator (banner in StatusSlot + outline icon in the row) →
        //       exactly 2 nodes carry the error-label content description;
        //   (b) when sid is absent, NEITHER surface paints → 0 such nodes.
        // This pins the C1↔C3 parity at the render layer — the same data
        // store drives both surfaces' visibility, structurally (not just
        // helper-by-helper).
        //
        // Discrimination: fails on either side independently — if the
        // banner branch is broken, count drops to 1 (only row); if the row
        // branch is broken, count drops to 1 (only banner); if both are
        // broken, count drops to 0.
        //
        // Single-setContent contract: the Compose test rule allows only ONE
        // setContent per test. Phase (b) is driven by mutating the state
        // holders (runOnIdle) so the surfaces recompose to the absent-sid
        // configuration inside the SAME composition — this is the same
        // pattern StatusSlotTransitionTest uses for its question-clear frame.
        val sid = "sess-parity"
        val error = SlimSessionLastError(name = "session_not_found", message = "x")
        val session = Session(id = sid, directory = "/tmp/work-parity")

        var currentLastError: SlimSessionLastError? by mutableStateOf(error)
        var currentMap: Map<String, SlimSessionLastError> by mutableStateOf(
            mapOf(sid to error)
        )

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    androidx.compose.foundation.layout.Column {
                        // StatusSlot — BoxScope receiver; wrap so the receiver
                        // is the Column's Box-like parent. lastError reads
                        // from the mutable state so phase (b) sees null.
                        Box(Modifier) {
                            StatusSlot(
                                permission = null,
                                question = null,
                                sessionStatus = null,
                                isCompacting = false,
                                currentActivityText = null,
                                currentActivityStartedAtMillis = null,
                                compactStartedAt = 0L,
                                isConnecting = false,
                                lastError = currentLastError,
                                permissionMetadata = ChatPermissionMetadata(
                                    hostName = null,
                                    workdirBasename = null,
                                    sessionName = null,
                                    toolName = null,
                                    target = null,
                                ),
                                onRespondPermission = { _: PermissionResponse -> },
                                onReplyQuestion = { _: String, _: List<List<String>>, _: () -> Unit -> },
                                onRejectQuestion = { _: String, _: () -> Unit -> },
                                questionQueuePosition = 1,
                                questionQueueTotal = 1,
                                onAbort = {},
                            )
                        }
                        // Drawer — same canonical map. Phase (b) sees empty.
                        RecentSessionsDrawer(
                            sessions = listOf(session),
                            onSelect = {},
                            onBackToHome = {},
                            sessionErrorsById = currentMap,
                        )
                    }
                }
            }
        }

        // (a) sid present → BOTH surfaces paint. Two nodes carry the
        // error-label CD (banner icon + row indicator).
        composeRule.waitForIdle()
        composeRule
            .onAllNodesWithContentDescription(errorLabel, substring = true)
            .assertCountEquals(2)

        // (b) Clear the map → BOTH surfaces must suppress. Drives the
        // null-safety gate at the render layer: absent sid → banner None,
        // absent sid → row indicator false. Mutate the state holders so the
        // existing composition recomposes (no second setContent — the
        // AndroidComposeTestRule disallows that).
        composeRule.runOnIdle {
            currentLastError = null
            currentMap = emptyMap()
        }
        composeRule.waitForIdle()
        composeRule
            .onAllNodesWithContentDescription(errorLabel, substring = true)
            .assertCountEquals(0)
    }
}
