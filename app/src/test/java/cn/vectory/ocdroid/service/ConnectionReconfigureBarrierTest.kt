package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionReconfigureBarrierTest {
    @Test
    fun `teardown joins before configure and emits current epoch exactly once`() = runTest {
        val store = ConnectionIdentityStore()
        store.bind("old-group", "/old", "old-endpoint")
        val effects = SharedEffectBus()
        val received = mutableListOf<ControllerEffect>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            effects.effectsConsumed.toList(received)
        }
        var transportJoined = false
        val order = mutableListOf<String>()
        val repository = mockk<OpenCodeRepository>(relaxed = true)
        // C-D3 rev-3 round-5: beginSlimReconfigure now returns a
        // SlimReconfigureTicket. The barrier threads it through
        // ConnectionReconfigureContext so the caller's configure activates
        // the SAME transaction (ticket-ownership).
        every { repository.beginSlimReconfigure() } answers {
            order += "beginSlimReconfigure"
            OpenCodeRepository.SlimReconfigureTicket(Any())
        }
        val barrier = ConnectionReconfigureBarrier(
            store,
            repository,
            object : ReconfigureTeardown {
                override suspend fun teardownAndAwait(reason: TeardownReason) {
                    assertEquals(TeardownReason.Reconfigure, reason)
                    order += "disconnectAndJoin"
                    transportJoined = true
                }
            },
            effects,
        )

        barrier.reconfigure { ctx ->
            assertTrue("transport must be joined before repository rebuild", transportJoined)
            // C-D3 rev-3 round-5: context carries epoch + slimTicket from
            // the boundary's beginReconfigure / beginSlimReconfigure.
            assertEquals("context.epoch must match store epoch", store.currentEpoch(), ctx.epoch)
            assertNotNull("context.slimTicket must be populated", ctx.slimTicket)
            order += "configure"
            store.bind("new-group", "/new", "new-endpoint")
            assertEquals(ctx.epoch, store.currentEpoch())
        }
        runCurrent()

        // C-D3 rev-3: slim marker rotates before streaming teardown and
        // before the caller's configure block.
        assertEquals(
            listOf("beginSlimReconfigure", "disconnectAndJoin", "configure"),
            order,
        )
        verify(exactly = 1) { repository.beginSlimReconfigure() }
        assertEquals(1L, store.currentEpoch())
        val hostEffects = received.filterIsInstance<ControllerEffect.HostReconfigured>()
        assertEquals(1, hostEffects.size)
        assertEquals(store.currentEpoch(), hostEffects.single().epoch)
        assertFalse(store.isCurrent(cn.vectory.ocdroid.service.identity.ConnectionIdentity(0, "old-group", "/old", "old-endpoint")))
        assertTrue(store.isCurrent(store.currentIdentity.value!!))
    }
}
