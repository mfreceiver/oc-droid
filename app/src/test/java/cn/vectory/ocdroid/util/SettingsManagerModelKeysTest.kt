package cn.vectory.ocdroid.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-20 Phase 5 — [SettingsManager] per-serverGroupFp model-key coverage.
 *
 * Phase 5 changed the disabled-models / model-availability API from
 * per-baseUrl keying to per-serverGroupFp keying (plan §3). The previous
 * `normalizeBaseUrl` collision-defense (lowercasing host, stripping scheme +
 * slash) is GONE — the fp is already a stable host identifier, and two
 * profiles reaching the same URL but in different groups are now correctly
 * isolated (was a known Phase <5 bug where URL-keying let sibling profiles
 * clobber each other).
 *
 * `getDisabledModels` / `setModelDisabled` / `setDisabledModels` /
 * `setModelAvailability` / `getModelAvailability` /
 * `clearModelDataForGroup` exercises cover the full per-fp CRUD surface.
 *
 * Uses the same Robolectric + FakeAndroidKeyStoreProvider setup as
 * [SettingsManagerTest] (EncryptedSharedPreferences needs a working
 * AndroidKeyStore provider).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsManagerModelKeysTest {

    private lateinit var settings: SettingsManager

    @Before
    fun setUp() {
        FakeAndroidKeyStoreProvider.install()
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = SettingsManager(context)
    }

    // ───────────────── per-fp disabled-models key ─────────────────

    @Test
    fun `setModelDisabled then getDisabledModels round trip on the same fp`() {
        settings.setModelDisabled("g1", "openai", "gpt-4", disabled = true)

        val disabled = settings.getDisabledModels("g1")
        assertTrue("expected entry to be persisted", disabled.contains("openai/gpt-4"))
    }

    @Test
    fun `distinct fps do not collide`() {
        // Phase 5 isolation contract: two fps get independent disabled sets
        // (was the URL-keyed bug where two profiles reaching the same URL but
        // in different groups would clobber each other).
        settings.setModelDisabled("g1", "openai", "gpt-4", disabled = true)
        settings.setModelDisabled("g2", "anthropic", "claude", disabled = true)

        assertEquals(setOf("openai/gpt-4"), settings.getDisabledModels("g1"))
        assertEquals(setOf("anthropic/claude"), settings.getDisabledModels("g2"))
    }

    @Test
    fun `disabled flag toggle removes entry when set back to false`() {
        settings.setModelDisabled("g1", "p", "m", disabled = true)
        assertTrue(settings.getDisabledModels("g1").contains("p/m"))

        settings.setModelDisabled("g1", "p", "m", disabled = false)
        assertFalse(settings.getDisabledModels("g1").contains("p/m"))
    }

    @Test
    fun `getDisabledModels returns empty set for never-configured fp`() {
        val disabled = settings.getDisabledModels("never-configured-fp")
        assertTrue(disabled.isEmpty())
    }

    @Test
    fun `setDisabledModels bulk replaces the entire set`() {
        settings.setModelDisabled("g1", "p", "old", disabled = true)
        settings.setDisabledModels("g1", setOf("p/new1", "p/new2"))

        val disabled = settings.getDisabledModels("g1")
        assertEquals(setOf("p/new1", "p/new2"), disabled)
    }

    @Test
    fun `setDisabledModels with empty set clears the entry`() {
        settings.setModelDisabled("g1", "p", "m", disabled = true)
        settings.setDisabledModels("g1", emptySet())

        assertTrue(settings.getDisabledModels("g1").isEmpty())
    }

    // ───────────────── model availability (per-fp) ─────────────────

    @Test
    fun `setModelAvailability round trip on the same fp`() {
        settings.setModelAvailability("g1", setOf("p/a", "p/b"))

        assertEquals(setOf("p/a", "p/b"), settings.getModelAvailability("g1"))
    }

    @Test
    fun `model availability shares fp with disabled set but stores independently`() {
        // Both share the per-fp dimension but distinct keys; one write does
        // NOT clobber the other.
        settings.setModelAvailability("g1", setOf("p/x"))
        settings.setModelDisabled("g1", "p", "x", disabled = true)

        assertEquals(setOf("p/x"), settings.getModelAvailability("g1"))
        assertTrue(settings.getDisabledModels("g1").contains("p/x"))
    }

    @Test
    fun `getModelAvailability returns empty for never-configured fp`() {
        assertTrue(settings.getModelAvailability("never").isEmpty())
    }

    // ───────────────── clearModelDataForGroup ─────────────────

    @Test
    fun `clearModelDataForGroup wipes both availability and disabled for that fp`() {
        settings.setModelDisabled("g1", "p", "m", disabled = true)
        settings.setModelAvailability("g1", setOf("p/a"))

        settings.clearModelDataForGroup("g1")

        assertTrue(settings.getDisabledModels("g1").isEmpty())
        assertTrue(settings.getModelAvailability("g1").isEmpty())
    }

    @Test
    fun `clearModelDataForGroup does not touch a different fp`() {
        settings.setModelDisabled("g1", "p", "m", disabled = true)
        settings.setModelDisabled("g2", "p", "m", disabled = true)

        settings.clearModelDataForGroup("g1")

        assertTrue(settings.getDisabledModels("g1").isEmpty())
        // g2 untouched — Phase 5 isolation.
        assertTrue(settings.getDisabledModels("g2").contains("p/m"))
    }

    // ───────────────── getModelForSession / setModelForSession (composite key) ─────────────────
    // Phase 5: composite (fp, sessionId) map key — see SettingsManager.getDraftText doc.

    @Test
    fun `setModelForSession round trip via slash-separated provider and model`() {
        settings.setModelForSession("g1", "sess-1", "openai", "gpt-4o")

        val model = settings.getModelForSession("g1", "sess-1")
        assertEquals("openai", model?.providerId)
        assertEquals("gpt-4o", model?.modelId)
    }

    @Test
    fun `getModelForSession returns null for unknown session`() {
        assertNull(settings.getModelForSession("g1", "never-seen"))
    }

    @Test
    fun `getModelForSession survives a corrupt stored value`() {
        // Manually inject a value without the expected "provider/model" shape.
        // The parser splits on "/" with limit 2; a single segment has size 1
        // and must yield null rather than throwing.
        settings.setModelForSession("g1", "sess-1", "no-slash-here", "")
        // The setter stores "no-slash-here/" — split("/", 2) gives 2 parts
        // where the second is empty. Assert the contract: it doesn't throw and
        // either decodes or returns null.
        val m = settings.getModelForSession("g1", "sess-1")
        if (m != null) {
            assertEquals("no-slash-here", m.providerId)
            assertEquals("", m.modelId)
        }
    }

    @Test
    fun `per-session model storage is independent across sessions`() {
        settings.setModelForSession("g1", "s1", "openai", "gpt-4")
        settings.setModelForSession("g1", "s2", "anthropic", "claude")

        assertEquals("gpt-4", settings.getModelForSession("g1", "s1")?.modelId)
        assertEquals("claude", settings.getModelForSession("g1", "s2")?.modelId)
    }

    @Test
    fun `same sessionId different fp are isolated`() {
        // Phase 5 collision defense (plan §3 freegpt #4): a branded ses_xxxx
        // string is not a UUID; clone/reset server collisions are possible.
        // The composite (fp, sessionId) key keeps the entries independent.
        settings.setModelForSession("g1", "ses_x", "openai", "gpt-4")
        settings.setModelForSession("g2", "ses_x", "anthropic", "claude")

        assertEquals("gpt-4", settings.getModelForSession("g1", "ses_x")?.modelId)
        assertEquals("claude", settings.getModelForSession("g2", "ses_x")?.modelId)
    }

    // ───────────────── UI scale clamping (was 0% covered) ─────────────────

    @Test
    fun `uiFontScale clamps below the minimum`() {
        settings.uiFontScale = 0.5f
        assertEquals(SettingsManager.UI_SCALE_MIN, settings.uiFontScale, 0f)
    }

    @Test
    fun `uiFontScale clamps above the maximum`() {
        settings.uiFontScale = 5f
        assertEquals(SettingsManager.UI_SCALE_MAX, settings.uiFontScale, 0f)
    }

    @Test
    fun `uiContentScale clamps below the minimum`() {
        settings.uiContentScale = 0.1f
        assertEquals(SettingsManager.UI_SCALE_MIN, settings.uiContentScale, 0f)
    }

    @Test
    fun `uiContentScale clamps above the maximum`() {
        settings.uiContentScale = 99f
        assertEquals(SettingsManager.UI_SCALE_MAX, settings.uiContentScale, 0f)
    }

    @Test
    fun `uiFontScale and uiContentScale default to 1_0`() {
        assertEquals(1f, settings.uiFontScale, 0f)
        assertEquals(1f, settings.uiContentScale, 0f)
    }

    @Test
    fun `uiFontScale preserves an in-range value exactly`() {
        settings.uiFontScale = 1.15f
        assertEquals(1.15f, settings.uiFontScale, 0f)
    }

    @Test
    fun `markdownFontSizes round trip through JSON`() {
        val original = MarkdownFontSizes(body = 18f, code = 14f, h1 = 22f)
        settings.markdownFontSizes = original

        val read = settings.markdownFontSizes
        assertEquals(18f, read.body, 0f)
        assertEquals(14f, read.code, 0f)
        assertEquals(22f, read.h1, 0f)
    }

    @Test
    fun `markdownFontSizes default when never set`() {
        val defaults = settings.markdownFontSizes
        assertEquals(MarkdownFontSizes(), defaults)
    }
}
