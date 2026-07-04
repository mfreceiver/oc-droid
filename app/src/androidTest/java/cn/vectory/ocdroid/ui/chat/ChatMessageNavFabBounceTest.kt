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
 *  3. Tap "Previous user message" → NavFab animateScrollToItem(target=2) +
 *     animateScrollBy(-0.8vh). The 0.8-viewport negative scroll overshoots
 *     past index 0 and clamps to the bottom.
 *  4. Tracker sees index == 0 → re-arms followBottom = true.
 *  5. contentVersion bumps (simulated streaming token) → auto-scroll effect
 *     scrollToItem(0) → list pinned at bottom = BOUNCE.
 *
 * Assertion: after the jump + a content tick, the list must NOT be back at
 * index 0 — the user's jump target must be preserved.
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
        val tag = "chatList"
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState

        composeRule.setContent {
            val ls = rememberLazyListState()
            listState = ls

            // --- Bottom-position tracker (faithful copy of ChatMessageContent.kt:310-327).
            // NB: no "programmatic scroll in progress" guard — this is the bug. ---
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
                        if (ls.layoutInfo.totalItemsCount == 0) return@collect
                        val atExactBottom = index == 0 && offset <= 24
                        followBottom = if (canBack) atExactBottom else true
                    }
            }

            // --- contentVersion auto-scroll (faithful copy of the non-streaming
            // branch of ChatMessageContent.kt:365-429). followBottom read inside
            // the effect, keyed on contentVersion — same as production. ---
            LaunchedEffect(contentVersion) {
                if (followBottom) {
                    ls.animateScrollToItem(0)
                }
            }

            Surface(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = ls,
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxSize()
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
                        onNavigateUp = { followBottom = false },
                        onNavigateBottom = { followBottom = true },
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
        composeRule.runOnIdle {
            kotlinx.coroutines.runBlocking { listState.scrollToItem(1) }
        }
        composeRule.waitForIdle()
        idx = composeRule.runOnIdle { listState.firstVisibleItemIndex }
        assertTrue("positioning failed (index=$idx)", idx == 1)

        // 3. Tap "Previous user message" → NavFab jumps to target=2 (first user
        //    index > 1) then animateScrollBy(-0.8vh) overshoots to index 0.
        composeRule.onNodeWithContentDescription("Previous user message").performClick()
        composeRule.waitForIdle()

        val afterJump = composeRule.runOnIdle { listState.firstVisibleItemIndex }

        // 4. Simulate a streaming token → restarts the contentVersion effect.
        composeRule.runOnIdle { contentVersion++ }
        composeRule.waitForIdle()

        val final = composeRule.runOnIdle { listState.firstVisibleItemIndex }

        // Desired: the user jumped to an OLDER user message; the list must NOT
        // be back at index 0 (bottom). Pre-fix this fails because the jump
        // overshoots to 0 and the re-armed followBottom pins it there.
        assertTrue(
            "NavFab up-jump bounced back to bottom — afterJump=$afterJump final=$final",
            final > 0
        )
    }
}
