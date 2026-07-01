package cn.vectory.ocdroid.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Applies the app's locale policy once at startup.
 *
 * The app ships `values/` (English default) and `values-zh/` (Chinese). The
 * rule: follow the system locale, EXCEPT that any non-English system locale
 * is forced to Chinese (since the app has no other localizations, a non-EN
 * system would otherwise fall through to the English default).
 *
 * This is NOT reactive to runtime state: it reads `Locale.getDefault()` once
 * at app start (the system locale does not change without a recreate, which
 * re-runs this).
 */
object AppLocaleController {
    fun applySystemLocale() {
        val systemLang = java.util.Locale.getDefault().language
        if (systemLang == "en") {
            // English: let the system default apply (empty list = follow system).
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            // Non-English system: force Chinese (the only other shipped locale).
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh"))
        }
    }
}
