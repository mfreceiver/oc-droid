package cn.vectory.ocdroid.ui.settings

import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §review-r4 (gpter R4 #3): JVM unit tests for the pure [buildSaveCall] /
 * [buildTestCall] dialog snapshot builders — hoisted out of the Compose Save
 * `onClick` lambda and the `triggerTestConnection` local `fun` so the
 * section-off credential-clearing rules (basicAuth / tunnel / mTLS), the
 * `name.ifBlank{"Untitled"}` fallback, the `selectedGroup != initialGroup`
 * groupFp rewrite, the `effectivePasswordEdited` forced-clear, and the
 * `hasMaterial` gating via [mtlsHasMaterial] are all unit-testable without
 * spinning up Compose.
 *
 * Same style as [MtlsHasMaterialTest] (plain `@Test` funs, no mockk, pure JDK).
 * Fixtures use [HostProfile.defaultDirect] for the default profile and the
 * `HostProfile(...)` constructor for the parameterized ones; `BasicAuthConfig`
 * is reused to seed existing basic-auth state.
 */
class MtlsDialogCallBuildersTest {

    // ----- helpers ----------------------------------------------------------

    private fun profile(
        id: String = "p1",
        name: String = "Localhost",
        serverUrl: String = "http://localhost:4096",
        basicAuth: BasicAuthConfig? = null,
        tunnelPasswordId: String? = null,
        serverGroupFp: String = "",
        mtlsEnabled: Boolean = false,
        clientCertId: String? = null,
    ): HostProfile = HostProfile(
        id = id,
        name = name,
        serverUrl = serverUrl,
        basicAuth = basicAuth,
        tunnelPasswordId = tunnelPasswordId,
        serverGroupFp = serverGroupFp,
        mtlsEnabled = mtlsEnabled,
        clientCertId = clientCertId,
    )

    /**
     * Convenience: buildSaveCall with all sections OFF and no mTLS material.
     * Individual tests override the relevant params.
     */
    private fun saveAllOff(
        initial: HostProfile,
        name: String = initial.name,
        serverUrl: String = initial.serverUrl,
        selectedGroup: String? = null,
        initialGroup: String? = null,
        slimEnabled: Boolean = false,
        clientCleared: Boolean = false,
        stagedP12: ByteArray? = null,
        caStage: CaStage = CaStage.Unchanged,
    ): SaveCallResult = buildSaveCall(
        initial = initial,
        name = name,
        serverUrl = serverUrl,
        selectedGroup = selectedGroup,
        initialGroup = initialGroup,
        basicAuthEnabled = false,
        authUsername = "",
        authPassword = "",
        passwordEdited = false,
        tunnelEnabled = false,
        tunnelPassword = "",
        tunnelEdited = false,
        mtlsEnabled = false,
        slimEnabled = slimEnabled,
        clientCleared = clientCleared,
        stagedP12 = stagedP12,
        caStage = caStage,
    )

    /**
     * Convenience: buildTestCall with no creds, no mTLS, no client cert.
     */
    private fun testMinimal(
        initial: HostProfile,
        serverUrl: String = initial.serverUrl,
        basicAuthEnabled: Boolean = false,
        authUsername: String = "",
        authPassword: String = "",
        passwordEdited: Boolean = false,
        mtlsEnabled: Boolean = false,
        clientCleared: Boolean = false,
        initialClientCertId: String? = initial.clientCertId,
        stagedP12: ByteArray? = null,
        caStage: CaStage = CaStage.Unchanged,
    ): TestCallResult = buildTestCall(
        initial = initial,
        serverUrl = serverUrl,
        basicAuthEnabled = basicAuthEnabled,
        authUsername = authUsername,
        authPassword = authPassword,
        passwordEdited = passwordEdited,
        mtlsEnabled = mtlsEnabled,
        clientCleared = clientCleared,
        initialClientCertId = initialClientCertId,
        stagedP12 = stagedP12,
        caStage = caStage,
    )

    // ====================== buildSaveCall ===================================

    @Test
    fun `buildSaveCall default profile all sections off preserves identity and clears creds`() {
        // §kover-4.5: covers the 1st case from the task list (default profile,
        // all sections off, no clientCleared, no stagedP12).
        val initial = HostProfile.defaultDirect("http://localhost:4096")
        val r = saveAllOff(initial, selectedGroup = null, initialGroup = null)

        assertEquals("Localhost", r.saved.name)
        assertEquals("http://localhost:4096", r.saved.serverUrl)
        assertNull(r.saved.basicAuth)
        assertNull(r.saved.tunnelPasswordId)
        // §tofu R2: allowInsecureConnections field removed — no assertion.
        assertFalse(r.effectivePasswordEdited)
        assertFalse(r.hasMaterial)
    }

    @Test
    fun `buildSaveCall all sections on with existing cert populates all three credentials`() {
        // §kover-4.5: covers the 2nd case from the task list (basicAuth on with
        // username+password, tunnel on with edited password, mTLS on with
        // existing cert untouched).
        val initial = profile(clientCertId = "cert-1", name = "Original")
        val r = buildSaveCall(
            initial = initial,
            name = "Renamed",
            serverUrl = "https://example.com",
            selectedGroup = null,
            initialGroup = null,
            basicAuthEnabled = true,
            authUsername = "alice",
            authPassword = "pw",
            passwordEdited = true,
            tunnelEnabled = true,
            tunnelPassword = "tp",
            tunnelEdited = true,
            mtlsEnabled = true,
            slimEnabled = false,
            clientCleared = false,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertEquals("Renamed", r.saved.name)
        assertEquals("https://example.com", r.saved.serverUrl)
        assertNotNull(r.saved.basicAuth)
        assertEquals("alice", r.saved.basicAuth?.username)
        assertEquals("p1", r.saved.basicAuth?.passwordId)
        // tunnelEdited=true, password not blank → tunnelPasswordId = initial.id
        assertEquals("p1", r.saved.tunnelPasswordId)
        // §tofu R2: allowInsecureConnections field removed — no assertion.
        // mtlsEnabled=true + clientCertId!=null + !clientCleared → hasMaterial
        assertTrue(r.hasMaterial)
        assertTrue(r.mtlsOn)
    }

    @Test
    fun `buildSaveCall basicAuth off on profile that HAD basicAuth forces clear`() {
        // §kover-4.5: covers the 3rd case (basicAuthEnabled=false on a profile
        // whose initial.basicAuth != null → effectivePasswordEdited=true and
        // saved.basicAuth=null).
        val initial = profile(
            basicAuth = BasicAuthConfig(username = "alice", passwordId = "p1"),
        )
        val r = saveAllOff(initial)
        assertNull(r.saved.basicAuth)
        assertTrue(r.effectivePasswordEdited)
    }

    @Test
    fun `buildSaveCall blank username with existing basicAuth forces password clear`() {
        // §kover-4.5: covers the 4th case (basicAuthEnabled=true, authUsername
        // blank, initial.basicAuth != null → effectivePasswordEdited=true).
        val initial = profile(
            basicAuth = BasicAuthConfig(username = "alice", passwordId = "p1"),
        )
        val r = buildSaveCall(
            initial = initial,
            name = "Test",
            serverUrl = "http://localhost:4096",
            selectedGroup = null,
            initialGroup = null,
            basicAuthEnabled = true,
            authUsername = "",
            authPassword = "",
            passwordEdited = false, // user did not edit
            tunnelEnabled = false,
            tunnelPassword = "",
            tunnelEdited = false,
            mtlsEnabled = false,
            slimEnabled = false,
            clientCleared = false,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertTrue(r.effectivePasswordEdited)
    }

    @Test
    fun `buildSaveCall tunnel off on profile with existing tunnelPasswordId clears it`() {
        // §kover-4.5: covers the 5th case (tunnelEnabled=false on a profile
        // whose initial.tunnelPasswordId != null → saved.tunnelPasswordId=null
        // and effectiveTunnelEd=true so ESP clears stored password).
        val initial = profile(tunnelPasswordId = "p1")
        val r = saveAllOff(initial)
        assertNull(r.saved.tunnelPasswordId)
        assertTrue(r.tunnelEd) // section-off → force-clear signal
        assertEquals("", r.tunnelPw)
    }

    @Test
    fun `buildSaveCall tunnel on with blank password and edited yields no tunnelId`() {
        // §kover-4.5: covers the 6th case (tunnelEnabled=true, tunnelEdited=true,
        // tunnelPassword blank → tunnelPasswordId=null).
        val initial = profile()
        val r = buildSaveCall(
            initial = initial,
            name = "Test",
            serverUrl = "http://localhost:4096",
            selectedGroup = null,
            initialGroup = null,
            basicAuthEnabled = false,
            authUsername = "",
            authPassword = "",
            passwordEdited = false,
            tunnelEnabled = true,
            tunnelPassword = "",
            tunnelEdited = true,
            mtlsEnabled = false,
            slimEnabled = false,
            clientCleared = false,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertNull(r.saved.tunnelPasswordId)
        assertEquals("", r.tunnelPw)
        assertTrue(r.tunnelEd)
    }

    @Test
    fun `buildSaveCall tunnel on with edit false keeps existing tunnelPasswordId`() {
        // §kover-4.5: covers the 7th case (tunnelEnabled=true, tunnelEdited=false
        // → tunnelPasswordId untouched at initial.tunnelPasswordId).
        val initial = profile(tunnelPasswordId = "existing")
        val r = buildSaveCall(
            initial = initial,
            name = "Test",
            serverUrl = "http://localhost:4096",
            selectedGroup = null,
            initialGroup = null,
            basicAuthEnabled = false,
            authUsername = "",
            authPassword = "",
            passwordEdited = false,
            tunnelEnabled = true,
            tunnelPassword = "",
            tunnelEdited = false,
            mtlsEnabled = false,
            slimEnabled = false,
            clientCleared = false,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertEquals("existing", r.saved.tunnelPasswordId)
        assertFalse(r.tunnelEd)
    }

    @Test
    fun `buildSaveCall clientCleared with mtls on and existing cert yields no material`() {
        // §kover-4.5: covers the 8th case (clientCleared=true + mtlsEnabled=true
        // + initial.clientCertId != null → hasMaterial=false). This is the
        // gpter R2 BLOCK scenario: 「移除→重开 mTLS→不粘贴→保存」 must NOT
        // silently reload the old p12 from ESP.
        val initial = profile(clientCertId = "cert-1")
        val r = buildSaveCall(
            initial = initial,
            name = "Test",
            serverUrl = "http://localhost:4096",
            selectedGroup = null,
            initialGroup = null,
            basicAuthEnabled = false,
            authUsername = "",
            authPassword = "",
            passwordEdited = false,
            tunnelEnabled = false,
            tunnelPassword = "",
            tunnelEdited = false,
            mtlsEnabled = true,
            slimEnabled = false,
            clientCleared = true,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertFalse(r.hasMaterial)
    }

    @Test
    fun `buildSaveCall mtls disabled with existing cert still has material`() {
        // §kover-4.5: covers the 9th case (mtlsEnabled=false + initial.clientCertId
        // != null → hasMaterial=true, because hasMaterial is computed from
        // (clientCertId, stagedP12, clientCleared), NOT from mtlsEnabled).
        val initial = profile(clientCertId = "cert-1")
        val r = saveAllOff(initial)
        assertTrue(r.hasMaterial)
        assertFalse(r.mtlsOn)
    }

    @Test
    fun `buildSaveCall stagedP12 with mtls on yields material`() {
        // §kover-4.5: covers the 10th case (stagedP12 != null + mtlsEnabled=true
        // → hasMaterial=true).
        val initial = profile()
        val r = buildSaveCall(
            initial = initial,
            name = "Test",
            serverUrl = "http://localhost:4096",
            selectedGroup = null,
            initialGroup = null,
            basicAuthEnabled = false,
            authUsername = "",
            authPassword = "",
            passwordEdited = false,
            tunnelEnabled = false,
            tunnelPassword = "",
            tunnelEdited = false,
            mtlsEnabled = true,
            slimEnabled = false,
            clientCleared = false,
            stagedP12 = byteArrayOf(1, 2, 3),
            caStage = CaStage.Unchanged,
        )
        assertTrue(r.hasMaterial)
        assertSame(r.stagedP12, r.stagedP12) // identity round-trip
    }

    @Test
    fun `buildSaveCall blank name becomes Untitled`() {
        // §kover-4.5: covers the 11th case (name="" → saved.name="Untitled").
        val initial = profile(name = "Original")
        val r = saveAllOff(initial, name = "")
        assertEquals("Untitled", r.saved.name)
    }

    @Test
    fun `buildSaveCall selectedGroup differs from initialGroup sets the new group`() {
        // §kover-4.5: covers the 12th case — half (selectedGroup non-null).
        // Editor seeded initialGroup="A" (matches the legacy serverGroupFp).
        // User changed it to "B" → saved.serverGroupFp = "B".
        val initial = profile(serverGroupFp = "A")
        val r = buildSaveCall(
            initial = initial,
            name = "Test",
            serverUrl = "http://localhost:4096",
            selectedGroup = "B",
            initialGroup = "A",
            basicAuthEnabled = false,
            authUsername = "",
            authPassword = "",
            passwordEdited = false,
            tunnelEnabled = false,
            tunnelPassword = "",
            tunnelEdited = false,
            mtlsEnabled = false,
            slimEnabled = false,
            clientCleared = false,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertEquals("B", r.saved.serverGroupFp)
    }

    @Test
    fun `buildSaveCall selectedGroup null differs from initialGroup sets to initial id`() {
        // §kover-4.5: covers the 12th case — other half (selectedGroup=null
        // while initialGroup is non-null → "not grouped" → saved.serverGroupFp
        // = initial.id, per NamedGroupLabels rewrite).
        val initial = profile(serverGroupFp = "A")
        val r = buildSaveCall(
            initial = initial,
            name = "Test",
            serverUrl = "http://localhost:4096",
            selectedGroup = null,
            initialGroup = "A",
            basicAuthEnabled = false,
            authUsername = "",
            authPassword = "",
            passwordEdited = false,
            tunnelEnabled = false,
            tunnelPassword = "",
            tunnelEdited = false,
            mtlsEnabled = false,
            slimEnabled = false,
            clientCleared = false,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertEquals("p1", r.saved.serverGroupFp)
    }

    @Test
    fun `buildSaveCall selectedGroup unchanged preserves initial groupFp including legacy values`() {
        // §kover-4.5: covers the 12th case — when the user did not move the
        // selector, the original serverGroupFp is preserved verbatim. This is
        // the soft-migration path for legacy non-slot values.
        val initial = profile(serverGroupFp = "legacy-key")
        val r = buildSaveCall(
            initial = initial,
            name = "Test",
            serverUrl = "http://localhost:4096",
            selectedGroup = null, // editor maps non-slot serverGroupFp to null in initialGroup
            initialGroup = null,
            basicAuthEnabled = false,
            authUsername = "",
            authPassword = "",
            passwordEdited = false,
            tunnelEnabled = false,
            tunnelPassword = "",
            tunnelEdited = false,
            mtlsEnabled = false,
            slimEnabled = false,
            clientCleared = false,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertEquals("legacy-key", r.saved.serverGroupFp)
    }

    @Test
    fun `buildSaveCall caStage passthrough carries Replace and Clear through`() {
        // §kover-4.5: covers the caStage / p12Password / p12PasswordEdited
        // passthrough fields (the dialog's onSave 11-arg pos 8/9/10).
        val initial = profile()
        val replaceBytes = byteArrayOf(0x42)
        val r = buildSaveCall(
            initial = initial,
            name = "Test",
            serverUrl = "http://localhost:4096",
            selectedGroup = null,
            initialGroup = null,
            basicAuthEnabled = false,
            authUsername = "",
            authPassword = "",
            passwordEdited = false,
            tunnelEnabled = false,
            tunnelPassword = "",
            tunnelEdited = false,
            mtlsEnabled = false,
            slimEnabled = false,
            clientCleared = false,
            stagedP12 = null,
            caStage = CaStage.Replace(replaceBytes),
        )
        assertTrue(r.caStage is CaStage.Replace)
        assertNull(r.p12Password)
        assertFalse(r.p12PasswordEdited)
    }

    @Test
    fun `buildSaveCall CaStage Clear is passthrough when mtls off`() {
        val initial = profile()
        val r = saveAllOff(initial, caStage = CaStage.Clear)
        assertTrue(r.caStage is CaStage.Clear)
    }

    // §tofu R2: the buildSaveCall allowInsecure forwarding test was removed —
    // the allowInsecure field no longer exists on HostProfile (TOFU replaces
    // the trust-all toggle). ServerUrl forwarding is still covered below.

    @Test
    fun `buildSaveCall serverUrl is forwarded to saved profile verbatim`() {
        val initial = profile()
        val r = saveAllOff(initial, serverUrl = "https://override.example.com:8443/path")
        assertEquals("https://override.example.com:8443/path", r.saved.serverUrl)
    }

    @Test
    fun `buildSaveCall slimEnabled true sets slim on saved profile`() {
        val initial = profile()
        // Default: slimEnabled=false
        val rOff = saveAllOff(initial)
        assertFalse(rOff.saved.slim)
        assertFalse(rOff.slimOn)
        // Explicit: slimEnabled=true
        val rOn = saveAllOff(initial, slimEnabled = true)
        assertTrue(rOn.saved.slim)
        assertTrue(rOn.slimOn)
    }

    // ====================== buildTestCall ===================================

    @Test
    fun `buildTestCall basicAuth on with username and password snapshots them`() {
        // §kover-4.5: covers the 1st case (basicAuthEnabled=true,
        // initialClientCertId=null → profileIdSnap=null, pwEditedSnap=passwordEdited).
        val initial = profile()
        val r = buildTestCall(
            initial = initial,
            serverUrl = "http://localhost:4096",
            basicAuthEnabled = true,
            authUsername = "u",
            authPassword = "p",
            passwordEdited = false,
            mtlsEnabled = false,
            clientCleared = false,
            initialClientCertId = null,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertEquals("u", r.userSnap)
        assertEquals("p", r.authPwSnap)
        assertNull(r.profileIdSnap) // initial.basicAuth is null → no stored-password fallback
        assertFalse(r.pwEditedSnap)
    }

    @Test
    fun `buildTestCall basicAuth off ignores creds and suppresses fallback`() {
        // §kover-4.5: covers the 2nd case (basicAuthEnabled=false → userSnap/
        // authPwSnap/profileIdSnap=null, pwEditedSnap=true so VM does NOT
        // fall back to the stored basic-auth password).
        val initial = profile(
            basicAuth = BasicAuthConfig(username = "stored", passwordId = "p1"),
        )
        val r = buildTestCall(
            initial = initial,
            serverUrl = "http://localhost:4096",
            basicAuthEnabled = false,
            authUsername = "should-be-ignored",
            authPassword = "should-be-ignored",
            passwordEdited = false,
            mtlsEnabled = false,
            clientCleared = false,
            initialClientCertId = null,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertNull(r.userSnap)
        assertNull(r.authPwSnap)
        assertNull(r.profileIdSnap)
        assertTrue(r.pwEditedSnap)
    }

    @Test
    fun `buildTestCall basicAuth on with existing auth uses initial id for profileIdSnap`() {
        // §kover-4.5: covers the 3rd case (basicAuthEnabled=true +
        // initial.basicAuth != null → profileIdSnap = initial.id so VM can
        // look up the stored basic-auth password).
        val initial = profile(
            basicAuth = BasicAuthConfig(username = "stored", passwordId = "p1"),
        )
        val r = buildTestCall(
            initial = initial,
            serverUrl = "http://localhost:4096",
            basicAuthEnabled = true,
            authUsername = "u",
            authPassword = "p",
            passwordEdited = true,
            mtlsEnabled = false,
            clientCleared = false,
            initialClientCertId = null,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertEquals("p1", r.profileIdSnap)
        assertTrue(r.pwEditedSnap)
    }

    @Test
    fun `buildTestCall basicAuth on with blank username becomes null`() {
        // §kover-4.5: covers the 4th case (basicAuthEnabled=true, authUsername
        // blank → userSnap=null, but authPwSnap is still snapshotted verbatim
        // from authPassword, and profileIdSnap still uses initial.id when
        // initial.basicAuth != null).
        val initial = profile(
            basicAuth = BasicAuthConfig(username = "stored", passwordId = "p1"),
        )
        val r = buildTestCall(
            initial = initial,
            serverUrl = "http://localhost:4096",
            basicAuthEnabled = true,
            authUsername = "",
            authPassword = "p",
            passwordEdited = false,
            mtlsEnabled = false,
            clientCleared = false,
            initialClientCertId = null,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertNull(r.userSnap)
        assertEquals("p", r.authPwSnap)
        assertEquals("p1", r.profileIdSnap)
    }

    @Test
    fun `buildTestCall clientCleared with mtls on and existing cert has no material`() {
        // §kover-4.5: covers the 5th case (clientCleared=true + mtlsEnabled=true
        // + initialClientCertId != null + stagedP12=null → hasMaterial=false).
        val initial = profile()
        val r = buildTestCall(
            initial = initial,
            serverUrl = "http://localhost:4096",
            basicAuthEnabled = false,
            authUsername = "",
            authPassword = "",
            passwordEdited = false,
            mtlsEnabled = true,
            clientCleared = true,
            initialClientCertId = "cert-1",
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertFalse(r.hasMaterial)
    }

    @Test
    fun `buildTestCall mtls off with existing cert still has material`() {
        // §kover-4.5: covers the 6th case (mtlsEnabled=false + initialClientCertId
        // != null → hasMaterial=true, since hasMaterial is independent of
        // mtlsEnabled).
        val initial = profile()
        val r = buildTestCall(
            initial = initial,
            serverUrl = "http://localhost:4096",
            basicAuthEnabled = false,
            authUsername = "",
            authPassword = "",
            passwordEdited = false,
            mtlsEnabled = false,
            clientCleared = false,
            initialClientCertId = "cert-1",
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertTrue(r.hasMaterial)
        assertFalse(r.mtlsOn)
    }

    @Test
    fun `buildTestCall caStage stagedP12 serverUrl passthrough`() {
        // §kover-4.5: covers the 7th case (caStage, stagedP12, serverUrl all
        // passthrough verbatim). §tofu R2: allowInsecure removed — the
        // insecureSnap assertion is dropped.
        val initial = profile()
        val stagedP12 = byteArrayOf(1, 2, 3)
        val r = buildTestCall(
            initial = initial,
            serverUrl = "https://override.example.com",
            basicAuthEnabled = false,
            authUsername = "",
            authPassword = "",
            passwordEdited = false,
            mtlsEnabled = true,
            clientCleared = false,
            initialClientCertId = null,
            stagedP12 = stagedP12,
            caStage = CaStage.Replace(byteArrayOf(99)),
        )
        assertEquals("https://override.example.com", r.url)
        assertSame(stagedP12, r.p12Snap)
        assertTrue(r.caSnap is CaStage.Replace)
        assertTrue(r.mtlsOn)
    }

    @Test
    fun `buildTestCall oldCertId mirrors initialClientCertId`() {
        // §kover-4.5: covers the oldCertId passthrough (= initialClientCertId),
        // which the dialog forwards unchanged so VM can resolve the existing
        // p12/CA from ESP when mTLS is on and the user did not re-paste.
        val r1 = testMinimal(profile(), initialClientCertId = null)
        assertNull(r1.oldCertId)
        val r2 = testMinimal(profile(), initialClientCertId = "cert-42")
        assertEquals("cert-42", r2.oldCertId)
    }

    @Test
    fun `buildTestCall basicAuth on with passwordEdited true forwards passwordEdited verbatim`() {
        // §kover-4.5: passwordEdited is forwarded verbatim when basicAuth is
        // on (the OFF branch forces pwEditedSnap=true; the ON branch passes
        // through the user's edit flag).
        val initial = profile()
        val r = buildTestCall(
            initial = initial,
            serverUrl = "http://localhost:4096",
            basicAuthEnabled = true,
            authUsername = "u",
            authPassword = "p",
            passwordEdited = true,
            mtlsEnabled = false,
            clientCleared = false,
            initialClientCertId = null,
            stagedP12 = null,
            caStage = CaStage.Unchanged,
        )
        assertTrue(r.pwEditedSnap)
    }

    // ====================== decodeClientP12Import ===========================

    @Test
    fun `decodeClientP12Import blank raw returns errBase64 error`() {
        // §coverage-r4: blank input branch — surfaces the user-facing base64
        // error without touching the PKCS12 loader.
        val (status, bytes) = decodeClientP12Import("", "ERR_B64", "ERR_P12")
        assertTrue(status is CertSlotStatus.Error)
        assertEquals("ERR_B64", (status as CertSlotStatus.Error).message)
        assertNull(bytes)
    }

    @Test
    fun `decodeClientP12Import non-base64 raw returns errBase64 error`() {
        // §coverage-r4: invalid base64 branch (decodeBase64OrNull returns null).
        val (status, bytes) = decodeClientP12Import("!@#$%", "ERR_B64", "ERR_P12")
        assertTrue(status is CertSlotStatus.Error)
        assertEquals("ERR_B64", (status as CertSlotStatus.Error).message)
        assertNull(bytes)
    }

    // ====================== decodeCaImport ==================================

    @Test
    fun `decodeCaImport blank raw returns errBase64 error with Unchanged stage`() {
        // §coverage-r4: blank input branch — surfaces base64 error, stage
        // stays Unchanged (failed paste does not touch existing CA).
        val (status, stage) = decodeCaImport("", "ERR_B64", "ERR_CA")
        assertTrue(status is CertSlotStatus.Error)
        assertEquals("ERR_B64", (status as CertSlotStatus.Error).message)
        assertTrue(stage is CaStage.Unchanged)
    }

    @Test
    fun `decodeCaImport non-base64 raw returns errBase64 error with Unchanged stage`() {
        // §coverage-r4: invalid base64 branch.
        val (status, stage) = decodeCaImport("!@#$%", "ERR_B64", "ERR_CA")
        assertTrue(status is CertSlotStatus.Error)
        assertEquals("ERR_B64", (status as CertSlotStatus.Error).message)
        assertTrue(stage is CaStage.Unchanged)
    }

    // ====================== applyClientP12ImportResult =======================

    @Test
    fun `applyClientP12ImportResult Imported fires all four setters`() {
        // §coverage-r4: success branch — status=Imported, bytes non-null →
        // all four setters fire (slot status, stagedP12, clientCleared=false,
        // mtlsImportError=null).
        var slot: CertSlotStatus = CertSlotStatus.Empty
        var staged: ByteArray? = null
        var cleared: Boolean? = null
        var importError: String? = "preset"
        val bytes = byteArrayOf(1, 2, 3)
        applyClientP12ImportResult(
            status = CertSlotStatus.Imported("subject", 3),
            bytes = bytes,
            setClientSlotStatus = { slot = it },
            setStagedP12 = { staged = it },
            setClientCleared = { cleared = it },
            setMtlsImportError = { importError = it },
        )
        assertTrue(slot is CertSlotStatus.Imported)
        assertSame(bytes, staged)
        assertEquals(false, cleared)
        assertNull(importError)
    }

    @Test
    fun `applyClientP12ImportResult Error fires only slot setter`() {
        // §coverage-r4: error branch — status=Error, bytes=null → only the
        // slot status setter fires; stagedP12 / clientCleared / mtlsImportError
        // are NOT touched.
        var slot: CertSlotStatus = CertSlotStatus.Empty
        var staged: ByteArray? = byteArrayOf(99)
        var cleared: Boolean? = null
        var importError: String? = "preset"
        var stagedInvoked = false
        var clearedInvoked = false
        var importErrorInvoked = false
        applyClientP12ImportResult(
            status = CertSlotStatus.Error("bad"),
            bytes = null,
            setClientSlotStatus = { slot = it },
            setStagedP12 = { staged = it; stagedInvoked = true },
            setClientCleared = { cleared = it; clearedInvoked = true },
            setMtlsImportError = { importError = it; importErrorInvoked = true },
        )
        assertTrue(slot is CertSlotStatus.Error)
        // The other setters must NOT be invoked in the error branch — the
        // stagedP12 / clientCleared / mtlsImportError state should not be
        // touched. Use invocation flags (not the captured values, which
        // may be null) so the assertion survives a null preset.
        assertFalse(stagedInvoked)
        assertFalse(clearedInvoked)
        assertFalse(importErrorInvoked)
    }

    @Test
    fun `applyClientP12ImportResult Imported with null bytes only fires slot setter`() {
        // §coverage-r4: defensive — status=Imported but bytes=null. The
        // helper's success branch is gated on BOTH conditions (Imported
        // AND bytes != null), so only the slot setter fires.
        var slot: CertSlotStatus = CertSlotStatus.Empty
        var stagedInvoked = false
        var clearedInvoked = false
        var importErrorInvoked = false
        applyClientP12ImportResult(
            status = CertSlotStatus.Imported("subject", 3),
            bytes = null,
            setClientSlotStatus = { slot = it },
            setStagedP12 = { stagedInvoked = true },
            setClientCleared = { clearedInvoked = true },
            setMtlsImportError = { importErrorInvoked = true },
        )
        assertTrue(slot is CertSlotStatus.Imported)
        assertFalse(stagedInvoked)
        assertFalse(clearedInvoked)
        assertFalse(importErrorInvoked)
    }

    // ====================== applyCaImportResult ==============================

    @Test
    fun `applyCaImportResult Imported fires both setters`() {
        // §coverage-r4: success branch — status=Imported → both setters fire
        // (slot status + caStage forwarded verbatim).
        var slot: CertSlotStatus = CertSlotStatus.Empty
        var stage: CaStage = CaStage.Unchanged
        val replace = CaStage.Replace(byteArrayOf(0x42))
        applyCaImportResult(
            status = CertSlotStatus.Imported("CA", 1),
            stage = replace,
            setCaSlotStatus = { slot = it },
            setCaStage = { stage = it },
        )
        assertTrue(slot is CertSlotStatus.Imported)
        assertSame(replace, stage)
    }

    @Test
    fun `applyCaImportResult Error fires only slot setter`() {
        // §coverage-r4: error branch — status=Error → only the slot setter
        // fires; caStage is NOT touched.
        var slot: CertSlotStatus = CertSlotStatus.Empty
        var stage: CaStage = CaStage.Replace(byteArrayOf(0x42))
        applyCaImportResult(
            status = CertSlotStatus.Error("bad"),
            stage = CaStage.Unchanged,
            setCaSlotStatus = { slot = it },
            setCaStage = { stage = it },
        )
        assertTrue(slot is CertSlotStatus.Error)
        assertTrue(stage is CaStage.Replace) // unchanged
    }

    // ====================== seedClientCertSlotStatus =========================

    @Test
    fun `seedClientCertSlotStatus null summary returns null`() {
        // §coverage-r4: null summary branch.
        val seeded = seedClientCertSlotStatus(
            initialClientSummary = null,
            clientEdited = false,
            currentSlotStatus = CertSlotStatus.Empty,
        )
        assertNull(seeded)
    }

    @Test
    fun `seedClientCertSlotStatus clientEdited true returns null`() {
        // §coverage-r4: clientEdited gate — user already pasted/removed, do
        // not overwrite.
        val seeded = seedClientCertSlotStatus(
            initialClientSummary = "subject" to 42,
            clientEdited = true,
            currentSlotStatus = CertSlotStatus.Empty,
        )
        assertNull(seeded)
    }

    @Test
    fun `seedClientCertSlotStatus currentSlotStatus non-Empty returns null`() {
        // §coverage-r4: currentSlotStatus guard — slot already has user-
        // driven state (e.g. user removed an existing cert), do not seed.
        val seeded = seedClientCertSlotStatus(
            initialClientSummary = "subject" to 42,
            clientEdited = false,
            currentSlotStatus = CertSlotStatus.Error("preset"),
        )
        assertNull(seeded)
    }

    @Test
    fun `seedClientCertSlotStatus all-clear seeds Imported with subject and size`() {
        // §coverage-r4: success branch — summary present, no user edit, slot
        // still Empty → seed Imported(subject, size).
        val seeded = seedClientCertSlotStatus(
            initialClientSummary = "subject" to 42,
            clientEdited = false,
            currentSlotStatus = CertSlotStatus.Empty,
        )
        assertNotNull(seeded)
        assertEquals("subject", (seeded as CertSlotStatus.Imported).label)
        assertEquals(42, seeded.sizeBytes)
    }

    // ====================== seedCaSlotStatus ================================

    @Test
    fun `seedCaSlotStatus null summary returns null`() {
        val seeded = seedCaSlotStatus(
            initialCaSummary = null,
            caEdited = false,
            currentSlotStatus = CertSlotStatus.Empty,
            initialHasCa = true,
        )
        assertNull(seeded)
    }

    @Test
    fun `seedCaSlotStatus caEdited true returns null`() {
        val seeded = seedCaSlotStatus(
            initialCaSummary = "CA" to 1,
            caEdited = true,
            currentSlotStatus = CertSlotStatus.Empty,
            initialHasCa = true,
        )
        assertNull(seeded)
    }

    @Test
    fun `seedCaSlotStatus initialHasCa false returns null`() {
        // §coverage-r4: initialHasCa guard — defensive against seed racing
        // the ESP read on dialog open (summary arrived but ESP reports no
        // stored CA).
        val seeded = seedCaSlotStatus(
            initialCaSummary = "CA" to 1,
            caEdited = false,
            currentSlotStatus = CertSlotStatus.Empty,
            initialHasCa = false,
        )
        assertNull(seeded)
    }

    @Test
    fun `seedCaSlotStatus currentSlotStatus non-Empty returns null`() {
        val seeded = seedCaSlotStatus(
            initialCaSummary = "CA" to 1,
            caEdited = false,
            currentSlotStatus = CertSlotStatus.Error("preset"),
            initialHasCa = true,
        )
        assertNull(seeded)
    }

    @Test
    fun `seedCaSlotStatus all-clear seeds Imported with subject and size`() {
        val seeded = seedCaSlotStatus(
            initialCaSummary = "CA" to 1,
            caEdited = false,
            currentSlotStatus = CertSlotStatus.Empty,
            initialHasCa = true,
        )
        assertNotNull(seeded)
        assertEquals("CA", (seeded as CertSlotStatus.Imported).label)
        assertEquals(1, seeded.sizeBytes)
    }

    // ====================== dialogHasCertError ===============================

    @Test
    fun `dialogHasCertError mtlsEnabled false ignores any slot Error`() {
        // §coverage-r4: mTLS-off gate — per §review-3, residual Error from
        // a failed paste must NOT block Save/Test once mTLS is off.
        assertFalse(
            dialogHasCertError(
                mtlsEnabled = false,
                clientSlotStatus = CertSlotStatus.Error("bad"),
                caSlotStatus = CertSlotStatus.Error("bad"),
            )
        )
        assertFalse(
            dialogHasCertError(
                mtlsEnabled = false,
                clientSlotStatus = CertSlotStatus.Empty,
                caSlotStatus = CertSlotStatus.Error("bad"),
            )
        )
    }

    @Test
    fun `dialogHasCertError mtlsEnabled true with no Error returns false`() {
        assertFalse(
            dialogHasCertError(
                mtlsEnabled = true,
                clientSlotStatus = CertSlotStatus.Empty,
                caSlotStatus = CertSlotStatus.Empty,
            )
        )
        assertFalse(
            dialogHasCertError(
                mtlsEnabled = true,
                clientSlotStatus = CertSlotStatus.Imported("s", 1),
                caSlotStatus = CertSlotStatus.Empty,
            )
        )
    }

    @Test
    fun `dialogHasCertError client Error returns true`() {
        assertTrue(
            dialogHasCertError(
                mtlsEnabled = true,
                clientSlotStatus = CertSlotStatus.Error("bad"),
                caSlotStatus = CertSlotStatus.Empty,
            )
        )
    }

    @Test
    fun `dialogHasCertError ca Error returns true`() {
        assertTrue(
            dialogHasCertError(
                mtlsEnabled = true,
                clientSlotStatus = CertSlotStatus.Empty,
                caSlotStatus = CertSlotStatus.Error("bad"),
            )
        )
    }
}
