package cn.vectory.ocdroid.util

import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * L4b domain split of [SettingsManager] — APPEARANCE / UI-PRESENTATION domain.
 *
 * Owns the user-tunable presentation prefs: theme, locale, UI scale (font +
 * content), the four font-family pickers (app + markdown × Latin + CJK),
 * the markdown font-size map, and the persistent-notification visibility
 * toggle.
 *
 * ⚠ AMBIGUOUS DOMAIN ASSIGNMENT (flagged per L4b instructions):
 * [persistentNotificationEnabled] controls FGS notification *presentation
 * behaviour*, which is not strictly "appearance". It is folded into this
 * domain (rather than given a one-key NotificationsPrefs) because it is a
 * single user-facing Settings toggle configured on the same surface as the
 * other appearance prefs, and it shares the exact ESP-Boolean shape. Its
 * key string + default + read/write cadence are byte-identical to the
 * pre-split code. If a future split promotes notifications to their own
 * domain, this is the only key that needs to move.
 *
 * Behavior byte-identical to pre-split [SettingsManager]: same ESP instance,
 * same key strings, same clamps ([UI_SCALE_MIN]–[UI_SCALE_MAX]), same
 * ThemeMode/LocaleMode-by-name codecs, same JSON parse-fallback defaults.
 * NO key renames.
 */
