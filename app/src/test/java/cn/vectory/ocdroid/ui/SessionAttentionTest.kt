package cn.vectory.ocdroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §ui-badges: pure unit tests for [computeSessionAttention]. Each test
 * verifies that the given boolean flags resolve to the expected
 * [SessionAttentionLevel], following the priority:
 *   HardError > PendingUserInput > TransientRetry > Unread > None
 */
class SessionAttentionTest {

    // ── Single flag → tier ──────────────────────────────────────────────

    @Test
    fun `error alone maps to HardError`() {
        assertEquals(SessionAttentionLevel.HardError, computeSessionAttention(
            hasError = true, hasPendingUserInput = false, isRetry = false, isUnread = false,
        ))
    }

    @Test
    fun `pendingUserInput alone maps to PendingUserInput`() {
        assertEquals(SessionAttentionLevel.PendingUserInput, computeSessionAttention(
            hasError = false, hasPendingUserInput = true, isRetry = false, isUnread = false,
        ))
    }

    @Test
    fun `retry alone maps to TransientRetry`() {
        assertEquals(SessionAttentionLevel.TransientRetry, computeSessionAttention(
            hasError = false, hasPendingUserInput = false, isRetry = true, isUnread = false,
        ))
    }

    @Test
    fun `unread alone maps to Unread`() {
        assertEquals(SessionAttentionLevel.Unread, computeSessionAttention(
            hasError = false, hasPendingUserInput = false, isRetry = false, isUnread = true,
        ))
    }

    @Test
    fun `all false maps to None`() {
        assertEquals(SessionAttentionLevel.None, computeSessionAttention(
            hasError = false, hasPendingUserInput = false, isRetry = false, isUnread = false,
        ))
    }

    // ── Priority pairs ──────────────────────────────────────────────────

    @Test
    fun `error plus retry maps to HardError`() {
        assertEquals(SessionAttentionLevel.HardError, computeSessionAttention(
            hasError = true, hasPendingUserInput = false, isRetry = true, isUnread = false,
        ))
    }

    @Test
    fun `error plus pendingUserInput maps to HardError`() {
        assertEquals(SessionAttentionLevel.HardError, computeSessionAttention(
            hasError = true, hasPendingUserInput = true, isRetry = false, isUnread = false,
        ))
    }

    @Test
    fun `error plus unread maps to HardError`() {
        assertEquals(SessionAttentionLevel.HardError, computeSessionAttention(
            hasError = true, hasPendingUserInput = false, isRetry = false, isUnread = true,
        ))
    }

    @Test
    fun `pendingUserInput plus retry maps to PendingUserInput`() {
        assertEquals(SessionAttentionLevel.PendingUserInput, computeSessionAttention(
            hasError = false, hasPendingUserInput = true, isRetry = true, isUnread = false,
        ))
    }

    @Test
    fun `pendingUserInput plus unread maps to PendingUserInput`() {
        assertEquals(SessionAttentionLevel.PendingUserInput, computeSessionAttention(
            hasError = false, hasPendingUserInput = true, isRetry = false, isUnread = true,
        ))
    }

    @Test
    fun `retry plus unread maps to TransientRetry`() {
        assertEquals(SessionAttentionLevel.TransientRetry, computeSessionAttention(
            hasError = false, hasPendingUserInput = false, isRetry = true, isUnread = true,
        ))
    }

    @Test
    fun `error plus pendingUserInput plus retry maps to HardError`() {
        assertEquals(SessionAttentionLevel.HardError, computeSessionAttention(
            hasError = true, hasPendingUserInput = true, isRetry = true, isUnread = false,
        ))
    }

    @Test
    fun `pendingUserInput plus retry plus unread maps to PendingUserInput`() {
        assertEquals(SessionAttentionLevel.PendingUserInput, computeSessionAttention(
            hasError = false, hasPendingUserInput = true, isRetry = true, isUnread = true,
        ))
    }

    // ── All flags ───────────────────────────────────────────────────────

    @Test
    fun `all flags true maps to HardError`() {
        assertEquals(SessionAttentionLevel.HardError, computeSessionAttention(
            hasError = true, hasPendingUserInput = true, isRetry = true, isUnread = true,
        ))
    }
}
