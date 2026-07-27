package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §Stage-B C5 (CRITICAL) + M5 (MAJOR) — pins the freeze-protocol contract
 * for the new route-aware removal + reconciler reducers:
 *
 *  - [AppAction.MessageRemovedConfirmed] MUST be a no-op when
 *    `expectedRouteInstance == 0L` (no active route → no transcript write).
 *  - [AppAction.MessageRemovedConfirmed] MUST be a no-op when the route
 *    token / session id no longer matches the live incarnation.
 *  - [AppAction.MessageRemovedConfirmed] MUST evict the message from
 *    BOTH the flat projection AND [LoadedContent] (dual-projection
 *    invariant) AND clear every streaming-overlay entry owned by the
 *    message's parts so a late straggler frame cannot resurrect ghost
 *    text.
 *  - [AppAction.SlimFullMessageReconciled] MUST merge with
 *    `authoritative=false` semantics — STREAMING token-stream-owned
 *    parts stay owned and are substituted in place of the fetched
 *    skeleton.
 *  - [AppAction.SlimFullMessageReconciled] MUST be a no-op when the
 *    bundle stamp or route token no longer matches the live state.
 *
 * The legacy [AppAction.MessageRemovedFromFull] reducer (deprecated,
 * retained for source compat until the parallel ControllerModule lane
 * migrates the call site) is also pinned: it must continue to evict the
 * message AND clear the streaming overlay (the M5 backport) so the
 * legacy dispatch path cannot leave ghost text either.
 */
@Suppress("DEPRECATION")
class StageBFreezeProtocolReducerTest {

    private val bundle = BundleStamp(generation = 1L, endpointFp = "fp-A")
    private val staleBundle = BundleStamp(generation = 99L, endpointFp = "fp-X")

    private fun msg(id: String, created: Long = 1000L): Message = Message(
        id = id,
        role = "assistant",
        time = Message.TimeInfo(created = created, updated = created),
    )

    private fun part(id: String, msgId: String, text: String? = null): Part = Part(
        id = id,
        messageId = msgId,
        sessionId = "ses-A",
        type = "text",
        text = text,
    )

    private fun chatWithMessageAndOverlay(
        routeInstance: Long = 5L,
        bundleStamp: BundleStamp = bundle,
        withLoadedContent: Boolean = true,
    ): StoreState {
        val chat = ChatState(
            currentSessionId = "ses-A",
            messages = listOf(msg("m1")),
            partsByMessage = mapOf("m1" to listOf(part("p1", "m1", "streamed-text"))),
            streamOwned = mapOf("p1" to StreamOwnedState.STREAMING),
            streamingPartTexts = mapOf("p1" to "streamed-text"),
            deltaBuffer = mapOf("p1" to "pending-delta"),
            fullTextBuffer = mapOf("p1" to "pending-full"),
            pendingFlushPartIds = setOf("p1"),
            streamingReasoningPart = null,
            content = if (withLoadedContent) {
                LoadedContent(
                    sessionId = "ses-A",
                    messages = listOf(msg("m1")),
                    partsByMessage = mapOf("m1" to listOf(part("p1", "m1", "streamed-text"))),
                    routeInstance = routeInstance,
                )
            } else null,
        )
        return StoreState.initial().copy(
            chatRouteInstance = routeInstance,
            liveBundleGeneration = bundleStamp.generation,
            liveEndpointFp = bundleStamp.endpointFp,
            chat = chat,
        )
    }

    // ── MessageRemovedConfirmed ───────────────────────────────────────────

    @Test
    fun `MessageRemovedConfirmed with expectedRouteInstance=0 is a no-op`() {
        val prior = chatWithMessageAndOverlay()
        val out = reduce(
            prior,
            AppAction.MessageRemovedConfirmed(
                sessionId = "ses-A",
                messageId = "m1",
                expectedRouteInstance = 0L,
                bundleStamp = bundle,
            ),
        )
        assertEquals("state unchanged", prior, out)
    }

    @Test
    fun `MessageRemovedConfirmed with stale route token is a no-op`() {
        val prior = chatWithMessageAndOverlay(routeInstance = 5L)
        val out = reduce(
            prior,
            AppAction.MessageRemovedConfirmed(
                sessionId = "ses-A",
                messageId = "m1",
                expectedRouteInstance = 999L,
                bundleStamp = bundle,
            ),
        )
        assertEquals("stale route rejected", prior, out)
    }

