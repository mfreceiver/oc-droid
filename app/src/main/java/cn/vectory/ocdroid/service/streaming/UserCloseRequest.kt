package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.service.identity.ConnectionIdentity

/**
 * D2 (gate #8 / §16-U1): the parsed request carried by the ongoing-notification
 * 「关闭后台」Action. Built by [UserCloseRequestParser] from the PendingIntent's
 * Intent extras, passed through the [cn.vectory.ocdroid.service.StartCommandRouter]
 * to [cn.vectory.ocdroid.service.streaming.SessionStreamingController.handleUserClose].
 *
 * **Why the identity is on the request** (§16-U1 implementation note "点击后
 * **重新查询 identity/status**"): the ongoing notification was built at some
 * earlier point using a [ConnectionIdentity] the host held then; by the time
 * the user taps「关闭后台」the host may have reconfigured (user switched host /
 * workdir / endpoint in Settings, or a sticky rebuild landed a different
 * epoch). Acting on the stale notification's identity would tear down the
 * NEW host's session — wrong. So the PendingIntent carries the identity it
 * was built with, and the close handler revalidates it against the
 * [cn.vectory.ocdroid.service.identity.ConnectionIdentityStore] current
 * identity before any teardown side-effect.
 *
 * **Degraded / placeholder no-identity case** ([expectedIdentity] == null):
 * the §5 degraded notification (TOFU pending, no successful bootstrap yet)
 * has no identity attached (no host was ever bound), yet the user can still
 * dismiss it. In that case the close handler skips the identity revalidation
 * and tears down directly — there is no "new host" to be torn down by
 * mistake.
 *
 * @property expectedIdentity the identity the ongoing notification was built
 *  with, or null when the notification was the §5 degraded placeholder (no
 *  host bound yet).
 */
data class UserCloseRequest(
    val expectedIdentity: ConnectionIdentity?,
)
