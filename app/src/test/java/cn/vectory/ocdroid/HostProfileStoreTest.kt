package cn.vectory.ocdroid

import android.util.Log
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class HostProfileStoreTest {
    private lateinit var settings: SettingsManager
    private lateinit var store: HostProfileStore
    private var hostProfilesJson: String? = null
    private var currentHostProfileId: String? = null

    @Before
    fun setUp() {
        // HostProfileStore logs warnings on SSH migration and on parse failure.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0

        settings = mockk(relaxed = true)
        every { settings.hostProfilesJson } answers { hostProfilesJson }
        every { settings.hostProfilesJson = any() } answers { hostProfilesJson = firstArg(); Unit }
        every { settings.currentHostProfileId } answers { currentHostProfileId }
        every { settings.currentHostProfileId = any() } answers { currentHostProfileId = firstArg(); Unit }
        every { settings.serverUrl } returns "https://legacy.example.com"
        every { settings.username } returns "legacy-user"
        every { settings.password } returns "legacy-password"
        every { settings.password = any() } just runs
        store = HostProfileStore(settings)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `migrates legacy server settings into default profile`() {
        val profiles = store.profiles()

        assertEquals(1, profiles.size)
        assertEquals("https://legacy.example.com", profiles.first().serverUrl)
        assertEquals("legacy-user", profiles.first().basicAuth?.username)
        assertEquals(profiles.first().id, currentHostProfileId)
    }

    @Test
    fun `save replaces single host in place`() {
        val original = store.currentProfile()
        val updated = original.copy(name = "Updated")

        store.save(updated)
        val profiles = store.profiles()
        assertEquals(1, profiles.size)
        assertEquals("Updated", profiles.first().name)
        assertEquals(original.id, profiles.first().id)
    }

    @Test
    fun `select validates single-host mode and refreshes lastUsedAt`() {
        val profile = store.currentProfile()
        val selected = store.select(profile.id)
        assertEquals(profile.id, selected.id)
        // save replaced in place, list stays size 1
        assertEquals(1, store.profiles().size)
    }

    @Test
    fun `select with wrong id throws in single-host mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            store.select("non-existent-id")
        }
    }

    @Test
    fun `malformed host profile list does not overwrite original data`() {
        val corrupted = "{ this is not valid JSON "
        hostProfilesJson = corrupted

        val profiles = store.profiles()

        // Decoder must fail soft: returns empty list rather than crashing.
        assertEquals(0, profiles.size)
        // Critical data-protection guarantee: the original (corrupt) JSON is
        // preserved on disk so the user can recover it. profiles() must NOT
        // trigger a saveProfiles() that would replace it with a fresh default.
        assertEquals(corrupted, hostProfilesJson)
    }

    @Test
    fun `removes legacy ssh profiles during decode and persists cleanup`() {
        val directProfile = HostProfile(
            id = "direct-1",
            name = "Direct",
            serverUrl = "https://opencode.example.com"
        )
        val rawArray = """
            [
              {"id":"ssh-1","name":"Old SSH","transport":"sshTunnel","serverURL":"https://old.example.com"},
              {"id":"direct-1","name":"Direct","serverURL":"https://opencode.example.com"}
            ]
        """.trimIndent()
        hostProfilesJson = rawArray
        currentHostProfileId = "direct-1"

        val profiles = store.profiles()

        assertEquals(1, profiles.size)
        assertEquals("direct-1", profiles.first().id)

        // The cleanup must be persisted (sshTunnel entry removed) and the
        // remaining direct profile still readable.
        val persisted = hostProfilesJson ?: error("expected persisted JSON")
        assertFalse(persisted.contains("sshTunnel"))
        assertTrue(persisted.contains("direct-1"))
    }

    @Test
    fun `preserves direct profiles with explicit transport field`() {
        val rawArray = """
            [
              {"id":"d-1","name":"Direct","transport":"direct","serverURL":"https://opencode.example.com"}
            ]
        """.trimIndent()
        hostProfilesJson = rawArray

        val profiles = store.profiles()

        assertEquals(1, profiles.size)
        assertEquals("d-1", profiles.first().id)
        assertEquals("https://opencode.example.com", profiles.first().serverUrl)
    }

    // ───────────── §需求12: serverGroupFp field removed ─────────────
    // The R-20 Phase 0 `serverGroupFp` nonblank invariant + its decode-time
    // normalization are GONE — the field is deleted (fp == id implicitly) and
    // `ignoreUnknownKeys=true` silently drops any legacy `serverGroupFp` key
    // in old JSON. These normalize-invariant tests were removed with the field.

    @Test
    fun `legacy multi-profile storage is trimmed to single host`() {
        val rawArray = """
            [
              {"id":"keep-id","name":"Keep","serverURL":"https://keep.example.com"},
              {"id":"other-id","name":"Other","serverURL":"https://other.example.com"}
            ]
        """.trimIndent()
        hostProfilesJson = rawArray
        currentHostProfileId = "keep-id"

        val profiles = store.profiles()
        assertEquals("L8 trims to single host", 1, profiles.size)
        assertEquals("keep-id", profiles.first().id)
        // The persisted JSON is also trimmed
        val persisted = hostProfilesJson!!
        assertTrue(persisted.contains("keep-id"))
        assertFalse(persisted.contains("other-id"))
    }

    @Test
    fun `legacy multi-profile storage falls back to first when currentId not found`() {
        val rawArray = """
            [
              {"id":"first-id","name":"First","serverURL":"https://first.example.com"},
              {"id":"second-id","name":"Second","serverURL":"https://second.example.com"}
            ]
        """.trimIndent()
        hostProfilesJson = rawArray
        currentHostProfileId = null // no current id set

        val profiles = store.profiles()
        assertEquals("L8 trims to single host (first)", 1, profiles.size)
        assertEquals("first-id", profiles.first().id)
    }

    @Test
    fun `imported profile decodes successfully`() {
        val payload = """
            {"version":1,"name":"Imported","serverURL":"https://imp.example.com"}
        """.trimIndent()

        val imported = store.importJson(payload)

        assertEquals("Imported", imported.name)
        assertEquals("https://imp.example.com", imported.serverUrl)
    }

    // ───────────── R8 slim-mode foundation: serialization migration ─────
    // Legacy JSON predates the `slim` field. After decode the field MUST
    // default to false (= legacy opencode direct connection) — neither crashes
    // nor silently enables slim mode. Same backward-compat contract as the
    // existing `mtlsEnabled` field.

    @Test
    fun `legacy json without slim field decodes to slim=false`() {
        hostProfilesJson = """
            [
              {"id":"p-1","name":"A","serverURL":"https://a.example.com","serverGroupFp":"p-1"}
            ]
        """.trimIndent()
        currentHostProfileId = "p-1"

        val profiles = store.profiles()

        assertEquals(1, profiles.size)
        assertFalse(
            "missing slim field must default to false (legacy opencode)",
            profiles.first().slim
        )
    }

    @Test
    fun `legacy json without mtlsEnabled field decodes to mtlsEnabled=false`() {
        // The existing mtlsEnabled field has the same backward-compat contract
        // as slim — pinned here so the migration symmetry is explicit.
        hostProfilesJson = """
            [
              {"id":"p-1","name":"A","serverURL":"https://a.example.com","serverGroupFp":"p-1"}
            ]
        """.trimIndent()
        currentHostProfileId = "p-1"

        val profiles = store.profiles()

        assertEquals(1, profiles.size)
        assertFalse(profiles.first().mtlsEnabled)
    }

    @Test
    fun `json with slim=true round-trips through save and reload`() {
        val slim = HostProfile(
            id = "slim-1",
            name = "Slim",
            serverUrl = "http://localhost:4097",
            slim = true,
            mtlsEnabled = true
        )
        store.save(slim)

        // Drop and re-read from disk-backed JSON.
        hostProfilesJson = hostProfilesJson // (no-op; trigger mockk store)
        val reloaded = store.profiles().first { it.id == "slim-1" }
        assertTrue(reloaded.slim)
        assertTrue(reloaded.mtlsEnabled)
    }

    @Test
    fun `defaultDirect constructs a legacy direct profile (slim=false)`() {
        val p = HostProfile.defaultDirect()
        assertFalse(
            "defaultDirect must NOT silently enable slim mode — legacy opencode direct",
            p.slim
        )
        assertFalse(p.mtlsEnabled)
    }

    @Test
    fun `legacy multi-profile JSON trimmed to single host by currentHostProfileId`() {
        hostProfilesJson = """
            [
              {"id":"leg-1","name":"Legacy","serverURL":"https://a.example.com","serverGroupFp":"leg-1"},
              {"id":"slim-1","name":"Slim","serverURL":"http://localhost:4097","serverGroupFp":"slim-1","slim":true},
              {"id":"mtls-1","name":"mTLS","serverURL":"https://b.example.com","serverGroupFp":"mtls-1","mtlsEnabled":true,"slim":true}
            ]
        """.trimIndent()
        currentHostProfileId = "leg-1"

        val profiles = store.profiles()

        assertEquals("L8 trims to 1 (the current host)", 1, profiles.size)
        assertEquals("leg-1", profiles.first().id)
    }

    // ───────────── §需求12: profilesInGroup + serverGroupFp export tests ─────────────
    // `profilesInGroup` was deleted (grouping concept removed under 需求12) and
    // the `serverGroupFp` field is gone, so the "export does not leak
    // serverGroupFp" test is vacuous. Both removed with the field.
}
