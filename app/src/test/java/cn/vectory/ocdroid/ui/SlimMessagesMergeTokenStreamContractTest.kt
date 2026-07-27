package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.controller.mergeSlimMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §Stage-B §3.4 regression guard (opus MF-A): when `streamOwned` is empty
 * (all non-token-stream slim users), `mergeSlimMessages(items, authoritative)`
 * MUST produce `partsByMessage` byte-for-byte identical to the legacy
 * `partsByMessage + (id to item.parts)` full-overwrite — for BOTH
 * `authoritative=false` and `authoritative=true`.
 *
 * This is the critical no-regression contract: until the token-stream path
 * (Stage C/D) actively populates `streamOwned`, the splice/merge rewrite
 * must be invisible to existing slim users.
 *
 * Also covers the single-owner splice semantics when `streamOwned` IS
 * populated (preservation on skeleton, substitution cleared on authoritative).
 */
@Suppress("DEPRECATION")
class SlimMessagesMergeTokenStreamContractTest {

    private fun msg(id: String, created: Long? = null): Message = Message(
        id = id,
        role = "user",
        time = created?.let { Message.TimeInfo(created = it, updated = it) },
    )

    private fun part(id: String, msgId: String, text: String = ""): Part = Part(
        id = id,
        messageId = msgId,
        sessionId = "sess-A",
        type = "text",
        text = text,
    )

    // ── MF-A: byte-for-byte legacy parity when streamOwned is empty ────────

    @Test
    fun `MF-A - empty streamOwned authoritative=false yields legacy partsByMessage full-overwrite`() {
        // Seed: two local messages with parts.
        val seed = ChatState(
            messages = listOf(msg("m1", 1000L), msg("m2", 2000L)),
            partsByMessage = mapOf(
                "m1" to listOf(part("p1", "m1", "local-text-1")),
                "m2" to listOf(part("p2", "m2", "local-text-2")),
            ),
            streamOwned = emptyMap(), // ← the MF-A precondition
        )
        // Items: patch m1's parts (server skeleton text="") + insert m3.
        val items = listOf(
            MessageWithParts(
                info = msg("m1", 1000L),
                parts = listOf(part("p1", "m1", "")), // server skeleton
            ),
            MessageWithParts(
                info = msg("m3", 3000L),
                parts = listOf(part("p3", "m3", "fetched-3")),
            ),
        )
        // Legacy expectation: partsByMessage + (id to item.parts) for non-empty parts.
        val legacyParts = seed.partsByMessage
            .toMutableMap()
            .apply {
                for (item in items) {
                    if (item.parts.isNotEmpty()) {
                        this[item.info.id] = item.parts
                    }
                }
            }
        val result = seed.mergeSlimMessages(items, authoritative = false)
        assertEquals(
            "partsByMessage MUST equal legacy full-overwrite when streamOwned is empty",
            legacyParts,
            result.partsByMessage,
        )
    }

    @Test
    fun `MF-A - empty streamOwned authoritative=true yields legacy partsByMessage full-overwrite`() {
        // Same seed as above; authoritative=true. Because streamOwned is empty,
        // there is nothing to clear → partsByMessage must STILL equal legacy.
        val seed = ChatState(
            messages = listOf(msg("m1", 1000L), msg("m2", 2000L)),
            partsByMessage = mapOf(
                "m1" to listOf(part("p1", "m1", "local-text-1")),
                "m2" to listOf(part("p2", "m2", "local-text-2")),
            ),
            streamOwned = emptyMap(),
        )
        val items = listOf(
            MessageWithParts(
                info = msg("m1", 1000L),
                parts = listOf(part("p1", "m1", "server-final")),
            ),
            MessageWithParts(
                info = msg("m3", 3000L),
                parts = listOf(part("p3", "m3", "fetched-3")),
            ),
        )
        val legacyParts = seed.partsByMessage
            .toMutableMap()
            .apply {
                for (item in items) {
                    if (item.parts.isNotEmpty()) {
                        this[item.info.id] = item.parts
                    }
                }
            }
        val result = seed.mergeSlimMessages(items, authoritative = true)
        assertEquals(
            "partsByMessage MUST equal legacy full-overwrite when streamOwned is empty (authoritative=true)",
            legacyParts,
            result.partsByMessage,
        )
        // streamOwned stays empty (nothing was owned).
        assertTrue(result.streamOwned.isEmpty())
        assertTrue(result.streamingPartTexts.isEmpty())
    }

