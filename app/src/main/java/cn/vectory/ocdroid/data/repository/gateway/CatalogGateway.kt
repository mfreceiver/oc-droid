package cn.vectory.ocdroid.data.repository.gateway

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.CommandInfo
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching
import java.io.IOException

/**
 * Gateway for read-only catalog operations: provider/model picker, agents, commands.
 *
 * Zero mutable state — all reads go through [bundleProvider] every call,
 * preserving the generational-consistency invariant.
 */
internal class CatalogGateway(
    private val bundleProvider: () -> ClientBundle,
    private val serverCompatProfile: ServerCompatProfile,
) {
    private val api: OpenCodeApi get() = bundleProvider().restApi

    suspend fun getProviders(): Result<ProvidersResponse> {
        val response: ProvidersResponse = try {
            api.getProviders()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("CatalogGateway", "catalog: /config/providers fetch failed, returning empty catalog", e)
            return Result.success(ProvidersResponse(providers = emptyList()))
        }
        val providers = response.providers.filter { it.models.isNotEmpty() }
        val totalModels = providers.sumOf { it.models.size }
        DebugLog.i("CatalogGateway", "catalog: ${providers.size} provider(s), $totalModels model(s) from /config/providers")
        return Result.success(ProvidersResponse(providers = providers, defaultByProvider = response.defaultByProvider))
    }

    suspend fun getProvidersOrFailure(): Result<ProvidersResponse> {
        val response: ProvidersResponse = try {
            api.getProviders()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("CatalogGateway", "catalog: /config/providers fetch failed (propagating as failure)", e)
            return Result.failure(e)
        }
        val providers = response.providers.filter { it.models.isNotEmpty() }
        val totalModels = providers.sumOf { it.models.size }
        DebugLog.i("CatalogGateway", "catalog: ${providers.size} provider(s), $totalModels model(s) from /config/providers")
        return Result.success(ProvidersResponse(providers = providers, defaultByProvider = response.defaultByProvider))
    }

    suspend fun getAgents(): Result<List<AgentInfo>> {
        if (serverCompatProfile.slimConnection && serverCompatProfile.useSlimCatalog) {
            return runSuspendCatching {
                val resp = api.getSlimapiAgents()
                if (resp.isSuccessful) {
                    serverCompatProfile.markSlimCatalogSupported()
                    resp.body() ?: emptyList()
                } else if (resp.code() == 404) {
                    serverCompatProfile.markSlimCatalogUnsupported()
                    DebugLog.w(
                        "CatalogGateway",
                        "slimapi /slimapi/agent 404 (old sidecar) → fallback to standard API",
                    )
                    api.getAgents()
                } else {
                    // 503 upstream_unavailable/transform_busy, 413, timeout, version/auth
                    // errors are transient/operational — NEVER fall back (would double
                    // traffic + mask the outage). Propagate so circuit breakers/retry fire.
                    throw IOException("slimapi /slimapi/agent HTTP ${resp.code()}")
                }
            }
        }
        return runSuspendCatching { api.getAgents() }
    }

    suspend fun getCommands(): Result<List<CommandInfo>> {
        if (serverCompatProfile.slimConnection && serverCompatProfile.useSlimCatalog) {
            return runSuspendCatching {
                val resp = api.getSlimapiCommands()
                if (resp.isSuccessful) {
                    serverCompatProfile.markSlimCatalogSupported()
                    resp.body() ?: emptyList()
                } else if (resp.code() == 404) {
                    serverCompatProfile.markSlimCatalogUnsupported()
                    DebugLog.w(
                        "CatalogGateway",
                        "slimapi /slimapi/command 404 (old sidecar) → fallback to standard API",
                    )
                    api.getCommands()
                } else {
                    throw IOException("slimapi /slimapi/command HTTP ${resp.code()}")
                }
            }
        }
        return runSuspendCatching { api.getCommands() }
    }
}
