package cn.vectory.ocdroid.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §0.6.2 ora-2 — JVM unit tests for the pure streaming-markdown helpers
 * ([buildStreamingRenderUnits] + [HeightShrinkCounter]) lifted into
 * [StreamingMarkdownHelpers.kt] (kover-covered; the @Composable counterpart
 * StreamingMarkdownRender.kt is excluded like ChatTextParts.kt).
 *
 * These pin the block-decomposition contract (global-offset stable keys,
 * completed-block / tail split, code-fence body extraction for open AND closed
 * fences, trailing-remainder preservation) that the 0-shrink androidTest relies
 * on, and the HeightShrinkCounter's shrink / width-reset accounting.
 */
class StreamingMarkdownHelpersTest {

    // ── buildStreamingRenderUnits — stable keys & kinds ──────────────────

    @Test
    fun `buildStreamingRenderUnits empty text returns empty list`() {
        assertTrue(buildStreamingRenderUnits("").isEmpty())
    }

    @Test
    fun `buildStreamingRenderUnits blank text returns empty list`() {
        assertTrue(buildStreamingRenderUnits("   \n  ").isEmpty())
    }

    @Test
    fun `buildStreamingRenderUnits pure prose yields single Prose unit keyed block_0_prose`() {
        val units = buildStreamingRenderUnits("hello world\nsecond line")
        assertEquals(1, units.size)
        val prose = units.single()
        assertTrue(prose is StreamingRenderUnit.Prose)
        assertEquals(0, prose.globalStartOffset)
        assertEquals("prose", prose.kind)
        // §gpter-2 globally-stable key: block:$globalStartOffset:$kind
        assertEquals("block:0:prose", prose.stableKey)
    }

    @Test
    fun `buildStreamingRenderUnits Prose unit raw contains the source text`() {
        val units = buildStreamingRenderUnits("hello world")
        val prose = units.single() as StreamingRenderUnit.Prose
        // The raw substring starts at globalStartOffset=0 and contains "hello".
        assertTrue(prose.raw.contains("hello"))
    }

    // ── code fence handling ──────────────────────────────────────────────

    @Test
    fun `buildStreamingRenderUnits closed fence yields Prose then Code then Prose`() {
        val text = "before\n```kotlin\nval x = 1\n```\nafter"
        val units = buildStreamingRenderUnits(text)
        assertEquals(3, units.size)
        // Prose("before...") + Code("val x = 1", "kotlin") + Prose("after...")
        assertTrue(units[0] is StreamingRenderUnit.Prose)
        assertTrue(units[1] is StreamingRenderUnit.Code)
        assertTrue(units[2] is StreamingRenderUnit.Prose)

        val code = units[1] as StreamingRenderUnit.Code
        assertEquals("val x = 1", code.code)
        assertEquals("kotlin", code.language)
        assertEquals("code", code.kind)
    }

    @Test
    fun `buildStreamingRenderUnits closed fence Code key is block_offset_code`() {
        val text = "before\n```kotlin\nval x = 1\n```\nafter"
        val units = buildStreamingRenderUnits(text)
        val code = units[1] as StreamingRenderUnit.Code
        // §gpter-2: key = block:$globalStartOffset:$kind. The fence starts
        // after "before\n" (>offset 0). Assert the key FORMAT (not a hardcoded
        // offset, which is intellij-version-dependent) + that the offset is
        // past the leading prose.
        assertEquals("block:${code.globalStartOffset}:code", code.stableKey)
        assertTrue(
            "code fence should start after 'before' (offset > 5); got ${code.globalStartOffset}",
            code.globalStartOffset > 5
        )
    }

    @Test
    fun `buildStreamingRenderUnits OPEN fence tail still emits Code with body`() {
        // §streaming-stability: an open fence (EOF reached without a closer) is
        // emitted as a Code unit whose body excludes the opening fence line.
        val text = "intro\n```python\ngrowing code"
        val units = buildStreamingRenderUnits(text)
        assertEquals(2, units.size)
        assertTrue(units[0] is StreamingRenderUnit.Prose)
        val code = units[1] as StreamingRenderUnit.Code
        assertEquals("growing code", code.code)
        assertEquals("python", code.language)
    }

