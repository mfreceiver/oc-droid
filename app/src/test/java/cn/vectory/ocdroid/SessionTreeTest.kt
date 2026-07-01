package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.session.buildSessionTree
import cn.vectory.ocdroid.ui.session.flattenVisibleTree
import org.junit.Assert.*
import org.junit.Test

class SessionTreeTest {

    private fun session(id: String, parentId: String? = null, updated: Long = 0) =
        Session(id = id, directory = "/tmp", parentId = parentId, time = Session.TimeInfo(updated = updated))

    @Test
    fun `buildSessionTree builds hierarchy`() {
        val sessions = listOf(
            session("parent", updated = 100),
            session("child1", parentId = "parent", updated = 90),
            session("child2", parentId = "parent", updated = 80)
        )
        val tree = buildSessionTree(sessions)
        assertEquals(1, tree.size)
        assertEquals("parent", tree[0].session.id)
        assertEquals(2, tree[0].children.size)
        val childIds = tree[0].children.map { it.session.id }.sorted()
        assertEquals(listOf("child1", "child2"), childIds)
    }

    @Test
    fun `buildSessionTree orphaned children become roots`() {
        val sessions = listOf(
            session("orphan", parentId = "missing-parent", updated = 90)
        )
        val tree = buildSessionTree(sessions)
        assertEquals(1, tree.size)
        assertEquals("orphan", tree[0].session.id)
    }

    @Test
    fun `flattenVisibleTree when collapsed shows only roots`() {
        val sessions = listOf(
            session("root", updated = 100),
            session("child", parentId = "root", updated = 90)
        )
        val tree = buildSessionTree(sessions)
        val flat = flattenVisibleTree(tree, expandedIds = emptySet())
        assertEquals(1, flat.size)
        assertEquals("root", flat[0].first.session.id)
        assertEquals(0, flat[0].second)
    }

    @Test
    fun `flattenVisibleTree when expanded shows children`() {
        val sessions = listOf(
            session("root", updated = 100),
            session("child", parentId = "root", updated = 90)
        )
        val tree = buildSessionTree(sessions)
        val flat = flattenVisibleTree(tree, expandedIds = setOf("root"))
        assertEquals(2, flat.size)
        assertEquals("root", flat[0].first.session.id)
        assertEquals(0, flat[0].second)
        assertEquals("child", flat[1].first.session.id)
        assertEquals(1, flat[1].second)
    }
}
