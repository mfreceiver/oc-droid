package cn.vectory.ocdroid.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [remainingSeconds] countdown edge cases that the retry card routes
 * on. The -1 sentinel (null OR due/overdue `next`) → "Retrying"; a genuinely
 * future `next` → positive seconds; sub-second futures → 0. Pure-function
 * tests — no Compose harness.
 */
class SessionRetryCardTest {

    @Test
    fun `remainingSeconds returns -1 when next is null`() {
        assertEquals(-1, remainingSeconds(next = null, now = 1_000_000L))
    }

    @Test
    fun `remainingSeconds returns whole seconds until a future next`() {
        assertEquals(5, remainingSeconds(next = 6_000L, now = 1_000L))
    }

    @Test
    fun `remainingSeconds returns zero for a sub-second future window`() {
        // Genuine future < 1s away → "0s until retry" is accurate (briefly,
        // until the next tick crosses into "Retrying").
        assertEquals(0, remainingSeconds(next = 1_999L, now = 1_000L))
    }

    @Test
    fun `remainingSeconds returns -1 when next equals now`() {
        // Exactly due — "Retrying" is more honest than "0s until retry".
        assertEquals(-1, remainingSeconds(next = 5_000L, now = 5_000L))
    }

    @Test
    fun `remainingSeconds returns -1 when next is in the past`() {
        // Overdue → -1 → "Retrying" branch; never a negative/zero countdown.
        assertEquals(-1, remainingSeconds(next = 1_000L, now = 4_000L))
    }

    @Test
    fun `remainingSeconds returns -1 for a just-past deadline within one second`() {
        // REGRESSION PIN: Kotlin int division truncates toward zero, so a naive
        // `(next - now) / 1000` for next = now - 999 would yield 0 (→ "0s"),
        // contradicting the "past deadline → Retrying" contract. The explicit
        // `next <= now` guard in remainingSeconds makes this return -1.
        assertEquals(-1, remainingSeconds(next = 1L, now = 1_000L))
    }
}
