package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PartState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §T4 FREEZE — ChatScaffold / ChatMessageContent mechanical split contracts.
 *
 * Status: GREEN characterization + documentation inventory. Deliberately does
 * NOT reference any NEW public types that the split would introduce (no
 * `T4ChatUiSplitMarker`, no package-visible interfaces) because that would
 * leave [check.sh] permanently RED until implementation lands — and the T4
 * split is a multi-step extraction that may sit open for weeks. Instead this
 * file pins the contracts that ALREADY hold today and that the split must
 * preserve:
 *
 *   1. Inventory of planned extract targets + current oversized functions
 *      (§1)                       — documentation (assertTrue)
 *   2. Smoke contract: ChatScaffold entry @Composable still exists (§2)
 *                              — reflection, GREEN now, RED on rename/remove
 *   3. T1 state bridge: buildRenderBlocks + fold expand still read
 *      expandedParts keys (§3)   — pure-helper pin, one small seam test
 *   4. ChatMessageList lifted pure-helper seam API surface (§4)
 *                              — direct-call existence pin (no behaviour dupe)
 *   5. Overlay discipline: chat surfaces stay A/B/C layers (§5)
 *                              — documentation (assertTrue)
 *
 * Hard rules honoured: tests only, GREEN (main stays green until impl), does
 * not weaken T0/T1, does not implement the UI split.
 *
 * Recon baseline (HEAD=572c0e1):
 *   ChatScaffold.kt          1522 — shell chrome, drawer, pager, overlays
 *   ChatMessageContent.kt    1388 — ChatMessageList 82–1251 scroll/auto-follow/paging
 *   ChatRenderBlockBuilder.kt 250 — already extracted pure
 *   ChatMessageNavFab.kt      105 — already extracted
 *   ChatTopBar.kt             700
 */
class T4ChatUiSplitFreezeTest {

    // ── §1. Inventory: planned extract targets + oversized functions ────────

    @Test
    fun `T4 inventory - planned extract targets and oversized functions are documented`() {
        // GREEN documentation freeze. The T4 split will mechanically extract
        // the oversized chat shell + message list into smaller hosts. These
        // assertTrue(true) anchors exist so the plan is versioned alongside
        // the code (git blame on this test = the contract of record). Update
        // the ranges/notes HERE when the split lands, do not delete the test.
        //
        // Planned extract targets (mechanical, behaviour-preserving):
        //   - ChatChrome          : the shell-chrome spine of ChatScaffold
        //                           (drawer + pager + status slot + composer)
        //   - ChatDrawerHost      : ModalNavigationDrawer + RecentSessionsDrawer
        //                           + new-session workdir picker (§drawer-new-session)
        //   - ChatOverlayHost     : the parity overlays (Todo/Context/workdir
        //                           pickers + error + TOFU dialogs) hoisted out
        //                           of the ChatScaffold tail (lines ~1411-1512)
        //   - ChatSessionPager    : the HorizontalPager page-per-root-session
        //                           switcher + its two ChatMessageList call sites
        //   - ChatMessageList seam: already its own internal @Composable
        //                           (ChatMessageContent.kt:82-1251) — the split
        //                           carves its scroll/auto-follow/paging body
        //                           into focused files; the pure helpers below
        //                           are the stable, JVM-testable API surface.
        //
        // Current oversized functions (HEAD=572c0e1, the reason T4 exists):
        //   - ChatScaffold.kt:114-1512  single fun ChatScaffold (~1398 lines)
        //   - ChatMessageContent.kt:82-1251 single internal fun ChatMessageList
        //                                  (~1170 lines, the scroll state machine)
        //
        // Already-extracted (do NOT re-extract — reference contracts):
        //   - ChatRenderBlockBuilder.kt (250)  buildRenderBlocks + RenderBlock ADT
        //   - ChatMessageNavFab.kt      (105)  ChatMessageNavFab composable
        assertTrue("ChatChrome / ChatDrawerHost / ChatOverlayHost / ChatSessionPager are the planned T4 extract targets", true)
        assertTrue("ChatScaffold.kt:114-1512 is the oversized shell-chrome spine", true)
        assertTrue("ChatMessageContent.kt:82-1251 is the oversized ChatMessageList body", true)
        assertTrue("ChatRenderBlockBuilder.kt + ChatMessageNavFab.kt are already extracted (reference, not re-extract)", true)
    }

