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
 *
 * @property navEpoch §B3-C2: monotonic counter bumped by
 *   [forceNavigateToSessions] and [OrchestratorViewModel.navigateToChat]
 *   so the [DerivedStateFlow] emits even when [lastRoute] (and the rest of the
 *   data class) is structurally equal to the previous value. [setLastRoute]
 *   does NOT touch [navEpoch] (its short-circuit guard is intentional for the
 *   Chat/Settings clean-exit path).
 */
data class NavState(
    val lastRoute: String = NavRoute.Sessions.route,
    /** §B6: lastNavPage 是 lastRoute 的旧整数镜像，不再作为导航权威。保留以不破坏现有写点。 */
    @Deprecated("保留仅因 SettingsManager/OrchestratorVM 仍有写点；导航权威是 lastRoute")
    val lastNavPage: Int = NavRoute.Sessions.legacyPage,
    /** §B3-C2: monotonic nav epoch. Bumped by forceNavigateToSessions and navigateToChat. */
    val navEpoch: Long = 0L,
)
