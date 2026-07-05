package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.ui.filterBeforeRevert
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §revert-fix: covers [filterBeforeRevert] in isolation. The helper must
 * EXCLUDE the revert message itself (mirrors the legacy `id < revertMessageId`
 * semantics) on BOTH the time-based and index-fallback paths, while keeping
 * orphan messages (no `time.created`) and tie-breaking equal timestamps by
 * list position.
 */
class FilterBeforeRevertTest {

    private fun msg(id: String, created: Long? = null): Message = Message(
        id = id,
        role = "user",
        time = created?.let { Message.TimeInfo(created = it) }
    )

    @Test
    fun `null revert id returns all messages unchanged`() {
        val list = listOf(msg("a", 1L), msg("b", 2L), msg("c", 3L))
        assertEquals(list, list.filterBeforeRevert(null))
    }

    @Test
    fun `revert message in middle with time - excluded itself, keeps strictly earlier`() {
        val list = listOf(
            msg("a", created = 100L),
            msg("revert", created = 200L),
            msg("c", created = 300L)
        )
        val result = list.filterBeforeRevert("revert")
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun `time-based - equal timestamp BEFORE revert position is kept`() {
        // m-eq 与 revert 时间相同，但列表中位于 revert 之前 → 保留。
        val list = listOf(
            msg("m-eq", created = 200L),
            msg("revert", created = 200L)
        )
        val result = list.filterBeforeRevert("revert")
        assertEquals(listOf("m-eq"), result.map { it.id })
    }

    @Test
    fun `time-based - equal timestamp AFTER revert position is excluded`() {
        // m-eq 与 revert 时间相同，但列表中位于 revert 之后 → 排除。
        val list = listOf(
            msg("revert", created = 200L),
            msg("m-eq", created = 200L)
        )
        val result = list.filterBeforeRevert("revert")
        assertEquals(emptyList<String>(), result.map { it.id })
    }

    @Test
    fun `time-based - orphan message after revert with null time is kept`() {
        // 无时间信息的孤儿消息（time==null）始终保留，即使在 revert 之后。
        val list = listOf(
            msg("revert", created = 200L),
            msg("orphan", created = null)
        )
        val result = list.filterBeforeRevert("revert")
        assertEquals(listOf("orphan"), result.map { it.id })
    }

    @Test
    fun `revert id absent from list returns all unchanged`() {
        val list = listOf(msg("a", 1L), msg("b", 2L))
        assertEquals(list, list.filterBeforeRevert("nonexistent"))
    }

    @Test
    fun `empty list returns empty`() {
        val empty = emptyList<Message>()
        assertEquals(empty, empty.filterBeforeRevert("revert"))
    }

    @Test
    fun `index fallback - revert message with null time excludes itself and later`() {
        // revert 消息自身 time.created==null → 走 index fallback。
        // subList(0, revertIndex) 排除 revert 自身及之后所有消息。
        val list = listOf(
            msg("a", created = null),
            msg("revert", created = null),
            msg("c", created = null)
        )
        val result = list.filterBeforeRevert("revert")
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun `revert at head with time - only earlier and orphans survive`() {
        // revert 是第一条（created=100）；它自身被排除。orphan（time==null）保留；
        // c（created=50 < 100）保留。
        val list = listOf(
            msg("revert", created = 100L),
            msg("orphan", created = null),
            msg("c", created = 50L)
        )
        val result = list.filterBeforeRevert("revert")
        assertEquals(listOf("orphan", "c"), result.map { it.id })
    }
}
