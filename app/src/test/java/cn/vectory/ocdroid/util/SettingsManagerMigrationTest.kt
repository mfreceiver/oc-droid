package cn.vectory.ocdroid.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-20 Phase 5 — [SettingsManager.migrateLegacyKeysToFp] coverage.
 *
 * Plan §3 Phase 5 (dser/maxer): applySavedSettings is the cold-start trigger
 * that copies legacy global / baseUrl-keyed / sessionId-keyed storage to the
 * current fp's per-fp / composite-keyed slots. The migration is IDEMPOTENT
 * per fp via the `cache_migration_v1_done_<fp>` flag — a second call MUST be
 * a no-op so a re-applySavedSettings (a normal cold-start event) does not
 * rewrite the maps a second time.
 *
 * Covers the three categories (plan §3 三类迁移):
 *  1. recent_workdirs (global) → recent_workdirs_<fp>.
 *  2. disabled_models_<normalizedBaseUrl> → disabled_models_<fp>.
 *  3. session_drafts / agents / models JSON maps (bare sessionId keys) →
 *     composite `"<fp>\u0000<sessionId>"` keys.
 *
 * Uses Robolectric + FakeAndroidKeyStoreProvider (EncryptedSharedPreferences
 * needs a working AndroidKeyStore provider).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsManagerMigrationTest {

    private lateinit var settings: SettingsManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.install()
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsManager(context)
    }

    /**
     * Helper: write directly to the legacy global `recent_workdirs` slot
     * (pre-Phase-5 format) to seed the migration source.
     */
    private fun seedLegacyRecentWorkdirs(workdirs: List<String>) {
        val esp = settings.javaClass.getDeclaredField("encryptedPrefs").apply { isAccessible = true }
            .get(settings) as android.content.SharedPreferences
        esp.edit().putString("recent_workdirs", Json.encodeToString(workdirs)).apply()
    }

    private fun seedLegacyDisabledModels(normalizedBaseUrl: String, entries: Set<String>) {
        val esp = settings.javaClass.getDeclaredField("encryptedPrefs").apply { isAccessible = true }
            .get(settings) as android.content.SharedPreferences
        esp.edit().putStringSet("disabled_models_$normalizedBaseUrl", entries).apply()
    }

    private fun seedLegacySessionDraft(drafts: Map<String, String>) {
        val esp = settings.javaClass.getDeclaredField("encryptedPrefs").apply { isAccessible = true }
            .get(settings) as android.content.SharedPreferences
        esp.edit().putString("session_drafts", Json.encodeToString(drafts)).apply()
    }

    private fun rawPrefs(): android.content.SharedPreferences =
        settings.javaClass.getDeclaredField("encryptedPrefs").apply { isAccessible = true }
            .get(settings) as android.content.SharedPreferences

    // ───────────────── 1) recent_workdirs ─────────────────

    @Test
    fun `migrate copies legacy global recent_workdirs into the fp slot`() {
        seedLegacyRecentWorkdirs(listOf("/legacy-a", "/legacy-b"))

        settings.migrateLegacyKeysToFp("g1", legacyBaseUrl = "localhost:4096")

        assertEquals(
            "legacy list copied to fp slot",
            listOf("/legacy-a", "/legacy-b"),
            settings.getRecentWorkdirs("g1"),
        )
    }

    @Test
    fun `migrate does not touch other fps recent_workdirs`() {
        seedLegacyRecentWorkdirs(listOf("/legacy"))
        settings.setRecentWorkdirs("g2", listOf("/already-here"))

        settings.migrateLegacyKeysToFp("g1", legacyBaseUrl = "localhost:4096")

        assertEquals(listOf("/legacy"), settings.getRecentWorkdirs("g1"))
        assertEquals(listOf("/already-here"), settings.getRecentWorkdirs("g2"))
    }

    // ───────────────── 2) disabled_models / model_availability ─────────────────

    @Test
    fun `migrate copies legacy disabled_models for the matching baseUrl into the fp slot`() {
        seedLegacyDisabledModels("localhost:4096", setOf("openai/gpt-4"))

        settings.migrateLegacyKeysToFp("g1", legacyBaseUrl = "http://localhost:4096/")

        assertEquals(setOf("openai/gpt-4"), settings.getDisabledModels("g1"))
    }

    @Test
    fun `migrate does not copy a different baseUrl's disabled set`() {
        seedLegacyDisabledModels("other-host:1234", setOf("openai/gpt-4"))

        settings.migrateLegacyKeysToFp("g1", legacyBaseUrl = "http://localhost:4096")

        // No data migrated — the legacy slot was for a DIFFERENT URL.
        assertTrue(settings.getDisabledModels("g1").isEmpty())
    }

    // ───────────────── 3) session_drafts / agents / models composite-key rewrite ─────────────────

    @Test
    fun `migrate rewrites session_drafts map keys to composite fp + NUL + sessionId`() {
        seedLegacySessionDraft(mapOf("s1" to "hello", "s2" to "world"))

        settings.migrateLegacyKeysToFp("g1", legacyBaseUrl = "localhost:4096")

        // Lookups via the public API now resolve via the composite key.
        assertEquals("hello", settings.getDraftText("g1", "s1"))
        assertEquals("world", settings.getDraftText("g1", "s2"))
        // A DIFFERENT fp does not see the drafts (isolation).
        assertEquals("", settings.getDraftText("g2", "s1"))
    }

    @Test
    fun `migrate preserves entries already carrying the composite prefix`() {
        // Simulate a partial prior migration: one entry already composite, one
        // bare-sessionId. The bare one is rewritten; the composite one is left.
        val prefix = "g1" + SettingsManager.COMPOSITE_KEY_SEPARATOR
        seedLegacySessionDraft(
            mapOf(
                prefix + "s-already" to "prior",
                "s-bare" to "fresh",
            )
        )

        settings.migrateLegacyKeysToFp("g1", legacyBaseUrl = "localhost:4096")

        assertEquals("prior", settings.getDraftText("g1", "s-already"))
        assertEquals("fresh", settings.getDraftText("g1", "s-bare"))
    }

    // ───────────────── idempotency (cache_migration_v1_done_<fp>) ─────────────────

    @Test
    fun `migrate is idempotent - second call is a no-op for the same fp`() {
        seedLegacySessionDraft(mapOf("s1" to "first"))
        // First migration: rewrites s1 → g1\u0000s1 + sets the per-fp flag.
        settings.migrateLegacyKeysToFp("g1", legacyBaseUrl = "localhost:4096")
        assertEquals("first", settings.getDraftText("g1", "s1"))
        // Snapshot the post-migration session_drafts blob.
        val blobAfterFirst = rawPrefs().getString("session_drafts", null)

        // Second call: the flag is set, so this MUST be a no-op — the
        // session_drafts blob is NOT rewritten (string identity).
        settings.migrateLegacyKeysToFp("g1", legacyBaseUrl = "localhost:4096")
        val blobAfterSecond = rawPrefs().getString("session_drafts", null)

        assertEquals(
            "session_drafts blob unchanged across re-migration (flag short-circuits)",
            blobAfterFirst,
            blobAfterSecond,
        )
        assertEquals("first", settings.getDraftText("g1", "s1"))
    }

    @Test
    fun `migrate is idempotent - second call does not duplicate recent_workdirs`() {
        seedLegacyRecentWorkdirs(listOf("/a", "/b"))
        settings.migrateLegacyKeysToFp("g1", legacyBaseUrl = "localhost:4096")
        // After migration, a separate path writes a different value into the
        // fp slot. A second migration MUST not overwrite it.
        settings.setRecentWorkdirs("g1", listOf("/c", "/d", "/e"))

        settings.migrateLegacyKeysToFp("g1", legacyBaseUrl = "localhost:4096")

        assertEquals(
            "fp slot preserved across re-migration",
            listOf("/c", "/d", "/e"),
            settings.getRecentWorkdirs("g1"),
        )
    }

    @Test
    fun `migrate flag is per-fp - second fp migrates independently`() {
        seedLegacyRecentWorkdirs(listOf("/global"))
        settings.migrateLegacyKeysToFp("g1", legacyBaseUrl = "localhost:4096")

        // Second fp migration: the flag for g2 is NOT set, so the global
        // slot is read again and copied to g2's slot.
        settings.migrateLegacyKeysToFp("g2", legacyBaseUrl = "localhost:4096")

        assertEquals(listOf("/global"), settings.getRecentWorkdirs("g1"))
        assertEquals(listOf("/global"), settings.getRecentWorkdirs("g2"))
    }

    @Test
    fun `migrate blank-fp guard is a no-op`() {
        // Defensive: blank fp would corrupt the key namespace.
        seedLegacyRecentWorkdirs(listOf("/a"))
        settings.migrateLegacyKeysToFp("", legacyBaseUrl = "localhost:4096")
        // Nothing happened — no exception, no writes.
        assertTrue(settings.getRecentWorkdirs("").isEmpty())
    }

    @Test
    fun `clearAllLocalData wipes the migration flag so the next migrate re-runs`() {
        seedLegacyRecentWorkdirs(listOf("/a"))
        settings.migrateLegacyKeysToFp("g1", legacyBaseUrl = "localhost:4096")
        // Confirm the flag landed.
        assertTrue(rawPrefs().getBoolean("cache_migration_v1_done_g1", false))

        settings.clearAllLocalData()

        // Flag wiped → next migrate re-runs.
        assertNull("migration flag wiped by clearAllLocalData", rawPrefs().getString("cache_migration_v1_done_g1", null))
    }
}

private val Json = Json
