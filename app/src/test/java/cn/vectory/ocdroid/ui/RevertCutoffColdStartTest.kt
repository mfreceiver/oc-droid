package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.data.model.SessionCacheEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cold-start regression proof: cached revert metadata never releases a full window. */
class RevertCutoffColdStartTest {
    private val messages = listOf(
        Message(id = "before", role = "user", time = Message.TimeInfo(created = 1L)),
        Message(id = "after", role = "user", time = Message.TimeInfo(created = 3L))
    )

    @Test
    fun `cached resolved cutoff truncates before any server session hydration`() {
        val cutoff = restoreRevertCutoffs(listOf(
            SessionCacheEntry("s1", directory = "/x", revertMessageId = "revert", revertCreatedAtEpochMs = 2L)
        )).getValue("s1")

        assertEquals(RevertCutoffState.Resolved(2L), cutoff.state)
        assertEquals(listOf("before"), visibleMessages(messages, null, cutoff).map { it.id })
    }

    @Test
    fun `cached unresolved cutoff is pending and fails closed before server hydration`() {
        val cutoff = restoreRevertCutoffs(listOf(
            SessionCacheEntry("s1", directory = "/x", revertMessageId = "revert")
        )).getValue("s1")

        assertEquals(RevertCutoffState.PendingFetch, cutoff.state)
        assertEquals(emptyList<Message>(), visibleMessages(messages, null, cutoff))
    }
}