    @Test
    fun `buildStreamingRenderUnits lone open fence opener emits empty-body Code`() {
        val text = "intro\n```kotlin\n"
        val units = buildStreamingRenderUnits(text)
        // Prose("intro...") + Code(body="", lang="kotlin") — open fence with
        // only the opener + a newline.
        val code = units.lastOrNull() as? StreamingRenderUnit.Code
            ?: error("expected trailing Code unit, got ${units.lastOrNull()}")
        assertEquals("kotlin", code.language)
        assertEquals("", code.code)
    }

    @Test
    fun `buildStreamingRenderUnits tilde fence works like backtick fence`() {
        val text = "~~~kotlin\ncode\n~~~"
        val units = buildStreamingRenderUnits(text)
        val code = units.single() as StreamingRenderUnit.Code
        assertEquals("code", code.code)
        assertEquals("kotlin", code.language)
    }

    @Test
    fun `buildStreamingRenderUnits pandoc attribute fence language is stripped`() {
        val text = "```{.kotlin}\ncode\n```"
        val units = buildStreamingRenderUnits(text)
        val code = units.single() as StreamingRenderUnit.Code
        assertEquals("kotlin", code.language)
    }

    @Test
    fun `buildStreamingRenderUnits code line starting with fence chars is NOT stripped as closer`() {
        // §gpter-1 closer tightening: an OPEN fence whose last code line merely
        // STARTS with ``` (e.g. a nested-fence-style code comment being typed)
        // must NOT be mis-stripped as a fence closer — only a line of PURE fence
        // chars (3+) is a valid closer. The old `startsWith("```")` check would
        // wrongly drop such a line.
        val text = "intro\n```kotlin\nval x = 1\n```next-line-of-code"
        val units = buildStreamingRenderUnits(text)
        val code = units.lastOrNull() as? StreamingRenderUnit.Code
            ?: error("expected trailing Code unit, got ${units.lastOrNull()}")
        assertTrue(
            "code line starting with ``` must be preserved (not stripped as a closer); got: ${code.code}",
            code.code.contains("```next-line-of-code")
        )
        assertTrue("preceding code line preserved", code.code.contains("val x = 1"))
    }

    @Test
    fun `buildStreamingRenderUnits shorter backtick line inside longer open fence is preserved`() {
        // §gpter-r2 CommonMark closer rule: a 4-backtick opener needs a 4+ backtick
        // closer, so a 3-backtick line inside it is CODE content, not a closer.
        val text = "````kotlin\nval x = 1\n```"
        val units = buildStreamingRenderUnits(text)
        val code = units.lastOrNull() as? StreamingRenderUnit.Code
            ?: error("expected trailing Code unit, got ${units.lastOrNull()}")
        assertTrue(
            "3-backtick line inside a 4-backtick open fence must be preserved; got: ${code.code}",
            code.code.contains("```")
        )
        assertTrue("preceding code line preserved", code.code.contains("val x = 1"))
    }

    @Test
    fun `buildStreamingRenderUnits tilde line inside backtick fence is preserved`() {
        // §gpter-r2: a tilde line cannot close a backtick fence (different char) —
        // it is CODE content.
        val text = "```kotlin\nval x = 1\n~~~"
        val units = buildStreamingRenderUnits(text)
        val code = units.lastOrNull() as? StreamingRenderUnit.Code
            ?: error("expected trailing Code unit, got ${units.lastOrNull()}")
        assertTrue(
            "tilde line inside a backtick fence must be preserved; got: ${code.code}",
            code.code.contains("~~~")
        )
        assertTrue("preceding code line preserved", code.code.contains("val x = 1"))
    }

    @Test
    fun `buildStreamingRenderUnits 4-space-indented fence-like line is code not closer`() {
        // §gpter-r3 #1: a fence-like line with 4+ leading spaces is CODE content,
        // not a closing fence (CommonMark allows a closer at most 3 spaces of
        // indent). The old `.trim()`-based check stripped it; the indent-aware
        // check preserves it.
        val text = "```kotlin\nval x = 1\n    ```"
        val units = buildStreamingRenderUnits(text)
        val code = units.lastOrNull() as? StreamingRenderUnit.Code
            ?: error("expected trailing Code unit, got ${units.lastOrNull()}")
        assertTrue(
            "4-space-indented fence-like line must be preserved as code; got: ${code.code}",
            code.code.contains("```")
        )
        assertTrue("preceding code line preserved", code.code.contains("val x = 1"))
    }

