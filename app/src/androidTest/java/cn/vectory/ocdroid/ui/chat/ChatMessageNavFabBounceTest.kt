package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for the NavFab "jump then bounce back to bottom" bug.
 *
 * [ChatMessageList] is hard-wired to [cn.vectory.ocdroid.ui.MainViewModel], so
 * this test faithfully replicates ONLY the scroll-control subgraph of
 * [ChatMessageContent] (the bottom-position tracker + the contentVersion
 * auto-scroll effect) and renders the REAL [ChatMessageNavFab] against it.
 *
 * Reproduction (pre-fix):
 *  1. List at bottom (index 0), followBottom = true.
 *  2. Position list just above bottom (index 1) → tracker sets followBottom=false.
 *  3. Tap "Previous user message" → NavFab jumps to target=2 (first user index
 *     > 1) and centers it.
 *  4. Tracker (unguarded pre-fix) re-arms followBottom=true on the jump's scroll
 *     frames or, post-jump, via the canScrollBackward==false shortcut on a short
 *     list when the next layout change (simulated token) re-emits.
 *  5. contentVersion bumps (simulated streaming token) → auto-scroll effect
 *     scrollToItem(0) → item 2 pushed off-screen (250dp viewport) = BOUNCE.
 *
 * §fix-nav-guard (kimo 🟡-2): the production fix uses a ref-counted
 * `navJumpDepth` guard that the NavFab increments via onJumpStart / decrements
 * via onJumpEnd (try/finally, cancel-safe) and all three scroll observers
 * (direction detector, bottom-position tracker, contentVersion auto-scroll)
 * respect (`> 0` → skip).
 *
 * §fix-nav-pin (glmer R-2): a `navPinnedAway` latch set by onNavigateUp makes
 * the bottom tracker require genuine atExactBottom before re-arming
 * followBottom (defeating the canScrollBackward==false→true shortcut that
 * caused the post-jump residual bounce in streaming short-list sessions).
 *
 * §fix-nav-test (glmer R-1): the viewport is pinned (requiredHeight 250dp) so
 * the assertion is device-independent, and the assertion checks the real fix
 * semantics (target still visible + followBottom not re-armed) instead of the
 * broken `firstVisibleItemIndex > 0` which false-passed on large viewports.
 *
 * Assertion: after the jump + a content tick, the target item must still be
 * visible and followBottom must remain false — the user's jump is preserved.
 *
 * NB: this is an androidTest (not in check.sh). It must be run via
 * ./gradlew connectedDebugAndroidTest on an emulator per AGENTS.md.
 */
class ChatMessageNavFabBounceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun navFabUpJump_isNotUndoneByFollowingContentTick() {
        // Hoisted state so the test can drive contentVersion (streaming token)
        // and observe followBottom.
        var contentVersion by mutableIntStateOf(0)
        var followBottom by mutableStateOf(true)
        // §fix-nav-guard (kimo 🟡-2): ref-counted programmatic-jump depth, mirroring production.
        var navJumpDepth by mutableIntStateOf(0)
        // §fix-nav-pin (glmer R-2): history-pin latch, mirroring production.
        var navPinnedAway by mutableStateOf(false)
        val tag = "chatList"
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState

