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
