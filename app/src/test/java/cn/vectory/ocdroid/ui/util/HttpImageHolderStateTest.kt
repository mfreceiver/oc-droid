package cn.vectory.ocdroid.ui.util

import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.data.repository.http.SslConfigFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * R18 Phase 5++ / §2.6: the testable (non-Bitmap, non-OkHttp-
 * execution) surface of [HttpImageHolder].
 *
 * L7: TOFU removed; [HttpImageHolder.updateSsl] receives a resolved [SslConfig]
 * (SystemDefault / TrustAll / MutualTLS). The test hook field
 * `lastUpdateSslMode: String?` reports values from
 * {"SYSTEM","TRUST_ALL","MUTUAL_TLS"}.
 */
class HttpImageHolderStateTest {

    private val factory = SslConfigFactory()

    @Before
    fun resetState() {
        HttpImageHolder.resetTestState()
    }

    @After
    fun cleanup() {
        HttpImageHolder.resetTestState()
    }

    @Test
    fun `resetTestState clears lastUpdateSslMode`() {
        HttpImageHolder.updateSsl(SslConfig.SystemDefault)
        assertEquals("SYSTEM", HttpImageHolder.lastUpdateSslMode)

        HttpImageHolder.resetTestState()

        assertNull(HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `updateSsl records the mode even when the actual rebuild is a no-op`() {
        HttpImageHolder.updateSsl(SslConfig.SystemDefault)

        assertEquals("SYSTEM", HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `updateSsl with a TrustAll config triggers a rebuild and records the call`() {
        factory.configureTrustAll(true)
        val trustAll = factory.sslConfigFor(null)
        assertEquals("factory should produce TrustAll", SslConfig.TrustAll, trustAll)

        HttpImageHolder.updateSsl(trustAll)

        assertEquals("TRUST_ALL", HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `updateSsl is idempotent when called twice with the same config`() {
        HttpImageHolder.updateSsl(SslConfig.TrustAll)
        HttpImageHolder.updateSsl(SslConfig.TrustAll)

        assertEquals("TRUST_ALL", HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `updateSsl can flip back to SYSTEM after going TRUST_ALL`() {
        HttpImageHolder.updateSsl(SslConfig.TrustAll)
        HttpImageHolder.updateSsl(SslConfig.SystemDefault)

        assertEquals("SYSTEM", HttpImageHolder.lastUpdateSslMode)
    }

    @Test
    fun `onLowMemory does not throw`() {
        HttpImageHolder.onLowMemory()
    }
}
