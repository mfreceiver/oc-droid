package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
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
        //    NB: scrollToItem is suspending in this Compose version → runBlocking.
        composeRule.runOnIdle {
            kotlinx.coroutines.runBlocking { listState.scrollToItem(1) }
        }
        composeRule.waitForIdle()
        idx = composeRule.runOnIdle { listState.firstVisibleItemIndex }
        assertTrue("positioning failed (index=$idx)", idx == 1)

        // 3. Tap "Previous user message" → NavFab jumps to the first user index
        //    greater than the viewport-centered item, then centers it.
        //    §fix-nav-pin: onNavigateUp sets followBottom=false + navPinnedAway=true;
        //    onJumpStart increments navJumpDepth.
        composeRule.onNodeWithContentDescription("Previous user message").performClick()
        composeRule.waitForIdle()

        // Record whichever user message the jump actually centered (adaptive —
        // robust to cursor-logic changes, §fix-nav-cursor).
        val userIndices = setOf(2, 15, 25)
        val centeredUser = composeRule.runOnIdle {
            val vh = listState.layoutInfo.viewportSize.height.toFloat()
            val half = vh / 2f
            listState.layoutInfo.visibleItemsInfo
                .filter { it.index in userIndices }
                .minByOrNull { info ->
                    val c = info.offset + info.size / 2f
                    if (c >= half) c - half else half - c
                }?.index
        }
        assertTrue("no user message centered after up-jump", centeredUser != null)

        // 4. Simulate a streaming token → restarts the contentVersion effect.
        //    Pre-fix: tracker re-arms followBottom=true → effect scrollToItem(0) →
        //    centered user pushed off-screen = BOUNCE.
        //    Post-fix: navJumpDepth guard + navPinnedAway latch keep followBottom=false
        //    → effect skips → centered user stays visible.
        composeRule.runOnIdle { contentVersion++ }
        composeRule.waitForIdle()

        val targetVisible = composeRule.runOnIdle {
            listState.layoutInfo.visibleItemsInfo.any { it.index == centeredUser }
        }
        val fbAfter = composeRule.runOnIdle { followBottom }

        // §fix-nav-test-assert (glmer R-1): assert on the actual fix semantics —
        // (a) the centered user message is still on screen (no bounce), and
        // (b) followBottom was NOT re-armed by the tracker (the R-2 latch fix).
        assertTrue(
            "NavFab up-jump bounced — centered user $centeredUser not visible after content tick (followBottom=$fbAfter)",
            targetVisible
        )
        assertTrue(
            "followBottom re-armed after up-jump (should stay false while pinned to history)",
            !fbAfter
        )
    }

    /**
     * §fix-nav-cursor: regression for the "stuck on one conversation" bug.
     *
     * Pre-fix the cursor was `firstVisibleItemIndex`. After centering a user
     * message, newer (smaller-index) items remain visible in the lower half of
     * the viewport → firstVisibleItemIndex stays 0 → the next UP tap computes
     * the same target → the user can never advance past the first user message.
     *
     * Post-fix the cursor is the viewport-centered item index, so each UP tap
     * advances to the next older user message. This test presses UP three times
     * from the bottom and asserts the centered user message strictly increases
     * each time (2 → 15 → 25 for userMessageLcIndices [2,15,25]).
     */
    @Test
    fun navFabUpJump_advancesThroughUserMessages_notStuck() {
        val userIndices = setOf(2, 15, 25)
        val tag = "chatList"
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState

        composeRule.setContent {
            val ls = rememberLazyListState()
            listState = ls
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    // Pin a tall viewport so centering lands the target cleanly
                    // mid-viewport and the centered index is unambiguous.
                    LazyColumn(
                        state = ls,
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .requiredHeight(400.dp)
                            .testTag(tag)
                    ) {
                        items(30) { idx ->
                            val isUser = idx in userIndices
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
                    ChatMessageNavFab(
                        listState = ls,
                        userMessageLcIndices = intArrayOf(2, 15, 25),
                        visible = true,
                        onInteract = { },
                        onNavigateUp = { },
                        onNavigateBottom = { },
                        onJumpStart = { },
                        onJumpEnd = { },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }

        composeRule.waitForIdle()

        fun centeredUser(): Int? = composeRule.runOnIdle {
            val vh = listState.layoutInfo.viewportSize.height.toFloat()
            val half = vh / 2f
            listState.layoutInfo.visibleItemsInfo
                .filter { it.index in userIndices }
                .minByOrNull { info ->
                    val c = info.offset + info.size / 2f
                    if (c >= half) c - half else half - c
                }?.index
        }

        // Start at bottom.
        val startIdx = composeRule.runOnIdle { listState.firstVisibleItemIndex }
        assertTrue("expected start at bottom, got $startIdx", startIdx == 0)

        // §fix-nav-cursor: at the bottom, user message 2 is already visible → navAnchor
        // treats it as "read" and UP skips to the next older user message (15). Each UP
        // then advances strictly: 15 → 25 → (exhausted) jump-to-top (no user msg = null).
        // The anti-stuck guarantee: no two consecutive jumps land on the same user msg.
        composeRule.onNodeWithContentDescription("Previous user message").performClick()
        composeRule.waitForIdle()
        val after1 = centeredUser()

        composeRule.onNodeWithContentDescription("Previous user message").performClick()
        composeRule.waitForIdle()
        val after2 = centeredUser()

        composeRule.onNodeWithContentDescription("Previous user message").performClick()
        composeRule.waitForIdle()
        val after3 = centeredUser()

        // after1 must be a real user message; after2 must strictly advance past after1.
        assertTrue("UP #1 did not land on a user message (got $after1)", after1 != null)
        assertTrue(
            "UP did not advance — stuck: #1=$after1 #2=$after2",
            after2 != null && after2 > after1!!
        )
        // after3: either advanced further to a user message, OR exhausted past the last
        // user message and jumped to the list top (centeredUser == null). Both are valid;
        // the bug is landing on the SAME user message as after2.
        assertTrue(
            "UP stuck on same user message after exhausting: #2=$after2 #3=$after3",
            after3 == null || after3 > after2!!
        )

        // §fix-nav-sign: assert the centered user message (after1 = item 15, which was
        // OFF-SCREEN before the jump) actually lands near viewport CENTER, not pinned at
        // the scroll-start edge (offset ~0 = visual bottom). A sign error in centerTarget
        // pushes the target off-screen → hard-guarantee snaps it back to offset 0 (bottom),
        // which the visibility-only assertions above cannot distinguish from real centering.
        // Tolerance: within itemH/2 of the desired centered offset.
        val centeredOffsetInfo = composeRule.runOnIdle {
            val vh = listState.layoutInfo.viewportSize.height.toFloat()
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == after1 }
            Triple(vh, info?.offset?.toFloat(), info?.size?.toFloat())
        }
        val (vh, off1, size1) = centeredOffsetInfo
        if (off1 != null && size1 != null && vh > 0f) {
            val desired = (vh - size1) / 2f
            val tol = size1   // generous: within one item height of center
            assertTrue(
                "UP #1 target $after1 not centered — offset=$off1 desired=$desired vh=$vh (sign-bug regression: target pinned at edge then bounced)",
                kotlin.math.abs(off1 - desired) <= tol
            )
        }
    }

    /**
     * §fix-nav-onestep (gpter 🔴-1 / glmer O-1): variable-height items must center the
     * off-screen jump target in ONE step via the instant scrollToItem correction (not a
     * second animateScrollBy refine that would replay the two-step bug).
     *
     * Scenario: items alternate tall (300dp, simulating long assistant replies) and short
     * (80dp, user messages). User rows at indices 1, 5, 9. From the bottom, UP jumps to a
     * tall-or-mixed region. After the jump the target must be (a) visible, (b) centered per
     * its ACTUAL measured size (offset ≈ (vh - actualSize)/2), proving the instant
     * scrollToItem(target) + centerTarget(animateScrollBy) path ran — not left at the
     * estimate-based offset (the disproven animateScrollToItem(target, scrollOffset)
     * approach would have clamped scrollOffset and left target at offset 0).
     *
     * This also guards the regression where someone reintroduces centerTarget(animateScrollBy)
     * in the off-screen branch: with variable heights the estimate-based offset would differ
     * from the actual-centered offset, and this assertion would catch the un-refined landing.
     */
    @Test
    fun navFabUpJump_variableHeightItems_centersByActualSize() {
        val userIndices = setOf(1, 5, 9)
        val tag = "chatList"
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState

        composeRule.setContent {
            val ls = rememberLazyListState()
            listState = ls
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = ls,
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .requiredHeight(500.dp)
                            .testTag(tag)
                    ) {
                        items(15) { idx ->
                            val isUser = idx in userIndices
                            // Variable heights: user messages short (80dp), others tall (300dp).
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .height(if (isUser) 80.dp else 300.dp)
                                    .background(if (isUser) Color(0xFFE3F2FD) else Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${if (isUser) "USER " else ""}item $idx")
                            }
                        }
                    }
                    ChatMessageNavFab(
                        listState = ls,
                        userMessageLcIndices = intArrayOf(1, 5, 9),
                        visible = true,
                        onInteract = { },
                        onNavigateUp = { },
                        onNavigateBottom = { },
                        onJumpStart = { },
                        onJumpEnd = { },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }

        composeRule.waitForIdle()

        // Start at bottom.
        val startIdx = composeRule.runOnIdle { listState.firstVisibleItemIndex }
        assertTrue("expected start at bottom, got $startIdx", startIdx == 0)

        // UP → jump to the next older user message (skips index 1 which is visible at bottom).
        composeRule.onNodeWithContentDescription("Previous user message").performClick()
        composeRule.waitForIdle()

        // The jumped-to target must be visible and centered per its ACTUAL size (80dp user msg),
        // not left at an estimate-based offset (which assumed ~300dp from the surrounding tall items).
        val landed = composeRule.runOnIdle {
            val vh = listState.layoutInfo.viewportSize.height.toFloat()
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index in userIndices }
            Triple(vh, info?.offset?.toFloat(), info?.size?.toFloat())
        }
        val (vh, off, size) = landed
        assertTrue("no user message visible after variable-height UP jump", off != null && size != null)
        if (vh > 0f && off != null && size != null) {
            val desired = (vh - size) / 2f
            // Tolerance: within the item's own height (80dp user msg). The instant scrollToItem
            // correction should land very close to exact center; a large deviation means the
            // correction was skipped (target left at estimate-based offset = un-refined).
            assertTrue(
                "variable-height UP jump target not centered by actual size — offset=$off desired=$desired size=$size vh=$vh (instant-correction regression)",
                kotlin.math.abs(off - desired) <= size
            )
        }
    }

    /**
     * §fix-nav-onestep (gpter 🔴-2, all reviewers): the off-screen jump must be a SINGLE
     * scroll-animation session, not two. This is the ONLY behavioral way to catch a
     * two-step regression (someone reverting to `animateScrollToItem(target) +
     * centerTarget(animateScrollBy)`), which the final-state assertions above cannot detect
     * because the final centered position is identical either way.
     *
     * Mechanism: `isScrollInProgress` is true only during ANIMATED scrolls
     * (animateScrollBy / animateScrollToItem). Instant `scrollToItem` does NOT trigger it.
     * - Current single-step impl: scrollToItem(target) [instant, no isScrollInProgress] +
     *   centerTarget(animateScrollBy) [1 session] → exactly **1** rising edge.
     * - Two-step regression: animateScrollToItem(target) [1 session] + centerTarget
     *   (animateScrollBy) [2nd session] → **2** rising edges → test fails.
     *
     * Assertion: after an off-screen UP jump, the number of distinct scroll-animation
     * sessions must be exactly 1.
     */
    @Test
    fun navFabOffscreenJump_isSingleScrollAnimationSession() {
        val tag = "chatList"
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState
        // Counted inside composition via a snapshotFlow on isScrollInProgress.
        val sessionCount = java.util.concurrent.atomic.AtomicInteger(0)

        composeRule.setContent {
            val ls = rememberLazyListState()
            listState = ls
            // §fix-nav-session: count rising edges of isScrollInProgress (each animated
            // scroll = one true→…→false cycle; a rising edge starts a session).
            LaunchedEffect(ls) {
                var prev = false
                snapshotFlow { ls.isScrollInProgress }.collect { progress ->
                    if (progress && !prev) sessionCount.incrementAndGet()
                    prev = progress
                }
            }
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = ls,
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .requiredHeight(400.dp)
                            .testTag(tag)
                    ) {
                        items(30) { idx ->
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
                    ChatMessageNavFab(
                        listState = ls,
                        userMessageLcIndices = intArrayOf(2, 15, 25),
                        visible = true,
                        onInteract = { },
                        onNavigateUp = { },
                        onNavigateBottom = { },
                        onJumpStart = { },
                        onJumpEnd = { },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }

        composeRule.waitForIdle()
        // Reset counter after initial settle (composition / layout may have caused early edges).
        sessionCount.set(0)

        // Start at bottom, tap UP → off-screen target (skips visible user msg 2, jumps to 15).
        val startIdx = composeRule.runOnIdle { listState.firstVisibleItemIndex }
        assertTrue("expected start at bottom, got $startIdx", startIdx == 0)

        composeRule.onNodeWithContentDescription("Previous user message").performClick()
        composeRule.waitForIdle()

        val sessions = composeRule.runOnIdle { sessionCount.get() }
        assertTrue(
            "off-screen UP jump used $sessions scroll-animation sessions (expected 1) — " +
                "two-step regression detected (animateScrollToItem + animateScrollBy would give 2)",
            sessions == 1
        )
    }
}
