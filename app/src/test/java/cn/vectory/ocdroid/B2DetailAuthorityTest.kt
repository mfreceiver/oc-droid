package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.BundleStamp
import cn.vectory.ocdroid.ui.LoadedContent
import cn.vectory.ocdroid.ui.StoreState
import cn.vectory.ocdroid.ui.StreamOwnedState
import cn.vectory.ocdroid.ui.reduce
import cn.vectory.ocdroid.ui.chat.chromeSessionIdFor
import cn.vectory.ocdroid.ui.chat.isRouteContentRenderable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class B2DetailAuthorityTest {
    private fun content(id: String, token: Long) = LoadedContent(
        sessionId = id,
        messages = listOf(Message(id = "m-$id", role = "user")),
        routeInstance = token,
    )

    @Test fun `route B plus content A is not renderable`() {
        assertFalse(isRouteContentRenderable("B", content("A", 4), 4))
    }

    @Test fun `stale token is not renderable`() {
        assertFalse(isRouteContentRenderable("A", content("A", 4), 5))
    }

    @Test fun `matching route content and token is renderable`() {
        assertTrue(isRouteContentRenderable("A", content("A", 4), 4))
    }

    @Test fun `currentSessionId disagreement does not change route predicate`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 4,
            chat = StoreState.initial().chat.copy(
                currentSessionId = "B",
                content = content("A", 4),
            ),
        )
        assertTrue(isRouteContentRenderable("A", state.chat.content, state.chatRouteInstance))
        assertFalse(isRouteContentRenderable("B", state.chat.content, state.chatRouteInstance))
    }

    @Test fun `close clears content and flat payload`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 4,
            chat = StoreState.initial().chat.copy(
                currentSessionId = "A",
                content = content("A", 4),
                messages = listOf(Message(id = "flat", role = "user")),
            ),
        )
        val out = reduce(state, AppAction.CloseDetail)
        assertFalse(isRouteContentRenderable("A", out.chat.content, out.chatRouteInstance))
        assertTrue(out.chat.messages.isEmpty())
    }

    @Test fun `detail missing clears content and preserves monotonic token`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 7,
            chat = StoreState.initial().chat.copy(content = content("A", 7), messages = listOf(Message(id = "flat", role = "user"))),
        )
        val out = reduce(state, AppAction.DetailMissing("A", 7))
        assertFalse(isRouteContentRenderable("A", out.chat.content, out.chatRouteInstance))
        assertTrue(out.chat.messages.isEmpty())
        assertTrue(out.chatRouteInstance == 7L)
    }

    @Test fun `stale detail missing for another route preserves active content`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 8,
            chat = StoreState.initial().chat.copy(
                currentSessionId = "B",
                content = content("B", 8),
                messages = listOf(Message(id = "flat-B", role = "user")),
            ),
        )
        val out = reduce(state, AppAction.DetailMissing("A", 7))
        assertTrue(out.chat.content?.sessionId == "B")
        assertTrue(out.chat.messages.any { it.id == "flat-B" })
        assertTrue(out.chatRouteInstance == 8L)
    }

    @Test fun `newer detail missing for another route preserves active content and token`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 8,
            chat = StoreState.initial().chat.copy(
                currentSessionId = "B",
                content = content("B", 8),
                messages = listOf(Message(id = "flat-B", role = "user")),
            ),
        )
        val out = reduce(state, AppAction.DetailMissing("A", 9))
        assertTrue(out.chat.content?.sessionId == "B")
        assertTrue(out.chat.messages.any { it.id == "flat-B" })
        assertTrue(out.chatRouteInstance == 8L)
    }

    @Test fun `route-aware session selection advances token atomically`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 4,
            chat = StoreState.initial().chat.copy(
                currentSessionId = "A",
                content = content("A", 4),
            ),
        )
        val out = reduce(
            state,
            AppAction.SessionSelected(
                "B",
                cn.vectory.ocdroid.ui.PendingScrollRequest(1L, "B", cn.vectory.ocdroid.ui.ScrollBehavior.Latest),
                routeInstance = 5L,
            ),
        )
        assertTrue(out.chatRouteInstance == 5L)
        assertTrue(out.chat.content == null)
        assertTrue(out.chat.currentSessionId == "B")
    }

    @Test fun `old route completion cannot repopulate after route-aware selection`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 4,
            chat = StoreState.initial().chat.copy(currentSessionId = "A"),
        )
        val selected = reduce(
            state,
            AppAction.SessionSelected(
                "B",
                cn.vectory.ocdroid.ui.PendingScrollRequest(2L, "B", cn.vectory.ocdroid.ui.ScrollBehavior.Latest),
                routeInstance = 5L,
            ),
        )
        val completed = reduce(
            selected,
            AppAction.ChatContentLoaded(
                sessionId = "A",
                expectedRouteInstance = 4L,
                messages = listOf(Message(id = "stale-A", role = "assistant")),
            ),
        )
        assertTrue(completed.chat.content == null)
        assertTrue(completed.chat.messages.isEmpty())
        assertTrue(completed.chat.currentSessionId == "B")

        val staleLiveUpdate = reduce(
            selected,
            AppAction.MessageUpdatedApplied(
                message = Message(id = "stale-live-A", role = "assistant"),
                expectedRouteInstance = 4L,
                sessionId = "A",
            ),
        )
        assertTrue(staleLiveUpdate.chat.content == null)
        assertTrue(staleLiveUpdate.chat.messages.isEmpty())
        assertTrue(staleLiveUpdate.chat.currentSessionId == "B")
    }

    @Test fun `route-aware load-more updates the owned content and legacy action stays flat-only`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 6,
            chat = StoreState.initial().chat.copy(
                currentSessionId = "A",
                content = content("A", 6),
            ),
        )
        val prepended = reduce(
            state,
            AppAction.MessagesPrepended(
                messages = listOf(Message(id = "older", role = "user")),
                partsByMessage = emptyMap(),
                olderMessagesCursor = "next",
                hasMoreMessages = true,
                expectedRouteInstance = 6L,
            ),
        )
        assertTrue(prepended.chat.content?.messages?.any { it.id == "older" } == true)

        val legacy = reduce(
            state,
            AppAction.MessagesPrepended(
                messages = listOf(Message(id = "legacy", role = "user")),
                partsByMessage = emptyMap(),
                olderMessagesCursor = "legacy-next",
                hasMoreMessages = true,
            ),
        )
        assertTrue(legacy.chat.messages.any { it.id == "legacy" })
        assertTrue(legacy.chat.content?.messages?.none { it.id == "legacy" } == true)
    }

    @Test fun `route-owned content follows message and streaming reducer sequence`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 11,
            chat = StoreState.initial().chat.copy(
                currentSessionId = "A",
            ),
        )
        val afterInitialLoad = reduce(
            state,
            AppAction.ChatContentLoaded(
                sessionId = "A",
                expectedRouteInstance = 11L,
                messages = listOf(Message(id = "m1", role = "assistant")),
            ),
        )
        val afterMessage = reduce(
            afterInitialLoad,
            AppAction.MessageUpdatedApplied(
                message = Message(id = "m2", role = "assistant"),
                expectedRouteInstance = 11L,
                sessionId = "A",
            ),
        )
        assertTrue(afterMessage.chat.content?.messages?.map { it.id } == listOf("m1", "m2"))

        val afterPart = reduce(
            afterMessage,
            AppAction.PartDeltaReceived(
                partId = "p1",
                delta = "live",
                partType = "text",
                messageId = "m2",
                sessionId = "A",
                expectedRouteInstance = 11L,
            ),
        )
        assertTrue(afterPart.chat.content?.streamingPartTexts?.get("p1") == "live")
        assertTrue(afterPart.chat.content?.partsByMessage?.get("m2")?.any { it.id == "p1" } == true)

        val afterToken = reduce(
            afterPart,
            AppAction.TokenStreamPartUpdated(
                partId = "p1",
                text = "live-final",
                state = StreamOwnedState.DONE,
                expectedRouteInstance = 11L,
                sessionId = "A",
                bundleStamp = BundleStamp(0L, ""),
            ),
        )
        assertTrue(afterToken.chat.content?.streamingPartTexts?.get("p1") == "live-final")
        assertTrue(afterToken.chat.content?.streamOwned?.get("p1") == StreamOwnedState.DONE)

        val afterClear = reduce(
            afterToken,
            // §B4 route-aware clear: the coordinator dispatches with the captured
            // route token + session so the reducer clears BOTH flat and route
            // content (the legacy token=0 path only clears the flat mirror).
            AppAction.ClearTokenStreamState(
                setOf("p1"),
                expectedRouteInstance = 11L,
                sessionId = "A",
                bundleStamp = BundleStamp(0L, ""),
            ),
        )
        assertTrue("completion clear must reach route-owned content", afterClear.chat.content?.streamOwned?.containsKey("p1") == false)
        assertTrue("completion clear must reach route-owned content", afterClear.chat.content?.streamingPartTexts?.containsKey("p1") == false)
    }

    @Test fun `flat transcript mutation cannot update a stale route incarnation`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 12,
            chat = StoreState.initial().chat.copy(
                currentSessionId = "A",
                content = LoadedContent(
                    sessionId = "A",
                    messages = listOf(Message(id = "route-old", role = "user")),
                    routeInstance = 11,
                ),
                messages = listOf(Message(id = "flat-old", role = "user")),
            ),
        )
        val out = reduce(
            state,
            AppAction.MessageUpdatedApplied(
                message = Message(id = "flat-new", role = "assistant"),
                expectedRouteInstance = 11L,
            ),
        )
        assertTrue(out.chat.content?.messages?.map { it.id } == listOf("route-old"))
        assertTrue(out.chat.messages.none { it.id == "flat-new" })
    }

    @Test
    fun `route-aware cache hydration updates messages parts and cursor atomically`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 13,
            chat = StoreState.initial().chat.copy(
                currentSessionId = "A",
            ),
        )
        val hydrated = reduce(
            state,
            AppAction.ChatWindowHydrated(
                messages = listOf(Message(id = "cached", role = "user")),
                partsByMessage = emptyMap(),
                olderMessagesCursor = "cursor-2",
                hasMoreMessages = true,
                expectedRouteInstance = 13L,
                sessionId = "A",
            ),
        )
        assertTrue(hydrated.chat.content?.messages?.map { it.id } == listOf("cached"))
        assertTrue(hydrated.chat.content?.olderMessagesCursor == "cursor-2")
        assertTrue(hydrated.chat.content?.hasMoreMessages == true)
    }

    @Test fun `host purge and refresh reset cannot retain renderable content`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 3,
            chat = StoreState.initial().chat.copy(content = content("A", 3), messages = listOf(Message(id = "flat", role = "user"))),
        )
        val cleared = state.copy(chat = state.chat.copy(content = null, messages = emptyList()))
        assertFalse(isRouteContentRenderable("A", cleared.chat.content, cleared.chatRouteInstance))
        assertTrue(cleared.chat.messages.isEmpty())
    }

    // ── §B2 rev-gpt BLOCK regression tests ───────────────────────────────────

    /**
     * CRITICAL: an in-flight route-aware load-more sets isLoadingMoreMessages;
     * its finally backstop is token-guarded, so once CloseDetail advances the
     * incarnation the finally no-ops. CloseDetail MUST clear the flag itself
     * so the loading state cannot get stuck.
     */
    @Test fun `CloseDetail clears an in-flight route-aware load-more flag so a late response cannot leave it stuck`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 5,
            chat = StoreState.initial().chat.copy(
                currentSessionId = "A",
                content = content("A", 5),
                isLoadingMoreMessages = true,
            ),
        )
        val out = reduce(state, AppAction.CloseDetail)
        // Flag cleared by CloseDetail (the finally backstop would no-op: the
        // token advanced to 6, so routeTokenValid is false).
        assertFalse("isLoadingMoreMessages must be cleared by CloseDetail", out.chat.isLoadingMoreMessages)
        assertTrue("route token must advance past the in-flight load's T=5", out.chatRouteInstance == 6L)
        // A late MessagesPrepended carrying the prior token T=5 is rejected by
        // acceptsRouteUpdate (5 != 6) — and even if it were not, the flag is
        // already false so no stuck state is possible.
        val lateOut = reduce(out, AppAction.MessagesPrepended(
            messages = listOf(Message(id = "late", role = "user")),
            partsByMessage = emptyMap(),
            olderMessagesCursor = null,
            hasMoreMessages = false,
            expectedRouteInstance = 5L,
            sessionId = "A",
        ))
        assertTrue("late prior-token load-more must not write", lateOut.chat.messages.none { it.id == "late" })
        assertFalse("flag stays false after the late response", lateOut.chat.isLoadingMoreMessages)
    }

    /**
     * MAJOR 2: CloseDetail MUST clear the coalesce buffers (deltaBuffer /
     * fullTextBuffer / pendingFlushPartIds). A late CoalesceFlushedForPart is
     * a legacy (token=0) action accepted by acceptsRouteUpdate. The REAL
     * resurrection/corruption guard is exercised by establishing a FRESH
     * overlay for the same partId (a new route streaming p1) BEFORE the late
     * flush fires: with the buffers cleared the flush appends nothing; without
     * the clear it would APPEND the stale route-A delta onto route-B's overlay.
     */
    @Test fun `CloseDetail clears coalesce buffers so a late flush cannot corrupt a fresh overlay`() {
        val state = StoreState.initial().copy(
            chatRouteInstance = 5,
            chat = StoreState.initial().chat.copy(
                currentSessionId = "A",
                content = content("A", 5),
                streamingPartTexts = emptyMap(),
                deltaBuffer = mapOf("p1" to "stale-A-delta"),
                fullTextBuffer = mapOf("p1" to "stale-A-fulltext"),
                pendingFlushPartIds = setOf("p1"),
            ),
        )
        val out = reduce(state, AppAction.CloseDetail)
        assertTrue("deltaBuffer must be cleared", out.chat.deltaBuffer.isEmpty())
        assertTrue("fullTextBuffer must be cleared", out.chat.fullTextBuffer.isEmpty())
        assertTrue("pendingFlushPartIds must be cleared", out.chat.pendingFlushPartIds.isEmpty())

        // Establish a FRESH overlay for p1 — the real resurrection scenario:
        // a new route has started streaming the same partId. If the buffers
        // had survived CloseDetail, the late flush below would APPEND the
        // stale route-A delta ("stale-A-delta") onto route-B's "fresh-B".
        val withFreshOverlay = out.copy(
            chat = out.chat.copy(streamingPartTexts = mapOf("p1" to "fresh-B")),
        )
        val afterLateFlush = reduce(
            withFreshOverlay,
            AppAction.CoalesceFlushedForPart(
                partId = "p1",
                bundleStamp = BundleStamp(0L, ""),
            ),
        )
        assertEquals(
            "late flush must not corrupt the fresh overlay (buffers were cleared by CloseDetail)",
            "fresh-B",
            afterLateFlush.chat.streamingPartTexts["p1"],
        )
    }

    /**
     * §B2 rev-gpt MAJOR 1/2 transition-window regression: after a route A→B
     * flip, flat currentSessionId lags (still "A") until SessionSelected
     * dispatches. [chromeSessionIdFor] resolves to the route id "B" for the
     * parameterized route — the authority every session-scoped chrome
     * derivation (title / todos / tab-strip selected / force-refresh /
     * staleNotice / lastError) now consumes — so none of them reflect A's
     * identity during the window. Legacy bare-chat (routeSessionId == null)
     * keeps flat currentSessionId.
     */
    @Test fun `transition window - chrome identity follows the route id not the lagging flat currentSessionId`() {
        // The A→B window: route flipped to B, flat currentSessionId still A.
        val transition = chromeSessionIdFor(routeSessionId = "B", currentSessionId = "A")
        assertEquals("chrome must follow the route id during the transition window", "B", transition)
        // Once SessionSelected catches up, flat agrees — still B.
        assertEquals("B", chromeSessionIdFor(routeSessionId = "B", currentSessionId = "B"))
        // Legacy bare-chat has no route id → flat currentSessionId governs.
        assertEquals("A", chromeSessionIdFor(routeSessionId = null, currentSessionId = "A"))
    }
}
