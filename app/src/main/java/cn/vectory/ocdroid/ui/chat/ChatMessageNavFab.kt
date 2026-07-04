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
    // §fix-nav-yank: 用户点"上"主动向上导航时调用，调用方据此把
    // followBottom 置 false，避免程序化向上滚动被方向检测器忽略后，
    // 下一个 SSE token 的 LaunchedEffect(contentVersion) scrollToItem(0)
    // 把列表拽回底部（"跳转向上后自动弹回"）。
    onNavigateUp: () -> Unit,
    // §fix-nav-bottom: 对称于 onNavigateUp。用户点"下"跳到列表最底
    // （最新消息，target == null 分支）时调用，调用方据此把 followBottom
    // 置 true，使 ~300ms 的 animateScrollToItem(0) 期间到达的 SSE token
    // 仍能自动跟到底（之前可能被"上"按钮置 false）。跳到中间某条用户消息
    // （target != null）时不调用——用户在读历史，followBottom 应保持 false。
    onNavigateBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Q12: 无用户消息 → 隐藏。
    if (userMessageLcIndices.isEmpty()) return
    // Q11: 键盘打开 → 隐藏。
    if (WindowInsets.isImeVisible) return

    val scope = rememberCoroutineScope()

    // §fix-nav-position: modifier（含 .align(BottomEnd)）必须应用在
    // AnimatedVisibility 上——它是外层 Box 的直接子节点。之前 modifier
    // 挂在内层 Column 上，Column 的直接父是 AnimatedVisibility（非 Box），
    // align() 不生效，AnimatedVisibility 以默认 TopStart 定位 → FAB 渲染在
    // 左上角而非右下角。
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 上 = 上一条（更旧）：最小的大于 firstVisibleItemIndex 的用户 index。
            FloatingActionButton(
                onClick = {
                    val cur = listState.firstVisibleItemIndex
                    val target = userMessageLcIndices.firstOrNull { it > cur }
                    onInteract()
                    onNavigateUp()
                    scope.launch {
                        if (target != null) {
                            // §issue-2: reverseLayout 下 scrollToItem 把 target 放到
                            // 视觉底部（scroll start）。追加 animateScrollBy（负向 = 向更新方向）把它推到
                            // 视觉顶部，方便用户向下（更新方向）阅读后续回复。
                            listState.animateScrollToItem(target)
                            // §fix-nav-bounce: 钳制"推向视觉顶部"的滚动量，避免对近底部
                            // 目标过冲到 index 0（最新消息）从而丢失刚跳转到的用户消息——
                            // 即"跳转后回弹到底部"bug。上限取 (target-1)*itemSize：保证
                            // 跳完后 firstVisibleItemIndex >= target-1，target 始终可见，
                            // 绝不越过 target 到达 index 0。
                            val push = cappedNavPush(listState, target)
                            if (push > 0f) listState.animateScrollBy(-push)
                        } else {
                            val topIdx = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            listState.animateScrollToItem(topIdx)
                            val push = cappedNavPush(listState, topIdx)
                            if (push > 0f) listState.animateScrollBy(-push)
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
                    // §fix-nav-bottom: target == null 表示跳到列表最底（最新
                    // 消息），在此同步 re-arm followBottom（在 launch 之外、
                    // 动画开始之前），使后续 ~300ms 动画期间到达的 SSE token
                    // 的 LaunchedEffect(contentVersion) scrollToItem(0) 能继续
                    // 跟底。target != null（跳到中间某条用户消息）时不调用。
                    if (target == null) onNavigateBottom()
                    scope.launch {
                        if (target != null) {
                            listState.animateScrollToItem(target)
                            // §fix-nav-bounce: 同 up 分支，钳制负向滚动避免过冲到 index 0。
                            val push = cappedNavPush(listState, target)
                            if (push > 0f) listState.animateScrollBy(-push)
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

/**
 * §fix-nav-bounce: 跳转到 [target] 后"推向视觉顶部"的安全滚动量（px）。
 *
 * 原实现固定 `-0.8 * viewportHeight`，对靠近底部的目标会越过 index 0 被
 * LazyListState 钳到最新消息，丢失刚跳转到的用户消息（"跳转后回弹"bug）。
 *
 * 这里把推量钳为 `min(0.8 * vh, (target - 1) * itemSize)`：
 *  - 远离底部的目标（target 大）：0.8vh 通常更小，行为不变，仍推到视觉顶部附近。
 *  - 靠近底部的目标（target 小）：被 (target-1)*itemSize 限制，保证跳完后
 *    firstVisibleItemIndex >= target-1，target 始终在视口内，绝不回弹到 index 0。
 *
 * itemSize 取 visibleItemsInfo 的首项尺寸（行高基本一致）；拿不到时返回 0（不推）。
 */
private suspend fun cappedNavPush(
    listState: androidx.compose.foundation.lazy.LazyListState,
    target: Int,
): Float {
    val vh = listState.layoutInfo.viewportSize.height.toFloat().coerceAtLeast(0f)
    val itemSize = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: 0f
    if (vh <= 0f || itemSize <= 0f || target <= 1) return 0f
    val desired = 0.8f * vh
    val maxWithoutOvershoot = (target - 1) * itemSize
    return desired.coerceAtMost(maxWithoutOvershoot)
}
