package cn.vectory.ocdroid.ui

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavRouteTest {
    @Test
    fun `fromRouteKey classifies all top-level routes exactly`() {
        assertEquals(NavRoute.Chat, NavRoute.fromRouteKey("chat"))
        assertEquals(NavRoute.Sessions, NavRoute.fromRouteKey("sessions"))
        assertEquals(NavRoute.Files, NavRoute.fromRouteKey("files"))
        assertEquals(NavRoute.Git, NavRoute.fromRouteKey("git"))
        assertEquals(NavRoute.Settings, NavRoute.fromRouteKey("settings"))
    }

    @Test
    fun `fromRouteKey classifies parameterized top-level routes`() {
        assertEquals(NavRoute.Files, NavRoute.fromRouteKey("files?workdir=%2Frepo"))
        assertEquals(NavRoute.Git, NavRoute.fromRouteKey("git?session=abc"))
        assertEquals(NavRoute.Settings, NavRoute.fromRouteKey("settings/hosts"))
        assertEquals(NavRoute.Chat, NavRoute.fromRouteKey("chat/preview?workdir=x&path=y"))
    }

    @Test
    fun `chatPreviewRoute encodes optional workdir and path`() {
        assertEquals("chat/preview", NavRoute.chatPreviewRoute())
        assertEquals(
            "chat/preview?workdir=%2Fmy%20repo&path=src%2FA.kt",
            NavRoute.chatPreviewRoute("/my repo", "src/A.kt"),
        )
        assertEquals("chat/preview", NavRoute.chatPreviewRoute(" ", ""))
        assertTrue(NavRoute.chatPreviewRoutePattern.contains("workdir={workdir}&path={path}"))
    }

    @Test
    fun `fromRouteKey classifies nested settings routes and rejects removed workspace family`() {
        assertEquals(NavRoute.Settings, NavRoute.fromRouteKey("settings/hosts"))
        assertEquals(NavRoute.Chat, NavRoute.fromRouteKey("workspace"))
        assertEquals(NavRoute.Chat, NavRoute.fromRouteKey("workspace/files"))
        assertEquals(NavRoute.Chat, NavRoute.fromRouteKey(null))
        assertEquals(NavRoute.Chat, NavRoute.fromRouteKey("unknown"))
    }

    @Test
    fun `settings nested-route classification only matches settings children`() {
        assertTrue(NavRoute.isNestedSettingsRoute("settings/about"))
        assertFalse(NavRoute.isNestedSettingsRoute("settings"))
        assertFalse(NavRoute.isNestedSettingsRoute("files"))
        assertFalse(NavRoute.isNestedSettingsRoute(null))
    }

    @Test
    fun `homeRoute returns each top-level destination`() {
        NavRoute.topLevel.forEach { assertEquals(it.route, NavRoute.homeRoute(it)) }
    }

    @Test
    fun `filesRoute encodes optional workdir and path`() {
        assertEquals("files", NavRoute.filesRoute())
        assertEquals(
            "files?workdir=%2Fmy%20repo&path=src%2FA.kt",
            NavRoute.filesRoute("/my repo", "src/A.kt"),
        )
        assertEquals("files", NavRoute.filesRoute(" ", ""))
        assertTrue(NavRoute.filesRoutePattern.contains("workdir={workdir}&path={path}"))
    }

    @Test
    fun `gitRoute encodes and round-trips optional session`() {
        assertEquals("git", NavRoute.gitRoute(null))
        val built = NavRoute.gitRoute("session/with spaces")
        assertEquals("git?session=session%2Fwith%20spaces", built)
        assertEquals("session/with spaces", extractQueryParam(built, "session"))
        assertTrue(NavRoute.gitRoutePattern.contains("session={session}"))
        assertNull(extractQueryParam("git", "session"))
    }

    @Test
    fun `legacy page mapping remains unchanged`() {
        assertEquals(NavRoute.Chat, NavRoute.fromLegacyPage(0))
        assertEquals(NavRoute.Sessions, NavRoute.fromLegacyPage(1))
        assertEquals(NavRoute.Settings, NavRoute.fromLegacyPage(2))
        assertEquals(NavRoute.Chat, NavRoute.fromLegacyPage(99))
    }

    private fun extractQueryParam(route: String, name: String): String? =
        route.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
            ?.takeIf(String::isNotEmpty)
}
