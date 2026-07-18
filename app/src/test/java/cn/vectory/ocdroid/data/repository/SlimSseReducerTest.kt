package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.SlimSessionDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cluster A (slim SSE reducer): pure-function unit tests for [reduceSlimDigest]
 * + [SlimSseState]. Drives the reducer with synthetic `session.digest` frames
 * and asserts:
 *
 *  1. **Field-absent semantics**: a digest that omits a field leaves the
 *     local value untouched (§3 debounce — only changed fields emitted).
 *  2. **Fetch decision (§5 A2=A)**: a digest whose updatedAt is strictly
 *     newer than the local bookmark triggers [SlimFetchMessages] anchored on
 *     the PRIOR bookmark (so the server returns the boundary message for
 *     messageID dedup). A digest with non-newer updatedAt is a no-op.
 *  3. **Cold path**: first-ever digest for a session with updatedAt set
 *     triggers a fetch anchored on 0L.
 *  4. **Message-id-only path** (defensive): a digest that carries only a
 *     fresh messageId (no updatedAt) triggers a fetch with the prior
 *     bookmark (or 0L) — covers sidecars that omit `updatedAt` on pure
 *     status digests.
 *  5. **State evolution**: archived/deleted/status fields merge correctly
 *     across a sequence of digests.
 *
 * Pure — no IO, no coroutines, no Android deps.
 */
class SlimSseReducerTest {

    private lateinit var state: SlimSseState

    @Before
    fun setUp() {
        state = SlimSseState()
    }

    // ── 1. Field-absent semantics ──────────────────────────────────────────

    @Test
    fun `digest with only status updates status and leaves other fields untouched`() {
        // Seed: full state.
        reduceSlimDigest(
            state,
            SlimSessionDigest(
                sessionId = "s1",
                directory = "/workdir",
                status = "idle",
                messageId = "m1",
                updatedAt = 100L,
            )
        )
        // New digest: only status changed.
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", status = "busy")
        )
        val merged = state.get("s1")!!
        assertEquals("busy", merged.status)
        // Untouched:
        assertEquals("/workdir", merged.directory)
        assertEquals("m1", merged.messageId)
        assertEquals(100L, merged.updatedAt)
        // Same updatedAt → no fetch.
        assertNull(decision)
    }

    @Test
    fun `absent fields are NOT reset to null`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(
                sessionId = "s1",
                directory = "/a",
                status = "idle",
                messageId = "m1",
                updatedAt = 5L,
                archived = 99L,
                deleted = false,
            )
        )
        // A subsequent digest carrying NOTHING but sessionId is a no-op
        // (defensive: sidecar shouldn't emit such a frame, but be tolerant).
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "s1"))
        val merged = state.get("s1")!!
        assertEquals("/a", merged.directory)
        assertEquals("idle", merged.status)
        assertEquals("m1", merged.messageId)
        assertEquals(5L, merged.updatedAt)
        assertEquals(99L, merged.archived)
        assertFalse(merged.deleted)
    }

    // ── 2. Fetch decision — strictly newer updatedAt ───────────────────────

    @Test
    fun `fetch fires when updatedAt strictly newer than prior bookmark`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L)
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 200L)
        )
        assertNotNull("strictly newer updatedAt must trigger a fetch", decision)
        // §5: anchor on the PRIOR bookmark so server returns the boundary
        // message (time.updated >= ts).
        assertEquals(100L, decision!!.since)
        assertEquals("s1", decision.sessionId)
        // Bookmark advanced.
        assertEquals(200L, state.get("s1")!!.updatedAt)
    }

    @Test
    fun `fetch does NOT fire when updatedAt equals prior`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L)
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L)
        )
        assertNull("equal updatedAt is a no-op (debounced re-emit)", decision)
    }

    @Test
    fun `fetch does NOT fire when updatedAt older than prior`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 200L)
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", updatedAt = 100L)
        )
        assertNull("stale (older) updatedAt is a no-op", decision)
        // Local bookmark is NOT regressed.
        assertEquals(200L, state.get("s1")!!.updatedAt)
    }

    // ── 3. Cold path ───────────────────────────────────────────────────────

    @Test
    fun `first-ever digest with updatedAt triggers fetch anchored on zero`() {
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "cold", updatedAt = 42L)
        )
        assertNotNull(decision)
        assertEquals(0L, decision!!.since)
        assertEquals("cold", decision.sessionId)
        assertEquals(42L, state.get("cold")!!.updatedAt)
    }

    @Test
    fun `first-ever digest without updatedAt does NOT fetch`() {
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "cold", status = "idle")
        )
        assertNull(decision)
        // Still seeds state.
        assertNotNull(state.get("cold"))
        assertEquals("idle", state.get("cold")!!.status)
    }

    // ── 4. Message-id-only path (defensive) ────────────────────────────────

    @Test
    fun `messageId change without updatedAt triggers fetch with prior bookmark`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", messageId = "m1", updatedAt = 100L)
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", messageId = "m2")
        )
        assertNotNull("fresh messageId alone must trigger fetch", decision)
        assertEquals(100L, decision!!.since)
        assertEquals("m2", state.get("s1")!!.messageId)
    }

    @Test
    fun `same messageId without updatedAt does NOT fetch`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", messageId = "m1", updatedAt = 100L)
        )
        val decision = reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", messageId = "m1")
        )
        assertNull(decision)
    }

    // ── 5. State evolution: archived / deleted / status ────────────────────

    @Test
    fun `archived flag flows into state when emitted`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", archived = 1234567890L)
        )
        val merged = state.get("s1")!!
        assertEquals(1234567890L, merged.archived)
    }

    @Test
    fun `deleted flag flows into state when emitted`() {
        reduceSlimDigest(
            state,
            SlimSessionDigest(sessionId = "s1", deleted = true)
        )
        assertTrue(state.get("s1")!!.deleted)
    }

    @Test
    fun `multiple sessions accumulate independently`() {
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "a", updatedAt = 10L))
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "b", updatedAt = 20L))
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "a", updatedAt = 30L))

        assertEquals(30L, state.get("a")!!.updatedAt)
        assertEquals(20L, state.get("b")!!.updatedAt)
        assertEquals(2, state.all().size)
    }

    @Test
    fun `bumpUpdatedAt only advances forward`() {
        state.put("s1", SlimSessionState(sessionId = "s1", updatedAt = 100L))
        state.bumpUpdatedAt("s1", 50L)  // older
        assertEquals(100L, state.get("s1")!!.updatedAt)
        state.bumpUpdatedAt("s1", 150L)  // newer
        assertEquals(150L, state.get("s1")!!.updatedAt)
    }

    @Test
    fun `bumpUpdatedAt on unknown session seeds entry`() {
        state.bumpUpdatedAt("fresh", 99L)
        assertEquals(99L, state.get("fresh")!!.updatedAt)
    }

    @Test
    fun `clear resets all bookmarks`() {
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "a", updatedAt = 10L))
        reduceSlimDigest(state, SlimSessionDigest(sessionId = "b", updatedAt = 20L))
        state.clear()
        assertTrue(state.all().isEmpty())
        assertNull(state.get("a"))
    }
}