        composeRule.setContent {
            val ls = rememberLazyListState()
            listState = ls

            // --- Bottom-position tracker (faithful copy of ChatMessageContent.kt
            // bottom tracker). §fix-nav-guard + §fix-nav-pin mirror production. ---
            LaunchedEffect(ls) {
                delay(300)
                snapshotFlow {
                    Triple(
                        ls.canScrollBackward,
                        ls.firstVisibleItemIndex,
                        ls.firstVisibleItemScrollOffset
                    )
                }
                    .drop(1)
                    .collect { (canBack, index, offset) ->
                        if (navJumpDepth > 0) return@collect
                        if (ls.layoutInfo.totalItemsCount == 0) return@collect
                        val atExactBottom = index == 0 && offset <= 24
                        if (navPinnedAway) {
                            followBottom = atExactBottom
                            if (atExactBottom) navPinnedAway = false
                        } else {
                            followBottom = if (canBack) atExactBottom else true
                        }
                    }
            }

            // --- contentVersion auto-scroll (faithful copy of the non-streaming
            // branch of ChatMessageContent contentVersion effect). §fix-nav-guard
            // skips while navJumpDepth > 0 — same as production. ---
            LaunchedEffect(contentVersion) {
                if (navJumpDepth > 0) return@LaunchedEffect
                if (followBottom) {
                    ls.animateScrollToItem(0)
                }
            }

            Surface(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    // §fix-nav-test-viewport (glmer R-1): pin the LazyColumn height so the
                    // test is deterministic across devices. 250dp fits 2.5 × 100dp items:
                    // after scrollToItem(0) item 2 is pushed off-screen (above viewport),
                    // so a bounce is detectable as "target not visible"; after a successful
                    // centered jump item 2 stays visible.
                    LazyColumn(
                        state = ls,
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .requiredHeight(250.dp)
                            .testTag(tag)
                    ) {
                        items(30) { idx ->
                            // index 0 = newest (visual bottom). User rows at
                            // 2 / 15 / 25 so NavFab has jump targets.
                            val isUser = idx in setOf(2, 15, 25)
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .height(100.dp)
                                    .background(if (isUser) Color(0xFFE3F2FD) else Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${if (isUser) "USER " else ""}item $idx")
                            }
                        }
                    }
                    // Real NavFab, forced visible (visibility gating is unrelated
                    // to the bounce). userMessageLcIndices ascending: [2,15,25].
                    ChatMessageNavFab(
                        listState = ls,
                        userMessageLcIndices = intArrayOf(2, 15, 25),
                        visible = true,
                        onInteract = { },
                        onNavigateUp = { followBottom = false; navPinnedAway = true },
                        onNavigateBottom = { followBottom = true; navPinnedAway = false },
                        onJumpStart = { navJumpDepth++ },
                        onJumpEnd = { if (navJumpDepth > 0) navJumpDepth-- },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }

        composeRule.waitForIdle()

        // 1. Start at bottom.
        var idx = composeRule.runOnIdle { listState.firstVisibleItemIndex }
        assertTrue("expected start at bottom (index 0), got $idx", idx == 0)

        // 2. Position just above bottom (index 1) → tracker sets followBottom=false.
        //    Deterministic (does not depend on gesture landing point).
        //    NB: scrollToItem is non-suspending — no runBlocking needed.
        composeRule.runOnIdle { listState.scrollToItem(1) }
        composeRule.waitForIdle()
        idx = composeRule.runOnIdle { listState.firstVisibleItemIndex }
        assertTrue("positioning failed (index=$idx)", idx == 1)

        // 3. Tap "Previous user message" → NavFab jumpToCenteredListState(target=2).
        //    §fix-nav-pin: onNavigateUp sets followBottom=false + navPinnedAway=true;
        //    onJumpStart increments navJumpDepth; the centered jump leaves item 2
        //    visible (pinned 250dp viewport keeps it on-screen post-center).
        composeRule.onNodeWithContentDescription("Previous user message").performClick()
        composeRule.waitForIdle()

        // 4. Simulate a streaming token → restarts the contentVersion effect.
        //    Pre-fix: tracker re-arms followBottom=true (canBack path or short-list
        //    shortcut) → effect scrollToItem(0) → item 2 pushed off-screen = BOUNCE.
        //    Post-fix: navJumpDepth guard + navPinnedAway latch keep followBottom=false
        //    → effect skips → item 2 stays visible.
        composeRule.runOnIdle { contentVersion++ }
        composeRule.waitForIdle()

        val targetVisible = composeRule.runOnIdle {
            listState.layoutInfo.visibleItemsInfo.any { it.index == 2 }
        }
        val fbAfter = composeRule.runOnIdle { followBottom }

        // §fix-nav-test-assert (glmer R-1): assert on the actual fix semantics —
        // (a) the jump target is still on screen (no bounce yanked it away), and
        // (b) followBottom was NOT re-armed by the tracker (the structural R-2 fix).
        // The old `final > 0` assertion was broken: in a large viewport item 0
        // remains visible after centering → firstVisibleItemIndex == 0 → false pass.
        assertTrue(
            "NavFab up-jump bounced — target item 2 not visible after content tick (followBottom=$fbAfter)",
            targetVisible
        )
        assertTrue(
            "followBottom re-armed after up-jump (should stay false while pinned to history)",
            !fbAfter
        )
    }
}
