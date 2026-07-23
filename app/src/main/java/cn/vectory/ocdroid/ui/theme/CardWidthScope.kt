package cn.vectory.ocdroid.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Responsive card width constraint: 2/3 of available width, capped at 480dp.
 * Wraps [BoxWithConstraints] and provides [cardMax] to [content].
 */
private val MAX_CARD_WIDTH: Dp = 480.dp

@Composable
fun CardWidthScope(
    modifier: Modifier = Modifier,
    content: @Composable (cardMax: Dp) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val cardMax = minOf(maxWidth * 2f / 3f, MAX_CARD_WIDTH)
        content(cardMax)
    }
}
