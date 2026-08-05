package cn.vectory.ocdroid.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §oracle §5 / Wave1C r2 ChatMessageContent god-object split: Compose
 * LaunchedEffect timing contracts for [rememberScrollController].
 *
 * The existing [ScrollManagerSaveableTest] covers saveable slot positionality
 * and scroll-position restoration. This file covers the FOUR pure
 * [LaunchedEffect] blocks inside [rememberScrollController] that are NOT
 * exercised by saveable testing:
 *
 * <ol>
 *   <li>session-reset: key=(sessionId) — resets plain-remember flags on enter
 *   <li>navFab-hide:   key=(navFabTick) — 3s idle auto-hide timer
 *   <li>direction:     key=(listState, sessionId) — scroll-direction detection
 *   <li>bottom-track:  key=(listState, sessionId) — bottom-position follower
 * </ol>
 *
 * Effects 3 and 4 require a live [LazyColumn] with scroll events to exercise
 * their [snapshotFlow] bodies in a robotic environment; this file tests their
 * guard clauses (null-session skip) plus the structural key-and-early-return
 * contracts that are already decidable without a layout pass.
 *
 * Key-tuple coverage (see [ScrollManager] lines 48-54):
 * <ul>
 *   <li>`(sessionId)` — complete (Test 1, Test 2)
 *   <li>`(navFabTick)` — complete (Test 3; timing gap documented in KDoc)
 *   <li>`(listState, sessionId)` — partial (Test 4 null-guard only;
 *       snapshotFlow body needs a real LazyColumn scroll — not covered here)
 * </ul>
 *
 * Production code under test: [rememberScrollController] in ScrollManager.kt.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ScrollManagerEffectTest {

    /**
     * Session-reset contract: [LaunchedEffect(sessionId)] resets the three
     * "plain remember" flags ([navFabVisible], [navJumping],
     * [pendingRestoreSession]) to their defaults every time the session id
     * changes.
     *
     * MUTATION PROBE: before switching sessionId, all three are set to
     * non-default values (true / true / "x"). After the switch, they MUST
     * return to defaults (false / false / null).
     *
     * NEGATIVE CONTROL — saveable/non-saveable boundary: [followBottom] is
     * backed by [rememberSaveable] (keyed on sessionId), NOT by the
     * LaunchedEffect body. When sessionId changes, a NEW saveable slot is
     * created with its initializer, so [followBottom] re-initializes to
     * `true`. If a future refactor accidentally adds a
     * `followBottom = ...` line inside the LaunchedEffect(sessionId) body,
     * this test would catch it because [followBottom] would diverge from
     * the saveable-driven value.
     *
     * Regressions caught:
     * - navFabVisible stays true  ⇒ LaunchedEffect over-firing / key regression
     * - navJumping stays true      ⇒ same
     * - pendingRestoreSession not null ⇒ same
     * - followBottom is false      ⇒ LaunchedEffect body incorrectly resets
     *                                followBottom (saveable boundary broken)
     */
    @Test
    fun `session-reset effect resets plain-remember flags on sessionId change`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val controller = arrayOf<ScrollController?>(null)
            var sessionId by mutableStateOf("s1")

            setContent {
                controller[0] = rememberScrollController(sessionId = sessionId)
            }

            // Mutate all 3 plain-remember flags + followBottom (saveable).
            runOnIdle {
                controller[0]!!.navFabVisible = true
                controller[0]!!.navJumping = true
                controller[0]!!.pendingRestoreSession = "x"
                controller[0]!!.followBottom = false
            }
            runOnIdle {
                assertEquals(
                    "pre-condition: navFabVisible should be true after mutation",
                    true, controller[0]!!.navFabVisible,
                )
                assertEquals(
                    "pre-condition: navJumping should be true after mutation",
                    true, controller[0]!!.navJumping,
                )
                assertEquals(
                    "pre-condition: pendingRestoreSession should be 'x' after mutation",
                    "x", controller[0]!!.pendingRestoreSession,
                )
                assertEquals(
                    "pre-condition: followBottom should be false after mutation",
                    false, controller[0]!!.followBottom,
                )
            }

            // Switch sessionId — this triggers:
            //   a) new rememberSaveable slots (listState, followBottom re-init)
            //   b) new LaunchedEffect(sessionId) — fires session-reset body
            //   c) new controller[0] instance (fresh remember for plain flags)
            runOnIdle { sessionId = "s2" }
            waitForIdle()

            runOnIdle {
                // The 3 plain-remember flags MUST be at defaults after the
                // sessionId change: LaunchedEffect(sessionId) body at lines
                // 211-213 resets them.
                assertEquals(
                    "navFabVisible must be false after sessionId change — " +
                        "LaunchedEffect(sessionId) resets plain-remember navFabVisible",
                    false, controller[0]!!.navFabVisible,
                )
                assertEquals(
                    "navJumping must be false after sessionId change — " +
                        "LaunchedEffect(sessionId) resets plain-remember navJumping",
                    false, controller[0]!!.navJumping,
                )
                assertEquals(
                    "pendingRestoreSession must be null after sessionId change — " +
                        "LaunchedEffect(sessionId) resets it to null",
                    null, controller[0]!!.pendingRestoreSession,
                )

                // ── NEGATIVE CONTROL ──────────────────────────────────────
                // followBottom is backed by rememberSaveable(sessionId). When
                // sessionId changes, the saveable slot is re-keyed and
                // re-initialized to `true` (its default). The
                // LaunchedEffect(sessionId) body (lines 210-214) does NOT
                // touch followBottom. If a future refactor adds a
                // `followBottom = ...` line to that LaunchedEffect, this
                // assertion would fail because the saveable-driven value would
                // differ from the LaunchedEffect-driven value.
                assertEquals(
                    "followBottom must be true after sessionId change — it is " +
                        "backed by rememberSaveable(sessionId), so a new sessionId " +
                        "creates a new saveable slot with default=true. The " +
                        "LaunchedEffect(sessionId) body does NOT set followBottom " +
                        "(saveable/non-saveable boundary cross-claim)",
                    true, controller[0]!!.followBottom,
                )
            }
        }

    /**
     * The [LaunchedEffect(sessionId)] must NOT fire when [sessionId] stays
     * the same across recomposition. Same key ⇒ effect is NOT cancelled and
     * NOT re-launched, so manually-set [navFabVisible] must survive a
     * recomposition triggered by a parent state change.
     *
     * MUTATION PROBE: set [navFabVisible] to `true` AFTER the initial
     * composition's LaunchedEffect(sessionId) has already run to completion.
     * Then force a recomposition (toggle [forceRecompose]) WITHOUT changing
     * sessionId. [navFabVisible] must remain `true`.
     *
     * Regressions caught:
     * - navFabVisible reset to false after recomposition ⇒ LunchedEffect
     *   is over-firing (likely key changed to an unstable reference such as
     *   a lambda or object that recomputes as "different" on every frame)
     */
    @Test
    fun `session-reset effect does NOT fire when sessionId stays the same`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val controller = arrayOf<ScrollController?>(null)
            var forceRecompose by mutableStateOf(0)

            setContent {
                controller[0] = rememberScrollController(sessionId = "s1")
                // Reading forceRecompose in the composition scope ensures
                // that changing it triggers a full recomposition of this
                // lambda — rememberScrollController is called again with the
                // SAME sessionId.
                forceRecompose
            }

            // Set navFabVisible to true AFTER the initial composition's
            // LaunchedEffect(sessionId) has already completed (it runs once
            // when sessionId="s1" first enters composition).
            runOnIdle { controller[0]!!.navFabVisible = true }
            runOnIdle {
                assertEquals(
                    "pre-condition: navFabVisible should be true after mutation",
                    true, controller[0]!!.navFabVisible,
                )
            }

            // Force a recomposition WITHOUT changing sessionId.
            runOnIdle { forceRecompose = 1 }
            waitForIdle()

            runOnIdle {
                assertEquals(
                    "navFabVisible must remain true after recomposition with the " +
                        "same sessionId — LaunchedEffect(sessionId) key has not " +
                        "changed, so the effect must NOT re-fire. If it did, " +
                        "navFabVisible would be reset to false.",
                    true, controller[0]!!.navFabVisible,
                )
            }
        }

    /**
     * Auto-hide timer contract: [LaunchedEffect(navFabTick)] must set
     * [navFabVisible] to `false` after 3000ms of idle time when
     * [navFabVisible] was `true` at launch.
     *
     * PROCEDURE:
     * <ol>
     *   <li>Compose [rememberScrollController] with a non-null sessionId
     *   <li>Set [navFabVisible]=true and bump [navFabTick] from 0 to 1,
     *       which restarts the LaunchedEffect with the new key
     *   <li>The new LaunchedEffect body enters the `if (navFabVisible)` block
     *       and begins `delay(3000)`
     *   <li>Advance the Compose [mainClock] by 3001ms past the delay
     *   <li>Assert [navFabVisible] is now `false`
     * </ol>
     *
     * ## Timing-testability note
     * Under Robolectric + [runAndroidComposeUiTest], the [kotlinx.coroutines.delay]
     * inside the LaunchedEffect runs on the composition's coroutine scope.
     * The Compose test [mainClock] controls the [MonotonicFrameClock] that
     * drives frame-based coroutine scheduling within the test environment.
     * If [delay] does NOT honor the [mainClock] under Robolectric (because it
     * dispatches through the main-thread [android.os.Handler] via
     * Dispatchers.Main rather than the frame clock), then the timing
     * assertion will fail and the auto-hide timing CANNOT be verified in
     * this unit-test environment.
     *
     * At minimum, this test pins the EFFECT REGISTRATION SMOKE: bumping
     * [navFabTick] triggers a new LaunchedEffect start without crashing,
     * and the [navFabVisible] guard is structurally exercised.
     *
     * Regressions caught:
     * - LaunchedEffect(navFabTick) not restarting on tick bump
     * - Crash on LaunchedEffect restart
     * - If timing works under Robolectric: delay(3000) not honored or
     *   navFabVisible not set to false after completion
     */
    @Test
    fun `navFab-hide effect auto-hides after 3s delay on navFabTick bump`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val controller = arrayOf<ScrollController?>(null)

            setContent {
                controller[0] = rememberScrollController(sessionId = "s1")
            }

            // Activate: set navFabVisible=true and bump navFabTick so that
            // LaunchedEffect(navFabTick) restarts (key changed from 0 to 1)
            // and the new effect enters the `if (navFabVisible)` block.
            runOnIdle {
                controller[0]!!.navFabVisible = true
                controller[0]!!.navFabTick = 1
            }
            waitForIdle()

            runOnIdle {
                assertEquals(
                    "pre-condition: navFabVisible should be true before delay elapses",
                    true, controller[0]!!.navFabVisible,
                )
            }

            // Advance virtual time past the 3000ms delay.
            mainClock.advanceTimeBy(3001)
            waitForIdle()

            runOnIdle {
                assertEquals(
                    "navFabVisible must be false after advancing 3001ms — " +
                        "LaunchedEffect(navFabTick) should have completed delay(3000) " +
                        "and set navFabVisible=false. If this fails under Robolectric, " +
                        "the delay() may not honor the Compose mainClock (see KDoc).",
                    false, controller[0]!!.navFabVisible,
                )
            }
        }

    /**
     * Null-session guard: both [LaunchedEffect(listState, sessionId)] blocks
     * (direction at line 136, bottom-track at line 181) must early-return
     * when [sessionId] is `null`, because their first guard is
     * `if (sessionId == null) return@LaunchedEffect`.
     *
     * The [LaunchedEffect(sessionId)] (session-reset) runs normally with
     * `null` key — it sets the three plain-remember flags to defaults (which
     * they already are from [remember] initializers). The
     * [LaunchedEffect(navFabTick)] (auto-hide) is keyed only on
     * [navFabTick] and is unaffected by [sessionId]; navFabTick=0 +
     * navFabVisible=false causes it to exit its guard immediately.
     *
     * Verifies: no crash, no infinite loop, and a valid [ScrollController]
     * with default values is returned.
     *
     * Regressions caught:
     * - Crash when sessionId is null (e.g. NPE in guard that was removed)
     * - Direction/bottom-track effects running with null sessionId and
     *   modifying controller state when they should not
     */
    @Test
    fun `direction and bottom-track effects skip when sessionId is null`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val controller = arrayOf<ScrollController?>(null)

            setContent {
                controller[0] = rememberScrollController(sessionId = null)
            }
            waitForIdle()

            runOnIdle {
                assertNotNull(
                    "rememberScrollController(sessionId=null) must return a " +
                        "non-null ScrollController",
                    controller[0],
                )
                assertEquals(
                    "followBottom must default to true when sessionId is null",
                    true, controller[0]!!.followBottom,
                )
                assertEquals(
                    "navFabVisible must default to false when sessionId is null",
                    false, controller[0]!!.navFabVisible,
                )
                assertEquals(
                    "navJumping must default to false when sessionId is null",
                    false, controller[0]!!.navJumping,
                )
                assertEquals(
                    "pendingRestoreSession must be null when sessionId is null",
                    null, controller[0]!!.pendingRestoreSession,
                )
            }
        }
}
