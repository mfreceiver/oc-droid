package cn.vectory.ocdroid.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §review-r4 (gpter R4 #2): JVM unit tests for the pure [mtlsHasMaterial]
 * predicate — the clientCleared state-machine core that decides whether the
 * current mTLS edit has usable client-cert material. Extracted from the inline
 * expression that was duplicated in `triggerTestConnection` and the Save
 * `onClick` so the gating rule is unit-testable without spinning up Compose
 * (same pattern as [CacheManagementSectionPureTest] for [CacheRowPresentation]).
 *
 * Locks the five state-machine invariants the dialog Save/Test gating depends
 * on:
 *  - fresh new profile (no cert, no paste) → false;
 *  - existing cert untouched → true;
 *  - existing cert removed (clientCleared) → false;
 *  - existing cert removed then a new paste → true (Clear-signal reset by paste);
 *  - new profile with a fresh paste → true.
 */
class MtlsHasMaterialTest {

    @Test
    fun `fresh new profile has no material`() {
        // clientCertId=null, stagedP12=null, clientCleared=false.
        assertFalse(mtlsHasMaterial(clientCleared = false, clientCertId = null, stagedP12 = null))
    }

    @Test
    fun `existing cert untouched has material`() {
        assertTrue(mtlsHasMaterial(clientCleared = false, clientCertId = "cert-1", stagedP12 = null))
    }

    @Test
    fun `existing cert removed has no material`() {
        // clientCleared=true blocks the ESP-resident p12 from counting — the
        // whole point of the Clear-signal (gpter R2 BLOCK scenario).
        assertFalse(mtlsHasMaterial(clientCleared = true, clientCertId = "cert-1", stagedP12 = null))
    }

    @Test
    fun `removed then new paste has material`() {
        // A successful paste resets clientCleared=false; the freshly staged p12
        // now supplies the material.
        assertTrue(mtlsHasMaterial(clientCleared = false, clientCertId = "cert-1", stagedP12 = byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `new profile with paste has material`() {
        // clientCertId=null but a fresh paste provides the material.
        assertTrue(mtlsHasMaterial(clientCleared = false, clientCertId = null, stagedP12 = byteArrayOf(1, 2, 3)))
    }
}
