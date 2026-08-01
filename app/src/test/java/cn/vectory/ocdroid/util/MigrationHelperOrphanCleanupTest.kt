package cn.vectory.ocdroid.util

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    // ───────────────── session_drafts nested composite-key sweep (rev-4 blocker A) ─────────────────
    //
    // SessionPrefs stores ALL per-(profileId, sessionId) drafts inside a
    // SINGLE `session_drafts` JSON map keyed by composite "<profileId>\u0000<sessionId>".
    // The top-level prefix sweep above can't see INSIDE this map, so old
    // group-keyed drafts (profileId = "A"/"B"/"C"/"D") and pre-Phase-5
    // bare-sessionId legacy entries leak forever once the orphan cleanup
    // flag lands. rev-4 blocker A adds a second sweep that parses the map
    // and drops any entry whose composite key's profileId portion is NOT a
    // canonical UUID.

    /** Encodes a draft map to the on-disk JSON form SessionPrefs uses. */
    private fun encodeDrafts(map: Map<String, String>): String = Json.encodeToString(map)

    /** Decodes the `session_drafts` JSON back to a map (empty on missing/null). */
    private fun decodeDrafts(json: String?): Map<String, String> =
        if (json == null) emptyMap() else Json.decodeFromString(json)

    @Test
    fun `purges drafts whose composite profileId is a non-UUID group label`() {
        val esp = rawPrefs()
        val sep = SessionPrefs.COMPOSITE_KEY_SEPARATOR
        val seed = mapOf(
            "A${sep}ses_1" to "draft-a",
            "B${sep}ses_2" to "draft-b",
            "$uuidA${sep}ses_3" to "draft-uuid",
        )
        esp.edit().putString(SessionPrefs.KEY_SESSION_DRAFTS, encodeDrafts(seed)).apply()

        settings.cleanupOrphanGroupKeys()

        val after = decodeDrafts(esp.getString(SessionPrefs.KEY_SESSION_DRAFTS, null))
        assertFalse("A-prefixed entry must be purged", after.keys.any { it.startsWith("A$sep") })
        assertFalse("B-prefixed entry must be purged", after.keys.any { it.startsWith("B$sep") })
        assertEquals("UUID-prefixed entry must survive", "draft-uuid", after["$uuidA${sep}ses_3"])
        assertEquals("map shrinks to 1 entry", 1, after.size)
    }

    @Test
    fun `purges pre-Phase-5 bare-sessionId legacy draft entries (no separator)`() {
        val esp = rawPrefs()
        val sep = SessionPrefs.COMPOSITE_KEY_SEPARATOR
        // `ses_legacy` has NO NUL separator — a pre-Phase-5 bare-sessionId
        // entry the R-20 migration never reached. substringBefore returns
        // the whole key when there's no separator, so the profileId portion
        // is "ses_legacy" → not a UUID → purge.
        val seed = mapOf(
            "ses_legacy" to "old-draft",
            "$uuidA${sep}ses_3" to "keep",
        )
        esp.edit().putString(SessionPrefs.KEY_SESSION_DRAFTS, encodeDrafts(seed)).apply()

        settings.cleanupOrphanGroupKeys()

        val after = decodeDrafts(esp.getString(SessionPrefs.KEY_SESSION_DRAFTS, null))
        assertFalse("bare sessionId entry must be purged", "ses_legacy" in after)
        assertEquals("UUID-prefixed entry survives", "keep", after["$uuidA${sep}ses_3"])
        assertEquals(1, after.size)
    }

    @Test
    fun `preserves session_drafts map entirely when all profileIds are UUIDs`() {
        val esp = rawPrefs()
        val sep = SessionPrefs.COMPOSITE_KEY_SEPARATOR
        val seed = mapOf(
            "$uuidA${sep}ses_1" to "draft-a",
            "$uuidB${sep}ses_2" to "draft-b",
        )
        esp.edit().putString(SessionPrefs.KEY_SESSION_DRAFTS, encodeDrafts(seed)).apply()

        settings.cleanupOrphanGroupKeys()

        val after = decodeDrafts(esp.getString(SessionPrefs.KEY_SESSION_DRAFTS, null))
        assertEquals("map unchanged when all profileIds are UUIDs", seed, after)
    }

    @Test
    fun `idempotent across the session_drafts sweep`() {
        val esp = rawPrefs()
        val sep = SessionPrefs.COMPOSITE_KEY_SEPARATOR
        val seed = mapOf(
            "A${sep}ses_1" to "draft-a",
            "B${sep}ses_2" to "draft-b",
            "$uuidA${sep}ses_3" to "draft-uuid",
        )
        esp.edit().putString(SessionPrefs.KEY_SESSION_DRAFTS, encodeDrafts(seed)).apply()

        settings.cleanupOrphanGroupKeys()
        // Second call: flag already set → entire scan skipped (top-level + draft).
        settings.cleanupOrphanGroupKeys()

        val after = decodeDrafts(esp.getString(SessionPrefs.KEY_SESSION_DRAFTS, null))
        assertEquals("UUID-prefixed entry survives both runs", "draft-uuid", after["$uuidA${sep}ses_3"])
        assertEquals(1, after.size)
    }

    @Test
    fun `does NOT touch a corrupt session_drafts JSON`() {
        val esp = rawPrefs()
        val corrupt = "{not valid json"
        esp.edit().putString(SessionPrefs.KEY_SESSION_DRAFTS, corrupt).apply()

        settings.cleanupOrphanGroupKeys()

        assertEquals(
            "corrupt JSON must be left untouched (parse failure → no-op)",
            corrupt,
            esp.getString(SessionPrefs.KEY_SESSION_DRAFTS, null),
        )
    }

    @Test
    fun `session_drafts sweep commits even when top-level prefix sweep found nothing`() {
        // rev-4 blocker A ordering guarantee: the draft edits MUST always
        // commit even when the top-level sweep found nothing (no top-level
        // orphan keys). Seed ONLY a session_drafts orphan (no top-level
        // keys at all) → the top-level `changed` stays false; the draft
        // sweep must still drive the batched apply().
        val esp = rawPrefs()
        val sep = SessionPrefs.COMPOSITE_KEY_SEPARATOR
        val seed = mapOf(
            "C${sep}ses_orphan" to "draft-orphan",
            "$uuidA${sep}ses_keep" to "draft-keep",
        )
        esp.edit().putString(SessionPrefs.KEY_SESSION_DRAFTS, encodeDrafts(seed)).apply()

        settings.cleanupOrphanGroupKeys()

        val after = decodeDrafts(esp.getString(SessionPrefs.KEY_SESSION_DRAFTS, null))
        assertFalse("C-prefixed orphan must be purged even with no top-level orphans", after.keys.any { it.startsWith("C$sep") })
        assertEquals("UUID-prefixed entry survives", "draft-keep", after["$uuidA${sep}ses_keep"])
        assertEquals(1, after.size)
    }
}
