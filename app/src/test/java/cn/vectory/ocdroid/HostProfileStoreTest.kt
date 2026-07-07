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

    // ───────────── R-20 Phase 0: serverGroupFp nonblank invariant ─────────
    // Legacy JSON that predates Phase 0 has no serverGroupFp field. After
    // decode every profile MUST carry serverGroupFp == id, distinct per
    // profile — otherwise unrelated legacy profiles collapse into a shared
    // blank group and contaminate the cache key.

    @Test
    fun `legacy json without serverGroupFp normalizes each profile to its own group`() {
        // Two pre-Phase-0 profiles — no serverGroupFp field on either.
        val rawArray = """
            [
              {"id":"p-1","name":"A","serverURL":"https://a.example.com"},
              {"id":"p-2","name":"B","serverURL":"https://b.example.com"}
            ]
        """.trimIndent()
        hostProfilesJson = rawArray
        currentHostProfileId = "p-1"

        val profiles = store.profiles()

        assertEquals(2, profiles.size)
        // Every profile nonblank and equal to its id (one-member group).
        profiles.forEach { p ->
            assertEquals(p.id, p.serverGroupFp)
            assertTrue(p.serverGroupFp.isNotBlank())
        }
        // Distinct groups — no cross-contamination.
        val groups = profiles.map { it.serverGroupFp }.toSet()
        assertEquals(profiles.size, groups.size)
    }

    @Test
    fun `normalization persists so subsequent reads see nonblank groups`() {
        hostProfilesJson = """
            [
              {"id":"p-1","name":"A","serverURL":"https://a.example.com"}
            ]
        """.trimIndent()

        // First call triggers the normalize + persist.
        store.profiles()
        // The persisted JSON must contain the serverGroupFp field.
        val persisted = hostProfilesJson ?: error("expected persisted JSON")
        assertTrue("persisted JSON should carry serverGroupFp: $persisted", persisted.contains("serverGroupFp"))

        // A fresh store reads back the nonblank invariant.
        val reread = store.profiles()
        reread.forEach { p -> assertEquals(p.id, p.serverGroupFp) }
    }

    @Test
    fun `save guarantees nonblank serverGroupFp on disk`() {
        // Construct a profile with blank serverGroupFp and persist via save().
        val blank = HostProfile(
            id = "blank-1",
            name = "Blank",
            serverUrl = "https://x.example.com",
            serverGroupFp = ""
        )
        store.save(blank)

        // Persisted JSON must NOT contain a blank serverGroupFp value.
        val persisted = hostProfilesJson ?: error("expected persisted JSON")
        assertFalse("persisted should normalize blank → id: $persisted",
            persisted.contains("\"serverGroupFp\":\"\""))

        // Read-back normalizes too (defense in depth).
        val readBack = store.profiles().first { it.id == "blank-1" }
        assertEquals("blank-1", readBack.serverGroupFp)
    }

    @Test
    fun `duplicate creates an independent single-member group`() {
        // Seed a profile, then duplicate — the duplicate's serverGroupFp must
        // equal its OWN new id, NOT inherit the source's group (plan §1:
        // duplicate ≡ import → fresh independent group).
        val original = store.currentProfile()
        val dup = store.duplicate(original.id)

        assertNotEquals(original.id, dup.id)
        assertEquals(dup.id, dup.serverGroupFp)
        assertNotEquals(original.serverGroupFp, dup.serverGroupFp)
    }

    @Test
    fun `imported profile starts as its own independent group`() {
        val payload = """
            {"version":1,"name":"Imported","serverURL":"https://imp.example.com"}
        """.trimIndent()

        val imported = store.importJson(payload)

        assertEquals(imported.id, imported.serverGroupFp)
    }

    @Test
    fun `defaultDirect seeds serverGroupFp equal to id`() {
        val p = HostProfile.defaultDirect()
        assertEquals(p.id, p.serverGroupFp)
    }

    // ───────────── R-20 grouping: manual A-D slots / profilesInGroup ─

    @Test
    fun `profilesInGroup returns matching profiles and ignores others`() {
        hostProfilesJson = """
            [
              {"id":"a","name":"A","serverURL":"https://a.example.com","serverGroupFp":"g1"},
              {"id":"b","name":"B","serverURL":"https://b.example.com","serverGroupFp":"g1"},
              {"id":"c","name":"C","serverURL":"https://c.example.com","serverGroupFp":"g2"}
            ]
        """.trimIndent()

        val g1 = store.profilesInGroup("g1")
        assertEquals(2, g1.size)
        assertTrue(g1.all { it.serverGroupFp == "g1" })
        assertEquals(1, store.profilesInGroup("g2").size)
        // Blank query never matches (the nonblank invariant forbids blank groups).
        assertTrue(store.profilesInGroup("").isEmpty())
    }

    @Test
    fun `export payload does not leak serverGroupFp`() {
        val p = HostProfile(
            id = "leak-1",
            name = "Leak",
            serverUrl = "https://leak.example.com",
            serverGroupFp = "super-secret-group"
        )
        val exported = store.exportJson(p)
        assertFalse("export must NOT include serverGroupFp: $exported",
            exported.contains("super-secret-group"))
        assertFalse("export must NOT include serverGroupFp field: $exported",
            exported.contains("serverGroupFp"))
    }
}
