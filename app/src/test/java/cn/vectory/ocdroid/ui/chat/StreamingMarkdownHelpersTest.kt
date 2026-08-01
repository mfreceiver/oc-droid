package cn.vectory.ocdroid.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * batch3 / req-2 — JVM unit tests for the streaming-markdown helpers.
 *
 * **batch3 req-2**: `buildStreamingRenderUnits` / `StreamingRenderUnit` /
 * `streamingCodeBody` are deleted (streaming-branch now renders plain Text).
 * The remaining tests cover:
 * - [HeightShrinkCounter] — shrink/width-reset accounting (7 tests)
 * - [HeightAnchorRegistry] — cross-branch maxHeight sharing (6 tests)
 *
 * See also [StreamingMarkdownZeroShrinkTest] (androidTest, emulator gate).
 */
class StreamingMarkdownHelpersTest {

    // ── HeightShrinkCounter ──────────────────────────────────────────────

    @Test
    fun `HeightShrinkCounter monotonically increasing heights yield shrinkCount 0`() {
        val c = HeightShrinkCounter()
        c.record(width = 100, height = 10)
        c.record(width = 100, height = 20)
        c.record(width = 100, height = 30)
        c.record(width = 100, height = 40)
        assertEquals(0, c.shrinkCount)
        assertEquals(0, c.widthResetCount)
    }

    @Test
    fun `HeightShrinkCounter equal heights yield shrinkCount 0`() {
        val c = HeightShrinkCounter()
        c.record(width = 100, height = 50)
        c.record(width = 100, height = 50)
        c.record(width = 100, height = 50)
        assertEquals(0, c.shrinkCount)
    }

    @Test
    fun `HeightShrinkCounter a height decrease increments shrinkCount`() {
        val c = HeightShrinkCounter()
        c.record(width = 100, height = 50)
        c.record(width = 100, height = 40) // shrink
        assertEquals(1, c.shrinkCount)
        c.record(width = 100, height = 35) // another shrink
        assertEquals(2, c.shrinkCount)
    }

    @Test
    fun `HeightShrinkCounter width change increments widthResetCount and skips that frame`() {
        val c = HeightShrinkCounter()
        c.record(width = 100, height = 50)
        c.record(width = 80, height = 40) // width change → reset frame, NOT a shrink
        assertEquals(0, c.shrinkCount)
        assertEquals(1, c.widthResetCount)
    }

    @Test
    fun `HeightShrinkCounter shrink after width-reset frame IS counted`() {
        // The width-reset frame itself is skipped, but a subsequent shrink at
        // the new width is a real shrink (the anchor should have prevented it).
        val c = HeightShrinkCounter()
        c.record(width = 100, height = 50)
        c.record(width = 80, height = 60)  // width change → reset frame, skipped
        c.record(width = 80, height = 55)  // shrink at new width → counted
        assertEquals(1, c.shrinkCount)
        assertEquals(1, c.widthResetCount)
    }

    @Test
    fun `HeightShrinkCounter reset clears all counters`() {
        val c = HeightShrinkCounter()
        c.record(width = 100, height = 50)
        c.record(width = 100, height = 40)
        c.record(width = 80, height = 30)
        assertTrue(c.shrinkCount >= 0)
        assertTrue(c.widthResetCount >= 1)
        c.reset()
        assertEquals(0, c.shrinkCount)
        assertEquals(0, c.widthResetCount)
        // After reset, the first record is the new baseline (no shrink).
        c.record(width = 80, height = 30)
        assertEquals(0, c.shrinkCount)
    }

    @Test
    fun `HeightShrinkCounter first record never counts as shrink`() {
        val c = HeightShrinkCounter()
        c.record(width = 100, height = 5)
        assertEquals(0, c.shrinkCount)
    }

    // ── HeightAnchorRegistry (cross-branch maxHeight sharing) ────────────
    // Uses unique keys per test to avoid cross-test pollution from the global
    // mutable map (the registry is a singleton object).
    //
    // §T2 (chat-ux-batch branch G): the registry key is now a width-aware
    // composite `(stableKey, width)`. The existing 0-shrink / isolation / LRU
    // tests below pin a FIXED width (100) for every call so they still verify
    // the original within-width semantics (same width → non-decreasing; key
    // isolation; LRU eviction) under the new signature.

    @Test
    fun `HeightAnchorRegistry update raises the stored maxHeight monotonically`() {
        val key = "test-registry-raise-${System.nanoTime()}" to 100
        assertEquals(0, HeightAnchorRegistry.anchorFor(key))
        HeightAnchorRegistry.update(key, 100)
        assertEquals(100, HeightAnchorRegistry.anchorFor(key))
        // A smaller natural height does NOT lower the anchor (non-decreasing).
        HeightAnchorRegistry.update(key, 80)
        assertEquals(100, HeightAnchorRegistry.anchorFor(key))
        // A larger natural height raises it.
        HeightAnchorRegistry.update(key, 150)
        assertEquals(150, HeightAnchorRegistry.anchorFor(key))
        HeightAnchorRegistry.reset(key)
    }

