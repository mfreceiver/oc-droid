package cn.vectory.ocdroid.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape

/**
 * Skeleton for status/info banners that share a common Surface + Row chrome.
 *
 * Provides the outer [Surface] with [RectangleShape], configurable [color] and
 * [border], and an inner [Row] with standard [Dimens] padding. The [content]
 * lambda supplies the row's body so each call site controls its own arrangement,
 * spacing, and composable children (spinners, icons, text, buttons, etc.).
 *
 * When [onClick] is non-null, the surface becomes clickable.
 */
@Composable
fun StatusBanner(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    border: BorderStroke? = BorderStroke(Dimens.hairline, MaterialTheme.colorScheme.outlineVariant),
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val baseModifier = modifier
        .padding(vertical = Dimens.spacing1)
    val surfaceModifier = if (onClick != null) {
        baseModifier.clickable(onClick = onClick)
    } else {
        baseModifier
    }
    Surface(
        modifier = surfaceModifier,
        shape = RectangleShape,
        color = color,
        border = border,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .padding(horizontal = Dimens.spacing2, vertical = Dimens.spacing2),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            content()
        }
    }
}
