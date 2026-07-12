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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cn.vectory.ocdroid.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * §navfab-redesign: tests for the single "jump to latest" button.
 *
 * The old up/down "navigate between user messages" design (with centering,
 * cursor, guards) was removed — it repeatedly failed (positioning math,
 * two-step animation, scrollOffset semantics). The new NavFab is a single
 * button that calls animateScrollToItem(0) to jump to the newest message.
 *
 * Visibility is driven by the caller (ChatMessageList's direction detector:
 * appears on scroll-toward-newer, hides on bottom/press/3s). These tests
 * cover the NavFab's own contract: press → list lands at index 0 (bottom).
 */
class ChatMessageNavFabBounceTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Pressing the "jump to latest" button scrolls the list to index 0 (newest,
     * visual bottom in reverseLayout) regardless of where it was.
     */
    @Test
    fun navFabJumpToLatest_bringsListToBottom() {
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
                            .testTag(tag)
                    ) {
                        items(30) { idx ->
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .height(100.dp)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("item $idx")
                            }
                        }
                    }
                    ChatMessageNavFab(
                        listState = ls,
                        visible = true,
                        onJump = { },
                        onJumpDone = { },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }

        composeRule.waitForIdle()

        // Position the list in the middle of history (index 10) — away from bottom.
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(10) } }
        composeRule.waitForIdle()
        val before = composeRule.runOnIdle { listState.firstVisibleItemIndex }
        assertTrue("expected to start away from bottom (index 10), got $before", before == 10)

        // Press "jump to latest" (locale-resolved) → animateScrollToItem(0).
        // §locale-stable: ChatMessageNavFab emits contentDescription via
        // stringResource(R.string.chat_jump_to_latest), which yields the device
        // locale's translation ("跳到最新" on zh, "Jump to latest" on en). Resolve
        // the same resource from the app's target context (the same context
        // Compose resolves stringResource against) so the assertion is
        // locale-independent and matches whatever the composable actually emitted.
        val jumpLabel = InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(R.string.chat_jump_to_latest)
        composeRule.onNodeWithContentDescription(jumpLabel).performClick()
        composeRule.waitForIdle()

        val after = composeRule.runOnIdle { listState.firstVisibleItemIndex }
        assertTrue("jump-to-latest did not reach bottom — before=$before after=$after", after == 0)
    }
}
