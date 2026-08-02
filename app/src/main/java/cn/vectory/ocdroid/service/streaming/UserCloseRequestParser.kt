package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore

/**
 * D2 (gate #8 / §16-U1): pure-JVM builder for [UserCloseRequest] from the
 * four identity fields an Android `Intent` extra bundle can carry.
 *
 * Extracted from the Android-side
 * [cn.vectory.ocdroid.service.SessionStreamingService] so the
 * "stale-identity → no teardown" decision is unit-testable without
 * Robolectric: the Service reads its `Intent` extras (4 primitives) and
 * hands them here; the result flows through
 * [cn.vectory.ocdroid.service.StartCommandRouter] into
 * [SessionStreamingController.handleUserClose].
 *
 * - All four fields present + non-null → [UserCloseRequest] with that
 *   [ConnectionIdentity].
 * - Any field missing/null → [UserCloseRequest] with `expectedIdentity = null`
 *   (the §5 degraded placeholder / TOFU-pending notification — no host was
 *   ever bound; §16-U1 lets the close action dismiss it directly).
 *
 * The companion `EXTRA_*` constants are the canonical keys both the
 * Service-side writer (`PendingIntent` extras) and reader (`Intent.get*Extra`)
 * reference — single source of truth. They live on the parser (pure JVM)
 * so the test fixture can use them without depending on the Android Service.
 *
 * §需求12 阶段6: the identity field carried by [EXTRA_PROFILE_ID] is exactly
 * the bound [ConnectionIdentity.profileId] (== the active HostProfile's `id`,
 * or `"manual:$url"` for a manual connection with no profile). The legacy
 * `serverGroupFp` extra name was renamed because (a) the server-group concept
 * is gone (需求12 阶段3) and (b) every send/receive site is internal self-send
 * to this app's `SessionStreamingService` (`exported="false"`, explicit
 * component Intent), so there is no external IPC consumer to break.
 */
object UserCloseRequestParser {

    const val EXTRA_EPOCH = "cn.vectory.ocdroid.extra.close.epoch"
    const val EXTRA_PROFILE_ID = "cn.vectory.ocdroid.extra.close.profileId"
    const val EXTRA_NORMALIZED_WORKDIR = "cn.vectory.ocdroid.extra.close.normalizedWorkdir"
    const val EXTRA_ENDPOINT_FP = "cn.vectory.ocdroid.extra.close.endpointFp"

    /**
     * Builds [UserCloseRequest] from the four identity fields the Service
     * extracted from the `Intent` extras. Pure JVM — no Android deps.
     *
     * The [profileId] argument is the value of [EXTRA_PROFILE_ID]: the active
     * profile's `id` (or `"manual:$url"` for a profile-less manual connection).
     */
    fun parse(
        epoch: Long?,
        profileId: String?,
        normalizedWorkdir: String?,
        endpointFp: String?,
    ): UserCloseRequest {
        val identity = if (epoch != null &&
            profileId != null &&
            normalizedWorkdir != null &&
            endpointFp != null
        ) {
            ConnectionIdentity(
                epoch = epoch,
                profileId = profileId,
                normalizedWorkdir = normalizedWorkdir,
                endpointFp = endpointFp,
            )
        } else {
            null
        }
        return UserCloseRequest(expectedIdentity = identity)
    }

    /**
     * D2 (gate #8): is [expected] still current per [store]? Null expected
     * is treated as "no identity to revalidate" (the degraded-placeholder
     * close path) and returns `true` — the close handler dismisses the
     * placeholder directly.
     *
     * - Null expected → `true` (no revalidation needed; degraded placeholder).
     * - Non-null expected + no current in store → `false` (stale; the host
     *   reconfigured away).
     * - Non-null expected + current in store → `store.isCurrent(expected)`.
     */
    fun isStillCurrent(
        expected: ConnectionIdentity?,
        store: ConnectionIdentityStore,
    ): Boolean {
        if (expected == null) return true
        return store.isCurrent(expected)
    }
}
