package com.yage.opencode_client.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.ui.theme.opencode
import kotlinx.coroutines.delay

enum class ToastSeverity { Success, Error, Info }

@Composable
fun AppToast(
    message: String,
    severity: ToastSeverity,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val oc = MaterialTheme.opencode

    // 3s auto-dismiss, keyed on `message`: the timer restarts whenever the
    // message VALUE changes (incl. null->msg after a clear). Note: if the exact
    // same string re-fires while already visible (no clear between), StateFlow
    // suppresses the equal update so the timer is NOT reset — acceptable, since
    // the toast already shows the correct text (standard toast semantics).
    // Tap-to-dismiss also routes through onDismiss; the callback is expected to
    // be idempotent (typically a clear), so the two paths do not conflict.
    if (onDismiss != null) {
        LaunchedEffect(message) {
            delay(3000)
            onDismiss()
        }
    }

    val borderColor: Color
    val icon: ImageVector
    val iconTint: Color

    when (severity) {
        ToastSeverity.Success -> {
            borderColor = oc.stateSuccessFg
            icon = Icons.Default.CheckCircle
            iconTint = oc.stateSuccessFg
        }
        ToastSeverity.Error -> {
            borderColor = oc.stateDangerFg
            icon = Icons.Default.ErrorOutline
            iconTint = oc.stateDangerFg
        }
        ToastSeverity.Info -> {
            borderColor = oc.borderBase
            icon = Icons.Default.Info
            iconTint = oc.stateInfoFg
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = oc.layer02,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (onDismiss != null) Modifier.clickable { onDismiss() }
                    else Modifier
                )
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = iconTint
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}
