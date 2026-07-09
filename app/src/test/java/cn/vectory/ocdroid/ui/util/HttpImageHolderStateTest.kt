package cn.vectory.ocdroid.ui.util

import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.data.repository.http.SslConfigFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * R18 Phase 5++ / §2.6: the testable (non-Bitmap, non-OkHttp-execution) surface
 * of [HttpImageHolder]. Coverage gap before this file: 2/62 methods, 79/719
 * instructions (mostly driven indirectly by HostProfileControllerTest via
 * updateSsl; resetTestState / onLowMemory / lastUpdateSslMode /
 * prefetch-no-op-when-cached / diskName-hash-stability were uncovered).
 *
 * §2.6: [HttpImageHolder.updateSsl] 现接收 [SslConfig]（不再是 allowInsecure
 * Boolean）；测试钩子字段由 `lastUpdateSslAllowInsecure: Boolean?` 改为
 * `lastUpdateSslMode: String?`（{"SYSTEM","TRUST_ALL","MUTUAL_TLS"}）。
 *
 * Bitmap-touching paths (downloadAndCache / loadFromDiskIntoMemory /
 * putBitmap / load @Composable) are exercised under instrumentation; here we
 * cover the pure state-machine helpers + the public surface that does NOT
 * touch Android Bitmap.
 */
class HttpImageHolderStateTest {

    private val factory = SslConfigFactory()

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
    fun `updateSsl with a different config triggers a rebuild and records the call`() {
        // Flip from default SystemDefault → TrustAll; the rebuild path runs.
        HttpImageHolder.updateSsl(factory.sslConfigFor(allowInsecure = true))

        assertEquals("TRUST_ALL", HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `updateSsl is idempotent when called twice with the same config instance`() {
        val trustAll = factory.sslConfigFor(allowInsecure = true)
        HttpImageHolder.updateSsl(trustAll)
        HttpImageHolder.updateSsl(trustAll)

        assertEquals("TRUST_ALL", HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `updateSsl can flip back to SYSTEM after going TRUST_ALL`() {
        HttpImageHolder.updateSsl(factory.sslConfigFor(allowInsecure = true))
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
