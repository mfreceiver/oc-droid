package cn.vectory.ocdroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Contract for [workdirBasename] — documents the trailing-slash choice (batch-1 gate adoptable fix). */
class WorkdirPathsBasenameTest {
    @Test
    fun workdirBasename_segments() {
        assertEquals("proj", "/home/u/proj".workdirBasename())
        // trailing slash: filter-then-last yields last non-empty segment (differs from old substringAfterLast('/').ifBlank{dir})
        assertEquals("proj", "/home/u/proj/".workdirBasename())
        assertNull("/".workdirBasename())
        assertNull("".workdirBasename())
    }
}
