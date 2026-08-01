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
import org.junit.Assert.assertNotEquals
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
    fun `save select duplicate and delete profiles`() {
        val original = store.currentProfile()
        val remote = HostProfile(
            name = "Remote",
            serverUrl = "https://opencode.example.com"
        )

        store.save(remote)
        assertEquals(2, store.profiles().size)

        val selected = store.select(remote.id)
        assertEquals(remote.id, selected.id)
        assertEquals(remote.id, currentHostProfileId)

        val duplicate = store.duplicate(remote.id)
        assertNotEquals(remote.id, duplicate.id)
        assertTrue(duplicate.name.contains("Copy"))

        store.delete(original.id)
        assertEquals(2, store.profiles().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cannot delete last profile`() {
        val only = store.currentProfile()
        store.delete(only.id)
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
    fun `duplicate creates an independent profile`() {
        // §需求12: duplicate clones config into a fresh id (fp == id, no
        // grouping concept remains).
        val original = store.currentProfile()
        val dup = store.duplicate(original.id)

        assertNotEquals(original.id, dup.id)
        assertTrue(dup.name.contains("Copy"))
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
    fun `legacy and slim profiles can coexist in the same JSON list`() {
        hostProfilesJson = """
            [
              {"id":"leg-1","name":"Legacy","serverURL":"https://a.example.com","serverGroupFp":"leg-1"},
              {"id":"slim-1","name":"Slim","serverURL":"http://localhost:4097","serverGroupFp":"slim-1","slim":true},
              {"id":"mtls-1","name":"mTLS","serverURL":"https://b.example.com","serverGroupFp":"mtls-1","mtlsEnabled":true,"slim":true}
            ]
        """.trimIndent()
        currentHostProfileId = "leg-1"

        val profiles = store.profiles()

        assertEquals(3, profiles.size)
        val byId = profiles.associateBy { it.id }
        assertFalse(byId["leg-1"]!!.slim)
        assertFalse(byId["leg-1"]!!.mtlsEnabled)
        assertTrue(byId["slim-1"]!!.slim)
        assertFalse(byId["slim-1"]!!.mtlsEnabled)
        assertTrue(byId["mtls-1"]!!.slim)
        assertTrue(byId["mtls-1"]!!.mtlsEnabled)
    }

    // ───────────── §需求12: profilesInGroup + serverGroupFp export tests ─────────────
    // `profilesInGroup` was deleted (grouping concept removed under 需求12) and
    // the `serverGroupFp` field is gone, so the "export does not leak
    // serverGroupFp" test is vacuous. Both removed with the field.
}
