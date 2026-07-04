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
import kotlin.math.abs
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
 * §fix-nav-center: 跳转后尽量把目标用户消息居中显示在视口中部；当目标处于列表
 * 两端、可滚动余量不足以居中时，LazyListState 会自动把 animateScrollBy 钳制到
 * 可滚动范围（等价于"空间不足→保持原位不调整"）。目标已可见时跳过
 * animateScrollToItem，直接做居中微调——避免短列表下的弹簧动画抖动。
 *
 * reverseLayout 语义：LazyColumn index 0 = 最新（视觉底部），index 大 = 更旧
 * （视觉顶部）。故「上 = 更旧 = 更大 index」，「下 = 更新 = 更小 index」。
 *
 * @param userMessageLcIndices 用户消息的 LazyColumn item index，**升序**
 *  （[0] = 最新用户消息的 index，[last] = 最旧用户消息的 index）。
 *  IntArray（stable 类型）让本 Composable 在参数不变时跳过重组。
 *
 * @param onJumpStart / onJumpStart §fix-nav-guard: 跳转动画开始前 / 结束后（含
 *  异常取消）的回调。调用方据此置位"程序化滚动进行中"标志，使 ChatMessageList
 *  里的 followBottom 追踪器与 contentVersion 自动滚动 effect 在跳转期间跳过
 *  各自的写入 / 滚动——否则它们会在动画期间重新 arm followBottom，下一个
 *  contentVersion tick 的 scrollToItem(0) 会把列表拽回最新消息（"跳转后弹回
 *  底部"bug）。
 */
