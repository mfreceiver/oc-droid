package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    // ─── (a) authoritative=false 直接断言 ──────────────────────────────────
    //
    // 不再仅靠 streamOwned 间接推断，而是捕获 dispatch 的 ChatContentLoaded
    // action 直接验证 authoritative==false。

    @Test
    fun `(a) skeleton reload dispatches ChatContentLoaded with authoritative false directly captured`() {
        val cs = CoordinatorScope()
        try {
            // Create real store with initial state, then spy on it.
            val realStore = SharedStateStore()
            realStore.mutateState {
                it.copy(
                    chat = it.chat.copy(
                        currentSessionId = "s",
                        messages = emptyList(),
                    ),
                    nav = it.nav.copy(lastRoute = "chat/s"),
                    chatRouteInstance = 42L,
                )
            }
            // Seed busy status so streamingFinalized=false → authoritative=false.
            realStore.mutateSessionList {
                it.copy(sessionStatuses = mapOf(
                    "s" to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"),
                ))
            }
            val store = spyk(realStore)

            // Re-route slices through the spy so slices.store.dispatch is intercepted.
            val spiedSlices = SliceFlows(store)
            every { store.slices } returns spiedSlices

            val items = listOf(
                mwp(msg("m1", created = 100L)),
            )
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns MessagesPage(items, null)

            // Capture ChatContentLoaded actions via any() + type-check in answers block.
            // slot<T> with subtype dispatch can fail — this approach is compile-safe.
            val capturedActions = mutableListOf<AppAction.ChatContentLoaded>()
            every { store.dispatch(any()) } answers {
                val action = firstArg<AppAction>()
                if (action is AppAction.ChatContentLoaded) {
                    capturedActions.add(action)
                }
                callOriginal()
            }

            val c = cs.coordinator(store, repo)
            c.requestReload("s")
            cs.advance()

            // Direct assertion: the captured action has authoritative=false.
            assertEquals(
                "exactly one ChatContentLoaded must be dispatched",
                1, capturedActions.size,
            )
            assertFalse(
                "skeleton reload must dispatch ChatContentLoaded with authoritative=false",
                capturedActions.single().authoritative,
            )
            assertEquals(
                "ChatContentLoaded must carry expectedRouteInstance=42L",
                42L, capturedActions.single().expectedRouteInstance,
            )
            assertEquals(
                "ChatContentLoaded must carry sessionId=s",
                "s", capturedActions.single().sessionId,
            )

            // Verify the reducer committed the data.
            assertEquals(listOf("m1"), store.slices.chat.value.messages.map { it.id })
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
    // v2.7-final 修复：空页成功 HTTP 分支在早退前执行 ownerState.failed = false、
    // ownerState.retryAttempt = 0、watchdogJobs.remove(sessionId)?.cancel()，
    // 确保 watchdog 被正确 disarm。

    @Test
    fun `(b) watchdog retry with empty page resets failed state and disarms watchdog`() {
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
    fun `(f) clear locallyInjected marker when message confirmed by server then cleanup on second fetch absence`() {
        val cs = CoordinatorScope()
        try {
            var callCount = 0
            // State has only the injected message (created=100).
            val existing = listOf(msg("injected", created = 100L))
            val store = createReadyStore("s", routeInstance = 42L, messages = existing)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                when (callCount) {
                    1 -> MessagesPage(listOf(
                        // First reload: injected IS in fetched → marker cleared.
                        mwp(msg("injected", created = 100L)),
                        // low and high define a window [50, 200] for the second reload.
                        mwp(msg("low", created = 50L)),
                        mwp(msg("high", created = 200L)),
                    ), null)
                    2 -> MessagesPage(listOf(
                        // Second reload: only low and high (injected ABSENT).
                        // Window is still [50, 200]. Since injected(100) is NOT in
                        // fetched, NOT in locallyInjected (cleared by reload 1),
                        // and strictly inside window (50 < 100 < 200), it IS deleted.
                        mwp(msg("low", created = 50L)),
                        mwp(msg("high", created = 200L)),
                    ), null)
                    else -> MessagesPage(emptyList(), null)
                }
            }

            val c = cs.coordinator(store, repo)
            c.markLocallyInjected("s", "injected")

            // Reload 1: injected(100) confirmed by server → survives, marker cleared.
            c.requestReload("s")
            cs.advance()
            assertEquals(1, callCount)
            assertTrue(
                "confirmed message must survive first reload",
                store.slices.chat.value.messages.map { it.id }.contains("injected"),
            )

            // Reload 2: injected(100) absent from fetched AND marker was cleared
            // by reload 1 → strictly inside window (50 < 100 < 200) → DELETED.
            c.requestReload("s")
            cs.advance()
            assertEquals(2, callCount)
            assertFalse(
                "injected must be deleted on second fetch-absence because marker was cleared",
                store.slices.chat.value.messages.map { it.id }.contains("injected"),
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

    // ═══════════════════════════════════════════════════════════════════════
    // 缺口①②：ABA 同 sid A₁→B(onSessionClosed)→A₂ + cancelAndJoin late-return
    // ═══════════════════════════════════════════════════════════════════════
    //
    // A₁ 的 repository mock 直接 blocking（不使用 withContext/Dispatchers.Default，
    // 无 suspend/dispatcher re-entry 边界），coAnswers 在同线程栈同步阻塞后
    // 直接返回 stale MessagesPage。
    //
    // 流程：
    //   1) A₁ 在 Default worker 进入 coAnswers，capture 其 job，signal entered，
    //      同步阻塞 a1Release.await()（CountDownLatch，非 suspend）。
    //   2) 启动 onSessionClosed：stateMutex → remove entries → cancelAndJoin(A₁)。
    //      cancel() 标记 job cancelled，join() 阻塞（A₁ 线程仍 blocked on latch）。
    //   3) 确定性等待 A₁ job 进入 cancelled state，断言 close 尚未完成。
    //   4) A₁ 仍阻塞时，用新 routeInstance (43L) 启动同 sid A₂。
    //      确定性等待 A₂ 完成，断言 a2-fresh 已提交。
    //   5) Release A₁：coAnswers 在同一 worker 栈直接返回 MessagesPage(a1-stale)。
    //      A₁ 协程恢复→withSessionLock 入口检查 cancelled state→throw CE，
    //      catch(ce) throw ce → finally NonCancellable 进入 stateMutex。
    //      此时 reloadStates[sessionId] 已为空（onSessionClosed 已 remove），
    //      current !== ownerState → finally 的簿记清理被跳过，
    //      stale inFlight/flags 不会误写回 map——这也防止了清理 A₂ state。
    //      不是 stateMutex 的"merge 入口拒绝 stale page 值"（CE 在 merge 前已抛，
    //      stale page 从未进入 merge）。
    //   6) Close 完成（join 等 A₁ 结束），断言 A₂ 未被 A₁ 污染、无 watchdog。
    //
    // 使用真实 CoroutineScope(Dispatchers.Default)，禁止 withContext/delay/
    // suspendCoroutine/Dispatcher re-entry。

    @Test
    fun `ABA same sid with onSessionClosed cancelAndJoin and deterministic late return`() {
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // ── Latch + capture setup ──
            val a1Entered = CountDownLatch(1)
            val a1Release = CountDownLatch(1)
            val a1Returned = AtomicBoolean(false)
            var capturedA1Job: Job? = null
            val callCount = AtomicInteger(0)

            val store = createReadyStore("s", routeInstance = 42L)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount.incrementAndGet()
                when (callCount.get()) {
                    1 -> {
                        // A₁: capture our own Job, signal entered, block directly
                        // on latch — no withContext, no dispatcher swap.
                        capturedA1Job = currentCoroutineContext()[Job]
                        a1Entered.countDown()
                        a1Release.await()
                        val result = MessagesPage(listOf(mwp(msg("a1-stale", created = 300L))), null)
                        a1Returned.set(true)
                        result
                    }
                    2 -> {
                        // A₂: succeed immediately.
                        MessagesPage(listOf(mwp(msg("a2-fresh", created = 300L))), null)
                    }
                    else -> MessagesPage(emptyList(), null)
                }
            }

            val c = SkeletonReloadCoordinator(
                scope = realScope,
                repository = repo,
                slices = store.slices,
                currentServerGroupFp = { "fp1" },
            )

            // ── (1) A₁: start reload → blocks on repo mock ──
            c.requestReload("s")
            assertTrue(
                "A₁ entered mock barrier",
                a1Entered.await(5, TimeUnit.SECONDS),
            )
            assertEquals("A₁ call recorded", 1, callCount.get())

            // ── (2) B: onSessionClosed → cancelAndJoin(A₁) — join blocks ──
            val closeDone = CountDownLatch(1)
            realScope.launch {
                c.onSessionClosed("s")
                closeDone.countDown()
            }

            // ── (3) Deterministically wait for A₁ Job to enter cancelled state ──
            val capturedJob = capturedA1Job
                ?: throw AssertionError("A₁ job must have been captured")
            var deadline = System.currentTimeMillis() + 5000
            while (!capturedJob.isCancelled && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertTrue("A₁ job must be cancelled by onSessionClosed", capturedJob.isCancelled)
            assertFalse(
                "onSessionClosed must be blocked on cancelAndJoin (A₁ still blocked on latch)",
                closeDone.await(0, TimeUnit.MILLISECONDS),
            )

            // ── (4) A₂: new routeInstance while A₁ still blocked ──
            store.mutateState {
                it.copy(chatRouteInstance = 43L)
            }
            c.requestReload("s")

            // Wait until A₂'s fresh data is committed in the StateFlow.
            // Do NOT use callCount as commit latch — callCount increments when the
            // repository coAnswers returns, BEFORE merge/dispatch in the coordinator.
            deadline = System.currentTimeMillis() + 5000
            var a2Messages: List<String> = emptyList()
            while (System.currentTimeMillis() < deadline) {
                a2Messages = store.slices.chat.value.messages.map { it.id }
                if (a2Messages == listOf("a2-fresh")) break
                Thread.sleep(10)
            }
            assertEquals(
                "A₂ must commit its fresh messages while A₁ is still blocked",
                listOf("a2-fresh"), a2Messages,
            )
            assertEquals("A₂ repository call recorded", 2, callCount.get())

            // ── (5) Release A₁ latch → coAnswers returns stale value on same stack ──
            a1Release.countDown()

            // Wait for close to complete (A₁ must finish first for join to unblock).
            assertTrue(
                "onSessionClosed must complete after A₁ finishes (cancelAndJoin waited)",
                closeDone.await(5, TimeUnit.SECONDS),
            )

            // ── (6) Assert: A₁ stale value did NOT pollute A₂ ──
            assertTrue(
                "A₁ mock must have returned its stale value",
                a1Returned.get(),
            )
            val afterA1 = store.slices.chat.value.messages.map { it.id }
            assertEquals(
                "A₁ late return must NOT pollute A₂'s state",
                listOf("a2-fresh"), afterA1,
            )
            assertFalse(
                "a1-stale must NEVER appear in state",
                afterA1.contains("a1-stale"),
            )

            // A₁ catch(ce) rethrows CE (not an Exception), so armWatchdog is NOT called.
            // No third repository call.
            assertEquals(
                "A₁ must NOT have armed watchdog (CE rethrown, armWatchdog not reached);" +
                " no third call expected",
                2, callCount.get(),
            )
        } finally {
            realScope.cancel(CancellationException("test done"))
        }
    }

    // ─── focused watchdog cancellation test ─────────────────────────────
    //
    // 单独验证：onSessionClosed 取消 watchdog 后，watchdog 即使到触发时刻
    // 也不再执行重试（精确推进 15s 后无调用）。

    @Test
    fun `onSessionClosed cancels watchdog and it does not fire after deadline`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                throw IOException("fail always")
            }

            val c = cs.coordinator(store, repo)

            // Initial fail → arm watchdog (15s)
            c.requestReload("s")
            cs.runCurrent()
            assertEquals("initial fail", 1, callCount)

            // Advance to 14.999s → watchdog should NOT fire yet.
            cs.advance(14_999)
            assertEquals("no watchdog before 15s boundary", 1, callCount)

            // ── onSessionClosed: must cancel watchdog ──
            cs.scope.launch { c.onSessionClosed("s") }
            cs.advance()

            // Advance 1ms → watchdog deadline reached (15s)
            cs.advance(1)
            // Watchdog should NOT fire because it was cancelled by onSessionClosed.
            assertEquals(
                "watchdog must NOT fire after onSessionClosed cancelled it",
                1, callCount,
            )

            // Advance further (full 300s window) → still no retry.
            cs.advance(300_000)
            assertEquals("no retry even after 300s (watchdog cancelled)", 1, callCount)
        } finally { cs.cancel() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 缺口③：watchdog 完整阶梯 + 精确边界断言 + 跨多次 arm retryAttempt 持久化
    // ═══════════════════════════════════════════════════════════════════════
    //
    // Watchdog 退避是累进的（15s → 30s → 60s → 300s capped），每次 delay
    // 从本次 retryAttempt 计算，NOT 从起点累计。验证：
    //   (a) 每次失败 catch 都调 armWatchdog，但 active job 使其 no-op
    //       → retryAttempt 不重置
    //   (b) 每档截止前 -1ms 不提前调用
    //   (c) 边界 +1ms 后精确递增
    //   (d) 第二个 300s 封顶周期

    @Test
    fun `(b) watchdog full backoff with precise boundary assertions and retryAttempt persistence`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                throw IOException("fail #$callCount")
            }

            val c = cs.coordinator(store, repo)

            // ── Initial failure → armWatchdog (retryAttempt=0) ──
            c.requestReload("s")
            cs.runCurrent()
            assertEquals("initial fail", 1, callCount)

            // ── #1: 15s window ───────────────────────────────────────────
            // delay(14,999) from arm → no fire
            cs.advance(14_999)
            assertEquals("no retry at 14,999ms", 1, callCount)
            // +1ms = 15,000 → fire #1 (retryAttempt 0→1)
            cs.advance(1)
            assertEquals("15s retry at boundary", 2, callCount)

            // ── #2: 30s window (from this retry, not from start) ────────
            // delay(29,999) from fire #1 time → no fire
            cs.advance(29_999)
            assertEquals("no retry at 29,999ms from fire #1", 2, callCount)
            // +1ms → fire #2 (retryAttempt 1→2)
            cs.advance(1)
            assertEquals("30s retry at boundary", 3, callCount)

            // ── #3: 60s window ──────────────────────────────────────────
            cs.advance(59_999)
            assertEquals("no retry at 59,999ms from fire #2", 3, callCount)
            cs.advance(1)
            assertEquals("60s retry at boundary", 4, callCount)

            // ── #4 onwards: 300s capped window ──────────────────────────
            // First 300s cap
            cs.advance(299_999)
            assertEquals("no retry at 299,999ms from fire #3", 4, callCount)
            cs.advance(1)
            assertEquals("300s retry #1 at boundary", 5, callCount)

            // Second 300s cap (retryAttempt 4→5)
            cs.advance(299_999)
            assertEquals("no retry at second 299,999ms", 5, callCount)
            cs.advance(1)
            assertEquals("300s retry #2 (cap verified)", 6, callCount)

            // Verify retryAttempt persists across multiple armWatchdog calls.
            // Each failure catch calls armWatchdog again, but since the watchdog
            // job is already active (isActive==true), armWatchdog no-ops.
            // This means retryAttempt is NOT reset by the re-arm - it stays
            // persistent in ReloadState, proving the watchdog loop uses the
            // same retryAttempt for all its retries.
        } finally { cs.cancel() }
    }

}
