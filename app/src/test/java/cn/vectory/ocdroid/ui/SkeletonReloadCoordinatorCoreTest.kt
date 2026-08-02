package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
                // L3: live bundle must match the coordinator's captured
                // BundleStamp(1L, "http://a") so the reducer's bundle CAS
                // accepts the reload's ChatContentLoaded dispatch.
                liveBundleGeneration = 1L,
                liveEndpointFp = "http://a",
            )
        }
        return store
    }

    /** 为 coordinator 创建独立 TestScope。调用方应在使用后 cancel() 以销毁 init 块
     *  中启动的无限 collect 协程。advanceOnScope()、advanceOnScopeBy() 辅助函数
     *  操纵该 scope 的调度器。 */
    private class CoordinatorScope {
        val scope: TestScope = TestScope(StandardTestDispatcher())
        val foreground = MutableStateFlow(true)
        val generation = AtomicLong(1L)
        val identity = AtomicReference(
            ConnectionIdentity(epoch = 1L, profileId = "host-A",
                normalizedWorkdir = "/a", endpointFp = "http://a"))
        val bundleStamp = AtomicReference(BundleStamp(1L, "http://a"))
        val nowMs: () -> Long = { scope.testScheduler.currentTime }
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
        foreground = foreground,
        currentTransport = { TransportSnapshot(generation.get(), identity.get()) },
        currentBundleStamp = { bundleStamp.get() },
        monotonicNowMs = nowMs,
    )

    /** Seed a busy session status so [SkeletonReloadCoordinator] applies its
     *  busy rate cap (1 reload / 2s) — required for the T-C1-d throttle tests. */
    private fun seedBusy(store: SharedStateStore, sessionId: String = "s") {
        store.mutateSessionList {
            it.withProjection(mapOf(sessionId to SessionStatus(type = "busy")))
        }
    }

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
                    // L3: live bundle matching the coordinator's BundleStamp
                    // so the bundle CAS accepts the dispatch.
                    liveBundleGeneration = 1L,
                    liveEndpointFp = "http://a",
                )
            }
            // Seed busy status so streamingFinalized=false → authoritative=false.
            realStore.mutateSessionList {
                it.withProjection(mapOf(
                    "s" to cn.vectory.ocdroid.data.model.SessionStatus(type = "busy"),
                ))
            }
            val store = spyk(realStore)

            // Re-route slices through the spy so slices.store.dispatchAndVerify is intercepted.
            val spiedSlices = SliceFlows(store)
            every { store.slices } returns spiedSlices

            val items = listOf(
                mwp(msg("m1", created = 100L)),
            )
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns MessagesPage(items, null)

            // L3: the merge now dispatches via dispatchAndVerify (returns the
            // commit verdict). Capture the ChatContentLoaded action there.
            val capturedActions = mutableListOf<AppAction.ChatContentLoaded>()
            every { store.dispatchAndVerify(any()) } answers {
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
    fun `(L3 R1) content-bearing empty page bounded retry at 2-4-8-16s then stops dirty retained`() {
        // Replaces the old "watchdog retry with empty page disarms watchdog"
        // test. The v2.7 watchdog (15/30/60/300s unbounded) is GONE; the unified
        // scheduler retries a content-bearing empty page on a bounded 2/4/8/16s
        // schedule (4 retries), then stops — dirty is retained (not silently
        // cleared) until a later external signal / content arrives.
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                MessagesPage(emptyList(), null) // always empty
            }

            val c = cs.coordinator(store, repo)
            c.onDigestChange("s") // content-bearing → R1 retry applies
            cs.runCurrent()
            assertEquals("initial content reload", 1, callCount)

            // 1st retry deadline = 2s.
            cs.advance(1_999)
            assertEquals("no retry before 2s boundary", 1, callCount)
            cs.advance(1)
            assertEquals("1st retry at 2s", 2, callCount)

            // 2nd retry deadline = +4s (from this retry).
            cs.advance(3_999)
            assertEquals("no retry before 4s boundary", 2, callCount)
            cs.advance(1)
            assertEquals("2nd retry at 4s", 3, callCount)

            // 3rd retry = +8s.
            cs.advance(7_999)
            assertEquals("no retry before 8s boundary", 3, callCount)
            cs.advance(1)
            assertEquals("3rd retry at 8s", 4, callCount)

            // 4th retry = +16s.
            cs.advance(15_999)
            assertEquals("no retry before 16s boundary", 4, callCount)
            cs.advance(1)
            assertEquals("4th retry at 16s (last)", 5, callCount)

            // Exhausted: no 5th retry even after a long advance.
            cs.advance(60_000)
            assertEquals("no 5th retry — bounded", 5, callCount)

            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())
            assertNotNull(snap)
            assertTrue("dirty must remain true after bounded retries exhausted", snap!!.dirty)
            assertNull("marker must NOT advance on empty page", snap.marker)
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
    fun `(b) onDigestChange after failure resets retry budget but respects the 2s busy cap`() {
        // Updated for L3: the v2.7 immediate-reload-on-digest-after-failure is
        // gone. A new content-bearing digest RESETS the retry budget and marks
        // dirty, but does NOT bypass the existing in-flight retry timer's 2s
        // deadline (anti-starvation: the timer keeps its earliest deadline).
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                if (callCount == 1) throw IOException("fail")
                else MessagesPage(listOf(mwp(msg("after-digest", created = 100L))), null)
            }

            val c = cs.coordinator(store, repo)

            // call 1: failure → retry timer scheduled at 2s.
            c.requestReload("s")
            cs.runCurrent()
            assertEquals(1, callCount)

            // onDigestChange resets retry budget + marks dirty, but the existing
            // 2s retry timer is retained (not re-armed/bypassed).
            c.onDigestChange("s")
            cs.runCurrent()
            assertEquals("digest must NOT bypass the in-flight 2s timer", 1, callCount)

            cs.advance(1_999)
            assertEquals("no reload before 2s deadline", 1, callCount)
            cs.advance(1) // t=2000 → timer fires → digest's reload (content)
            assertEquals("digest reload fires at the 2s deadline", 2, callCount)

            // Content obtained → done (no further retries).
            cs.advance(16_000)
            assertEquals("no further reload after content committed", 2, callCount)
            assertEquals(
                listOf("after-digest"),
                store.slices.chat.value.messages.map { it.id },
            )
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

            val abaForeground = MutableStateFlow(true)
            val abaIdentity = ConnectionIdentity(
                epoch = 1L, profileId = "host-A",
                normalizedWorkdir = "/a", endpointFp = "http://a")
            val c = SkeletonReloadCoordinator(
                scope = realScope,
                repository = repo,
                slices = store.slices,
                foreground = abaForeground,
                currentTransport = { TransportSnapshot(1L, abaIdentity) },
                currentBundleStamp = { BundleStamp(1L, "http://a") },
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
                Thread.sleep(25)
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
                Thread.sleep(30)
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

    // ─── onSessionClosed cancels the trailing/retry timer ───────────────
    //
    // The v2.7 watchdog is gone; the unified scheduler's retry timer (2s for a
    // failure) must be cancelled by onSessionClosed so no post-close reload fires.

    @Test
    fun `onSessionClosed cancels retry timer and it does not fire after deadline`() {
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

            // Initial fail → retry scheduled at 2s.
            c.requestReload("s")
            cs.runCurrent()
            assertEquals("initial fail", 1, callCount)

            // Advance to 1.999s → retry should NOT fire yet.
            cs.advance(1_999)
            assertEquals("no retry before 2s boundary", 1, callCount)

            // ── onSessionClosed: must cancel the retry timer + detach state ──
            cs.scope.launch { c.onSessionClosed("s") }
            cs.advance()

            // Advance well past the 2s deadline → no retry (timer cancelled).
            cs.advance(60_000)
            assertEquals(
                "retry must NOT fire after onSessionClosed cancelled it",
                1, callCount,
            )
        } finally { cs.cancel() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // L3: bounded failure-retry backoff (replaces the old unbounded watchdog
    // ladder 15/30/60/300s). A network failure retries at 2/4/8/16s (4 retries),
    // then stops — dirty is retained for a later external signal.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `(L3) network failure bounded retry at 2-4-8-16s then stops`() {
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

            // ── Initial failure → retry scheduled at 2s ──
            c.requestReload("s")
            cs.runCurrent()
            assertEquals("initial fail", 1, callCount)

            // #1: 2s window
            cs.advance(1_999)
            assertEquals("no retry at 1,999ms", 1, callCount)
            cs.advance(1)
            assertEquals("2s retry at boundary", 2, callCount)

            // #2: +4s window (from this retry)
            cs.advance(3_999)
            assertEquals("no retry at 3,999ms from retry #1", 2, callCount)
            cs.advance(1)
            assertEquals("4s retry at boundary", 3, callCount)

            // #3: +8s window
            cs.advance(7_999)
            assertEquals("no retry at 7,999ms from retry #2", 3, callCount)
            cs.advance(1)
            assertEquals("8s retry at boundary", 4, callCount)

            // #4: +16s window (last)
            cs.advance(15_999)
            assertEquals("no retry at 15,999ms from retry #3", 4, callCount)
            cs.advance(1)
            assertEquals("16s retry at boundary (last)", 5, callCount)

            // Exhausted: no further retry.
            cs.advance(120_000)
            assertEquals("no retry after exhaustion — bounded", 5, callCount)

            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())
            assertNotNull(snap)
            assertTrue("dirty retained after failure retries exhausted", snap!!.dirty)
        } finally { cs.cancel() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // L3 §6 test matrix: T-C1-a..f, T-C2-a, T-R1
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `(T-C1-a) digest during inFlight coalesces into dirty - no second concurrent request`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            seedBusy(store)
            val firstGate = CompletableDeferred<Unit>()
            var callCount = 0
            var concurrent = 0
            var maxConcurrent = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                concurrent++; if (concurrent > maxConcurrent) maxConcurrent = concurrent
                try {
                    if (callCount == 1) firstGate.await()
                    MessagesPage(listOf(mwp(msg("m$callCount", created = 100L * callCount))), null)
                } finally { concurrent-- }
            }
            val c = cs.coordinator(store, repo)
            c.submit("s", Tuple(100L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals("first reload inFlight", 1, callCount)

            // Second digest while inFlight → must coalesce, NOT launch a 2nd request.
            c.submit("s", Tuple(200L, "m2"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals("no second concurrent request (coalesced into dirty)", 1, callCount)

            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())
            assertNotNull(snap)
            assertTrue("dirty retained for 2nd digest", snap!!.dirty)
            assertTrue("still inFlight", snap.inFlight)

            // Release first → commit; trailing reload for 2nd digest respects the
            // 2s busy rate cap.
            firstGate.complete(Unit)
            cs.advance(1_999)
            assertEquals("trailing reload waits for 2s busy cap", 1, callCount)
            cs.advance(1_000)
            assertEquals("trailing reload fires after 2s", 2, callCount)
            assertEquals("max concurrent must be 1", 1, maxConcurrent)
        } finally { cs.cancel() }
    }

    @Test
    fun `(T-C1-b) FORCE supersedes queued DIGEST - limit 200 not downgraded`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            seedBusy(store)
            val limits = mutableListOf<Int>()
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                limits += secondArg<Int>()
                MessagesPage(listOf(mwp(msg("m${limits.size}", created = 100L * limits.size))), null)
            }
            val c = cs.coordinator(store, repo)

            // First digest launches immediately at t=0 (limit 50); busy cap → nextAllowedAt=2s.
            c.submit("s", Tuple(1L, "a"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals(listOf(50), limits)

            // Queue a DIGEST, then a FORCE, then another DIGEST — all before the 2s cap.
            c.submit("s", Tuple(2L, "b"), Priority.DIGEST, ReloadReason.DIGEST)
            c.submit("s", null, Priority.FORCE_RECONCILE, ReloadReason.REQUEST_RELOAD)
            c.submit("s", Tuple(3L, "c"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.advance(1_999)
            assertEquals("no launch before 2s cap", listOf(50), limits)

            cs.advance(1_000) // t=2000 → trailing launch
            // FORCE superseded the queued DIGEST → limit 200 (not downgraded to 50 by the
            // later DIGEST).
            assertEquals("limits must be [50, 200]", listOf(50, 200), limits)
        } finally { cs.cancel() }
    }

    @Test
    fun `(T-C1-c) background cancels timer and in-flight completion schedules no trailing`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            seedBusy(store)
            val gate = CompletableDeferred<Unit>()
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                gate.await() // hold the in-flight
                MessagesPage(listOf(mwp(msg("m1", created = 100L))), null)
            }
            val c = cs.coordinator(store, repo)
            c.submit("s", Tuple(1L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals(1, callCount)
            // A 2nd digest during inFlight marks dirty (pending content work).
            c.submit("s", Tuple(2L, "m2"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()

            // Enter background → cancelForBackground cancels trailing timers.
            cs.foreground.value = false
            cs.runCurrent()

            // Release the in-flight → it completes while background.
            gate.complete(Unit)
            cs.advance(60_000)
            // Background: in-flight completion schedules NO trailing reload.
            assertEquals("no trailing reload while background", 1, callCount)

            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())
            assertNotNull(snap)
            assertFalse("no active timer in background", snap!!.timerActive)
            assertTrue("dirty retained in background", snap.dirty)

            // Foreground resumes → trailing reload fires.
            cs.foreground.value = true
            cs.runCurrent()
            cs.advance(2_000)
            assertEquals("foreground resumes trailing reload", 2, callCount)
        } finally { cs.cancel() }
    }

    @Test
    fun `(T-C1-d) dense 250ms digests rate-limited to at most 30-per-min regardless of RTT`() {
        listOf(100L, 500L, 2_000L).forEach { rtt ->
            val cs = CoordinatorScope()
            try {
                val store = createReadyStore("s", routeInstance = 42L)
                seedBusy(store)
                val launchTimes = mutableListOf<Long>()
                var concurrent = 0
                var maxConcurrent = 0
                val repo = mockk<OpenCodeRepository>(relaxed = false)
                coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                    launchTimes += cs.nowMs()
                    concurrent++; if (concurrent > maxConcurrent) maxConcurrent = concurrent
                    try {
                        delay(rtt)
                        MessagesPage(listOf(mwp(msg("m", created = launchTimes.last()))), null)
                    } finally { concurrent-- }
                }
                val c = cs.coordinator(store, repo)
                c.submit("s", Tuple(0L, "m0"), Priority.DIGEST, ReloadReason.DIGEST)
                cs.runCurrent()
                var i = 1
                while (cs.nowMs() < 60_000) {
                    cs.advance(250)
                    c.submit("s", Tuple(cs.nowMs(), "m$i"), Priority.DIGEST, ReloadReason.DIGEST)
                    cs.runCurrent()
                    i++
                }
                cs.advance(65_000) // let the trailing settle
                val inWindow = launchTimes.filter { it < 60_000 }
                assertTrue(
                    "RTT=$rtt: launches in 60s window = ${inWindow.size}, must be <= 30",
                    inWindow.size <= 30,
                )
                assertTrue(
                    "RTT=$rtt: consecutive launches must be >= 2s apart",
                    inWindow.zipWithNext().all { (a, b) -> b - a >= 2_000L },
                )
                assertEquals("RTT=$rtt: max concurrent must be 1", 1, maxConcurrent)
            } finally { cs.cancel() }
        }
    }

    @Test
    fun `(T-C1-e) generation isolation - host-A to host-B same sid, old completion cannot mutate new state`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val aGate = CompletableDeferred<Unit>()
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                when (callCount) {
                    1 -> { aGate.await(); MessagesPage(listOf(mwp(msg("a-stale", created = 300L))), null) }
                    else -> MessagesPage(listOf(mwp(msg("b-fresh", created = 300L))), null)
                }
            }
            val c = cs.coordinator(store, repo)

            // host-A (gen 1): submit → inFlight, blocks on aGate.
            c.submit("s", Tuple(300L, "a"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals(1, callCount)

            // host switch: gen 1 → gen 2, host-A → host-B, bundle updated.
            cs.generation.set(2L)
            cs.identity.set(ConnectionIdentity(epoch = 2L, profileId = "host-B",
                normalizedWorkdir = "/b", endpointFp = "http://b"))
            cs.bundleStamp.set(BundleStamp(2L, "http://b"))
            store.mutateState { it.copy(liveBundleGeneration = 2L, liveEndpointFp = "http://b") }
            c.detachGeneration(2L)
            cs.runCurrent()

            // host-B submit (same sid) → new gen-2 slot → launches B.
            c.submit("s", Tuple(300L, "b"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals("B launched", 2, callCount)

            // Release A → A completes with a-stale, but its completion is fenced
            // (gen-1 slot detached; stillOwnsLocked fails).
            aGate.complete(Unit)
            cs.advance()
            assertEquals("A completion must not trigger a 3rd reload", 2, callCount)

            val msgs = store.slices.chat.value.messages.map { it.id }
            assertEquals("only b-fresh committed", listOf("b-fresh"), msgs)
            assertFalse("a-stale must NEVER appear", msgs.contains("a-stale"))

            assertNull("gen-1 state detached", c.schedulerSnapshotForTest("s", 1L))
            val snap2 = c.schedulerSnapshotForTest("s", 2L)
            assertNotNull(snap2)
            assertEquals("gen-2 marker == b's tuple", Tuple(300L, "b"), snap2!!.marker)
        } finally { cs.cancel() }
    }

    @Test
    fun `(T-C1-f) onSessionClosed cancels and joins the timer - no leak`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            seedBusy(store)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                MessagesPage(listOf(mwp(msg("m$callCount", created = 100L))), null)
            }
            val c = cs.coordinator(store, repo)
            c.submit("s", Tuple(1L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals(1, callCount)
            // 2nd digest → trailing timer at the 2s busy cap.
            c.submit("s", Tuple(2L, "m2"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())
            assertNotNull(snap)
            assertTrue("trailing timer active before close", snap!!.timerActive)

            val closeJob = cs.scope.launch { c.onSessionClosed("s") }
            cs.advance()
            assertTrue("onSessionClosed completed (cancel+join)", closeJob.isCompleted)

            assertNull("state detached after close", c.schedulerSnapshotForTest("s", cs.generation.get()))
            cs.advance(60_000)
            assertEquals("no reload after close (timer cancelled, no leak)", 1, callCount)
        } finally { cs.cancel() }
    }

    // ─── T-C2-a NO-ADVANCE marker cases (focused) ────────────────────────

    @Test
    fun `(T-C2-a empty) complete tuple + empty page does NOT advance marker`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns MessagesPage(emptyList(), null)
            val c = cs.coordinator(store, repo)
            c.submit("s", Tuple(100L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertNull("marker must NOT advance on empty page", snap.marker)
            assertTrue("dirty retained (R1)", snap.dirty)
        } finally { cs.cancel() }
    }

    @Test
    fun `(T-C2-a malformed) incomplete tuple commits content but does NOT advance marker`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns
                MessagesPage(listOf(mwp(msg("m1", created = 100L))), null)
            val c = cs.coordinator(store, repo)
            // Tuple(123, null) → isComplete == false (no messageId).
            c.submit("s", Tuple(123L, null), Priority.DIGEST, ReloadReason.DIGEST_MALFORMED)
            cs.runCurrent()
            assertEquals("content committed", listOf("m1"), store.slices.chat.value.messages.map { it.id })
            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertNull("marker must NOT advance when request tuple is incomplete", snap.marker)
        } finally { cs.cancel() }
    }

    @Test
    fun `(T-C2-a route-CAS) route mismatch rejects commit - marker null dirty retained`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val gate = CompletableDeferred<Unit>()
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                gate.await(); MessagesPage(listOf(mwp(msg("m1", created = 100L))), null)
            }
            val c = cs.coordinator(store, repo)
            c.submit("s", Tuple(100L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            // Route advances while the HTTP is in flight → reducer CAS rejects.
            store.mutateState { it.copy(chatRouteInstance = 99L) }
            gate.complete(Unit)
            // runCurrent (not advance): run the first commit (rejected) but NOT
            // the 2s retry, which would re-capture the new route and succeed.
            cs.runCurrent()
            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertNull("marker must NOT advance on route-CAS reject", snap.marker)
            assertTrue("dirty retained on uncommitted", snap.dirty)
        } finally { cs.cancel() }
    }

    @Test
    fun `(T-C2-a bundle-CAS) bundle mismatch rejects commit - marker null dirty retained`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val gate = CompletableDeferred<Unit>()
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                gate.await(); MessagesPage(listOf(mwp(msg("m1", created = 100L))), null)
            }
            val c = cs.coordinator(store, repo)
            c.submit("s", Tuple(100L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            // Live bundle rotates while HTTP in flight → bundle-aware reducer rejects.
            store.mutateState { it.copy(liveBundleGeneration = 99L, liveEndpointFp = "http://other") }
            gate.complete(Unit)
            cs.advance()
            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertNull("marker must NOT advance on bundle-CAS reject", snap.marker)
            assertTrue("dirty retained on uncommitted", snap.dirty)
        } finally { cs.cancel() }
    }

    @Test
    fun `(T-C2-a background) background-suppressed reload sends no HTTP and advances no marker`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++; MessagesPage(emptyList(), null)
            }
            val c = cs.coordinator(store, repo)
            cs.foreground.value = false
            cs.runCurrent()
            c.submit("s", Tuple(100L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.advance(5_000)
            assertEquals("no HTTP while background", 0, callCount)
            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertNull("marker null (no commit)", snap.marker)
            assertTrue("dirty retained while background", snap.dirty)
            assertFalse("no active timer while background", snap.timerActive)
        } finally { cs.cancel() }
    }

    @Test
    fun `(T-C2-a uncommitted) HTTP 200 but merge uncommitted - marker null dirty retained`() {
        val cs = CoordinatorScope()
        try {
            val realStore = createReadyStore("s", routeInstance = 42L)
            val store = spyk(realStore)
            val spiedSlices = SliceFlows(store)
            every { store.slices } returns spiedSlices
            // Force dispatchAndVerify to report "uncommitted" (reducer rejected).
            every { store.dispatchAndVerify(any()) } returns false
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns
                MessagesPage(listOf(mwp(msg("m1", created = 100L))), null)
            val c = cs.coordinator(store, repo)
            c.submit("s", Tuple(100L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertNull("marker must NOT advance when merge uncommitted", snap.marker)
            assertTrue("dirty retained for bounded retry", snap.dirty)
        } finally { cs.cancel() }
    }

    // ─── T-R1 empty-page zero-loss ───────────────────────────────────────

    @Test
    fun `(T-R1) unique content digest to empty page bounded-retries until content is visible`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var calls = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                calls++
                if (calls == 1) MessagesPage(emptyList(), null)
                else MessagesPage(listOf(mwp(msg("eventual", created = 100L))), null)
            }
            val c = cs.coordinator(store, repo)
            // Sole content-bearing digest.
            c.submit("s", Tuple(100L, "eventual"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals("first reload returned empty", 1, calls)
            assertEquals("transcript unchanged after empty", emptyList<String>(),
                store.slices.chat.value.messages.map { it.id })
            val snap0 = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertNull("marker null on empty", snap0.marker)
            assertTrue("dirty retained after empty", snap0.dirty)

            // No further digest → bounded retry at 2s fetches the content.
            cs.advance(1_999)
            assertEquals("no retry before 2s", 1, calls)
            cs.advance(1)
            cs.runCurrent()
            assertEquals("retry fetches content", 2, calls)
            assertEquals("content eventually visible", listOf("eventual"),
                store.slices.chat.value.messages.map { it.id })
            val snap1 = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertEquals("marker advances to the digest tuple", Tuple(100L, "eventual"), snap1.marker)
            assertFalse("dirty cleared after content", snap1.dirty)
        } finally { cs.cancel() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // rev-gpt blocker regression tests (L3 reliability)
    // ═══════════════════════════════════════════════════════════════════════

    // ─── Blocker #2a: null identity prevents HTTP, retains dirty ──────────

    @Test
    fun `(blocker-2a) null identity prevents HTTP launch and retains dirty`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                MessagesPage(listOf(mwp(msg("m1", created = 100L))), null)
            }
            // Set identity to null → launchReloadLocked must NOT fire HTTP.
            cs.identity.set(null)
            val c = cs.coordinator(store, repo)
            c.submit("s", Tuple(100L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.advance(5_000)
            assertEquals("no HTTP with null identity", 0, callCount)
            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("dirty retained with null identity", snap.dirty)
            assertFalse("not inFlight with null identity", snap.inFlight)
        } finally { cs.cancel() }
    }

    // ─── Blocker #2b: null bundle prevents HTTP, retains dirty ──────────

    @Test
    fun `(blocker-2b) null bundle prevents HTTP launch and retains dirty`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                MessagesPage(listOf(mwp(msg("m1", created = 100L))), null)
            }
            // Set bundle to null → launchReloadLocked must NOT fire HTTP.
            cs.bundleStamp.set(null)
            val c = cs.coordinator(store, repo)
            c.submit("s", Tuple(100L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.advance(5_000)
            assertEquals("no HTTP with null bundle", 0, callCount)
            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("dirty retained with null bundle", snap.dirty)
            assertFalse("not inFlight with null bundle", snap.inFlight)
        } finally { cs.cancel() }
    }

    // ─── Blocker #3a: FORCE failure retries with limit=200 (not 50) ─────

    @Test
    fun `(blocker-3a) FORCE failure retries with limit 200 not downgraded to 50`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val limits = mutableListOf<Int>()
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                limits += secondArg<Int>()
                throw IOException("FORCE fail #${limits.size}")
            }
            val c = cs.coordinator(store, repo)
            // Submit FORCE → fails at t=0.
            c.requestReload("s", limit = 200)
            cs.runCurrent()
            assertEquals("initial FORCE attempt", listOf(200), limits)

            // 1st retry at 2s must be limit=200 (not 50).
            cs.advance(1_999)
            assertEquals("no retry before 2s", 1, limits.size)
            cs.advance(1)
            assertEquals("1st retry at 2s must be limit=200", listOf(200, 200), limits)

            // 2nd retry at +4s = limit=200.
            cs.advance(3_999)
            assertEquals("no retry before 4s boundary", 2, limits.size)
            cs.advance(1)
            assertEquals("2nd retry at 4s limit=200", listOf(200, 200, 200), limits)

            // All retries must be limit=200 — verify all entries.
            val all200 = limits.all { it == 200 }
            assertTrue("every FORCE retry must use limit=200, got $limits", all200)
        } finally { cs.cancel() }
    }

    // ─── Blocker #3b: background guard reject preserves demand on foreground restore ──
    //
    // Submit in background → preHttpGuard never runs (scheduleTrailingLocked sees
    // !foreground and returns before launchReloadLocked). When foreground restores,
    // nudge retries with PRESERVED FORCE limit=200.

    @Test
    fun `(blocker-3b-v2) background guard reject preserves FORCE limit 200 on foreground restore`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val limits = mutableListOf<Int>()
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                limits += secondArg<Int>()
                MessagesPage(listOf(mwp(msg("m${limits.size}", created = 100L))), null)
            }
            val c = cs.coordinator(store, repo)

            // Submit in background → must NOT fire HTTP (scheduleTrailingLocked
            // returns due to !foreground.value before launchReloadLocked).
            cs.foreground.value = false
            cs.runCurrent()
            c.submit("s", Tuple(100L, "m1"), Priority.FORCE_RECONCILE, ReloadReason.REQUEST_RELOAD)
            cs.advance(10_000)
            assertEquals("no HTTP while background", 0, limits.size)
            var snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("dirty retained in background", snap.dirty)
            assertFalse("no active timer in background", snap.timerActive)
            assertEquals("priority preserved", Priority.FORCE_RECONCILE, snap.priority)

            // Foreground restored → nudge → retry with FORCE limit=200.
            cs.foreground.value = true
            cs.runCurrent()
            cs.advance(2_000)
            assertEquals("foreground triggers retry with limit=200",
                listOf(200), limits)
        } finally { cs.cancel() }
    }

    // ─── Blocker #4a: onSessionClosed (detached cancellation) is no-op ────
    //
    // An in-flight reload cancelled via onSessionClosed gets Detached outcome:
    // the state is already removed, so onReloadComplete(Cancelled) → Detached
    // → no mutation of the (now-new) slot. Uses real dispatcher + CountDownLatch
    // for deterministic cancellation timing (same pattern as the existing ABA
    // test at line 686).
    //
    // Determinism note (blocker-4a fix): the reload's repository mock blocks on
    // a [blocked] CountDownLatch — a NON-cooperative suspend (it does not observe
    // coroutine cancellation). [onSessionClosed] removes the state slot under
    // stateLock and ONLY THEN calls cancelAndJoin. So under Dispatchers.Default
    // thread starvation the reload coroutine could resume from the released
    // latch and run its commit critical section before [onSessionClosed] is even
    // dispatched — at which point no cancellation has been requested yet, so no
    // in-coroutine cancellation check (ensureActive/isActive/epoch token) could
    // reject the stale commit. To make the outcome DETERMINISTIC, this test
    // awaits onSessionClosed's slot removal (observable via
    // [schedulerSnapshotForTest] → null) BEFORE releasing the blocking mock.
    // That establishes a happens-before edge: slot-removed → blocked.countDown()
    // → reload resumes → stillOwnsLocked fails → Detached → no commit, regardless
    // of dispatcher thread availability. (The SUT's runReload #4a ensureActive()
    // fence independently hardens the latent gap where a cancel requested DURING
    // a cooperative IO could otherwise slip past the uncontended session Mutex —
    // a real B1 fix, orthogonal to this test's B2 ordering requirement.)

    @Test
    fun `(blocker-4a) onSessionClosed detached cancellation is no-op - no stale mutation`() {
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = CountDownLatch(1)
            val blocked = CountDownLatch(1)
            val store = createReadyStore("s", routeInstance = 42L)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                entered.countDown()
                blocked.await()
                MessagesPage(listOf(mwp(msg("cancelled-m1", created = 100L))), null)
            }
            val identity = AtomicReference(ConnectionIdentity(
                epoch = 1L, profileId = "host-A",
                normalizedWorkdir = "/a", endpointFp = "http://a"))
            val c = SkeletonReloadCoordinator(
                scope = realScope,
                repository = repo,
                slices = store.slices,
                foreground = MutableStateFlow(true),
                currentTransport = { TransportSnapshot(1L, identity.get()) },
                currentBundleStamp = { BundleStamp(1L, "http://a") },
            )

            // Submit → blocks on repo mock.
            c.submit("s", Tuple(100L, "cancelled-m1"), Priority.FORCE_RECONCILE, ReloadReason.REQUEST_RELOAD)
            assertTrue("entered mock barrier", entered.await(5, TimeUnit.SECONDS))

            // onSessionClosed: detach (remove slot under stateLock) + cancelAndJoin
            // (outside stateLock). The slot removal happens-before the
            // cancelAndJoin, so we FIRST observe the detach (slot gone) before
            // releasing the reload's blocking mock — this is what makes the
            // outcome deterministic (see method kdoc).
            val closeDone = CountDownLatch(1)
            realScope.launch {
                c.onSessionClosed("s")
                closeDone.countDown()
            }
            // Poll for the slot removal (onSessionClosed's synchronized block)
            // BEFORE releasing the reload. Bounded by the close coroutine
            // completing; [onSessionClosed] waits in cancelAndJoin for the
            // in-flight job, which is parked on [blocked.await()] until we
            // release it below — so the slot removal is observed promptly while
            // the cancelAndJoin join is still pending.
            val detachedDeadline = System.nanoTime() + 5_000_000_000L
            while (c.schedulerSnapshotForTest("s", 1L) != null) {
                assertTrue(
                    "onSessionClosed did not remove the state slot within 5s",
                    System.nanoTime() < detachedDeadline,
                )
            }
            // Slot is gone → release the reload. Its commit's stillOwnsLocked now
            // deterministically fails → Detached → no mutation.
            blocked.countDown()
            assertTrue("onSessionClosed completed within 10s",
                closeDone.await(10, TimeUnit.SECONDS))

            // State is gone; no stale mutation can occur.
            assertNull("state removed by onSessionClosed",
                c.schedulerSnapshotForTest("s", 1L))
            assertEquals("stale content must NOT appear in store",
                emptyList<String>(), store.slices.chat.value.messages.map { it.id })
        } finally { realScope.cancel() }
    }

    // ─── Blocker #1: lazy job body does NOT execute under stateLock ──────
    //
    // Use the production coordinator's stateLockHeldForTest() seam and a
    // spy in the repository/preHttpGuard to prove the lock is NOT held when
    // the job body begins.

    @Test
    fun `(blocker-1) lazy job body does not execute under stateLock - proved via preHttpGuard seam`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val lockChecks = mutableListOf<String>()
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            val c = cs.coordinator(store, repo)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                // At the moment the repository call executes (inside the LAZY job
                // body, after startJobs() outside stateLock started the coroutine),
                // stateLock must NOT be held by this thread.
                if (c.stateLockHeldForTest()) {
                    lockChecks.add("repository-enter: stateLock HELD (BUG)")
                } else {
                    lockChecks.add("repository-enter: stateLock NOT held (OK)")
                }
                MessagesPage(listOf(mwp(msg("m1", created = 100L))), null)
            }

            // Submit: creates LAZY job inside stateLock, adds to toStart,
            // then startJobs(toStart) OUTSIDE stateLock. The LAZY body starts
            // on the TestDispatcher (not inline), so stateLock is NOT held.
            c.submit("s", Tuple(100L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.advance()

            // Verify the lock was NOT held during job body execution.
            assertFalse(
                "stateLock must NOT be held when repository seam executes",
                lockChecks.any { it.contains("HELD") },
            )
            assertTrue(
                "repository seam must have been reached",
                lockChecks.any { it.contains("NOT held") },
            )
            assertEquals("reload completed normally", listOf("m1"),
                store.slices.chat.value.messages.map { it.id })
        } finally { cs.cancel() }
    }

    // ─── Blocker #9: rejected commit does not clear locallyInjected ─────────
    //
    // First 5 dispatchAndVerify calls return false (initial + 4 bounded retries,
    // all exhausted). A new external submit then succeeds — locallyInjected
    // must survive the failed attempts (not cleared before a successful commit).

    @Test
    fun `(blocker-9) rejected commit preserves locallyInjected - second commit succeeds`() {
        val cs = CoordinatorScope()
        try {
            val realStore = createReadyStore("s", routeInstance = 42L)
            val store = spyk(realStore)
            val spiedSlices = SliceFlows(store)
            every { store.slices } returns spiedSlices

            val dispatchCallCount = AtomicInteger(0)
            every { store.dispatchAndVerify(any()) } answers {
                if (dispatchCallCount.incrementAndGet() <= 5) {
                    false // first 5 calls fail (covering initial + all retries)
                } else {
                    callOriginal()
                }
            }

            val existing = listOf(msg("local-injected", created = 100L))
            store.mutateChat { it.copy(messages = existing, currentSessionId = "s") }

            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns
                MessagesPage(listOf(mwp(msg("server-msg", created = 200L))), null)

            val c = cs.coordinator(store, repo)
            c.markLocallyInjected("s", "local-injected")

            // Submit → launch → HTTP → merge → dispatchAndVerify returns false → Uncommitted
            // → restoreDirty + boundedRetry (2s/4s/8s/16s = 4 retries, all fail).
            // After 5 failed attempts (1 initial + 4 retries), retries exhausted.
            c.submit("s", Tuple(200L, "server-msg"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.advance(60_000)

            var snap = c.schedulerSnapshotForTest("s", cs.generation.get())
            assertNotNull(snap)
            assertTrue("dirty retained after all retries exhausted", snap!!.dirty)

            // Change the mock so the 6th dispatchAndVerify on a manual nudge succeeds.
            every { store.dispatchAndVerify(any()) } answers {
                callOriginal()
            }
            // Nudge the state (set dirty + foreground = new nudge).
            // Since boundedRetriesExhausted=true, we need a new external signal.
            c.submit("s", Tuple(200L, "server-msg"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.advance(2_000)

            val after = store.slices.chat.value.messages.map { it.id }
            assertTrue(
                "local-injected must survive rejected commit",
                after.contains("local-injected"),
            )
            assertTrue(
                "server-msg must be present after successful commit",
                after.contains("server-msg"),
            )
        } finally { cs.cancel() }
    }

    // ─── Blocker #10: route switch cancels timer but retains dirty ─────────

    @Test
    fun `(blocker-10) route switch cancels trailing timer but retains dirty for nudge on reopen`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            seedBusy(store)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                MessagesPage(listOf(mwp(msg("m$callCount", created = 100L))), null)
            }
            val c = cs.coordinator(store, repo)

            // Submit digest → launch fired (busy → nextAllowedAt = 2s).
            c.submit("s", Tuple(1L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals(1, callCount)

            // 2nd digest during inFlight → dirty=true, trailing timer scheduled.
            c.submit("s", Tuple(2L, "m2"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            var snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("timer active for trailing reload", snap.timerActive)
            assertTrue("dirty retained for 2nd digest", snap.dirty)
            assertFalse("no second in-flight", snap.inFlight)

            // Route switch: change the current session (simulate user navigating
            // away from "s" to a different session). The init-block collector
            // reacts to slices.chat.value.currentSessionId change.
            store.mutateState {
                it.copy(chat = it.chat.copy(currentSessionId = "other", messages = it.chat.messages))
            }
            cs.runCurrent()
            cs.advance(5_000)

            // Assert: timer cancelled, dirty retained, no new call.
            snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertFalse("timer cancelled after route switch", snap.timerActive)
            assertTrue("dirty retained after route switch", snap.dirty)
            assertEquals("no trailing reload fired for switched-away session",
                1, callCount)

            // Navigate back to "s" → nudge schedules trailing reload.
            store.mutateState {
                it.copy(chat = it.chat.copy(currentSessionId = "s", messages = it.chat.messages))
            }
            cs.runCurrent()
            cs.advance(2_000)
            assertEquals("nudge on reopen triggers trailing reload", 2, callCount)
        } finally { cs.cancel() }
    }

    // ─── Blocker #5b: real deletion calls onSessionClosed ─────────────────

    @Test
    fun `(blocker-5b) onSessionClosed via coordinator detaches state and cancels timer`() {
        // Tests that SkeletonReloadCoordinator.onSessionClosed() itself
        // correctly detaches state and cancels jobs. The SSC integration
        // (handleSessionDigest deleted/archived → scope.launch { skel.onSessionClosed(sid) })
        // is an async fire-and-forget — the coordinator-level behavior is
        // what we can deterministically verify.
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            seedBusy(store)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                MessagesPage(listOf(mwp(msg("m$callCount", created = 100L))), null)
            }
            val c = cs.coordinator(store, repo)
            // Launch + enqueue trailing.
            c.submit("s", Tuple(1L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals(1, callCount)
            c.submit("s", Tuple(2L, "m2"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            var snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("trailing timer active before close", snap.timerActive)

            // onSessionClosed → detach + cancel+join.
            cs.scope.launch { c.onSessionClosed("s") }
            cs.advance()
            assertNull("state detached after onSessionClosed",
                c.schedulerSnapshotForTest("s", cs.generation.get()))

            // Advance well past 2s — no trailing should fire.
            cs.advance(60_000)
            assertEquals("no reload after onSessionClosed", 1, callCount)
        } finally { cs.cancel() }
    }

    // ─── Blocker #4b: authoritative-empty in-flight + new digest ───────────

    @Test
    fun `(blocker-4b) authoritative-empty in-flight plus new digest retains newer demand`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val gate = CompletableDeferred<Unit>()
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                // First call (auth-empty): block then return empty.
                // Second call (the digest): return content so we get a clean state.
                if (callCount == 1) {
                    gate.await()
                    MessagesPage(emptyList(), null)
                } else {
                    MessagesPage(listOf(mwp(msg("digest-content", created = 100L))), null)
                }
            }
            val c = cs.coordinator(store, repo)

            // Submit authoritative-empty → inFlight at t=0.
            c.submit("s", Tuple(1L, "m1"), Priority.FORCE_RECONCILE, ReloadReason.FORCE_RECONCILE_AUTHORITATIVE_EMPTY)
            cs.runCurrent()
            assertEquals(1, callCount)

            // Submit a new content-bearing digest while authoritative-empty is in-flight.
            // This bumps demandVersion above what the ticket captured.
            c.submit("s", Tuple(2L, "m2"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("inFlight still true (second submit coalesced)", snap.inFlight)
            assertTrue("dirty re-set by second submit while inFlight", snap.dirty)
            val dvDuringFlight = snap.demandVersion
            assertTrue("demandVersion bumped for second submit", dvDuringFlight >= 1L)

            // Release gate → authoritative-empty completes as Empty.
            // The Empty handler checks confirmsAuthoritativeEmpty → true, AND
            // newerDemand is true (the DIGEST submit bumped demandVersion).
            // Since newerDemand=true, it must NOT clear dirty.
            gate.complete(Unit)
            cs.advance()

            // After the authoritative-empty Empty outcome completes, the retained
            // dirty (newer DIGEST demand) should schedule a trailing reload.
            // That reload fires and gets content from the second mock answer.
            val snap2 = c.schedulerSnapshotForTest("s", cs.generation.get())
            assertNotNull("state must still exist (digest demand retained)", snap2)
            // If dirty=false → the authoritative-empty cleared it despite
            // newer demand → BUG. If dirty=true (correct), the second demand's
            // reload committed content → dirty should now be false.
            // Assert the content arrived.
            val msgIds = store.slices.chat.value.messages.map { it.id }
            assertTrue(
                "digest content must eventually be committed after authoritative-empty",
                msgIds.contains("digest-content"),
            )
            // The marker should be from the DIGEST submit (supplied tuple (2L, "m2")),
            // not from the authoritative-empty (which doesn't advance marker on empty).
            val marker = snap2!!.marker
            assertEquals(
                "marker must be the DIGEST tuple (2L, m2), not from auth-empty",
                Tuple(2L, "m2"), marker,
            )
        } finally { cs.cancel() }
    }

    // ─── Blocker #3c: guard reject via route change between launch and preHttpGuard ──
    //
    // Use a gate that blocks the coroutine BEFORE preHttpGuard (inside the LAZY
    // body but before any HTTP). Change the route while blocked → preHttpGuard
    // returns false → GuardRejected → restoreTicket preserves full demand.

    @Test
    fun `(blocker-3c) guard reject via route change restores ticket demand`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var limits = mutableListOf<Int>()
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                limits += secondArg<Int>()
                MessagesPage(listOf(mwp(msg("m${limits.size}", created = 100L))), null)
            }
            val c = cs.coordinator(store, repo)

            // Submit with one route; job scheduled on dispatcher but NOT yet executed.
            c.submit("s", Tuple(100L, "m1"), Priority.FORCE_RECONCILE,
                ReloadReason.FORCE_RECONCILE_AUTHORITATIVE_EMPTY)
            // Change route NOW, before the LAZY job body runs.
            store.mutateState { it.copy(chatRouteInstance = 99L) }
            cs.runCurrent()
            // preHttpGuard finds routeInstance(99L) != ticket.routeInstance(42L)
            // → GuardRejected → restoreTicketAsDirtyLocked
            assertEquals("no HTTP (guard rejected)", 0, limits.size)

            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("dirty restored after GuardRejected", snap.dirty)
            assertEquals("priority preserved as FORCE",
                Priority.FORCE_RECONCILE, snap.priority)

            // Fix the route back → nudge by toggling foreground → retry with FORCE limit=200.
            store.mutateState { it.copy(chatRouteInstance = 42L,
                liveBundleGeneration = 1L, liveEndpointFp = "http://a") }
            cs.foreground.value = false
            cs.runCurrent()
            cs.foreground.value = true
            cs.runCurrent()
            cs.advance(2_000)
            assertEquals("retry with FORCE limit=200 after route restored", listOf(200), limits)
        } finally { cs.cancel() }
    }

    // ─── Blocker #3d: digest → network failure → empty → eventual content (R1 chain) ──

    @Test
    fun `(blocker-3d) digest-to-network-failure-to-empty-to-eventual-content R1 chain`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                when (callCount) {
                    1 -> throw IOException("network failure #1")
                    2 -> MessagesPage(emptyList(), null) // empty after retry
                    3 -> MessagesPage(listOf(mwp(msg("eventual", created = 100L))), null)
                    else -> MessagesPage(emptyList(), null)
                }
            }
            val c = cs.coordinator(store, repo)
            c.submit("s", Tuple(100L, "eventual"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals("call 1: network failure", 1, callCount)

            // Retry at 2s: empty page.
            cs.advance(1_999)
            assertEquals("no retry before 2s", 1, callCount)
            cs.advance(1)
            assertEquals("call 2: empty page at 2s", 2, callCount)

            // Retry at +4s: eventual content.
            cs.advance(3_999)
            assertEquals("no retry before 4s", 2, callCount)
            cs.advance(1)
            assertEquals("call 3: eventual content at 4s", 3, callCount)
            assertEquals(listOf("eventual"),
                store.slices.chat.value.messages.map { it.id })

            val snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertFalse("dirty cleared after content committed", snap.dirty)
            assertEquals("marker advanced to digest tuple",
                Tuple(100L, "eventual"), snap.marker)
        } finally { cs.cancel() }
    }

    // ─── Blocker #3e: owned cancellation (state still owned) via onSessionClosed ──
    // Use the existing ABA test pattern (CountDownLatch + real dispatcher).
    // Cancel the in-flight job via onSessionClosed BUT the state is still owned
    // during the Cancellation handling — the Cancelled outcome's restoreTicket
    // runs while the state slot is still in the map.

    @Test
    fun `(blocker-3e) onSessionClosed detached cancellation does not mutate state`() {
        val store = createReadyStore("s", routeInstance = 42L)
        val entered = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val repo = mockk<OpenCodeRepository>(relaxed = false)
        coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
            entered.countDown()
            blocked.await()
            MessagesPage(listOf(mwp(msg("cancelled-content", created = 100L))), null)
        }
        val identity = AtomicReference(ConnectionIdentity(
            epoch = 1L, profileId = "host-A",
            normalizedWorkdir = "/a", endpointFp = "http://a"))
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val c = SkeletonReloadCoordinator(
                scope = realScope,
                repository = repo,
                slices = store.slices,
                foreground = MutableStateFlow(true),
                currentTransport = { TransportSnapshot(1L, identity.get()) },
                currentBundleStamp = { BundleStamp(1L, "http://a") },
            )

            // Submit → blocks on latch.
            c.submit("s", Tuple(100L, "cancelled-content"), Priority.FORCE_RECONCILE,
                ReloadReason.REQUEST_RELOAD)
            assertTrue("entered mock barrier", entered.await(5, TimeUnit.SECONDS))

            // onSessionClosed: stateLock removes the state + cancelAndJoin.
            // cancelAndJoin cancels the in-flight, then join() blocks until
            // the coroutine finishes (CE handler under NonCancellable).
            realScope.launch { c.onSessionClosed("s") }

            // The CE handler runs: stillOwnsLocked fails (state removed from map)
            // → Detached → no-op. The owned-cancellation restoreTicket logic
            // only applies when stillOwnsLocked passes (state NOT removed).
            blocked.countDown()
            Thread.sleep(500)

            // State is gone.
            assertNull("state detached by onSessionClosed",
                c.schedulerSnapshotForTest("s", 1L))
        } finally { realScope.cancel() }
    }

    // ─── Blocker #3f: production wiring - SSC delete/archive calls onSessionClosed ──
    //
    // Verifies that SessionSyncCoordinator.closeSkeletonSession (called from
    // digest deleted/archived, LegacySseHandler, and applySlimStatusFanOutSummary)
    // correctly delegates to SkeletonReloadCoordinator.onSessionClosed.

    @Test
    fun `(blocker-3f) SSC closeSkeletonSession delegates to coordinator onSessionClosed`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                MessagesPage(listOf(mwp(msg("m$callCount", created = 100L))), null)
            }
            val c = cs.coordinator(store, repo)

            // Submit → busy cap at 2s for second launch.
            seedBusy(store)
            c.submit("s", Tuple(1L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals(1, callCount)
            var snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertFalse("dirty cleared after commit", snap.dirty)

            // Second submit while busy → dirty queued, timer at 2s.
            c.submit("s", Tuple(2L, "m2"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("dirty for 2nd digest", snap.dirty)
            assertTrue("timer active for trailing", snap.timerActive)

            // Close → detach + cancel timer.
            cs.scope.launch { c.onSessionClosed("s") }
            cs.advance()
            assertNull("state detached after closeSkeletonSession",
                c.schedulerSnapshotForTest("s", cs.generation.get()))

            // Advance past the 2s timer → no additional calls.
            cs.advance(60_000)
            assertEquals("no reloads after close", 1, callCount)
        } finally { cs.cancel() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Round-2 rev-gpt blocker regression tests
    // ═══════════════════════════════════════════════════════════════════════

    // ─── Blocker #3 (round-2): owned cancellation via cancelInFlightForTest ──

    @Test
    fun `(R2-owned-cancellation) cancelInFlight restores dirty, priority, reasons, requiresContent, target`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val gate = CompletableDeferred<Unit>()
            var launchLimits = mutableListOf<Int>()
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                launchLimits += secondArg<Int>()
                gate.await()
                MessagesPage(listOf(mwp(msg("m1", created = 100L))), null)
            }
            val c = cs.coordinator(store, repo)

            // Submit content-bearing DIGEST + FORCE → in-flight, blocks at gate.
            c.submit("s", Tuple(100L, "m1"), Priority.FORCE_RECONCILE, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals(1, launchLimits.size)
            assertEquals(200, launchLimits[0])
            var snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("inFlight", snap.inFlight)

            // Capture the marker before cancellation (should be null on first launch).
            assertNull("marker not advanced yet", snap.marker)

            // Cancel the in-flight WITHOUT detaching state.
            c.cancelInFlightForTest(cs.generation.get(), "s")
            gate.complete(Unit)
            cs.advance()

            // After Cancelled outcome restores demand: dirty, priority, reasons,
            // requiresContent must all be restored. Marker must NOT advance.
            snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("dirty restored after owned cancellation", snap.dirty)
            assertEquals("priority preserved as FORCE",
                Priority.FORCE_RECONCILE, snap.priority)
            assertNull("marker must NOT advance on Cancelled outcome", snap.marker)
            assertTrue("queuedRequiresContent restored after owned cancellation (DIGEST is contentBearing)",
                snap.queuedRequiresContent)
            assertFalse("no inFlight after cancellation", snap.inFlight)
        } finally { cs.cancel() }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NOTE — No direct unit test for locallyInjected generation-isolated cleanup
    // ══════════════════════════════════════════════════════════════════════
    //
    //  Invariant: when `onSessionClosed` removes a closed generation's markers,
    //  a different-generation incarnation's markers survive.
    //
    //  Why no dedicated test: the `locallyInjected` field is `private val` with
    //  no test-inspection accessor, and there is no observable side-effect that
    //  isolates marker survival per-generation without also exercising the full
    //  detach/close flow (which is already covered elsewhere).
    //
    //  Enforced by:
    //    1. Key-type change: `Map<String, Set<String>>` → `Map<IncarnationKey, …>`
    //       where `IncarnationKey` includes `generation`. The type system
    //       guarantees that `onSessionClosed` and `detachGeneration` can only
    //       remove entries by the explicit generation they target.
    //    2. Existing generation-isolation test T-C1-e verifies that a gen-2
    //       state slot survives after gen-1 `detachGeneration` / `onSessionClosed`.
    //
    //  A future `schedulerSnapshotForTest`-style accessor for locallyInjected
    //  could enable a direct assertion; until then this invariant is compiler-
    //  proven plus the T-C1-e generation-separation coverage.

    // ─── Blocker #6 (round-2): authoritative-empty + content mixed-ticket ──

    @Test
    fun `(R2-mixed-ticket) authoritative-empty + content digest merged before launch retains content demand on empty`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            seedBusy(store)
            var callCount = 0
            var limits = mutableListOf<Int>()
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                limits += secondArg<Int>()
                MessagesPage(emptyList(), null) // always empty for this test
            }
            val c = cs.coordinator(store, repo)

            // First submit: content-bearing DIGEST → launches at t=0 (busy → nextAllowedAt=2s).
            c.submit("s", Tuple(100L, "content-target"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertEquals(1, callCount)
            assertEquals(listOf(50), limits)

            // During in-flight (busy, 2s cap), queue FORCE_RECONCILE_AUTHORITATIVE_EMPTY
            // AND another DIGEST — they merge into the same queued state.
            c.submit("s", Tuple(200L, "auth-target"), Priority.FORCE_RECONCILE,
                ReloadReason.FORCE_RECONCILE_AUTHORITATIVE_EMPTY)
            c.submit("s", Tuple(200L, "content-target-2"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()

            var snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("dirty for queued work", snap.dirty)
            assertTrue("requiresContent true (from DIGEST)", snap.queuedRequiresContent)

            // First launch completes (empty), onReloadComplete runs.
            // The ticket captured FORCE_RECONCILE_AUTHORITATIVE_EMPTY + DIGEST reasons.
            // requiresContent = true (DIGEST is contentBearing).
            // Authoritative-empty branch: ticketHasContentDemand = true → restore + R1 retry.
            cs.advance(1_999)
            assertEquals("no retry before 2s boundary", 1, callCount)
            cs.advance(1) // t=2000

            snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("dirty retained (content demand survived auth-empty clearing)", snap.dirty)
            assertTrue("requiresContent preserved", snap.queuedRequiresContent)

            // The retry should be the merged ticket's content-demand.
            assertEquals("retry fired", 2, callCount)
            // The retry priority should be FORCE (merged max from both).
            assertEquals("retry limit is 200 (FORCE from merged)",
                listOf(50, 200), limits)

            // Marker must NOT advance (empty page).
            assertNull("marker not advanced on empty", snap.marker)
        } finally { cs.cancel() }
    }

    // ─── Blocker #1/2 (round-2): readiness nudge - null identity → 0 HTTP → ready → HTTP ──

    @Test
    fun `(R2-readiness-nudge) null identity to ready triggers exactly one HTTP via nudgeCurrentSession`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                MessagesPage(listOf(mwp(msg("m$callCount", created = 100L))), null)
            }

            // Start with null identity (simulating cold start / post-beginReconfigure).
            cs.identity.set(null)
            val c = cs.coordinator(store, repo)

            // Submit a content-bearing digest while identity is null → no HTTP.
            c.submit("s", Tuple(100L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.advance(5_000)
            assertEquals("no HTTP while identity null", 0, callCount)
            var snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("dirty retained with null identity", snap.dirty)

            // Identity becomes available (simulating bind).
            cs.identity.set(ConnectionIdentity(epoch = 1L, profileId = "host-A",
                normalizedWorkdir = "/a", endpointFp = "http://a"))
            // nudgeCurrentSession should trigger the retained dirty work.
            c.nudgeCurrentSession()
            cs.advance(2_000)
            assertEquals("exactly one HTTP after identity ready", 1, callCount)
            snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertFalse("dirty cleared after commit", snap.dirty)
        } finally { cs.cancel() }
    }

    // ─── Blocker #1/2 (round-2): readiness nudge - null bundle to ready ──

    @Test
    fun `(R2-readiness-nudge-bundle) null bundle to ready triggers exactly one HTTP via nudgeCurrentSession`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            var callCount = 0
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                callCount++
                MessagesPage(listOf(mwp(msg("m$callCount", created = 100L))), null)
            }

            // Start with null bundle (simulating post-reconfigure before bundle publish).
            cs.bundleStamp.set(null)
            val c = cs.coordinator(store, repo)

            // Submit content-bearing digest → no HTTP (bundle null).
            c.submit("s", Tuple(100L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.advance(5_000)
            assertEquals("no HTTP while bundle null", 0, callCount)
            var snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertTrue("dirty retained with null bundle", snap.dirty)

            // Bundle becomes available → nudge triggers HTTP.
            cs.bundleStamp.set(BundleStamp(1L, "http://a"))
            c.nudgeCurrentSession()
            cs.advance(2_000)
            assertEquals("exactly one HTTP after bundle ready", 1, callCount)
            snap = c.schedulerSnapshotForTest("s", cs.generation.get())!!
            assertFalse("dirty cleared after commit", snap.dirty)
        } finally { cs.cancel() }
    }

    // ─── Blocker #4 (round-2): reconfigure barrier - detachGeneration isolation ──
    // Tests that detachGeneration removes stale generation states (the barrier's
    // second half). The full barrier (beginReconfigure + detachGeneration) is
    // tested by the existing T-C1-e generation isolation test.

    @Test
    fun `(R2-barrier-detach) detachGeneration removes stale generation slots`() {
        val cs = CoordinatorScope()
        try {
            val store = createReadyStore("s", routeInstance = 42L)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } returns
                MessagesPage(listOf(mwp(msg("m1", created = 100L))), null)
            val c = cs.coordinator(store, repo)

            // Submit with gen 1 → creates state.
            c.submit("s", Tuple(100L, "m1"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertNotNull("gen-1 state created",
                c.schedulerSnapshotForTest("s", 1L))

            // detachGeneration(2) removes gen-1 states.
            c.detachGeneration(2L)
            cs.runCurrent()

            assertNull("gen-1 state detached",
                c.schedulerSnapshotForTest("s", 1L))

            // Submit at gen 2 → creates fresh gen-2 state.
            cs.generation.set(2L)
            c.submit("s", Tuple(200L, "m2"), Priority.DIGEST, ReloadReason.DIGEST)
            cs.runCurrent()
            assertNotNull("gen-2 state created",
                c.schedulerSnapshotForTest("s", 2L))
            assertNull("gen-1 still absent",
                c.schedulerSnapshotForTest("s", 1L))
        } finally { cs.cancel() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // U-CQ6: blocker-4a ensureActive fence (orthogonal to B2 ordering slot-removal).
    // Verifies that the ensureActive() call at runReload :1381 drops the stale
    // HTTP page when cancellation hits during non-cooperative IO (CountDownLatch
    // blocking inside coAnswers), WITHOUT the slot being removed (no onSessionClosed).
    // The only guard against stale commit is the ensureActive fence itself.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `(blocker-4a) ensureActive fence drops stale page when cancel hits during non-cooperative IO`() {
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = CountDownLatch(1)
            val blocked = CountDownLatch(1)
            val store = createReadyStore("s", routeInstance = 42L)
            val repo = mockk<OpenCodeRepository>(relaxed = false)
            coEvery { repo.getSlimapiMessagesSkeleton("s", any(), any()) } coAnswers {
                entered.countDown()
                blocked.await()              // 非协作：cancel 期间不抛
                MessagesPage(listOf(mwp(msg("stale-m1", created = 100L))), null)
            }
            val identity = AtomicReference(ConnectionIdentity(
                epoch = 1L, profileId = "host-A",
                normalizedWorkdir = "/a", endpointFp = "http://a"))
            val c = SkeletonReloadCoordinator(
                scope = realScope, repository = repo, slices = store.slices,
                foreground = MutableStateFlow(true),
                currentTransport = { TransportSnapshot(1L, identity.get()) },
                currentBundleStamp = { BundleStamp(1L, "http://a") },
            )
            c.submit("s", Tuple(100L, "stale-m1"), Priority.FORCE_RECONCILE, ReloadReason.REQUEST_RELOAD)
            assertTrue("entered mock barrier", entered.await(5, TimeUnit.SECONDS))

            // 👇 Slot preserved (NOT onSessionClosed) — confirm it exists.
            val preCancelSnap = c.schedulerSnapshotForTest("s", 1L)
            assertNotNull("slot must exist before cancel (no onSessionClosed)", preCancelSnap)

            // 👇 关键：cancel 整个 scope（驱动 reload 子协程 cancel），NOT onSessionClosed → slot 保留
            realScope.cancel()
            blocked.countDown()             // 释放非协作 mock → 返回 stale page → ensureActive() 抛

            // Deterministically wait for the reload child to FINISH: after cancel +
            // release, the child resumes from the mock, hits runReload's ensureActive()
            // (MessageActions.kt:1381) which throws CancellationException, then the
            // `catch(CE){ withContext(NonCancellable){ onReloadComplete(Cancelled) } }`
            // (NonCancellable body runs despite the scope cancel) completes, and the
            // SupervisorJob's child finishes → scopeJob.isCompleted becomes true.
            //
            // We must NOT use a blind Thread.sleep here: it would risk reading the
            // INITIAL empty chat before ensureActive even runs → a silent false pass.
            // Bounded busy-poll on isCompleted is the file's established determinism
            // pattern (ABA test :752-755 / :771-777 poll with nanoTime deadline). Job.join()
            // is suspend and unusable in a plain @Test.
            val scopeJob = realScope.coroutineContext[Job]!!
            val completionDeadline = System.nanoTime() + 5_000_000_000L
            while (!scopeJob.isCompleted && System.nanoTime() < completionDeadline) {
                Thread.sleep(25)
            }
            assertTrue(
                "scope must complete after cancel (ensureActive fence + CE handler ran)",
                scopeJob.isCompleted,
            )

            // ensureActive() threw CancellationException before commitReload → stale page never committed.
            assertEquals(
                "ensureActive fence must prevent stale page from committing",
                emptyList<String>(), store.slices.chat.value.messages.map { it.id },
            )
        } finally { realScope.cancel() }
    }

}
