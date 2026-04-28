package com.yage.opencode_client.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
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

    var currentSessionId: String?
        get() = encryptedPrefs.getString(KEY_SESSION_ID, null)
        set(value) = encryptedPrefs.edit().putString(KEY_SESSION_ID, value).apply()

    var selectedModelIndex: Int
        get() = encryptedPrefs.getInt(KEY_MODEL_INDEX, 1)
        set(value) = encryptedPrefs.edit().putInt(KEY_MODEL_INDEX, value).apply()

    var selectedAgentName: String?
        get() = encryptedPrefs.getString(KEY_AGENT_NAME, null)
        set(value) = encryptedPrefs.edit().putString(KEY_AGENT_NAME, value).apply()

    var themeMode: ThemeMode
        get() = ThemeMode.valueOf(encryptedPrefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        set(value) = encryptedPrefs.edit().putString(KEY_THEME, value.name).apply()

    var aiBuilderBaseURL: String
        get() = encryptedPrefs.getString(KEY_AI_BUILDER_BASE_URL, DEFAULT_AI_BUILDER_BASE_URL) ?: DEFAULT_AI_BUILDER_BASE_URL
        set(value) = encryptedPrefs.edit().putString(KEY_AI_BUILDER_BASE_URL, value).apply()

    var aiBuilderToken: String
        get() = encryptedPrefs.getString(KEY_AI_BUILDER_TOKEN, "") ?: ""
        set(value) = encryptedPrefs.edit().putString(KEY_AI_BUILDER_TOKEN, value).apply()

    var aiBuilderCustomPrompt: String
        get() = encryptedPrefs.getString(KEY_AI_BUILDER_CUSTOM_PROMPT, DEFAULT_AI_BUILDER_CUSTOM_PROMPT) ?: DEFAULT_AI_BUILDER_CUSTOM_PROMPT
        set(value) = encryptedPrefs.edit().putString(KEY_AI_BUILDER_CUSTOM_PROMPT, value).apply()

    var aiBuilderTerminology: String
        get() = encryptedPrefs.getString(KEY_AI_BUILDER_TERMINOLOGY, DEFAULT_AI_BUILDER_TERMINOLOGY) ?: DEFAULT_AI_BUILDER_TERMINOLOGY
        set(value) = encryptedPrefs.edit().putString(KEY_AI_BUILDER_TERMINOLOGY, value).apply()

    var aiBuilderLastOKSignature: String?
        get() = encryptedPrefs.getString(KEY_AI_BUILDER_LAST_OK_SIG, null)
        set(value) = encryptedPrefs.edit().putString(KEY_AI_BUILDER_LAST_OK_SIG, value).apply()

    var aiBuilderLastOKTestedAt: Long
        get() = encryptedPrefs.getLong(KEY_AI_BUILDER_LAST_OK_TESTED, 0L)
        set(value) = encryptedPrefs.edit().putLong(KEY_AI_BUILDER_LAST_OK_TESTED, value).apply()

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

    fun getModelForSession(sessionId: String): Int? {
        val json = encryptedPrefs.getString(KEY_SESSION_MODELS, null) ?: return null
        return try {
            Json.decodeFromString<Map<String, String>>(json)[sessionId]?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun setModelForSession(sessionId: String, modelIndex: Int) {
        val json = encryptedPrefs.getString(KEY_SESSION_MODELS, null)
        val map: MutableMap<String, String> = try {
            json?.let { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
        map[sessionId] = modelIndex.toString()
        encryptedPrefs.edit().putString(KEY_SESSION_MODELS, Json.encodeToString(map)).apply()
    }

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
        const val DEFAULT_SERVER = "http://localhost:4096"
        const val DEFAULT_AI_BUILDER_BASE_URL = "https://space.ai-builders.com/backend"
        const val DEFAULT_AI_BUILDER_CUSTOM_PROMPT = "All file and directory names should use snake_case (lowercase with underscores)."
        const val DEFAULT_AI_BUILDER_TERMINOLOGY = "adhoc_jobs, life_consulting, survey_sessions, thought_review"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_MODEL_INDEX = "model_index"
        private const val KEY_AGENT_NAME = "agent_name"
        private const val KEY_THEME = "theme"
        private const val KEY_AI_BUILDER_BASE_URL = "ai_builder_base_url"
        private const val KEY_AI_BUILDER_TOKEN = "ai_builder_token"
        private const val KEY_AI_BUILDER_CUSTOM_PROMPT = "ai_builder_custom_prompt"
        private const val KEY_AI_BUILDER_TERMINOLOGY = "ai_builder_terminology"
        private const val KEY_AI_BUILDER_LAST_OK_SIG = "ai_builder_last_ok_sig"
        private const val KEY_AI_BUILDER_LAST_OK_TESTED = "ai_builder_last_ok_tested"
        private const val KEY_SESSION_DRAFTS = "session_drafts"
        private const val KEY_SESSION_MODELS = "session_models"
        private const val KEY_SESSION_AGENTS = "session_agents"
    }
}

enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}