    @Test
    fun `MessageRemovedConfirmed with mismatched bundle stamp is a no-op`() {
        val prior = chatWithMessageAndOverlay()
        val out = reduce(
            prior,
            AppAction.MessageRemovedConfirmed(
                sessionId = "ses-A",
                messageId = "m1",
                expectedRouteInstance = 5L,
                bundleStamp = staleBundle,
            ),
        )
        assertEquals("stale bundle rejected", prior, out)
    }

    @Test
    fun `MessageRemovedConfirmed with mismatched sessionId is a no-op`() {
        val prior = chatWithMessageAndOverlay()
        val out = reduce(
            prior,
            AppAction.MessageRemovedConfirmed(
                sessionId = "ses-OTHER",
                messageId = "m1",
                expectedRouteInstance = 5L,
                bundleStamp = bundle,
            ),
        )
        assertEquals("cross-session rejected", prior, out)
    }

    @Test
    fun `MessageRemovedConfirmed evicts message from flat + LoadedContent + stream maps`() {
        val prior = chatWithMessageAndOverlay()
        val out = reduce(
            prior,
            AppAction.MessageRemovedConfirmed(
                sessionId = "ses-A",
                messageId = "m1",
                expectedRouteInstance = 5L,
                bundleStamp = bundle,
            ),
        )
        // Flat projection evicted.
        assertTrue("flat messages emptied for m1", out.chat.messages.none { it.id == "m1" })
        assertTrue("flat partsByMessage emptied for m1", !out.chat.partsByMessage.containsKey("m1"))
        // Streaming overlay fully cleared for the message's parts.
        assertTrue("streamOwned cleared", out.chat.streamOwned.isEmpty())
        assertTrue("streamingPartTexts cleared", out.chat.streamingPartTexts.isEmpty())
        assertTrue("deltaBuffer cleared", out.chat.deltaBuffer.isEmpty())
        assertTrue("fullTextBuffer cleared", out.chat.fullTextBuffer.isEmpty())
        assertTrue("pendingFlushPartIds cleared", out.chat.pendingFlushPartIds.isEmpty())
        // LoadedContent dual-projection invariant: content slot no longer
        // references the evicted message.
        val content = out.chat.content
        assertNotNull("LoadedContent slot preserved (route still active)", content)
        assertTrue(
            "LoadedContent partsByMessage evicted for m1",
            !content!!.partsByMessage.containsKey("m1"),
        )
        assertTrue(
            "LoadedContent messages evicted for m1",
            content.messages.none { it.id == "m1" },
        )
    }

    @Test
    fun `MessageRemovedConfirmed clears streamingReasoningPart when its part belongs to the removed message`() {
        val reasoningPart = Part(
            id = "p-reason",
            messageId = "m1",
            sessionId = "ses-A",
            type = "reasoning",
        )
        val chat = ChatState(
            currentSessionId = "ses-A",
            messages = listOf(msg("m1")),
            partsByMessage = mapOf("m1" to listOf(reasoningPart)),
            streamingReasoningPart = reasoningPart,
            content = LoadedContent(
                sessionId = "ses-A",
                messages = listOf(msg("m1")),
                partsByMessage = mapOf("m1" to listOf(reasoningPart)),
                routeInstance = 5L,
            ),
        )
        val prior = StoreState.initial().copy(
            chatRouteInstance = 5L,
            liveBundleGeneration = bundle.generation,
            liveEndpointFp = bundle.endpointFp,
            chat = chat,
        )
        val out = reduce(
            prior,
            AppAction.MessageRemovedConfirmed(
                sessionId = "ses-A",
                messageId = "m1",
                expectedRouteInstance = 5L,
                bundleStamp = bundle,
            ),
        )
        assertNull("streamingReasoningPart cleared", out.chat.streamingReasoningPart)
    }

