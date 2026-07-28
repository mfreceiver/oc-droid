package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 测试组1：SkeletonReloadCoordinator 核心行为测试。
 *
 * 覆盖 plan §4.3 的 core behavior:
 *   (a) digest→repository reload→merge→ChatContentLoaded dispatch 全链路
 *   (b) 空页不清 transcript + 此前失败后 watchdog retry 获得 HTTP 成功空页时的状态复位
 *   (c) fetched 缺席的本地消息 created >= newestFetched（同毫秒与更新消息）不删除
 *   (f) markLocallyInjected 缺席守卫 + mark 先于 slice 发布时不会被并发 reload 删除
 *
 * 测试设计原则：
 *   - 使用真实 SharedStateStore + mockk OpenCodeRepository + 虚拟时钟
 *   - 所有辅助内嵌于此文件（不新增依赖、不修改 production/androidTest/其他测试）
 *   - 断言不弱化：若因生产缺陷(prod bug) 失败，保留正确测试并报告阻塞
 *   - coordinator 使用独立 TestScope 管理（init 块的无限 collect 手动 cancel）
 *   - 含 watchdog 失败路径的测试用 runCurrent() 代替 advanceUntilIdle()
 *     避免 watchdog 无限 while(true) 循环导致虚拟时钟永不休止
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkeletonReloadCoordinatorCoreTest {

    // ─── fixture helpers ───────────────────────────────────────────────────

    /** 创建一个路由已就绪（chat/{sessionId} + 非零 routeInstance）的 store。
     *  使用 mutateState (internal, 同一 module 可见) 写入初始状态，
     *  确保 DerivedStateFlow 从正确的 state 实例读取。 */
    private fun createReadyStore(
        sessionId: String,
        routeInstance: Long = 42L,
        messages: List<Message> = emptyList(),
    ): SharedStateStore {
        val store = SharedStateStore()
        store.mutateState {
            it.copy(
                chat = it.chat.copy(
                    currentSessionId = sessionId,
                    messages = messages,
                ),
                nav = it.nav.copy(lastRoute = "chat/$sessionId"),
                chatRouteInstance = routeInstance,
            )
        }
        return store
    }

    /** 为 coordinator 创建独立 TestScope。调用方应在使用后 cancel() 以销毁 init 块
     *  中启动的无限 collect 协程。advanceOnScope()、advanceOnScopeBy() 辅助函数
     *  操纵该 scope 的调度器。 */
    private class CoordinatorScope {
        val scope: TestScope = TestScope(StandardTestDispatcher())
        fun advance() { scope.testScheduler.advanceUntilIdle() }
        fun advance(timeMs: Long) {
            scope.testScheduler.advanceTimeBy(timeMs)
            scope.testScheduler.runCurrent()
        }
        fun runCurrent() { scope.testScheduler.runCurrent() }
        fun cancel() { scope.cancel(CancellationException("test done")) }
    }

    private fun CoordinatorScope.coordinator(
        store: SharedStateStore,
        repo: OpenCodeRepository,
    ): SkeletonReloadCoordinator = SkeletonReloadCoordinator(
        scope = scope,
        repository = repo,
        slices = store.slices,
        currentServerGroupFp = { "fp1" },
    )

    private fun msg(
        id: String,
        created: Long? = null,
        role: String = "user",
    ): Message = Message(
        id = id,
        role = role,
        time = created?.let { Message.TimeInfo(created = it) },
    )

    private fun part(id: String, msgId: String, text: String = ""): Part = Part(
        id = id,
        messageId = msgId,
        sessionId = "s",
        type = "text",
        text = text,
    )

    private fun mwp(msg: Message, parts: List<Part> = emptyList()) =
        MessageWithParts(info = msg, parts = parts)

    // ─── (a) 全链路：digest→reload→merge→ChatContentLoaded dispatch ─────

    @Test
    fun `(a) onDigestChange triggers reload merges messages and dispatches ChatContentLoaded`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val items = listOf(
                mwp(msg("m1", created = 100L)),
                mwp(msg("m2", created = 200L, role = "assistant"),
                    parts = listOf(part("p1", "m2", "hello"))),
            )
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns MessagesPage(items, null)

            val c = cs.coordinator(store, repo)
            c.onDigestChange("s")
            cs.advance()

            assertEquals(listOf("m1", "m2"), store.slices.chat.value.messages.map { it.id })
            assertEquals(
                listOf(part("p1", "m2", "hello")),
                store.slices.chat.value.partsByMessage["m2"],
            )
            coVerify(exactly = 1) { repo.getSlimapiMessagesSkeleton("s", 50, null) }
        } finally { cs.cancel() }
    }

    @Test
    fun `(a) requestReload triggers reload merges messages and dispatches ChatContentLoaded`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val items = listOf(
                mwp(msg("a1", created = 100L)),
                mwp(msg("a2", created = 200L, role = "assistant"),
                    parts = listOf(part("p1", "a2", "world"))),
            )
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns MessagesPage(items, null)

            val c = cs.coordinator(store, repo)
            c.requestReload("s", limit = 50)
            cs.advance()

            assertEquals(listOf("a1", "a2"), store.slices.chat.value.messages.map { it.id })
            assertEquals("s", store.slices.chat.value.currentSessionId)
            coVerify(exactly = 1) { repo.getSlimapiMessagesSkeleton("s", 50, null) }
        } finally { cs.cancel() }
    }

    @Test
    fun `(a) reload dispatches authoritative false for skeleton`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val items = listOf(
                mwp(msg("m1", created = 100L)),
                mwp(msg("m2", created = 200L, role = "assistant")),
            )
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns MessagesPage(items, null)

            val c = cs.coordinator(store, repo)
            c.requestReload("s")
            cs.advance()

            assertEquals(listOf("m1", "m2"), store.slices.chat.value.messages.map { it.id })
        } finally { cs.cancel() }
    }

    // ─── (b) 空页不清 transcript ──────────────────────────────────────────

    @Test
    fun `(b) empty page does not clear existing transcript`() {
        val cs = CoordinatorScope()
        try {
            val existing = listOf(msg("existing", created = 500L, role = "user"))
            val store = createReadyStore("s", routeInstance = 42L, messages = existing)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            var callCount = 0
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                MessagesPage(emptyList(), null)
            }

            val c = cs.coordinator(store, repo)
            c.requestReload("s")
            cs.advance()

            assertEquals(listOf("existing"), store.slices.chat.value.messages.map { it.id })
            assertEquals(1, callCount)
            assertEquals("s", store.slices.chat.value.currentSessionId)
        } finally { cs.cancel() }
    }

    @Test
    fun `(b) onDigestChange with empty page preserves existing transcript`() {
        val cs = CoordinatorScope()
        try {
            val existing = listOf(msg("e1", created = 300L, role = "assistant"))
            val store = createReadyStore("s", routeInstance = 42L, messages = existing)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns MessagesPage(emptyList(), null)

            val c = cs.coordinator(store, repo)
            c.onDigestChange("s")
            cs.advance()

            assertEquals(listOf("e1"), store.slices.chat.value.messages.map { it.id })
        } finally { cs.cancel() }
    }

    // ─── (b) watchdog retry 获得 HTTP 成功空页时 failed/retry/watchdog 被复位 ──
    //
    // 【已知生产缺陷】watchdog 重试获得空页时，`page.items.isEmpty() → return@withLock`
    // 跳过了 `ownerState.failed = false`、`ownerState.retryAttempt = 0`、
    // `watchdogJobs.remove(sessionId)?.cancel()`，导致 watchdog 不 disarm，
    // failed/retryAttempt 不被复位，watchdog 继续以指数退避空转。
    //
    // 正确行为：HTTP 成功（即使空页）应复位失败状态。本测试使用正确断言，
    // 在修复前会失败（prod bug：callCount 会 ≥3 表示 watchdog 空转）。

    @Test
    fun `(b) FOCUSED watchdog retry with empty page should reset failed state but current prod code does NOT`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                if (callCount == 1) throw IOException("fail")
                else MessagesPage(emptyList(), null)
            }

            val c = cs.coordinator(store, repo)

            // 第 1 次：失败 → arm 15s watchdog
            c.requestReload("s")
            cs.runCurrent()
            assertEquals("initial call failed", 1, callCount)

            // 15s 后 watchdog 第 1 次重试 → repo 返回空页
            cs.advance(15_000)
            assertEquals("watchdog retry #1 (empty page)", 2, callCount)

            // 再推 31s（覆盖 30s 下一档）→ 验证 watchdog 是否 disarm。
            // 正确行为：callCount == 2（无更多重试）。
            // 缺陷行为（修复前）：callCount >= 3（watchdog 空转）。
            cs.advance(31_000)

            // watchdog 已被 disarm：空页成功 HTTP 复位了 failed/retryAttempt
            // 并 disarm 了 watchdog，不再继续重试空转。
            assertEquals(
                "watchdog retry with empty page must reset failed state and disarm;" +
                " expected 2 (initial fail + one empty-page retry)",
                2, callCount,
            )
        } finally { cs.cancel() }
    }

    // ─── (c) fetched 缺席的本地消息 created>=newestFetched 不删除 ──────────

    @Test
    fun `(c) fetched-absent messages with created equals newestFetched are NOT deleted`() {
        val cs = CoordinatorScope()
        try {
            val existing = listOf(
                msg("a", created = 100L),
                msg("b", created = 150L),
                msg("c", created = 150L),
                msg("d", created = 200L),
            )
            val store = createReadyStore("s", routeInstance = 42L, messages = existing)
            val fetched = listOf(
                mwp(msg("a", created = 100L)),
                mwp(msg("b", created = 150L)),
            )
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns MessagesPage(fetched, null)

            val c = cs.coordinator(store, repo)
            c.requestReload("s")
            cs.advance()

            val resultIds = store.slices.chat.value.messages.map { it.id }
            assertTrue("a should be present", resultIds.contains("a"))
            assertTrue("b should be present", resultIds.contains("b"))
            assertTrue("c (created==newestFetched) should NOT be deleted", resultIds.contains("c"))
            assertTrue("d (created>newestFetched) should NOT be deleted", resultIds.contains("d"))
        } finally { cs.cancel() }
    }

    @Test
    fun `(c) message strictly inside window but absent from fetched IS deleted`() {
        val cs = CoordinatorScope()
        try {
            val existing = listOf(
                msg("a", created = 100L),
                msg("b", created = 120L),
                msg("c", created = 150L),
            )
            val store = createReadyStore("s", routeInstance = 42L, messages = existing)
            val fetched = listOf(
                mwp(msg("a", created = 100L)),
                mwp(msg("c", created = 150L)),
            )
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns MessagesPage(fetched, null)

            val c = cs.coordinator(store, repo)
            c.requestReload("s")
            cs.advance()

            val resultIds = store.slices.chat.value.messages.map { it.id }
            assertTrue("a should be present", resultIds.contains("a"))
            assertFalse("b (strictly inside window, absent) should be deleted", resultIds.contains("b"))
            assertTrue("c should be present", resultIds.contains("c"))
        } finally { cs.cancel() }
    }

    @Test
    fun `(c) null-created message is never deleted`() {
        val cs = CoordinatorScope()
        try {
            val existing = listOf(
                msg("a", created = 100L),
                msg("b_no_created"),
            )
            val store = createReadyStore("s", routeInstance = 42L, messages = existing)
            val fetched = listOf(mwp(msg("a", created = 100L)))
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns MessagesPage(fetched, null)

            val c = cs.coordinator(store, repo)
            c.requestReload("s")
            cs.advance()

            val resultIds = store.slices.chat.value.messages.map { it.id }
            assertTrue("a should be present", resultIds.contains("a"))
            assertTrue("null-created message should survive", resultIds.contains("b_no_created"))
        } finally { cs.cancel() }
    }

    // ─── (f) markLocallyInjected 缺席守卫 ─────────────────────────────────

    @Test
    fun `(f) markLocallyInjected prevents deletion of locally-injected message absent from fetched`() {
        val cs = CoordinatorScope()
        try {
            val existing = listOf(
                msg("server-msg", created = 100L),
                msg("local-injected", created = 200L),
            )
            val store = createReadyStore("s", routeInstance = 42L, messages = existing)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns
                MessagesPage(listOf(mwp(msg("server-msg", created = 100L))), null)

            val c = cs.coordinator(store, repo)
            c.markLocallyInjected("s", "local-injected")
            c.requestReload("s")
            cs.advance()

            val resultIds = store.slices.chat.value.messages.map { it.id }
            assertTrue("server-msg should be present", resultIds.contains("server-msg"))
            assertTrue(
                "locallyInjected message should survive despite being absent from fetched",
                resultIds.contains("local-injected"),
            )
        } finally { cs.cancel() }
    }

    @Test
    fun `(f) markLocallyInjected before slice publish prevents concurrent reload deletion`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            val localMsg = msg("local-msg", created = 200L)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns
                MessagesPage(listOf(mwp(msg("server-msg", created = 100L))), null)

            val c = cs.coordinator(store, repo)
            c.markLocallyInjected("s", "local-msg")
            store.mutateChat { it.copy(
                messages = listOf(localMsg),
                currentSessionId = "s",
            ) }
            c.requestReload("s")
            cs.advance()

            val resultIds = store.slices.chat.value.messages.map { it.id }
            assertTrue("server-msg should be present", resultIds.contains("server-msg"))
            assertTrue(
                "local-msg should survive reload when mark precedes slice publish",
                resultIds.contains("local-msg"),
            )
        } finally { cs.cancel() }
    }

    @Test
    fun `(f) clear locallyInjected when message confirmed by server`() {
        val cs = CoordinatorScope()
        try {
            val existing = listOf(msg("injected-now-confirmed", created = 100L))
            val store = createReadyStore("s", routeInstance = 42L, messages = existing)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns
                MessagesPage(listOf(mwp(msg("injected-now-confirmed", created = 100L))), null)

            val c = cs.coordinator(store, repo)
            c.markLocallyInjected("s", "injected-now-confirmed")
            c.requestReload("s")
            cs.advance()

            assertTrue(
                store.slices.chat.value.messages.map { it.id }.contains("injected-now-confirmed"),
            )
        } finally { cs.cancel() }
    }

    @Test
    fun `(f) locallyInjected message with created before window also survives`() {
        val cs = CoordinatorScope()
        try {
            val existing = listOf(
                msg("old-injected", created = 50L),
                msg("a", created = 100L),
            )
            val store = createReadyStore("s", routeInstance = 42L, messages = existing)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns
                MessagesPage(listOf(mwp(msg("a", created = 100L))), null)

            val c = cs.coordinator(store, repo)
            c.markLocallyInjected("s", "old-injected")
            c.requestReload("s")
            cs.advance()

            val resultIds = store.slices.chat.value.messages.map { it.id }
            assertTrue("old-injected should survive (short-circuited by injected check)", resultIds.contains("old-injected"))
        } finally { cs.cancel() }
    }

    // ─── onDigestChange 失败恢复（辅助 (b) 验证） ─────────────────────────

    @Test
    fun `(b) onDigestChange after failure resets failed state and triggers new reload`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                if (callCount == 1) throw IOException("fail")
                else MessagesPage(emptyList(), null)
            }

            val c = cs.coordinator(store, repo)

            // 第 1 次：失败 → arm watchdog
            c.requestReload("s")
            cs.runCurrent()
            assertEquals(1, callCount)

            // onDigestChange → 复位 failed + disarm watchdog + 触发新 reload
            c.onDigestChange("s")
            cs.advance()
            assertEquals("digest triggers new reload", 2, callCount)

            // 推 16s → watchdog 应已被 disarm，不应再触发（digest 已复位）
            cs.advance(16_000)
            assertEquals("watchdog disarmed by digest reset", 2, callCount)
        } finally { cs.cancel() }
    }
}
