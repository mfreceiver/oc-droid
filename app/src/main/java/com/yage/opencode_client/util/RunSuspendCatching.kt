package com.yage.opencode_client.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * R-14: cancellation-safe 版本的 [runCatching]，供 suspend 函数体使用。
 *
 * 标准库的 `runCatching { ... }` 会捕获 **所有** Throwable，包括
 * [CancellationException]。在协程里吞掉 CancellationException 是禁忌：
 * 它会破坏结构化并发（ViewModel clear / scope cancel 无法干净传播），
 * 导致协程"假成功"或卡在已取消的 scope 里继续运行。
 *
 * 本函数在捕获链里 **优先** 透传 CancellationException，其余 Throwable
 * 仍按 [Result.failure] 返回，行为与 `runCatching` 对业务异常一致。
 *
 * 仅用于 `suspend` 函数体；普通（非 suspend）代码里 CancellationException
 * 不会出现，可直接用标准 `runCatching`。
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
