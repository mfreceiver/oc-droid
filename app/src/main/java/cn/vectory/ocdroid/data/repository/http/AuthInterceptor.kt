package cn.vectory.ocdroid.data.repository.http

import cn.vectory.ocdroid.data.repository.HostConfig
import cn.vectory.ocdroid.data.repository.HostSnapshot
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Base64

/**
 * Basic Auth injector: when the current host profile has both a username and
 * a password configured, every request gets an `Authorization: Basic <...>`
 * header. Split out of the pre-R-18 combined header/auth/cache interceptor
 * for testability.
 *
 * Behavior preserved byte-for-byte: the header is added only when BOTH
 * username and password are non-null. The credential pair is encoded as
 * UTF-8 bytes before Base64 encoding, matching the original implementation.
 *
 * Uses `.header(name, value)` (which replaces any pre-existing
 * Authorization) rather than `.addHeader(...)` — the SSE request builder
 * independently sets Authorization on its own request, and when the chain
 * reaches this interceptor the values come from the same source
 * (`HostConfig.username` / `password`), so the replace semantics are a
 * no-op in practice but defensive against accidental duplicates.
 */
class AuthInterceptor internal constructor(
    private val hostSnapshot: HostSnapshot
) : Interceptor {

    /** Compatibility constructor: capture, never retain, the mutable holder. */
    constructor(hostConfig: HostConfig) : this(hostConfig.snapshot())

    override fun intercept(chain: Interceptor.Chain): Response {
        val username = hostSnapshot.username ?: return chain.proceed(chain.request())
        val password = hostSnapshot.password ?: return chain.proceed(chain.request())
        val credential = "$username:$password"
        val encoded = Base64.getEncoder().encodeToString(credential.toByteArray())
        val authenticated = chain.request().newBuilder()
            .header("Authorization", "Basic $encoded")
            .build()
        return chain.proceed(authenticated)
    }
}
