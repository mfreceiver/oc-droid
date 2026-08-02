package cn.vectory.ocdroid.data.repository.http

import java.net.URI

/**
 * `host:port` authority from a URL. Defaults the port to the scheme default
 * (443 https / 80 http) when absent so the same endpoint always maps to the
 * same key. null for non-authority URLs.
 */
fun hostPortFromUrl(url: String): String? = runCatching {
    val u = URI(url)
    val host = u.host ?: return null
    val port = if (u.port != -1) u.port else when (u.scheme?.lowercase()) {
        "https" -> 443; "http" -> 80; else -> return null
    }
    "${host.lowercase()}:$port"  // DNS 大小写不敏感，lowercase 使 Example.com:443 == example.com:443
}.getOrNull()
