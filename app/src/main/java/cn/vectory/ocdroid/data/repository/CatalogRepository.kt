package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.CommandInfo
import cn.vectory.ocdroid.data.model.ProvidersResponse

/** Phase B narrow seam: read-only provider/agent/command catalog. Implemented by [OpenCodeRepository]. */
interface CatalogRepository {
    suspend fun getProviders(): Result<ProvidersResponse>
    suspend fun getProvidersOrFailure(): Result<ProvidersResponse>
    suspend fun getAgents(): Result<List<AgentInfo>>
    suspend fun getCommands(): Result<List<CommandInfo>>
}
