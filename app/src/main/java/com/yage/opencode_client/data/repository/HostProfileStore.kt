package com.yage.opencode_client.data.repository

import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.HostProfileExportPayload
import com.yage.opencode_client.data.model.HostProfileImportPayload
import com.yage.opencode_client.util.SettingsManager
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        val decoded = decodeProfiles(settingsManager.hostProfilesJson)
        if (decoded.isNotEmpty()) return decoded

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

    private fun decodeProfiles(raw: String?): List<HostProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<HostProfile>>(raw) }.getOrElse { emptyList() }
    }

    private fun saveProfiles(profiles: List<HostProfile>, currentId: String?) {
        settingsManager.hostProfilesJson = json.encodeToString(profiles)
        settingsManager.currentHostProfileId = currentId ?: profiles.firstOrNull()?.id
    }
}