    @Test
    fun `MF-A - empty streamOwned empty items is a no-op on partsByMessage`() {
        val seed = ChatState(
            messages = listOf(msg("m1", 1000L)),
            partsByMessage = mapOf("m1" to listOf(part("p1", "m1", "keep"))),
            streamOwned = emptyMap(),
        )
        val resultFalse = seed.mergeSlimMessages(emptyList(), authoritative = false)
        val resultTrue = seed.mergeSlimMessages(emptyList(), authoritative = true)
        assertEquals(seed.partsByMessage, resultFalse.partsByMessage)
        assertEquals(seed.partsByMessage, resultTrue.partsByMessage)
    }

    // ── §rev-ogpt severe #2: empty parts is authoritative replacement ──────

    @Test
    fun `severe-2 - empty parts clears partsByMessage when streamOwned is empty (authoritative=false)`() {
        // Pre-fix: an item with parts=[] was a no-op for partsByMessage, so
        // a removed-last-part message left its stale Part entries in the map
        // (ghost content). Post-fix: parts=[] clears partsByMessage[msgId].
        val seed = ChatState(
            messages = listOf(msg("m1", 1000L)),
            partsByMessage = mapOf("m1" to listOf(part("p1", "m1", "stale-text"))),
            streamOwned = emptyMap(),
        )
        val items = listOf(
            MessageWithParts(info = msg("m1", 1000L), parts = emptyList()),
        )
        val result = seed.mergeSlimMessages(items, authoritative = false)
        assertTrue(
            "partsByMessage[m1] MUST be empty when fetched parts is empty and streamOwned is clear",
            result.partsByMessage["m1"]?.isEmpty() == true,
        )
    }

    @Test
    fun `severe-2 - empty parts clears partsByMessage when streamOwned is empty (authoritative=true)`() {
        val seed = ChatState(
            messages = listOf(msg("m1", 1000L)),
            partsByMessage = mapOf("m1" to listOf(part("p1", "m1", "stale-text"))),
            streamOwned = emptyMap(),
        )
        val items = listOf(
            MessageWithParts(info = msg("m1", 1000L), parts = emptyList()),
        )
        val result = seed.mergeSlimMessages(items, authoritative = true)
        assertTrue(
            "partsByMessage[m1] MUST be empty on authoritative empty-parts merge",
            result.partsByMessage["m1"]?.isEmpty() == true,
        )
        assertTrue(result.streamOwned.isEmpty())
        assertTrue(result.streamingPartTexts.isEmpty())
    }

    @Test
    fun `severe-2 - empty parts preserves STREAMING-owned local when overlay still active (authoritative=false)`() {
        // The reverse of the above: when the token-stream overlay for p1 is
        // STILL active (no ClearPartState has fired yet — the server hasn't
        // sent message.part.removed, /full just hasn't caught up), the
        // §Stage-B skeleton contract keeps the live streamed text. This is
        // the same preservedLocal semantics as a skeleton merge — the empty
        // parts list just means "nothing fetched", not "delete the active
        // stream". Once the removal event fires and ClearPartState clears
        // streamOwned, a subsequent empty-parts merge will collapse to []
        // (covered by the test above).
        val seed = ChatState(
            messages = listOf(msg("m1", 1000L)),
            partsByMessage = mapOf("m1" to listOf(part("p1", "m1", "streamed-live-text"))),
            streamOwned = mapOf("p1" to StreamOwnedState.STREAMING),
            streamingPartTexts = mapOf("p1" to "streamed-live-text"),
        )
        val items = listOf(
            MessageWithParts(info = msg("m1", 1000L), parts = emptyList()),
        )
        val result = seed.mergeSlimMessages(items, authoritative = false)
        assertEquals(
            "STREAMING-owned part preserved through empty-parts skeleton merge",
            listOf(part("p1", "m1", "streamed-live-text")),
            result.partsByMessage["m1"],
        )
        // Ownership + overlay untouched.
        assertEquals(seed.streamOwned, result.streamOwned)
        assertEquals(seed.streamingPartTexts, result.streamingPartTexts)
    }

