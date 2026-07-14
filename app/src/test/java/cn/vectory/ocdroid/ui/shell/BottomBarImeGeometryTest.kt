package cn.vectory.ocdroid.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomBarImeGeometryTest {
    @Test
    fun `closed ime preserves full measured height`() {
        assertEquals(
            BottomBarImeGeometry(visibleHeightPx = 80, translationYPx = 0f, alpha = 1f),
            bottomBarImeGeometry(imeBottomPx = 0f, barHeightPx = 80),
        )
    }

    @Test
    fun `partial ime shrinks scaffold inset and translates bar downward`() {
        assertEquals(
            BottomBarImeGeometry(visibleHeightPx = 60, translationYPx = 20f, alpha = 0.75f),
            bottomBarImeGeometry(imeBottomPx = 20f, barHeightPx = 80),
        )
    }

    @Test
    fun `open ime supplies zero scaffold height and no touch surface`() {
        assertEquals(
            BottomBarImeGeometry(visibleHeightPx = 0, translationYPx = 80f, alpha = 0f),
            bottomBarImeGeometry(imeBottomPx = 400f, barHeightPx = 80),
        )
    }
}
