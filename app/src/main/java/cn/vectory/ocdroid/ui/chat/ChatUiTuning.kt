package cn.vectory.ocdroid.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object ChatUiTuning {
    val inputActionVerticalThreshold: Dp = 112.dp
    val sessionSheetHeight: Dp = 400.dp
    val contextRingOuterSize: Dp = 28.dp
    val contextRingInnerSize: Dp = 22.dp
}

internal fun shouldUseVerticalChatActions(textFieldHeight: Dp): Boolean {
    return textFieldHeight >= ChatUiTuning.inputActionVerticalThreshold
}