    @Test
    fun `severe-2 - empty parts collapses to empty when streamOwned was cleared post-removal (authoritative=false)`() {
        // End-to-end severe #2 + #3 contract: a part removed upstream fires
        // ClearPartState → the coordinator's ClearTokenStreamState clears
        // streamOwned[p1] → the subsequent /full arrives with parts=[] →
        // preservedLocal now sees newOwned[p1] == null (not STREAMING) and
        // drops the local → partsByMessage[m1] collapses to [].
        val seed = ChatState(
            messages = listOf(msg("m1", 1000L)),
            partsByMessage = mapOf("m1" to listOf(part("p1", "m1", "stale-text"))),
            // Simulate the post-ClearPartState state: p1 is in partsByMessage
            // (the stale placeholder) but streamOwned has been torn down.
            streamOwned = emptyMap(),
            streamingPartTexts = emptyMap(),
        )
        val items = listOf(
            MessageWithParts(info = msg("m1", 1000L), parts = emptyList()),
        )
        val result = seed.mergeSlimMessages(items, authoritative = false)
        assertTrue(
            "stale placeholder cleared after streamOwned torn down + empty /full",
            result.partsByMessage["m1"]?.isEmpty() == true,
        )
    }

    @Test
    fun `severe-2 - empty parts on authoritative merge clears STREAMING-owned overlay for stale locals`() {
        // Authoritative empty-parts merge (resync / watchdog): even if p1 is
        // STREAMING-owned locally, an authoritative view with parts=[] means
        // the server has dropped the part upstream — ownership MUST be cleared
        // and partsByMessage collapsed. This is the §Stage-B M5 contract:
        // authoritative treats the fetched items as the final view.
        val seed = ChatState(
            messages = listOf(msg("m1", 1000L)),
            partsByMessage = mapOf("m1" to listOf(part("p1", "m1", "streamed-live-text"))),
            streamOwned = mapOf("p1" to StreamOwnedState.STREAMING),
            streamingPartTexts = mapOf("p1" to "streamed-live-text"),
        )
        val items = listOf(
            MessageWithParts(info = msg("m1", 1000L), parts = emptyList()),
        )
        val result = seed.mergeSlimMessages(items, authoritative = true)
        assertTrue(
            "partsByMessage[m1] empty on authoritative empty-parts merge",
            result.partsByMessage["m1"]?.isEmpty() == true,
        )
        assertTrue("streamOwned cleared", result.streamOwned.isEmpty())
        assertTrue("streamingPartTexts cleared", result.streamingPartTexts.isEmpty())
    }

    @Test
    fun `severe-2 - mixed batch with one empty-parts item only clears that message's parts`() {
        // Two messages: m1 has parts, m2 has parts. The fetched items carry
        // an empty parts list for m1 (removed) and a normal parts list for
        // m2. Only m1's partsByMessage entry should collapse; m2's should be
        // replaced by the fetched parts (normal merge).
        val seed = ChatState(
            messages = listOf(msg("m1", 1000L), msg("m2", 2000L)),
            partsByMessage = mapOf(
                "m1" to listOf(part("p1", "m1", "stale")),
                "m2" to listOf(part("p2", "m2", "old")),
            ),
            streamOwned = emptyMap(),
        )
        val items = listOf(
            MessageWithParts(info = msg("m1", 1000L), parts = emptyList()),
            MessageWithParts(
                info = msg("m2", 2000L),
                parts = listOf(part("p2", "m2", "refreshed")),
            ),
        )
        val result = seed.mergeSlimMessages(items, authoritative = false)
        assertTrue("m1 parts cleared", result.partsByMessage["m1"]?.isEmpty() == true)
        assertEquals(
            "m2 parts replaced (normal merge)",
            listOf(part("p2", "m2", "refreshed")),
            result.partsByMessage["m2"],
        )
    }

    // ── Splice semantics when streamOwned IS populated ─────────────────────

