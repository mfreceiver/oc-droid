package cn.vectory.ocdroid.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §oracle §5 / Wave1C r2 ChatMessageContent god-object split: Compose saveable
 * slot-positionality regression test for [rememberScrollController].
 *
 * oracle §5 flagged that "Compose saveable slot positionality is not caught by
 * check.sh": after [rememberScrollController] was extracted out of
 * `ChatMessageList`, a refactor that accidentally moves a [rememberSaveable]
 * call behind a conditional (or changes its input key) within
 * [rememberScrollController] would silently break the [SaveableStateHolder]'s
 * slot mapping — the old saved state would no longer be applied, and scroll
 * memory / follow-bottom would reset on every Chat→preview→back /
 * configuration change.
 *
 * Scope: these tests compose [rememberScrollController] DIRECTLY, so they cover
 * slot positionality WITHIN that function. They do NOT cover the call position
 * of [rememberScrollController] inside `ChatMessageList` (a shift there would
 * need an integration test rendering the full scaffold).
 *
 * Pure unit tests (and `check.sh`) cannot detect this because they never
 * exercise the save/restore lifecycle. These tests use
 * [StateRestorationTester.emulateSaveAndRestore] to drive a full save → dispose
 * → recreate-with-restored-state cycle, catching slot-positionality regressions.
 *
 * Production code under test: [rememberScrollController] in ScrollManager.kt.
 * Saveable surface (both [rememberSaveable] calls at the SAME body position):
 *   - `listState = rememberSaveable(sessionId, saver = LazyListState.Saver) { LazyListState() }`
 *   - `followBottomState = rememberSaveable(sessionId) { mutableStateOf(true) }`
 *
 * ## Compose-ui-test 1.10.0 note
 * `StateRestorationTester` takes a `ComposeUiTest`, which JUnit4's
 * `createComposeRule()` does NOT produce. The 1.10.0-correct entry is
 * [runAndroidComposeUiTest], whose receiver `AndroidComposeUiTest` IS a
 * `ComposeUiTest` (it shares the same `AndroidComposeUiTestEnvironment` as the
 * JUnit4 rule, so it runs identically under Robolectric).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ScrollManagerSaveableTest {

    /**
     * Core slot-positionality probe: [ScrollController.followBottom] is backed
     * by `rememberSaveable`, so a user-set `false` must survive a save+restore
     * cycle. If the `followBottomState` rememberSaveable slot were moved into a
     * conditional / shifted call position, the restored composition would
     * re-initialize it to its default `true`.
     *
     * The `navFabVisible` assertion is a NEGATIVE CONTROL: it is backed by
     * plain `remember` (NOT saveable), so it MUST reset to its default `false`
     * across the cycle. If it stayed `true`, the cycle did not actually run and
     * the followBottom assertion would be vacuous.
     */
    @Test
    fun `followBottom saveable slot survives save and restore`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val restorationTester = StateRestorationTester(this)
            val controller = arrayOf<ScrollController?>(null)

            restorationTester.setContent {
                controller[0] = rememberScrollController(sessionId = "s1")
            }

            // Defaults: followBottom=true, navFabVisible=false.
            runOnIdle {
                assertTrue("default followBottom should be true", controller[0]!!.followBottom)
                assertEquals("default navFabVisible should be false", false, controller[0]!!.navFabVisible)
            }

            // Mutate both: followBottom (saveable) + navFabVisible (plain remember).
            runOnIdle {
                controller[0]!!.followBottom = false
                controller[0]!!.navFabVisible = true
            }
            runOnIdle {
                assertEquals("followBottom should be false after mutation", false, controller[0]!!.followBottom)
                assertEquals("navFabVisible should be true after mutation", true, controller[0]!!.navFabVisible)
            }

            // Drive the save → dispose → recreate-with-restored-state cycle.
            restorationTester.emulateSaveAndRestore()

            runOnIdle {
                // followBottom is saveable → MUST survive.
                assertEquals(
                    "followBottom must remain false after save+restore — if this fails, " +
                        "followBottomState's rememberSaveable slot position is broken " +
                        "(moved into a conditional / shifted call position)",
                    false,
                    controller[0]!!.followBottom,
                )
                // navFabVisible is plain remember → MUST reset (proves the cycle ran).
                assertEquals(
                    "navFabVisible is plain remember and must reset to false across the " +
                        "cycle; if it stayed true the cycle did not run and the test is vacuous",
                    false,
                    controller[0]!!.navFabVisible,
                )
            }
        }

    /**
     * Scroll-position restoration: the [LazyListState] (driven via
     * `rememberSaveable(sessionId, saver = LazyListState.Saver)`) must round-trip
     * its `firstVisibleItemIndex` / `firstVisibleItemScrollOffset` across a
     * save+restore cycle. Renders a real [LazyColumn] wired to the controller's
     * listState, scrolls to a non-default position via the non-suspending
     * [LazyListState.requestScrollToItem], then asserts exact restoration.
     */
    @Test
    fun `listState scroll position survives save and restore`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val restorationTester = StateRestorationTester(this)
            val controller = arrayOf<ScrollController?>(null)

            restorationTester.setContent {
                val c = rememberScrollController(sessionId = "s1")
                controller[0] = c
                LazyColumn(state = c.listState, modifier = Modifier.fillMaxSize()) {
                    items((1..50).toList()) { i ->
                        Text(
                            "row $i",
                            modifier = Modifier.height(20.dp).fillMaxWidth(),
                        )
                    }
                }
            }
            waitForIdle()

            // Scroll to a non-default position (index=5, offset=10).
            runOnIdle { controller[0]!!.listState.requestScrollToItem(5, 10) }
            waitForIdle()

            val indexBefore = runOnIdle { controller[0]!!.listState.firstVisibleItemIndex }
            val offsetBefore = runOnIdle { controller[0]!!.listState.firstVisibleItemScrollOffset }
            // Pre-condition: the scroll must have taken effect, else the test is vacuous.
            assertTrue(
                "pre-restore firstVisibleItemIndex should be 5 (got $indexBefore) — " +
                    "if this fails the test surface did not lay out enough items",
                indexBefore == 5,
            )
            assertEquals("pre-restore firstVisibleItemScrollOffset", 10, offsetBefore)

            restorationTester.emulateSaveAndRestore()

            val indexAfter = runOnIdle { controller[0]!!.listState.firstVisibleItemIndex }
            val offsetAfter = runOnIdle { controller[0]!!.listState.firstVisibleItemScrollOffset }
            assertEquals(
                "listState firstVisibleItemIndex must survive save+restore (LazyListState.Saver)",
                indexBefore,
                indexAfter,
            )
            assertEquals(
                "listState firstVisibleItemScrollOffset must survive save+restore",
                offsetBefore,
                offsetAfter,
            )
        }

    /**
     * Keyed re-init contract: [rememberScrollController] keys its saveable
     * state on `sessionId`. Switching to a different session id MUST create a
     * fresh saveable slot (re-initialized to defaults) rather than restoring the
     * prior session's state. This pins Chat→preview→back isolation.
     */
    @Test
    fun `session id change re-initializes instead of restoring prior session state`() =
        runAndroidComposeUiTest<ComponentActivity> {
            val restorationTester = StateRestorationTester(this)
            val controller = arrayOf<ScrollController?>(null)
            var sessionId by mutableStateOf("s1")

            restorationTester.setContent {
                controller[0] = rememberScrollController(sessionId = sessionId)
            }

            // Session s1: set followBottom to false.
            runOnIdle { controller[0]!!.followBottom = false }
            runOnIdle { assertEquals("followBottom should be false for s1", false, controller[0]!!.followBottom) }

            // Switch to a new session — rememberSaveable key changes → re-init.
            runOnIdle { sessionId = "s2" }
            waitForIdle()

            runOnIdle {
                assertEquals(
                    "followBottom must be true after sessionId change to s2 — the " +
                        "rememberSaveable key changed, so a new slot was created with the " +
                        "default initializer { mutableStateOf(true) }. If the key/slot " +
                        "wiring were broken, stale false from s1 would survive.",
                    true,
                    controller[0]!!.followBottom,
                )
            }
        }
}
