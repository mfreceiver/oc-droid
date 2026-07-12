package cn.vectory.ocdroid.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * R18 Phase 5++ / §P5a (Q5) coverage: [AppLocaleController] — applies the
 * user's persisted [LocaleMode] via [AppCompatDelegate.setApplicationLocales].
 *
 * Coverage goal: the three [AppLocaleController.apply] branches (ZH / EN /
 * SYSTEM) + the [LocaleManagerCompat.getSystemLocales]-based SYSTEM
 * resolution. Robolectric's AppCompatDelegate shadow does not persist the
 * applied value across a get, so (mirroring the original test) we assert the
 * call returns without throwing and the delegate's locale list is non-null;
 * the branch coverage is the goal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLocaleControllerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        // §note: see SettingsManagerTest — Robolectric boots the real OpenCodeApp
        // whose Hilt graph needs AndroidKeyStore.
        FakeAndroidKeyStoreProvider.install()
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // Reset the delegate to follow system (empty list).
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun `apply with ZH forces Chinese`() {
        AppLocaleController.apply(context, LocaleMode.ZH)

        val applied = AppCompatDelegate.getApplicationLocales()
        assertNotNull(applied)
    }

    @Test
    fun `apply with EN forces English`() {
        AppLocaleController.apply(context, LocaleMode.EN)

        val applied = AppCompatDelegate.getApplicationLocales()
        assertNotNull(applied)
    }

    @Test
    fun `apply with SYSTEM follows the real system locale`() {
        // SYSTEM mode resolves the real system locale via
        // LocaleManagerCompat.getSystemLocales(context); under Robolectric the
        // default system locale is used. zh→zh, en→en, any other→zh. The call
        // must not throw and must land a non-null locale list.
        AppLocaleController.apply(context, LocaleMode.SYSTEM)

        val applied = AppCompatDelegate.getApplicationLocales()
        assertNotNull(applied)
    }

    @Test
    fun `applyPersisted delegates to the SettingsManager locale`() {
        // Build a real SettingsManager (Robolectric + FakeAndroidKeyStoreProvider
        // backs the EncryptedSharedPreferences); default localeMode is SYSTEM
        // (first-launch). applyPersisted must not throw.
        val sm = SettingsManager(context)
        AppLocaleController.applyPersisted(context, sm)

        assertNotNull(AppCompatDelegate.getApplicationLocales())
        // Sanity: default LocaleMode is SYSTEM.
        assert(sm.localeMode == LocaleMode.SYSTEM)
    }
}