    @Test
    fun `buildStreamingRenderUnits indented code block is returned verbatim`() {
        // §gpter-r3 #2: an indented CODE_BLOCK has no fence structure — its
        // content (even if fence-looking) is returned verbatim, never stripped as
        // opener/closer. intellij parses this as CODE_BLOCK (isFence=false), so
        // streamingCodeBody returns the span as-is.
        val text = "intro\n\n    indented line one\n    indented line two"
        val units = buildStreamingRenderUnits(text)
        val code = units.lastOrNull() as? StreamingRenderUnit.Code
            ?: error("expected trailing Code unit, got ${units.lastOrNull()}")
        assertTrue(
            "indented block first line preserved verbatim; got: ${code.code}",
            code.code.contains("indented line one")
        )
        assertTrue("indented block second line preserved", code.code.contains("indented line two"))
    }

    // ── gpter #2 — global-offset key stability across tail promotion ─────

    @Test
    fun `buildStreamingRenderUnits key is invariant when tail promotes to completed block`() {
        // Frame 1: two paragraphs, second is the tail.
        val text1 = "first paragraph\n\nsecond paragraph"
        val units1 = buildStreamingRenderUnits(text1)
        // intellij-markdown merges consecutive paragraphs (no code between)
        // into ONE prose run spanning both — so this is one Prose unit.
        // The key point: its key is block:$startOffset:prose.
        val firstRunKey = units1.last().stableKey

        // Frame 2: a third paragraph appears; the previously-tail run now
        // ends earlier (its content froze when the blank line closed it) but
        // its START offset + kind are unchanged → same key.
        val text2 = "first paragraph\n\nsecond paragraph\n\nthird paragraph"
        val units2 = buildStreamingRenderUnits(text2)
        // Still one merged prose run (all prose, no code) → same start offset.
        assertEquals(firstRunKey, units2.last().stableKey)
    }

    @Test
    fun `buildStreamingRenderUnits completed Code block keeps its key when a new block follows`() {
        // Frame 1: prose + open code fence (code is the tail).
        val text1 = "intro\n```kotlin\nval x = 1"
        val codeKey1 = buildStreamingRenderUnits(text1).last().stableKey

        // Frame 2: fence closes and prose follows — the code block is now
        // COMPLETED (not the tail). Its start offset + kind are unchanged →
        // key is invariant (gpter #2 cache reuse).
        val text2 = "intro\n```kotlin\nval x = 1\n```\nafter"
        val units2 = buildStreamingRenderUnits(text2)
        val codeUnit = units2.first { it is StreamingRenderUnit.Code }
        assertEquals(codeKey1, codeUnit.stableKey)
    }

    // ── multiple code blocks ─────────────────────────────────────────────

    @Test
    fun `buildStreamingRenderUnits multiple fences yield alternating Prose and Code units`() {
        val text = """
            intro
            ```kotlin
            val a = 1
            ```
            middle
            ```python
            b = 2
            ```
            outro
        """.trimIndent()
        val units = buildStreamingRenderUnits(text)
        // Prose, Code(kotlin), Prose, Code(python), Prose
        assertEquals(5, units.size)
        assertTrue(units[0] is StreamingRenderUnit.Prose)
        assertEquals("kotlin", (units[1] as StreamingRenderUnit.Code).language)
        assertTrue(units[2] is StreamingRenderUnit.Prose)
        assertEquals("python", (units[3] as StreamingRenderUnit.Code).language)
        assertTrue(units[4] is StreamingRenderUnit.Prose)
    }

    // ── trailing remainder ───────────────────────────────────────────────

    @Test
    fun `buildStreamingRenderUnits trailing partial content is not dropped`() {
        // A trailing line that the parser doesn't attach to a block (rare, but
        // possible with partial input) must still appear in the output so the
        // user sees every token they've received.
        val text = "hello"
        val units = buildStreamingRenderUnits(text)
        assertEquals(1, units.size)
        val prose = units.single() as StreamingRenderUnit.Prose
        assertTrue(prose.raw.contains("hello"))
    }

    @Test
    fun `buildStreamingRenderUnits code block followed by trailing prose merges trailing into prose`() {
        val text = "```kotlin\ncode\n```\ntrailing prose"
        val units = buildStreamingRenderUnits(text)
        // Code + Prose("trailing prose")
        assertEquals(2, units.size)
        assertTrue(units[0] is StreamingRenderUnit.Code)
        val prose = units[1] as StreamingRenderUnit.Prose
        assertTrue(prose.raw.contains("trailing prose"))
    }

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
        // (DebugLogTest / SessionSwitcherTest / CacheRepositoryEvictionTest).
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