@Composable
internal fun ChatMessageNavFab(
    listState: LazyListState,
    userMessageLcIndices: IntArray,
    visible: Boolean,
    onInteract: () -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateBottom: () -> Unit,
    // §fix-nav-guard: 见上，程序化跳转进行中标志的置位 / 清零回调。
    onJumpStart: () -> Unit,
    onJumpEnd: () -> Unit,
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
            // 上 = 上一条（更旧）：最小的大于「当前居中 item index」的用户 index。
            FloatingActionButton(
                onClick = {
                    // §fix-nav-cursor: 游标用「视口内最靠近中线的用户消息」index，
                    // 而非 firstVisibleItemIndex / 任意居中 item。居中跳转后比 target 更新
                    // 的消息仍留在视口内，若用 firstVisibleItemIndex（或非用户 item 的
                    // centerIdx）做游标，会反复锁定同一条用户消息（"卡在一组对话上"）。
                    // 用可见用户消息做锚点：已可见的视为"已读"，UP/DOWN 跳到更旧/更新
                    // 的下一条。无可见用户消息时回退到 centerIdx。
                    val cur = navAnchorIndex(listState, userMessageLcIndices)
                    val target = userMessageLcIndices.firstOrNull { it > cur }
                    onInteract()
                    // §fix-nav-race (kimo 🟡-1): onJumpStart 必须在 followBottom 写入
                    // 之前，消除"守卫未置位但 followBottom 已改"的竞态窗口。
                    onJumpStart()
                    onNavigateUp()
                    scope.launch {
                        try {
                            if (target != null) {
                                jumpToCenteredListState(listState, target)
                            } else {
                                // Q7: 已在最旧一条用户消息 → 跳到列表最顶端（load-more 位置）。
                                val topIdx = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                jumpToCenteredListState(listState, topIdx)
                            }
                        } finally {
                            onJumpEnd()
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
            // 下 = 下一条（更新）：最大的小于「当前居中 item index」的用户 index。
            FloatingActionButton(
                onClick = {
                    // §fix-nav-cursor: 同 UP，游标用可见用户消息锚点。
                    val cur = navAnchorIndex(listState, userMessageLcIndices)
                    val target = userMessageLcIndices.lastOrNull { it < cur }
                    onInteract()
                    // §fix-nav-race (kimo 🟡-1): onJumpStart 先于 followBottom 写入。
                    onJumpStart()
                    // §fix-nav-bottom: target == null 表示跳到列表最底（最新
                    // 消息），在此同步 re-arm followBottom（在 launch 之外、
                    // 动画开始之前），使后续 ~300ms 动画期间到达的 SSE token
                    // 的 LaunchedEffect(contentVersion) scrollToItem(0) 能继续
                    // 跟底。target != null（跳到中间某条用户消息）时不调用。
                    if (target == null) onNavigateBottom()
                    scope.launch {
                        try {
                            if (target != null) {
                                jumpToCenteredListState(listState, target)
                            } else {
                                // Q8: 已在最新用户消息，继续按下 → 跳到列表最底（最新消息）。
                                listState.animateScrollToItem(0)
                            }
                        } finally {
                            onJumpEnd()
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
 * §fix-nav-center: 跳转到 [target] 并尽量把它居中到视口中部；列表两端空间不足时
 * 自动降级为"尽量靠中"（LazyListState 钳制到可滚动范围），等价于用户要求的
 * "空间不足→保持原位不调整"。
 *
 * §fix-nav-onestep（核心）：用 `animateScrollToItem(target, desiredOffset)` **单段**
 * 动画直接滚到居中位置，避免旧实现 `animateScrollToItem(target) + animateScrollBy`
 * 两段动画被用户看到"先滑到底部再跳到中间"。
 *
 * 步骤：
 *  1. 若 [target] 已可见 → 直接 centerTarget（单段 animateScrollBy 居中微调）。
 *  2. 若 [target] 不可见 → 用可见 item 的尺寸估算 itemSize，算 desiredOffset =
 *     (vh - itemSize)/2，调 animateScrollToItem(target, desiredOffset) 一段直达。
 *     - scrollOffset 语义：item 滚动后在其滚动起点（reverseLayout = 视觉底部）之上
 *       的偏移 = visibleItemsInfo.offset，故传入 desiredOffset 即让 target 落在
 *       desiredOffset 处 ≈ 居中。
 *  3. 变高 item 下 itemSize 是估算（取首个可见 item），落点可能略偏；animateScrollToItem
 *     落定后用实测尺寸做一次小幅 centerTarget 精修（等高 item 下 delta≈0 被 <1f
 *     守卫跳过，无第二段可见动画）。
 *  4. §hard-guarantee：极端情况 target 不可见 → scrollToItem(target) 兜底。
 */
private suspend fun jumpToCenteredListState(
    listState: androidx.compose.foundation.lazy.LazyListState,
    target: Int,
) {
    val vh = listState.layoutInfo.viewportSize.height.toFloat()
    if (vh <= 0f) return
    val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
    if (visible != null) {
        // target 已可见 → 直接居中微调（单段 animateScrollBy，不触发 scrollToItem）。
        centerTarget(listState, target, visible.offset.toFloat(), visible.size.toFloat(), vh)
        return
    }
    // target 不可见 → 单段 animateScrollToItem 直达居中位置。
    val estimate = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.toFloat()
    if (estimate == null || estimate <= 0f || estimate >= vh) {
        // 拿不到尺寸或比视口还高 → 只能带到 scroll-start。
        listState.animateScrollToItem(target)
        return
    }
    val desiredOffset = ((vh - estimate) / 2f).toInt().coerceAtLeast(0)
    listState.animateScrollToItem(target, desiredOffset)
    // 精修：用 target 落定后的实测尺寸校正居中（等高 item 下无操作）。
    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
    if (info == null) {
        listState.scrollToItem(target)
    } else {
        centerTarget(listState, target, info.offset.toFloat(), info.size.toFloat(), vh)
    }
}

/**
 * §fix-nav-center: 把 [target] 平滑滚动到视口中部。
 *
 * @param currentOffset target 当前相对视口（reverseLayout 下从视觉底部起算）的偏移（px）。
 * @param itemSize target 的实测高度（px）。
 * @param viewportHeight 视口主轴高度（px）。
 *
 * 居中目标：desiredOffset = (viewportHeight - itemSize) / 2。
 *
 * §fix-nav-sign（核心）：reverseLayout 下 `animateScrollBy(delta)` 与 item offset **反向**
 * ——正向 delta（scrollOffset 增大 / 向更旧滚动）会让内容整体下移、item 的 offset **减小**。
 * 即 offset_change = -delta。因此要把 offset 从 currentOffset 改到 desiredOffset，需：
 *   -delta = desiredOffset - currentOffset  →  delta = currentOffset - desiredOffset
 * 旧代码误用 `animateScrollBy(desiredOffset - currentOffset)`（正向），把 target 推出
 * 视口（offset 变负 / 越过顶部），触发硬保证 scrollToItem 回弹（"滑到底→再滑一点→弹回"症状）。
 *
 * LazyListState 自动钳制 delta 到可滚动范围——两端空间不足时 target 停在"尽量靠中"的位置。
 */
private suspend fun centerTarget(
    listState: androidx.compose.foundation.lazy.LazyListState,
    target: Int,
    currentOffset: Float,
    itemSize: Float,
    viewportHeight: Float,
) {
    if (itemSize <= 0f || itemSize >= viewportHeight) return // 比视口还高 → 无法居中，保留原位
    val desiredOffset = (viewportHeight - itemSize) / 2f
    // §fix-nav-sign: delta = currentOffset - desiredOffset（reverseLayout 反向）。
    val delta = currentOffset - desiredOffset
    if (abs(delta) < 1f) return // 已足够居中
    listState.animateScrollBy(delta)
    // §hard-guarantee: 变高 item 下居中量近似，极端情况可能把 target 推出视口。
    if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
        listState.scrollToItem(target)
    }
}

/**
 * §fix-nav-cursor: 返回 NavFab 上/下跳转的"当前游标"——视口内**最靠近视口中线的
 * 用户消息** index；无可见用户消息时回退到最靠近中线的任意 item index。
 *
 * 用可见用户消息做锚点：已进入视口的用户消息视为"已读"，UP/DOWN 据此跳到更旧/更新
 * 的下一条，避免反复锁定同一条（"卡在一组对话上"bug）。回退分支处理用户滚到两条
 * 用户消息之间（无用户消息可见）的情况。
 */
private fun navAnchorIndex(
    listState: androidx.compose.foundation.lazy.LazyListState,
    userMessageLcIndices: IntArray,
): Int {
    val layout = listState.layoutInfo
    val vh = layout.viewportSize.height.toFloat()
    if (vh <= 0f) return listState.firstVisibleItemIndex
    val half = vh / 2f
    fun dist(info: androidx.compose.foundation.lazy.LazyListItemInfo): Float {
        val c = info.offset + info.size / 2f
        return if (c >= half) c - half else half - c
    }
    // 优先：可见用户消息中最靠近中线的。
    val visibleSet = layout.visibleItemsInfo
    val closestUser = userMessageLcIndices
        .asList()
        .mapNotNull { idx -> visibleSet.firstOrNull { it.index == idx } }
        .minByOrNull { dist(it) }
    if (closestUser != null) return closestUser.index
    // 回退：无可见用户消息 → 最靠近中线的任意 item。
    return visibleSet.minByOrNull { dist(it) }?.index ?: listState.firstVisibleItemIndex
}
