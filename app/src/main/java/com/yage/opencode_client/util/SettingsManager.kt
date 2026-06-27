package com.yage.opencode_client.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yage.opencode_client.ui.theme.MarkdownFontSizes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "opencode_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var serverUrl: String
        get() = encryptedPrefs.getString(KEY_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = encryptedPrefs.edit().putString(KEY_SERVER_URL, value).apply()

    var username: String?
        get() = encryptedPrefs.getString(KEY_USERNAME, null)
        set(value) = encryptedPrefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String?
        get() = encryptedPrefs.getString(KEY_PASSWORD, null)
        set(value) = encryptedPrefs.edit().putString(KEY_PASSWORD, value).apply()

    var hostProfilesJson: String?
        get() = encryptedPrefs.getString(KEY_HOST_PROFILES, null)
        set(value) = encryptedPrefs.edit().putString(KEY_HOST_PROFILES, value).apply()

    var currentHostProfileId: String?
        get() = encryptedPrefs.getString(KEY_CURRENT_HOST_PROFILE_ID, null)
        set(value) = encryptedPrefs.edit().putString(KEY_CURRENT_HOST_PROFILE_ID, value).apply()

    fun basicAuthPassword(passwordId: String): String? {
        if (passwordId == LEGACY_BASIC_AUTH_PASSWORD_ID) return password
        return encryptedPrefs.getString(basicAuthPasswordKey(passwordId), null)
    }

    fun setBasicAuthPassword(passwordId: String, value: String?) {
        encryptedPrefs.edit().apply {
            if (value.isNullOrBlank()) remove(basicAuthPasswordKey(passwordId)) else putString(basicAuthPasswordKey(passwordId), value)
        }.apply()
    }

    fun getTunnelPassword(id: String): String? {
        return encryptedPrefs.getString(tunnelPasswordKey(id), null)
    }

    fun setTunnelPassword(id: String, password: String?) {
        encryptedPrefs.edit().apply {
            if (password.isNullOrBlank()) remove(tunnelPasswordKey(id)) else putString(tunnelPasswordKey(id), password)
        }.apply()
    }

    fun clearTunnelPassword(id: String) {
        encryptedPrefs.edit().remove(tunnelPasswordKey(id)).apply()
    }

    var currentSessionId: String?
        get() = encryptedPrefs.getString(KEY_SESSION_ID, null)
        set(value) = encryptedPrefs.edit().putString(KEY_SESSION_ID, value).apply()

    var selectedAgentName: String?
        get() = encryptedPrefs.getString(KEY_AGENT_NAME, null)
        set(value) = encryptedPrefs.edit().putString(KEY_AGENT_NAME, value).apply()

    var themeMode: ThemeMode
        get() = ThemeMode.valueOf(encryptedPrefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        set(value) = encryptedPrefs.edit().putString(KEY_THEME, value.name).apply()

    var languageMode: LanguageMode
        get() = LanguageMode.valueOf(encryptedPrefs.getString(KEY_LANGUAGE, LanguageMode.SYSTEM.name) ?: LanguageMode.SYSTEM.name)
        set(value) = encryptedPrefs.edit().putString(KEY_LANGUAGE, value.name).apply()

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

    /**
     * "Open" (not closed) session IDs in open-order (most recently opened first).
     * Replaces the previous MRU [recentSessionIds] model with a browser-tab style
     * list: opening/switching a session prepends it; closing (x) removes it.
     * Capped at 8 entries.
     */
    var openSessionIds: List<String>
        get() {
            val json = encryptedPrefs.getString(KEY_OPEN_SESSION_IDS, null) ?: return emptyList()
            return try {
                Json.decodeFromString<List<String>>(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse open session IDs, using empty", e)
                emptyList()
            }
        }
        set(value) {
            val json = Json.encodeToString(value)
            encryptedPrefs.edit().putString(KEY_OPEN_SESSION_IDS, json).apply()
        }

    fun getDraftText(sessionId: String): String {
        val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null) ?: return ""
        return try {
            Json.decodeFromString<Map<String, String>>(json)[sessionId] ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun setDraftText(sessionId: String, text: String) {
        val json = encryptedPrefs.getString(KEY_SESSION_DRAFTS, null)
        val map: MutableMap<String, String> = try {
            json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        if (text.isBlank()) {
            map.remove(sessionId)
        } else {
            map[sessionId] = text
        }
        encryptedPrefs.edit().putString(KEY_SESSION_DRAFTS, Json.encodeToString(map)).apply()
    }

    /**
     * Cumulative bytes sent over HTTP, persisted across restarts. Seeded into
     * [TrafficTracker] on app start; updated by [TrafficTracker] on every
     * counted request. See [trafficBytesReceived] for the inbound counterpart.
     */
    var trafficBytesSent: Long
        get() = encryptedPrefs.getLong(KEY_TRAFFIC_SENT, 0L)
        set(value) = encryptedPrefs.edit().putLong(KEY_TRAFFIC_SENT, value).apply()

    var trafficBytesReceived: Long
        get() = encryptedPrefs.getLong(KEY_TRAFFIC_RECEIVED, 0L)
        set(value) = encryptedPrefs.edit().putLong(KEY_TRAFFIC_RECEIVED, value).apply()

    fun getAgentForSession(sessionId: String): String? {
        val json = encryptedPrefs.getString(KEY_SESSION_AGENTS, null) ?: return null
        return try {
            Json.decodeFromString<Map<String, String>>(json)[sessionId]
        } catch (e: Exception) {
            null
        }
    }

    fun setAgentForSession(sessionId: String, agentName: String) {
        val json = encryptedPrefs.getString(KEY_SESSION_AGENTS, null)
        val map: MutableMap<String, String> = try {
            json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        map[sessionId] = agentName
        encryptedPrefs.edit().putString(KEY_SESSION_AGENTS, Json.encodeToString(map)).apply()
    }

    companion object {
        private const val TAG = "SettingsManager"
        const val DEFAULT_SERVER = "http://localhost:4096"
        const val LEGACY_BASIC_AUTH_PASSWORD_ID = "legacy_basic_auth_password"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_HOST_PROFILES = "host_profiles_json"
        private const val KEY_CURRENT_HOST_PROFILE_ID = "current_host_profile_id"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_AGENT_NAME = "agent_name"
        private const val KEY_THEME = "theme"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_SESSION_DRAFTS = "session_drafts"
        private const val KEY_SESSION_AGENTS = "session_agents"
        private const val KEY_MARKDOWN_FONT_SIZES = "markdown_font_sizes_json"
        private const val KEY_OPEN_SESSION_IDS = "open_session_ids"
        private const val KEY_TRAFFIC_SENT = "traffic_sent"
        private const val KEY_TRAFFIC_RECEIVED = "traffic_received"

        private fun basicAuthPasswordKey(passwordId: String): String = "basic_auth_password_$passwordId"
        private fun tunnelPasswordKey(id: String): String = "tunnel_password_$id"
    }
}

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

enum class LanguageMode(val languageTag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    CHINESE("zh")
}
