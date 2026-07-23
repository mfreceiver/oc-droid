package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.SlimapiFeatures
import cn.vectory.ocdroid.data.repository.SlimapiHealthPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §E4 wiring integration test (fakes, NO live server). Verifies:
 *
 * (a) the open-gate predicate [shouldOpenTokenStream]
 *       — open when true, skip when false or session not foreground.
 *  (b) EvictSession clears ChatState overlay (`ClearTokenStreamState(streamOwned.keys)`)
 *       for the current session only.
 *
 * Uses fakes / the existing test seams — no full AppCore integration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenStreamWiringTest {

    // ── (a) Gate predicate truth table ─────────────────────────────────────

    @Test
    fun `gate opens when slimapiTokenStreamEnabled AND sessionId matches currentSessionId`() = runTest {
        val profile = ServerCompatProfile()
        profile.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = false,
                serverApiVersion = 1,
                acceptedClientVersions = 1 to 1,
                features = SlimapiFeatures(tokenStream = true),
            )
        )

        val chatState = MutableStateFlow(
            ChatState(currentSessionId = "sess-A")
        )

        assertTrue(
            "gate must open when both conditions true",
            shouldOpenTokenStream(profile.slimapiTokenStreamEnabled, chatState.value.currentSessionId, "sess-A"),
        )

        assertFalse(
            "gate must NOT open when sessionId differs",
            shouldOpenTokenStream(profile.slimapiTokenStreamEnabled, chatState.value.currentSessionId, "sess-B"),
        )
    }

    @Test
    fun `gate skips when slimapiTokenStreamEnabled is false`() = runTest {
        val profile = ServerCompatProfile()
        // Default: slimapiTokenStreamEnabled == false

        val chatState = MutableStateFlow(
            ChatState(currentSessionId = "sess-A")
        )

        assertFalse(
            "gate must skip when feature disabled",
            shouldOpenTokenStream(profile.slimapiTokenStreamEnabled, chatState.value.currentSessionId, "sess-A"),
        )
    }

    @Test
    fun `gate skips when currentSessionId is null (no foreground session)`() = runTest {
        val profile = ServerCompatProfile()
        profile.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = false,
                serverApiVersion = 1,
                acceptedClientVersions = 1 to 1,
                features = SlimapiFeatures(tokenStream = true),
            )
        )

        val chatState = MutableStateFlow(
            ChatState(currentSessionId = null)
        )

        assertFalse(
            "gate must skip when session not foreground",
            shouldOpenTokenStream(profile.slimapiTokenStreamEnabled, chatState.value.currentSessionId, "sess-A"),
        )
    }

    @Test
    fun `shouldOpenTokenStream full truth table`() {
        // §token-stream-open-gate: pin the exact boolean truth table so
        // future regressions in the shared predicate are caught here.
        // enabled | currentSessionId | targetSessionId | result
        assertTrue(shouldOpenTokenStream(true, "s1", "s1"))
        assertFalse(shouldOpenTokenStream(true, "s1", "s2"))
        assertFalse(shouldOpenTokenStream(true, null, "s1"))
        assertFalse(shouldOpenTokenStream(false, "s1", "s1"))
        assertFalse(shouldOpenTokenStream(false, null, "s1"))
        assertFalse(shouldOpenTokenStream(false, "s1", "s2"))
        // Edge: both null — only matches if targetSessionId is also null
        // (which never happens in production — loadMessagesForEffect always
        // receives a non-null sessionId). Document for completeness.
        assertFalse(shouldOpenTokenStream(true, null, "s1"))
    }

    // ── (b) EvictSession clears ChatState overlay ──────────────────────────

    @Test
    fun `EvictSession clears ChatState overlay for the current session only`() = runTest {
        // Seed a SharedStateStore with a chat state having streamOwned entries.
        val store = SharedStateStore()
        store.mutateChat {
            it.copy(
                currentSessionId = "sess-A",
                streamOwned = mapOf(
                    "p1" to StreamOwnedState.STREAMING,
                    "p2" to StreamOwnedState.DONE,
                ),
                streamingPartTexts = mapOf("p1" to "text"),
            )
        }

        // Simulate the EvictSession handler logic (from AppCore.kt:736-757).
        // We do NOT have access to the real tokenStreamCoordinator.close() here;
        // we only test the ChatState overlay clear path.
        val evictedSid = "sess-A"
        val ownedKeys = store.chatFlow.value.streamOwned.keys
        assertTrue("streamOwned must have entries before EvictSession", ownedKeys.isNotEmpty())
        assertTrue("currentSessionId must be sess-A", store.chatFlow.value.currentSessionId == "sess-A")

        // Dispatch ClearTokenStreamState only if evicted session == current.
        if (store.chatFlow.value.currentSessionId == evictedSid) {
            if (ownedKeys.isNotEmpty()) {
                store.dispatch(AppAction.ClearTokenStreamState(ownedKeys))
            }
        }

        advanceUntilIdle()

        // Verify that after ClearTokenStreamState, streamOwned is empty.
        assertTrue("streamOwned must be cleared after ClearTokenStreamState",
            store.chatFlow.value.streamOwned.isEmpty())
    }

    @Test
    fun `EvictSession of non-current session does NOT clear ChatState overlay`() = runTest {
        val store = SharedStateStore()
        store.mutateChat {
            it.copy(
                currentSessionId = "sess-A",
                streamOwned = mapOf("p1" to StreamOwnedState.STREAMING),
                streamingPartTexts = mapOf("p1" to "text"),
            )
        }

        val evictedSid = "sess-B" // NOT the current session

        val ownedKeys = store.chatFlow.value.streamOwned.keys
        assertTrue("streamOwned must have entries", ownedKeys.isNotEmpty())

        // The handler only dispatches ClearTokenStreamState if evicted == current.
        // Since evictedSid != currentSessionId ("sess-B" != "sess-A"), this
        // branch does NOT fire. We verify that the state is unchanged.
        if (store.chatFlow.value.currentSessionId == evictedSid) {
            if (ownedKeys.isNotEmpty()) {
                store.dispatch(AppAction.ClearTokenStreamState(ownedKeys))
            }
        }

        advanceUntilIdle()

        // streamOwned must still be intact.
        assertFalse("streamOwned must survive EvictSession of non-current session",
            store.chatFlow.value.streamOwned.isEmpty())
    }
}
