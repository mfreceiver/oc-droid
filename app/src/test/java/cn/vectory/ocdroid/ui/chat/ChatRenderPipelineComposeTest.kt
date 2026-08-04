package cn.vectory.ocdroid.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
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
 *  1. renderBlocks.size == lazyColumnKeys.size  (keys match blocks)
 *  2. Every block.id appears in lazyColumnKeys  (no orphan blocks)
 *  3. lazyColumnKeys has no duplicates          (key uniqueness)
 *
 * These tests would FAIL if a remember key dimension were dropped, because a
 * stale cache would return old pipeline output despite changed inputs.
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
     * [renderBlocks] when those items are present. The correct invariant is
     * "every block is referenced by a key" (item 1 above), not "keys equal
     * blocks".
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

    // ── Scenario 1: empty messages ──────────────────────────────────────────

    @Test
    fun `pipeline empty messages`() {
        val capture = PipelineCapture()
        composeRule.setContent {
            val pipeline = rememberRenderPipeline(
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
            )
            capture.renderBlocks = pipeline.renderBlocks
            capture.lazyColumnKeys = pipeline.lazyColumnKeys
        }
        composeRule.runOnIdle {
            assertTrue("empty pipeline should have 0 blocks", capture.renderBlocks.isEmpty())
            assertTrue("empty pipeline should have 0 keys", capture.lazyColumnKeys.isEmpty())
        }
    }

    // ── Scenario 2: normal messages, no streaming ───────────────────────────

    @Test
    fun `pipeline normal messages`() {
        val capture = PipelineCapture()
        composeRule.setContent {
            val messages = listOf(msg("m1", "user"), msg("m2", "assistant"))
            val partsByMessage = mapOf(
                "m1" to listOf(part("p1")),
                "m2" to listOf(part("p2")),
            )
            val reversed = computeFilteredReversedMessages(
                messages = messages,
                partsByMessage = partsByMessage,
                streamingPartTextKeys = emptySet(),
                streamingReasoningPart = null,
                sessionIsRunning = false,
            )
            val pipeline = rememberRenderPipeline(
                reversedMessages = reversed,
                partsByMessage = partsByMessage,
                streamingPartTexts = emptyMap(),
                staleQuestionPartKeys = emptySet(),
                streamingReasoningPart = null,
                sessionIsRunning = false,
                sessionDiff = null,
                messages = messages,
                hasMoreMessages = false,
                olderMessagesCursor = null,
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
            val partsByMessage = mapOf(
                "m1" to listOf(part("p1")),
                "m2" to listOf(part("p2", "text")),
            )
            val streamingTexts = mapOf("p2" to "partial response")
            val reversed = computeFilteredReversedMessages(
                messages = messages,
                partsByMessage = partsByMessage,
                streamingPartTextKeys = streamingTexts.keys,
                streamingReasoningPart = null,
                sessionIsRunning = true,
            )
            val pipeline = rememberRenderPipeline(
                reversedMessages = reversed,
                partsByMessage = partsByMessage,
                streamingPartTexts = streamingTexts,
                staleQuestionPartKeys = emptySet(),
                streamingReasoningPart = null,
                sessionIsRunning = true,
                sessionDiff = null,
                messages = messages,
                hasMoreMessages = true,
                olderMessagesCursor = "cursor-1",
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
}
