package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.SlimSessionDigest
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.supervisorScope

/**
 * OCR wiring integration test: verifies that [OpenCodeRepository]'s epoch guard
 * correctly rejects a commit token after [ConnectionIdentityStore.beginReconfigure]
 * (epoch bump), WITHOUT requiring the full [OpenCodeRepository.beginSlimReconfigure]
 * transaction (marker rotation is done by the host-switch path in production).
 *
 * This validates the real wiring: [identityStore] → [epochProvider] →
 * [SlimSseStateMachine.commitIfSlimTokenCurrent].
 */
class OpenCodeRepositoryEpochWiringTest {

    @Test
    fun `epoch bump via identityStore beginReconfigure blocks commit`() {
        val tracker = mockk<TrafficTracker>(relaxed = true)
        val logger = mockk<TrafficLogger>(relaxed = true)
        val repository = OpenCodeRepository(tracker, logger)
        repository.identityStore = ConnectionIdentityStore()

        // Capture a token (current epoch = 0)
        val token = repository.captureSlimCommitToken()

        // Simulate host-switch: bump epoch via ConnectionIdentityStore (not
        // repository.beginSlimReconfigure, which also rotates marker). This
        // isolates the epoch-check behavior from marker rotation.
        repository.identityStore.beginReconfigure()

        var committed = false
        val result = repository.commitIfSlimTokenCurrent(token) {
            committed = true
        }

        assertFalse(
            "commitIfSlimTokenCurrent must return false after identityStore epoch bump",
            result,
        )
        assertFalse(
            "commit block must not execute after epoch bump",
            committed,
        )
    }

    @Test
    fun `identity rebinding at the same epoch blocks an old slim operation`() {
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val identities = ConnectionIdentityStore()
        repository.identityStore = identities
        identities.bind("group-a", "/work", "host-a.test")
        val tokenA = repository.captureSlimCommitToken()

        // This intentionally does not bump the epoch.  The identity itself is
        // still a separate guard from the monotonic epoch counter.
        identities.bind("group-b", "/work", "host-b.test")

        var committed = false
        assertFalse(
            repository.commitIfSlimTokenCurrent(tokenA) { committed = true },
        )
        assertFalse(committed)
    }

    @Test
    fun `T3-3-C3 C7 stale slim commit rejects suspended A without touching B state`() =
        runBlocking {
            supervisorScope {
                val tracker = mockk<TrafficTracker>(relaxed = true)
                val logger = mockk<TrafficLogger>(relaxed = true)
                val repository = OpenCodeRepository(tracker, logger)
                val identities = ConnectionIdentityStore()
                repository.identityStore = identities

                repository.configure(
                    baseUrl = "http://host-a.test",
                    slim = true,
                )
                identities.bind("group-a", "/work", "host-a.test")
                val tokenA = repository.captureSlimCommitToken()

                // The deferred represents the in-flight A network response.  The
                // commit is deliberately attempted only after B is published.
                // Keep the whole scenario inside supervisorScope: creating an
                // async inside a short-lived supervisorScope would await it
                // before this test can publish incarnation B.
                val responseReleased = CompletableDeferred<Unit>()
                val oldResult = async {
                    responseReleased.await()
                    repository.requireSlimTokenCurrent(tokenA)
                    repository.commitIfSlimTokenCurrent(tokenA) {
                        repository.applySlimDigest(
                            SlimSessionDigest(sessionId = "b-session", updatedAt = 99L),
                            tokenA,
                        )
                    }
                }

                identities.beginReconfigure()
                repository.configure(
                    baseUrl = "http://host-b.test",
                    slim = true,
                )
                val tokenB = repository.captureSlimCommitToken()
                assertTrue(
                    "B readiness must be armed before releasing A",
                    repository.isSlimCommitTokenCurrent(tokenB),
                )
                repository.applySlimDigest(
                    SlimSessionDigest(sessionId = "b-session", updatedAt = 7L),
                    tokenB,
                )

                responseReleased.complete(Unit)
                assertThrows(OpenCodeRepository.StaleSlimCommitException::class.java) {
                    runBlocking { oldResult.await() }
                }

                assertEquals(
                    "the B watermark must not be overwritten by A",
                    7L,
                    repository.snapshotSlimSseState()["b-session"]?.remoteUpdatedAt,
                )
                val bState = repository.snapshotSlimSseState()["b-session"]!!
                assertNull(
                    "a stale A result must not merge into B's local-applied message watermark",
                    bState.localAppliedUpdatedAt,
                )
                assertNull(
                    "a stale A result must not merge its message id into B state",
                    bState.localAppliedMessageId,
                )
                assertTrue(
                    "B readiness must remain armed after stale A release",
                    repository.isSlimCommitTokenCurrent(tokenB),
                )
                assertEquals(
                    "the B generation remains the published generation",
                    2L,
                    repository.currentClientBundle()!!.generation,
                )
            }
        }
}
