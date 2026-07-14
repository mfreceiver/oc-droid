package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.util.SettingsManager
import javax.inject.Inject
import javax.inject.Singleton

enum class EffectiveConnectionSource { Manual, Profile }

data class EffectiveConnectionConfig(
    val source: EffectiveConnectionSource,
    val profileId: String?,
    val serverGroupFp: String,
    val url: String,
    val username: String?,
    val password: String?,
    val workdir: String,
    val tunnelPasswordId: String?,
    val tunnelPassword: String?,
    val clientCertId: String?,
    val mtlsEnabled: Boolean,
)

interface EffectiveConnectionConfigResolver {
    fun resolve(): EffectiveConnectionConfig?
    fun activateManual(url: String, username: String? = null, password: String? = null)
    fun activateProfile(profileId: String)
}

@Singleton
class DefaultEffectiveConnectionConfigResolver @Inject constructor(
    private val settingsManager: SettingsManager,
    private val hostProfileStore: HostProfileStore,
) : EffectiveConnectionConfigResolver {
    override fun resolve(): EffectiveConnectionConfig? = when (resolvedSource()) {
        EffectiveConnectionSource.Manual -> resolveManual()
        EffectiveConnectionSource.Profile -> resolveProfile()
    }

    override fun activateManual(url: String, username: String?, password: String?) {
        settingsManager.serverUrl = url
        settingsManager.username = username
        settingsManager.password = password
        settingsManager.effectiveConnectionSourceMarker = EffectiveConnectionSource.Manual.name
    }

    override fun activateProfile(profileId: String) {
        hostProfileStore.select(profileId)
        settingsManager.effectiveConnectionSourceMarker = EffectiveConnectionSource.Profile.name
    }

    private fun resolvedSource(): EffectiveConnectionSource {
        settingsManager.effectiveConnectionSourceMarker
            ?.let { marker -> EffectiveConnectionSource.entries.firstOrNull { it.name == marker } }
            ?.let { return it }
        // Deterministic one-time migration: a persisted profile payload means
        // profile mode; old installations with only direct settings remain manual.
        val migrated = if (settingsManager.hostProfilesJson.isNullOrBlank()) {
            EffectiveConnectionSource.Manual
        } else {
            EffectiveConnectionSource.Profile
        }
        settingsManager.effectiveConnectionSourceMarker = migrated.name
        return migrated
    }

    private fun resolveManual(): EffectiveConnectionConfig? {
        val url = settingsManager.serverUrl.trim()
        if (url.isEmpty()) return null
        val profile = runCatching { hostProfileStore.currentProfile() }.getOrNull()
        return EffectiveConnectionConfig(
            source = EffectiveConnectionSource.Manual,
            profileId = profile?.id,
            serverGroupFp = profile?.serverGroupFp?.ifBlank { profile.id } ?: "manual:$url",
            url = url,
            username = settingsManager.username,
            password = settingsManager.password,
            workdir = settingsManager.currentWorkdir.orEmpty(),
            tunnelPasswordId = null,
            tunnelPassword = null,
            clientCertId = profile?.clientCertId?.takeIf { profile.mtlsEnabled },
            mtlsEnabled = profile?.mtlsEnabled == true,
        )
    }

    private fun resolveProfile(): EffectiveConnectionConfig? {
        val profile = runCatching { hostProfileStore.currentProfile() }.getOrNull() ?: return null
        val url = profile.serverUrl.trim()
        if (url.isEmpty()) return null
        return EffectiveConnectionConfig(
            source = EffectiveConnectionSource.Profile,
            profileId = profile.id,
            serverGroupFp = profile.serverGroupFp.ifBlank { profile.id },
            url = url,
            username = profile.basicAuth?.username,
            password = profile.basicAuth?.passwordId?.let(settingsManager::basicAuthPassword),
            workdir = settingsManager.currentWorkdir.orEmpty(),
            tunnelPasswordId = profile.tunnelPasswordId,
            tunnelPassword = profile.tunnelPasswordId?.let(settingsManager::getTunnelPassword),
            clientCertId = profile.clientCertId.takeIf { profile.mtlsEnabled },
            mtlsEnabled = profile.mtlsEnabled,
        )
    }
}
