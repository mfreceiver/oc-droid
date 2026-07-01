package cn.vectory.ocdroid.data.api

import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-21 — SSEClient 单测。
 *
 * 生产类 [SSEClient] 的所有重试/退避/心跳常量都是 `const val`（编译期内联到调用点），
 * 反射改伴生字段**不会**改变已编译的调用点行为，因此：
 *  - **不**做"反射注入短退避"——既不可行也不可靠。
 *  - **不**测 `MAX_RETRY_ATTEMPTS` 耗尽抛 [SSEConnectionExhausted]：要真正触发需累加
 *    10 次退避（基础 1s 起 ×2 指数 → 总和 ~181s），单测不可接受。详见
 *    `connectionExhausted_isNotCoverableDueToInlinedConsts` 的说明。
 *  - **不**测 30s 心跳看门狗：阈值同样是 `const val` 内联，且要等 ≥30s 真实时间。
 *
 * 实际覆盖（最高价值、可确定性验证的三类）：
 *  1. 事件流解析（单行 data / 多行 data 拼接 / 非法 data 静默丢弃）
 *  2. URL userinfo 脱敏（`user:pass@` 不得进入 in-app 日志 ring buffer）
 *  3. 服务端立即失败后的重连恢复（best-effort，验证 retryWhen 至少重试一次并恢复）
 *
 * 全部用纯 JVM + OkHttp MockWebServer 的 SSE 模式驱动；android.util.Log / Uri.parse
 * 由 `testOptions.unitTests.isReturnDefaultValues = true` 提供 stub 默认返回。
 */
class SSEClientTest {

    private val server = MockWebServer()
    private lateinit var client: OkHttpClient
    private lateinit var sse: SSEClient

    @Before
    fun setUp() {
        // DebugLog 是全局 ring buffer；每个 case 起手清空，避免互相断言污染。
        DebugLog.clear()
        server.start()
        client = OkHttpClient.Builder().build()
        sse = SSEClient(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * SSE 响应：text/event-stream + 若干 SSE 帧。
     *
     * 关键：每帧之间及末尾必须有**空行**（`\n\n`）—— SSE 用空行作为事件分隔符，
     * 没有空行 OkHttp RealEventSource 不会触发 onEvent。frames 之间用 `\n\n` join、
     * 末尾也补 `\n\n`，保证最后一个事件被立即派发而不是等连接关闭。
     */
    private fun sseResponse(vararg frames: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(frames.joinToString(separator = "\n\n", postfix = "\n\n"))

    /** 单条 `data: <json>` 帧（与 sseResponse 的 `\n\n` 分隔符配合构成完整事件）。 */
    private fun dataFrame(json: String): String = "data: $json"

    // ───────────────────────── 1. 事件解析 ─────────────────────────

    @Test
    fun `parses single data event as SSEEvent`() = runBlocking {
        val payload = """{"payload":{"type":"server.connected","properties":{"sessionID":"s1"}}}"""
        server.enqueue(sseResponse(dataFrame(payload)))

        val event = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'))
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("server.connected", event.payload.type)
        assertEquals("s1", event.payload.getString("sessionID"))
    }

    @Test
    fun `parses multiple sequential events`() = runBlocking {
        val p1 = """{"payload":{"type":"server.connected"}}"""
        val p2 = """{"payload":{"type":"session.updated","properties":{"sessionID":"x"}}}"""
        // 两帧之间必须有空行分隔（SSE 事件边界）。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: $p1\n\ndata: $p2\n\n")
        )

        val events = withTimeout(5_000) {
            // 取前 2 条成功事件；流在 EOF 后 onClosed 关闭，take(2) + toList 自然完成。
            sse.connect(server.url("/").toString().trimEnd('/'))
                .take(2)
                .toList()
                .map { it.getOrThrow() }
        }

        assertEquals(2, events.size)
        assertEquals("server.connected", events[0].payload.type)
        assertEquals("session.updated", events[1].payload.type)
        assertEquals("x", events[1].payload.getString("sessionID"))
    }

    @Test
    fun `multi-line data field is concatenated by okhttp before onEvent`() = runBlocking {
        // OkHttp RealEventSource 把同一事件里多行 `data:` 用 "\n" 拼接后再回调 onEvent。
        // SSE 规范允许 JSON 跨多行（token 之间的空白被 JSON parser 忽略）。这里把一个
        // 完整 JSON 在 **逗号处**（token 边界，非字符串内部）拆成两行 data:，验证拼接后
        // 仍能被 SSEClient 解析。注意：拆在字符串字面量中间会让 "\n" 进入串值导致 JSON 非法。
        val payload = """{"payload":{"type":"message.part.delta","properties":{"sessionID":"m"}}}"""
        val commaIdx = payload.indexOf(',')
        val part1 = payload.substring(0, commaIdx)  // {"payload":{"type":"message.part.delta"
        val part2 = payload.substring(commaIdx)      // ,"properties":{"sessionID":"m"}}}
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: $part1\ndata: $part2\n\n")
        )

        val event = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'))
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("message.part.delta", event.payload.type)
        assertEquals("m", event.payload.getString("sessionID"))
    }

    @Test
    fun `malformed data event is skipped without crashing flow`() = runBlocking {
        // 第一帧是非法 JSON（应被 SSEClient 内部 try/catch 吞掉），第二帧合法。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: not-json\n\ndata: {\"payload\":{\"type\":\"ok\"}}\n\n")
        )

        val event = withTimeout(5_000) {
            sse.connect(server.url("/").toString().trimEnd('/'))
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("ok", event.payload.type)
    }

    // ───────────────────────── 2. URL 脱敏 ─────────────────────────

    @Test
    fun `url userinfo is never written to debug log`() = runBlocking {
        // 用带 userinfo 的 URL 连接；服务端立即 401 关闭即可触发日志路径。
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("nope")
        )
        val host = server.hostName
        val port = server.port
        val urlWithCreds = "http://alice:supersecret@$host:$port"

        // 主动消费流到一个失败/关闭结束；忽略结果（onFailure/onClosed 都会走 DebugLog.w）。
        runCatching {
            withTimeout(3_000) {
                sse.connect(urlWithCreds).take(1).toList()
            }
        }
        // 等一小会儿让 OkHttp 后台线程把 onFailure 的 DebugLog.w 写入 ring buffer。
        Thread.sleep(300)

        val ringDump = DebugLog.entries.value.joinToString("\n") { "${it.tag}: ${it.message}" }
        println("DEBUG LOG DUMP:\n$ringDump")
        assertFalse(
            "userinfo alice:supersecret must NOT appear in any DebugLog entry",
            ringDump.contains("alice:supersecret")
        )
        assertFalse(
            "raw password must NOT appear in any DebugLog entry",
            ringDump.contains("supersecret")
        )
    }

