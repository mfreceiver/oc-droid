package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionOption
import cn.vectory.ocdroid.data.model.QuestionRequest
import org.junit.Rule
import org.junit.Test

/**
 * P0 crash regression: the AnimatedContent inside [StatusSlot] used to NPE during
 * the Question→None exit transition. When a pending question was cleared on reply
 * success, the outer `question` parameter recomposed to null, priority dropped
 * Question→None, and AnimatedContent re-invoked the SHARED content lambda for the
 * EXITING Question branch — which still read `question!!.id` from the outer scope
 * (now null) and threw NPE.
 *
 * Fix (StatusSlot.kt §crash-fix): the AnimatedContent targetState now carries the
 * data ([StatusSlotContent.Question] wraps the QuestionRequest) so the exiting
 * branch reads the value captured when the state became active, not the recomposed-
 * to-null outer param.
 *
 * This test reproduces the transition (question set → question cleared) and asserts
 * the exit animation runs without throwing. If the regression returns, this test
 * fails with an NPE during the Question→None exit frame.
 */
class StatusSlotTransitionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun questionClearedToNone_exitTransition_doesNotThrowNpe() {
        // Minimal-but-valid QuestionRequest: one question with one option so
        // QuestionCardView's tab/option logic (which reads questions[0]) is in a
        // well-defined state during both the enter and exit frames.
        val question = QuestionRequest(
            id = "q-1",
            sessionId = "sess-1",
            questions = listOf(
                QuestionInfo(
                    question = "Continue?",
                    header = "Confirmation",
                    options = listOf(QuestionOption(label = "yes", description = "proceed")),
                    multiple = false,
                    custom = false,
                )
            ),
            tool = null,
        )

        var currentQuestion: QuestionRequest? by mutableStateOf(question)

        composeRule.mainClock.autoAdvance = true

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier) {
                        StatusSlot(
                            permission = null,
                            question = currentQuestion,
                            sessionStatus = null,
                            isCompacting = false,
                            currentActivityText = null,
                            currentActivityStartedAtMillis = null,
                            compactStartedAt = 0L,
                            isConnecting = false,
                            // §T17: lastError = null (this test pre-dates the
                            // LastError tier; the new param is required by the
                            // composable's contract).
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

        // Let the Question branch fully enter.
        composeRule.waitForIdle()

        // Simulate replyQuestion success: the caller filters the pending question
        // out, the outer `question` recomposes to null, priority drops Question→None.
        // Before the fix this is the frame that NPE'd.
        composeRule.runOnIdle { currentQuestion = null }

        // Drive the Question→None exit transition to completion.
        composeRule.waitForIdle()

        // No assertion: test passes iff no exception was thrown during the
        // transition. An NPE in the exit branch fails the test.
    }

    /**
     * Symmetric to [questionClearedToNone_exitTransition_doesNotThrowNpe] but for the
     * Permission→None branch. The Permission card reads `active.permission` during the
     * exit frame (StatusSlot §crash-fix), so this asserts the Permission exit also
     * runs without reading the recomposed-to-null outer param.
     */
    @Test
    fun permission_clearedDuringTransition_doesNotCrash() {
        // Minimal-but-valid PermissionRequest: id + sessionId are the only required
        // fields; the rest are nullable (defaults). ChatPermissionCard reads the id +
        // metadata-driven lines, all null-tolerant.
        val permission = PermissionRequest(
            id = "p-1",
            sessionId = "sess-1",
        )

        var currentPermission: PermissionRequest? by mutableStateOf(permission)

        composeRule.mainClock.autoAdvance = true

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier) {
                        StatusSlot(
                            permission = currentPermission,
                            question = null,
                            sessionStatus = null,
                            isCompacting = false,
                            currentActivityText = null,
                            currentActivityStartedAtMillis = null,
                            compactStartedAt = 0L,
                            isConnecting = false,
                            // §T17: lastError = null (this test pre-dates the
                            // LastError tier; the new param is required by the
                            // composable's contract).
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

        // Let the Permission branch fully enter.
        composeRule.waitForIdle()

        // Simulate the user responding → caller filters the pending permission out,
        // outer `permission` recomposes to null, priority drops Permission→None.
        composeRule.runOnIdle { currentPermission = null }

        // Drive the Permission→None exit transition to completion.
        composeRule.waitForIdle()

        // No assertion: passes iff no exception during the transition.
    }

    /**
     * Regression for the §oracle-fix duplicate-key crash: before adding
     * `contentKey = { it::class }` to the AnimatedContent, a SAME-variant data change
     * (q1 → q2 with the SAME id but different content) made the two StatusSlotContent
     * data-class instances unequal → AnimatedContent transitioned → BOTH the exiting
     * and entering Question children called
     * `saveableStateHolder.SaveableStateProvider(question.id)` with the SAME key
     * concurrently → SaveableStateHolder threw a duplicate-key IllegalStateException.
     *
     * With `contentKey = { it::class }`, the same-variant swap is an IN-PLACE update
     * (no transition → single child → single provider). This test asserts that path
     * does not crash.
     */
    @Test
    fun question_swapInPlace_doesNotCrash() {
        // q1 and q2 share the SAME id ("q-swap") but differ in question text. With
        // contentKey by data (pre-fix), Question(q1) != Question(q2) → transition →
        // duplicate SaveableStateProvider("q-swap") → crash. With contentKey by class,
        // both targetState values map to StatusSlotContent.Question::class → in-place.
        val q1 = QuestionRequest(
            id = "q-swap",
            sessionId = "sess-1",
            questions = listOf(
                QuestionInfo(
                    question = "Continue?",
                    header = "Confirmation",
                    options = listOf(QuestionOption(label = "yes", description = "proceed")),
                    multiple = false,
                    custom = false,
                )
            ),
            tool = null,
        )
        val q2 = QuestionRequest(
            id = "q-swap",
            sessionId = "sess-1",
            questions = listOf(
                QuestionInfo(
                    question = "Are you sure?", // different content, same id
                    header = "Confirmation",
                    options = listOf(
                        QuestionOption(label = "yes", description = "proceed"),
                        QuestionOption(label = "no", description = "abort"),
                    ),
                    multiple = false,
                    custom = false,
                )
            ),
            tool = null,
        )

        var currentQuestion: QuestionRequest? by mutableStateOf(q1)

        composeRule.mainClock.autoAdvance = true

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier) {
                        StatusSlot(
                            permission = null,
                            question = currentQuestion,
                            sessionStatus = null,
                            isCompacting = false,
                            currentActivityText = null,
                            currentActivityStartedAtMillis = null,
                            compactStartedAt = 0L,
                            isConnecting = false,
                            // §T17: lastError = null (this test pre-dates the
                            // LastError tier; the new param is required by the
                            // composable's contract).
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

        // Let q1 fully enter (SaveableStateProvider("q-swap") is now active).
        composeRule.waitForIdle()

        // Swap to q2 (same id, different content). Pre-fix this is the frame that
        // threw the duplicate-key IllegalStateException.
        composeRule.runOnIdle { currentQuestion = q2 }

        // Drive any recomposition / transition to quiescence.
        composeRule.waitForIdle()

        // No assertion: passes iff no exception during the same-variant swap.
    }
}
