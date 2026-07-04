@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Phase 8: 消息导航悬浮按钮（右侧中下，上下两键）。
 *
 * 行为（用户确认 Q7-Q12）：
 *  - 每按一次上/下 → 跳到当前已加载消息中的前/后一条「我发出的消息」
 *  - 基于 visible position（Q9a）：以 listState.firstVisibleItemIndex 为界，找
 *    最近的老/新一条用户消息
 *  - 最前（最旧）一条按上 → 回到最顶端（load-more 位置，Q7：不自动加载）
 *  - 最后（最新）一条按下 → 跳到列表最底（最新消息，Q8）
 *  - 纯箭头无计数（Q10）
 *  - 键盘打开时隐藏（Q11）
 *  - 当前会话无用户消息时隐藏（Q12）
 *
 * reverseLayout 语义：LazyColumn index 0 = 最新（视觉底部），index 大 = 更旧
 * （视觉顶部）。故「上 = 更旧 = 更大 index」，「下 = 更新 = 更小 index」。
 *
 * @param userMessageLcIndices 用户消息的 LazyColumn item index，**升序**
 *  （[0] = 最新用户消息的 index，[last] = 最旧用户消息的 index）。
 *  IntArray（stable 类型）让本 Composable 在参数不变时跳过重组。
 */
@Composable
internal fun ChatMessageNavFab(
    listState: LazyListState,
    userMessageLcIndices: IntArray,
    visible: Boolean,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Q12: 无用户消息 → 隐藏。
    if (userMessageLcIndices.isEmpty()) return
    // Q11: 键盘打开 → 隐藏。
    if (WindowInsets.isImeVisible) return

    val scope = rememberCoroutineScope()

    AnimatedVisibility(visible = visible) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 上 = 上一条（更旧）：最小的大于 firstVisibleItemIndex 的用户 index。
        FloatingActionButton(
            onClick = {
                val cur = listState.firstVisibleItemIndex
                val target = userMessageLcIndices.firstOrNull { it > cur }
                onInteract()
                scope.launch {
                    if (target != null) {
                        // §issue-2: reverseLayout 下 scrollToItem 把 target 放到
                        // 视觉底部（scroll start）。追加 animateScrollBy（负向 = 向更新方向）把它推到
                        // 视觉顶部，方便用户向下（更新方向）阅读后续回复。
                        listState.animateScrollToItem(target)
                        val vh = listState.layoutInfo.viewportSize.height.toFloat()
                        listState.animateScrollBy(-vh * 0.8f)
                    } else {
                        val topIdx = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                        listState.animateScrollToItem(topIdx)
                        val vh = listState.layoutInfo.viewportSize.height.toFloat()
                        listState.animateScrollBy(-vh * 0.8f)
                    }
                }
            },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "Previous user message",
            )
        }
        // 下 = 下一条（更新）：最大的小于 firstVisibleItemIndex 的用户 index。
        FloatingActionButton(
            onClick = {
                val cur = listState.firstVisibleItemIndex
                val target = userMessageLcIndices.lastOrNull { it < cur }
                onInteract()
                scope.launch {
                    if (target != null) {
                        listState.animateScrollToItem(target)
                        val vh = listState.layoutInfo.viewportSize.height.toFloat()
                        listState.animateScrollBy(-vh * 0.8f)
                    } else {
                        // Q8: 已在最新用户消息，继续按下 → 跳到列表最底（最新消息）。
                        listState.animateScrollToItem(0)
                    }
                }
            },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Next user message",
            )
        }
    }
    }
}
