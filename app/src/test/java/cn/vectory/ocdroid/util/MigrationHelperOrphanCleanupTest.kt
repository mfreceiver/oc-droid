package cn.vectory.ocdroid.util

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §需求12阶段4 — [SettingsManager.cleanupOrphanGroupKeys] coverage.
 *
 * Post-需求12 every profile's fp == its own UUID `id`, so any persisted
 * per-fp key whose suffix is NOT a canonical UUID is an orphan that no live
 * profile can reference (legacy named-group A/B/C/D slots + legacy
 * baseUrl-keyed model slots + their migration flags). The cleanup purges
 * those orphans once, idempotently, while preserving UUID-suffixed keys
 * (current profile.id-keyed data).
 *
 * Uses Robolectric + FakeAndroidKeyStoreProvider (EncryptedSharedPreferences
 * needs a working AndroidKeyStore provider).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationHelperOrphanCleanupTest {

    private lateinit var settings: SettingsManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.install()
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsManager(context)
    }

    private fun rawPrefs(): SharedPreferences =
        settings.javaClass.getDeclaredField("encryptedPrefs").apply { isAccessible = true }
            .get(settings) as SharedPreferences

    private val uuidA = "11111111-1111-4111-8111-111111111111"
    private val uuidB = "22222222-2222-4222-8222-222222222222"

    // ───────────────── orphan purge ─────────────────

    @Test
    fun `deletes named-group A B C D suffixed disabled_models keys`() {
        val esp = rawPrefs()
        esp.edit()
            .putStringSet("disabled_models_A", setOf("openai/gpt-4"))
            .putStringSet("disabled_models_B", setOf("anthropic/claude"))
            .putStringSet("disabled_models_C", setOf("x/y"))
            .putStringSet("disabled_models_D", setOf("p/q"))
            .apply()

        settings.cleanupOrphanGroupKeys()

        listOf("A", "B", "C", "D").forEach {
            assertFalse("disabled_models_$it should be purged", esp.contains("disabled_models_$it"))
        }
    }

    @Test
    fun `deletes named-group suffixed model_availability keys`() {
        val esp = rawPrefs()
        esp.edit()
            .putStringSet("model_availability_A", setOf("openai/gpt-4"))
            .putStringSet("model_availability_B", setOf("anthropic/claude"))
            .apply()

        settings.cleanupOrphanGroupKeys()

        assertFalse(esp.contains("model_availability_A"))
        assertFalse(esp.contains("model_availability_B"))
    }

    @Test
    fun `deletes named-group suffixed recent_workdirs keys`() {
        val esp = rawPrefs()
        esp.edit()
            .putString("recent_workdirs_A", "[\"/proj-a\"]")
            .putString("recent_workdirs_B", "[\"/proj-b\"]")
            .apply()

        settings.cleanupOrphanGroupKeys()

        assertFalse(esp.contains("recent_workdirs_A"))
        assertFalse(esp.contains("recent_workdirs_B"))
    }

    @Test
    fun `deletes orphan cache_migration_v1_done flags for named-group fps`() {
        val esp = rawPrefs()
        esp.edit()
            .putBoolean("cache_migration_v1_done_A", true)
            .putBoolean("cache_migration_v1_done_B", true)
            .apply()

        settings.cleanupOrphanGroupKeys()

        assertFalse(esp.contains("cache_migration_v1_done_A"))
        assertFalse(esp.contains("cache_migration_v1_done_B"))
    }

    @Test
    fun `deletes legacy baseUrl-keyed model slots`() {
        val esp = rawPrefs()
        esp.edit()
            .putStringSet("disabled_models_localhost:4096", setOf("openai/gpt-4"))
            .putStringSet("model_availability_10.0.2.2:4097", setOf("x/y"))
            .apply()

        settings.cleanupOrphanGroupKeys()

        assertFalse(esp.contains("disabled_models_localhost:4096"))
        assertFalse(esp.contains("model_availability_10.0.2.2:4097"))
    }

    // ───────────────── UUID preservation ─────────────────

    @Test
    fun `preserves UUID-suffixed disabled_models and model_availability keys`() {
        val esp = rawPrefs()
        esp.edit()
            .putStringSet("disabled_models_$uuidA", setOf("openai/gpt-4"))
            .putStringSet("model_availability_$uuidA", setOf("openai/gpt-4"))
            .putStringSet("disabled_models_$uuidB", setOf("anthropic/claude"))
            .apply()

        settings.cleanupOrphanGroupKeys()

        assertEquals(setOf("openai/gpt-4"), esp.getStringSet("disabled_models_$uuidA", emptySet()))
        assertEquals(setOf("openai/gpt-4"), esp.getStringSet("model_availability_$uuidA", emptySet()))
        assertEquals(setOf("anthropic/claude"), esp.getStringSet("disabled_models_$uuidB", emptySet()))
    }

    @Test
    fun `preserves UUID-suffixed recent_workdirs keys`() {
        val esp = rawPrefs()
        esp.edit().putString("recent_workdirs_$uuidA", "[\"/proj-a\"]").apply()

        settings.cleanupOrphanGroupKeys()

        assertTrue(esp.contains("recent_workdirs_$uuidA"))
        assertEquals("[\"/proj-a\"]", esp.getString("recent_workdirs_$uuidA", null))
    }

    @Test
    fun `preserves UUID-suffixed cache_migration flags`() {
        val esp = rawPrefs()
        esp.edit().putBoolean("cache_migration_v1_done_$uuidA", true).apply()

        settings.cleanupOrphanGroupKeys()

        assertTrue(esp.contains("cache_migration_v1_done_$uuidA"))
    }

    @Test
    fun `mixed bag - deletes orphans and preserves UUIDs in one pass`() {
        val esp = rawPrefs()
        esp.edit()
            .putStringSet("disabled_models_A", setOf("orphan"))
            .putStringSet("disabled_models_$uuidA", setOf("keep-a"))
            .putStringSet("disabled_models_$uuidB", setOf("keep-b"))
            .putStringSet("model_availability_C", setOf("orphan"))
            .putStringSet("model_availability_$uuidA", setOf("keep-a"))
            .putString("recent_workdirs_D", "[\"/orphan\"]")
            .putString("recent_workdirs_$uuidA", "[\"/keep\"]")
            .apply()

        settings.cleanupOrphanGroupKeys()

        assertFalse(esp.contains("disabled_models_A"))
        assertFalse(esp.contains("model_availability_C"))
        assertFalse(esp.contains("recent_workdirs_D"))
        assertEquals(setOf("keep-a"), esp.getStringSet("disabled_models_$uuidA", emptySet()))
        assertEquals(setOf("keep-b"), esp.getStringSet("disabled_models_$uuidB", emptySet()))
        assertEquals(setOf("keep-a"), esp.getStringSet("model_availability_$uuidA", emptySet()))
        assertTrue(esp.contains("recent_workdirs_$uuidA"))
    }

    // ───────────────── idempotency ─────────────────

    @Test
    fun `idempotent - second invocation is a no-op and does not touch UUID keys`() {
        val esp = rawPrefs()
        esp.edit()
            .putStringSet("disabled_models_A", setOf("orphan"))
            .putStringSet("disabled_models_$uuidA", setOf("keep"))
            .apply()

        settings.cleanupOrphanGroupKeys()
        // Second call: flag already set → scan skipped entirely.
        settings.cleanupOrphanGroupKeys()

        assertFalse(esp.contains("disabled_models_A"))
        assertEquals(setOf("keep"), esp.getStringSet("disabled_models_$uuidA", emptySet()))
    }

    @Test
    fun `idempotent - sets the orphan_group_cleanup_v1_done flag`() {
        val esp = rawPrefs()
        assertFalse(esp.getBoolean("orphan_group_cleanup_v1_done", false))

        settings.cleanupOrphanGroupKeys()

        assertTrue(esp.getBoolean("orphan_group_cleanup_v1_done", false))
    }

    @Test
    fun `idempotent - no orphans present still sets the flag`() {
        val esp = rawPrefs()
        // Only UUID keys, nothing to delete.
        esp.edit().putStringSet("disabled_models_$uuidA", setOf("keep")).apply()

        settings.cleanupOrphanGroupKeys()

        assertTrue("flag set even when nothing was purged", esp.getBoolean("orphan_group_cleanup_v1_done", false))
        assertEquals(setOf("keep"), esp.getStringSet("disabled_models_$uuidA", emptySet()))
    }
}
