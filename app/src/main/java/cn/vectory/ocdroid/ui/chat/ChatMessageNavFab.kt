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
                    // §fix-nav-leak (kimo 🔴): onNavigateUp 同步设置 followBottom=false
                    // + navPinnedAway=true。navPinnedAway 锁存保护"onJumpStart 尚未执行
                    // 的一帧窗口"内的观察者（底部追踪器不会短路 arm followBottom）。
                    // §fix-nav-leak: onJumpStart 移入 launch try 块第一行——若 scope 已
                    // 取消（composable 正在 dispose），协程体不执行 → onJumpStart 不调用
                    // → navJumpDepth 不递增 → 无泄漏（旧实现 onJumpStart 在 launch 外同步
                    // 调用，scope 取消时 finally 不跑 → 计数永久 >0 → 自动跟底永久失效）。
                    onNavigateUp()
                    scope.launch {
                        try {
                            onJumpStart()
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
                    // §fix-nav-bottom: target == null 表示跳到列表最底（最新消息），
                    // 同步 re-arm followBottom（在 launch 之外），使后续动画期间到达的
                    // SSE token 的 LaunchedEffect(contentVersion) scrollToItem(0) 能跟底。
                    // target != null（跳到中间用户消息）时不调用。
                    // §fix-nav-leak: onJumpStart 移入 launch try 块（见 UP 按钮注释）。
                    if (target == null) onNavigateBottom()
                    scope.launch {
                        try {
                            onJumpStart()
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
 * §fix-nav-top: 跳转到 [target] 并把它置顶到视口**视觉顶端**（回复在其下方铺开）；
 * 列表空间不足时自动降级为"尽量靠顶"（LazyListState 钳制到可滚动范围），等价于
 * 用户要求的"空间不足→保持原位不调整"。
 *
 * §fix-nav-onestep（核心，gpter 🔴 / glmer O-1）：离屏分支用**瞬时 scrollToItem(target)
 * + 单段 animateScrollBy** 置顶，确保**只有一段可见动画**，杜绝用户抱怨的"先滑到底部
 * 再弹到中部"两段式。
 *
 * **硬约束**：离屏分支【禁止】调 animateScrollToItem(target)（会逐帧长滑动 = 两段式
 * 第一段）或 animateScrollToItem(target, scrollOffset)（scrollOffset 语义是"item 起点之后
 * 滚过的像素"、受 itemSize 上限约束、≠ visibleItemsInfo.offset，变高 item 测试已证伪）。
 * 离屏分支只能：瞬时 scrollToItem(target) + centerTarget(animateScrollBy)。
 *
 * 步骤：
 *  1. 若 [target] 已可见 → 直接 centerTarget（单段 animateScrollBy 置顶微调；target 已
 *     实测，无估算误差，单段即精确置顶）。
 *  2. 若 [target] 不可见 → 瞬时 scrollToItem(target) [一帧，target 到 scroll-start，
 *     非动画、不触发 isScrollInProgress] → 读 target 实测 offset/size → centerTarget
 *     单段 animateScrollBy 平滑滚到精确置顶。整体只有一段可见动画。
 *     - reverseLayout 下 animateScrollBy(delta) 使 offset 变化 -delta（正向 delta=向更旧
 *       滚动=内容下移=offset 减小），故 centerTarget 内 delta = currentOffset - desiredOffset。
 *  3. §hard-guarantee：centerTarget 内部若极端情况把 target 推出视口 → scrollToItem(target)
 *     兜底（正常几何不触发）。
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
    // target 不可见 → 瞬时 scrollToItem 带到 scroll-start + 单段 animateScrollBy 居中。
    // §fix-nav-onestep (glmer O-1 / gpter 🔴-1): 离屏目标用【瞬时】scrollToItem(target)
    // 带到 scroll-start（一帧），再用 centerTarget 的【单段】animateScrollBy 平滑滚到
    // 精确居中（用 target 实测尺寸）。整体只有一段可见动画。
    //
    // 为何不用 animateScrollToItem(target)（动画带到 scroll-start）：那会逐帧滑过所有中间
    // item（长滑动），用户看到"先滑到底部"——正是被抱怨的两段式第一段。
    // 为何不用 animateScrollToItem(target, scrollOffset)：scrollOffset 语义是"item 起点之后
    // 滚过的像素"，受 itemSize 上限约束（>itemSize 被钳制），不等于 visibleItemsInfo.offset，
    // 无法可靠居中（变高 item 测试证伪）。glmer O-1 / dser 🟡-3 / gpter 🟠-1 均警告此语义不稳定。
    //
    // 瞬时 scrollToItem 对远距离目标是"一帧瞬移"（非长滑动），随后一段平滑居中动画——
    // 用户感知为"消息出现并平滑移到中部"，优于长滑动两段式。
    listState.scrollToItem(target)
    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
    if (info == null) {
        // §hard-guarantee: 极端高度突变导致 target 不可见 → 兜底已在 scrollToItem 完成。
        return
    }
    centerTarget(listState, target, info.offset.toFloat(), info.size.toFloat(), vh)
}

/**
 * §fix-nav-top: 把 [target] 平滑滚动到视口**视觉顶端**（紧贴上沿，回复在其下方铺开）。
 *
 * @param currentOffset target 当前相对视口（reverseLayout 下从视觉底部起算）的偏移（px）。
 * @param itemSize target 的实测高度（px）。
 * @param viewportHeight 视口主轴高度（px）。
 *
 * 置顶目标：desiredOffset = viewportHeight - itemSize（target 顶边贴视口顶端，
 * offset 从底部起算 = vh - itemSize）。target 占据顶部 itemSize 像素，下方剩余空间
 * 显示更新的内容（reverseLayout 下更新=视觉下方=助手回复），便于阅读。
 *
 * §fix-nav-sign（核心）：reverseLayout 下 `animateScrollBy(delta)` 与 item offset **反向**
 * ——正向 delta（scrollOffset 增大 / 向更旧滚动）会让内容整体下移、item 的 offset **减小**。
 * 即 offset_change = -delta。因此要把 offset 从 currentOffset 改到 desiredOffset，需：
 *   -delta = desiredOffset - currentOffset  →  delta = currentOffset - desiredOffset
 *
 * LazyListState 自动钳制 delta 到可滚动范围——目标靠近最新端、下方更新内容不足以
 * 填满视口时，target 停在"尽量靠顶"的位置（"空间不足→保持原位"语义）。
 */
private suspend fun centerTarget(
    listState: androidx.compose.foundation.lazy.LazyListState,
    target: Int,
    currentOffset: Float,
    itemSize: Float,
    viewportHeight: Float,
) {
    if (itemSize <= 0f || itemSize >= viewportHeight) return // 比视口还高 → 无法置顶，保留原位
    val desiredOffset = viewportHeight - itemSize // §fix-nav-top: 置顶（非居中）
    // §fix-nav-sign: delta = currentOffset - desiredOffset（reverseLayout 反向）。
    val delta = currentOffset - desiredOffset
    if (abs(delta) < 1f) return // 已在置顶位置
    listState.animateScrollBy(delta)
    // §hard-guarantee: 变高 item / 钳制边界极端情况可能把 target 推出视口。
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
