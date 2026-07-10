package cn.vectory.ocdroid.ui.util

import cn.vectory.ocdroid.data.repository.http.InMemoryTofuPinStore
import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.data.repository.http.SslConfigFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * R18 Phase 5++ / §2.6 / §tofu R2: the testable (non-Bitmap, non-OkHttp-
 * execution) surface of [HttpImageHolder]. Coverage gap before this file:
 * 2/62 methods, 79/719 instructions (mostly driven indirectly by
 * HostProfileControllerTest via updateSsl; resetTestState / onLowMemory /
 * lastUpdateSslMode / prefetch-no-op-when-cached / diskName-hash-stability
 * were uncovered).
 *
 * §tofu R2: [HttpImageHolder.updateSsl] receives a resolved [SslConfig]
 * (SystemDefault / TofuPinned / MutualTLS) — never TrustAll anymore (the
 * trust-all downgrade was replaced by TOFU pinning). The test hook field
 * `lastUpdateSslMode: String?` now reports values from
 * {"SYSTEM","TRUST_ALL","MUTUAL_TLS","TOFU_PINNED"}; TrustAll stays in the
 * `when` for back-compat with any future reintroduction but is no longer
 * produced by [SslConfigFactory.sslConfigFor].
 *
 * Bitmap-touching paths (downloadAndCache / loadFromDiskIntoMemory /
 * putBitmap / load @Composable) are exercised under instrumentation; here we
 * cover the pure state-machine helpers + the public surface that does NOT
 * touch Android Bitmap.
 */
class HttpImageHolderStateTest {

    // §tofu R2: SslConfigFactory now requires a TofuPinStore; use the in-memory
    // fake. A pinned host:port makes sslConfigFor return TofuPinned.
    private val tofuStore = InMemoryTofuPinStore()
    private val factory = SslConfigFactory(tofuStore)

    @Before
    fun resetState() {
        // Each test starts from the documented "process startup" baseline
        // (SystemDefault, no prior updateSsl call).
        HttpImageHolder.resetTestState()
    }

    @After
    fun cleanup() {
        HttpImageHolder.resetTestState()
    }

    @Test
    fun `resetTestState clears lastUpdateSslMode`() {
        // Set then reset; the test hook field MUST clear.
        HttpImageHolder.updateSsl(SslConfig.SystemDefault)
        assertEquals("SYSTEM", HttpImageHolder.lastUpdateSslMode)

        HttpImageHolder.resetTestState()

        assertNull(HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `updateSsl records the mode even when the actual rebuild is a no-op`() {
        // Initial state is SystemDefault; calling updateSsl(SystemDefault) is a
        // no-op rebuild BUT the test hook MUST still record the call.
        HttpImageHolder.updateSsl(SslConfig.SystemDefault)

        assertEquals("SYSTEM", HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `updateSsl with a TofuPinned config triggers a rebuild and records the call`() {
        // §tofu R2: plant a session pin → factory returns TofuPinned for that
        // host:port → the image client rebuild records TOFU_PINNED.
        tofuStore.acceptSession("pinned.example:443", "ab".repeat(32))
        val pinned = factory.sslConfigFor("pinned.example:443")
        assertEquals("factory should produce TofuPinned", SslConfig.TofuPinned::class.java, pinned::class.java)

        HttpImageHolder.updateSsl(pinned)

        assertEquals("TOFU_PINNED", HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `updateSsl is idempotent when called twice with the same config instance`() {
        tofuStore.trustPersistent("pinned.example:443", "cd".repeat(32))
        val pinned = factory.sslConfigFor("pinned.example:443")
        HttpImageHolder.updateSsl(pinned)
        HttpImageHolder.updateSsl(pinned)

        assertEquals("TOFU_PINNED", HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `updateSsl can flip back to SYSTEM after going TOFU_PINNED`() {
        tofuStore.acceptSession("pinned.example:443", "ef".repeat(32))
        HttpImageHolder.updateSsl(factory.sslConfigFor("pinned.example:443"))
        HttpImageHolder.updateSsl(SslConfig.SystemDefault)

        assertEquals("SYSTEM", HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `onLowMemory does not throw`() {
        // onLowMemory evicts the in-memory LRU cache and bumps the cache
        // version. Without Bitmap the cache is empty, but the call MUST NOT
        // throw.
        HttpImageHolder.onLowMemory()
    }
}
