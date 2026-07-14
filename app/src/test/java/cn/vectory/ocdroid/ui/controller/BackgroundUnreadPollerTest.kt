package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundUnreadPollerTest {
    private val repository = mockk<OpenCodeRepository>()
    private val settings = mockk<SettingsManager>()
    private val store = SharedStateStore()
    private var now = 1_000L

    private fun root(id: String) = Session(id = id, directory = "/repo", title = "Root $id")

    private fun poller(
        isBackground: () -> Boolean = { true },
        lifecycleGeneration: () -> Long = { 0L },
    ) = BackgroundUnreadPoller(
        repository = repository,
        settingsManager = settings,
        store = store,
        clock = { now },
        isBackground = isBackground,
        lifecycleGeneration = lifecycleGeneration,
    )

    private fun stubSnapshot(
        sessions: List<Session>,
        statuses: Map<String, SessionStatus>,
        children: Map<String, List<Session>> = emptyMap(),
    ) {
        every { settings.currentWorkdir } returns "/repo"
        coEvery { repository.getSessions(any()) } returns Result.success(sessions)
        coEvery { repository.getSessionStatus() } returns Result.success(statuses)
        coEvery { repository.getChildren(any()) } answers {
            Result.success(children[arg<String>(0)].orEmpty())
        }
    }

    @Test
    fun `two background polls mark once after soak and continuous idle does not duplicate`() = runTest {
        stubSnapshot(listOf(root("A")), mapOf("A" to SessionStatus("idle")))
        val poller = poller()

        assertTrue(poller.poll().isEmpty())
        now = 31_000L
        val completed = poller.poll()
        now = 61_000L
        val repeated = poller.poll()

        assertEquals(listOf("A"), completed.map { it.rootId })
        assertEquals("pending cycle remains retryable; monitor dedupes posted keys", completed, repeated)
        assertTrue("authoritative unread set is updated", "A" in store.unreadFlow.value.unreadSessions)
    }

    @Test
    fun `successful status snapshot absence is normalized to authoritative idle`() = runTest {
        stubSnapshot(listOf(root("A")), emptyMap())
        val poller = poller()

        assertTrue(poller.poll().isEmpty())
        now = 31_000L

        assertEquals(listOf("A"), poller().poll().map { it.rootId })
    }

    @Test
    fun `successful background poll bumps completeness epoch to drop in-flight foreground hydration`() = runTest {
        // §gpter-residual: the background poll writes an authoritative
        // completeness snapshot. Bumping the epoch makes any foreground
        // hydration captured before this poll fail-closed at its commit
        // instead of re-certifying roots against a stale session map.
        stubSnapshot(listOf(root("A")), mapOf("A" to SessionStatus("idle")))
        assertEquals("default epoch", 0L, store.sessionListFlow.value.completenessEpoch)

        assertTrue(poller().poll().isEmpty())

        assertEquals(
            "background poll bumped the completeness epoch",
            1L,
            store.sessionListFlow.value.completenessEpoch,
        )
    }


    @Test
    fun `current root and busy child produce no background alert`() = runTest {
        val a = root("A")
        val child = Session(id = "C", directory = "/repo", parentId = "A")
        stubSnapshot(
            sessions = listOf(a),
            statuses = mapOf("A" to SessionStatus("idle"), "C" to SessionStatus("busy")),
            children = mapOf("A" to listOf(child)),
        )
        store.mutateChat { it.copy(currentSessionId = "A") }
        store.mutateUnread { it.copy(idleSince = mapOf("A" to 1_000L)) }
        now = 31_000L

        val alerts = poller().poll()

        assertTrue(alerts.isEmpty())
        assertFalse("A" in store.unreadFlow.value.unreadSessions)
    }

    @Test
    fun `viewed during cycle suppresses background alert`() = runTest {
        stubSnapshot(listOf(root("A")), mapOf("A" to SessionStatus("idle")))
        store.mutateUnread {
            it.copy(idleSince = mapOf("A" to 1_000L), lastViewedTime = mapOf("A" to 2_000L))
        }
        now = 31_000L

        assertTrue(poller().poll().isEmpty())
        assertFalse("A" in store.unreadFlow.value.unreadSessions)
    }

    @Test
    fun `notification key includes server workdir root and idle cycle`() {
        assertEquals(
            "idle:server-1:/repo:A:1000",
            idleNotificationKey("server-1", "/repo", "A", 1_000L),
        )
    }

    @Test
    fun `host switch during final tree request prevents stale commit and alert`() = runTest {
        val a = root("A")
        every { settings.currentWorkdir } returns "/repo"
        store.mutateHost { it.copy(currentHostProfileId = "host-1") }
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(a))
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getChildren("A") } coAnswers {
            store.mutateHost { it.copy(currentHostProfileId = "host-2") }
            Result.success(emptyList())
        }

        val alerts = poller().poll()

        assertTrue(alerts.isEmpty())
        assertTrue(store.sessionListFlow.value.sessions.isEmpty())
        assertTrue(store.unreadFlow.value.unreadSessions.isEmpty())
    }

    @Test
    fun `foreground transition during final request prevents stale commit and alert`() = runTest {
        val a = root("A")
        var background = true
        var generation = 1L
        every { settings.currentWorkdir } returns "/repo"
        coEvery { repository.getSessions(any()) } returns Result.success(listOf(a))
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getChildren("A") } coAnswers {
            background = false
            generation += 1
            Result.success(emptyList())
        }

        val alerts = poller({ background }, { generation }).poll()

        assertTrue(alerts.isEmpty())
        assertTrue(store.sessionListFlow.value.sessions.isEmpty())
    }
}