    @Test
    fun `MessageRemovedConfirmed preserves unrelated message state`() {
        // Seed two messages; remove only m1. m2 + its overlay must survive.
        val m1Part = part("p1", "m1", "x")
        val m2Part = part("p2", "m2", "y")
        val chat = ChatState(
            currentSessionId = "ses-A",
            messages = listOf(msg("m1", 1000L), msg("m2", 2000L)),
            partsByMessage = mapOf(
                "m1" to listOf(m1Part),
                "m2" to listOf(m2Part),
            ),
            streamOwned = mapOf(
                "p1" to StreamOwnedState.STREAMING,
                "p2" to StreamOwnedState.STREAMING,
            ),
            streamingPartTexts = mapOf("p1" to "x", "p2" to "y"),
            content = LoadedContent(
                sessionId = "ses-A",
                messages = listOf(msg("m1", 1000L), msg("m2", 2000L)),
                partsByMessage = mapOf(
                    "m1" to listOf(m1Part),
                    "m2" to listOf(m2Part),
                ),
                routeInstance = 5L,
            ),
        )
        val prior = StoreState.initial().copy(
            chatRouteInstance = 5L,
            liveBundleGeneration = bundle.generation,
            liveEndpointFp = bundle.endpointFp,
            chat = chat,
        )
        val out = reduce(
            prior,
            AppAction.MessageRemovedConfirmed(
                sessionId = "ses-A",
                messageId = "m1",
                expectedRouteInstance = 5L,
                bundleStamp = bundle,
            ),
        )
        // m2 survives everywhere.
        assertTrue("m2 still in flat messages", out.chat.messages.any { it.id == "m2" })
        assertTrue("m2 still in flat partsByMessage", out.chat.partsByMessage.containsKey("m2"))
        assertEquals(
            "m2's overlay preserved",
            StreamOwnedState.STREAMING,
            out.chat.streamOwned["p2"],
        )
        assertEquals("m2's streaming text preserved", "y", out.chat.streamingPartTexts["p2"])
        assertTrue(
            "m2 still in LoadedContent",
            out.chat.content?.partsByMessage?.containsKey("m2") == true,
        )
    }

    @Test
    fun `MessageRemovedConfirmed collects part IDs from BOTH flat and LoadedContent projections`() {
        // Seed a torn state: LoadedContent has p1b, flat has only p1a.
        // Both part IDs must be cleared from the overlay (defensive — the
        // freeze protocol's dual-projection invariant).
        val m1PartFlat = part("p1a", "m1", "flat-text")
        val m1PartLoaded = part("p1b", "m1", "loaded-text")
        val chat = ChatState(
            currentSessionId = "ses-A",
            messages = listOf(msg("m1")),
            partsByMessage = mapOf("m1" to listOf(m1PartFlat)),
            streamOwned = mapOf(
                "p1a" to StreamOwnedState.STREAMING,
                "p1b" to StreamOwnedState.STREAMING,
            ),
            streamingPartTexts = mapOf("p1a" to "flat-text", "p1b" to "loaded-text"),
            content = LoadedContent(
                sessionId = "ses-A",
                messages = listOf(msg("m1")),
                partsByMessage = mapOf("m1" to listOf(m1PartLoaded)),
                routeInstance = 5L,
            ),
        )
        val prior = StoreState.initial().copy(
            chatRouteInstance = 5L,
            liveBundleGeneration = bundle.generation,
            liveEndpointFp = bundle.endpointFp,
            chat = chat,
        )
        val out = reduce(
            prior,
            AppAction.MessageRemovedConfirmed(
                sessionId = "ses-A",
                messageId = "m1",
                expectedRouteInstance = 5L,
                bundleStamp = bundle,
            ),
        )
        // Both projections' part IDs cleared from the overlay.
        assertFalse("p1a (flat-only) cleared", out.chat.streamOwned.containsKey("p1a"))
        assertFalse("p1b (loaded-only) cleared", out.chat.streamOwned.containsKey("p1b"))
    }

    // ── SlimFullMessageReconciled ─────────────────────────────────────────

