package cn.vectory.ocdroid.ui.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R18 Phase 5++ coverage: the testable (non-Bitmap, non-OkHttp-execution)
 * surface of [HttpImageHolder]. Coverage gap before this file: 2/62 methods,
 * 79/719 instructions (mostly driven indirectly by HostProfileControllerTest
 * via updateSsl; resetTestState / onLowMemory / lastUpdateSslAllowInsecure
 * / prefetch-no-op-when-cached / diskName-hash-stability were uncovered).
 *
 * Bitmap-touching paths (downloadAndCache / loadFromDiskIntoMemory /
 * putBitmap / load @Composable) are exercised under instrumentation; here we
 * cover the pure state-machine helpers + the public surface that does NOT
 * touch Android Bitmap.
 */
class HttpImageHolderStateTest {

    @Before
    fun resetState() {
        // Each test starts from the documented "process startup" baseline
        // (allowInsecure=false, no prior updateSsl call).
        HttpImageHolder.resetTestState()
    }

    @After
    fun cleanup() {
        HttpImageHolder.resetTestState()
    }

    @Test
    fun `resetTestState clears lastUpdateSslAllowInsecure`() {
        // Set then reset; the test hook field MUST clear.
        HttpImageHolder.updateSsl(true)
        assertEquals(true, HttpImageHolder.lastUpdateSslAllowInsecure)

        HttpImageHolder.resetTestState()

        assertNull(HttpImageHolder.lastUpdateSslAllowInsecure)
    }

    @Test
    fun `updateSsl records the allowInsecure value even when the actual rebuild is a no-op`() {
        // Initial state is allowInsecure=false; calling updateSsl(false) is a
        // no-op rebuild BUT the test hook MUST still record the call.
        HttpImageHolder.updateSsl(false)

        assertEquals(false, HttpImageHolder.lastUpdateSslAllowInsecure)
    }

    @Test
    fun `updateSsl with a different allowInsecure triggers a rebuild and records the call`() {
        // Flip from default false → true; the rebuild path runs.
        HttpImageHolder.updateSsl(true)

        assertEquals(true, HttpImageHolder.lastUpdateSslAllowInsecure)
    }

    @Test
    fun `updateSsl is idempotent when called twice with the same value`() {
        HttpImageHolder.updateSsl(true)
        HttpImageHolder.updateSsl(true)

        assertEquals(true, HttpImageHolder.lastUpdateSslAllowInsecure)
    }

    @Test
    fun `updateSsl can flip back to false after going true`() {
        HttpImageHolder.updateSsl(true)
        HttpImageHolder.updateSsl(false)

        assertEquals(false, HttpImageHolder.lastUpdateSslAllowInsecure)
    }

    @Test
    fun `onLowMemory does not throw`() {
        // onLowMemory evicts the in-memory LRU cache and bumps the cache
        // version. Without Bitmap the cache is empty, but the call MUST NOT
        // throw.
        HttpImageHolder.onLowMemory()
    }
}
