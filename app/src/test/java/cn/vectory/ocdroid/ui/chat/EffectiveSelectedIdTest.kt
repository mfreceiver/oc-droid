package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.controller.rootIdOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── truncateTitle §task7-coverage ──────────────────────────────────────

    @Test
    fun `truncateTitle returns the original when at or under the char cap`() {
        assertEquals("short", truncateTitle("short"))
        assertEquals("123456789012345", truncateTitle("123456789012345"))
    }

    @Test
    fun `truncateTitle truncates with ellipsis when over the char cap`() {
        val result = truncateTitle("1234567890123456")
        assertEquals(15, result.length)
        assertEquals("12345678901234…", result)
    }

    @Test
    fun `truncateTitle handles empty string`() {
        assertEquals("", truncateTitle(""))
    }

    // ── §task6-grandchild (final-review fix 4): full-root tab selection ────

    @Test
    fun `grandchild current session selects the root tab when caller resolves root via rootIdOf`() {
        // §task6-grandchild (final-review fix 4): when currentSessionId is a
        // multi-level descendant (grandchild), resolveEffectiveSelectedId
        // alone walks only ONE parent level — it returns null because neither
        // the grandchild nor its direct parent is in the root-only
        // openSessions list. With null, no tab reads as selected → the root
        // tab's "?" marker is NOT suppressed (its question is already
        // surfaced in the chat's QuestionCard for the current tree, so the
        // tab marker is a redundant duplicate the spec forbids).
        //
        // Plan B (chosen): the ChatScaffold caller resolves the FULL root via
        // rootIdOf(currentSessionId, sessionsById) and feeds that root id
        // into the parentSessionId slot of resolveEffectiveSelectedId. The
        // root tab then reads as selected for any descendant depth, and
        // shouldShowQuestionMarker suppresses its "?" marker.
        val grandchild = Session(id = "grandchild", directory = "/p", parentId = "child")
        val child = Session(id = "child", directory = "/p", parentId = "root")
        val root = Session(id = "root", directory = "/p")
        val sessionsById = mapOf(
            "grandchild" to grandchild,
            "child" to child,
            "root" to root,
        )
        val openSessions = listOf(root)
        val currentSessionId = "grandchild"

        // Pre-fix premise (still pinning the helper's unchanged contract):
        // direct-parent-only resolution yields null for a grandchild.
        assertNull(
            "direct-parent-only resolution still yields null for grandchild (helper unchanged)",
            resolveEffectiveSelectedId(
                openSessions = openSessions,
                currentSessionId = currentSessionId,
                parentSessionId = child.id,
            ),
        )

        // Plan B caller-side contract: resolve full root, then feed it in.
        val resolvedRoot = rootIdOf(currentSessionId, sessionsById)
        assertEquals("root", resolvedRoot)
        val effective = resolveEffectiveSelectedId(
            openSessions = openSessions,
            currentSessionId = currentSessionId,
            parentSessionId = resolvedRoot,
        )
        assertEquals("root", effective)

        // Contract: with the root tab selected, its "?" marker is suppressed.
        assertFalse(
            "root tab '?' marker suppressed when grandchild is current",
            shouldShowQuestionMarker(
                isSelected = root.id == effective,
                questionSessionIds = setOf("root"),
                sessionId = root.id,
            ),
        )
    }
}
