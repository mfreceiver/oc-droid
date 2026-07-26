package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.TokenStreamFrame
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TokenStreamCoordinatorBundleIdentityTest {

    @Test
    fun `stale bound bundle cannot commit an epoch frame`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val store = SharedStateStore()
        val repository = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )

        repository.configure(baseUrl = "http://host-a.test", slim = true)
        val bundleA = repository.currentClientBundle()!!
        repository.configure(baseUrl = "http://host-b.test", slim = true)
        val bundleB = repository.currentClientBundle()!!
        store.dispatch(AppAction.BundlePublished(bundleB.generation, bundleB.endpointFp))

        val coordinator = TokenStreamCoordinator(
            scope = scope,
            slices = store.slices,
            streamProvider = { _, _ -> emptyFlow() },
            triggerSinceFetch = { _, _ -> },
            bundleCommitLock = repository,
            currentBundleProvider = { repository.currentClientBundle() },
        )
        val epoch = coordinator.bumpEpochForTest("s1")
        val generation = coordinator.beginSession("s1")

        coordinator.dispatchEpochFrame(
            sid = "s1",
            epoch = epoch,
            gen = generation,
            frame = TokenStreamFrame.PartSnapshot("s1", "m1", "p1", "stale", false, false),
            capturedRouteInstance = 0L,
            boundBundle = bundleA,
        )

        assertTrue(store.chatFlow.value.streamingPartTexts.isEmpty())
        assertTrue(store.chatFlow.value.streamOwned.isEmpty())
        assertTrue(coordinator.ownedPartsForSid("s1").isEmpty())
    }
}
