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
 * `requestNavigate(...)` (new-draft → Chat / server popup → Settings / etc.)
 * and `navigateToChat(...)` (deeplink / notification / session-tap) still
 * mutate this slice and re-fire the hop — both bump navEpoch so the
 * synchronizer re-fires even on same-target re-entry. `setLastRoute(...)` is
 * now used ONLY by the AppShell destination observer (A3 passive mirror
 * reconciliation) and does NOT re-fire the hop (no epoch bump).
 *
 * @property navEpoch §unified-nav: monotonic counter bumped ONLY by
 *   [OrchestratorViewModel.requestNavigate] (the explicit nav-command setter)
 *   so the [DerivedStateFlow] emits even when [lastRoute] (and the rest of the
 *   data class) is structurally equal to the previous value. The AppShell nav
 *   synchronizer observes BOTH [lastRoute] and [navEpoch], so a same-target
 *   re-navigation (item 6 / item 8) re-fires the synchronizer via the epoch bump.
 *   [setLastRoute] is now the PASSIVE mirror setter (A3 destination observer)
 *   and NEVER touches [navEpoch] — that is intentional, so the passive mirror
 *   reconciliation cannot feed the nav syncer into a loop.
 * @property activeDestination §unified-nav (A5.2): the runtime-only resolved
 *   actual destination pattern written by the AppShell destination listener
 *   (e.g. `"chat"`, `"chat/ses_X"`, `"sessions"`, `"settings"`, `"files/..."`).
 *   NOT persisted (the app cold-starts at Sessions; [activeDestination] /
 *   [activeDestinationEpoch] are runtime-only). Used by
 *   [cn.vectory.ocdroid.ui.DraftRouteOrigin.stillOwnsDraftSurface] to detect
 *   whether the user navigated away mid-create during a draft materialize. Bumped
 *   via [cn.vectory.ocdroid.ui.OrchestratorVM-activeDestination passive setter]
 *   which does NOT touch [lastRoute] / [navEpoch] (so it never fires the nav
 *   syncer).
 * @property activeDestinationEpoch monotonic counter bumped alongside every
 *   [activeDestination] write so a CAS comparing the captured origin against
 *   the live snapshot detects a navigation that returned to the SAME
 *   destination string (epoch changed even though the string is identical).
 */
data class NavState(
    val lastRoute: String = NavRoute.Sessions.route,
    /** §B6: lastNavPage 是 lastRoute 的旧整数镜像，不再作为导航权威。保留以不破坏现有写点。 */
    @Deprecated("保留仅因 SettingsManager/OrchestratorVM 仍有写点；导航权威是 lastRoute")
    val lastNavPage: Int = NavRoute.Sessions.legacyPage,
    /** §unified-nav: monotonic nav epoch. Bumped ONLY by requestNavigate (the
     *  explicit nav-command setter). setLastRoute (passive mirror) never touches it. */
    val navEpoch: Long = 0L,
    /** §unified-nav (A5.2): runtime-only resolved actual destination (NOT persisted). */
    val activeDestination: String? = null,
    /** §unified-nav (A5.2): monotonic counter bumped with every activeDestination write. */
    val activeDestinationEpoch: Long = 0L,
)
