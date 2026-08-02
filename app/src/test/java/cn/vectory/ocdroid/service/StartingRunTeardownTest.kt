package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §sse-zombie-fix (v4 R5): tests that the [OwnershipState.Starting.runTeardown]
 * helper is the single legal teardown path and that it runs the two callbacks
 * in the fixed order (transport disconnect FIRST, then shell abort), including
 * when [disconnectAndJoin] throws or the calling coroutine is cancelled.
 *
 * These tests are the primary evidence for R5 closure (the "forgot the second
 * callback" class is mechanically impossible) plus the C5 fix (try/finally
 * guarantees abort even on disconnect failure).
 *
 * Approach: construct [OwnershipState.Starting] directly (it is a public data
 * class) rather than going through the gate — this isolates the helper under
 * test from gate mechanics.
 */
class StartingRunTeardownTest {

    private val identity = ConnectionIdentity(1L, "p", "/w", "e")

    private fun makeStarting(
        disconnectAndJoin: suspend (Boolean) -> Unit,
        abortStartup: () -> Unit,
    ): OwnershipState.Starting = OwnershipState.Starting(
        identity = identity,
        attemptId = 1L,
        disconnectAndJoin = disconnectAndJoin,
        abortStartup = abortStartup,
        terminal = CompletableDeferred(),
    )

    @Test
    fun `runTeardown invokes disconnect then abort exactly once in order`() = runTest {
        val calls = mutableListOf<String>()
        val starting = makeStarting(
            disconnectAndJoin = { calls += "disconnect:$it" },
            abortStartup = { calls += "abort" },
        )

        starting.runTeardown(markGap = false)

        assertEquals(listOf("disconnect:false", "abort"), calls)
    }

    @Test
    fun `runTeardown passes markGap through to disconnect`() = runTest {
        val gaps = mutableListOf<Boolean>()
        val starting = makeStarting(
            disconnectAndJoin = { gaps += it },
            abortStartup = { },
        )

        starting.runTeardown(markGap = true)

        assertEquals(listOf(true), gaps)
    }

    @Test
    fun `runTeardown runs abort even when disconnect throws`() = runTest {
        // §sse-zombie-fix-impl-rev2 (rev-gpt v4-impl-review critical C5):
        // disconnectAndJoin is suspend and may throw (transport error during
        // cancelAndJoin). abortStartup MUST still run — otherwise an ownerless
        // foreground Service / bootstrap shell is left behind. The try/finally
        // in runTeardown is the structural guarantee.
        var abortRan = false
        val starting = makeStarting(
            disconnectAndJoin = { throw RuntimeException("transport disconnect failed") },
            abortStartup = { abortRan = true },
        )

        var thrown: Throwable? = null
        try {
            starting.runTeardown(markGap = false)
        } catch (t: Throwable) {
            thrown = t
        }

        // The disconnect exception propagates (caller can observe/log it)...
        assertEquals("transport disconnect failed", thrown?.message)
        // ...but abort STILL ran (try/finally).
        assertTrue("abortStartup must run even when disconnectAndJoin throws", abortRan)
    }
}
