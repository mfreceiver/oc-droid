package cn.vectory.ocdroid.ui

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §phase2: unit tests for [NavRoute] workspace-family classification, the
 * typed Files route builder (workdir + path encoding), and the Changes
 * deep-link builder. Pins the §phase2 fixes:
 *   1. fromRouteKey classifies `workspace/files`, `workspace/changes?...`,
 *      `workspace` as Workspace (not Chat — the fix-6 regression).
 *   2. isNestedWorkspaceRoute distinguishes nested routes (for AppShell's
 *      back behaviour: nested pops, top-level routes back to Chat).
 *   3. workspaceFiles() builder encodes workdir + path; bare call yields the
 *      plain base route.
 *   4. workspaceChanges() round-trips (encode → decode).
 */
class NavRouteTest {

    // ── fromRouteKey: workspace family classification ───────────────────

    @Test
    fun `fromRouteKey classifies top-level routes exactly`() {
        assertEquals(NavRoute.Chat, NavRoute.fromRouteKey("chat"))
        assertEquals(NavRoute.Sessions, NavRoute.fromRouteKey("sessions"))
        assertEquals(NavRoute.Workspace, NavRoute.fromRouteKey("workspace"))
        assertEquals(NavRoute.Settings, NavRoute.fromRouteKey("settings"))
    }

    @Test
    fun `fromRouteKey classifies workspace files as Workspace not Chat`() {
        // §phase2 fix-6 regression: nested workspace routes fell back to
        // Chat (because fromRouteKey was exact-match-only). The nested
        // routes MUST classify as Workspace for nav-bar selection.
        assertEquals(
            "workspace/files (bare) → Workspace",
            NavRoute.Workspace,
            NavRoute.fromRouteKey("workspace/files"),
        )
        assertEquals(
            "workspace/files?workdir=X&path=Y → Workspace",
            NavRoute.Workspace,
            NavRoute.fromRouteKey("workspace/files?workdir=%2Frepo&path=src%2FA.kt"),
        )
    }

    @Test
    fun `fromRouteKey classifies workspace changes as Workspace not Chat`() {
        assertEquals(
            "workspace/changes?session=x → Workspace",
            NavRoute.Workspace,
            NavRoute.fromRouteKey("workspace/changes?session=abc%2F123"),
        )
    }

    @Test
    fun `fromRouteKey returns Chat for null and unknown routes`() {
        assertEquals(NavRoute.Chat, NavRoute.fromRouteKey(null))
        assertEquals(NavRoute.Chat, NavRoute.fromRouteKey(""))
        assertEquals(NavRoute.Chat, NavRoute.fromRouteKey("unknown/route"))
    }

    // ── isNestedWorkspaceRoute: back-behaviour classification ───────────

    @Test
    fun `isNestedWorkspaceRoute true for files and changes false for base workspace`() {
        // §phase2: AppShell uses this to let the NavHost pop nested routes
        // (Changes/Files → Workspace base) instead of bouncing to Chat.
        assertTrue(NavRoute.isNestedWorkspaceRoute("workspace/files"))
        assertTrue(NavRoute.isNestedWorkspaceRoute("workspace/files?workdir=x&path=y"))
        assertTrue(NavRoute.isNestedWorkspaceRoute("workspace/changes?session=x"))
        assertFalse(NavRoute.isNestedWorkspaceRoute("workspace"))
        assertFalse(NavRoute.isNestedWorkspaceRoute("chat"))
        assertFalse(NavRoute.isNestedWorkspaceRoute("sessions"))
        assertFalse(NavRoute.isNestedSettingsRoute("settings"))
        assertFalse(NavRoute.isNestedWorkspaceRoute(null))
    }

    // ── fromRouteKey: settings family classification (§phase3 G.3) ──────