    @Test
    fun `HeightAnchorRegistry reset clears the entry`() {
        val key = "test-registry-reset-${System.nanoTime()}" to 100
        HeightAnchorRegistry.update(key, 200)
        assertEquals(200, HeightAnchorRegistry.anchorFor(key))
        HeightAnchorRegistry.reset(key)
        assertEquals(0, HeightAnchorRegistry.anchorFor(key))
    }

    @Test
    fun `HeightAnchorRegistry unknown key returns 0`() {
        val key = "test-registry-unknown-${System.nanoTime()}" to 100
        assertEquals(0, HeightAnchorRegistry.anchorFor(key))
    }

    @Test
    fun `HeightAnchorRegistry keys are isolated`() {
        val keyA = "test-registry-A-${System.nanoTime()}" to 100
        val keyB = "test-registry-B-${System.nanoTime()}" to 100
        HeightAnchorRegistry.update(keyA, 50)
        HeightAnchorRegistry.update(keyB, 90)
        assertEquals(50, HeightAnchorRegistry.anchorFor(keyA))
        assertEquals(90, HeightAnchorRegistry.anchorFor(keyB))
        // Resetting A does not affect B (the streaming→completed sharing model).
        HeightAnchorRegistry.reset(keyA)
        assertEquals(0, HeightAnchorRegistry.anchorFor(keyA))
        assertEquals(90, HeightAnchorRegistry.anchorFor(keyB))
        HeightAnchorRegistry.reset(keyB)
    }

    @Test
    fun `HeightAnchorRegistry LRU evicts least-recently-used past MAX_ENTRIES`() {
        // §kimo-r2 / glmer-r2: the registry is an access-order LRU capped at
        // MAX_ENTRIES. This pins the distinguishing behaviour the other tests
        // don't cover: (1) the eldest never-again-touched entry IS evicted once
        // size exceeds the cap, and (2) a recently-queried entry is promoted
        // (access-order) and survives an overflow that would otherwise evict it.
        // Mirrors the repo's existing eviction-test convention
        // (DebugLogTest / SessionSwitcherTest).
        //
        // §T2: keys are `(stableKey, width)` Pairs; width is pinned to 100 so
        // eviction semantics are unchanged from the bare-key era.
        val base = "lru-${System.nanoTime()}-"
        // Fill exactly to the cap — no eviction yet (removeEldestEntry is `size > cap`).
        repeat(HeightAnchorRegistry.MAX_ENTRIES) { i ->
            HeightAnchorRegistry.update("$base$i" to 100, 100 + i)
        }
        // Touch an early key → promote it to most-recently-used (access-order).
        val promotedValue = HeightAnchorRegistry.anchorFor("$base${0}" to 100)
        assertEquals(100, promotedValue)
        // Overflow by one → forces one eviction. The LRU is now base:1 (base:0
        // was just promoted past it), so base:1 is evicted; base:0 survives.
        HeightAnchorRegistry.update("${base}overflow" to 100, 1)
        assertEquals(
            "the promoted (recently-queried) key must survive the overflow",
            100, HeightAnchorRegistry.anchorFor("$base${0}" to 100)
        )
        assertEquals(
            "the least-recently-used untouched key must be evicted",
            0, HeightAnchorRegistry.anchorFor("$base${1}" to 100)
        )
        assertEquals(1, HeightAnchorRegistry.anchorFor("${base}overflow" to 100))
        // cleanup (evicted keys' reset is a harmless no-op)
        for (i in 0..HeightAnchorRegistry.MAX_ENTRIES) HeightAnchorRegistry.reset("$base$i" to 100)
        HeightAnchorRegistry.reset("${base}overflow" to 100)
    }

    // ── T2 (chat-ux-batch / branch G): width-aware composite key ──────────
    // The registry MUST key on `(stableKey, width)` so a stableKey measured at
    // one width does NOT leak its maxHeight into a different width (the
    // "变宽流式留白" bug: on window widen, the stale old-width maxHeight
    // pinned the visible height above the new natural height → empty bottom
    // space). With a width-aware key, different widths get independent anchors
    // → on widen, the new width starts from a fresh anchor at its own natural
    // height. Same width stays 0-shrink (monotonic non-decreasing).
    //
    // §test-hygiene: uses a unique base key (per-test nanoTime suffix, matching
    // the convention of the other registry tests above) so it NEVER shares
    // registry state with other tests and needs no global `resetAll`-style
    // helper. Cross-width assertions compose the SAME base with different
    // widths (100 / 200); cleanup `reset`s both composite keys.

    @Test
    fun `width-aware key isolates anchors across widths`() {
        val k = "t2-iso-${System.nanoTime()}"
        HeightAnchorRegistry.update(k to 100, 50)
        assertEquals(50, HeightAnchorRegistry.anchorFor(k to 100))
        assertEquals(0, HeightAnchorRegistry.anchorFor(k to 200)) // 跨 width 不泄漏
        HeightAnchorRegistry.update(k to 100, 30) // 同 width 只增不减
        assertEquals(50, HeightAnchorRegistry.anchorFor(k to 100))
        // cleanup
        HeightAnchorRegistry.reset(k to 100)
        HeightAnchorRegistry.reset(k to 200)
    }
}
