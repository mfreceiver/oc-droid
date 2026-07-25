package cn.vectory.ocdroid.data.repository

/**
 * P11 §4 (Option B: repo-owned [RepositoryNetworkGraph]): immutable snapshot
 * of the host connection profile captured at [OpenCodeRepository.configure]
 * time. This is the value the `ClientBundle` publishes atomically and that
 * host-dependent interceptors capture instead of
 * reading the mutable live [HostConfig] holder.
 *
 * **Wire contract (P11 §R3)**: the `slimHost` flag is the only `slim`
 * surface permitted at the repo / DI / source-assembling layer. UI / service
 * / DI business code MUST NOT branch on a slim boolean — that routing lives
 * behind [OpenCodeRepository] (R1/R2 — see
 * `2026-07-25-remaining-waves-execution-plan.md`).
 *
 * Host-dependent interceptors capture this value when a candidate
 * `ClientBundle` is built; they never retain the mutable [HostConfig] mirror.
 *
 * Field semantics mirror the four [HostConfig] readable properties + the
 * derived `host:port` authority used by the TOFU pin store:
 *  - [baseUrl]            ← `HostConfig.baseUrl`
 *  - [hostPort]           ← `HostConfig.hostPort` (String? authority)
 *  - [username]/[password]← `HostConfig.username` / `password`
 *  - [slimHost]           ← `HostConfig.slim` (renamed to distance it from
 *                            the legacy `slimMode` parameter name; §R3)
 */
internal data class HostSnapshot(
    val baseUrl: String,
    val hostPort: String?,
    val username: String?,
    val password: String?,
    /**
     * §R3: only repo / DI / source-assembling code reads this flag.
     * UI / service / DI business code MUST NOT branch on it.
     */
    val slimHost: Boolean,
) {
    val hasBasicAuth: Boolean get() = username != null && password != null

    companion object {
        internal fun from(
            baseUrl: String,
            username: String?,
            password: String?,
            hostPort: String?,
            slimHost: Boolean,
        ): HostSnapshot = HostSnapshot(
            baseUrl = baseUrl,
            hostPort = hostPort ?: cn.vectory.ocdroid.data.repository.http.hostPortFromUrl(baseUrl),
            username = username,
            password = password,
            slimHost = slimHost,
        )
    }
}
