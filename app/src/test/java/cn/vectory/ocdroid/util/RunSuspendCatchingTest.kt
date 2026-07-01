package cn.vectory.ocdroid.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-14: 单测覆盖 [runSuspendCatching] —— 该扩展被 OpenCodeRepository 的 30+
 * 处 suspend 入口依赖，行为契约不可回归：
 *
 *  - 业务异常按 [Result.failure] 包装（保留原异常类型与消息）。
 *  - **CancellationException 必须透传（rethrow）**，绝不能被吞为 failure。
 *    否则会破坏结构化并发：ViewModel clear / scope cancel 无法干净传播，
 *    协程会"假成功"或卡在已取消的 scope 里继续运行。
 *
 * 纯 JVM 单测，无需 Robolectric。
 */
class RunSuspendCatchingTest {

    @Test
    fun `wraps normal return as success`() {
        val result = runSuspendCatching { "hello" }

        assertTrue(result.isSuccess)
        assertEquals("hello", result.getOrThrow())
    }

    @Test
    fun `preserves success value of nullable type`() {
        val result = runSuspendCatching<String?> { null }

        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrThrow())
    }

    @Test
    fun `wraps thrown business exception as failure and preserves cause`() {
        val cause = IllegalStateException("boom")

        val result = runSuspendCatching<String> { throw cause }

        assertTrue(result.isFailure)
        val thrown = result.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(
            "exception type must be preserved (got ${thrown?.javaClass?.name})",
            thrown is IllegalStateException
        )
        // 同一实例引用 —— 不可被重新包装。
        assertTrue("exception instance must be the same", thrown === cause)
        assertEquals("boom", thrown!!.message)
    }

    @Test
    fun `wraps generic RuntimeException as failure (not rethrown)`() {
        val result = runSuspendCatching<String> { throw RuntimeException("regular") }

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull() is RuntimeException)
    }

    @Test
    fun `wraps Error subclass as failure mirroring runCatching semantics`() {
        // 与标准 runCatching 一致：连 Error 也按 failure 包装，避免 OOM 等
        // 在 runSuspendCatching 中意外穿透。
        val result = runSuspendCatching<String> { throw OutOfMemoryError("oom") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is OutOfMemoryError)
    }

    /**
     * **核心防回归**：CancellationException 透传，不被吞为 Result.failure。
     *
     * 这是 runSuspendCatching 存在的全部理由；任何把它"简化"回 runCatching
     * 的重构都会让此用例挂掉，从而提醒 reviewer。
     */
    @Test
    fun `rethrowsCancellationException instead of wrapping as failure`() = runTest {
        var threw = false
        try {
            runSuspendCatching<String> { throw CancellationException("test") }
        } catch (e: CancellationException) {
            threw = true
        }
        assertTrue(
            "CancellationException must be rethrown, not wrapped as Result.failure",
            threw
        )
    }

    /**
     * 同上契约，但确认抛出的 CancellationException 实例本身被透传（非重新构造）。
     */
    @Test
    fun `rethrownCancellationException preserves the original instance`() = runTest {
        val original = CancellationException("struct-cancel")
        var captured: Throwable? = null
        try {
            runSuspendCatching<String> { throw original }
        } catch (e: CancellationException) {
            captured = e
        }
        assertNotNull(captured)
        assertTrue(
            "the exact CancellationException instance must propagate (got ${captured?.javaClass?.name})",
            captured === original
        )
    }
}