    @Test
    fun `fromRouteKey classifies nested settings sub-routes as Settings not Chat`() {
        // §phase3 (G.3 / D.8): nested settings sub-routes (settings/hosts,
        // settings/appearance, ...) classify as Settings so the Settings nav
        // item stays selected while the user drills in. Mirrors the
        // workspace-family classification (§phase2 fix-6 regression: nested
        // routes fell back to Chat when fromRouteKey was exact-match-only).
        assertEquals(
            "settings/hosts → Settings",
            NavRoute.Settings,
            NavRoute.fromRouteKey("settings/hosts"),
        )
        assertEquals(
            "settings/appearance → Settings",
            NavRoute.Settings,
            NavRoute.fromRouteKey("settings/appearance"),
        )
        assertEquals(
            "settings/models → Settings",
            NavRoute.Settings,
            NavRoute.fromRouteKey("settings/models"),
        )
        assertEquals(
            "settings/notifications → Settings",
            NavRoute.Settings,
            NavRoute.fromRouteKey("settings/notifications"),
        )
        assertEquals(
            "settings/storage → Settings",
            NavRoute.Settings,
            NavRoute.fromRouteKey("settings/storage"),
        )
        assertEquals(
            "settings/about → Settings",
            NavRoute.Settings,
            NavRoute.fromRouteKey("settings/about"),
        )
        // Top-level settings exact match still classifies as Settings.
        assertEquals(
            "settings → Settings",
            NavRoute.Settings,
            NavRoute.fromRouteKey("settings"),
        )
    }

    // ── isNestedSettingsRoute: back-behaviour classification (§phase3) ──

    @Test
    fun `isNestedSettingsRoute true for sub-routes false for base settings`() {
        // §phase3 (G.3): AppShell uses this to let the NavHost pop nested
        // settings routes (settings/hosts → settings root) instead of
        // bouncing to Chat, mirroring isNestedWorkspaceRoute.
        assertTrue(NavRoute.isNestedSettingsRoute("settings/hosts"))
        assertTrue(NavRoute.isNestedSettingsRoute("settings/appearance"))
        assertTrue(NavRoute.isNestedSettingsRoute("settings/models"))
        assertTrue(NavRoute.isNestedSettingsRoute("settings/notifications"))
        assertTrue(NavRoute.isNestedSettingsRoute("settings/storage"))
        assertTrue(NavRoute.isNestedSettingsRoute("settings/about"))
        assertFalse(NavRoute.isNestedSettingsRoute("settings"))
        // Cross-family + null boundaries: a workspace nested route is NOT a
        // nested settings route, and vice versa.
        assertFalse(NavRoute.isNestedSettingsRoute("workspace/files"))
        assertFalse(NavRoute.isNestedSettingsRoute("workspace/changes?session=x"))
        assertFalse(NavRoute.isNestedSettingsRoute("chat"))
        assertFalse(NavRoute.isNestedSettingsRoute("sessions"))
        assertFalse(NavRoute.isNestedSettingsRoute(null))
    }

    // ── workspaceFiles builder: typed route with workdir + path ──────────

    @Test
    fun `workspaceFiles builder with no args yields bare base route`() {
        assertEquals("workspace/files", NavRoute.workspaceFiles())
        assertEquals("workspace/files", NavRoute.workspaceFiles(workdir = null, path = null))
    }

    @Test
    fun `workspaceFiles builder encodes workdir`() {
        val route = NavRoute.workspaceFiles(workdir = "/repo", path = null)
        assertEquals("workspace/files?workdir=%2Frepo", route)
    }

    @Test
    fun `workspaceFiles builder encodes both workdir and path`() {
        val route = NavRoute.workspaceFiles(workdir = "/repo", path = "src/A.kt")
        assertEquals("workspace/files?workdir=%2Frepo&path=src%2FA.kt", route)
    }

    @Test
    fun `workspaceFiles builder percent-encodes spaces and slashes`() {
        val route = NavRoute.workspaceFiles(workdir = "/my repo", path = "src/sub dir/A.kt")
        assertEquals(
            "workspace/files?workdir=%2Fmy%20repo&path=src%2Fsub%20dir%2FA.kt",
            route,
        )
    }

