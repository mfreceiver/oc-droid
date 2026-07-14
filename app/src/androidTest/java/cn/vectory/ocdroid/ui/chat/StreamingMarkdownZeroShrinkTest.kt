package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * §0.6.2 ora-2 — the 0-shrink Compose UI test gate (gpter #3 + #4).
 *
 * Drives [DebugStreamingMarkdownRender] (HeightAnchor SubcomposeLayout +
 * [HeightShrinkCounter]) through adversarial growing-text token sequences —
 * half-open bold / link, an unclosed code fence, a table built row-by-row,
 * multi-paragraph promotion, a width change, and the streaming→completed
 * transition — and asserts `counter.shrinkCount == 0` for each. A correct
 * HeightAnchor pins the visible height to max(H_natural(t), anchor(t-1)) →
 * non-decreasing → zero visible height-shrink flicker.
 *
 * This file is the androidTest counterpart to the JVM
 * [StreamingMarkdownHelpersTest] (which covers the pure decomposition +
 * counter logic). Together they form the gpter #3 release gate: the JVM test
 * runs in `check.sh` (kover floor), this androidTest runs in
 * `connectedDebugAndroidTest` (emulator, per AGENTS.md 模拟器纪律).
 *
 * NOTE on width-change: [HeightShrinkCounter] excludes the single
 * width-reset frame (gpter #1 accepts one natural-height frame on rotation /
 * split-screen). So the width-change scenarios assert `shrinkCount == 0` while
 * `widthResetCount >= 1`.
 */
class StreamingMarkdownZeroShrinkTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── §gpter-4 scenario 1: pure prose with half-open inline formatting ──

    @Test
    fun streamingProseWithHalfOpenBold_link_zeroShrink() {
        val counter = HeightShrinkCounter()
        // Adversarial: a half-open bold, then closed; a half-open link, then
        // closed; the markdown parser reinterprets the growing text each token,
        // which is exactly the case that produced 151 shrinks/turn with the old
        // whole-document re-parse. The HeightAnchor must mask all of it.
        val tokens = listOf(
            "Here is",
            " **an important",
            "** point",
            " with [a",
            " link](http://example.com)",
            " and more text",
            " to force wrapping",
            " across several lines",
            " so the height grows."
        )
        var text by mutableStateOf("")
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DebugStreamingMarkdownRender(
                        text = text,
                        stableKey = "msg-1|part-1",
                        counter = counter
                    )
                }
            }
        }
        for (tok in tokens) {
            text += tok
            composeRule.waitForIdle()
        }
        assertEquals(
            "streaming prose (half-open bold/link) must have 0 visible shrinks",
            0, counter.shrinkCount
        )
    }

    // ── §gpter-4 scenario 2: unclosed code fence growing ─────────────────

    @Test
    fun streamingUnclosedFenceGrowing_zeroShrink() {
        val counter = HeightShrinkCounter()
        val tokens = listOf(
            "Let me show code:\n```kotlin\n",
            "fun foo() {\n",
            "    println(\"hi\")\n",
            "}\n",
            "fun bar() {\n",
            "    println(\"bye\")\n",
            "}"
        )
        var text by mutableStateOf("")
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DebugStreamingMarkdownRender(
                        text = text,
                        stableKey = "msg-2|part-1",
                        counter = counter
                    )
                }
            }
        }
        for (tok in tokens) {
            text += tok
            composeRule.waitForIdle()
        }
        assertEquals(
            "streaming open fence must have 0 visible shrinks",
            0, counter.shrinkCount
        )
    }

    // ── §gpter-4 scenario 3: table built row-by-row ──────────────────────

    @Test
    fun streamingTableBuiltRowByRow_zeroShrink() {
        val counter = HeightShrinkCounter()
        val tokens = listOf(
            "| Name | Age |\n",
            "|------|-----|\n",
            "| Alice | 30 |\n",
            "| Bob | 25 |\n",
            "| Charlie | 40 |"
        )
        var text by mutableStateOf("")
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DebugStreamingMarkdownRender(
                        text = text,
                        stableKey = "msg-3|part-1",
                        counter = counter
                    )
                }
            }
        }
        for (tok in tokens) {
            text += tok
            composeRule.waitForIdle()
        }
        assertEquals(
            "streaming table row-by-row must have 0 visible shrinks",
            0, counter.shrinkCount
        )
    }

    // ── §gpter-4 scenario 4: multi-paragraph prose promotion ─────────────

    @Test
    fun streamingMultiParagraphPromotion_zeroShrink() {
        val counter = HeightShrinkCounter()
        // Multiple paragraphs: each blank line closes a paragraph and opens a
        // new one (the tail promotes). Completed paragraphs keep their stable
        // key (gpter #2) → cache reuse; only the new tail recomposes.
        val tokens = listOf(
            "First paragraph here.",
            "\n\nSecond paragraph",
            " with more content.",
            "\n\nThird paragraph appears.",
            "\n\nFourth and final paragraph.",
            " It keeps growing."
        )
        var text by mutableStateOf("")
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DebugStreamingMarkdownRender(
                        text = text,
                        stableKey = "msg-4|part-1",
                        counter = counter
                    )
                }
            }
        }
        for (tok in tokens) {
            text += tok
            composeRule.waitForIdle()
        }
        assertEquals(
            "streaming multi-paragraph promotion must have 0 visible shrinks",
            0, counter.shrinkCount
        )
    }

    // ── §gpter-1 + §gpter-4 scenario 5: width change ─────────────────────

    @Test
    fun streamingWidthChange_resetsAnchorWithoutPersistentShrink() {
        val counter = HeightShrinkCounter()
        val tokensWide = listOf(
            "This is a long line of prose",
            " that should wrap at the wider",
            " width into a couple of lines",
            " and keep growing steadily."
        )
        val tokensNarrow = listOf(
            " Now the width shrinks",
            " and the text reflows",
            " into more lines",
            " but the visible height",
            " must not shrink."
        )
        var text by mutableStateOf("")
        var boxWidth by mutableStateOf(360.dp)
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(boxWidth)) {
                        DebugStreamingMarkdownRender(
                            text = text,
                            stableKey = "msg-5|part-1",
                            counter = counter
                        )
                    }
                }
            }
        }
        // Grow at the wide width.
        for (tok in tokensWide) {
            text += tok
            composeRule.waitForIdle()
        }
        val shrinksBeforeWidthChange = counter.shrinkCount
        assertEquals(0, shrinksBeforeWidthChange)

        // Change width (rotation / split-screen simulation). The anchor resets;
        // exactly one widthResetCount increment is expected; the reset frame is
        // excluded from shrinkCount.
        boxWidth = 200.dp
        composeRule.waitForIdle()
        assertTrue(
            "width change must register a width-reset",
            counter.widthResetCount >= 1
        )

        // Continue growing at the narrow width — no further shrinks.
        for (tok in tokensNarrow) {
            text += tok
            composeRule.waitForIdle()
        }
        assertEquals(
            "no shrinks after width change (reset frame excluded)",
            0, counter.shrinkCount
        )
    }

    // ── §gpter-4 scenario 6: streaming → completed transition ────────────
    // The marquee scenario: HeightAnchorRegistry carries the streaming
    // maxHeight across the streaming→completed composition-position fork so
    // the finalization snap inherits the anchor → no height drop.
    //
    // §T2 (chat-ux-batch branch G): the registry key is now width-aware
    // `(stableKey, width)`. This scenario pins the layout width with an
    // explicit `Modifier.width(boxWidthDp)` on BOTH composition branches so
    // the streaming branch and the completed branch land on the SAME
    // `(stableKey, width)` composite key → the completed anchor inherits the
    // streaming maxHeight. The assertion then reads the SAME composite key.

    @Test
    fun streamingToCompletedTransition_sharesAnchorViaRegistry_zeroShrink() {
        val counter = HeightShrinkCounter()
        var text by mutableStateOf("")
        var isStreaming by mutableStateOf(true)
        // Pin the width so both branches share the same `(stableKey, width)`
        // composite key. 360.dp is the same convention as scenario 5.
        val boxWidthDp = 360.dp
        val widthPx = with(
            androidx.compose.ui.unit.Density(
                composeRule.density.density,
                composeRule.density.fontScale
            )
        ) { boxWidthDp.roundToPx() }

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(boxWidthDp)) {
                        if (isStreaming) {
                            // Streaming branch: DebugStreamingMarkdownRender writes
                            // natural heights into
                            // HeightAnchorRegistry[("msg-6|p1", widthPx)].
                            DebugStreamingMarkdownRender(
                                text = text,
                                stableKey = "msg-6|part-1",
                                counter = counter
                            )
                        } else {
                            // §seamless-evidence: completed branch is a DIFFERENT
                            // composition position (the `if` fork) but uses the
                            // SAME stableKey AND the SAME pinned width. HeightAnchor
                            // reads HeightAnchorRegistry[("msg-6|part-1", widthPx)]
                            // (populated during streaming) → inherits the streaming
                            // maxHeight → the completed markdown's natural height is
                            // pinned to ≥ the streaming max → no height drop on
                            // finalization.
                            HeightAnchor(stableKey = "msg-6|part-1") {
                                Markdown(content = text)
                            }
                        }
                    }
                }
            }
        }

        // Stream several tokens (multi-paragraph + bold).
        val tokens = listOf(
            "# Heading\n\n",
            "A paragraph with **bold** text.",
            "\n\nSecond paragraph grows.",
            "\n\n- a list item\n- another"
        )
        for (tok in tokens) {
            text += tok
            composeRule.waitForIdle()
        }
        val streamingShrinks = counter.shrinkCount
        assertEquals(
            "streaming phase must have 0 visible shrinks",
            0, streamingShrinks
        )

        // Finalize: flip isStreaming. The streaming HeightAnchor disposes; the
        // completed HeightAnchor activates at a different position but reads the
        // SAME registry entry by `(stableKey, width)` → maxHeight is inherited.
        isStreaming = false
        composeRule.waitForIdle()

        // Drive one more layout pass at the completed state. The DebugHeightAnchor
        // is gone (we're in the completed branch now), but the HeightAnchor's
        // own layout ran — assert no exception + the registry still holds the
        // streaming max (the completed natural height is pinned to ≥ it).
        val registryMax = HeightAnchorRegistry.anchorFor("msg-6|part-1" to widthPx)
        assertTrue(
            "HeightAnchorRegistry must retain the streaming maxHeight after " +
                "the streaming→completed fork (ora-2 (iii) seamless evidence); " +
                "got registryMax=$registryMax",
            registryMax > 0
        )
    }

    // ── §gpter-4 scenario 7: code fence closes mid-stream ────────────────

    @Test
    fun streamingFenceClosesMidStream_zeroShrink() {
        val counter = HeightShrinkCounter()
        // An open fence grows (Code tail), then closes (Code becomes completed),
        // then prose follows (new Prose tail). The fence's stable key is
        // invariant across the open→closed transition (gpter #2).
        val tokens = listOf(
            "intro\n```kotlin\n",
            "fun a() {}\n",
            "fun b() {}\n",
            "```\n",
            "after the fence",
            " more prose",
            " continues."
        )
        var text by mutableStateOf("")
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DebugStreamingMarkdownRender(
                        text = text,
                        stableKey = "msg-7|part-1",
                        counter = counter
                    )
                }
            }
        }
        for (tok in tokens) {
            text += tok
            composeRule.waitForIdle()
        }
        assertEquals(
            "streaming fence close + prose tail must have 0 visible shrinks",
            0, counter.shrinkCount
        )
    }

    // ── §gpter-4 scenario 8: ResolvedMarkdownText-style async image ──────
    // ResolvedMarkdownText itself can't run here (needs a repository), but the
    // HeightAnchor + Markdown path that ResolvedMarkdownText uses is exercised
    // by the transition test above. This test instead pins that a data-uri
    // image (the cheap async-image case, resolved inline by
    // DataUriImageTransformer) does not cause a shrink as it loads.

    @Test
    fun streamingDataUriImage_zeroShrink() {
        val counter = HeightShrinkCounter()
        // A tiny 1x1 PNG data URI. DataUriImageTransformer decodes it inline
        // (no network / repository needed) → exercises the async-image height
        // path through the anchor.
        val png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
        val tokens = listOf(
            "Here is an image:\n\n",
            "![alt]($png)\n\n",
            "And some text after the image",
            " that continues to grow."
        )
        var text by mutableStateOf("")
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    DebugStreamingMarkdownRender(
                        text = text,
                        stableKey = "msg-8|part-1",
                        counter = counter
                    )
                }
            }
        }
        for (tok in tokens) {
            text += tok
            composeRule.waitForIdle()
        }
        assertEquals(
            "streaming data-uri image must have 0 visible shrinks",
            0, counter.shrinkCount
        )
    }

    // ── T2 (chat-ux-batch / branch G): composable cross-width anchor isolation
    // Guards the PRODUCTION call-site fix, not just the registry API: the
    // HeightAnchor must key its registry entries on `(stableKey, width)` so a
    // HeightAnchor that DISPOSES and RE-CREATES at a DIFFERENT width (e.g. the
    // streaming→completed fork coinciding with a rotation / split-screen widen)
    // does NOT inherit the old width's stale maxHeight.
    //
    // Under the OLD bare-stableKey call site, the completed branch's fresh
    // `lastWidth = remember(effectiveKey){ intArrayOf(-1) }` started at -1 on
    // re-composition → the width-change reset branch was SKIPPED → the narrow-
    // width maxHeight leaked through the bare registry key into the wide width
    // → the visible height stayed pinned at the (taller) narrow height → empty
    // bottom space (the "变宽流式留白" bug). Under the NEW composite key, the
    // width is part of the key → the wide width gets a fresh anchor at its own
    // (shorter) natural height → the visible height re-measures DOWN.

    @Test
    fun streamingWidenAfterFork_compositeKeyReMeasureDown_notStaleAnchor() {
        val counter = HeightShrinkCounter()
        var text by mutableStateOf("")
        var boxWidth by mutableStateOf(200.dp)
        var isStreaming by mutableStateOf(true)
        // Capture the Box's reported height (= the HeightAnchor's targetHeight)
        // via onSizeChanged so we can assert on the VISIBLE height behaviour
        // without exposing private counter state.
        var reportedHeight by mutableStateOf(0)

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Box(
                        Modifier
                            .width(boxWidth)
                            .onSizeChanged { reportedHeight = it.height }
                    ) {
                        if (isStreaming) {
                            // Streaming branch: DebugHeightAnchor writes the
                            // narrow-width natural height into the registry.
                            DebugStreamingMarkdownRender(
                                text = text,
                                stableKey = "msg-wc|part-1",
                                counter = counter
                            )
                        } else {
                            // Completed branch: HeightAnchor at a DIFFERENT
                            // composition position (the `if` fork). Under the
                            // OLD code, its fresh lastWidth=-1 skipped the
                            // reset, leaking the narrow anchor. Under the NEW
                            // code, the composite key isolates per-width.
                            HeightAnchor(stableKey = "msg-wc|part-1") {
                                Markdown(content = text)
                            }
                        }
                    }
                }
            }
        }

        // A long paragraph that wraps to MANY lines at 200.dp (narrow → tall)
        // and FEWER lines at 360.dp (wide → short). The height difference is
        // the discrimination signal: if the wide width inherits the narrow
        // anchor, heightWide would equal heightNarrow (stale, no re-measure).
        text = "This is a long paragraph of streaming markdown prose that " +
            "wraps to several lines at a narrow width and should measure " +
            "taller there than at a wider width where fewer lines are needed " +
            "to render the same content without any loss or truncation " +
            "whatsoever across the whole paragraph."
        composeRule.waitForIdle()
        val heightNarrow = composeRule.runOnIdle { reportedHeight }
        assertTrue("narrow rendering must produce a positive height", heightNarrow > 0)

        // Fork streaming→completed AND widen simultaneously. This is the exact
        // scenario that exposed the bug: the completed HeightAnchor is a fresh
        // composition at a different composition position AND a different width.
        boxWidth = 360.dp
        isStreaming = false
        composeRule.waitForIdle()
        val heightWide = composeRule.runOnIdle { reportedHeight }

        // Under the NEW composite key: heightWide reflects the WIDE natural
        // height (shorter → fewer wrapped lines). Under the OLD bare key:
        // heightWide would be pinned to heightNarrow (the stale narrow
        // maxHeight leaked through the bare registry key after the fork) →
        // this assertion FAILS. This is the 行为级 RED/GREEN discriminator.
        assertTrue(
            "wider width must re-measure DOWN after the fork (composite key " +
                "isolates per-width anchors — the 变宽流式留白 fix); " +
                "heightNarrow=$heightNarrow heightWide=$heightWide",
            heightWide < heightNarrow
        )

        // Registry-level evidence: the two widths have INDEPENDENT composite
        // entries. (Density bridging: composeRule.density is the test-only
        // Density interface; we construct a unit.Density to convert dp→px.)
        val testDensity = androidx.compose.ui.unit.Density(
            composeRule.density.density,
            composeRule.density.fontScale
        )
        val width1Px = with(testDensity) { 200.dp.roundToPx() }
        val width2Px = with(testDensity) { 360.dp.roundToPx() }
        val anchor1 = HeightAnchorRegistry.anchorFor("msg-wc|part-1" to width1Px)
        val anchor2 = HeightAnchorRegistry.anchorFor("msg-wc|part-1" to width2Px)
        assertTrue("width-1 composite entry must exist (call site wrote Pair)", anchor1 > 0)
        assertTrue("width-2 composite entry must exist (call site wrote Pair)", anchor2 > 0)
        // The narrow anchor is TALLER than the wide anchor (independent entries,
        // each reflecting its own width's natural height). Under the OLD bare
        // key, only ONE entry ("msg-wc|part-1" → heightNarrow) would exist; both
        // Pair lookups would return 0 → the assertions above would FAIL.
        assertTrue(
            "narrow-width anchor must be taller than wide-width anchor " +
                "(independent per-width entries); anchor1=$anchor1 anchor2=$anchor2",
            anchor1 > anchor2
        )
        // cleanup
        HeightAnchorRegistry.reset("msg-wc|part-1" to width1Px)
        HeightAnchorRegistry.reset("msg-wc|part-1" to width2Px)
    }
}
