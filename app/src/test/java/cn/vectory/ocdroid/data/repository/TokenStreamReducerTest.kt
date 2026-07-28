package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.ResyncReason
import cn.vectory.ocdroid.data.model.TokenStreamFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §Stage-C §3.3 / §3.8 — pins the [TokenStreamReducer] pure state machine.
 * Each transition is exercised in isolation: snapshot→STREAMING+replace,
 * delta→append, done:true→DONE+replace, truncated→clear+effects,
 * resync→clear-all-sid+effects+reconnect-flag, late/orphan delta drop.
 *
 * The reducer is a pure function (state in → state out + effects); no store,
 * no IO — these tests drive it as a black box.
 */
class TokenStreamReducerTest {

    private fun snapshot(
        partId: String = "p1",
        text: String? = "",
        done: Boolean = false,
        truncated: Boolean = false,
        sessionId: String = "s1",
        messageId: String = "m1",
    ) = TokenStreamFrame.PartSnapshot(sessionId, messageId, partId, text, done, truncated)

    private fun delta(
        partId: String = "p1",
        text: String = "x",
        sessionId: String = "s1",
        messageId: String = "m1",
    ) = TokenStreamFrame.PartDelta(sessionId, messageId, partId, text)

    // ── snapshot(done=false) → REPLACE buffer + STREAMING ─────────────────

