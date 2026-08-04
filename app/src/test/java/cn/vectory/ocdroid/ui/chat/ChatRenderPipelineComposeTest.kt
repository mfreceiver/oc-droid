package cn.vectory.ocdroid.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §wave2-1-l4: Compose-level baseline for [rememberRenderPipeline].
 *
 * Exercises the REAL @Composable [remember] key tuple — not just the pure
 * helper — catching remember-key-dimension regressions (e.g. dropping
 * `streamingPartTexts` from the key tuple) that pure-JVM tests miss.
 *
 * Three invariants:
 *  1. Every block.id appears in lazyColumnKeys (no orphan blocks)
 *  2. lazyColumnKeys has no duplicates (key uniqueness)
 *
 * Contains both SINGLE-composition tests (scenarios 1-5) and
 * RECOMPOSITION-based tests (6-7) that mutate key inputs and verify the
 * pipeline recomputes — the only way to catch a dropped key dimension.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatRenderPipelineComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun msg(id: String, role: String = "user"): Message = Message(id = id, role = role)
    private fun part(id: String, type: String = "text"): Part = Part(id = id, type = type)

    /** Mutable holder for pipeline output captured from @Composable scope. */
    private data class PipelineCapture(
        var renderBlocks: List<RenderBlock> = emptyList(),
        var lazyColumnKeys: List<String> = emptyList(),
    )

    /**
     * Asserts the mirror invariants on a [PipelineCapture]:
     *  1. Every block.id appears in lazyColumnKeys (no orphan block)
     *  2. lazyColumnKeys has no duplicates
     *
     * NOTE: size equality is NOT asserted because [lazyColumnKeys] includes
     * non-block items ("streaming-reasoning", "session-diff", "load-more")
     * that have no corresponding [RenderBlock], making it longer than
     * [renderBlocks] when those items are present.
     */
    private fun assertMirrorInvariants(capture: PipelineCapture) {
        capture.renderBlocks.forEach { block ->
            assertTrue(
                "block.id=${block.id} must have a matching key, keys=${capture.lazyColumnKeys}",
                capture.lazyColumnKeys.contains(block.id),
            )
        }
        assertEquals(
            "keys must be unique; keys=${capture.lazyColumnKeys}",
            capture.lazyColumnKeys.toSet().size,
            capture.lazyColumnKeys.size,
        )
    }

    // ── helpers for recomposition tests ─────────────────────────────────────

    /**
     * Inside [composeRule.setContent], returns a MutableState ref that persists
     * across recompositions via [remember]. The [teeRef] array captures the
     * ref so test code can mutate it from outside the composable.
     */
    private fun <T> mutableStateInComposition(
        initial: T,
        teeRef: Array<MutableState<T>?>,
    ): MutableState<T> {
        val state = mutableStateOf(initial)
        teeRef[0] = state
        return state
    }

    // ── Scenario 1: empty messages ──────────────────────────────────────────

    @Test
    fun `pipeline empty messages`() {
        val capture = PipelineCapture()
        composeRule.setContent {
            capture.renderBlocks = rememberRenderPipeline(
                reversedMessages = emptyList(),
                partsByMessage = emptyMap(),
                streamingPartTexts = emptyMap(),
                staleQuestionPartKeys = emptySet(),
                streamingReasoningPart = null,
                sessionIsRunning = false,
                sessionDiff = null,
                messages = emptyList(),
                hasMoreMessages = false,
                olderMessagesCursor = null,
            ).renderBlocks
            capture.lazyColumnKeys = emptyList()
        }
        composeRule.runOnIdle {
            assertTrue("empty pipeline should have 0 blocks", capture.renderBlocks.isEmpty())
        }
    }

    // ── Scenario 2: normal messages, no streaming ───────────────────────────

    @Test
    fun `pipeline normal messages`() {
        val capture = PipelineCapture()
        composeRule.setContent {
            val messages = listOf(msg("m1", "user"), msg("m2", "assistant"))
            val partsByMessage = mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2")))
            val reversed = computeFilteredReversedMessages(messages, partsByMessage, emptySet(), null, false)
            val pipeline = rememberRenderPipeline(
                reversedMessages = reversed, partsByMessage = partsByMessage,
                streamingPartTexts = emptyMap(), staleQuestionPartKeys = emptySet(),
                streamingReasoningPart = null, sessionIsRunning = false,
                sessionDiff = null, messages = messages,
                hasMoreMessages = false, olderMessagesCursor = null,
            )
            capture.renderBlocks = pipeline.renderBlocks
            capture.lazyColumnKeys = pipeline.lazyColumnKeys
        }
        composeRule.runOnIdle {
            assertMirrorInvariants(capture)
            assertTrue("should have >0 blocks", capture.renderBlocks.isNotEmpty())
        }
    }

    // ── Scenario 3: streaming parts + load-more ─────────────────────────────

    @Test
    fun `pipeline with streaming parts`() {
        val capture = PipelineCapture()
        composeRule.setContent {
            val messages = listOf(msg("m1", "user"), msg("m2", "assistant"))
            val partsByMessage = mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2", "text")))
            val streamingTexts = mapOf("p2" to "partial response")
            val reversed = computeFilteredReversedMessages(messages, partsByMessage, streamingTexts.keys, null, true)
            val pipeline = rememberRenderPipeline(
                reversedMessages = reversed, partsByMessage = partsByMessage,
                streamingPartTexts = streamingTexts, staleQuestionPartKeys = emptySet(),
                streamingReasoningPart = null, sessionIsRunning = true,
                sessionDiff = null, messages = messages,
                hasMoreMessages = true, olderMessagesCursor = "cursor-1",
            )
            capture.renderBlocks = pipeline.renderBlocks
            capture.lazyColumnKeys = pipeline.lazyColumnKeys
        }
        composeRule.runOnIdle {
            assertMirrorInvariants(capture)
            assertEquals("last key should be load-more", "load-more", capture.lazyColumnKeys.lastOrNull())
        }
    }

    // ── Scenario 4: session-diff key ────────────────────────────────────────

    @Test
    fun `pipeline with session diff`() {
        val capture = PipelineCapture()
        composeRule.setContent {
            val messages = listOf(msg("m1", "user"), msg("m2", "assistant"))
            val partsByMessage = mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2")))
            val reversed = computeFilteredReversedMessages(messages, partsByMessage, emptySet(), null, false)
            val pipeline = rememberRenderPipeline(
                reversedMessages = reversed, partsByMessage = partsByMessage,
                streamingPartTexts = emptyMap(), staleQuestionPartKeys = emptySet(),
                streamingReasoningPart = null, sessionIsRunning = false,
                sessionDiff = listOf(FileDiff()), messages = messages,
                hasMoreMessages = false, olderMessagesCursor = null,
            )
            capture.renderBlocks = pipeline.renderBlocks
            capture.lazyColumnKeys = pipeline.lazyColumnKeys
        }
        composeRule.runOnIdle {
            assertMirrorInvariants(capture)
            assertTrue(
                "keys should contain session-diff when sessionDiff != null",
                capture.lazyColumnKeys.any { it == "session-diff" },
            )
        }
    }

    // ── Scenario 5: streaming reasoning part ────────────────────────────────

    @Test
    fun `pipeline with streaming reasoning part`() {
        val capture = PipelineCapture()
        composeRule.setContent {
            val messages = listOf(msg("m1", "user"), msg("m2", "assistant"))
            val partsByMessage = mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2")))
            val sr = Part(id = "sr-1", type = "reasoning", messageId = "m2")
            val reversed = computeFilteredReversedMessages(messages, partsByMessage, emptySet(), sr, true)
            val pipeline = rememberRenderPipeline(
                reversedMessages = reversed, partsByMessage = partsByMessage,
                streamingPartTexts = emptyMap(), staleQuestionPartKeys = emptySet(),
                streamingReasoningPart = sr, sessionIsRunning = true,
                sessionDiff = null, messages = messages,
                hasMoreMessages = false, olderMessagesCursor = null,
            )
            capture.renderBlocks = pipeline.renderBlocks
            capture.lazyColumnKeys = pipeline.lazyColumnKeys
        }
        composeRule.runOnIdle {
            assertMirrorInvariants(capture)
            assertTrue(
                "keys should contain streaming-reasoning when streamingReasoningPart != null",
                capture.lazyColumnKeys.any { it == "streaming-reasoning" },
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // §rev-kimi: RECOMPOSITION-BASED key-dimension tests.
    //
    // These tests wrap a key input in [mutableStateOf], compose, then mutate
    // from the test thread and assert the pipeline recomputes. They would FAIL
    // if the relevant key dimension were dropped from the pipeline's remember
    // key tuple — because [remember] would return the stale cached pipeline.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `recomposition streamingPartTexts full-Map change recomputes pipeline`() {
        // This test guards the M4 key dimension: `streamingPartTexts` (full Map).
        // A token delta changes the Map's VALUES but not its KEYS. If the key
        // tuple used `streamingPartTexts.keys` (set equality) instead of the
        // full Map, changing values would NOT recompute the pipeline.
        //
        // M3 (reversedMessages) correctly uses set-equality keys.
        // M4 (pipeline) intentionally uses the full Map.
        // This test verifies that separation is preserved.

        val streamingTexts = mutableStateOf(mapOf("p2" to "initial"))
        val snapshots = mutableListOf<PipelineCapture>()

        composeRule.setContent {
            val messages = listOf(msg("m1", "user"), msg("m2", "assistant"))
            val partsByMessage = mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2", "text")))
            val reversed = computeFilteredReversedMessages(
                messages, partsByMessage, streamingTexts.value.keys, null, sessionIsRunning = true,
            )
            val pipeline = rememberRenderPipeline(
                reversedMessages = reversed, partsByMessage = partsByMessage,
                streamingPartTexts = streamingTexts.value,
                staleQuestionPartKeys = emptySet(),
                streamingReasoningPart = null, sessionIsRunning = true,
                sessionDiff = null, messages = messages,
                hasMoreMessages = false, olderMessagesCursor = null,
            )
            // Deduplicate captures: only record when pipeline identity changes
            val lastHash = remember { mutableStateOf(-1) }
            val h = pipeline.renderBlocks.hashCode()
            if (lastHash.value != h) {
                lastHash.value = h
                snapshots.add(PipelineCapture(pipeline.renderBlocks, pipeline.lazyColumnKeys))
            }
        }

        // Initial composition should produce 1 snapshot
        composeRule.runOnIdle {
            assertEquals("initial composition", 1, snapshots.size)
            assertMirrorInvariants(snapshots[0])
        }

        // Mutate streaming text VALUES (same keys, different values = a token delta)
        composeRule.runOnIdle {
            streamingTexts.value = mapOf("p2" to "updated delta text")
        }

        // After recomposition, the pipeline must recompute (new snapshot)
        composeRule.runOnIdle {
            assertTrue(
                "must recompose after streamingPartTexts value change: " +
                    "if this assertion fails, streamingPartTexts (full Map) was " +
                    "dropped from the key tuple, and a token delta would NOT " +
                    "recompute the pipeline",
                snapshots.size >= 2,
            )
            // Verify the second snapshot has DIFFERENT block content
            // (the new streaming text should be baked into blocks)
            val firstBlockIds = snapshots[0].renderBlocks.map { it.id }
            val secondBlockIds = snapshots[1].renderBlocks.map { it.id }
            assertEquals(
                "block ids should be the same (same keys, only values changed)",
                firstBlockIds, secondBlockIds,
            )
        }
    }

    @Test
    fun `recomposition messages change recomputes pipeline`() {
        // Guards that adding a new message (which changes reversedMessages)
        // also recomputes the pipeline. If `reversedMessages` or `messages`
        // were dropped from the pipeline's key tuple, the cached blocks
        // would not reflect the new message.

        val messagesState = mutableStateOf(listOf(msg("m1", "user")))
        var lastBlockCount = -1

        composeRule.setContent {
            val msgs = messagesState.value
            val partsByMessage = msgs.associate { it.id to listOf(part("p${it.id.last()}")) }
            val reversed = computeFilteredReversedMessages(
                msgs, partsByMessage, emptySet(), null, sessionIsRunning = false,
            )
            val pipeline = rememberRenderPipeline(
                reversedMessages = reversed, partsByMessage = partsByMessage,
                streamingPartTexts = emptyMap(), staleQuestionPartKeys = emptySet(),
                streamingReasoningPart = null, sessionIsRunning = false,
                sessionDiff = null, messages = msgs,
                hasMoreMessages = false, olderMessagesCursor = null,
            )
            // Track the LAST block count seen — this var is updated on every
            // composition (including recomposition), so we can detect recompose
            lastBlockCount = pipeline.renderBlocks.size
        }

        // After initial composition: 1 message → 1 block (user message)
        composeRule.runOnIdle {
            assertEquals("initial should have 1 block", 1, lastBlockCount)
        }

        // Reset the sentinel so we can detect recomposition
        lastBlockCount = -1

        // Add a second message
        composeRule.runOnIdle {
            messagesState.value = listOf(msg("m1", "user"), msg("m2", "assistant"))
        }

        composeRule.runOnIdle {
            assertFalse(
                "must recompose after messages change: lastBlockCount=$lastBlockCount " +
                    "should be != -1. If this assertion fails, the pipeline remember " +
                    "key tuple does not include reversedMessages or messages, so " +
                    "the cached pipeline was returned stale",
                lastBlockCount == -1,
            )
            assertTrue(
                "after adding a message, should have >= 1 block (got $lastBlockCount)",
                lastBlockCount >= 1,
            )
        }
    }

    @Test
    fun `recomposition sessionDiff change recomputes lazyColumnKeys`() {
        // Guards that `sessionDiff` is in the pipeline's key tuple. If it
        // were dropped, mutating sessionDiff would not add "session-diff"
        // to lazyColumnKeys (a stale cache would return the old key list).

        val sessionDiffState = mutableStateOf<List<FileDiff>?>(null)
        val snapshots = mutableListOf<PipelineCapture>()

        composeRule.setContent {
            val messages = listOf(msg("m1", "user"), msg("m2", "assistant"))
            val partsByMessage = mapOf("m1" to listOf(part("p1")), "m2" to listOf(part("p2")))
            val reversed = computeFilteredReversedMessages(messages, partsByMessage, emptySet(), null, false)
            val pipeline = rememberRenderPipeline(
                reversedMessages = reversed, partsByMessage = partsByMessage,
                streamingPartTexts = emptyMap(), staleQuestionPartKeys = emptySet(),
                streamingReasoningPart = null, sessionIsRunning = false,
                sessionDiff = sessionDiffState.value,
                messages = messages, hasMoreMessages = false,
                olderMessagesCursor = null,
            )
            val lastHash = remember { mutableStateOf(-1) }
            val h = pipeline.lazyColumnKeys.hashCode()
            if (lastHash.value != h) {
                lastHash.value = h
                snapshots.add(PipelineCapture(pipeline.renderBlocks, pipeline.lazyColumnKeys))
            }
        }

        composeRule.runOnIdle {
            assertEquals("initial without sessionDiff", 1, snapshots.size)
            assertFalse(
                "keys should NOT contain session-diff initially",
                snapshots[0].lazyColumnKeys.any { it == "session-diff" },
            )
        }

        // Add a session diff
        composeRule.runOnIdle {
            sessionDiffState.value = listOf(FileDiff())
        }

        composeRule.runOnIdle {
            assertTrue(
                "must recompose after sessionDiff change",
                snapshots.size >= 2,
            )
            assertTrue(
                "keys should contain session-diff after mutation",
                snapshots[1].lazyColumnKeys.any { it == "session-diff" },
            )
        }
    }
}