    // ─────────────── 3. 重连（best-effort） / 跳过说明 ───────────────

    /**
     * BEST-EFFORT：验证服务端首次失败后 retryWhen 确实重试并最终拿到事件。
     *
     * 限制：
     *  - 退避基础延迟 `INITIAL_RETRY_DELAY_MS = 1000L` 是 `const val`，编译期内联，
     *    无法反射缩短。本用例因此需真实 wall-clock 等 ≥1s，超时阈值给宽。
     *  - 仅做"至少重试一次并恢复"的弱断言；不做 MAX_RETRY 耗尽（见下）。
     */
    @Test
    fun `reconnects after transient server failure`() = runBlocking {
        // 第 1 个请求立即 500（onFailure → retryWhen 退避 → 重连）。
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        // 第 2 个请求正常返回一条事件，证明重连后能恢复。
        val payload = """{"payload":{"type":"server.connected"}}"""
        server.enqueue(sseResponse(dataFrame(payload)))

        val event = withTimeout(10_000) {
            sse.connect(server.url("/").toString().trimEnd('/'))
                .first { it.isSuccess }
                .getOrThrow()
        }

        assertEquals("server.connected", event.payload.type)
        // 至少发生了一次重连：第一个请求被消费 + 第二个请求被消费。
        assertTrue(
            "both enqueued responses should have been consumed (requestCount=${server.requestCount})",
            server.requestCount >= 2
        )
    }

    /**
     * 文档化用例：[SSEConnectionExhausted] 在单测中不可达。
     *
     * 原因：
     *  - `MAX_RETRY_ATTEMPTS = 10L` / `INITIAL_RETRY_DELAY_MS = 1000L` /
     *    `RETRY_MULTIPLIER = 2.0` 全是 `const val`，Kotlin 在编译期把字面值内联到
     *    `connect` 的 `retryWhen` 调用点。运行时反射改 Companion 字段**不会**改变
     *    已编译的字节码行为。
     *  - 真要触发耗尽，需累计 10 次退避：1+2+4+8+16+30×5 ≈ 181s wall-clock，
     *    这在 unit test 中不可接受。
     *
     * 若未来要把这类异常路径纳入 CI，需要先把这几个常量从 `const val` 改成普通
     * `private val`（或通过构造注入），并提供测试友好的较短默认。本任务约束
     * "不改生产代码"，故仅在此标注，并顺手覆盖异常类的构造。
     */
    @Test
    fun connectionExhausted_isNotCoverableDueToInlinedConsts() {
        val ex = SSEConnectionExhausted()
        assertNotNull(ex.message)
    }
}
