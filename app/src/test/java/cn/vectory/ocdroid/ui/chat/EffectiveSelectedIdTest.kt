package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [resolveEffectiveSelectedId] — the pure selection resolver
 * backing the session tab strip's highlight + scroll-centre (§problem-1 /
 * 0.2.0). Extracted from the [SessionTabStrip] composable so the precedence and
 * null semantics are unit-testable instead of only verifiable on-device.
 *
 * Contract: currentSessionId wins when it is itself a root session in the list;
 * otherwise parentSessionId wins when it is in the list (opening a sub-agent
 * highlights its parent tab); otherwise null (draft, or a multi-level orphan).
 */
class EffectiveSelectedIdTest {

    private fun session(id: String): Session = Session(id = id, directory = "/proj/$id")

    private val roots = listOf(session("root-1"), session("root-2"), session("root-3"))

    // ── precedence ─────────────────────────────────────────────────────────

    @Test
    fun `currentSessionId wins when it is a root session in the list`() {
        assertEquals(
            "root-2",
            resolveEffectiveSelectedId(roots, currentSessionId = "root-2", parentSessionId = "root-1")
        )
    }

    @Test
    fun `falls back to parentSessionId when currentSessionId is not in the list`() {
        // currentSessionId is a sub-agent id absent from openSessions → parent
        // tab is highlighted.
        assertEquals(
            "root-1",
            resolveEffectiveSelectedId(roots, currentSessionId = "sub-agent-9", parentSessionId = "root-1")
        )
    }

    @Test
    fun `currentSessionId takes precedence over parentSessionId when both are in the list`() {
        assertEquals(
            "root-2",
            resolveEffectiveSelectedId(roots, currentSessionId = "root-2", parentSessionId = "root-3")
        )
    }

    // ── null / orphan cases ────────────────────────────────────────────────

    @Test
    fun `draft session with null currentSessionId and null parent highlights nothing`() {
        assertNull(resolveEffectiveSelectedId(roots, currentSessionId = null, parentSessionId = null))
    }

    @Test
    fun `multi-level orphan whose parent is also a sub-agent highlights nothing`() {
        // Neither currentSessionId nor parentSessionId is a root session → null.
        assertNull(
            resolveEffectiveSelectedId(roots, currentSessionId = "sub-2", parentSessionId = "sub-1")
        )
    }

    @Test
    fun `parentSessionId not in list yields null even when currentSessionId is also absent`() {
        assertNull(
            resolveEffectiveSelectedId(roots, currentSessionId = null, parentSessionId = "not-a-root")
        )
    }

    // ── edge: empty list ───────────────────────────────────────────────────

    @Test
    fun `empty openSessions yields null regardless of ids`() {
        assertNull(
            resolveEffectiveSelectedId(emptyList(), currentSessionId = "root-1", parentSessionId = "root-1")
        )
    }
}
