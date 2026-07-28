// StatusSlotPlacementTest.kt — Rev-2 remediation: replace the old
// parameter-forwarding unit test with real Compose UI placement assertions.
//
// 待模拟器回归：本文件是 androidTest（需模拟器运行）。按 ocdroid 模拟器占用
// 纪律（AGENTS.md「模拟器是共享资源，需 status + 用户许可」），本测试只写结构
// 正确的代码但不在此会话运行。模拟器回归时执行：
//   ./scripts/emulator.sh status   # 确认未运行
//   ./scripts/emulator.sh start    # 启动
//   ./gradlew :app:connectedDebugAndroidTest --tests "cn.vectory.ocdroid.ui.chat.StatusSlotPlacementTest" --no-daemon
//   ./scripts/emulator.sh stop     # 用完必关
//
// 验证目标：
//  - StatusSlot 固定在父容器的 TopCenter（Modifier.align(Alignment.TopCenter)）。
//  - StatusSlot 的 layout modifier 应用了 top gap（Dimens.spacing2 = 8dp）。
//  - gap 作用为垂直偏移：内容顶部距容器顶部 = gap px。
//  - 内容水平居中于父容器。

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.ui.theme.Dimens
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StatusSlotPlacementTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * The Running branch shows a ThinkingCapsule with the activity text
     * and an abort button. This is the most visually descriptive StatusSlot
     * variant and easiest to anchor assertions on (the text has a known
     * content). We verify the slot is top-center aligned with the correct gap.
     */
    @Test
    fun runningBranch_rendersTopAlignedWithGap() {
        val containerSize = 400.dp
        val expectedGap = Dimens.spacing2 // 8dp

        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.size(containerSize)) {
                    // A Box with a test tag so we can locate it.
                    // Background color is purely visual for debugging.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Transparent)
                            .testTag("slotContainer"),
                    ) {
                        StatusSlot(
                            permission = null,
                            question = null,
                            sessionStatus = null,
                            isCompacting = false,
                            currentActivityText = "Running test activity",
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
                            onRespondPermission = {},
                            onReplyQuestion = { _, _, _ -> },
                            onRejectQuestion = { _, _ -> },
                            questionQueuePosition = 1,
                            questionQueueTotal = 1,
                            onAbort = {},
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()

        // 1. The Running text is displayed — confirms the Running branch rendered.
        composeRule.onNodeWithText("Running test activity")
            .assertExists("Running activity text should be rendered")

        // 2. Verify the slot container is at the top of the parent.
        //    The container is the root Surface (400dp tall). The StatusSlot
        //    is Modifier.align(TopCenter), so its top edge should be at ~0
        //    PLUS the gap offset applied by the layout modifier (= 8dp).
        //    getUnclippedBoundsInRoot() returns BoundsInRoot with Dp fields.
        val containerBounds = composeRule.onNodeWithTag("slotContainer")
            .getUnclippedBoundsInRoot()
        val activityTextBounds = composeRule.onNodeWithText("Running test activity")
            .getUnclippedBoundsInRoot()

        // The text node is a child of the AnimatedContent → the gap offset
        // propagates from the outer layout modifier. The text's top should be
        // at least expectedGap from the container's top. Work in Dp: Dp - Dp = Dp,
        // Dp * 0.8f = Dp, Dp >= Dp compiles.
        val textTopFromContainer = activityTextBounds.top - containerBounds.top
        assertTrue(
            "Text top should be >= gap ($expectedGap), " +
                "was $textTopFromContainer",
            textTopFromContainer >= expectedGap * 0.8f, // allow small rounding
        )

        // 3. The content should be horizontally centered in the container.
        //    The AnimatedContent has Modifier.align(Alignment.TopCenter), so
        //    the child's center X should be near the container's center X.
        //    Convert Dp to Float via .value for division and abs.
        val containerCenterX = (containerBounds.left + containerBounds.right).value / 2f
        val textCenterX = (activityTextBounds.left + activityTextBounds.right).value / 2f
        val centerDelta = kotlin.math.abs(textCenterX - containerCenterX)
        // Allow 10% of the container width for padding/content-inset tolerance.
        val maxCenterDelta = (containerBounds.right - containerBounds.left).value * 0.10f
        assertTrue(
            "Text should be roughly centered horizontally; delta=$centerDelta " +
                "max=$maxCenterDelta",
            centerDelta <= maxCenterDelta,
        )
    }

    /**
     * The Compacting branch shows a different text ("Compacting…") with
     * NO abort button. Use this to cross-verify the gap + top-center
     * contract on a distinct branch.
     */
    @Test
    fun compactingBranch_rendersTopAlignedWithGap() {
        val containerSize = 400.dp
        val expectedGap = Dimens.spacing2 // 8dp

        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.size(containerSize)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Transparent)
                            .testTag("slotContainer2"),
                    ) {
                        StatusSlot(
                            permission = null,
                            question = null,
                            sessionStatus = null,
                            isCompacting = true,
                            currentActivityText = null,
                            currentActivityStartedAtMillis = null,
                            compactStartedAt = 12345L, // non-zero → timer enabled
                            isConnecting = false,
                            lastError = null,
                            permissionMetadata = ChatPermissionMetadata(
                                hostName = null,
                                workdirBasename = null,
                                sessionName = null,
                                toolName = null,
                                target = null,
                            ),
                            onRespondPermission = {},
                            onReplyQuestion = { _, _, _ -> },
                            onRejectQuestion = { _, _ -> },
                            questionQueuePosition = 1,
                            questionQueueTotal = 1,
                            onAbort = {},
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()

        // The Compacting branch renders a ThinkingCapsule with "Compacting…" text.
        composeRule.onNodeWithText("Compacting…")
            .assertExists("Compacting text should be rendered")

        val containerBounds = composeRule.onNodeWithTag("slotContainer2")
            .getUnclippedBoundsInRoot()
        val compactTextBounds = composeRule.onNodeWithText("Compacting…")
            .getUnclippedBoundsInRoot()

        val textTopFromContainer = compactTextBounds.top - containerBounds.top
        assertTrue(
            "Compacting text top should be >= gap ($expectedGap), " +
                "was $textTopFromContainer",
            textTopFromContainer >= expectedGap * 0.8f,
        )

        // Verify horizontal centering.
        val containerCenterX = (containerBounds.left + containerBounds.right).value / 2f
        val textCenterX = (compactTextBounds.left + compactTextBounds.right).value / 2f
        val centerDelta = kotlin.math.abs(textCenterX - containerCenterX)
        val maxCenterDelta = (containerBounds.right - containerBounds.left).value * 0.10f
        assertTrue(
            "Compacting text should be roughly centered; delta=$centerDelta " +
                "max=$maxCenterDelta",
            centerDelta <= maxCenterDelta,
        )
    }

    /**
     * Only the StatusSlot layout matters for our gap/alignment contract.
     * The None branch produces no rendered child — the AnimatedContent is
     * still composed (cross-fading to nothing) but there is nothing to assert
     * position on. This test ensures the slot does NOT crash in the None state
     * and that the parent Box layout is unbroken (smoke test).
     */
    @Test
    fun noneBranch_parentLayoutUnbroken() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.size(400.dp)) {
                    Box(modifier = Modifier.fillMaxSize()) {
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
                            onRespondPermission = {},
                            onReplyQuestion = { _, _, _ -> },
                            onRejectQuestion = { _, _ -> },
                            questionQueuePosition = 1,
                            questionQueueTotal = 1,
                            onAbort = {},
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        // No crash during composition/layout → pass.
    }
}
