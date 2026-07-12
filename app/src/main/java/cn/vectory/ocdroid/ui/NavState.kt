package cn.vectory.ocdroid.ui

/**
 * In-memory mirror of the persisted stable route key.
 *
 * [lastNavPage] is the legacy integer projection derived through
 * [NavRoute.legacyPage]; it is not a persistence identity ([lastRoute] is).
 * AppShell is the sole shell (the legacy PhoneLayout + USE_NEW_SHELL flag
 * were removed in the redesign); [lastNavPage] is retained only for the
 * one-time migration in [cn.vectory.ocdroid.util.SettingsManager.lastRoute].
 */
data class NavState(
    val lastRoute: String = NavRoute.Chat.route,
    val lastNavPage: Int = NavRoute.Chat.legacyPage,
)
