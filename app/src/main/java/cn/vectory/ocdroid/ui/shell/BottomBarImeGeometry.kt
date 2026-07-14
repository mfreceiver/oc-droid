package cn.vectory.ocdroid.ui.shell

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

internal data class BottomBarImeGeometry(
    val visibleHeightPx: Int,
    val translationYPx: Float,
    val alpha: Float,
)

internal fun bottomBarImeGeometry(
    imeBottomPx: Float,
    barHeightPx: Int,
): BottomBarImeGeometry {
    if (barHeightPx <= 0) return BottomBarImeGeometry(0, 0f, 0f)
    val hiddenPx = imeBottomPx.coerceIn(0f, barHeightPx.toFloat())
    return BottomBarImeGeometry(
        visibleHeightPx = (barHeightPx - hiddenPx).toInt(),
        translationYPx = hiddenPx,
        alpha = 1f - hiddenPx / barHeightPx,
    )
}

/** Shrinks Scaffold's measured bottom-bar height while sliding its paint down. */
internal fun Modifier.collapseForIme(imeBottomPx: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val geometry = bottomBarImeGeometry(imeBottomPx, placeable.height)
    layout(placeable.width, geometry.visibleHeightPx) {
        placeable.placeRelativeWithLayer(
            x = 0,
            y = -geometry.translationYPx.toInt(),
        ) {
            translationY = geometry.translationYPx
            alpha = geometry.alpha
        }
    }
}
