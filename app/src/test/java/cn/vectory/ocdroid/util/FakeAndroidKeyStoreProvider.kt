package cn.vectory.ocdroid.util

import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * R-21 测试基础设施：把 `AndroidKeyStore` 这个 provider 在 JVM 上"伪造"出来。
 *
 * 背景：[androidx.security.crypto.MasterKey] / [androidx.security.crypto.EncryptedSharedPreferences]
 * 走 Android 系统的 `AndroidKeyStore`（硬件/TEE 支撑），该 provider 只存在于真机。
 * Robolectric 4.13 **不**注册 `AndroidKeyStore` provider，导致
 * `KeyStore.getInstance("AndroidKeyStore")` 直接抛 `KeyStoreException: AndroidKeyStore
 * not found`，`SettingsManager` 连构造都过不了。
 *
 * 这里注册一个名为 `"AndroidKeyStore"` 的 [Provider]，提供：
 *  - `KeyStore.AndroidKeyStore` → 进程内存的 KeyStore（alias → key），足以让
 *    `MasterKeys.keyExists` / `.containsAlias` 工作。
 *  - `KeyGenerator.AES` → 生成真的 256-bit AES [SecretKey]，忽略 Android 专属的
 *    `KeyGenParameterSpec`（JVM 无此类，但 Robolectric 里 android.jar stub 可加载，
 *    我们把它当作通用 `AlgorithmParameterSpec` 接住即丢）。
 *
 * 生成的密钥是**纯软件**（非 TEE），但 Tink 的 envelope encryption 只要求主密钥能
 * AEAD 加解密 —— 软件 AES 完全够用。这让整个 EncryptedSharedPreferences 管线能在
 * unit test 里跑通，从而能真正测试 SettingsManager 的 round-trip / clearAllLocalData。
 *
 * 仅用于 test classpath，**不**进生产构建（位于 `app/src/test/`）。
 */
class FakeAndroidKeyStoreProvider :
    Provider("AndroidKeyStore", 1.0, "Test stub of AndroidKeyStore for Robolectric/JVM") {
    init {
        // KeyStore 服务：把 type "AndroidKeyStore" 映射到内存实现。
        put("KeyStore.AndroidKeyStore", InMemoryKeyStoreSpi::class.java.name)
        // 对称密钥生成：MasterKey 的 AES256_GCM scheme 用 KeyGenerator.getInstance("AES", "AndroidKeyStore")。
        put("KeyGenerator.AES", FakeAesKeyGeneratorSpi::class.java.name)
    }

    companion object {
        /** 安装 stub provider（幂等）。在每个需要 SettingsManager 的测试 @Before 里调一次。 */
        fun install() {
            java.security.Security.removeProvider("AndroidKeyStore")
            java.security.Security.insertProviderAt(FakeAndroidKeyStoreProvider(), 1)
        }
    }
}

/**
 * 内存 KeyStore：alias → key。线程安全。`load(null)` 清空（匹配 AndroidKeyStore 约定：
 * 传入 null InputStream 即初始化空 store）。
 */
class InMemoryKeyStoreSpi : KeyStoreSpi() {
    private val entries: MutableMap<String, Key> =
        java.util.concurrent.ConcurrentHashMap()

    override fun engineIsKeyEntry(alias: String): Boolean = entries.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCreationDate(alias: String): Date = Date(0)
    override fun engineGetKey(alias: String, password: CharArray?): Key? = entries[alias]
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineSetKeyEntry(
        alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?
    ) { entries[alias] = key }
    override fun engineSetKeyEntry(
        alias: String, key: ByteArray, chain: Array<out Certificate>?
    ) { /* MasterKey 走 SecretKey 重载，不会进 byte[] 重载。*/ }
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) {}
    override fun engineDeleteEntry(alias: String) { entries.remove(alias) }
    override fun engineAliases(): Enumeration<String> =
        Collections.enumeration(entries.keys.toSet())
    override fun engineSize(): Int = entries.size
    override fun engineContainsAlias(alias: String): Boolean = entries.containsKey(alias)
    override fun engineGetCertificateAlias(cert: Certificate): String? = null
    override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) {
        // AndroidKeyStore 约定：load(null, null) → 空的、未持久化的 keystore。
        if (stream == null) entries.clear()
    }
    override fun engineStore(stream: java.io.OutputStream, password: CharArray?) {
        // no-op：内存 store 不持久化。
    }
}

/**
 * AES-256 key generator。`engineInit(spec)` 接住任意 `AlgorithmParameterSpec`
 * （含 Android 的 `KeyGenParameterSpec` stub，best-effort 读 keySize，读不到用默认 256）。
 */
class FakeAesKeyGeneratorSpi : KeyGeneratorSpi() {
    private var keySizeBits = 256

    override fun engineInit(random: SecureRandom) {}
    override fun engineInit(keySize: Int, random: SecureRandom) { keySizeBits = keySize }
    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom) {
        // Android KeyGenParameterSpec 有 getKeySize()；反射 best-effort 读，失败保留默认。
        runCatching {
            val m = params?.javaClass?.getMethod("getKeySize")
            if (m != null) keySizeBits = m.invoke(params) as Int
        }
    }
    override fun engineGenerateKey(): SecretKey {
        val keyBytes = ByteArray(keySizeBits / 8).also { SecureRandom().nextBytes(it) }
        return SecretKeySpec(keyBytes, "AES")
    }
}
