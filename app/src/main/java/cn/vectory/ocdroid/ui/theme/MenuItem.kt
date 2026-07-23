package cn.vectory.ocdroid.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Thin wrapper around Material3 [DropdownMenuItem] that applies a consistent
 * leading-icon sizing. Icon size defaults to [Dimens.iconSm] (18dp) matching
 * M3 default; pass a custom [iconModifier] to override.
 */
@Composable
fun MenuItem(
    text: @Composable () -> Unit,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    iconModifier: Modifier = Modifier.size(Dimens.iconSm),
) {
    DropdownMenuItem(
        text = text,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = iconModifier,
            )
        },
        onClick = onClick,
        enabled = enabled,
    )
}
