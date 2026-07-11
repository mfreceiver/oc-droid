package cn.vectory.ocdroid.ui

/**
 * In-memory mirror of the persisted stable route key.
 *
 * [lastNavPage] remains only while the opt-in legacy PhoneLayout exists; it is
 * derived through [NavRoute.legacyPage] and is not a persistence identity.
 */
data class NavState(
    val lastRoute: String = NavRoute.Chat.route,
    val lastNavPage: Int = NavRoute.Chat.legacyPage,
)
