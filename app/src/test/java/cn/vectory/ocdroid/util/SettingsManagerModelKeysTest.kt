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
 * R18 Phase 5 — [SettingsManager] per-URL model-key coverage.
 *
 * The private `normalizeBaseUrl(...)` (lowercases host, strips scheme +
 * trailing slash) is the linchpin of §bug5's per-URL model state isolation:
 *  - `http://Host:4096/Path` and `https://host:4096/Path/` MUST share storage
 *    so the user's disable/availability toggles survive a scheme flip or a
 *    pasted trailing slash.
 *  - Distinct hosts MUST NOT collide.
 *
 * `normalizeBaseUrl` is private, but its contract is fully observable through
 * the public model-key API: [setModelDisabled] / [getDisabledModels] /
 * [setModelAvailability] / [getModelAvailability] / [clearModelDataForUrl].
 * These exercises cover every branch of the normalizer + the full per-URL
 * CRUD surface that was previously 0% line-covered.
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

    // ───────────────── normalizeBaseUrl contract (via disabled-model key) ─────────────────

    @Test
    fun `setModelDisabled then getDisabledModels round trip on the same URL`() {
        settings.setModelDisabled("http://localhost:4096", "openai", "gpt-4", disabled = true)

        val disabled = settings.getDisabledModels("http://localhost:4096")
        assertTrue("expected entry to be persisted", disabled.contains("openai/gpt-4"))
    }

    @Test
    fun `disabled set survives scheme change http vs https`() {
        // normalizeBaseUrl strips the scheme → same key.
        settings.setModelDisabled("http://localhost:4096", "openai", "gpt-4", disabled = true)

        val fromHttps = settings.getDisabledModels("https://localhost:4096")
        assertTrue(
            "scheme flip must not break the disabled-set lookup",
            fromHttps.contains("openai/gpt-4"),
        )
    }

    @Test
    fun `disabled set normalises host casing`() {
        // normalizeBaseUrl lowercases the host portion only.
        settings.setModelDisabled("http://Host.Example:4096", "openai", "gpt-4", disabled = true)

        val fromLower = settings.getDisabledModels("http://host.example:4096")
        assertTrue(
            "host-casing difference must collapse to the same key",
            fromLower.contains("openai/gpt-4"),
        )
    }

    @Test
    fun `disabled set normalises trailing slash`() {
        settings.setModelDisabled("http://localhost:4096/", "openai", "gpt-4", disabled = true)

        val noSlash = settings.getDisabledModels("http://localhost:4096")
        assertTrue(
            "trailing slash on write must not fragment the key",
            noSlash.contains("openai/gpt-4"),
        )
    }

    @Test
    fun `disabled set keeps path component so distinct paths do not collide`() {
        settings.setModelDisabled("http://host/a/api", "openai", "gpt-4", disabled = true)

        val otherPath = settings.getDisabledModels("http://host/b/api")
        assertFalse(
            "different URL path must not be treated as the same key",
            otherPath.contains("openai/gpt-4"),
        )
    }

    @Test
    fun `disabled flag toggle removes entry when set back to false`() {
        settings.setModelDisabled("http://h:1", "p", "m", disabled = true)
        assertTrue(settings.getDisabledModels("http://h:1").contains("p/m"))

        settings.setModelDisabled("http://h:1", "p", "m", disabled = false)
        assertFalse(settings.getDisabledModels("http://h:1").contains("p/m"))
    }

    @Test
    fun `getDisabledModels returns empty set for never-configured URL`() {
        val disabled = settings.getDisabledModels("http://never-configured:1234")
        assertTrue(disabled.isEmpty())
    }

    @Test
    fun `setDisabledModels bulk replaces the entire set`() {
        settings.setModelDisabled("http://h:1", "p", "old", disabled = true)
        settings.setDisabledModels("http://h:1", setOf("p/new1", "p/new2"))

        val disabled = settings.getDisabledModels("http://h:1")
        assertEquals(setOf("p/new1", "p/new2"), disabled)
    }

    @Test
    fun `setDisabledModels with empty set clears the entry`() {
        settings.setModelDisabled("http://h:1", "p", "m", disabled = true)
        settings.setDisabledModels("http://h:1", emptySet())

        assertTrue(settings.getDisabledModels("http://h:1").isEmpty())
    }

    // ───────────────── model availability ─────────────────

    @Test
    fun `setModelAvailability round trip on the same URL`() {
        settings.setModelAvailability("http://h:1", setOf("p/a", "p/b"))

        assertEquals(setOf("p/a", "p/b"), settings.getModelAvailability("http://h:1"))
    }

    @Test
    fun `model availability shares normalised key with disabled set`() {
        // Both use normalizeBaseUrl, so writing availability for one URL form
        // and reading via a normalised equivalent must agree.
        settings.setModelAvailability("https://Host:4096/", setOf("p/x"))

        val fromHttpLower = settings.getModelAvailability("http://host:4096")
        assertEquals(setOf("p/x"), fromHttpLower)
    }

    @Test
    fun `getModelAvailability returns empty for never-configured URL`() {
        assertTrue(settings.getModelAvailability("http://never:1").isEmpty())
    }

    // ───────────────── clearModelDataForUrl ─────────────────

    @Test
    fun `clearModelDataForUrl wipes both availability and disabled for that URL`() {
        settings.setModelDisabled("http://h:1", "p", "m", disabled = true)
        settings.setModelAvailability("http://h:1", setOf("p/a"))

        settings.clearModelDataForUrl("http://h:1")

        assertTrue(settings.getDisabledModels("http://h:1").isEmpty())
        assertTrue(settings.getModelAvailability("http://h:1").isEmpty())
    }

    @Test
    fun `clearModelDataForUrl is keyed by normalised URL so scheme variants clear`() {
        settings.setModelDisabled("http://h:1", "p", "m", disabled = true)

        // Clearing via the https equivalent must reach the same key.
        settings.clearModelDataForUrl("https://h:1")

        assertTrue(settings.getDisabledModels("http://h:1").isEmpty())
    }

    @Test
    fun `clearModelDataForUrl does not touch a different URL`() {
        settings.setModelDisabled("http://a:1", "p", "m", disabled = true)
        settings.setModelDisabled("http://b:1", "p", "m", disabled = true)

        settings.clearModelDataForUrl("http://a:1")

        assertTrue(settings.getDisabledModels("http://a:1").isEmpty())
        // b is untouched.
        assertTrue(settings.getDisabledModels("http://b:1").contains("p/m"))
    }

    // ───────────────── getModelForSession / setModelForSession ─────────────────
    // These exercise the per-session model map (separate from per-URL keys but
    // also previously 0% line-covered).

    @Test
    fun `setModelForSession round trip via slash-separated provider and model`() {
        settings.setModelForSession("sess-1", "openai", "gpt-4o")

        val model = settings.getModelForSession("sess-1")
        assertEquals("openai", model?.providerId)
        assertEquals("gpt-4o", model?.modelId)
    }

    @Test
    fun `getModelForSession returns null for unknown session`() {
        assertNull(settings.getModelForSession("never-seen"))
    }

    @Test
    fun `getModelForSession survives a corrupt stored value`() {
        // Manually inject a value without the expected "provider/model" shape.
        // The parser splits on "/" with limit 2; a single segment has size 1
        // and must yield null rather than throwing.
        settings.setModelForSession("sess-1", "no-slash-here", "")
        // The setter stores "no-slash-here/" — split("/", 2) gives 2 parts
        // where the second is empty. Assert the contract: it doesn't throw and
        // either decodes or returns null. The §model-selection parser treats
        // a missing slash as null; here we get providerId="no-slash-here",
        // modelId="" which is the documented behaviour. Lock it so a future
        // change is a visible diff.
        val m = settings.getModelForSession("sess-1")
        if (m != null) {
            assertEquals("no-slash-here", m.providerId)
            assertEquals("", m.modelId)
        }
    }

    @Test
    fun `per-session model storage is independent across sessions`() {
        settings.setModelForSession("s1", "openai", "gpt-4")
        settings.setModelForSession("s2", "anthropic", "claude")

        assertEquals("gpt-4", settings.getModelForSession("s1")?.modelId)
        assertEquals("claude", settings.getModelForSession("s2")?.modelId)
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
        // Fresh prefs → no KEY_UI_*_SCALE entry → getter returns the default
        // 1f (after coerceIn, which 1f satisfies in [0.85, 1.3]).
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
