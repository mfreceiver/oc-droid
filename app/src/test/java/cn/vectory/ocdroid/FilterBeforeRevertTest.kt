package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.RevertCutoff
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.ui.filterBeforeRevert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FilterBeforeRevertTest {
    private fun msg(id: String, created: Long? = null) = Message(id = id, role = "user", time = created?.let { Message.TimeInfo(created = it) })
    private fun cutoff(messageId: String, state: RevertCutoffState) = RevertCutoff("s1", messageId, state)

    @Test fun `no active revert returns complete window`() {
        val all = listOf(msg("a", 1), msg("b", 2)); assertEquals(all, all.filterBeforeRevert(null, null))
    }
    @Test fun `pending in-window uses safe index prefix`() {
        val all = listOf(msg("a", 1), msg("r", 2), msg("after", 3))
        assertEquals(listOf("a"), all.filterBeforeRevert("r", cutoff("r", RevertCutoffState.PendingFetch)).map { it.id })
    }
    @Test fun `pending absent is empty and never releases window`() {
        val all = listOf(msg("after1", 3), msg("after2", 4)); val result = all.filterBeforeRevert("r", null)
        assertEquals(emptyList<Message>(), result); assertNotEquals(all, result)
    }
    @Test fun `failed absent remains fail closed`() {
        assertEquals(emptyList<Message>(), listOf(msg("x", 3)).filterBeforeRevert("r", cutoff("r", RevertCutoffState.Failed)))
    }
    @Test fun `stale cutoff message id is atomically invalidated`() {
        val stale = cutoff("old-revert", RevertCutoffState.Resolved(10))
        assertEquals(emptyList<Message>(), listOf(msg("old", 1), msg("new", 9)).filterBeforeRevert("new-revert", stale))
    }
    @Test fun `resolved out-of-window filters by persisted timestamp`() {
        val all = listOf(msg("before", 1), msg("after", 3), msg("unknown"))
        assertEquals(listOf("before"), all.filterBeforeRevert("r", cutoff("r", RevertCutoffState.Resolved(2))).map { it.id })
    }
    @Test fun `resolved equal timestamp excludes every equal row and unknown times`() {
        val all = listOf(msg("equal-before", 2), msg("r", 2), msg("equal-after", 2), msg("unknown"))
        assertEquals(emptyList<String>(), all.filterBeforeRevert("r", cutoff("r", RevertCutoffState.Resolved(2))).map { it.id })
    }
    @Test fun `no timestamp uses index prefix only when target present`() {
        val all = listOf(msg("before"), msg("r"), msg("after"))
        assertEquals(listOf("before"), all.filterBeforeRevert("r", cutoff("r", RevertCutoffState.NoTimestamp)).map { it.id })
        assertEquals(emptyList<Message>(), all.filterBeforeRevert("missing", cutoff("missing", RevertCutoffState.NoTimestamp)))
    }
    @Test fun `paging target into window changes pending empty to safe prefix`() {
        val pending = cutoff("r", RevertCutoffState.PendingFetch)
        assertEquals(emptyList<Message>(), listOf(msg("after", 3)).filterBeforeRevert("r", pending))
        assertEquals(listOf("before"), listOf(msg("before", 1), msg("r", 2), msg("after", 3)).filterBeforeRevert("r", pending).map { it.id })
    }
}
