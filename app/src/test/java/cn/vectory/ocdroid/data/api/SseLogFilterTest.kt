package cn.vectory.ocdroid.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-18 Phase 5 coverage: pins the membership of [NOISY_SSE_LOG_EVENTS].
 *
 * This Set is consumed by [SSEClient] (`val noisy = type in
 * NOISY_SSE_LOG_EVENTS`) to suppress per-token streaming events from the
 * debug ring buffer. Removing an entry here would re-flood the log;
 * adding one would hide signal. Pinning the membership makes either
 * regression visible at test time.
 *
 * The set itself is a `Set<String>` constant — testing it directly is the
 * highest-ROI coverage (the surrounding SSEClient.connect path needs a
 * fake stream and is covered separately).
 */
class SseLogFilterTest {

    @Test
    fun `noise set contains the high-frequency streaming events`() {
        // The per-token streaming events — emitted dozens–100s/sec during
        // AI output. These are the original reason the set exists.
        assertTrue("message.part.delta must be filtered", "message.part.delta" in NOISY_SSE_LOG_EVENTS)
        assertTrue("message.part.updated must be filtered", "message.part.updated" in NOISY_SSE_LOG_EVENTS)
    }

    @Test
    fun `noise set contains server heartbeat and connection events`() {
        assertTrue("server.heartbeat must be filtered", "server.heartbeat" in NOISY_SSE_LOG_EVENTS)
        assertTrue("server.connected must be filtered", "server.connected" in NOISY_SSE_LOG_EVENTS)
    }

    @Test
    fun `noise set contains the catalog and integration refresh events`() {
        assertTrue("plugin.added must be filtered", "plugin.added" in NOISY_SSE_LOG_EVENTS)
        assertTrue("catalog.updated must be filtered", "catalog.updated" in NOISY_SSE_LOG_EVENTS)
        assertTrue("integration.updated must be filtered", "integration.updated" in NOISY_SSE_LOG_EVENTS)
    }

    @Test
    fun `noise set excludes signal events that drive UI state`() {
        // These MUST stay logged — they drive session/message/permission UI.
        // Pinning the negative prevents a future "filter everything" mistake.
        val signal = listOf(
            "message.updated",
            "message.removed",
            "session.updated",
            "session.removed",
            "permission.updated",
            "question.updated",
            "tool_call.updated",
            "part.updated"
        )
        for (type in signal) {
            assertFalse(
                "$type is signal and must NOT be filtered",
                type in NOISY_SSE_LOG_EVENTS
            )
        }
    }

    @Test
    fun `noise set is exactly the documented 7 entries`() {
        // Lock the cardinal: each entry was individually justified; an
        // accidental duplicate or removal changes the semantics.
        assertEquals(
            setOf(
                "message.part.delta",
                "message.part.updated",
                "server.heartbeat",
                "server.connected",
                "plugin.added",
                "catalog.updated",
                "integration.updated"
            ),
            NOISY_SSE_LOG_EVENTS
        )
    }

    @Test
    fun `noise set membership check returns false for unknown event types`() {
        // Unknown event types (e.g. a future "tool_call.delta") must NOT be
        // filtered — fail-open so new server events surface in the log.
        assertFalse("unknown-event-xyz" in NOISY_SSE_LOG_EVENTS)
        assertFalse("" in NOISY_SSE_LOG_EVENTS)
    }

    @Test
    fun `noise set is a Set with deduplicated contains contract`() {
        // Set semantics — the `in` operator the SSEClient uses is O(1)
        // and dedup is enforced by construction. Smoke-test the contract.
        val expected = listOf(
            "message.part.delta", "message.part.updated", "server.heartbeat",
            "server.connected", "plugin.added", "catalog.updated", "integration.updated"
        )
        for (e in expected) {
            assertTrue(NOISY_SSE_LOG_EVENTS.contains(e))
        }
        assertEquals(expected.size, NOISY_SSE_LOG_EVENTS.size)
    }
}