internal class AppearancePrefs(
    private val encryptedPrefs: SharedPreferences,
) {
    var themeMode: ThemeMode
        get() = ThemeMode.valueOf(encryptedPrefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        set(value) = encryptedPrefs.edit().putString(KEY_THEME, value.name).apply()

    /**
     * T5-C1/C2: when `false` (default), the persistent/ongoing FGS
     * session-status notification is built with `PRIORITY_MIN` +
     * `setSilent(true)` so it does not surface in the shade nor make
     * sound/vibrate, while still `setOngoing` so the FGS slot survives and
     * SSE keepalive continues unchanged. When `true`, the prior LOW +
     * non-silent surface is restored (the user explicitly opts in to a
     * visible ongoing notification).
     *
     * ESP-persisted Boolean mirroring the existing key/get/set pattern
     * (e.g. [themeMode]); default `false` so a fresh install keeps the
     * notification drawer quiet.
     */
    var persistentNotificationEnabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_PERSISTENT_NOTIFICATION_ENABLED, false)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_PERSISTENT_NOTIFICATION_ENABLED, value).apply()

    /**
     * §P5a (Q5): user-facing language preference. SYSTEM = follow the real
     * system locale (zh→zh, en→en, anything else→zh via AppLocaleController);
     * ZH/EN = force that language. First-launch default = SYSTEM (null in ESP
     * → SYSTEM). Applied at process startup in OpenCodeApp.onCreate before
     * the first Activity frame, and re-applied on every setLocaleMode via
     * [cn.vectory.ocdroid.util.AppLocaleController.apply].
     *
     * NOT in the [SettingsManager.clearAllLocalData] preserved-keys
     * whitelist — a full data reset returns the language to SYSTEM (the
     * comment in that method notes KEY_LOCALE is intentionally wiped
     * alongside theme/font prefs).
     */
    var localeMode: LocaleMode
        get() = encryptedPrefs.getString(KEY_LOCALE, null)?.let {
            runCatching { LocaleMode.valueOf(it) }.getOrNull()
        } ?: LocaleMode.SYSTEM
        set(value) = encryptedPrefs.edit().putString(KEY_LOCALE, value.name).apply()

    /**
     * §ui-scale: user-adjustable UI scale factors (M3 canonical pattern —
     * applied via CompositionLocalProvider(LocalDensity provides Density(...))
     * in OpenCodeTheme). Two independent axes per the official Android
     * scalable-content guidance:
     *
     *  - [uiFontScale]: multiplies LocalDensity.fontScale ONLY → text resizes,
     *    layout/padding/icon sizes stay fixed. Good for "make text bigger
     *    without reflowing the layout".
     *  - [uiContentScale]: multiplies LocalDensity.density → everything (dp-
     *    based dimensions AND sp text) resizes together. True "zoom" — good
     *    for tablet users who want the whole UI larger.
     *
     * Both layer ON TOP OF the system accessibility font size (the base
     * LocalDensity.fontScale already carries the OS setting). Range clamped to
     * [UI_SCALE_MIN]–[UI_SCALE_MAX]; default 1.0 (no change).
     */
    var uiFontScale: Float
        get() = encryptedPrefs.getFloat(KEY_UI_FONT_SCALE, 1f).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
        set(value) = encryptedPrefs.edit().putFloat(KEY_UI_FONT_SCALE, value.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)).apply()

    var uiContentScale: Float
        get() = encryptedPrefs.getFloat(KEY_UI_CONTENT_SCALE, 1f).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)
        set(value) = encryptedPrefs.edit().putFloat(KEY_UI_CONTENT_SCALE, value.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX)).apply()

    /**
     * 字体脚手架（v2 §20 / D5；Phase 1 Expressive 字体路线 B 更新）。
     *
     * 4 键默认空字符串（= 使用 bundled 可变字体）。Phase 1 已 bundle Noto Sans VF
     * （常规）+ JetBrains Mono VF（等宽）到 res/font/。空 →
     * [cn.vectory.ocdroid.ui.theme.OpenCodeTheme] 内的 `resolveFontFamilyOrNull`
     * 回退到 `BundledSansFamily`（Noto Sans VF）；非空 → 经 `systemFontFamily()`
     * 作为 4 档 weight 系统字体族名加载（Android 对任意族名 weight 支持有限，
     * 自定义名可能回落 Normal，平台限制）。
     *
     * - [fontLatin] / [fontCJK]：作用于全应用 Typography（含顶栏、卡片等）。
     * - [markdownFontLatin] / [markdownFontCJK]：作用于聊天 markdown 渲染。
     */
    var fontLatin: String
        get() = encryptedPrefs.getString(KEY_FONT_LATIN, "") ?: ""
        set(value) = encryptedPrefs.edit().putString(KEY_FONT_LATIN, value).apply()

    var fontCJK: String
        get() = encryptedPrefs.getString(KEY_FONT_CJK, "") ?: ""
        set(value) = encryptedPrefs.edit().putString(KEY_FONT_CJK, value).apply()

    var markdownFontLatin: String
        get() = encryptedPrefs.getString(KEY_MARKDOWN_FONT_LATIN, "") ?: ""
        set(value) = encryptedPrefs.edit().putString(KEY_MARKDOWN_FONT_LATIN, value).apply()

    var markdownFontCJK: String
        get() = encryptedPrefs.getString(KEY_MARKDOWN_FONT_CJK, "") ?: ""
        set(value) = encryptedPrefs.edit().putString(KEY_MARKDOWN_FONT_CJK, value).apply()

    var markdownFontSizes: MarkdownFontSizes
        get() {
            val json = encryptedPrefs.getString(KEY_MARKDOWN_FONT_SIZES, null) ?: return MarkdownFontSizes()
            return try {
                Json.decodeFromString<MarkdownFontSizes>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse markdown font sizes, using defaults", e)
                MarkdownFontSizes()
            }
        }
        set(value) {
            val json = Json.encodeToString(value)
            encryptedPrefs.edit().putString(KEY_MARKDOWN_FONT_SIZES, json).apply()
        }

    companion object {
        private const val TAG = "SettingsManager"

        internal const val KEY_THEME = "theme"
        /** T5-C1: ESP key for [persistentNotificationEnabled]. Default false. */
        internal const val KEY_PERSISTENT_NOTIFICATION_ENABLED = "persistent_notification_enabled"
        /** §P5a (Q5): persisted [LocaleMode] (language preference). Default null → SYSTEM. */
        internal const val KEY_LOCALE = "locale"
        internal const val KEY_UI_FONT_SCALE = "ui_font_scale"
        internal const val KEY_UI_CONTENT_SCALE = "ui_content_scale"
        /** §ui-scale: clamp range for both font + content scale sliders. */
        const val UI_SCALE_MIN = 0.85f
        const val UI_SCALE_MAX = 1.3f
        internal const val KEY_MARKDOWN_FONT_SIZES = "markdown_font_sizes_json"
        internal const val KEY_FONT_LATIN = "font_latin"
        internal const val KEY_FONT_CJK = "font_cjk"
        internal const val KEY_MARKDOWN_FONT_LATIN = "markdown_font_latin"
        internal const val KEY_MARKDOWN_FONT_CJK = "markdown_font_cjk"
    }
}
