package com.yage.opencode_client.data.repository

import android.util.Log
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.HostProfileExportPayload
import com.yage.opencode_client.data.model.HostProfileImportPayload
import com.yage.opencode_client.util.SettingsManager
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostProfileStore @Inject constructor(
    private val settingsManager: SettingsManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Synchronized
    fun profiles(): List<HostProfile> {
        val raw = settingsManager.hostProfilesJson
        // Decode + one-time SSH migration. On parse failure we MUST NOT
        // overwrite the original JSON — that would silently destroy data.
        val decoded = decodeProfiles(raw)
        return when (decoded) {
            is DecodeOutcome.ParseFailure -> {
                // Preserve the corrupt JSON on disk so the user can recover
                // (or a future fix-up can run). Do NOT fall through to the
                // migration path, which would overwrite with a fresh default.
                emptyList()
            }
            is DecodeOutcome.Decoded -> {
                if (decoded.profiles.isNotEmpty()) return decoded.profiles
                migrateLegacySettings()
            }
        }
    }

    @Synchronized
    fun currentProfile(): HostProfile {
        val all = profiles()
        val currentId = settingsManager.currentHostProfileId
        return all.firstOrNull { it.id == currentId } ?: all.first().also {
            settingsManager.currentHostProfileId = it.id
        }
    }

    @Synchronized
    fun save(profile: HostProfile) {
        val all = profiles().toMutableList()
        val index = all.indexOfFirst { it.id == profile.id }
        if (index >= 0) all[index] = profile else all.add(profile)
        val currentId = settingsManager.currentHostProfileId ?: profile.id
        saveProfiles(all, currentId)
    }

    @Synchronized
    fun select(profileId: String): HostProfile {
        val all = profiles()
        val selected = all.firstOrNull { it.id == profileId } ?: error("Host profile not found")
        val updated = selected.copy(lastUsedAt = System.currentTimeMillis())
        save(updated)
        settingsManager.currentHostProfileId = selected.id
        return updated
    }

    @Synchronized
    fun duplicate(profileId: String): HostProfile {
        val source = profiles().firstOrNull { it.id == profileId } ?: error("Host profile not found")
        val copy = source.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${source.displayName} Copy",
            lastUsedAt = null
        )
        save(copy)
        return copy
    }

    @Synchronized
    fun delete(profileId: String) {
        val all = profiles()
        require(all.size > 1) { "Keep at least one profile" }
        val remaining = all.filterNot { it.id == profileId }
        require(remaining.size != all.size) { "Host profile not found" }
        val nextCurrent = if (settingsManager.currentHostProfileId == profileId) remaining.first().id else settingsManager.currentHostProfileId
        saveProfiles(remaining, nextCurrent)
    }

    fun exportJson(profile: HostProfile): String {
        return json.encodeToString(HostProfileExportPayload.from(profile))
    }

    fun importJson(payload: String): HostProfile {
        val profile = try {
            json.decodeFromString<HostProfileImportPayload>(payload).makeProfile()
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid host profile JSON", e)
        }
        save(profile)
        return profile
    }

    /**
     * Decodes the raw JSON payload and performs a one-time migration that strips
     * legacy SSH-only profiles (`transport == "sshTunnel"`). On successful decode
     * with SSH entries removed, the cleaned list is persisted in place. On parse
     * failure, returns [DecodeOutcome.ParseFailure] so callers can preserve the
     * original payload instead of overwriting it.
     */
    private fun decodeProfiles(raw: String?): DecodeOutcome {
        if (raw.isNullOrBlank()) return DecodeOutcome.Decoded(emptyList())
        return runCatching {
            val elements = json.decodeFromString<JsonElement>(raw).jsonArray
            val filtered = elements.filterNot { el ->
                runCatching {
                    el.jsonObject["transport"]?.jsonPrimitive?.content == "sshTunnel"
                }.getOrDefault(false)
            }
            val profiles = json.decodeFromJsonElement(
                ListSerializer(HostProfile.serializer()),
                JsonArray(filtered)
            )
            if (filtered.size != elements.size) {
                Log.w(
                    TAG,
                    "Removed ${elements.size - filtered.size} legacy SSH profile(s) (SSH support removed)"
                )
                // Persist the cleanup. Safe: we successfully decoded every
                // remaining entry, so this is a deliberate migration rather
                // than a destructive overwrite.
                val remainingCurrentId = settingsManager.currentHostProfileId
                    ?.takeIf { id -> profiles.any { it.id == id } }
                saveProfiles(profiles, remainingCurrentId)
            }
            profiles
        }.fold(
            onSuccess = { DecodeOutcome.Decoded(it) },
            onFailure = {
                Log.w(TAG, "Failed to decode host profiles JSON; preserving original payload", it)
                DecodeOutcome.ParseFailure
            }
        )
    }

    private fun migrateLegacySettings(): List<HostProfile> {
        val migrated = listOf(
            HostProfile.defaultDirect(
                serverUrl = settingsManager.serverUrl,
                username = settingsManager.username,
                passwordId = SettingsManager.LEGACY_BASIC_AUTH_PASSWORD_ID.takeIf { !settingsManager.password.isNullOrBlank() }
            )
        )
        saveProfiles(migrated, migrated.first().id)
        return migrated
    }

    private fun saveProfiles(profiles: List<HostProfile>, currentId: String?) {
        settingsManager.hostProfilesJson = json.encodeToString(profiles)
        settingsManager.currentHostProfileId = currentId ?: profiles.firstOrNull()?.id
    }

    private sealed interface DecodeOutcome {
        data class Decoded(val profiles: List<HostProfile>) : DecodeOutcome
        object ParseFailure : DecodeOutcome
    }

    private companion object {
        private const val TAG = "HostProfileStore"
    }
}
