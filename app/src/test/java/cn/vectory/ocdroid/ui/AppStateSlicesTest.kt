package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5++ coverage: data-class state slices + small sealed hierarchies
 * on [AppStateSlices.kt]. These are pure value types whose synthetic
 * accessors (equals / hashCode / copy / componentN / defaults) only land in
 * coverage when a test constructs them. Coverage gap before this file:
 *  - ContextUsage: 0/1 class, 0/11 lines, 0/71 instructions
 *  - TrafficState: 1/2 branches, 14/19 lines (totalTrafficBytes getter)
 *  - CachedSessionWindow: 0% (never constructed directly)
 *
 * Construction-only tests are sufficient for kover line coverage of synthetic
 * accessors + the explicit derived getters (totalTrafficBytes, etc.).
 */
class AppStateSlicesTest {

    @Test
    fun `ContextUsage default constructor fills every field`() {
        val c = ContextUsage(
            percentage = 0f,
            totalTokens = 0,
            contextLimit = 0,
        )
        assertEquals(0f, c.percentage, 0.0001f)
        assertEquals(0, c.totalTokens)
        assertEquals(0, c.contextLimit)
        assertNull(c.providerId)
        assertNull(c.modelId)
        assertNull(c.inputTokens)
        assertNull(c.outputTokens)
        assertNull(c.reasoningTokens)
        assertNull(c.cachedReadTokens)
        assertNull(c.cachedWriteTokens)
        assertNull(c.cost)
    }

    @Test
    fun `ContextUsage full constructor round-trips`() {
        val c = ContextUsage(
            percentage = 0.5f,
            totalTokens = 100,
            contextLimit = 200,
            providerId = "p",
            modelId = "m",
            inputTokens = 70,
            outputTokens = 20,
            reasoningTokens = 10,
            cachedReadTokens = 5,
            cachedWriteTokens = 2,
            cost = 0.001,
        )
        assertEquals(0.5f, c.percentage, 0.0001f)
        assertEquals("p", c.providerId)
        assertEquals("m", c.modelId)
        assertEquals(70, c.inputTokens)
        assertEquals(20, c.outputTokens)
        assertEquals(10, c.reasoningTokens)
        assertEquals(5, c.cachedReadTokens)
        assertEquals(2, c.cachedWriteTokens)
        assertEquals(0.001, c.cost!!, 0.0000001)
    }

    @Test
    fun `ContextUsage equals hashCode copy componentN are synthetic-covered`() {
        val c1 = ContextUsage(percentage = 0.1f, totalTokens = 1, contextLimit = 10)
        val c2 = c1.copy()
        assertEquals(c1, c2)
        assertEquals(c1.hashCode(), c2.hashCode())
        assertEquals(0.1f, c1.component1(), 0.0001f)
        assertEquals(1, c1.component2())
        assertEquals(10, c1.component3())
        assertTrue(c1.toString().contains("ContextUsage"))
    }

    @Test
    fun `TrafficState defaults are zero`() {
        val t = TrafficState()
        assertEquals(0L, t.trafficSent)
        assertEquals(0L, t.trafficReceived)
        assertEquals(0L, t.totalTrafficBytes)
    }

    @Test
    fun `TrafficState totalTrafficBytes sums sent and received`() {
        val t = TrafficState(trafficSent = 100L, trafficReceived = 250L)
        assertEquals(350L, t.totalTrafficBytes)
    }

    @Test
    fun `TrafficState equals and copy`() {
        val t1 = TrafficState(trafficSent = 1L, trafficReceived = 2L)
        val t2 = t1.copy()
        assertEquals(t1, t2)
        val t3 = t1.copy(trafficSent = 99L)
        assertEquals(99L, t3.trafficSent)
        assertEquals(2L, t3.trafficReceived)
    }

    @Test
    fun `CachedSessionWindow default constructor round-trips`() {
        val w = cn.vectory.ocdroid.ui.controller.CachedSessionWindow(
            messages = emptyList(),
            partsByMessage = emptyMap(),
            olderMessagesCursor = null,
            hasMoreMessages = true,
        )
        assertTrue(w.messages.isEmpty())
        assertTrue(w.partsByMessage.isEmpty())
        assertNull(w.olderMessagesCursor)
        assertTrue(w.hasMoreMessages)
    }

    @Test
    fun `CachedSessionWindow full constructor round-trips`() {
        val msg = Message(id = "m1", role = "user")
        val part = Part(id = "p1", type = "text")
        val w = cn.vectory.ocdroid.ui.controller.CachedSessionWindow(
            messages = listOf(msg),
            partsByMessage = mapOf("m1" to listOf(part)),
            olderMessagesCursor = "cursor-1",
            hasMoreMessages = false,
        )
        assertEquals(listOf(msg), w.messages)
        assertEquals(mapOf("m1" to listOf(part)), w.partsByMessage)
        assertEquals("cursor-1", w.olderMessagesCursor)
        assertFalse(w.hasMoreMessages)
    }

