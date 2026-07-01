package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.data.repository.HostConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §Stage D (gpter 阻塞 #2): strips the configured baseUrl's path prefix from
 * a request path so the cache whitelist can EXACT-match the canonical
 * endpoint shape even when the API is deployed behind a sub-path (e.g.
 * baseUrl `http://host/opencode` + request `/opencode/agent` → `/agent`).
 *
 * Returns the request path unchanged when the baseUrl has no path prefix,
 * which keeps the bare-host deployment (the common case) on the same
 * exact-match behavior. Uses simple string slicing (consistent with the
 * Retrofit baseUrl `trimEnd('/') + "/"` normalization) rather than okhttp's
 * URL parser so the helper stays dependency-free and unit-testable.
 *
 * Extracted verbatim from `OpenCodeRepository.cacheRelativePath` in R-18;
 * behavior preserved byte-for-byte.
 */
@Singleton
class CachePathSanitizer @Inject constructor(
    private val hostConfig: HostConfig
) {

    /**
     * Strips the configured baseUrl's path prefix from [requestPath]. Pure
     * function of the current `hostConfig.baseUrl` snapshot — safe to call
     * from interceptor threads.
     */
    fun cacheRelativePath(requestPath: String): String {
        val baseUrl = hostConfig.baseUrl
        val protocolEnd = baseUrl.indexOf("://")
        val hostStart = if (protocolEnd >= 0) protocolEnd + 3 else 0
        val pathStart = baseUrl.indexOf('/', hostStart)
        val basePath = if (pathStart >= 0) baseUrl.substring(pathStart).trimEnd('/') else ""
        return if (basePath.isNotEmpty() && requestPath.startsWith("$basePath/")) {
            requestPath.removePrefix(basePath)
        } else {
            requestPath
        }
    }
}
