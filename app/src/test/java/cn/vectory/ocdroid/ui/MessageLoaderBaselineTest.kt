package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * §Wave1B MessageLoader 提取行为基线 (r2 纠正轮) — 三路合并契约锚点。
 *
 * 上下文：`launchLoadMessages` 的三路合并（olderKept / fetched / newerKept）
 * 是侦察报告点名的技术债热点：函数单体 ~600 行，合并语义跨多轮 bug 修复沉淀
 * （§Q10 三路合并、§newerKept-force-window-fix 2026-07-26、§flicker-fix、
 * §append-safe）。本基线在 Wave1B「MessageLoader 提取」之前显式钉死该契约，
 * 作为行为保持的 oracle——提取后必须仍绿。
 *
 * 覆盖面定位（与既有 [MessageActionsTest] 互补，非重复）：
 *  - MessageActionsTest 已显式覆盖 olderKept（"preserves older already-loaded
 *    pages across reload"）与 cursor 分页、CE/重试、fp 守卫等。
 *  - 本基线补齐 newerKept 维度——历史上 bug 最多的那一桶（REST 在飞期间 SSE
 *    注入的 user/assistant 消息）：
 *     1. 三路并存：older 历史 + fetched 服务端窗口 + newer 在飞注入消息
 *        一次 reload 后三者俱全（§Q10）。
 *     2. forceInitialWindow=true 下 newerKept 必须存活（§newerKept-force-
 *        window-fix 2026-07-26 回归锚点）：fresh window 丢弃 stale 历史
 *        （olderKept 清空）但 live SSE 消息不能被一并清掉。
 *     3. 无 created 时间戳的乐观本地插入落入 newest 端（null-created 语义）。
 *
 * 实现细节：直接驱动真实 [SharedStateStore]（mutateChat 写入传播到 slice 读）
 * + mockk [OpenCodeRepository] + 不走 AppCore/Hilt/Robolectric，镜像
 * [MessageActionsTest] 的 fixture 风格。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageLoaderBaselineTest {

    private lateinit var store: SharedStateStore
    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var scope: TestScope
    private lateinit var emit: EventEmitter

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        store = SharedStateStore()
        slices = store.slices
        repository = mockk(relaxed = true)
        scope = TestScope(UnconfinedTestDispatcher())
        emit = EventEmitter { }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun msg(id: String, role: String = "user", created: Long? = null): Message =
        Message(
            id = id,
            role = role,
            time = created?.let { Message.TimeInfo(created = it) },
        )

    private fun stubTodos(sessionId: String) {
        coEvery { repository.getSessionTodos(sessionId) } returns Result.success(emptyList())
    }

    // ── §Q10 三路合并契约 ────────────────────────────────────────────────────

    @Test
    fun `three-way merge keeps older history + fetched window + newer in-flight injection across reload`() = runTest {
        // 本地已加载：一条 older 历史（created=100）+ 一条更新于 fetched 窗口的
        // 在飞注入消息（created=400，服务端 REST 快照尚未收录）。
        val olderLocal = msg("old", created = 100L)
        val newerLocal = msg("newer-local", role = "assistant", created = 400L)
        // fetched 窗口介于两者之间（服务端权威页）。
        val fetched = listOf(
            MessageWithParts(info = msg("mid1", created = 200L)),
            MessageWithParts(info = msg("mid2", role = "assistant", created = 300L)),
        )
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(fetched, null))
        stubTodos("s1")
        store.mutateChat {
            it.copy(currentSessionId = "s1", messages = listOf(olderLocal, newerLocal))
        }

        launchLoadMessages(scope, repository, slices, "s1", resetLimit = false, emit = emit)
        advanceUntilIdle()

        // olderKept=[old] + fetched=[mid1,mid2] + newerKept=[newer-local]
        // → 升序合并后三者俱全（§Q10 三路契约）。
        assertEquals(
            listOf("old", "mid1", "mid2", "newer-local"),
            slices.chat.value.messages.map { it.id },
        )
    }

    // ── §newerKept-force-window-fix (2026-07-26) 回归锚点 ─────────────────────

    @Test
    fun `forceInitialWindow=true discards stale older history but preserves newer in-flight injection`() = runTest {
        // 经典 bug 场景：新会话，REST GET 在飞期间 SSE 已注入消息。
        // forceInitialWindow=true（仅 VerifyAndHydrate 冷加载分支置位）走
        // unanchored 抓取；olderKept 被清空（fresh window 丢弃 stale 历史），
        // 但 newerKept 必须保留——否则"首条消息直到重进会话才渲染"。
        val olderLocal = msg("stale-old", created = 100L)
        val newerLocal = msg("sse-injected", role = "assistant", created = 400L)
        // unanchored 抓取返回一条介于两者之间的消息，使得 oldest/newestFetchedCreated
        // 均有定义——从而 olderKept(清空) 与 newerKept(保留) 的分类都走实值分支。
        val fetched = listOf(MessageWithParts(info = msg("mid", created = 250L)))
        coEvery { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) } returns
            Result.success(MessagesPage(fetched, null))
        stubTodos("s1")
        store.mutateChat {
            it.copy(currentSessionId = "s1", messages = listOf(olderLocal, newerLocal))
        }

        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            resetLimit = true,
            emit = emit,
            forceInitialWindow = true,
        )
        advanceUntilIdle()

        // stale-old(100 < 250) 被 forceInitialWindow 的 olderKept 清空丢弃；
        // sse-injected(400 >= 250) 经 newerKept 存活。合并 = [mid, sse-injected]。
        // 若 §newerKept-force-window-fix 回归（newerKept 也被 forceWindow 清空），
        // sse-injected 会消失 → 本断言失败。
        assertEquals(
            listOf("mid", "sse-injected"),
            slices.chat.value.messages.map { it.id },
        )
    }

    // ── null-created 乐观本地插入落入 newest 端 ───────────────────────────────

    @Test
    fun `optimistic local insert with null created timestamp lands at newest end via newerKept`() = runTest {
        // 乐观本地插入（ensurePlaceholderPart 注入的 user echo / assistant shell，
        // 尚无 created 时间戳）必须落入 newerKept（reverseLayout 底部），不能被
        // 误判为 older 而塞到历史前端。
        val optimistic = msg("optimistic") // created = null
        val fetched = listOf(MessageWithParts(info = msg("server-msg", created = 200L)))
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(fetched, null))
        stubTodos("s1")
        store.mutateChat {
            it.copy(currentSessionId = "s1", messages = listOf(optimistic))
        }

        launchLoadMessages(scope, repository, slices, "s1", resetLimit = true, emit = emit)
        advanceUntilIdle()

        // fetched=[server-msg] + newerKept=[optimistic]（null-created → newest 端）。
        assertEquals(
            listOf("server-msg", "optimistic"),
            slices.chat.value.messages.map { it.id },
        )
    }
}
