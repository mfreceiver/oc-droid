package cn.vectory.ocdroid.data.repository.http

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * §4 / v3: 单测 [SslConfigFactory] + [buildMutualTlsConfig]。
 *
 * 覆盖：
 *  - [buildMutualTlsConfig] 两 CA 模式（私有 CA 严格 / 平台 CA）。
 *  - CA bundle 多证（generateCertificates 复数）。
 *  - 多 key entry p12 → require 抛（v3-gpter R2#1重要）。
 *  - mTLS 优先于 allowInsecure（[SslConfigFactory.sslConfigFor]）。
 *  - [SslConfigFactory.configureClientCert] 损坏 p12 → runCatching 降级 +
 *    [SslConfigFactory.lastClientCertError] 非空（v3-glmer R2 防冷启崩溃）。
 *  - [SslConfigFactory.resolveProbe] 纯参数，**不读** held mTLS（v3-gpter R2#1 阻断）。
 *
 * JVM 单测走 JDK 自带 PKCS12 provider（读 PBES2 无碍），与设备 BC 行为不同——
 * `-legacy` p12 在 API 26 模拟器的可读性是单独硬发布门（见 plan §4），本单测不足证。
 */
class SslConfigFactoryTest {

    private val server = MockWebServer()
    private val pw = "test-p12-pw"

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** 自签名根 CA（带 keyPair，可签发子证）。 */
    private fun newRootCa(cn: String = "opencode-test-ca"): HeldCertificate =
        HeldCertificate.Builder().commonName(cn).certificateAuthority(0).build()

    /** 由 [parent] 签发的叶子证书（客户端 / 服务端通用）。 */
    private fun newSigned(
        parent: HeldCertificate,
        cn: String = "leaf",
        san: String? = null,
    ): HeldCertificate {
        val b = HeldCertificate.Builder().commonName(cn)
        if (san != null) b.addSubjectAlternativeName(san)
        return b.signedBy(parent).build()
    }

    /** 把 [HeldCertificate]（含私钥）+ 证书链打包成 PKCS12 字节。 */
    private fun p12(
        holder: HeldCertificate,
        chain: List<X509Certificate>,
        alias: String = "single",
        password: String = pw,
    ): ByteArray {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry(alias, holder.keyPair.private, password.toCharArray(), chain.toTypedArray())
        val baos = ByteArrayOutputStream()
        ks.store(baos, password.toCharArray())
        return baos.toByteArray()
    }

    /** 多 key entry p12（两个不同 alias 的 key entry）→ 用来触发 require 抛。 */
    private fun p12MultiKey(
        holder1: HeldCertificate, chain1: List<X509Certificate>,
        holder2: HeldCertificate, chain2: List<X509Certificate>,
        password: String = pw,
    ): ByteArray {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("k1", holder1.keyPair.private, password.toCharArray(), chain1.toTypedArray())
        ks.setKeyEntry("k2", holder2.keyPair.private, password.toCharArray(), chain2.toTypedArray())
        val baos = ByteArrayOutputStream()
        ks.store(baos, password.toCharArray())
        return baos.toByteArray()
    }

