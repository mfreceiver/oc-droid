package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Test

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
}
