package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lite-v2-dev (plan §4.1): SlimSessionState + SlimSseStateMachine + SlimSyncEngine
 * have been RETIRED. The only survivors in [SlimapiResync] are two pure
 * helpers — [compareWatermark] and [maxMessageTuple] — both kept because they
 * have live main-source callers and primitive (not SlimSessionState) inputs.
 *
 * This file pins ONLY those two pure helpers. Every test that exercised the
 * SlimSessionState-typed helpers (`needsReconcile`, `onReconcileSuccess`,
 * `onReconcileFailure`, `markDeleted`, `clearLocal`,
 * `canAdvanceLocalAppliedTuple`) has been deleted with the machinery it
 * pinned — the contracts those tests documented are no longer reachable
 * from any main-source code path.
 */
class SlimapiResyncTest {

    // ── T1-C1: compareWatermark lexicographic + null ordering ─────────────

    @Test
    fun `compareWatermark lexicographic and null ordering`() {
        // ts 主导
        assertTrue(compareWatermark(200L, "m1", 100L, "m9") > 0)
        assertTrue(compareWatermark(100L, "m9", 200L, "m1") < 0)
        // ts 相等 → id 字典序
        assertTrue(compareWatermark(200L, "m2", 200L, "m1") > 0)
        assertTrue(compareWatermark(200L, "m1", 200L, "m2") < 0)
        assertEquals(0, compareWatermark(200L, "m1", 200L, "m1"))
        // null ts = 最旧
        assertTrue(compareWatermark(null, "m9", 100L, "m1") < 0)
        assertTrue(compareWatermark(100L, "m1", null, "m9") > 0)
        assertEquals(0, compareWatermark(null, "a", null, "b"))
        // ts 相等 + null id = 最旧
        assertTrue(compareWatermark(200L, null, 200L, "m1") < 0)
        assertTrue(compareWatermark(200L, "m1", 200L, null) > 0)
    }

    // ── §11.3: maxMessageTuple ────────────────────────────────────────────

    @Test
    fun `§11_3 maxMessageTuple null updated timestamp is not completeness proof`() {
        // A message whose time.updated is null (or <= 0L) is NOT a
        // completeness signal — it is excluded from watermark selection.
        // Here every candidate is ineligible, so maxMessageTuple returns
        // null even though the collection is non-empty.
        val items = listOf(
            MessageWithParts(info = Message(id = "m1", role = "assistant")), // no time
            messageWithParts(id = "m2", updated = 0L),
        )
        assertNull(
            "null / zero updated timestamps are not completeness proof — " +
                "no eligible item ⇒ maxMessageTuple is null",
            maxMessageTuple(items),
        )
    }

    @Test
    fun `§11_3 maxMessageTuple same timestamp uses existing message id tie break`() {
        // When multiple eligible items share the max updatedAt, the
        // LARGEST id wins (lexicographic tie-break, mirroring
        // compareWatermark). Both components of the returned pair come
        // from that same winning item.
        val items = listOf(
            messageWithParts(id = "aaa", updated = 200L),
            messageWithParts(id = "zzz", updated = 200L),
            messageWithParts(id = "mmm", updated = 200L),
        )
        val max = maxMessageTuple(items)
        assertEquals(200L, max?.first)
        assertEquals(
            "same-ts tie-break: largest id (zzz) wins, mirroring compareWatermark",
            "zzz",
            max?.second,
        )
    }

    @Test
    fun `§11_3 maxMessageTuple empty collection returns null`() {
        assertNull(maxMessageTuple(emptyList()))
    }

    @Test
    fun `§11_3 maxMessageTuple all ineligible items returns null`() {
        // Blank ids and non-positive updated are both excluded.
        val items = listOf(
            messageWithParts(id = "", updated = 500L),
            messageWithParts(id = "   ", updated = 400L),
            messageWithParts(id = "m1", updated = 0L),
            messageWithParts(id = "m2", updated = -1L),
            MessageWithParts(info = Message(id = "m3", role = "assistant")), // no time
        )
        assertNull(
            "no eligible item (blank ids / non-positive updated) ⇒ null, never (null, id)",
            maxMessageTuple(items),
        )
    }

    @Test
    fun `§11_3 maxMessageTuple blank id coexists with valid lower-ts item anchors to valid max`() {
        // Among mixed items, only non-blank-id + positive-updated
        // candidates participate. A blank id @500 is ignored; the valid
        // tuple-max (m-valid @300) wins.
        val items = listOf(
            messageWithParts(id = "", updated = 500L),
            messageWithParts(id = "m-valid", updated = 300L),
            messageWithParts(id = "m-older", updated = 200L),
        )
        val max = maxMessageTuple(items)
        assertEquals(300L, max?.first)
        assertEquals("m-valid", max?.second)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun messageWithParts(id: String, updated: Long): MessageWithParts =
        MessageWithParts(
            info = Message(
                id = id,
                role = "assistant",
                time = Message.TimeInfo(updated = updated),
            )
        )
}
