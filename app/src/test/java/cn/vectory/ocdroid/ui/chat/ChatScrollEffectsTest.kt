package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.PendingScrollRequest
import cn.vectory.ocdroid.ui.ScrollBehavior
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §P0-2: Compose-level key-tuple pinning for [UnifiedScrollConsumerEffect],
 * [ReselectScrollEffect], and [ContentVersionScrollEffect].
 *
 * Each `@Test` exercises a VERBATIM key tuple dimension from
 * [ChatScrollEffects.kt] by mutating one key input and asserting the effect
 * re-fires (or the guard blocks it). Failure messages cite the exact key
 * tuple so a dropped dimension can be traced directly to the `LaunchedEffect`
 * key declaration.
 *
 * Uses `createComposeRule` (NOT `runAndroidComposeUiTest`) since we test
 * recomposition-driven re-fire, not saveable restoration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatScrollEffectsTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun msg(id: String): Message = Message(id = id, role = "user")
    private fun part(id: String): Part = Part(id = id, type = "text")

    /** Creates a fresh [ScrollController] backed by live MutableStates. */
    private fun createScrollController(
        listState: LazyListState = mockk<LazyListState>(relaxed = true).apply {
            every { layoutInfo } returns mockk {
                every { totalItemsCount } returns 5
                every { visibleItemsInfo } returns emptyList()
            }
            every { firstVisibleItemIndex } returns 0
            every { firstVisibleItemScrollOffset } returns 0
        },
    ) = ScrollController(
        listState = listState,
        followBottomState = mutableStateOf(true),
        navFabVisibleState = mutableStateOf(false),
        navFabTickState = mutableIntStateOf(0),
        navJumpingState = mutableStateOf(false),
        pendingRestoreSessionState = mutableStateOf(null),
    )

    /** Creates a [LazyListState] mock with sensible defaults. */
    private fun createListState(): LazyListState = mockk<LazyListState>(relaxed = true).apply {
        every { layoutInfo } returns mockk {
            every { totalItemsCount } returns 5
            every { visibleItemsInfo } returns emptyList()
        }
        every { firstVisibleItemIndex } returns 0
        every { firstVisibleItemScrollOffset } returns 0
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  § UnifiedScrollConsumerEffect
    //  Key tuple: (sessionId, pendingScrollRequest?.requestId, messages.isEmpty())
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `USCE requestId dimension triggers re-fire on new requestId`() {
        // Pins: pendingScrollRequest?.requestId  (key tuple index 2 of 3)
        //   null → 1L  (initial fire),  1L → 2L  (re-fire on mutation)
        val scroll = createScrollController()
        val chatVM = mockk<ChatViewModel>(relaxed = true)
        val listState = createListState()

        val sessionId = mutableStateOf<String?>("s1")
        val pendingRequest = mutableStateOf<PendingScrollRequest?>(null)
        val messages = mutableStateOf(listOf(msg("m1")))

        composeRule.setContent {
            UnifiedScrollConsumerEffect(
                sessionId = sessionId.value,
                pendingScrollRequest = pendingRequest.value,
                messages = messages.value,
                scroll = scroll,
                listState = listState,
                lazyColumnKeys = listOf("m1"),
                chatVM = chatVM,
            )
        }

        composeRule.waitForIdle()

        // pendingScrollRequest = null → LaunchedEffect fires with null key,
        // guard catches null and returns. No side-effects.
        assertTrue("followBottom should remain true (default) when null request",
            scroll.followBottom)
        assertFalse("navFabVisible should remain false (default) when null request",
            scroll.navFabVisible)

        // Set requestId = 1L → key changes from (…, null, …) to (…, 1L, …)
        composeRule.runOnIdle {
            pendingRequest.value = PendingScrollRequest(
                requestId = 1L,
                targetSessionId = "s1",
                behavior = ScrollBehavior.Latest,
            )
        }
        composeRule.waitForIdle()

        assertTrue("followBottom should become true after Latest scroll (requestId=1L)",
            scroll.followBottom)
        assertFalse("navFabVisible should become false after Latest scroll (requestId=1L)",
            scroll.navFabVisible)
        verify(exactly = 1) { chatVM.consumeScrollRequest(1L) }

        clearMocks(chatVM)

        // Change requestId to 2L → key changes from (…, 1L, …) to (…, 2L, …)
        composeRule.runOnIdle {
            pendingRequest.value = PendingScrollRequest(
                requestId = 2L,
                targetSessionId = "s1",
                behavior = ScrollBehavior.Latest,
            )
        }
        composeRule.waitForIdle()

        verify(exactly = 1) {
            chatVM.consumeScrollRequest(2L)
        }
    }

    @Test
    fun `USCE sessionId mismatch guard blocks fire until sessionId matches`() {
        // Pins: sessionId  (key tuple index 1 of 3)
        //   request targets "s1" but sessionId="s2" → guard returns
        //   sessionId switches to "s1" → effect fires
        val scroll = createScrollController()
        val chatVM = mockk<ChatViewModel>(relaxed = true)
        val listState = createListState()

        val sessionId = mutableStateOf<String?>("s2")
        val pendingRequest = mutableStateOf<PendingScrollRequest?>(
            PendingScrollRequest(
                requestId = 10L,
                targetSessionId = "s1",
                behavior = ScrollBehavior.Latest,
            ),
        )
        val messages = mutableStateOf(listOf(msg("m1")))

        composeRule.setContent {
            UnifiedScrollConsumerEffect(
                sessionId = sessionId.value,
                pendingScrollRequest = pendingRequest.value,
                messages = messages.value,
                scroll = scroll,
                listState = listState,
                lazyColumnKeys = listOf("m1"),
                chatVM = chatVM,
            )
        }

        composeRule.waitForIdle()

        // Session mismatch: req.targetSessionId="s1" != sessionId="s2" → guard
        assertTrue("followBottom should stay true (default) when sessionId mismatches",
            scroll.followBottom)
        verify(exactly = 0) { chatVM.consumeScrollRequest(any()) }

        // Switch sessionId to "s1" → key changes, now target matches → fires
        composeRule.runOnIdle {
            sessionId.value = "s1"
        }
        composeRule.waitForIdle()

        assertTrue("followBottom should become true after sessionId matches target",
            scroll.followBottom)
        verify(exactly = 1) { chatVM.consumeScrollRequest(10L) }
    }

    @Test
    fun `USCE messagesNotEmpty dimension unblocks effect when messages arrive`() {
        // Pins: messages.isEmpty()  (key tuple index 3 of 3)
        //   empty list → guard returns at "wait for the load"
        //   add a message → effect fires
        val scroll = createScrollController()
        val chatVM = mockk<ChatViewModel>(relaxed = true)
        val listState = createListState()

        val sessionId = mutableStateOf<String?>("s1")
        // Latest request already present targeting this session
        val pendingRequest = mutableStateOf<PendingScrollRequest?>(
            PendingScrollRequest(
                requestId = 20L,
                targetSessionId = "s1",
                behavior = ScrollBehavior.Latest,
            ),
        )
        val messages = mutableStateOf(emptyList<Message>())

        composeRule.setContent {
            UnifiedScrollConsumerEffect(
                sessionId = sessionId.value,
                pendingScrollRequest = pendingRequest.value,
                messages = messages.value,
                scroll = scroll,
                listState = listState,
                lazyColumnKeys = emptyList(),
                chatVM = chatVM,
            )
        }

        composeRule.waitForIdle()

        // messages.isEmpty() = true → key tuple dimension true → guard returns
        assertTrue("followBottom should stay true (default) when messages empty",
            scroll.followBottom)
        verify(exactly = 0) { chatVM.consumeScrollRequest(any()) }

        // Add a message → key changes from (…, true) to (…, false) → re-fire
        composeRule.runOnIdle {
            messages.value = listOf(msg("m1"))
        }
        composeRule.waitForIdle()

        assertTrue("followBottom should become true after messages arrive",
            scroll.followBottom)
        verify(exactly = 1) { chatVM.consumeScrollRequest(20L) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  § ContentVersionScrollEffect
    //  Key tuple: (sessionId, messages.size, isStreaming, streamingPartTextKeys,
    //              streamingReasoningPart?.id)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `CVSE messagesSize dimension re-fires when message count changes`() {
        // Pins: messages.size  (key tuple index 2 of 5)
        val scroll = createScrollController()
        val listState = createListState()

        val sessionId = mutableStateOf<String?>("s1")
        val messages = mutableStateOf(listOf(msg("m1")))
        val isStreaming = mutableStateOf(false)
        val streamingPartTextKeys = mutableStateOf(emptySet<String>())
        val streamingReasoningPart = mutableStateOf<Part?>(null)
        val expandedParts = mutableStateOf(emptyMap<String, Boolean>())
        val pendingScrollRequest = mutableStateOf<PendingScrollRequest?>(null)

        composeRule.setContent {
            ContentVersionScrollEffect(
                sessionId = sessionId.value,
                scroll = scroll,
                listState = listState,
                messages = messages.value,
                isStreaming = isStreaming.value,
                streamingPartTextKeys = streamingPartTextKeys.value,
                streamingReasoningPart = streamingReasoningPart.value,
                expandedParts = expandedParts.value,
                pendingScrollRequest = pendingScrollRequest.value,
            )
        }

        composeRule.waitForIdle()

        // Initial: messages=[m1], size=1 → non-streaming → animateScrollToItem(0)
        coVerify(atLeast = 1) { listState.animateScrollToItem(0) }

        clearMocks(listState)

        // Add m2 → key changes from size=1 to size=2 → re-fire
        composeRule.runOnIdle {
            messages.value = listOf(msg("m1"), msg("m2"))
        }
        composeRule.waitForIdle()

        coVerify(atLeast = 1) {
            listState.animateScrollToItem(0)
        }
    }

    @Test
    fun `CVSE isStreaming dimension re-fires when streaming flag flips`() {
        // Pins: isStreaming  (key tuple index 3 of 5)
        val scroll = createScrollController()
        val listState = createListState()

        val sessionId = mutableStateOf<String?>("s1")
        val messages = mutableStateOf(listOf(msg("m1")))
        val isStreaming = mutableStateOf(true) // start streaming
        val streamingPartTextKeys = mutableStateOf(emptySet<String>())
        val streamingReasoningPart = mutableStateOf<Part?>(null)
        val expandedParts = mutableStateOf(emptyMap<String, Boolean>())
        val pendingScrollRequest = mutableStateOf<PendingScrollRequest?>(null)

        composeRule.setContent {
            ContentVersionScrollEffect(
                sessionId = sessionId.value,
                scroll = scroll,
                listState = listState,
                messages = messages.value,
                isStreaming = isStreaming.value,
                streamingPartTextKeys = streamingPartTextKeys.value,
                streamingReasoningPart = streamingReasoningPart.value,
                expandedParts = expandedParts.value,
                pendingScrollRequest = pendingScrollRequest.value,
            )
        }

        composeRule.waitForIdle()

        // isStreaming=true + atBottom → requestScrollToItem(0)
        verify(atLeast = 1) { listState.requestScrollToItem(0) }

        clearMocks(listState)

        // Flip isStreaming to false → key changes → re-fire
        composeRule.runOnIdle {
            isStreaming.value = false
        }
        composeRule.waitForIdle()

        // Now non-streaming path: animateScrollToItem(0)
        coVerify(atLeast = 1) {
            listState.animateScrollToItem(0)
        }
    }

    @Test
    fun `CVSE streamingPartTextKeys dimension re-fires when key set changes`() {
        // Pins: streamingPartTextKeys  (key tuple index 4 of 5)
        val scroll = createScrollController()
        val listState = createListState()

        val sessionId = mutableStateOf<String?>("s1")
        val messages = mutableStateOf(listOf(msg("m1")))
        val isStreaming = mutableStateOf(false)
        val streamingPartTextKeys = mutableStateOf(setOf("k1"))
        val streamingReasoningPart = mutableStateOf<Part?>(null)
        val expandedParts = mutableStateOf(emptyMap<String, Boolean>())
        val pendingScrollRequest = mutableStateOf<PendingScrollRequest?>(null)

        composeRule.setContent {
            ContentVersionScrollEffect(
                sessionId = sessionId.value,
                scroll = scroll,
                listState = listState,
                messages = messages.value,
                isStreaming = isStreaming.value,
                streamingPartTextKeys = streamingPartTextKeys.value,
                streamingReasoningPart = streamingReasoningPart.value,
                expandedParts = expandedParts.value,
                pendingScrollRequest = pendingScrollRequest.value,
            )
        }

        composeRule.waitForIdle()

        // Initial fire with keys={"k1"}
        coVerify(atLeast = 1) { listState.animateScrollToItem(0) }
        clearMocks(listState)

        // Change key set from {"k1"} to {"k1", "k2"} → key changes → re-fire
        composeRule.runOnIdle {
            streamingPartTextKeys.value = setOf("k1", "k2")
        }
        composeRule.waitForIdle()

        coVerify(atLeast = 1) {
            listState.animateScrollToItem(0)
        }
    }

    @Test
    fun `CVSE streamingReasoningPartId dimension re-fires when reasoning part id changes`() {
        // Pins: streamingReasoningPart?.id  (key tuple index 5 of 5)
        val scroll = createScrollController()
        val listState = createListState()

        val sessionId = mutableStateOf<String?>("s1")
        val messages = mutableStateOf(listOf(msg("m1")))
        val isStreaming = mutableStateOf(false)
        val streamingPartTextKeys = mutableStateOf(emptySet<String>())
        val streamingReasoningPart = mutableStateOf<Part?>(part("r1"))
        val expandedParts = mutableStateOf(emptyMap<String, Boolean>())
        val pendingScrollRequest = mutableStateOf<PendingScrollRequest?>(null)

        composeRule.setContent {
            ContentVersionScrollEffect(
                sessionId = sessionId.value,
                scroll = scroll,
                listState = listState,
                messages = messages.value,
                isStreaming = isStreaming.value,
                streamingPartTextKeys = streamingPartTextKeys.value,
                streamingReasoningPart = streamingReasoningPart.value,
                expandedParts = expandedParts.value,
                pendingScrollRequest = pendingScrollRequest.value,
            )
        }

        composeRule.waitForIdle()

        // streamingReasoningPart.id="r1", not expanded → follows bottom
        coVerify(atLeast = 1) { listState.animateScrollToItem(0) }
        clearMocks(listState)

        // Change to a different Part with different id → key changes → re-fire
        composeRule.runOnIdle {
            streamingReasoningPart.value = part("r2")
        }
        composeRule.waitForIdle()

        coVerify(atLeast = 1) {
            listState.animateScrollToItem(0)
        }
    }

    @Test
    fun `CVSE restoreInFlight guard prevents scroll when restore active`() {
        // Guard: scroll.pendingRestoreSession == sessionId → early return
        val listState = createListState()
        val pendingRestoreSessionState = mutableStateOf<String?>("s1")
        val scroll = ScrollController(
            listState = listState,
            followBottomState = mutableStateOf(true),
            navFabVisibleState = mutableStateOf(false),
            navFabTickState = mutableIntStateOf(0),
            navJumpingState = mutableStateOf(false),
            pendingRestoreSessionState = pendingRestoreSessionState,
        )

        val sessionId = mutableStateOf<String?>("s1")
        val messages = mutableStateOf(listOf(msg("m1")))
        val isStreaming = mutableStateOf(false)
        val streamingPartTextKeys = mutableStateOf(emptySet<String>())
        val streamingReasoningPart = mutableStateOf<Part?>(null)
        val expandedParts = mutableStateOf(emptyMap<String, Boolean>())
        val pendingScrollRequest = mutableStateOf<PendingScrollRequest?>(null)

        composeRule.setContent {
            ContentVersionScrollEffect(
                sessionId = sessionId.value,
                scroll = scroll,
                listState = listState,
                messages = messages.value,
                isStreaming = isStreaming.value,
                streamingPartTextKeys = streamingPartTextKeys.value,
                streamingReasoningPart = streamingReasoningPart.value,
                expandedParts = expandedParts.value,
                pendingScrollRequest = pendingScrollRequest.value,
            )
        }

        composeRule.waitForIdle()

        // restoreInFlight: pendingRestoreSession="s1" == sessionId="s1"
        // → effect returns before any scroll call
        coVerify(exactly = 0) { listState.animateScrollToItem(any()) }
        verify(exactly = 0) { listState.requestScrollToItem(any()) }
    }

    @Test
    fun `CVSE restorePending guard prevents scroll when Restore request targets this session`() {
        // Guard: pendingScrollRequest with Restore behavior targeting this
        // session → early return
        val scroll = createScrollController()
        val listState = createListState()

        val sessionId = mutableStateOf<String?>("s1")
        val messages = mutableStateOf(listOf(msg("m1")))
        val isStreaming = mutableStateOf(false)
        val streamingPartTextKeys = mutableStateOf(emptySet<String>())
        val streamingReasoningPart = mutableStateOf<Part?>(null)
        val expandedParts = mutableStateOf(emptyMap<String, Boolean>())
        val pendingScrollRequest = mutableStateOf<PendingScrollRequest?>(
            PendingScrollRequest(
                requestId = 30L,
                targetSessionId = "s1",
                behavior = ScrollBehavior.Restore(
                    cn.vectory.ocdroid.ui.ScrollCheckpoint(
                        anchorKey = null,
                        fallbackIndex = 0,
                        offset = 0,
                    ),
                ),
            ),
        )

        composeRule.setContent {
            ContentVersionScrollEffect(
                sessionId = sessionId.value,
                scroll = scroll,
                listState = listState,
                messages = messages.value,
                isStreaming = isStreaming.value,
                streamingPartTextKeys = streamingPartTextKeys.value,
                streamingReasoningPart = streamingReasoningPart.value,
                expandedParts = expandedParts.value,
                pendingScrollRequest = pendingScrollRequest.value,
            )
        }

        composeRule.waitForIdle()

        // restorePending: pendingScrollRequest targets "s1" with Restore behavior
        // → effect returns before any scroll call
        coVerify(exactly = 0) { listState.animateScrollToItem(any()) }
        verify(exactly = 0) { listState.requestScrollToItem(any()) }
    }

    @Test
    fun `CVSE sessionId dimension re-fires when session changes`() {
        // Pins: sessionId  (key tuple index 1 of 5)
        val scroll = createScrollController()
        val listState = createListState()

        val sessionId = mutableStateOf<String?>("s1")
        val messages = mutableStateOf(listOf(msg("m1")))
        val isStreaming = mutableStateOf(false)
        val streamingPartTextKeys = mutableStateOf(emptySet<String>())
        val streamingReasoningPart = mutableStateOf<Part?>(null)
        val expandedParts = mutableStateOf(emptyMap<String, Boolean>())
        val pendingScrollRequest = mutableStateOf<PendingScrollRequest?>(null)

        composeRule.setContent {
            ContentVersionScrollEffect(
                sessionId = sessionId.value,
                scroll = scroll,
                listState = listState,
                messages = messages.value,
                isStreaming = isStreaming.value,
                streamingPartTextKeys = streamingPartTextKeys.value,
                streamingReasoningPart = streamingReasoningPart.value,
                expandedParts = expandedParts.value,
                pendingScrollRequest = pendingScrollRequest.value,
            )
        }

        composeRule.waitForIdle()

        // Initial fire with sessionId="s1"
        coVerify(atLeast = 1) { listState.animateScrollToItem(0) }
        clearMocks(listState)

        // Switch sessionId to "s2" → key changes → re-fire
        composeRule.runOnIdle {
            sessionId.value = "s2"
        }
        composeRule.waitForIdle()

        coVerify(atLeast = 1) {
            listState.animateScrollToItem(0)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  § ReselectScrollEffect
    //  Key tuple: (orchestratorVM)
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `RSE orchestratorVM identity triggers re-fire on VM instance change`() {
        // Pins: orchestratorVM  (key tuple — the sole dimension)
        //
        // Phase 1: compose with VM1, emit NavRoute.Chat → effect fires
        // Phase 2: replace VM with a new instance (VM2), emit from new flow
        //          → LaunchedEffect restarts, collects from VM2's flow → re-fire

        val scroll = createScrollController()
        val listState = createListState()

        val reselectFlow1 = MutableSharedFlow<NavRoute>(
            replay = 0,
            extraBufferCapacity = 1,
        )
        val orchestratorVM1 = mockk<OrchestratorViewModel>(relaxed = true)
        every { orchestratorVM1.reselectFlow } returns reselectFlow1.asSharedFlow()

        val orchestratorVM = mutableStateOf<OrchestratorViewModel>(orchestratorVM1)

        composeRule.setContent {
            ReselectScrollEffect(
                orchestratorVM = orchestratorVM.value,
                scroll = scroll,
                listState = listState,
            )
        }

        composeRule.waitForIdle()

        // Emit NavRoute.Chat on VM1's flow → effect should fire
        composeRule.runOnIdle {
            reselectFlow1.tryEmit(NavRoute.Chat)
        }
        composeRule.waitForIdle()

        assertTrue("followBottom should become true after reselect emission",
            scroll.followBottom)
        assertFalse("navFabVisible should become false after reselect emission",
            scroll.navFabVisible)
        coVerify(atLeast = 1) { listState.scrollToItem(0) }

        // §fix: keep stubbings (answers) so phase 2's effect guard
        // `if (listState.layoutInfo.totalItemsCount > 0)` still resolves to 5
        // (not the relaxed-default 0 that would short-circuit scrollToItem).
        clearMocks(listState, answers = false, childMocks = false)
        // Reset scroll state so we can detect re-fire
        composeRule.runOnIdle {
            scroll.followBottom = false
            scroll.navFabVisible = true
        }
        composeRule.waitForIdle()

        // Phase 2: replace orchestratorVM with a new instance
        val reselectFlow2 = MutableSharedFlow<NavRoute>(
            replay = 0,
            extraBufferCapacity = 1,
        )
        val orchestratorVM2 = mockk<OrchestratorViewModel>(relaxed = true)
        every { orchestratorVM2.reselectFlow } returns reselectFlow2.asSharedFlow()

        composeRule.runOnIdle {
            orchestratorVM.value = orchestratorVM2
        }
        composeRule.waitForIdle()

        // Emit on VM2's flow (VM1's flow has no subscriber now)
        composeRule.runOnIdle {
            reselectFlow2.tryEmit(NavRoute.Chat)
        }
        composeRule.waitForIdle()

        assertTrue("followBottom should become true after VM2 reselect emission",
            scroll.followBottom)
        assertFalse("navFabVisible should become false after VM2 reselect emission",
            scroll.navFabVisible)
        coVerify(atLeast = 1) { listState.scrollToItem(0) }
    }
}
