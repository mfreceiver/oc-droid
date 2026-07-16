package cn.vectory.ocdroid.ui

/**
 * In-memory mirror of the persisted stable route key.
 *
 * [lastNavPage] is the legacy integer projection derived through
 * [NavRoute.legacyPage]; it is not a persistence identity ([lastRoute] is).
 * AppShell is the sole shell (the legacy PhoneLayout + USE_NEW_SHELL flag
 * were removed in the redesign); [lastNavPage] is retained only for the
 * one-time migration in [cn.vectory.ocdroid.util.SettingsManager.lastRoute].
 *
 * §home-hub T7-C5 (cold-start-stays-home): the initial value is the Sessions
 * route (the new home hub), NOT Chat and NOT the persisted lastRoute. AppShell
 * never restores the persisted lastRoute on cold start (the old
 * `restoreLastRoute()` method was removed as dead code — its sole caller, the
 * cold-start `LaunchedEffect(Unit){…}`, was deleted in T7), so this initial
 * value must match `NavHost.startDestination` exactly — that equality is what
 * suppresses the `LaunchedEffect(requestedRoute)` hop on cold start
 * (`requestedRoute == currentRoute == Sessions` → no navigate). Explicit
 * `setLastRoute(...)` calls (deeplink / notification / in-session navigation)
 * still mutate this slice and re-fire the hop.
 */
data class NavState(
    val lastRoute: String = NavRoute.Sessions.route,
    val lastNavPage: Int = NavRoute.Sessions.legacyPage,
)