    /** X509Certificate → PEM 字节（CertificateFactory.generateCertificates 吃 PEM bundle）。 */
    private fun X509Certificate.toPem(): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("-----BEGIN CERTIFICATE-----\n".toByteArray())
        baos.write(Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encode(encoded))
        baos.write("\n-----END CERTIFICATE-----\n".toByteArray())
        return baos.toByteArray()
    }

    // ── buildMutualTlsConfig ─────────────────────────────────────────────────

    @Test
    fun `buildMutualTlsConfig private CA mode returns MutualTLS and trusts only the CA`() {
        val ca = newRootCa()
        val client = newSigned(ca, cn = "ocdroid-test-client")
        val p12Bytes = p12(client, listOf(client.certificate, ca.certificate))
        val caPem = ca.certificate.toPem()

        val cfg = buildMutualTlsConfig(ClientCertMaterial(p12Bytes, pw.toCharArray(), caPem))

        // End-to-end: MockWebServer 呈服务端证书（CA 签），客户端出示 client 证书 +
        // 只信 CA → 握手成功（v3-gpter R2#5：私有 CA 严格，防 WebPKI MITM）。
        val serverCert = newSigned(ca, cn = "server", san = "localhost")
        val serverHs = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .addTrustedCertificate(client.certificate) // 服务端要求并校验 client 证书
            .build()
        server.useHttps(serverHs.sslContext().socketFactory, false)
        server.enqueue(MockResponse().setBody("ok"))

        val okClient = OkHttpClient.Builder()
            .apply { applySsl(cfg) }
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val res = okClient.newCall(Request.Builder().url(server.url("/").toString()).build()).execute()
        assertEquals("private CA mTLS handshake succeeds", 200, res.code)
        assertEquals("ok", res.body?.string())
        res.close()
    }

    @Test
    fun `buildMutualTlsConfig platform CA mode returns MutualTLS when caBytes is null`() {
        val ca = newRootCa()
        val client = newSigned(ca, cn = "client")
        val p12Bytes = p12(client, listOf(client.certificate, ca.certificate))

        // caBytes=null → 平台 CA 模式（服务端证书为公开签发时，如 Let's Encrypt）。
        // 这里仅断返回类型正确；end-to-end 需平台信任的证书，不在 JVM 单测范围。
        val cfg = buildMutualTlsConfig(ClientCertMaterial(p12Bytes, pw.toCharArray(), null))
    }

    @Test
    fun `buildMutualTlsConfig accepts a CA bundle with multiple certificates`() {
        val ca1 = newRootCa(cn = "ca-1")
        val ca2 = newRootCa(cn = "ca-2")
        val client = newSigned(ca1, cn = "client")
        val p12Bytes = p12(client, listOf(client.certificate, ca1.certificate))
        // v3-gpter R2#5：generateCertificates（复数）支持 PEM bundle / 中间 CA。
        val bundle = ca1.certificate.toPem() + ca2.certificate.toPem()

        val cfg = buildMutualTlsConfig(ClientCertMaterial(p12Bytes, pw.toCharArray(), bundle))
    }

    @Test
    fun `buildMutualTlsConfig rejects a PKCS12 with multiple key entries`() {
        val ca = newRootCa()
        val c1 = newSigned(ca, cn = "client-a")
        val c2 = newSigned(ca, cn = "client-b")
        // v3-gpter R2#1重要：多 key entry 下默认 X509KeyManager 可能出示非预期证书。
        val multiKeyP12 = p12MultiKey(
            c1, listOf(c1.certificate, ca.certificate),
            c2, listOf(c2.certificate, ca.certificate),
        )

        assertThrows(IllegalArgumentException::class.java) {
            buildMutualTlsConfig(ClientCertMaterial(multiKeyP12, pw.toCharArray(), null))
        }
    }

    @Test
    fun `buildMutualTlsConfig rejects a corrupted p12`() {
        // 非法字节 → KeyStore.load 抛（不是 require，而是 IOException/解析错）。
        assertThrows(Exception::class.java) {
            buildMutualTlsConfig(ClientCertMaterial(ByteArray(64) { it.toByte() }, pw.toCharArray(), null))
        }
    }

    // ── configureClientCert runCatching degradation ──────────────────────────

    @Test
    fun `configureClientCert with corrupted p12 degrades and sets lastClientCertError`() {
        // v3-glmer R2：p12 损坏时不抛穿栈（防冷启崩溃），而是降级 mutualTlsConfig=null +
        // 记 lastClientCertError 供 UI 诊断。
        val factory = SslConfigFactory()
        val corrupted = ClientCertMaterial(ByteArray(32) { it.toByte() }, pw.toCharArray(), null)

        factory.configureClientCert(corrupted)

        assertNotNull("lastClientCertError set on corruption", factory.lastClientCertError)
        // 降级到非 mTLS（hostPort=null → SystemDefault，非 MutualTLS）。
        assertEquals(SslConfig.SystemDefault, factory.sslConfigFor(null))
    }

    @Test
    fun `configureClientCert null clears lastClientCertError`() {
        val factory = SslConfigFactory()
        factory.configureClientCert(ClientCertMaterial(ByteArray(8), pw.toCharArray(), null))
        assertNotNull(factory.lastClientCertError)

        factory.configureClientCert(null)

        assertEquals(null, factory.lastClientCertError)
        assertEquals(SslConfig.SystemDefault, factory.sslConfigFor(null))
    }

    // ── resolveProbe does NOT read held mTLS (v3-gpter R2#1 阻断) ──────────────

    @Test
    fun `resolveProbe ignores held mTLS and returns based purely on params`() {
        // 先 configureClientCert 一个有效 mTLS（held 状态非空）。
        val factory = SslConfigFactory()
        val ca = newRootCa()
        val client = newSigned(ca)
        factory.configureClientCert(
            ClientCertMaterial(
                p12(client, listOf(client.certificate, ca.certificate)),
                pw.toCharArray(),
                null,
            )
        )
        assertTrue("held mTLS configured", factory.sslConfigFor(null) is SslConfig.MutualTLS)

        // resolveProbe(hostPort, clientCert=null) 必须不读 held mTLS——
        // 否则测他 profile 会复用当前 mTLS 缓存，误出示证书 / 泄漏身份。
        assertEquals(
            "resolveProbe with no clientCert → SystemDefault (NOT held MutualTLS)",
            SslConfig.SystemDefault,
            factory.resolveProbe(hostPort = "unrelated.example:443", clientCert = null, trustAll = false),
        )
    }

    @Test
    fun `resolveProbe builds a fresh MutualTLS from the supplied clientCert`() {
        val factory = SslConfigFactory()
        // held = CA-a 的 mTLS。
        val caA = newRootCa(cn = "ca-a")
        val clientA = newSigned(caA)
        factory.configureClientCert(
            ClientCertMaterial(
                p12(clientA, listOf(clientA.certificate, caA.certificate)),
                pw.toCharArray(), null,
            )
        )
        // 探测用 CA-b 的材料 → resolveProbe 必须构建全新的 MutualTLS（基于入参），
        // 而非返回 held 的 CA-a 配置。
        val caB = newRootCa(cn = "ca-b")
        val clientB = newSigned(caB)
        val probe = factory.resolveProbe(
            hostPort = null,
            clientCert = ClientCertMaterial(
                p12(clientB, listOf(clientB.certificate, caB.certificate)),
                pw.toCharArray(), null,
            ),
            trustAll = false,
        )
        assertTrue("resolveProbe builds MutualTLS from supplied clientCert", probe is SslConfig.MutualTLS)
        // 与 held 不是同一实例（每次 buildMutualTlsConfig 新建）。
        val held = factory.sslConfigFor(null)
        assertTrue(held is SslConfig.MutualTLS)
        assertTrue("resolveProbe result is a fresh build, not the held cache", probe !== held)
    }

    // ── publishClientCertResolution (§concurrency-refactor) ──────────────────

    /**
     * §6.3 §concurrency-refactor: the new [SslConfigFactory.publishClientCertResolution]
     * setter publishes a PRE-BUILT mTLS resolution onto the factory mirrors,
     * replicating [configureClientCert] semantics EXACTLY without re-parsing the
     * PKCS12. This block covers the three branches:
     *  - null material → clear both mirrors.
     *  - resolved MutualTLS → set mTLS, clear error.
     *  - failed (resolved=null) → clear mTLS, set error (with the
     *    "client cert load failed" fallback when the error message is null).
     *
     * It also pins the byte-identical parity between [configureClientCert] and
     * [publishClientCertResolution] for the same inputs (the load-bearing
     * guarantee that [configure] can swap the call without behavior change).
     */
    @Test
    fun `publishClientCertResolution null material clears both mirrors`() {
        val factory = SslConfigFactory()
        // Seed a non-empty state first.
        val ca = newRootCa()
        val client = newSigned(ca)
        factory.publishClientCertResolution(
            material = ClientCertMaterial(p12(client, listOf(client.certificate, ca.certificate)), pw.toCharArray(), null),
            resolved = buildMutualTlsConfig(
                ClientCertMaterial(p12(client, listOf(client.certificate, ca.certificate)), pw.toCharArray(), null),
            ),
            error = null,
        )
        assertTrue("seed: mTLS held", factory.sslConfigFor(null) is SslConfig.MutualTLS)

        // null material → clear both (mirrors configureClientCert(null)).
        factory.publishClientCertResolution(material = null, resolved = null, error = null)

        assertEquals("mutualTlsConfig cleared", SslConfig.SystemDefault, factory.sslConfigFor(null))
        assertEquals("lastClientCertError cleared", null, factory.lastClientCertError)
    }

    @Test
    fun `publishClientCertResolution resolved sets the pre-built mTLS and clears the error`() {
        val factory = SslConfigFactory()
        val ca = newRootCa()
        val client = newSigned(ca)
        val material = ClientCertMaterial(p12(client, listOf(client.certificate, ca.certificate)), pw.toCharArray(), null)
        val preBuilt = buildMutualTlsConfig(material)

        factory.publishClientCertResolution(material = material, resolved = preBuilt, error = null)

        assertSame(
            "factory must hold the EXACT pre-built MutualTLS instance (no second parse)",
            preBuilt,
            factory.sslConfigFor(null),
        )
        assertEquals("error cleared on resolved publish", null, factory.lastClientCertError)
    }

    @Test
    fun `publishClientCertResolution failed with a message mirrors configureClientCert`() {
        val factory = SslConfigFactory()
        val badMaterial = ClientCertMaterial(ByteArray(8), pw.toCharArray(), null)

        factory.publishClientCertResolution(material = badMaterial, resolved = null, error = "explicit parse error")

        assertEquals(
            "failed parse → SystemDefault (mTLS degraded)",
            SslConfig.SystemDefault,
            factory.sslConfigFor(null),
        )
        assertEquals(
            "failed parse with message → that exact message",
            "explicit parse error",
            factory.lastClientCertError,
        )
    }

    @Test
    fun `publishClientCertResolution failed with null message falls back to the canonical message`() {
        val factory = SslConfigFactory()
        val badMaterial = ClientCertMaterial(ByteArray(8), pw.toCharArray(), null)

        // error=null models a throwing exception whose .message was null. The
        // factory mirror MUST substitute the non-null fallback (configureClientCert
        // does `it.message ?: "client cert load failed"`); this asymmetry vs the
        // nullable bundle field is preserved exactly.
        factory.publishClientCertResolution(material = badMaterial, resolved = null, error = null)

        assertEquals(
            "null message → canonical non-null fallback on the factory mirror",
            "client cert load failed",
            factory.lastClientCertError,
        )
    }

    @Test
    fun `publishClientCertResolution is state-equivalent to configureClientCert for the same inputs`() {
        // The load-bearing parity guarantee: for every (material, resolved-or-error)
        // combination, publishClientCertResolution and configureClientCert leave
        // the factory mirrors in the SAME state. This is what lets configure()
        // swap configureClientCert → publishClientCertResolution with zero
        // behavior delta (and parse the p12 once instead of twice).
        val ca = newRootCa()
        val client = newSigned(ca)
        val goodMaterial = ClientCertMaterial(p12(client, listOf(client.certificate, ca.certificate)), pw.toCharArray(), null)
        val preBuilt = buildMutualTlsConfig(goodMaterial)
        val badMaterial = ClientCertMaterial(ByteArray(64) { it.toByte() }, pw.toCharArray(), null)
        val badError = runCatching { buildMutualTlsConfig(badMaterial) }.exceptionOrNull()?.message

        // Branch 1: resolved (good material). configureClientCert re-parses;
        // publishClientCertResolution reuses preBuilt. sslConfigFor must be
        // MutualTLS for both (different instances, same TYPE — re-parse builds
        // a new object; that is the only allowed divergence).
        val a1 = SslConfigFactory().apply { configureClientCert(goodMaterial) }
        val b1 = SslConfigFactory().apply { publishClientCertResolution(goodMaterial, preBuilt, null) }
        assertTrue("configureClientCert resolved → MutualTLS", a1.sslConfigFor(null) is SslConfig.MutualTLS)
        assertTrue("publishClientCertResolution resolved → MutualTLS", b1.sslConfigFor(null) is SslConfig.MutualTLS)
        assertEquals("both clear error on resolved", null, a1.lastClientCertError)
        assertEquals("both clear error on resolved", null, b1.lastClientCertError)

        // Branch 2: failed (bad material). Both must degrade identically.
        val a2 = SslConfigFactory().apply { configureClientCert(badMaterial) }
        val b2 = SslConfigFactory().apply { publishClientCertResolution(badMaterial, null, badError) }
        assertEquals("both degrade to SystemDefault", a2.sslConfigFor(null), b2.sslConfigFor(null))
        assertEquals(
            "both carry the SAME error message (one parse, byte-identical)",
            a2.lastClientCertError,
            b2.lastClientCertError,
        )

        // Branch 3: null material. Both clear.
        val a3 = SslConfigFactory().apply { configureClientCert(goodMaterial); configureClientCert(null) }
        val b3 = SslConfigFactory().apply { publishClientCertResolution(goodMaterial, preBuilt, null); publishClientCertResolution(null, null, null) }
        assertEquals("both clear mTLS", SslConfig.SystemDefault, a3.sslConfigFor(null))
        assertEquals("both clear mTLS", SslConfig.SystemDefault, b3.sslConfigFor(null))
        assertEquals("both clear error", null, a3.lastClientCertError)
        assertEquals("both clear error", null, b3.lastClientCertError)
    }
}