    // ── §2. Smoke contract: ChatScaffold entry @Composable must survive ────

    @Test
    fun `T4 smoke - ChatScaffold entry composable survives the split`() {
        // SOFT but real: reflection pins that the chat-shell ENTRY remains a
        // top-level @Composable named `ChatScaffold` in package
        // cn.vectory.ocdroid.ui.chat (file ChatScaffold.kt → facade
        // ChatScaffoldKt). GREEN today; goes RED only if the entry is renamed
        // or removed — exactly the regression the "still exists as entry"
        // smoke contract is for. The body may shrink as overlays / drawer /
        // pager extract out, but the public entry name + package MUST stay so
        // AppShell's call site (hiltViewModel wiring) is untouched.
        val klass = Class.forName("cn.vectory.ocdroid.ui.chat.ChatScaffoldKt")
        val hasEntry = klass.declaredMethods.any { it.name == "ChatScaffold" }
        assertTrue(
            "ChatScaffold must remain the chat-shell entry @Composable " +
                "(ChatScaffoldKt.ChatScaffold disappeared — did T4 rename the entry?)",
            hasEntry,
        )
    }

    // ── §3. T1 state bridge pin: render blocks + expandedParts fold seam ────

    @Test
    fun `T1 bridge - buildRenderBlocks and fold expand still read expandedParts keys`() {
        // §T1 state bridge contract (must preserve across the split):
        // ChatMessageList reads three slices that the chrome/content boundary
        // must keep flowing to the message timeline:
        //   - streamingPartTexts   (chatState.streamingPartTexts)
        //   - expandedParts        (chatVM.expandedParts) — fold/patch/tool expand
        //   - partExpandStates     (chatState.partExpandStates) — "展开省略内容"
        // The fold seam is the one with a pure helper (isCrossMessageFoldExpanded)
        // so it is JVM-pinnable WITHOUT Robolectric. This is ONE small seam pin
        // (NOT a re-run of ChatRenderBlockBuilderTest) — it proves the builder
        // output + the expand lookup compose, so any extraction that moves
        // ChatMessageList's body cannot silently drop the expandedParts wiring.
        val m1 = Message(id = "m1", role = "assistant")
        val m2 = Message(id = "m2", role = "assistant")
        val blocks = buildRenderBlocks(
            messages = listOf(m1, m2),
            partsByMessage = mapOf(
                "m1" to listOf(Part(id = "a", messageId = "m1", type = "tool", tool = "bash", state = PartState("completed"))),
                "m2" to listOf(Part(id = "b", messageId = "m2", type = "tool", tool = "bash", state = PartState("completed"))),
            ),
            streamingPartTexts = emptyMap(),
            staleQuestionPartKeys = emptySet(),
            streamingReasoningPartId = null,
            sessionIsRunning = false,
        )

        // Two adjacent tool parts across messages fold into ONE Fold block
        // keyed by the first part id — the shape expandedParts addresses.
        val fold = blocks.single { it is RenderBlock.Fold } as RenderBlock.Fold
        val folded = fold.asFoldedToolRun()

        // collapsed by default — the expandedParts map does not yet carry the key
        assertFalse("fold must be collapsed when expandedParts lacks the fold key", isCrossMessageFoldExpanded(folded, emptyMap()))
        // expanded when the fold|<firstPartId> key is present — the T1 bridge
        // contract: the SAME expandedParts map flows from chrome to content.
        assertTrue("fold must expand when expandedParts carries the fold key", isCrossMessageFoldExpanded(folded, mapOf("fold|a" to true)))
    }

    // ── §4. ChatMessageList lifted pure-helper seam API surface ─────────────

