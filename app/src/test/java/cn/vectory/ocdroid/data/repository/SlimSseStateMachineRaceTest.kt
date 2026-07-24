package cn.vectory.ocdroid.data.repository

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Race-condition regression test for P2 (D3 host-switch race fix).
 *
 * Verifies that [SlimSseStateMachine] with an [epochProvider] correctly
 * rejects tokens captured before a host epoch bump, while still accepting
 * tokens captured after. Also verifies the unregistered-token fail-closed
 * behavior, the no-provider legacy compat path, and that
 * [beginSlimReconfigure] clears the token-to-epoch map so new captures
 * after a reconfigure are accepted.
 */
class SlimSseStateMachineRaceTest {

    @Test
    fun `epoch check rejects stale token after epoch bump`() {
        val epoch = AtomicLong(0L)
        val machine = SlimSseStateMachine(Any()) { epoch.get() }

        val tokenA = machine.captureSlimCommitToken()

        // Simulate ConnectionIdentityStore.beginReconfigure()
        epoch.incrementAndGet()

        var committed = false
        val result = machine.commitIfSlimTokenCurrent(tokenA) {
            committed = true
        }

        assertFalse("commitIfSlimTokenCurrent must return false when epoch bumped", result)
        assertFalse("commit block must not execute when epoch bumped", committed)
    }

    @Test
    fun `epoch check still passes for current token`() {
        val epoch = AtomicLong(42L)
        val machine = SlimSseStateMachine(Any()) { epoch.get() }

        val token = machine.captureSlimCommitToken()

        var committed = false
        val result = machine.commitIfSlimTokenCurrent(token) {
            committed = true
        }

        assertTrue("commitIfSlimTokenCurrent must return true for current token", result)
        assertTrue("commit block must execute for current token", committed)
    }

    @Test
    fun `token not registered with provider returns false`() {
        val epoch = AtomicLong(0L)
        val machine = SlimSseStateMachine(Any()) { epoch.get() }

        // Create a token bypassing capture (simulate a token from before
        // epoch provider was added, or a token manufactured by reflection).
        val token = OpenCodeRepository.SlimCommitToken(
            marker = Any(),
            issuedReady = true,
        )

        var committed = false
        val result = machine.commitIfSlimTokenCurrent(token) {
            committed = true
        }

        assertFalse("commitIfSlimTokenCurrent must return false for unregistered token", result)
        assertFalse("commit block must not execute for unregistered token", committed)
    }

    @Test
    fun `no epoch provider behaves as before`() {
        val machine = SlimSseStateMachine(Any())  // no provider

        val token = machine.captureSlimCommitToken()

        var committed = false
        val result = machine.commitIfSlimTokenCurrent(token) {
            committed = true
        }

        assertTrue("commitIfSlimTokenCurrent must return true without epoch provider", result)
        assertTrue("commit block must execute without epoch provider", committed)
    }

    @Test
    fun `beginSlimReconfigure clears token epochs`() {
        val epoch = AtomicLong(0L)
        val machine = SlimSseStateMachine(Any()) { epoch.get() }

        val tokenA = machine.captureSlimCommitToken()
        epoch.incrementAndGet()

        // beginSlimReconfigure clears map and sets readiness=false
        val ticket = machine.beginSlimReconfigure()

        // tokenA should be stale (cleared from map + epoch mismatch)
        assertFalse("tokenA should be stale after beginSlimReconfigure",
            machine.commitIfSlimTokenCurrent(tokenA) {})

        // Complete the reconfigure so readiness re-arms
        machine.completeSlimReconfigure(ticket)

        // Now capture a new token (should be current)
        val tokenB = machine.captureSlimCommitToken()
        assertTrue("tokenB should be current after completeSlimReconfigure",
            machine.commitIfSlimTokenCurrent(tokenB) {})

        // tokenA should still be stale (map entry gone)
        assertFalse("tokenA should remain stale after completeSlimReconfigure",
            machine.commitIfSlimTokenCurrent(tokenA) {})
    }
}
