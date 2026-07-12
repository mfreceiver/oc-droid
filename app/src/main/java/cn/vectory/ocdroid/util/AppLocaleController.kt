package cn.vectory.ocdroid.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat

/**
 * §P5a (Q5): applies the app's locale policy based on the user's persisted
 * [LocaleMode] (Follow System / 中文 / English).
 *
 * The app ships `values/` (English default) and `values-zh/` (Chinese).
 * Semantics:
 *  - [LocaleMode.ZH] → force zh.
 *  - [LocaleMode.EN] → force en.
 *  - [LocaleMode.SYSTEM] → follow the REAL system locale: zh→zh, en→en, ANY
 *    OTHER (fr/ja/ko/undetermined)→zh ("跟不上系统时选中文"). The real system
 *    locale is read via [LocaleManagerCompat.getSystemLocales], NOT
 *    `Locale.getDefault()` (the latter reports the app override after the
 *    first apply, so it would self-lock to whatever we set last).
 *
 * [apply] routes through [AppCompatDelegate.setApplicationLocales]; because
 * MainActivity does NOT declare `locale` in its configChanges, a locale
 * change triggers an Activity recreate (instant effect on the UI). The call
 * is idempotent (AppCompat no-ops when the locale list is unchanged).
 *
 * The authoritative cold-start application point is [OpenCodeApp.onCreate]
 * (before the first Activity frame); [cn.vectory.ocdroid.MainActivity.onCreate]
 * re-applies on every Activity create to catch a SYSTEM-locale change that
 * happened while the process was alive (no re-run of Application.onCreate).
 */
object AppLocaleController {

    /** Applies [mode] to the app via AppCompatDelegate. [context] is needed
     *  only for [LocaleMode.SYSTEM] (to read the real system locale). */
    fun apply(context: Context, mode: LocaleMode) {
        val tags = when (mode) {
            LocaleMode.ZH -> LocaleListCompat.forLanguageTags("zh")
            LocaleMode.EN -> LocaleListCompat.forLanguageTags("en")
            LocaleMode.SYSTEM -> LocaleListCompat.forLanguageTags(resolveSystemLocaleLanguage(context))
        }
        AppCompatDelegate.setApplicationLocales(tags)
    }

    /** Applies the locale persisted in [settingsManager]. Convenience for the
     *  startup path (OpenCodeApp / MainActivity onCreate). */
    fun applyPersisted(context: Context, settingsManager: SettingsManager) {
        apply(context, settingsManager.localeMode)
    }

    /**
     * Resolves the REAL system locale to one of the two shipped language tags
     * ("zh"/"en"). ANY non-en locale (or undetermined) maps to "zh".
     *
     * Uses [LocaleManagerCompat.getSystemLocales] (androidx.core, API-safe —
     * returns the OS system locales, NOT the app-level override). Fallback to
     * `Resources.getSystem().configuration.locales[0]` if the LocaleManager
     * yields nothing (defensive; on minSdk 34 this path is effectively
     * unreachable but kept for robustness).
     *
     * NEVER use `Locale.getDefault()` here — it reflects the app override set
     * by a prior [apply], so it would self-lock and never reflect a real
     * system change.
     */
    private fun resolveSystemLocaleLanguage(context: Context): String {
        val systemLang = LocaleManagerCompat.getSystemLocales(context).get(0)?.language
            ?: android.content.res.Resources.getSystem().configuration.locales[0]?.language
            ?: return "zh"
        // zh→"zh", en→"en", ANY other (or undetermined)→"zh".
        return if (systemLang == "en") "en" else "zh"
    }
}
