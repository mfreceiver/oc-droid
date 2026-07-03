package cn.vectory.ocdroid.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntSize

// ─────────────────────────────────────────────────────────────────────────────
// AppMotion — Phase 3 动效 token 系统。
//
// 集中定义 duration / easing / animation spec，替代散落业务代码的魔法数。
// 新代码用 AppMotion 的常量 / 工厂，而非裸 tween(N)。
//
// 设计参考 M3 motion 规范（核对：developer.android.com/develop/ui/compose/animation):
//  - StandardEasing (FastOutSlowIn)：常见过渡减速曲线
//  - EmphasizedDecelerateEasing：M3 emphasized 的减速 cubic 变体（非完整 emphasized
//    path——后者不是单一 cubic-bezier；此处采用 decelerate 半部，无 overshoot）。
//  - Spring：物理感连续运动
//
// 常用 Float 档位预缓存为 val 常量（standardSmall / standardMedium），避免在热路径
// （高频重组的 Composable 如 ChatTopBar / MainActivity nav crossfade）每次调用分配
// 新 TweenSpec 对象。泛型工厂 standard<T>()/emphasized<T>()/springMedium<T>() 保留给
// 非 Float 类型（Rect/IntOffset/IntSize）以及为 Phase 6（Card morph）/ Phase 7
// （横屏分栏 SharedTransition）预留——当前无调用方是有意的前瞻 API。
//
// 豁免：MetadataMarker.kt 的行内详情展开用 Compose 默认 fadeIn()+expandVertically()
// （spring-based，无魔法数），不纳入 token 化——其行为物理感与本 token 系统的
// duration-based 风格不同，强行替换引入回归。
// ─────────────────────────────────────────────────────────────────────────────

object AppMotion {
    // ── Duration tokens (ms) ──────────────────────────────────────────────
    /** 极短（瞬时反馈，如 ripple 补充）。 */
    const val DURATION_INSTANT = 100
    /** 短（SessionTabStrip 显隐等紧凑过渡，保留原 tween(200) 行为）。 */
    const val DURATION_SMALL = 200
    /** 中（标准内容过渡，M3 推荐范围 250-400）。 */
    const val DURATION_MEDIUM = 300
    /** 长（hero / 全屏转换，M3 推荐范围 300-500）。 */
    const val DURATION_LARGE = 450
    /** 持续脉冲周期（SessionStatusDot busy pulse）。 */
    const val DURATION_PULSE = 800

    // ── Easing tokens ─────────────────────────────────────────────────────
    /** Standard easing = FastOutSlowIn（减速进入停止）。Compose 内建常量。 */
    val StandardEasing = FastOutSlowInEasing
    /** M3 Emphasized Decelerate cubic 变体（减速入场，无 overshoot）。
     *  非 M3 完整 emphasized path（后者非单一 cubic-bezier）。控制点已由 MotionTest
     *  经 transform 采样锁定。 */
    val EmphasizedDecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    // ── 预缓存常用 Float spec（避免热路径每次分配）────────────────────────
    /** 标准 SHORT 时长 Float spec（最常用：fade / crossfade / slide）。 */
    val standardSmall: FiniteAnimationSpec<Float> =
        tween(durationMillis = DURATION_SMALL, easing = StandardEasing)
    /** 标准 MEDIUM 时长 Float spec。 */
    val standardMedium: FiniteAnimationSpec<Float> =
        tween(durationMillis = DURATION_MEDIUM, easing = StandardEasing)
    /** animateContentSize 用：SHORT emphasized IntSize spec（卡片展开/折叠的容器尺寸变化）。 */
    val expandSizeSpec: FiniteAnimationSpec<IntSize> =
        tween(durationMillis = DURATION_SMALL, easing = EmphasizedDecelerateEasing)

    // ── 泛型工厂（非 Float 类型用，如 Rect / IntOffset / Color）────────────
    /** Standard 过渡泛型版（FastOutSlowIn）。默认中时长。 */
    fun <T> standard(durationMillis: Int = DURATION_MEDIUM): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = StandardEasing)

    /** Emphasized 过渡泛型版（decelerate）。用于 hero / 表现性动效。 */
    fun <T> emphasized(durationMillis: Int = DURATION_MEDIUM): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = EmphasizedDecelerateEasing)

    /** Spring（物理感）。用于状态连续变化、手指跟随。 */
    fun <T> springMedium(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

    /** 线性 tween（非循环的线性过渡，如打字机式 fade）。 */
    fun <T> linear(durationMillis: Int = DURATION_MEDIUM): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = LinearEasing)

    /** 线性脉冲（持续循环动画，如 busy pulse）。返回 DurationBasedAnimationSpec
     * 以兼容 infiniteRepeatable 的 animation 参数。 */
    fun <T> linearPulse(durationMillis: Int = DURATION_PULSE): DurationBasedAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = LinearEasing)

    // ── 常用过渡 helper ────────────────────────────────────────────────────
    // 注：helper 内 expandVertically/slideInVertically 需 IntSize/IntOffset spec，
    // 用泛型 standard(DURATION_SMALL) 让类型推断（helper 调用低频，分配可接受）。
    /** SessionTabStrip 显隐入场：fade + 垂直展开 + 从顶部滑入（SHORT standard）。 */
    fun tabStripEnter(): EnterTransition =
        fadeIn(standardSmall) +
            expandVertically(standard(DURATION_SMALL)) +
            slideInVertically(standard(DURATION_SMALL)) { fullHeight -> -fullHeight }

    /** SessionTabStrip 退场：fade + 垂直收起 + 向顶部滑出。 */
    fun tabStripExit(): ExitTransition =
        fadeOut(standardSmall) +
            shrinkVertically(standard(DURATION_SMALL)) +
            slideOutVertically(standard(DURATION_SMALL)) { fullHeight -> -fullHeight }
}