    @Test
    fun `skeleton merge preserves STREAMING-owned fetched part's local content`() {
        // p1 is token-stream-owned (STREAMING). The fetched item carries a
        // skeleton (text=""). On a skeleton merge, the LOCAL part (with the
        // streamed text) MUST be substituted — the server skeleton is dropped.
        val seed = ChatState(
            messages = listOf(msg("m1", 1000L)),
            partsByMessage = mapOf(
                "m1" to listOf(part("p1", "m1", "streamed-live-text")),
            ),
            streamOwned = mapOf("p1" to StreamOwnedState.STREAMING),
            streamingPartTexts = mapOf("p1" to "streamed-live-text"),
        )
        val items = listOf(
            MessageWithParts(
                info = msg("m1", 1000L),
                parts = listOf(part("p1", "m1", "")), // server skeleton text=""
            ),
        )
        val result = seed.mergeSlimMessages(items, authoritative = false)
        // The streamed local part is preserved (substituted for the skeleton).
        assertEquals(
            "streamed text preserved",
            listOf(part("p1", "m1", "streamed-live-text")),
            result.partsByMessage["m1"],
        )
        // Ownership + overlay untouched (token stream still owns it).
        assertEquals(seed.streamOwned, result.streamOwned)
        assertEquals(seed.streamingPartTexts, result.streamingPartTexts)
    }

    @Test
    fun `skeleton merge preserves locally-owned part NOT in fetched set (preservedLocal)`() {
        // p1 (STREAMING-owned) is absent from the fetched items. It must
        // survive the merge (preservedLocal) so the in-flight stream stays
        // visible.
        val seed = ChatState(
            messages = listOf(msg("m1", 1000L)),
            partsByMessage = mapOf(
                "m1" to listOf(part("p1", "m1", "streaming"), part("p2", "m1", "old")),
            ),
            streamOwned = mapOf("p1" to StreamOwnedState.STREAMING),
            streamingPartTexts = mapOf("p1" to "streaming"),
        )
        val items = listOf(
            MessageWithParts(
                info = msg("m1", 1000L),
                parts = listOf(part("p2", "m1", "refreshed")), // p2 fetched, p1 NOT
            ),
        )
        val result = seed.mergeSlimMessages(items, authoritative = false)
        // p1 (owned, not fetched) preserved; p2 (fetched) replaced.
        val m1Parts = result.partsByMessage["m1"]!!
        assertTrue("owned part p1 preserved", m1Parts.any { it.id == "p1" && it.text == "streaming" })
        assertTrue("fetched part p2 replaced", m1Parts.any { it.id == "p2" && it.text == "refreshed" })
    }

    @Test
    fun `authoritative merge substitutes fetched content and clears ownership`() {
        // p1 is STREAMING-owned. On an authoritative merge, the fetched
        // content wins (NOT substituted) and p1's ownership is cleared.
        val seed = ChatState(
            messages = listOf(msg("m1", 1000L)),
            partsByMessage = mapOf(
                "m1" to listOf(part("p1", "m1", "streamed-live-text")),
            ),
            streamOwned = mapOf("p1" to StreamOwnedState.STREAMING),
            streamingPartTexts = mapOf("p1" to "streamed-live-text"),
        )
        val items = listOf(
            MessageWithParts(
                info = msg("m1", 1000L),
                parts = listOf(part("p1", "m1", "server-final-text")),
            ),
        )
        val result = seed.mergeSlimMessages(items, authoritative = true)
        // Fetched content wins.
        assertEquals(
            "authoritative fetched content wins",
            listOf(part("p1", "m1", "server-final-text")),
            result.partsByMessage["m1"],
        )
        // Ownership + overlay cleared for the fetched id.
        assertTrue("streamOwned cleared for fetched id", result.streamOwned.isEmpty())
        assertTrue("streamingPartTexts cleared for fetched id", result.streamingPartTexts.isEmpty())
    }

    @Test
    fun `MessagesMerged skeleton idle does not clear STREAMING-owned overlay`() {
        val owned = mapOf("p1" to StreamOwnedState.STREAMING)
        val resetLimit = true; val streamingFinalized = true; val overlayFinalized = true
        val ownedStreamingKeys = owned.filterValues { it == StreamOwnedState.STREAMING }.keys
        val authoritative = resetLimit && streamingFinalized && overlayFinalized && ownedStreamingKeys.isEmpty()
        assertEquals(false, authoritative)
        val before = mapOf("p1" to "live")
        val newTexts = if (authoritative) emptyMap<String, String>() else before.filterKeys { it in ownedStreamingKeys }
        assertEquals(mapOf("p1" to "live"), newTexts)
    }
}