    @Test
    fun `SlimFullMessageReconciled preserves STREAMING token-stream-owned part on authoritative=false`() {
        // Seed: m1 has a token-stream-owned part p1 with live streamed text.
        // A `/full` 200 reconcile arrives with a server skeleton (text="")
        // for p1. The reducer MUST substitute the local streamed part and
        // keep ownership intact.
        val streamedPart = part("p1", "m1", "streamed-live-text")
        val chat = ChatState(
            currentSessionId = "ses-A",
            messages = listOf(msg("m1")),
            partsByMessage = mapOf("m1" to listOf(streamedPart)),
            streamOwned = mapOf("p1" to StreamOwnedState.STREAMING),
            streamingPartTexts = mapOf("p1" to "streamed-live-text"),
        )
        val prior = StoreState.initial().copy(
            chatRouteInstance = 5L,
            liveBundleGeneration = bundle.generation,
            liveEndpointFp = bundle.endpointFp,
            chat = chat,
        )
        val skeletonItem = MessageWithParts(
            info = msg("m1"),
            parts = listOf(part("p1", "m1", "")), // server skeleton text=""
        )
        val out = reduce(
            prior,
            AppAction.SlimFullMessageReconciled(
                sessionId = "ses-A",
                message = skeletonItem,
                expectedRouteInstance = 5L,
                bundleStamp = bundle,
            ),
        )
        // Streamed part preserved (skeleton substituted out).
        assertEquals(
            "streamed text preserved through reconcile",
            listOf(streamedPart),
            out.chat.partsByMessage["m1"],
        )
        // Ownership + overlay untouched (token stream still owns it).
        assertEquals(prior.chat.streamOwned, out.chat.streamOwned)
        assertEquals(prior.chat.streamingPartTexts, out.chat.streamingPartTexts)
    }

    @Test
    fun `SlimFullMessageReconciled with stale route token is a no-op`() {
        val prior = StoreState.initial().copy(
            chatRouteInstance = 5L,
            liveBundleGeneration = bundle.generation,
            liveEndpointFp = bundle.endpointFp,
            chat = ChatState(currentSessionId = "ses-A"),
        )
        val out = reduce(
            prior,
            AppAction.SlimFullMessageReconciled(
                sessionId = "ses-A",
                message = MessageWithParts(info = msg("m1"), parts = emptyList()),
                expectedRouteInstance = 999L,
                bundleStamp = bundle,
            ),
        )
        assertEquals("stale route rejected", prior, out)
    }

    @Test
    fun `SlimFullMessageReconciled with stale bundle stamp is a no-op`() {
        val prior = StoreState.initial().copy(
            chatRouteInstance = 5L,
            liveBundleGeneration = bundle.generation,
            liveEndpointFp = bundle.endpointFp,
            chat = ChatState(currentSessionId = "ses-A"),
        )
        val out = reduce(
            prior,
            AppAction.SlimFullMessageReconciled(
                sessionId = "ses-A",
                message = MessageWithParts(info = msg("m1"), parts = emptyList()),
                expectedRouteInstance = 5L,
                bundleStamp = staleBundle,
            ),
        )
        assertEquals("stale bundle rejected", prior, out)
    }

    // ── Legacy MessageRemovedFromFull backport ────────────────────────────

    @Test
    fun `legacy MessageRemovedFromFull evicts message AND clears streaming overlay (M5 backport)`() {
        // The legacy action carries no route token / bundle stamp. The
        // reducer must continue to evict the message (source compat) AND
        // now ALSO clear the streaming overlay (the M5 backport — same
        // contract as MessageRemovedConfirmed so the legacy dispatch path
        // cannot leave ghost text).
        val chat = ChatState(
            currentSessionId = "ses-A",
            messages = listOf(msg("m1")),
            partsByMessage = mapOf("m1" to listOf(part("p1", "m1", "x"))),
            streamOwned = mapOf("p1" to StreamOwnedState.STREAMING),
            streamingPartTexts = mapOf("p1" to "x"),
            deltaBuffer = mapOf("p1" to "d"),
            fullTextBuffer = mapOf("p1" to "f"),
            pendingFlushPartIds = setOf("p1"),
        )
        val prior = StoreState.initial().copy(chat = chat)
        val out = reduce(
            prior,
            AppAction.MessageRemovedFromFull(
                sessionId = "ses-A",
                messageId = "m1",
            ),
        )
        assertTrue("flat messages evicted", out.chat.messages.none { it.id == "m1" })
        assertTrue("flat partsByMessage evicted", !out.chat.partsByMessage.containsKey("m1"))
        assertTrue("streamOwned cleared", out.chat.streamOwned.isEmpty())
        assertTrue("streamingPartTexts cleared", out.chat.streamingPartTexts.isEmpty())
        assertTrue("deltaBuffer cleared", out.chat.deltaBuffer.isEmpty())
        assertTrue("fullTextBuffer cleared", out.chat.fullTextBuffer.isEmpty())
        assertTrue("pendingFlushPartIds cleared", out.chat.pendingFlushPartIds.isEmpty())
    }
}
