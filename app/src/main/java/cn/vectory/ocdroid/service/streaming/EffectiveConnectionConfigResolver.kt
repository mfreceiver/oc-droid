package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.util.SettingsManager
import javax.inject.Inject
import javax.inject.Singleton

enum class EffectiveConnectionSource { Manual, Profile }

/**
 * R8 slim-mode foundation / Cluster B: 服务层透传的「当前生效连接配置」。
 *
 * `slim` 字段是 R8 模型的 slim 维度（与 [mtlsEnabled] 正交），来自所选
 * [HostProfile.slim]。 [ConnectionBootstrapEngine.performAttempt] 把它喂给
 * [cn.vectory.ocdroid.data.repository.OpenCodeRepository.configure] 的 `slim`
 * 参数，由 repository 写入 [cn.vectory.ocdroid.data.repository.HostConfig.slim]
 * ——后者被 [cn.vectory.ocdroid.data.repository.http.SlimapiVersionInterceptor]
 * 与 SSEClient（A1）读取以决定路由（注入版本头、走 `/slimapi/` 端点）。
 *
 * `slim` 默认 false，保持所有现有构造点（含 Manual 源、测试 fixture）的
 * legacy 行为不变——只有显式选了 `slim=true` 的 profile 才激活省流。
 *
 * **设计权衡**：把 slim 放进 EffectiveConnectionConfig（而不是让 bootstrap
 * engine 直接读 HostProfile）的理由——
 *  1. 与 [mtlsEnabled] / [clientCertId] / [tunnelPasswordId] 同源：这些都是
 *     「当前生效连接」的属性，由 resolver 集中解析（Profile 源 vs Manual 源），
 *     engine 只消费 EffectiveConnectionConfig、不直接碰 HostProfileStore——
 *     单一职责 + 测试隔离。
 *  2. `configuredKey != key` 比较：engine 用 EffectiveConnectionConfig 整体
 *     相等性判断是否需要重 configure；slim 是路由属性，切换 slim 状态必须触发
 *     重 configure（hostConfig.slim 变化 → SSE/REST/health 端点切换）。
 *  3. Manual 源的 slim：用户手动输 URL（未存为 profile）时，slim 沿用当前
 *     profile 的值（与 mtlsEnabled 同模式）——手动 URL 通常是用当前 server
 *     的另一个 endpoint，slim 状态延续符合用户预期。
 */
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
    val slim: Boolean = false,
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
            // R8 slim-mode foundation / Cluster B: 手动 URL 沿用当前 profile 的 slim
            // 标志（与 mtlsEnabled 同模式）——手动输入通常是同 server 的另一端点，
            // slim 路由属性延续符合用户预期。无 profile → false（legacy 直连）。
            slim = profile?.slim == true,
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
            // R8 slim-mode foundation / Cluster B: 直接透传 profile.slim。
            // engine 喂给 repository.configure → hostConfig.slim，供
            // SlimapiVersionInterceptor + SSEClient 路由使用。
            slim = profile.slim,
        )
    }
}
