package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * §A5-3 Phase B1: validates the composite-aggregate refactor of
 * [SharedStateStore]. The N per-slice `MutableStateFlow<XxxState>` were
 * collapsed into ONE `MutableStateFlow<StoreState>`; per-slice reads now go
 * through [DerivedStateFlow] projections.
 *
 * The B1 gate is behavior-preservation: every existing test stays GREEN
 * UNCHANGED (that gate is enforced by `./scripts/check.sh` over the whole
 * pre-existing suite). THIS file adds the projection-consistency test that
 * proves the refactor is lag-free + isolated + distinct-only — the invariant
 * B2 (`AppAction` / reducer / atomic multi-slice writes) will lean on.
 *
 * What MUST hold for B1 to be a no-behavior-change refactor:
 *  1. **Lag-free reads** — `chatFlow.value` is `selector(state.value)`, so a
 *     post-`mutateChat` read observes the new value on the SAME tick with NO
 *     `advanceUntilIdle` / dispatcher hop (the pre-B1 `MutableStateFlow.value`
 *     contract).
 *  2. **Slice isolation** — a `mutateChat` MUST NOT mutate any other slice's
 *     projected `.value` (the `StoreState.copy(chat = …)` leaves every other
 *     field reference untouched).
 *  3. **Distinct-only emissions** — collectors see a selector value at most
 *     once per change; a structural-equal (no-op) mutation does NOT re-emit
 *     (the pre-B1 `MutableStateFlow` distinct-value contract).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedStateStoreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `projected value reflects mutation on the same tick without a dispatcher advance`() = runTest {
        val store = SharedStateStore()
        // Synchronous, lag-free: chatFlow.value reads selector(state.value).chat
        // directly (NO async stateIn / SharingStarted hop). Right after
        // mutateChat returns — with NO advanceUntilIdle — the new value must be
        // observable. This is the lag-free contract.
        assertEquals(null, store.chatFlow.value.currentSessionId)
        store.mutateChat { it.copy(currentSessionId = "sess-1") }
        // NO advanceUntilIdle() here — this is the lag-free assertion.
        assertEquals("sess-1", store.chatFlow.value.currentSessionId)
    }

    @Test
    fun `mutating one slice does not change another slice projected value`() = runTest {
        val store = SharedStateStore()
        // Snapshot the OTHER slices' projected values before a chat mutation.
        val connectionBefore = store.connectionFlow.value
        val trafficBefore = store.trafficFlow.value
        val composerBefore = store.composerFlow.value
        val fileBefore = store.fileFlow.value
        val settingsBefore = store.settingsFlow.value
        val sessionListBefore = store.sessionListFlow.value
        val unreadBefore = store.unreadFlow.value
        val hostBefore = store.hostFlow.value
        val navBefore = store.navFlow.value
        val expandedBefore = store.expandedParts.value

        store.mutateChat { it.copy(currentSessionId = "sess-isolation", isCompacting = true) }

        // Every OTHER slice's projection must be the SAME instance — the
        // StoreState.copy(chat = ...) over the aggregate leaves the non-target
        // field references untouched (data-class copy semantics).
        assertSame(connectionBefore, store.connectionFlow.value)
        assertSame(trafficBefore, store.trafficFlow.value)
        assertSame(composerBefore, store.composerFlow.value)
        assertSame(fileBefore, store.fileFlow.value)
        assertSame(settingsBefore, store.settingsFlow.value)
        assertSame(sessionListBefore, store.sessionListFlow.value)
        assertSame(unreadBefore, store.unreadFlow.value)
        assertSame(hostBefore, store.hostFlow.value)
        assertSame(navBefore, store.navFlow.value)
        assertSame(expandedBefore, store.expandedParts.value)
        // And the TARGET slice did change.
        assertEquals("sess-isolation", store.chatFlow.value.currentSessionId)
        assertTrue(store.chatFlow.value.isCompacting)
    }

    @Test
    fun `projected flow emits distinct selector values only`() = runTest {
        val store = SharedStateStore()
        val seen = mutableListOf<String?>()
        val job = launch {
            store.chatFlow.collect { seen += it.currentSessionId }
        }
        // Let the collector subscribe + emit the current (initial) value.
        advanceUntilIdle()
        assertEquals(listOf<String?>(null), seen)

        // First real mutation → exactly one new emission.
        store.mutateChat { it.copy(currentSessionId = "a") }
        advanceUntilIdle()
        assertEquals(listOf(null, "a"), seen)

        // Second real mutation → exactly one new emission.
        store.mutateChat { it.copy(currentSessionId = "b") }
        advanceUntilIdle()
        assertEquals(listOf(null, "a", "b"), seen)

        // A no-op mutation (the transform returns the SAME ChatState) must NOT
        // emit — distinct selector values only, matching the pre-B1
        // MutableStateFlow distinct-value contract. (Double safety: the
        // aggregate MutableStateFlow suppresses structural-equal CAS, and
        // distinctUntilChanged() in DerivedStateFlow.collect would filter it
        // anyway.)
        store.mutateChat { it }
        advanceUntilIdle()
        assertEquals(listOf(null, "a", "b"), seen)

        job.cancel()
    }

    @Test
    fun `StoreState_initial matches the pre-B1 per-slice default constructions`() {
        // The composite must reproduce the EXACT starting values each slice had
        // as its own MutableStateFlow pre-B1. If this drifts, a behavior
        // regression slips in at construction time (the gate this test guards).
        val s = StoreState.initial()
        assertEquals(ConnectionState(), s.connection)
        assertEquals(TrafficState(), s.traffic)
        assertEquals(ComposerState(), s.composer)
        assertEquals(FileState(), s.file)
        assertEquals(SettingsState(), s.settings)
        assertEquals(ChatState(), s.chat)
        assertEquals(SessionListState(), s.sessionList)
        assertEquals(UnreadState(), s.unread)
        assertEquals(HostState(), s.host)
        assertEquals(emptyMap<String, Boolean>(), s.expandedParts)
        assertEquals(NavState(), s.nav)
    }
}
