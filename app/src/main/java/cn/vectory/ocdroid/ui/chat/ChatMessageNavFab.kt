@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import kotlinx.coroutines.launch

/**
 * §navfab-redesign: 单键"跳到最新"悬浮按钮（右下角）。
 *
 * 取代旧的上下双键"在用户消息间跳转"设计——后者因 reverseLayout offset 语义、
 * 变高 item 居中、两段式动画等问题反复出 bug（定位不准 / 二次跳动）。新设计把
 * 目标简化为唯一确定点：列表底部（index 0 = 最新消息），用一段 animateScrollToItem(0)
 * 平滑直达，无定位数学、无 scrollOffset、无游标。
 *
 * 行为：
 *  - 按下 → animateScrollToItem(0)（一段平滑动画跳到最新），随后隐藏。
 *  - 仅在用户"向新滑动"（从历史往最新方向滚）时由调用方浮现。
 *  - 静置 3s 或按一次后由调用方隐藏。
 *  - 键盘打开时不渲染。
 *
 * reverseLayout 语义：index 0 = 最新 = 视觉底部，故"跳到最新"= 滚到 index 0。
 *
 * @param visible 由 ChatMessageList 控制（向新滑动时 true，到底部/3s/按下后 false）。
 * @param onJump 按下时**同步**调用（动画开始前）——调用方据此立即隐藏按钮 + 置程序化
 *  滚动守卫 + re-arm followBottom（"按一次即消失"）。
 * @param onJumpDone 跳转动画结束后（含取消）调用——调用方据此清除程序化滚动守卫。
 */
@Composable
internal fun ChatMessageNavFab(
    listState: LazyListState,
    visible: Boolean,
    onJump: () -> Unit,
    onJumpDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 键盘打开 → 不渲染。
    if (WindowInsets.isImeVisible) return

    val scope = rememberCoroutineScope()

    // §fix-nav-position: modifier（含 .align(BottomEnd)）必须应用在 AnimatedVisibility
    // 上——它是外层 Box 的直接子节点。
    AnimatedVisibility(visible = visible, modifier = modifier) {
        FloatingActionButton(
            onClick = {
                // §navfab-press: onJump 同步先执行——立即隐藏按钮（按一次即消失）+
                // 置守卫，避免动画期间方向检测器重新点亮按钮（闪烁）。
                onJump()
                scope.launch {
                    try {
                        listState.animateScrollToItem(0)
                    } finally {
                        // §navfab-guard: 无论动画正常完成还是被取消（会话切换），清守卫。
                        onJumpDone()
                    }
                }
            },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.chat_jump_to_latest),
            )
        }
    }
}
