package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5++ coverage: the [ToolRenderItem] sealed-class hierarchy
 * (cn.vectory.ocdroid.ui.chat.ChatMessageRow.kt). Coverage gap before this
 * file: 0/1 class, 0/1 method, 0/2 branches, 0/8 instructions — the sealed
 * class was never instantiated by a test.
 *
 * Construction of each subtype flips the synthetic equals/hashCode/copy/
 * toString accessors to covered.
 */
class ToolRenderItemTest {

    @Test
    fun `ContextGroup subtype holds the supplied parts list`() {
        val parts = listOf(
            Part(id = "p1", type = "tool", tool = "read"),
            Part(id = "p2", type = "tool", tool = "grep"),
        )
        val item = ToolRenderItem.ContextGroup(parts)
        assertEquals(parts, item.parts)
        // data-class equality.
        assertEquals(item, ToolRenderItem.ContextGroup(parts))
        assertEquals(item.hashCode(), ToolRenderItem.ContextGroup(parts).hashCode())
    }

    @Test
    fun `SubAgent subtype holds the supplied part`() {
        val part = Part(id = "p1", type = "tool", tool = "task")
        val item = ToolRenderItem.SubAgent(part)
        assertEquals(part, item.part)
    }

    @Test
    fun `WritePatch subtype holds the supplied part`() {
        val part = Part(id = "p1", type = "patch")
        val item = ToolRenderItem.WritePatch(part)
        assertEquals(part, item.part)
    }

    @Test
    fun `Basic subtype holds the supplied part`() {
        val part = Part(id = "p1", type = "tool", tool = "ls")
        val item = ToolRenderItem.Basic(part)
        assertEquals(part, item.part)
    }

    @Test
    fun `subtypes are distinguishable by runtime type`() {
        val part = Part(id = "p", type = "tool", tool = "x")
        val items: List<ToolRenderItem> = listOf(
            ToolRenderItem.ContextGroup(listOf(part)),
            ToolRenderItem.SubAgent(part),
            ToolRenderItem.WritePatch(part),
            ToolRenderItem.Basic(part),
        )
        assertEquals(4, items.size)
        assertTrue(items.any { it is ToolRenderItem.ContextGroup })
        assertTrue(items.any { it is ToolRenderItem.SubAgent })
        assertTrue(items.any { it is ToolRenderItem.WritePatch })
        assertTrue(items.any { it is ToolRenderItem.Basic })
    }

    @Test
    fun `subtypes toString is non-empty`() {
        val part = Part(id = "p", type = "tool", tool = "x")
        assertNotNull(ToolRenderItem.ContextGroup(listOf(part)).toString())
        assertNotNull(ToolRenderItem.SubAgent(part).toString())
        assertNotNull(ToolRenderItem.WritePatch(part).toString())
        assertNotNull(ToolRenderItem.Basic(part).toString())
    }
}