    @Test
    fun `CachedSessionWindow equals hashCode copy`() {
        val w1 = cn.vectory.ocdroid.ui.controller.CachedSessionWindow(
            messages = emptyList(),
            partsByMessage = emptyMap(),
            olderMessagesCursor = null,
            hasMoreMessages = true,
        )
        val w2 = w1.copy()
        assertEquals(w1, w2)
        assertEquals(w1.hashCode(), w2.hashCode())
    }

    @Test
    fun `ConnectionPhase has the expected variants`() {
        // Seal completeness: every variant is a distinct subtype.
        val idle: ConnectionPhase = ConnectionPhase.Idle
        val connecting: ConnectionPhase = ConnectionPhase.Connecting
        val connected: ConnectionPhase = ConnectionPhase.Connected
        val disconnected: ConnectionPhase = ConnectionPhase.Disconnected
        val retrying: ConnectionPhase = ConnectionPhase.ReconnectingAttempt(1, 3)

        assertTrue(idle is ConnectionPhase.Idle)
        assertTrue(connecting is ConnectionPhase.Connecting)
        assertTrue(connected is ConnectionPhase.Connected)
        assertTrue(disconnected is ConnectionPhase.Disconnected)
        assertTrue(retrying is ConnectionPhase.ReconnectingAttempt)
        assertEquals(1, (retrying as ConnectionPhase.ReconnectingAttempt).attempt)
        assertEquals(3, retrying.maxAttempts)
    }

    @Test
    fun `TunnelActivationState has the expected variants`() {
        val idle: TunnelActivationState = TunnelActivationState.Idle
        val loading: TunnelActivationState = TunnelActivationState.Loading
        val success: TunnelActivationState = TunnelActivationState.Success
        val error: TunnelActivationState = TunnelActivationState.Error("boom")

        assertTrue(idle is TunnelActivationState.Idle)
        assertTrue(loading is TunnelActivationState.Loading)
        assertTrue(success is TunnelActivationState.Success)
        assertTrue(error is TunnelActivationState.Error)
        assertEquals("boom", (error as TunnelActivationState.Error).message)
    }

    // R-20 Phase 2 / remove-message-persistence Task 4: the legacy single-gap
    // `GapInfo` class + its constructor round-trip test were removed, then the
    // entire multi-gap replacement (the contract GapMarker + the GapFill
    // coordinator + the gap-aware render pipeline) was deleted in Task 4 —
    // catch-up now always merges the fetched window.

    @Test
    fun `NavState default lastNavPage is zero`() {
        val n = cn.vectory.ocdroid.ui.NavState()
        assertEquals(0, n.lastNavPage)
    }

    @Test
    fun `NavState copy`() {
        val n1 = cn.vectory.ocdroid.ui.NavState(lastNavPage = 2)
        val n2 = n1.copy(lastNavPage = 0)
        assertEquals(2, n1.lastNavPage)
        assertEquals(0, n2.lastNavPage)
    }

    // §1B (F.4): the additive ComposerState.fileReferences field + the
    // ComposerFileReference value type. Round-tripping the data class is
    // essential to flag accidental data-class changes (kover counts the
    // synthetic equals/hashCode/copy/componentN/toString accessors).

    @Test
    fun `ComposerState default fileReferences is empty`() {
        val s = cn.vectory.ocdroid.ui.ComposerState()
        assertEquals(emptyList<cn.vectory.ocdroid.ui.ComposerFileReference>(), s.fileReferences)
    }

    @Test
    fun `ComposerState full constructor round-trips fileReferences`() {
        val ref = cn.vectory.ocdroid.ui.ComposerFileReference(path = "/a/b.kt", id = "id1")
        val s = cn.vectory.ocdroid.ui.ComposerState(fileReferences = listOf(ref))
        assertEquals(listOf(ref), s.fileReferences)
    }

    @Test
    fun `ComposerFileReference default id is a non-empty UUID`() {
        val ref = cn.vectory.ocdroid.ui.ComposerFileReference(path = "/a/b.kt")
        // Default id is a random UUID — non-empty and non-blank.
        assertTrue(ref.id.isNotBlank())
        assertEquals("/a/b.kt", ref.path)
    }

    @Test
    fun `ComposerFileReference equals hashCode copy`() {
        val r1 = cn.vectory.ocdroid.ui.ComposerFileReference(path = "/a", id = "x")
        val r2 = r1.copy()
        assertEquals(r1, r2)
        assertEquals(r1.hashCode(), r2.hashCode())
        val r3 = r1.copy(path = "/b")
        assertEquals("/b", r3.path)
        assertEquals("x", r3.id)
    }
}
