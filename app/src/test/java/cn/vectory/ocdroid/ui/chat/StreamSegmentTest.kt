package cn.vectory.ocdroid.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * R18 Phase 5++ coverage: the [StreamSegment] sealed-class hierarchy
 * (cn.vectory.ocdroid.ui.chat.ChatTextParts.kt). Coverage gap before this
 * file: 0/1 class, 0/2 methods, 0/13 instructions — the sealed class was
 * never instantiated by a test.
 *
 * Construction of each subtype flips the synthetic equals/hashCode/copy/
 * toString accessors to covered.
 */
class StreamSegmentTest {

    @Test
    fun `Prose subtype holds the raw text`() {
        val s = StreamSegment.Prose(raw = "hello world")
        assertEquals("hello world", s.raw)
    }

    @Test
    fun `Code subtype holds code and language`() {
        val s = StreamSegment.Code(code = "val x = 1", language = "kotlin")
        assertEquals("val x = 1", s.code)
        assertEquals("kotlin", s.language)
    }

    @Test
    fun `Prose equals hashCode copy`() {
        val s1 = StreamSegment.Prose("x")
        val s2 = s1.copy()
        assertEquals(s1, s2)
        assertEquals(s1.hashCode(), s2.hashCode())
        val s3 = s1.copy(raw = "y")
        assertEquals("y", s3.raw)
    }

    @Test
    fun `Code equals hashCode copy`() {
        val s1 = StreamSegment.Code("c", "kotlin")
        val s2 = s1.copy()
        assertEquals(s1, s2)
        assertEquals(s1.hashCode(), s2.hashCode())
        val s3 = s1.copy(language = "java")
        assertEquals("java", s3.language)
    }

    @Test
    fun `subtypes are distinguishable by runtime type`() {
        val segments: List<StreamSegment> = listOf(
            StreamSegment.Prose("p"),
            StreamSegment.Code("c", "kotlin"),
        )
        assertEquals(2, segments.size)
        assertEquals(true, segments.any { it is StreamSegment.Prose })
        assertEquals(true, segments.any { it is StreamSegment.Code })
    }

    @Test
    fun `subtypes toString is non-empty`() {
        assertNotNull(StreamSegment.Prose("p").toString())
        assertNotNull(StreamSegment.Code("c", "kotlin").toString())
    }
}
