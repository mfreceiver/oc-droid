package cn.vectory.ocdroid.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PartState
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.RevertCutoff
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.filterBeforeRevert
import cn.vectory.ocdroid.ui.injectMetadataMarkers
import cn.vectory.ocdroid.ui.isStaleQuestionPart
import cn.vectory.ocdroid.ui.isStaleRunningPart
import cn.vectory.ocdroid.ui.METADATA_MARKER_ROLES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §remember-key regression tests for [cn.vectory.ocdroid.ui.chat.ChatMessageContent].
 *
 * Reference-contract pinning of the key-tuple contracts for three [remember]
 * blocks inside [ChatMessageList] that had zero test coverage despite complex
 * key dependencies.
 *
 * NOTE (rev-ds §correctness YELLOW): these tests drive a SELF-AUTHORED REPLICA
 * of each remember block (same key tuple + the REAL pure helpers
 * filterBeforeRevert / injectMetadataMarkers / computeFilteredReversedMessages
 * / isStaleQuestionPart / isStaleRunningPart), NOT the production
 * [ChatMessageList] composable itself. The counter-inside-remember mechanism
 * is sound (remember bodies re-execute only on key change — proven by the
 * negative tests at the bottom of each section), so each replica genuinely
 * pins its key-tuple SEMANTICS and exercises the pure chain. BUT a source-side
 * key-tuple change in ChatMessageContent.kt would NOT fail these tests unless
 * this replica is kept in sync.
 *
 * To close the source-drift gap: extract the 3 remember blocks into internal
 * @Composable testable functions and test the real ones (like
 * ChatRenderPipelineComposeTest tests rememberRenderPipeline directly). Until
 * then, treat this file as reference-contract documentation + pure-chain
 * exercise, NOT automated source-drift detection.
 *
 * Strategy: a counter INSIDE the remember body increments only when the keys
 * cause re-execution. Mutating a key dimension must increment the counter;
 * mutating a non-key dimension must NOT.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatMessageContentRememberTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── Test fixtures ─────────────────────────────────────────────────────────

    private fun msg(id: String, role: String = "user"): Message = Message(id = id, role = role)

    private fun part(id: String, type: String = "text"): Part = Part(id = id, type = type)

    private fun questionPart(
        id: String,
        messageId: String = "m-q",
        callId: String = "call-q",
    ): Part = Part(
        id = id,
        type = "tool",
        tool = "question",
        state = PartState("running"),
        messageId = messageId,
        callId = callId,
    )

    /** A [QuestionRequest] that matches [questionPart] via ToolRef. */
    private fun matchingRequest(
        messageId: String = "m-q",
        callId: String = "call-q",
    ): QuestionRequest = QuestionRequest(
        id = "qr-1",
        sessionId = "s1",
        questions = emptyList(),
        tool = QuestionRequest.ToolRef(messageId = messageId, callId = callId),
    )

    // ─────────────────────────────────────────────────────────────────────────
    // §1: messages remember(loadedMessages, revertMessageId, currentCutoff)
    //
    // Source: line ~114, keys=(loadedMessages, revertMessageId, currentCutoff)
    // Body:  filterBeforeRevert + filter tool/system + injectMetadataMarkers.
    //        ALL inputs are key dimensions — there are no non-key parameters.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `messages remember recomputes when loadedMessages changes`() {
        val loadedState = mutableStateOf(listOf(msg("m1"), msg("m2")))
        var recomputeCount = 0

        composeRule.setContent {
            val ctr = remember { intArrayOf(0) }
            remember(loadedState.value, null as String?, null as RevertCutoff?) {
                ctr[0]++
                val reverted = loadedState.value.filterBeforeRevert(null, null)
                val visible = reverted.filter { !it.isToolRole || it.role in METADATA_MARKER_ROLES }
                injectMetadataMarkers(visible)
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose: remember body ran once", 1, recomputeCount)
        }

        loadedState.value = listOf(msg("m1"), msg("m2"), msg("m3"))

        composeRule.runOnIdle {
            assertTrue(
                "recomputeCount should increase when loadedMessages is mutated: " +
                    "if this fails, loadedMessages dimension is MISSING from the key tuple — " +
                    "a new message batch would render stale content",
                recomputeCount >= 2,
            )
        }
    }

    @Test
    fun `messages remember recomputes when revertMessageId changes`() {
        val msgs = listOf(msg("m-before", "user"), msg("m-revert", "assistant"))
        val revertIdState = mutableStateOf<String?>(null)
        var recomputeCount = 0

        composeRule.setContent {
            val ctr = remember { intArrayOf(0) }
            remember(msgs, revertIdState.value, null as RevertCutoff?) {
                ctr[0]++
                val reverted = msgs.filterBeforeRevert(revertIdState.value, null)
                val visible = reverted.filter { !it.isToolRole || it.role in METADATA_MARKER_ROLES }
                injectMetadataMarkers(visible)
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        // Set a revertMessageId — key dimension changes
        revertIdState.value = "m-revert"

        composeRule.runOnIdle {
            assertTrue(
                "recomputeCount should increase when revertMessageId is set: " +
                    "if this fails, revertMessageId dimension is MISSING from the key tuple — " +
                    "a revert point change would show a stale transcript",
                recomputeCount >= 2,
            )
        }
    }

    @Test
    fun `messages remember recomputes when currentCutoff changes`() {
        val msgs = listOf(msg("m1", "user"), msg("m2", "assistant"))
        val cutoffState = mutableStateOf<RevertCutoff?>(null)
        var recomputeCount = 0

        composeRule.setContent {
            val ctr = remember { intArrayOf(0) }
            remember(msgs, null as String?, cutoffState.value) {
                ctr[0]++
                val reverted = msgs.filterBeforeRevert(null, cutoffState.value)
                val visible = reverted.filter { !it.isToolRole || it.role in METADATA_MARKER_ROLES }
                injectMetadataMarkers(visible)
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        // Change currentCutoff — key dimension changes
        cutoffState.value = RevertCutoff(
            sessionId = "s1",
            messageId = "m1",
            state = RevertCutoffState.Resolved(createdAtEpochMs = 1000L),
        )

        composeRule.runOnIdle {
            assertTrue(
                "recomputeCount should increase when currentCutoff changes: " +
                    "if this fails, currentCutoff dimension is MISSING from the key tuple — " +
                    "a new revert-cutoff policy would not truncate the transcript",
                recomputeCount >= 2,
            )
        }
    }

    @Test
    fun `messages remember does NOT recompute on unrelated state change`() {
        // All three parameters are keys, so the negative test uses a
        // non-key state declared alongside the remember (read in the
        // composable but NOT in the key tuple).
        val loadedState = mutableStateOf(listOf(msg("m1"), msg("m2")))
        val nonKeyTrigger = mutableStateOf(0)
        var recomputeCount = 0

        composeRule.setContent {
            val ctr = remember { intArrayOf(0) }
            remember(loadedState.value, null as String?, null as RevertCutoff?) {
                ctr[0]++
                val reverted = loadedState.value.filterBeforeRevert(null, null)
                val visible = reverted.filter { !it.isToolRole || it.role in METADATA_MARKER_ROLES }
                injectMetadataMarkers(visible)
            }
            recomputeCount = ctr[0]
            // Read nonKeyTrigger to force recomposition when it changes
            @Suppress("UNUSED_VARIABLE")
            val triggerValue = nonKeyTrigger.value
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        // Mutate a non-key state — this triggers recomposition of the
        // outer composable but should NOT re-execute the remember body.
        nonKeyTrigger.value = 1

        composeRule.runOnIdle {
            assertEquals(
                "recomputeCount must NOT increase on unrelated state change: " +
                    "if this fails, there is an unintended key dependency — " +
                    "the remember body is re-executing when only a non-key state changed",
                1,
                recomputeCount,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // §2: reversedMessages remember(messages, partsByMessage,
    //                                streamingPartTexts.keys,
    //                                streamingReasoningPart, sessionIsRunning)
    //
    // Source: line ~192. Uses Set equality for streamingPartTexts.keys
    // (the Map's KeySet view). ALL inputs are key dimensions.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `reversedMessages remember recomputes when messages changes`() {
        val msgsState = mutableStateOf(listOf(msg("m1", "user"), msg("m2", "assistant")))
        val parts = mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2")))
        var recomputeCount = 0

        composeRule.setContent {
            val ctr = remember { intArrayOf(0) }
            remember(msgsState.value, parts, emptySet<String>(), null as Part?, false) {
                ctr[0]++
                computeFilteredReversedMessages(
                    messages = msgsState.value,
                    partsByMessage = parts,
                    streamingPartTextKeys = emptySet(),
                    streamingReasoningPart = null,
                    sessionIsRunning = false,
                )
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        msgsState.value = listOf(msg("m1", "user"), msg("m2", "assistant"), msg("m3", "user"))

        composeRule.runOnIdle {
            assertTrue(
                "recomputeCount should increase when messages changes: " +
                    "if this fails, messages dimension is MISSING — a new turn " +
                    "would not appear in the reversed list",
                recomputeCount >= 2,
            )
        }
    }

    @Test
    fun `reversedMessages remember recomputes when partsByMessage changes`() {
        val msgs = listOf(msg("m1", "user"), msg("m2", "assistant"))
        val partsState = mutableStateOf(mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2"))))
        var recomputeCount = 0

        composeRule.setContent {
            val ctr = remember { intArrayOf(0) }
            remember(msgs, partsState.value, emptySet<String>(), null as Part?, false) {
                ctr[0]++
                computeFilteredReversedMessages(
                    messages = msgs,
                    partsByMessage = partsState.value,
                    streamingPartTextKeys = emptySet(),
                    streamingReasoningPart = null,
                    sessionIsRunning = false,
                )
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        // Add a new part for m2
        partsState.value = mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2"), part("p3")))

        composeRule.runOnIdle {
            assertTrue(
                "recomputeCount should increase when partsByMessage changes: " +
                    "if this fails, partsByMessage dimension is MISSING — " +
                    "a message with newly arrived parts would render empty",
                recomputeCount >= 2,
            )
        }
    }

    @Test
    fun `reversedMessages remember recomputes when streamingReasoningPart changes`() {
        val msgs = listOf(msg("m1", "user"), msg("m2", "assistant"))
        val parts = mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2")))
        val srState = mutableStateOf<Part?>(null)
        var recomputeCount = 0

        composeRule.setContent {
            val ctr = remember { intArrayOf(0) }
            remember(msgs, parts, emptySet<String>(), srState.value, true) {
                ctr[0]++
                computeFilteredReversedMessages(
                    messages = msgs,
                    partsByMessage = parts,
                    streamingPartTextKeys = emptySet(),
                    streamingReasoningPart = srState.value,
                    sessionIsRunning = true,
                )
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        srState.value = Part(id = "sr-1", type = "reasoning", messageId = "m2")

        composeRule.runOnIdle {
            assertTrue(
                "recomputeCount should increase when streamingReasoningPart changes: " +
                    "if this fails, streamingReasoningPart dimension is MISSING — " +
                    "a newly arriving reasoning part would not affect filtering",
                recomputeCount >= 2,
            )
        }
    }

    @Test
    fun `reversedMessages remember recomputes when sessionIsRunning changes`() {
        val msgs = listOf(msg("m1", "user"), msg("m2", "assistant"))
        val parts = mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2")))
        val runningState = mutableStateOf(false)
        var recomputeCount = 0

        composeRule.setContent {
            val ctr = remember { intArrayOf(0) }
            remember(msgs, parts, emptySet<String>(), null as Part?, runningState.value) {
                ctr[0]++
                computeFilteredReversedMessages(
                    messages = msgs,
                    partsByMessage = parts,
                    streamingPartTextKeys = emptySet(),
                    streamingReasoningPart = null,
                    sessionIsRunning = runningState.value,
                )
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        runningState.value = true

        composeRule.runOnIdle {
            assertTrue(
                "recomputeCount should increase when sessionIsRunning changes: " +
                    "if this fails, sessionIsRunning dimension is MISSING — " +
                    "a session transition from idle to busy would not update filtering",
                recomputeCount >= 2,
            )
        }
    }

    @Test
    fun `reversedMessages remember does NOT recompute when streamingPartTexts keys are set-equal`() {
        // Oracle-flagged (M3 set-equality key): streamingPartTexts.keys is a Set.
        // Replacing the map with a different instance that has the SAME key set
        // must NOT re-execute the remember body.
        val msgs = listOf(msg("m1", "user"), msg("m2", "assistant"))
        val parts = mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2")))
        val textsState = mutableStateOf(mapOf("p2" to "initial"))
        var recomputeCount = 0

        composeRule.setContent {
            val ctr = remember { intArrayOf(0) }
            remember(msgs, parts, textsState.value.keys, null as Part?, false) {
                ctr[0]++
                computeFilteredReversedMessages(
                    messages = msgs,
                    partsByMessage = parts,
                    streamingPartTextKeys = textsState.value.keys,
                    streamingReasoningPart = null,
                    sessionIsRunning = false,
                )
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        // Replace the map with a DIFFERENT instance but SAME key set
        textsState.value = mapOf("p2" to "different value, same key")

        composeRule.runOnIdle {
            assertEquals(
                "recomputeCount must NOT increase when streamingPartTexts keys are set-equal " +
                    "(same keys, different map instance): if this fails, the key tuple uses " +
                    "the full Map instead of Set equality — a token delta (value change, same key) " +
                    "would wastefully recompute reversedMessages. See oracle flag \"M3 set-equality key\".",
                1,
                recomputeCount,
            )
        }
    }

    @Test
    fun `reversedMessages remember recomputes when streamingPartTexts gains a new key`() {
        val msgs = listOf(msg("m1", "user"), msg("m2", "assistant"))
        val parts = mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2")))
        val textsState = mutableStateOf(mapOf("p2" to "initial"))
        var recomputeCount = 0

        composeRule.setContent {
            val ctr = remember { intArrayOf(0) }
            remember(msgs, parts, textsState.value.keys, null as Part?, false) {
                ctr[0]++
                computeFilteredReversedMessages(
                    messages = msgs,
                    partsByMessage = parts,
                    streamingPartTextKeys = textsState.value.keys,
                    streamingReasoningPart = null,
                    sessionIsRunning = false,
                )
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        // Add a new key to the map
        textsState.value = mapOf("p2" to "initial", "p3" to "new streaming part")

        composeRule.runOnIdle {
            assertTrue(
                "recomputeCount should increase when streamingPartTexts gains a new key: " +
                    "if this fails, new streaming keys do NOT trigger recomputation — " +
                    "a newly streaming message would be missing from reversedMessages",
                recomputeCount >= 2,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // §3: staleQuestionPartKeys remember(partsByMessage, pendingQuestions,
    //                                     currentSessionStatus,
    //                                     questionGraceTick.intValue)
    //
    // Source: line ~149. Has a side-effect on questionRunningSince map
    // (mutableStateMapOf). ALL inputs are key dimensions.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `staleKeys remember recomputes when partsByMessage changes`() {
        val qp = questionPart(id = "qp1")
        val partsState = mutableStateOf(mapOf("m-q" to listOf(qp)))
        val pending = listOf(matchingRequest())
        var recomputeCount = 0

        composeRule.setContent {
            val questionRunningSince = remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }
            val ctr = remember { intArrayOf(0) }
            remember(partsState.value, pending, null as SessionStatus?, 0) {
                ctr[0]++
                val now = System.currentTimeMillis()
                val liveQuestionCandidates = HashSet<String>()
                val keys = HashSet<String>()
                for (part in partsState.value.values.flatten()) {
                    if (isStaleQuestionPart(part, pending)) {
                        liveQuestionCandidates.add(part.id)
                        questionRunningSince.putIfAbsent(part.id, now)
                    }
                    if (isStaleRunningPart(part, pending, null, now, questionRunningSince[part.id])) {
                        keys.add(part.id)
                    }
                }
                questionRunningSince.keys.retainAll(liveQuestionCandidates)
                keys
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        // Change partsByMessage — key dimension changes
        partsState.value = mapOf("m-q" to listOf(qp, questionPart(id = "qp2", messageId = "m-q", callId = "call-q2")))

        composeRule.runOnIdle {
            assertTrue(
                "recomputeCount should increase when partsByMessage changes: " +
                    "if this fails, partsByMessage dimension is MISSING — " +
                    "newly arrived parts would not be evaluated for stale-question status",
                recomputeCount >= 2,
            )
        }
    }

    @Test
    fun `staleKeys remember recomputes when pendingQuestions changes`() {
        val qp = questionPart(id = "qp1")
        val parts = mapOf("m-q" to listOf(qp))
        val pendingState = mutableStateOf(listOf(matchingRequest()))
        var recomputeCount = 0

        composeRule.setContent {
            val questionRunningSince = remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }
            val ctr = remember { intArrayOf(0) }
            remember(parts, pendingState.value, null as SessionStatus?, 0) {
                ctr[0]++
                val now = System.currentTimeMillis()
                val liveQuestionCandidates = HashSet<String>()
                val keys = HashSet<String>()
                for (part in parts.values.flatten()) {
                    if (isStaleQuestionPart(part, pendingState.value)) {
                        liveQuestionCandidates.add(part.id)
                        questionRunningSince.putIfAbsent(part.id, now)
                    }
                    if (isStaleRunningPart(part, pendingState.value, null, now, questionRunningSince[part.id])) {
                        keys.add(part.id)
                    }
                }
                questionRunningSince.keys.retainAll(liveQuestionCandidates)
                keys
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        // Remove the matching request — now the question part IS stale
        pendingState.value = emptyList()

        composeRule.runOnIdle {
            assertTrue(
                "recomputeCount should increase when pendingQuestions changes: " +
                    "if this fails, pendingQuestions dimension is MISSING — " +
                    "resolved question requests would not update stale detection",
                recomputeCount >= 2,
            )
        }
    }

    @Test
    fun `staleKeys remember recomputes when currentSessionStatus changes`() {
        val qp = questionPart(id = "qp1")
        val parts = mapOf("m-q" to listOf(qp))
        val pending = listOf(matchingRequest())
        val statusState = mutableStateOf<SessionStatus?>(null)
        var recomputeCount = 0

        composeRule.setContent {
            val questionRunningSince = remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }
            val ctr = remember { intArrayOf(0) }
            remember(parts, pending, statusState.value, 0) {
                ctr[0]++
                val now = System.currentTimeMillis()
                val liveQuestionCandidates = HashSet<String>()
                val keys = HashSet<String>()
                for (part in parts.values.flatten()) {
                    if (isStaleQuestionPart(part, pending)) {
                        liveQuestionCandidates.add(part.id)
                        questionRunningSince.putIfAbsent(part.id, now)
                    }
                    if (isStaleRunningPart(part, pending, statusState.value, now, questionRunningSince[part.id])) {
                        keys.add(part.id)
                    }
                }
                questionRunningSince.keys.retainAll(liveQuestionCandidates)
                keys
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        // Change session status — key dimension changes
        statusState.value = SessionStatus(type = "idle")

        composeRule.runOnIdle {
            assertTrue(
                "recomputeCount should increase when currentSessionStatus changes: " +
                    "if this fails, currentSessionStatus dimension is MISSING — " +
                    "a session becoming idle would not trigger stale-question transitions",
                recomputeCount >= 2,
            )
        }
    }

    @Test
    fun `staleKeys remember recomputes when questionGraceTick intValue changes`() {
        // This is the hardest key dimension: questionGraceTick.intValue drives
        // timer-based recomposition. We simulate it with a mutableIntStateOf.
        val qp = questionPart(id = "qp1")
        val parts = mapOf("m-q" to listOf(qp))
        val pending = listOf(matchingRequest())
        val tickState = mutableIntStateOf(0)
        var recomputeCount = 0

        composeRule.setContent {
            val questionRunningSince = remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }
            val ctr = remember { intArrayOf(0) }
            remember(parts, pending, null as SessionStatus?, tickState.intValue) {
                ctr[0]++
                val now = System.currentTimeMillis()
                val liveQuestionCandidates = HashSet<String>()
                val keys = HashSet<String>()
                for (part in parts.values.flatten()) {
                    if (isStaleQuestionPart(part, pending)) {
                        liveQuestionCandidates.add(part.id)
                        questionRunningSince.putIfAbsent(part.id, now)
                    }
                    if (isStaleRunningPart(part, pending, null, now, questionRunningSince[part.id])) {
                        keys.add(part.id)
                    }
                }
                questionRunningSince.keys.retainAll(liveQuestionCandidates)
                keys
            }
            recomputeCount = ctr[0]
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        // Advance the tick — simulates the LaunchedEffect timer increment
        tickState.intValue = 1

        composeRule.runOnIdle {
            assertTrue(
                "recomputeCount should increase when questionGraceTick.intValue changes: " +
                    "if this fails, questionGraceTick dimension is MISSING — " +
                    "the 1-second timer driving stale-question transitions would be dead; " +
                    "parts would never transition to 'interrupted' state",
                recomputeCount >= 2,
            )
        }
    }

    @Test
    fun `staleKeys remember does NOT recompute on unrelated state change`() {
        // All parameters are keys, so we use a non-key trigger outside the key tuple.
        val qp = questionPart(id = "qp1")
        val parts = mapOf("m-q" to listOf(qp))
        val pending = listOf(matchingRequest())
        val nonKeyTrigger = mutableStateOf(0)
        var recomputeCount = 0

        composeRule.setContent {
            val questionRunningSince = remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }
            val ctr = remember { intArrayOf(0) }
            remember(parts, pending, null as SessionStatus?, 0) {
                ctr[0]++
                val now = System.currentTimeMillis()
                val liveQuestionCandidates = HashSet<String>()
                val keys = HashSet<String>()
                for (part in parts.values.flatten()) {
                    if (isStaleQuestionPart(part, pending)) {
                        liveQuestionCandidates.add(part.id)
                        questionRunningSince.putIfAbsent(part.id, now)
                    }
                    if (isStaleRunningPart(part, pending, null, now, questionRunningSince[part.id])) {
                        keys.add(part.id)
                    }
                }
                questionRunningSince.keys.retainAll(liveQuestionCandidates)
                keys
            }
            recomputeCount = ctr[0]
            // Force recomposition on non-key change
            @Suppress("UNUSED_VARIABLE")
            val triggerValue = nonKeyTrigger.value
        }

        composeRule.runOnIdle {
            assertEquals("initial compose", 1, recomputeCount)
        }

        nonKeyTrigger.value = 1

        composeRule.runOnIdle {
            assertEquals(
                "recomputeCount must NOT increase on unrelated state change: " +
                    "if this fails, the staleKeys remember is re-executing on every " +
                    "recomposition instead of only on key changes — wasting CPU",
                1,
                recomputeCount,
            )
        }
    }
}