    @Test
    fun `snapshot streaming replaces buffer and sets STREAMING`() {
        val (state, effects) = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = "hello"),
        )
        val acc = state.parts["p1"]
        assertEquals("hello", acc?.text)
        assertEquals(TokenPartStreamState.STREAMING, acc?.state)
        assertEquals("s1", acc?.sessionId)
        assertEquals("m1", acc?.messageId)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `snapshot streaming replaces an existing DONE buffer back to STREAMING`() {
        // Seed a DONE part, then a fresh streaming snapshot replaces it.
        val done = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = "old", done = true),
        ).first
        val (state, effects) = TokenStreamReducer.reduce(done, snapshot(text = "new"))
        assertEquals("new", state.parts["p1"]?.text)
        assertEquals(TokenPartStreamState.STREAMING, state.parts["p1"]?.state)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `snapshot with null text yields empty buffer`() {
        val (state, _) = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = null),
        )
        assertEquals("", state.parts["p1"]?.text)
    }

    // ── delta → append ───────────────────────────────────────────────────

    @Test
    fun `delta appends to STREAMING buffer`() {
        val streaming = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = "hello"),
        ).first
        val (state, effects) = TokenStreamReducer.reduce(streaming, delta(text = " world"))
        assertEquals("hello world", state.parts["p1"]?.text)
        assertEquals(TokenPartStreamState.STREAMING, state.parts["p1"]?.state)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `multiple deltas accumulate in order`() {
        var state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = "a"),
        ).first
        state = TokenStreamReducer.reduce(state, delta(text = "b")).first
        state = TokenStreamReducer.reduce(state, delta(text = "c")).first
        assertEquals("abc", state.parts["p1"]?.text)
    }

    // ── done:true → DONE + REPLACE final text ────────────────────────────

    @Test
    fun `snapshot done true transitions to DONE and replaces final text`() {
        val streaming = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = "partial"),
        ).first
        // Accumulate some deltas, then a terminal snapshot replaces.
        val withDeltas = TokenStreamReducer.reduce(streaming, delta(text = "+")).first
        val (state, effects) = TokenStreamReducer.reduce(
            withDeltas,
            snapshot(text = "FINAL", done = true),
        )
        assertEquals("FINAL", state.parts["p1"]?.text)
        assertEquals(TokenPartStreamState.DONE, state.parts["p1"]?.state)
        // lite-v2-dev: done:true → TriggerSinceFetch (skeleton reload).
        assertEquals(1, effects.size)
        val trigger = effects[0] as TokenStreamCoordinatorEffect.TriggerSinceFetch
        assertEquals("s1", trigger.sessionId)
        assertTrue(trigger.authoritative)
    }

    // ── §5 C-1: done:true with null text keeps accumulated buffer ──────────

    @Test
    fun `done snapshot with null text keeps accumulated buffer and transitions to DONE`() {
        // Seed a streaming part with accumulated text via snapshot + deltas.
        var state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = "hel"),
        ).first
        state = TokenStreamReducer.reduce(state, delta(text = "lo")).first
        assertEquals("hello", state.parts["p1"]?.text)

        // Terminal snapshot with text:null must NOT overwrite the buffer with "".
        val (next, effects) = TokenStreamReducer.reduce(state, snapshot(text = null, done = true))

        val acc = next.parts["p1"]
        assertEquals("accumulated text must be preserved on done+null text", "hello", acc?.text)
        assertEquals(TokenPartStreamState.DONE, acc?.state)
        // lite-v2-dev: done:true → TriggerSinceFetch (skeleton reload),
        // even with null text. Buffer is preserved, but a reload is triggered.
        assertEquals(1, effects.size)
        val trigger = effects[0] as TokenStreamCoordinatorEffect.TriggerSinceFetch
        assertEquals("s1", trigger.sessionId)
        assertTrue(trigger.authoritative)
    }

    @Test
    fun `done snapshot with null text and no prior buffer yields empty DONE`() {
        // No prior snapshot — done+null text on a fresh part: "" is correct
        // (there was nothing to preserve). Still DONE, emits TriggerSinceFetch.
        val (state, effects) = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = null, done = true),
        )
        assertEquals("", state.parts["p1"]?.text)
        assertEquals(TokenPartStreamState.DONE, state.parts["p1"]?.state)
        assertEquals(1, effects.size)
        val trigger = effects[0] as TokenStreamCoordinatorEffect.TriggerSinceFetch
        assertEquals("s1", trigger.sessionId)
        assertTrue(trigger.authoritative)
    }

    @Test
    fun `done snapshot with non-null text still replaces the buffer (C-1 preserves existing behavior)`() {
        // Regression guard: done with explicit text remains authoritative.
        var state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = "partial"),
        ).first
        state = TokenStreamReducer.reduce(state, delta(text = "+")).first
        assertEquals("partial+", state.parts["p1"]?.text)

        val (next, effects) = TokenStreamReducer.reduce(state, snapshot(text = "FINAL", done = true))
        assertEquals("FINAL", next.parts["p1"]?.text)
        assertEquals(TokenPartStreamState.DONE, next.parts["p1"]?.state)
        // lite-v2-dev: done:true → TriggerSinceFetch (skeleton reload).
        assertEquals(1, effects.size)
        val trigger = effects[0] as TokenStreamCoordinatorEffect.TriggerSinceFetch
        assertEquals("s1", trigger.sessionId)
        assertTrue(trigger.authoritative)
    }

    // ── truncated → clear + ClearPartState + TriggerSinceFetch ───────────

    @Test
    fun `snapshot truncated clears part and emits ClearPartState and TriggerSinceFetch`() {
        val streaming = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = "partial"),
        ).first
        val (state, effects) = TokenStreamReducer.reduce(streaming, snapshot(truncated = true))

        // Part cleared from reducer state.
        assertNull(state.parts["p1"])

        assertEquals(2, effects.size)
        val clear = effects[0] as TokenStreamCoordinatorEffect.ClearPartState
        assertEquals(setOf("p1"), clear.partIds)
        val trigger = effects[1] as TokenStreamCoordinatorEffect.TriggerSinceFetch
        assertEquals("s1", trigger.sessionId)
        assertTrue("resync/truncate fetch must be authoritative", trigger.authoritative)
    }

    @Test
    fun `truncated takes priority over done`() {
        // done=true AND truncated=true → truncated wins (part cleared, not DONE).
        val (state, effects) = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = "x", done = true, truncated = true),
        )
        assertNull(state.parts["p1"])
        assertEquals(2, effects.size)
        assertTrue(effects.any { it is TokenStreamCoordinatorEffect.ClearPartState })
        assertTrue(effects.any { it is TokenStreamCoordinatorEffect.TriggerSinceFetch })
    }

    // ── delta drop: orphan + late ────────────────────────────────────────

    @Test
    fun `orphan delta before any snapshot creates provisional STREAMING entry`() {
        // §orphan-delta-fix (2026-07-26): the server sends deltas BEFORE the
        // initial snapshot. The reducer MUST create a provisional STREAMING
        // entry from the delta (sessionId/messageId/partId/text are all on the
        // wire frame) so the UI renders live text. The old behavior dropped
        // orphan deltas — the user saw NO streaming, only the REST-completed
        // message seconds later.
        val (state, effects) = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            delta(text = "stray"),
        )
        val acc = state.parts["p1"]
        assertEquals("stray", acc?.text)
        assertEquals(TokenPartStreamState.STREAMING, acc?.state)
        assertEquals("s1", acc?.sessionId)
        assertEquals("m1", acc?.messageId)
        // NOT counted as dropped — it was used.
        assertEquals(0L, state.droppedDeltaCount)
        assertTrue(effects.isEmpty())
    }

    // ── orphan delta → snapshot transitions (rev-gpt concern #1 + #4) ──────

    @Test
    fun `orphan deltas then snapshot done=false with shorter text preserves accumulated`() {
        // §orphan-delta-guard: multiple orphan deltas accumulate into a
        // provisional entry. A delayed initial snapshot(done=false) arrives
        // with SHORTER text (stale/empty initial snapshot). The accumulated
        // delta text MUST be preserved — token streaming is append-only, so
        // the authoritative snapshot should always be ≥ the accumulated text.
        // If shorter, it's stale; keep the existing.
        var state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            delta(text = "Hello "),
        ).first
        state = TokenStreamReducer.reduce(state, delta(text = "World")).first
        assertEquals("Hello World", state.parts["p1"]?.text)
        // Delayed stale snapshot with empty text.
        state = TokenStreamReducer.reduce(state, snapshot(text = "", done = false)).first
        assertEquals("Hello World", state.parts["p1"]?.text)
        assertEquals(TokenPartStreamState.STREAMING, state.parts["p1"]?.state)
    }

    @Test
    fun `orphan deltas then snapshot done=false with longer text uses snapshot`() {
        // Normal case: the snapshot's text is LONGER than accumulated deltas
        // (it includes text from deltas the client missed). The snapshot
        // wins — it's the authoritative superset.
        var state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            delta(text = "Hel"),
        ).first
        state = TokenStreamReducer.reduce(state, delta(text = "lo")).first
        assertEquals("Hello", state.parts["p1"]?.text)
        // Authoritative snapshot with full text.
        state = TokenStreamReducer.reduce(state, snapshot(text = "Hello World!", done = false)).first
        assertEquals("Hello World!", state.parts["p1"]?.text)
        assertEquals(TokenPartStreamState.STREAMING, state.parts["p1"]?.state)
    }

    @Test
    fun `orphan delta then snapshot done=true with final text replaces`() {
        // Terminal snapshot(done=true) with explicit final text → the final
        // text is authoritative regardless of what was accumulated. The part
        // transitions to DONE.
        var state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            delta(text = "partial"),
        ).first
        assertEquals("partial", state.parts["p1"]?.text)
        state = TokenStreamReducer.reduce(state, snapshot(text = "final answer", done = true)).first
        assertEquals("final answer", state.parts["p1"]?.text)
        assertEquals(TokenPartStreamState.DONE, state.parts["p1"]?.state)
    }

    @Test
    fun `orphan delta then snapshot done=true with null text preserves accumulated`() {
        // Terminal snapshot(done=true) with text=null → the server signals
        // completion but provides no terminal text. The accumulated delta
        // text is the best available content — keep it and mark DONE.
        var state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            delta(text = "streamed text"),
        ).first
        state = TokenStreamReducer.reduce(state, snapshot(text = null, done = true)).first
        assertEquals("streamed text", state.parts["p1"]?.text)
        assertEquals(TokenPartStreamState.DONE, state.parts["p1"]?.state)
    }

    @Test
    fun `late delta after DONE is dropped and counted`() {
        val done = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = "final", done = true),
        ).first
        assertEquals(0L, done.droppedDeltaCount)
        val (state, effects) = TokenStreamReducer.reduce(done, delta(text = "late"))
        // Text unchanged.
        assertEquals("final", state.parts["p1"]?.text)
        assertEquals(TokenPartStreamState.DONE, state.parts["p1"]?.state)
        // Dropped + counted.
        assertEquals(1L, state.droppedDeltaCount)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `delta after truncated-cleared part creates new provisional entry`() {
        // §orphan-delta-fix (2026-07-26): after truncation clears the part,
        // a subsequent delta is an orphan — but now it creates a NEW
        // provisional STREAMING entry (same fix as the cold-start orphan
        // case). The old behavior dropped it; now the UI can resume live
        // rendering immediately.
        val streaming = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = "p"),
        ).first
        val truncated = TokenStreamReducer.reduce(streaming, snapshot(truncated = true)).first
        // Part is gone → a subsequent delta creates a fresh provisional entry.
        val (state, _) = TokenStreamReducer.reduce(truncated, delta(text = "z"))
        val acc = state.parts["p1"]
        assertEquals("z", acc?.text)
        assertEquals(TokenPartStreamState.STREAMING, acc?.state)
        // NOT counted as dropped.
        assertEquals(0L, state.droppedDeltaCount)
    }

    // ── resync → clear-all-sid + effects + reconnect flag ────────────────

    @Test
    fun `resync reconnect_no_replay clears all parts for sid and emits full effect set`() {
        // Two parts for s1, one for s2.
        var state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(partId = "p1", sessionId = "s1", text = "a"),
        ).first
        state = TokenStreamReducer.reduce(
            state,
            snapshot(partId = "p2", sessionId = "s1", text = "b"),
        ).first
        state = TokenStreamReducer.reduce(
            state,
            snapshot(partId = "p3", sessionId = "s2", text = "c"),
        ).first

        val (next, effects) = TokenStreamReducer.reduce(
            state,
            TokenStreamFrame.Resync(ResyncReason.RECONNECT_NO_REPLAY, "s1"),
            ownedBySession = mapOf("s1" to setOf("p1", "p2")),
        )

        // All s1 parts cleared from reducer state; s2 part preserved.
        assertNull(next.parts["p1"])
        assertNull(next.parts["p2"])
        assertEquals("c", next.parts["p3"]?.text)

        // Effects: ClearPartState(s1 parts) + TriggerSinceFetch(s1, true) + Reconnect(s1).
        val clear = effects.filterIsInstance<TokenStreamCoordinatorEffect.ClearPartState>().single()
        assertEquals(setOf("p1", "p2"), clear.partIds)
        val trigger = effects.filterIsInstance<TokenStreamCoordinatorEffect.TriggerSinceFetch>().single()
        assertEquals("s1", trigger.sessionId)
        assertTrue(trigger.authoritative)
        val reconnect = effects.filterIsInstance<TokenStreamCoordinatorEffect.Reconnect>().single()
        assertEquals("s1", reconnect.sessionId)
    }

    @Test
    fun `resync token_memory_limit clears sid parts, fetches authoritatively, no reconnect (D-MB-P)`() {
        // D-MB-P: the server LRU-evicts ONE LivePart on token_memory_limit but
        // keeps the stream alive AND (MB-P-S1, slimapi 3e4b3b7) RE-EMITS
        // surviving-part snapshots to EXISTING subscribers, so a same-connection
        // re-anchor (clear + /since + accept re-emitted snapshots) is sufficient.
        // No Reconnect effect — mirrors SESSION_IDLE/SESSION_DELETED/UNKNOWN.
        val state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(partId = "p1", sessionId = "s1", text = "a"),
        ).first

        val (next, effects) = TokenStreamReducer.reduce(
            state,
            TokenStreamFrame.Resync(ResyncReason.TOKEN_MEMORY_LIMIT, "s1"),
        )

        assertNull(next.parts["p1"])
        assertTrue(effects.any { it is TokenStreamCoordinatorEffect.ClearPartState })
        val trigger = effects.filterIsInstance<TokenStreamCoordinatorEffect.TriggerSinceFetch>().single()
        assertTrue(trigger.authoritative)
        // D-MB-P: NO Reconnect — same-connection re-anchor.
        assertFalse(effects.any { it is TokenStreamCoordinatorEffect.Reconnect })
    }

    // ── §5 C-2: session_idle / session_deleted / unknown-reason fallback ───

    @Test
    fun `resync session_idle clears sid parts, fetches authoritatively, no reconnect`() {
        val state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(partId = "p1", sessionId = "s1", text = "a"),
        ).first

        val (next, effects) = TokenStreamReducer.reduce(
            state,
            TokenStreamFrame.Resync(ResyncReason.SESSION_IDLE, "s1"),
        )

        assertNull(next.parts["p1"])
        assertTrue(effects.any { it is TokenStreamCoordinatorEffect.ClearPartState })
        assertTrue(effects.any { it is TokenStreamCoordinatorEffect.TriggerSinceFetch })
        assertFalse(effects.any { it is TokenStreamCoordinatorEffect.Reconnect })
    }

    @Test
    fun `resync session_deleted takes conservative clear_plus_since fallback (no eviction hook in reducer layer)`() {
        // §5 C-2: the reducer is pure data/repository code and CANNOT reach the
        // UI-layer EvictSession hook (AppCore.kt ~:736, emitted by
        // SessionSyncCoordinator). session_deleted therefore takes the same
        // conservative fallback as session_idle HERE: clear + `/since`, no
        // reconnect. The UI-layer digest path drives the actual session
        // eviction independently.
        val state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(partId = "p1", sessionId = "s1", text = "a"),
        ).first

        val (next, effects) = TokenStreamReducer.reduce(
            state,
            TokenStreamFrame.Resync(ResyncReason.SESSION_DELETED, "s1"),
        )

        assertNull(next.parts["p1"])
        assertTrue(effects.any { it is TokenStreamCoordinatorEffect.ClearPartState })
        assertTrue(effects.any { it is TokenStreamCoordinatorEffect.TriggerSinceFetch })
        assertFalse(effects.any { it is TokenStreamCoordinatorEffect.Reconnect })
    }

    @Test
    fun `resync UNKNOWN reason does NOT drop the frame and takes conservative clear_plus_since fallback`() {
        // §5 C-2: an unrecognized reason is mapped to ResyncReason.UNKNOWN by
        // fromWire (frame NOT dropped). The reducer routes it through the same
        // clear + authoritative `/since` path as known non-reconnect reasons,
        // with NO reconnect.
        val state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(partId = "p1", sessionId = "s1", text = "a"),
        ).first

        val (next, effects) = TokenStreamReducer.reduce(
            state,
            TokenStreamFrame.Resync(ResyncReason.UNKNOWN, "s1"),
        )

        assertNull(next.parts["p1"])
        assertTrue(effects.any { it is TokenStreamCoordinatorEffect.ClearPartState })
        val trigger = effects.filterIsInstance<TokenStreamCoordinatorEffect.TriggerSinceFetch>().single()
        assertTrue(trigger.authoritative)
        assertFalse(effects.any { it is TokenStreamCoordinatorEffect.Reconnect })
    }

    @Test
    fun `resync with unrecognized reason wire round-trips end-to-end to clear_plus_since fallback`() {
        // Full parse → reduce round-trip: a server emitting a brand-new reason
        // string must not silently lose the frame (no silent drop, no reconnect).
        val parsed = TokenStreamFrame.parse("resync", """{"reason":"brand_new_future_reason","sessionID":"s1"}""")
            as TokenStreamFrame.Resync
        assertEquals(ResyncReason.UNKNOWN, parsed.reason)

        val state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(partId = "p1", sessionId = "s1", text = "a"),
        ).first
        val (next, effects) = TokenStreamReducer.reduce(state, parsed)

        assertNull(next.parts["p1"])
        assertTrue(effects.any { it is TokenStreamCoordinatorEffect.ClearPartState })
        assertTrue(effects.any { it is TokenStreamCoordinatorEffect.TriggerSinceFetch })
        assertFalse(effects.any { it is TokenStreamCoordinatorEffect.Reconnect })
    }

    @Test
    fun `resync ClearPartState unions reducer-owned and externally-owned parts`() {
        // Reducer knows p1 for s1; external ownership also has p9 (not yet streamed).
        val state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(partId = "p1", sessionId = "s1", text = "a"),
        ).first

        val (_, effects) = TokenStreamReducer.reduce(
            state,
            TokenStreamFrame.Resync(ResyncReason.SUBSCRIBER_BACKPRESSURE, "s1"),
            ownedBySession = mapOf("s1" to setOf("p9")),
        )
        val clear = effects.filterIsInstance<TokenStreamCoordinatorEffect.ClearPartState>().single()
        assertEquals(setOf("p1", "p9"), clear.partIds)
    }

    @Test
    fun `resync with null sessionID is a no-op`() {
        val state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(partId = "p1", sessionId = "s1", text = "a"),
        ).first
        val (next, effects) = TokenStreamReducer.reduce(
            state,
            TokenStreamFrame.Resync(ResyncReason.RECONNECT_NO_REPLAY, null),
        )
        // State unchanged, no effects.
        assertEquals("a", next.parts["p1"]?.text)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `resync reconnect flags`() {
        // Parametrized across the whole enum: the reducer's Reconnect effect
        // must mirror ResyncReason.triggersReconnect exactly. D-MB-P:
        // TOKEN_MEMORY_LIMIT is no longer in the reconnect set (same-connection
        // re-anchor), so only TWO reasons reconnect (reconnect_no_replay /
        // subscriber_backpressure); token_memory_limit / session_idle /
        // session_deleted / UNKNOWN do not.
        ResyncReason.entries.forEach { reason ->
            val state = TokenStreamReducer.reduce(
                TokenStreamReducerState(),
                snapshot(partId = "p1", sessionId = "s1", text = "a"),
            ).first
            val (_, effects) = TokenStreamReducer.reduce(
                state,
                TokenStreamFrame.Resync(reason, "s1"),
            )
            val hasReconnect = effects.any { it is TokenStreamCoordinatorEffect.Reconnect }
            assertEquals(
                "reason=$reason triggersReconnect=${reason.triggersReconnect}",
                reason.triggersReconnect,
                hasReconnect,
            )
        }
    }

    // ── transport-level frames are state-neutral pass-throughs ───────────

    @Test
    fun `server connected and heartbeat do not mutate state or emit effects`() {
        val base = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(text = "a"),
        ).first

        val (s1, e1) = TokenStreamReducer.reduce(
            base,
            TokenStreamFrame.ServerConnected("s1"),
        )
        assertEquals(base, s1)
        assertTrue(e1.isEmpty())

        val (s2, e2) = TokenStreamReducer.reduce(base, TokenStreamFrame.ServerHeartbeat)
        assertEquals(base, s2)
        assertTrue(e2.isEmpty())
    }

    // ── §Stage-B M5: removal frames clear the reducer + emit ClearPartState ──

    @Test
    fun `MessagePartRemoved drops the part and emits ClearPartState (M5 fix)`() {
        // Pre-M5 this branch was a no-op — the reducer kept the part and
        // emitted no effect, leaving the streaming overlay intact so a
        // late straggler frame could resurrect ghost text. After the fix
        // the part is dropped from the reducer map AND a ClearPartState
        // effect is emitted so the coordinator tears down the chat-slice
        // overlay (streamOwned / streamingPartTexts / coalesce buffers).
        val state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(partId = "p1", sessionId = "s1", messageId = "m1", text = "live"),
        ).first
        val (next, effects) = TokenStreamReducer.reduce(
            state,
            TokenStreamFrame.MessagePartRemoved(
                sessionId = "s1",
                messageId = "m1",
                partId = "p1",
                messageEventSeq = 7L,
            ),
        )
        assertNull("reducer part dropped", next.parts["p1"])
        val clear = effects.filterIsInstance<TokenStreamCoordinatorEffect.ClearPartState>().single()
        assertEquals(setOf("p1"), clear.partIds)
    }

    @Test
    fun `MessagePartRemoved for an unknown part emits ClearPartState (severe #3 fix)`() {
        // §rev-ogpt severe #3: a removal for a part the reducer does NOT own
        // (UI-owned overlay — e.g. a reasoning part whose streaming text lives
        // in ChatState.streamingPartTexts without a reducer TokenPartAcc, or a
        // DONE part pruned from the reducer's working map while the chat slice
        // retained it) MUST still emit ClearPartState(frame.partId). Pre-fix
        // this was a no-op — the UI overlay survived the removal event and a
        // subsequent /full(authoritative=false) merge kept the ghost via
        // preservedLocal (the part was still STREAMING-owned in streamOwned).
        val state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(partId = "p1", sessionId = "s1", messageId = "m1", text = "live"),
        ).first
        val (next, effects) = TokenStreamReducer.reduce(
            state,
            TokenStreamFrame.MessagePartRemoved(
                sessionId = "s1",
                messageId = "m1",
                partId = "p-not-owned",
                messageEventSeq = 7L,
            ),
        )
        // Reducer state unchanged (it never owned p-not-owned)...
        assertEquals("state unchanged for unknown part", state, next)
        // ...but a ClearPartState effect IS emitted so the coordinator can
        // tear down the UI-side overlay via ClearTokenStreamState.
        val clear = effects.filterIsInstance<TokenStreamCoordinatorEffect.ClearPartState>().single()
        assertEquals("effect carries the wire frame's partId", setOf("p-not-owned"), clear.partIds)
    }

    @Test
    fun `MessagePartRemoved with mismatched session or message is a no-op`() {
        // Defensive: a partId collision across sessions/messages (theoretically
        // impossible on the wire but cheap to guard) must not let a removal
        // in one context clear another context's overlay.
        val state = TokenStreamReducer.reduce(
            TokenStreamReducerState(),
            snapshot(partId = "p1", sessionId = "s1", messageId = "m1", text = "live"),
        ).first
        val (nextSid, effectsSid) = TokenStreamReducer.reduce(
            state,
            TokenStreamFrame.MessagePartRemoved(
                sessionId = "s-OTHER",
                messageId = "m1",
                partId = "p1",
                messageEventSeq = 7L,
            ),
        )
        assertEquals("session mismatch no-op", state, nextSid)
        assertTrue("session mismatch no effect", effectsSid.isEmpty())

        val (nextMid, effectsMid) = TokenStreamReducer.reduce(
            state,
            TokenStreamFrame.MessagePartRemoved(
                sessionId = "s1",
                messageId = "m-OTHER",
                partId = "p1",
                messageEventSeq = 7L,
            ),
        )
        assertEquals("message mismatch no-op", state, nextMid)
        assertTrue("message mismatch no effect", effectsMid.isEmpty())
    }

    @Test
    fun `MessageRemoved drops ALL reducer-owned parts for the message and emits ClearPartState (M5 fix)`() {
        // Seed TWO parts for m1 (both STREAMING) plus one part for m2 to
        // prove the eviction is scoped by messageId. Pre-M5 this branch
        // was a no-op; after the fix ALL of m1's parts are dropped from
        // the reducer map AND a single ClearPartState effect carries the
        // union of those part IDs.
        val seed = TokenStreamReducerState(
            parts = mapOf(
                "p1" to TokenPartAcc("s1", "m1", "p1", "live-1", TokenPartStreamState.STREAMING),
                "p2" to TokenPartAcc("s1", "m1", "p2", "live-2", TokenPartStreamState.STREAMING),
                "p3" to TokenPartAcc("s1", "m2", "p3", "live-3", TokenPartStreamState.STREAMING),
            ),
        )
        val (next, effects) = TokenStreamReducer.reduce(
            seed,
            TokenStreamFrame.MessageRemoved(sessionId = "s1", messageId = "m1"),
        )
        assertNull("p1 dropped", next.parts["p1"])
        assertNull("p2 dropped", next.parts["p2"])
        assertNotNull("p3 (different message) preserved", next.parts["p3"])
        val clear = effects.filterIsInstance<TokenStreamCoordinatorEffect.ClearPartState>().single()
        assertEquals(setOf("p1", "p2"), clear.partIds)
    }

    @Test
    fun `MessageRemoved for a message the reducer has no parts for is a no-op`() {
        val seed = TokenStreamReducerState(
            parts = mapOf(
                "p1" to TokenPartAcc("s1", "m1", "p1", "live", TokenPartStreamState.STREAMING),
            ),
        )
        val (next, effects) = TokenStreamReducer.reduce(
            seed,
            TokenStreamFrame.MessageRemoved(sessionId = "s1", messageId = "m-EMPTY"),
        )
        assertEquals("state unchanged", seed, next)
        assertTrue("no effect", effects.isEmpty())
    }
}
