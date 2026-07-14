package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val barrier = ConnectionReconfigureBarrier(
            store,
            object : ReconfigureTeardown {
                override suspend fun teardownAndAwait(reason: TeardownReason) {
                    assertEquals(TeardownReason.Reconfigure, reason)
                    order += "disconnectAndJoin"
                    transportJoined = true
                }
            },
            effects,
        )

        barrier.reconfigure { epoch ->
            assertTrue("transport must be joined before repository rebuild", transportJoined)
            order += "configure"
            store.bind("new-group", "/new", "new-endpoint")
            assertEquals(epoch, store.currentEpoch())
        }
        runCurrent()

        assertEquals(listOf("disconnectAndJoin", "configure"), order)
        assertEquals(1L, store.currentEpoch())
        val hostEffects = received.filterIsInstance<ControllerEffect.HostReconfigured>()
        assertEquals(1, hostEffects.size)
        assertEquals(store.currentEpoch(), hostEffects.single().epoch)
        assertFalse(store.isCurrent(cn.vectory.ocdroid.service.identity.ConnectionIdentity(0, "old-group", "/old", "old-endpoint")))
        assertTrue(store.isCurrent(store.currentIdentity.value!!))
    }
}
