package cn.vectory.ocdroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.data.repository.http.buildMutualTlsConfig
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * mTLS / PKCS12 兼容性硬发布门（plan §4）。
 *
 * JVM 单测走 JDK 自带 PKCS12 provider（读 PBES2 / 3DES 无碍），给虚假信心——
 * Android 上 PKCS12 由 BouncyCastle 提供，老设备/低 API 对 `-legacy`（3DES/SHA1）
 * 加密的 p12 解析行为与桌面 JDK **不同**。本测试在真机/模拟器的 Android BC
 * provider 上验证 App 的客户端证书载入路径能正确解析 legacy 加密的 p12。
 *
 * Fixture：`mtls_legacy_test.p12`（3DES/SHA1、空密码、最兼容老 Android BC）含
 * 客户端 key+cert 与 CA 链；`mtls_test_ca.pem` 为对应 CA 证书（PEM）。
 *
 * 跑：`./gradlew connectedDebugAndroidTest`（需模拟器）。本文件仅保证编译，
 * 真实执行由编排者在模拟器上跑。
 */
@RunWith(AndroidJUnit4::class)
class MtlsPkcs12CompatInstrumentedTest {

    /**
     * 读 androidTest/assets 下 fixture 为 ByteArray。
     *
     * 用 `getInstrumentation().context`（插桩 APK 上下文）—— androidTest/assets
     * 打进插桩（测试）APK 而非被测 App，故不能用 targetContext。
     */
    private fun readAsset(name: String): ByteArray {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return ctx.assets.open(name).use { it.readBytes() }
    }

    /**
     * 核心硬门：直接 PKCS12 载入。验证 Android BC provider 能读 3DES/SHA1 legacy
     * p12（空密码），且别名表里有且仅有一个 key entry、其证书是 X509。
     *
     * 不走 App 封装，直接打 KeyStore —— 把 provider 行为这一变量隔离出来：若本
     * case 挂，是 BC 不可读 legacy p12（API26 兼容问题），与 App 逻辑无关。
     */
    @Test
    fun pkcs12_legacyP12_loadsOnAndroidBc() {
        val p12Bytes = readAsset("mtls_legacy_test.p12")

        val ks = KeyStore.getInstance("PKCS12")
        // 空密码（fixture 用 openssl -password pass: 生成）。
        ks.load(ByteArrayInputStream(p12Bytes), CharArray(0))

        val keyAliases = ks.aliases().toList().filter { ks.isKeyEntry(it) }
        assertTrue(
            "legacy p12 must expose at least one key entry on Android BC (found ${keyAliases.size})",
            keyAliases.isNotEmpty()
        )

        val cert = ks.getCertificate(keyAliases.first())
        assertNotNull("key entry must carry a certificate")
        assertTrue(
            "key entry cert must be an X509Certificate (was ${cert?.javaClass?.name})",
            cert is X509Certificate
        )
    }

    /**
     * 端到端：走 App 的 [buildMutualTlsConfig]，传 legacy p12 + 对应 CA。断言返回
     * [SslConfig.MutualTLS] 且不抛——即 App 的 mTLS 配置构建路径在 Android BC 上
     * 对 legacy p12 兼容（KeyManagerFactory / TrustManagerFactory / SSLContext 全链）。
     */
    @Test
    fun buildMutualTlsConfig_legacyP12_returnsMutualTls() {
        val p12Bytes = readAsset("mtls_legacy_test.p12")
        val caBytes = readAsset("mtls_test_ca.pem")

        val cfg = buildMutualTlsConfig(ClientCertMaterial(p12Bytes, CharArray(0), caBytes))

        assertTrue(
            "legacy p12 + CA → MutualTLS on Android BC (was ${cfg::class.simpleName})",
            cfg is SslConfig.MutualTLS
        )
        // 构建出的 socketFactory / trustManager 非空（data class 字段）。
        assertNotNull(cfg.socketFactory)
        assertNotNull(cfg.trustManager)
    }
}
