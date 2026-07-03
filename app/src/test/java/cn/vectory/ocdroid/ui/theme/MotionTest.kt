package cn.vectory.ocdroid.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion token 锁定测试——保证 duration / easing / cached spec 常量不被无意改动
 * （Phase 3 引入）。TweenSpec / CubicBezierEasing 是纯 JVM 数据，无需 Robolectric。
 */
class MotionTest {

    @Test
    fun `duration tokens are stable`() {
        assertEquals(100, AppMotion.DURATION_INSTANT)
        assertEquals(200, AppMotion.DURATION_SMALL)
        assertEquals(300, AppMotion.DURATION_MEDIUM)
        assertEquals(450, AppMotion.DURATION_LARGE)
        assertEquals(800, AppMotion.DURATION_PULSE)
    }

    @Test
    fun `StandardEasing is the Compose built-in FastOutSlowInEasing`() {
        assertEquals(FastOutSlowInEasing, AppMotion.StandardEasing)
    }

    @Test
    fun `EmphasizedDecelerateEasing control points locked via transform sampling`() {
        // CubicBezierEasing 不暴露控制点，但 transform 是控制点的确定性函数。
        // 锁定 3 个采样点 = 锁定整条曲线——若有人改动 (0.05,0.7,0.1,1) 会立即失败。
        val easing = AppMotion.EmphasizedDecelerateEasing
        // decelerate 形态：前半段输出 >> 输入（快速冲入后平缓）
        assertEquals(0.8315f, easing.transform(0.25f), 0.001f)
        assertEquals(0.9502f, easing.transform(0.5f), 0.001f)
        assertEquals(0.9905f, easing.transform(0.75f), 0.001f)
        // 端点
        assertEquals(0f, easing.transform(0f), 0.0001f)
        assertEquals(1f, easing.transform(1f), 0.0001f)
    }

    @Test
    fun `cached standardSmall is TweenSpec with SHORT duration + Standard easing`() {
        val spec = AppMotion.standardSmall
        assertTrue("expected TweenSpec, got ${spec::class}", spec is TweenSpec<*>)
        val tween = spec as TweenSpec<Float>
        assertEquals(AppMotion.DURATION_SMALL, tween.durationMillis)
        assertEquals(AppMotion.StandardEasing, tween.easing)
    }

    @Test
    fun `cached standardMedium is TweenSpec with MEDIUM duration + Standard easing`() {
        val spec = AppMotion.standardMedium
        assertTrue(spec is TweenSpec<*>)
        val tween = spec as TweenSpec<Float>
        assertEquals(AppMotion.DURATION_MEDIUM, tween.durationMillis)
        assertEquals(AppMotion.StandardEasing, tween.easing)
    }

    @Test
    fun `cached vals are process-singleton (zero allocation on re-read)`() {
        // object 的 val 在 init 时分配一次，重复引用应同一实例
        assertTrue(AppMotion.standardSmall === AppMotion.standardSmall)
        assertTrue(AppMotion.standardMedium === AppMotion.standardMedium)
    }

    @Test
    fun `generic standard factory respects custom duration + Standard easing`() {
        val spec = AppMotion.standard<Float>(AppMotion.DURATION_LARGE)
        assertTrue(spec is TweenSpec<*>)
        val tween = spec as TweenSpec<Float>
        assertEquals(AppMotion.DURATION_LARGE, tween.durationMillis)
        assertEquals(AppMotion.StandardEasing, tween.easing)
    }

    @Test
    fun `linearPulse returns TweenSpec with LinearEasing + PULSE duration`() {
        // 返回类型 DurationBasedAnimationSpec 在编译期保证可装入 infiniteRepeatable，
        // 这里运行时再确认具体值
        val spec = AppMotion.linearPulse<Float>()
        assertTrue(spec is TweenSpec<*>)
        val tween = spec as TweenSpec<Float>
        assertEquals(AppMotion.DURATION_PULSE, tween.durationMillis)
        assertEquals(LinearEasing, tween.easing)
    }

    @Test
    fun `linear factory uses LinearEasing`() {
        val spec = AppMotion.linear<Float>(AppMotion.DURATION_SMALL)
        assertTrue(spec is TweenSpec<*>)
        val tween = spec as TweenSpec<Float>
        assertEquals(LinearEasing, tween.easing)
    }

    @Test
    fun `emphasized factory is TweenSpec with EmphasizedDecelerate easing (reserved API smoke)`() {
        // 前瞻 API（Phase 5/6 预留）—— 烟雾测试锁定契约：返回 TweenSpec + 正确 duration/easing
        val spec = AppMotion.emphasized<Float>(AppMotion.DURATION_LARGE)
        assertTrue(spec is TweenSpec<*>)
        val tween = spec as TweenSpec<Float>
        assertEquals(AppMotion.DURATION_LARGE, tween.durationMillis)
        assertEquals(AppMotion.EmphasizedDecelerateEasing, tween.easing)
    }

    @Test
    fun `springMedium factory returns FiniteAnimationSpec (reserved API smoke)`() {
        // 前瞻 API（Phase 6 Card morph 预留）—— 烟雾测试确认可构造、非 null
        val spec = AppMotion.springMedium<Float>()
        assertTrue("springMedium must return a FiniteAnimationSpec", spec is FiniteAnimationSpec<*>)
    }
}