    @Test
    fun `workspaceFiles builder skips blank values`() {
        assertEquals("workspace/files", NavRoute.workspaceFiles(workdir = "", path = ""))
        assertEquals("workspace/files", NavRoute.workspaceFiles(workdir = "   ", path = null))
    }

    // ── workspaceChanges builder: encode → decode round-trip ────────────

    @Test
    fun `workspaceChanges builder percent-encodes the session id`() {
        assertEquals(
            "workspace/changes?session=session%2Fwith%20spaces",
            NavRoute.workspaceChanges("session/with spaces"),
        )
    }

    @Test
    fun `workspaceChanges round-trips through the route pattern`() {
        // The route pattern is "workspace/changes?session={session}". A built
        // route MUST be classifiable as Workspace via fromRouteKey AND the
        // nested-route check, so AppShell's nav + back wiring works.
        val sessionId = "sess-abc/def with spaces"
        val built = NavRoute.workspaceChanges(sessionId)
        assertEquals(NavRoute.Workspace, NavRoute.fromRouteKey(built))
        assertTrue(NavRoute.isNestedWorkspaceRoute(built))
    }

    @Test
    fun `workspaceFiles round-trips through the route pattern`() {
        val built = NavRoute.workspaceFiles(workdir = "/repo", path = "src/A.kt")
        assertEquals(NavRoute.Workspace, NavRoute.fromRouteKey(built))
        assertTrue(NavRoute.isNestedWorkspaceRoute(built))
    }

    // ── §B1-fix③: workspace/changes session-arg encode → decode contract ─

    /**
     * §B1-fix③: the route pattern declares `session={session}` as a nullable
     * navArgument in AppShell. The builder MUST encode the session id so the
     * arg parses back out; a bare (no-query) navigation MUST yield session=null.
     *
     * This helper mirrors Navigation Compose's query-param extraction (the same
     * parse `entry.arguments?.getString("session")` runs against the route's
     * query string) so the contract is JVM-testable without a NavHost.
     */
    private fun extractSessionParam(builtRoute: String): String? {
        val query = builtRoute.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&')
            .firstOrNull { it.startsWith("session=") }
            ?.removePrefix("session=")
            ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
            ?.takeIf { it.isNotEmpty() }
    }

    @Test
    fun `workspaceChangesRoute pattern declares the session placeholder`() {
        // The navArgument name MUST match the route pattern's {session} token.
        // A mismatch would silently drop the arg (the bug groker flagged).
        assertTrue(
            "route pattern must contain session={session}",
            NavRoute.workspaceChangesRoute.contains("session={session}"),
        )
    }

    @Test
    fun `workspaceChanges builder encodes session s1 and round-trips to session s1`() {
        val built = NavRoute.workspaceChanges("s1")
        assertEquals("s1", extractSessionParam(built))
    }

    @Test
    fun `workspaceChanges builder with complex session round-trips`() {
        val sessionId = "session/with spaces&amp;"
        val built = NavRoute.workspaceChanges(sessionId)
        assertEquals(sessionId, extractSessionParam(built))
    }

    @Test
    fun `bare changes route (no query) yields session null`() {
        // Navigating to the bare `workspace/changes` (session is a nullable query
        // param, so the pattern matches without it) MUST yield session=null —
        // the WorkspaceScaffold then falls back to the current session. This is
        // the "empty builder" case.
        assertNull(extractSessionParam("workspace/changes"))
    }

    // ── legacy page migration (unchanged, pinned) ───────────────────────

    @Test
    fun `fromLegacyPage maps 0 to Chat 1 to Sessions 2 to Settings`() {
        // §phase2偏差: legacy 2 was Settings, never Workspace (gpter #2).
        assertEquals(NavRoute.Chat, NavRoute.fromLegacyPage(0))
        assertEquals(NavRoute.Sessions, NavRoute.fromLegacyPage(1))
        assertEquals(NavRoute.Settings, NavRoute.fromLegacyPage(2))
        assertEquals(NavRoute.Chat, NavRoute.fromLegacyPage(-1))
        assertEquals(NavRoute.Chat, NavRoute.fromLegacyPage(99))
    }
}
