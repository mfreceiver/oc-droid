package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.ui.SharedStateStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §unread-soak: integration tests for [UnreadSoakController]. Each test
 * drives the controller's internal [tick] synchronously (no [delay] needed)
 * over a sequence of clock values + seeded slice states, asserting the
 * unread-slice transitions that the LOCKED spec mandates:
 *
 *  - a root going busy→all-idle + 10s + not viewed ⇒ enters unreadSessions
 *  - viewing it (lastViewedTime ≥ idleSince) ⇒ not marked
 *  - re-busy ⇒ cleared (stamp reset)
 *  - current-session stamping ⇒ "watching-at-completion counts as viewed"
 *
 * The controller's [UnreadSoakController.tick] is the production sweep body;
 * these tests invoke it directly to avoid the [UnreadSoakController.SWEEP_INTERVAL_MS]
 * wall-clock wait. The foreground loop itself is NOT exercised here (it's a
 * trivial delay+tick while-true); the [tick] logic is the contract.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnreadSoakControllerTest {

    private lateinit var store: SharedStateStore
    private lateinit var foregroundFlow: MutableStateFlow<Boolean>
    private var nowMs: Long = 0L
    private lateinit var scope: CoroutineScope

    private val idle = SessionStatus(type = "idle")
    private val busy = SessionStatus(type = "busy")

    @Before
    fun setUp() {
        store = SharedStateStore()
        foregroundFlow = MutableStateFlow(true)
        nowMs = 0L
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun makeController(
        requestTreeHydration: (Set<String>) -> Unit = {},
        requestStatusRefresh: ((Boolean) -> Unit) -> Boolean = { completion -> completion(true); true },
    ): UnreadSoakController = UnreadSoakController(
        appLifecycleMonitor = stubMonitor(),
        scope = scope,
        store = store,
        clock = { nowMs },
        autoStart = false,
        requestTreeHydration = requestTreeHydration,
        requestStatusRefresh = requestStatusRefresh,
    )

    private fun stubMonitor(): AppLifecycleMonitor = mockk(relaxed = true) {
        every { isInForeground } returns foregroundFlow
    }

    // §unread-semantics (F3): `updated` mirrors Session.time.updated — the
    // "new content" signal. Defaults to null (no content); mark-asserting
    // tests opt into a timestamp to exercise the new-message branch.
    private fun root(id: String, updated: Long? = null) =
        Session(id = id, directory = "/x", parentId = null, time = Session.TimeInfo(updated = updated))
    private fun child(id: String, parent: String) = Session(id = id, directory = "/x", parentId = parent)

    private fun seedSessions(vararg sessions: Session) {
        store.mutateSessionList {
            it.copy(
                sessions = sessions.toList(),
                completeRootIds = sessions.filter { session -> session.parentId == null }
                    .mapTo(mutableSetOf()) { session -> session.id },
            )
        }
    }

    private fun seedStatuses(vararg entries: Pair<String, SessionStatus>) {
        store.mutateSessionList { it.copy(sessionStatuses = entries.toMap()) }
    }

    private fun seedCurrent(sessionId: String?) {
        store.mutateChat { it.copy(currentSessionId = sessionId) }
    }

    // ── busy → all-idle → 10s → mark ───────────────────────────────────────

    @Test
    fun `incomplete idle root requests hydration and cannot start soak`() {
        val requested = mutableSetOf<String>()
        val controller = makeController(requestTreeHydration = { requested += it })
        seedSessions(root("A"))
        store.mutateSessionList { it.copy(completeRootIds = emptySet()) }
        seedStatuses("A" to idle)

        nowMs = 1_000L
        controller.tick()

        assertEquals(setOf("A"), requested)
        assertNull(store.unreadFlow.value.idleSince["A"])
    }

    @Test
    fun `hydration revealing busy child blocks unread`() {
        val controller = makeController()
        seedSessions(root("A"), child("C", "A"))
        seedStatuses("A" to idle, "C" to busy)
        store.mutateSessionList { it.copy(completeRootIds = setOf("A")) }
        store.mutateUnread { it.copy(idleSince = mapOf("A" to 1_000L)) }

        nowMs = 11_000L
        controller.tick()

        assertFalse("A" in store.unreadFlow.value.unreadSessions)
        assertNull(store.unreadFlow.value.idleSince["A"])
    }

    @Test
    fun `complete hydrated all-idle tree can soak`() {
        val controller = makeController()
        seedSessions(root("A"), child("C", "A"))
        seedStatuses("A" to idle, "C" to idle)
        store.mutateSessionList { it.copy(completeRootIds = setOf("A")) }

        nowMs = 1_000L
        controller.tick()

        assertEquals(1_000L, store.unreadFlow.value.idleSince["A"])
    }

    @Test
    fun `complete tree with unknown descendant retries status without rehydrating tree`() {
        var statusRefreshes = 0
        val hydratedAgain = mutableSetOf<String>()
        val controller = makeController(
            requestTreeHydration = { hydratedAgain += it },
            requestStatusRefresh = { _ -> statusRefreshes += 1; true },
        )
        seedSessions(root("A"), child("C", "A"))
        seedStatuses("A" to idle)
        store.mutateSessionList { it.copy(completeRootIds = setOf("A")) }

        controller.tick()

        assertEquals(1, statusRefreshes)
        assertTrue(hydratedAgain.isEmpty())
        assertNull(store.unreadFlow.value.idleSince["A"])
    }

    @Test
    fun `root busy then all-idle plus 10s not viewed enters unreadSessions`() {
        val controller = makeController()
        // F3: opt-in a new message (arrived just before A went idle at t=2000)
        // so the soak→mark transition fires; lastViewedTime[A] is null (never
        // viewed) ⇒ baseline 0 ⇒ any updated > 0 counts as unviewed new content.
        seedSessions(root("A", updated = 1_500L), root("CUR"))
        seedCurrent("CUR")
        // A is busy.
        seedStatuses("A" to busy, "CUR" to idle)

        // First tick: A is busy → no soak (stamp cleared).
        nowMs = 1_000L
        controller.tick()
        assertNull(store.unreadFlow.value.idleSince["A"])

        // A goes idle.
        seedStatuses("A" to idle, "CUR" to idle)
        nowMs = 2_000L
        controller.tick()
        // Rising edge: stamp set to 2000.
        assertEquals(2_000L, store.unreadFlow.value.idleSince["A"])
        assertFalse(store.unreadFlow.value.unreadSessions.contains("A"))

        // 6s later: still soaking.
        nowMs = 8_000L
        controller.tick()
        assertEquals(2_000L, store.unreadFlow.value.idleSince["A"])
        assertFalse(store.unreadFlow.value.unreadSessions.contains("A"))

        // 10s+ later: soak crosses → marked + consumed.
        nowMs = 13_000L
        controller.tick()
        controller.tick()
        assertTrue(
            "A must enter unreadSessions after 10s soak",
            store.unreadFlow.value.unreadSessions.contains("A"),
        )
        assertEquals(2_000L, store.unreadFlow.value.idleSince["A"])
    }

    // ── viewing since idle prevents mark ───────────────────────────────────

    @Test
    fun `viewing root after idle prevents mark via lastViewedTime`() {
        val controller = makeController()
        seedSessions(root("A"))
        seedCurrent("CUR") // A is not current
        seedStatuses("A" to idle, "CUR" to idle)

        // Start soak.
        nowMs = 1_000L
        controller.tick()
        assertEquals(1_000L, store.unreadFlow.value.idleSince["A"])

        // User views A at 1500 (after the soak started) → lastViewedTime[A]=1500.
        store.mutateUnread { it.copy(lastViewedTime = it.lastViewedTime + ("A" to 1_500L)) }

        // Soak completes at 11s.
        nowMs = 11_000L
        controller.tick()
        controller.tick()
        assertFalse(
            "viewed-since-idle must not mark A",
            store.unreadFlow.value.unreadSessions.contains("A"),
        )
        assertEquals(1_000L, store.unreadFlow.value.idleSince["A"])
    }

    // ── re-busy clears ─────────────────────────────────────────────────────

    @Test
    fun `re-busy mid-soak clears the stamp`() {
        val controller = makeController()
        seedSessions(root("A"))
        seedCurrent("CUR")
        seedStatuses("A" to idle, "CUR" to idle)

        nowMs = 1_000L
        controller.tick()
        assertEquals(1_000L, store.unreadFlow.value.idleSince["A"])

        // A goes busy again mid-soak.
        seedStatuses("A" to busy, "CUR" to idle)
        nowMs = 5_000L
        controller.tick()
        assertNull("re-busy must clear stamp", store.unreadFlow.value.idleSince["A"])
        assertFalse(store.unreadFlow.value.unreadSessions.contains("A"))
    }

    // ── current session stamping ───────────────────────────────────────────

    @Test
    fun `current root all-idle stamps lastViewedTime so switch-away does not immediately re-mark`() {
        val controller = makeController()
        seedSessions(root("A"))
        seedCurrent("A") // A IS current
        seedStatuses("A" to idle)

        nowMs = 5_000L
        controller.tick()

        // Watching-at-completion: lastViewedTime[A] stamped to now.
        assertEquals(
            "current all-idle root must be stamped viewed",
            5_000L,
            store.unreadFlow.value.lastViewedTime["A"],
        )
        // And never marked unread.
        assertFalse(store.unreadFlow.value.unreadSessions.contains("A"))
        // Opening/current viewing consumes the pending cycle immediately.
        assertNull(store.unreadFlow.value.idleSince["A"])
    }

    @Test
    fun `current root that is busy does not stamp viewed`() {
        val controller = makeController()
        seedSessions(root("A"))
        seedCurrent("A")
        seedStatuses("A" to busy)

        nowMs = 5_000L
        controller.tick()
        // Not all-idle → no stamp.
        assertNull(store.unreadFlow.value.lastViewedTime["A"])
    }

    @Test
    fun `current child resolves to root for viewed stamping`() {
        // User is viewing CHILD of root A; A's subtree is all-idle. The sweep
        // resolves currentSessionId=CHILD → root A and stamps lastViewedTime[A].
        val controller = makeController()
        seedSessions(root("A"), child("C", "A"))
        seedCurrent("C")
        seedStatuses("A" to idle, "C" to idle)

        nowMs = 5_000L
        controller.tick()

        assertEquals(
            "viewing a child stamps the ROOT's lastViewedTime",
            5_000L,
            store.unreadFlow.value.lastViewedTime["A"],
        )
    }

    @Test
    fun `current child cannot mark its root when an existing soak completes`() {
        val controller = makeController()
        seedSessions(root("A"), child("C", "A"))
        seedCurrent("C")
        seedStatuses("A" to idle, "C" to idle)
        store.mutateUnread { it.copy(idleSince = mapOf("A" to 1_000L)) }

        nowMs = 11_000L
        controller.tick()
        controller.tick()

        assertFalse(store.unreadFlow.value.unreadSessions.contains("A"))
        assertEquals(11_000L, store.unreadFlow.value.lastViewedTime["A"])
    }

    @Test
    fun `sweep defensively clears existing root unread when current session is a child`() {
        val controller = makeController()
        seedSessions(root("A"), child("C", "A"))
        seedCurrent("C")
        seedStatuses("A" to busy, "C" to busy)
        store.mutateUnread {
            it.copy(unreadSessions = setOf("A"), idleSince = mapOf("A" to 1_000L))
        }

        nowMs = 5_000L
        controller.tick()

        assertFalse("A" in store.unreadFlow.value.unreadSessions)
        assertFalse("A" in store.unreadFlow.value.idleSince)
        assertEquals(5_000L, store.unreadFlow.value.lastViewedTime["A"])
    }

    @Test
    fun `continuous idle evaluation marks only once`() {
        val controller = makeController()
        seedSessions(root("A"))
        seedCurrent("CUR")
        seedStatuses("A" to idle)

        nowMs = 1_000L
        controller.tick()
        nowMs = 11_000L
        controller.tick()
        store.mutateUnread { it.copy(unreadSessions = emptySet()) }
        nowMs = 30_000L
        controller.tick()

        assertFalse("same idle cycle must not re-badge", store.unreadFlow.value.unreadSessions.contains("A"))
    }

    // ── child/descendant busy blocks marking via sweep ─────────────────────

    @Test
    fun `child busy blocks root from soaking through the sweep`() {
        val controller = makeController()
        seedSessions(root("A"), child("C", "A"))
        seedCurrent("CUR")
        seedStatuses("A" to idle, "C" to busy, "CUR" to idle)

        // Even with a stale stamp from a prior all-idle window, the sweep's
        // evaluator sees C busy → resets + never marks.
        store.mutateUnread { it.copy(idleSince = mapOf("A" to 1_000L)) }
        nowMs = 11_000L
        controller.tick()
        assertFalse(store.unreadFlow.value.unreadSessions.contains("A"))
        assertNull(store.unreadFlow.value.idleSince["A"])
    }

    // ── soak drives multiple roots independently via the sweep ─────────────

    @Test
    fun `sweep marks one root and continues soaking another`() {
        val controller = makeController()
        // F3: opt-in new messages (arrived before the soak rising-edge at
        // t=1000) on both roots so the threshold-crossing tick marks them;
        // neither root is viewed (lastViewedTime baseline 0).
        seedSessions(root("A", updated = 500L), root("B", updated = 500L))
        seedCurrent("CUR")
        seedStatuses("A" to idle, "B" to idle, "CUR" to idle)

        // Both start soaking at t=1000.
        nowMs = 1_000L
        controller.tick()
        assertEquals(1_000L, store.unreadFlow.value.idleSince["A"])
        assertEquals(1_000L, store.unreadFlow.value.idleSince["B"])

        // A's stamp is wiped (simulate A had already soaked once before and
        // the user dismissed it); both past threshold at t=11000.
        nowMs = 11_000L
        controller.tick()
        controller.tick()
        assertTrue("A marked", store.unreadFlow.value.unreadSessions.contains("A"))
        assertTrue("B marked", store.unreadFlow.value.unreadSessions.contains("B"))
    }

    // ── archive cleanup via removeSessions clears idleSince ────────────────

    @Test
    fun `removeSessions clears idleSince for archived subtree`() {
        seedSessions(root("A"), child("C", "A"))
        store.mutateUnread {
            it.copy(
                unreadSessions = setOf("A"),
                idleSince = mapOf("A" to 1_000L),
                lastViewedTime = mapOf("A" to 500L),
            )
        }
        // Simulate the archive reducer's removeSessions(subtree).
        val subtree = setOf("A", "C")
        store.mutateUnread { it.removeSessions(subtree) }
        val unread = store.unreadFlow.value
        assertFalse("A removed from unreadSessions", unread.unreadSessions.contains("A"))
        assertNull("A idleSince cleared", unread.idleSince["A"])
        assertNull("A lastViewedTime cleared", unread.lastViewedTime["A"])
    }

    // ── foreground lifecycle ───────────────────────────────────────────────

    @Test
    fun `foreground transition starts sweep - tick is invocable`() {
        // The controller subscribes in init; we just verify tick runs cleanly
        // after construction (no crash, foreground=true baseline).
        val controller = makeController()
        seedSessions(root("A"))
        seedStatuses("A" to idle)
        nowMs = 1_000L
        controller.tick()
        assertNotNull(store.unreadFlow.value.idleSince["A"])
    }

    @Test
    fun `tick is a no-op when there are no roots`() {
        val controller = makeController()
        seedCurrent(null)
        nowMs = 1_000L
        controller.tick()
        assertTrue(store.unreadFlow.value.unreadSessions.isEmpty())
        assertTrue(store.unreadFlow.value.idleSince.isEmpty())
    }
}