    @Test
    fun `T4 seam - ChatMessageList lifted pure helpers remain accessible in package`() {
        // Existence pin (NOT a behaviour re-test — ChatMessageContentHelpersTest
        // owns the behaviour matrix). These three helpers were lifted OUT of the
        // @Composable ChatMessageList body specifically so they are JVM-testable
        // without Robolectric. The T4 split carves the composable body into new
        // files; these helpers MUST stay internal top-level in package
        // cn.vectory.ocdroid.ui.chat (same package = this test still resolves
        // them). If a splitter moves one to a different package or renames it,
        // this file fails to COMPILE — the conscious-decision anchor a freeze
        // is supposed to provide, on exactly the stable API surface.
        val conversation = RenderBlock.Conversation(
            message = Message(id = "seam-assistant", role = "assistant"),
            parts = emptyList(),
            id = "conversation|seam-assistant|empty",
        )
        // shouldRenderInFlightEmpty (ChatMessageContent.kt:1253)
        assertTrue(shouldRenderInFlightEmpty(conversation, sessionIsRunning = true))
        assertFalse(shouldRenderInFlightEmpty(conversation, sessionIsRunning = false))

        // lazyColumnKeyList (ChatMessageContent.kt:1336) — empty-input contract
        assertTrue(
            lazyColumnKeyList(
                streamingReasoningPart = null,
                sessionDiff = null,
                renderBlocks = emptyList(),
                messages = emptyList(),
                hasMoreMessages = true,
                olderMessagesCursor = "cursor",
            ).isEmpty(),
        )

        // resolveRestoreIndex (ChatMessageContent.kt:1373) — empty-keys contract
        assertTrue(
            resolveRestoreIndex(
                cn.vectory.ocdroid.ui.ScrollCheckpoint(anchorKey = null, fallbackIndex = 0, offset = 0),
                emptyList(),
            ) == null,
        )
    }

    // ── §5. Overlay discipline: chat surfaces stay A/B/C layers ─────────────

    @Test
    fun `T4 overlay discipline - chat shell overlays stay A_B_C layers per ui-style-spec`() {
        // GREEN documentation freeze. The T4 split will move overlays out of
        // ChatScaffold's tail (lines ~1411-1512) into a ChatOverlayHost. Every
        // overlay surface MUST remain on its mandated layer (docs/ui-style-spec.md
        // three-layer rule) — the extraction is mechanical relocation, NOT a
        // licence to invent ad-hoc popup surfaces. Shared primitives from
        // ui/theme/ (AppBottomSheet / AppConfirmDialog / AppFormDialog /
        // AppSectionHeader / PickerTrailingCheck / Dimens) MUST be reused.
        //
        // Current ChatScaffold overlay inventory (must survive on its layer):
        //   A = anchored DropdownMenu (≤6 items, trigger-anchored):
        //       - conversation overflow menu → ALREADY in ChatTopBar (co-located
        //         with its ContextUsageRing trigger; ChatScaffold.kt:1514-1521
        //         documents the removal of the old standalone overflow composable)
        //   B = AppBottomSheet (list / preview):
        //       - TodoListPanel sheet        (ChatScaffold.kt:1425, AppBottomSheet)
        //       - workdir picker sheet       (ChatScaffold.kt:1453, AppBottomSheet)
        //       - SessionPicker / Agent / Model sheets (state owned in scaffold,
        //         composables in Composer.kt / SessionPickerSheet.kt)
        //   C = AlertDialog family (form / blocking / destructive confirm):
        //       - error-detail AlertDialog    (ChatScaffold.kt:1483)
        //       - TofuTrustDialog            (ChatScaffold.kt:1507, AlertDialog family)
        //       - ContextUsageDialog         (ChatScaffold.kt:1437, AlertDialog family)
        //
        // Forbidden after the split: ad-hoc Popup / Dialog / custom bottom
        // surface that bypasses the three-layer rule; scattered dp literals
        // (spacing MUST go through Dimens).
        assertTrue("Layer A (DropdownMenu) overflow stays anchored in ChatTopBar, not re-centralised in the shell", true)
        assertTrue("Layer B (AppBottomSheet) picker/preview surfaces keep using ui/theme AppBottomSheet", true)
        assertTrue("Layer C (AlertDialog family) blocking/destructive surfaces keep using the shared dialog primitives", true)
        assertTrue("No new overlay surface may bypass the A/B/C three-layer rule during the T4 extract", true)
    }
}
