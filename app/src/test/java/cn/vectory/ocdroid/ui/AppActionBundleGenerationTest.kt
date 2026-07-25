package cn.vectory.ocdroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppActionBundleGenerationTest {

    @Test
    fun `bundle publication stamp is monotonic in StoreState`() {
        val store = SharedStateStore()

        store.dispatch(AppAction.BundlePublished(2L, "https://host-b"))
        store.dispatch(AppAction.BundlePublished(1L, "https://host-a"))

        assertEquals(2L, store.stateFlow.value.liveBundleGeneration)
        assertEquals("https://host-b", store.stateFlow.value.liveEndpointFp)
    }

    @Test
    fun `bundle publication rejects endpoint reuse within the same generation`() {
        val store = SharedStateStore()

        store.dispatch(AppAction.BundlePublished(2L, "https://host-b"))
        store.dispatch(AppAction.BundlePublished(2L, "https://host-c"))

        assertEquals(2L, store.stateFlow.value.liveBundleGeneration)
        assertEquals("https://host-b", store.stateFlow.value.liveEndpointFp)
    }

    @Test
    fun `stale token-stream action is rejected at the store CAS boundary`() {
        val store = SharedStateStore()
        store.dispatch(AppAction.BundlePublished(2L, "https://host-b"))

        store.dispatch(
            AppAction.TokenStreamPartUpdated(
                partId = "p1",
                text = "stale",
                state = StreamOwnedState.STREAMING,
                bundleStamp = BundleStamp(1L, "https://host-a"),
            ),
        )

        assertEquals(emptyMap<String, String>(), store.chatFlow.value.streamingPartTexts)
        assertEquals(emptyMap<String, StreamOwnedState>(), store.chatFlow.value.streamOwned)
    }

    @Test
    fun `matching token-stream action commits at the store CAS boundary`() {
        val store = SharedStateStore()
        store.dispatch(AppAction.BundlePublished(2L, "https://host-b"))

        store.dispatch(
            AppAction.TokenStreamPartUpdated(
                partId = "p1",
                text = "current",
                state = StreamOwnedState.STREAMING,
                bundleStamp = BundleStamp(2L, "https://host-b"),
            ),
        )

        assertEquals(mapOf("p1" to "current"), store.chatFlow.value.streamingPartTexts)
        assertEquals(mapOf("p1" to StreamOwnedState.STREAMING), store.chatFlow.value.streamOwned)
    }

    @Test
    fun `zero bundle stamp is not a token-stream bypass`() {
        val store = SharedStateStore()
        store.dispatch(AppAction.BundlePublished(2L, "https://host-b"))

        store.dispatch(
            AppAction.TokenStreamPartUpdated(
                partId = "p1",
                text = "must-drop",
                state = StreamOwnedState.STREAMING,
                bundleStamp = BundleStamp(0L, ""),
            ),
        )

        assertEquals(emptyMap<String, String>(), store.chatFlow.value.streamingPartTexts)
        assertEquals(emptyMap<String, StreamOwnedState>(), store.chatFlow.value.streamOwned)
    }
}
