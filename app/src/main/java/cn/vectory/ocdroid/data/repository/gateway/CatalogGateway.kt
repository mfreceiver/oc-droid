package cn.vectory.ocdroid.data.repository.gateway

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.CommandInfo
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.runSuspendCatching

/**
 * Gateway for read-only catalog operations: provider/model picker, agents, commands.
 *
 * Zero mutable state — all reads go through [bundleProvider] every call,
 * preserving the generational-consistency invariant.
 */
internal class CatalogGateway(
    private val bundleProvider: () -> ClientBundle,
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

    suspend fun getAgents(): Result<List<AgentInfo>> =
        runSuspendCatching { api.getAgents() }

    suspend fun getCommands(): Result<List<CommandInfo>> =
        runSuspendCatching { api.getCommands() }
}
