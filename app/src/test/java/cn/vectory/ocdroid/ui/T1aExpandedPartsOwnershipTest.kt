package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1a freeze — **RED until impl**. Step-1 `expandedParts` ownership migration
 * (full-refactor-plan §2.3 ownership table row 3 + §3.2 reducer skeleton +
 * §4-T1 acceptance "目标字段业务直接写入点静态归零；每字段族唯一 reducer").
 *
 * Migrates the three production write sites that today call
 * `SharedStateStore.mutateExpandedParts { ... }` directly:
 *
 *  | file                                        | line | current write                                   | new dispatch                                                |
 *  |---------------------------------------------|------|-------------------------------------------------|-------------------------------------------------------------|
 *  | `ComposerController.kt` (`togglePartExpand`)| ~134 | `mutateExpandedParts { it + (key to !current) }`| `dispatch(PartExpansionToggled(key, !current))`             |
 *  | `ComposerController.kt` (`clearExpandedParts`)| ~141| `mutateExpandedParts { emptyMap() }`            | `dispatch(ExpandedPartsCleared)`                            |
 *  | `SessionSwitcher.kt` (`switchTo` Step 5)    | ~537 | `mutateExpandedParts { emptyMap() }`            | `dispatch(ExpandedPartsCleared)`                            |
 *
 * **Expected action vocabulary** (T1a impl MUST add these to `AppAction`):
 *  - `data class PartExpansionToggled(val key: String, val expanded: Boolean) : AppAction`
 *  - `data object ExpandedPartsCleared : AppAction`
 *
 * Naming follows the plan's §3.2 skeleton verbatim
 * (`PartExpansionToggled`); `ExpandedPartsCleared` follows the existing
 * `StreamingCleared` / `DraftSessionMaterialized` past-tense-participle style.
 *
 * **Expected reduce branches** (T1a impl MUST add to `reduce`):
 *  ```kotlin
 *  is AppAction.PartExpansionToggled -> state.copy(
 *      expandedParts = state.expandedParts + (action.key to action.expanded)
 *  )
 *  AppAction.ExpandedPartsCleared -> state.copy(expandedParts = emptyMap())
 *  ```
 *
 * The `+ (key to value)` form mirrors the current production write EXACTLY:
 * a toggle-off sets `key → false` (it does NOT `minus(key)`); that is the
 * pre-T1a behavior pinned by `ComposerControllerTest.togglePartExpand toggles
 * expansion state` (toggling `currentValue = true` writes `false`, keeps the
 * key). T1a is a 1:1 mechanical migration — no semantic change.
 *
 * **RED kind**: `compile-error` — `AppAction.PartExpansionToggled` and
 * `AppAction.ExpandedPartsCleared` are unresolved references today. Once the
 * impl lane adds the two action types + the two reduce branches, every test
 * in this file should go GREEN unchanged (no test-side edits needed).
 *
 * **P0-6 / existing-test impact**: NONE. This file is additive; the
 * `expandedParts` migration is a writer-route change, not a state-shape or
 * semantics change. `ComposerControllerTest` / `ChatViewModelTest` /
 * `SessionSwitcherTest` / `LegacyGoldenPathRegressionTest P0-6` / slim-fold
 * tests all assert on the OBSERVABLE `expandedParts.value` map and stay green
 * whether the write route is `mutateExpandedParts` or `dispatch(AppAction.*)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class T1aExpandedPartsOwnershipTest {

    // ── 1. Pure reducer — single-transition semantics ───────────────────────
    //
    // Direct `reduce(snapshot, action)` calls (no SharedStateStore, no
    // dispatcher, no runTest). Mirrors AppActionReducerTest group 1 style.

    @Test
    fun `reduce PartExpansionToggled(key, true) adds the key with value true`() {
        val prior = StoreState.initial()

        val out = reduce(prior, AppAction.PartExpansionToggled("fold:m1:p1", expanded = true))

        assertEquals(
            "PartExpansionToggled(key, true) writes key→true into expandedParts",
            mapOf("fold:m1:p1" to true),
            out.expandedParts,
        )
    }

    @Test
    fun `reduce PartExpansionToggled(key, false) sets key to false (NOT removed — mirrors mutateExpandedParts + semantics)`() {
        // Critical 1:1 parity with the legacy write. The pre-T1a production
        // path `mutateExpandedParts { it + (key to !current) }` SETS the key
        // to the inverted value; it does NOT `minus(key)`. So a toggle-OFF
        // (currentValue=true → !currentValue=false) leaves the key in the map
        // with value=false. Pinned by ComposerControllerTest:
        //   `togglePartExpand toggles expansion state` line 241-242 asserts
        //   `expandedParts.value["msg1|key1"] == false` after toggling
        //   currentValue=true. T1a MUST preserve this — `+ (key to false)`,
        //   not `- key`.
        val prior = StoreState.initial()

        val out = reduce(prior, AppAction.PartExpansionToggled("fold:m1:p1", expanded = false))

        assertEquals(
            "PartExpansionToggled(key, false) sets key→false (key retained, NOT removed) — " +
                "1:1 with legacy mutateExpandedParts { it + (key to false) }",
            mapOf("fold:m1:p1" to false),
            out.expandedParts,
        )
    }

    @Test
    fun `reduce PartExpansionToggled overwrites an existing key's value`() {
        val prior = StoreState.initial().copy(
            expandedParts = mapOf("k" to false),
        )

        val out = reduce(prior, AppAction.PartExpansionToggled("k", expanded = true))

        assertEquals(
            "PartExpansionToggled on an existing key OVERWRITES the value (map `+` semantics)",
            mapOf("k" to true),
            out.expandedParts,
        )
    }

    @Test
    fun `reduce PartExpansionToggled preserves other keys`() {
        val prior = StoreState.initial().copy(
            expandedParts = mapOf("a" to true, "b" to false),
        )

        val out = reduce(prior, AppAction.PartExpansionToggled("c", expanded = true))

        assertEquals(
            "PartExpansionToggled preserves every other key in the map",
            mapOf("a" to true, "b" to false, "c" to true),
            out.expandedParts,
        )
    }

    @Test
    fun `reduce ExpandedPartsCleared empties the expandedParts map`() {
        val prior = StoreState.initial().copy(
            expandedParts = mapOf("a" to true, "b" to false, "c" to true),
        )

        val out = reduce(prior, AppAction.ExpandedPartsCleared)

        assertTrue(
            "ExpandedPartsCleared empties expandedParts (1:1 with mutateExpandedParts { emptyMap() })",
            out.expandedParts.isEmpty(),
        )
    }

    @Test
    fun `reduce ExpandedPartsCleared on an already-empty map is a no-op (idempotent)`() {
        val prior = StoreState.initial() // expandedParts defaults to emptyMap

        val out = reduce(prior, AppAction.ExpandedPartsCleared)

        assertTrue(
            "ExpandedPartsCleared on an empty map is a no-op",
            out.expandedParts.isEmpty(),
        )
    }

    // ── 2. Reducer purity + scoping ────────────────────────────────────────
    //
    // The reducer MUST be pure (full-refactor-plan §2.3 "reduce 纯函数，禁
    // network/settings/effect/suspend"; AppAction.kt:33-42 purity contract).
    // These tests verify (a) the reducer does not touch any slice outside
    // expandedParts (domain scoping), and (b) the input state is not mutated
    // (immutability — `data class copy` discipline).

    @Test
    fun `reduce PartExpansionToggled is scoped to expandedParts — every other slice untouched`() {
        val prior = StoreState.initial().copy(
            // Seed every slice with a non-default value so a partial
            // mutation would be observable (an empty/default StoreState
            // would not distinguish a real copy from a fresh instantiation).
            connection = ConnectionState(serverVersion = "1.0"),
            composer = ComposerState(inputText = "draft"),
            chat = ChatState(currentSessionId = "sess"),
            sessionList = SessionListState(sessions = listOf(Session(id = "sess", directory = "/p"))),
            unread = UnreadState(unreadSessions = setOf("sess")),
            nav = NavState(),
        )

        val out = reduce(prior, AppAction.PartExpansionToggled("k", expanded = true))

        // The whole-state equality against `prior.copy(expandedParts = ...)`
        // proves (a) only expandedParts changed AND (b) the new value is
        // exactly `prior.expandedParts + ("k" to true)`. One assertion, full
        // scoping coverage.
        assertEquals(
            "PartExpansionToggled touches ONLY expandedParts — every other slice " +
                "is byte-for-byte unchanged (domain-scoped reducer)",
            prior.copy(expandedParts = prior.expandedParts + ("k" to true)),
            out,
        )
    }

    @Test
    fun `reduce ExpandedPartsCleared is scoped to expandedParts — every other slice untouched`() {
        val prior = StoreState.initial().copy(
            connection = ConnectionState(serverVersion = "1.0"),
            composer = ComposerState(inputText = "draft"),
            chat = ChatState(currentSessionId = "sess"),
            sessionList = SessionListState(sessions = listOf(Session(id = "sess", directory = "/p"))),
            unread = UnreadState(unreadSessions = setOf("sess")),
            expandedParts = mapOf("a" to true, "b" to false),
        )

        val out = reduce(prior, AppAction.ExpandedPartsCleared)

        assertEquals(
            "ExpandedPartsCleared touches ONLY expandedParts — every other slice " +
                "is byte-for-byte unchanged (domain-scoped reducer)",
            prior.copy(expandedParts = emptyMap()),
            out,
        )
    }

    @Test
    fun `reduce PartExpansionToggled does not mutate the input state (immutability)`() {
        val prior = StoreState.initial().copy(expandedParts = mapOf("existing" to true))
        val priorSnapshot = prior.copy()

        reduce(prior, AppAction.PartExpansionToggled("new", expanded = true))

        assertEquals(
            "input StoreState MUST NOT be mutated by reduce (referential transparency / purity)",
            priorSnapshot,
            prior,
        )
    }

    @Test
    fun `reduce is deterministic — same inputs produce same output (referential transparency)`() {
        val prior = StoreState.initial().copy(
            expandedParts = mapOf("a" to true),
            chat = ChatState(currentSessionId = "sess"),
        )
        val action = AppAction.PartExpansionToggled("b", expanded = false)

        val out1 = reduce(prior, action)
        val out2 = reduce(prior, action)

        assertEquals(
            "reduce(prior, action) is deterministic — repeated calls return equal StoreState",
            out1,
            out2,
        )
    }

    // ── 3. Atomicity — single dispatch = single stateFlow emission ─────────
    //
    // Mirrors AppActionReducerTest group 2. Collects store.stateFlow (the B2
    // aggregate); dispatches one action; asserts EXACTLY ONE post-initial
    // emission (no torn intermediate). This is the foundation that lets
    // concurrent stateFlow collectors observe a single atomic transition.

    @Test
    fun `dispatch PartExpansionToggled produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore()

        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()
        assertEquals("initial state emitted once", 1, seen.size)

        store.dispatch(AppAction.PartExpansionToggled("k", expanded = true))
        advanceUntilIdle()

        assertEquals(
            "exactly one initial + one post-dispatch emission (no torn intermediate)",
            2,
            seen.size,
        )
        assertEquals(
            "the single post-dispatch state carries the new key",
            mapOf("k" to true),
            seen.last().expandedParts,
        )
        job.cancel()
    }

    @Test
    fun `dispatch ExpandedPartsCleared produces exactly one aggregate emission`() = runTest {
        val store = SharedStateStore().apply {
            mutateExpandedParts { mapOf("a" to true, "b" to false) }
        }

        val seen = mutableListOf<StoreState>()
        val job = launch { store.stateFlow.collect { seen += it } }
        advanceUntilIdle()
        assertEquals(1, seen.size)

        store.dispatch(AppAction.ExpandedPartsCleared)
        advanceUntilIdle()

        assertEquals(
            "exactly one initial + one post-dispatch emission (no torn intermediate)",
            2,
            seen.size,
        )
        assertTrue(
            "the single post-dispatch state has expandedParts cleared",
            seen.last().expandedParts.isEmpty(),
        )
        job.cancel()
    }

    // ── 4. Equivalence — new dispatch path ≡ old mutateExpandedParts path ──
    //
    // CORE CONTRACT (task brief §3 等价性). For the same seed StoreState, the
    // new `dispatch(AppAction.*)` path MUST produce a byte-for-byte equal
    // final StoreState to the legacy `mutateExpandedParts { ... }` path.
    // These tests are the migration's behavior-equivalence proof: if the impl
    // lane's reducer diverges (even slightly) from the legacy `+ (key to v)`
    // / `emptyMap()` semantics, these fail loudly.
    //
    // Two stores seeded identically; old path applies the legacy mutate on
    // one, new path dispatches the action on the other; assert whole
    // StoreState equality (data-class equals covers every slice).

    @Test
    fun `dispatch PartExpansionToggled is byte-for-byte equivalent to mutateExpandedParts add`() = runTest {
        val oldStore = seedStore()
        val newStore = seedStore()
        // Sanity: both seeds start byte-for-byte equal.
        assertEquals(oldStore.stateFlow.value, newStore.stateFlow.value)

        oldStore.mutateExpandedParts { it + ("new-key" to true) }
        newStore.dispatch(AppAction.PartExpansionToggled("new-key", expanded = true))

        assertEquals(
            "new path (dispatch PartExpansionToggled) MUST produce a byte-for-byte equal " +
                "StoreState to the legacy path (mutateExpandedParts { it + (key to v) })",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    @Test
    fun `dispatch PartExpansionToggled to false is byte-for-byte equivalent to mutateExpandedParts add-false`() = runTest {
        // The toggle-OFF parity (key SET to false, NOT removed). Critical
        // because the legacy `+ (key to false)` retains the key; a future
        // refactor that "helpfully" switched to `minus(key)` would silently
        // change observable behavior for cards whose default-expanded state
        // differs from `expandedParts[key] ?: false`.
        val oldStore = seedStore()
        val newStore = seedStore()

        oldStore.mutateExpandedParts { it + ("toggle-off" to false) }
        newStore.dispatch(AppAction.PartExpansionToggled("toggle-off", expanded = false))

        assertEquals(
            "toggle-OFF via dispatch MUST equal legacy mutateExpandedParts { it + (key to false) } " +
                "(key retained, value false — NOT minus)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    @Test
    fun `dispatch ExpandedPartsCleared is byte-for-byte equivalent to mutateExpandedParts emptyMap`() = runTest {
        val oldStore = seedStore()
        val newStore = seedStore()

        oldStore.mutateExpandedParts { emptyMap() }
        newStore.dispatch(AppAction.ExpandedPartsCleared)

        assertEquals(
            "new path (dispatch ExpandedPartsCleared) MUST produce a byte-for-byte equal " +
                "StoreState to the legacy path (mutateExpandedParts { emptyMap() })",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    @Test
    fun `full toggle-and-clear sequence — final StoreState byte-for-byte equal between old and new paths`() = runTest {
        // End-to-end equivalence over a realistic toggle → toggle-off →
        // toggle-another → clear sequence (mirrors a user expanding a tool
        // card, collapsing it, expanding a reasoning card, then switching
        // sessions which triggers the SessionSwitcher clear).
        val oldStore = seedStore()
        val newStore = seedStore()
        assertEquals(oldStore.stateFlow.value, newStore.stateFlow.value)

        // 1. Toggle on key-A (currentValue=false → new value=true)
        oldStore.mutateExpandedParts { it + ("key-A" to true) }
        newStore.dispatch(AppAction.PartExpansionToggled("key-A", expanded = true))
        assertEquals(oldStore.stateFlow.value, newStore.stateFlow.value)

        // 2. Toggle off key-A (currentValue=true → new value=false; key retained)
        oldStore.mutateExpandedParts { it + ("key-A" to false) }
        newStore.dispatch(AppAction.PartExpansionToggled("key-A", expanded = false))
        assertEquals(oldStore.stateFlow.value, newStore.stateFlow.value)

        // 3. Toggle on key-B
        oldStore.mutateExpandedParts { it + ("key-B" to true) }
        newStore.dispatch(AppAction.PartExpansionToggled("key-B", expanded = true))
        assertEquals(oldStore.stateFlow.value, newStore.stateFlow.value)

        // 4. Clear all (session switch / explicit reset)
        oldStore.mutateExpandedParts { emptyMap() }
        newStore.dispatch(AppAction.ExpandedPartsCleared)

        assertEquals(
            "after a full toggle/clear sequence, the two paths produce byte-for-byte equal " +
                "final StoreStates (every slice, not just expandedParts)",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
        // And the clear actually fired (sanity — guards against a no-op reducer
        // that would trivially satisfy the equality by changing nothing on both).
        assertTrue(
            "expandedParts is empty after the clear step (sequence ran, not a no-op)",
            newStore.stateFlow.value.expandedParts.isEmpty(),
        )
        assertNotEquals(
            "post-sequence state differs from the seed (proves the sequence mutated state)",
            seedStore().stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    // ── 5. ComposerController migration seam — parity with the existing impl ─
    //
    // The pre-T1a `ComposerController.togglePartExpand(key, currentValue)`
    // computes `!currentValue` and writes it. The migrated impl MUST dispatch
    // `PartExpansionToggled(key, !currentValue)` — same computation, same
    // resulting map. This test seeds two stores identically and applies the
    // SAME `currentValue` arg through both paths, asserting final equality.
    //
    // (This test does NOT instantiate ComposerController — it tests the
    // pure-state parity at the dispatch layer. A controller-level integration
    // test is already covered by the existing `ComposerControllerTest` /
    // `ChatViewModelPassThroughTest` / `ChatViewModelTest`, which assert on
    // `expandedParts.value` and stay green whether the write route is
    // mutateExpandedParts or dispatch.)

    @Test
    fun `dispatch PartExpansionToggled with inverted currentValue is equivalent to ComposerController toggle`() = runTest {
        // ComposerController.togglePartExpand(key, currentValue=true) writes
        // `it + (key to !currentValue)` = `it + (key to false)`.
        // The migrated ComposerController will dispatch
        // PartExpansionToggled(key, expanded = !currentValue) = PartExpansionToggled(key, false).
        val currentValue = true // the displayed value passed into togglePartExpand
        val newDisplayedValue = !currentValue // what togglePartExpand computes

        val oldStore = seedStore()
        val newStore = seedStore()

        // Old path: ComposerController's current body (mutateExpandedParts).
        oldStore.mutateExpandedParts { it + ("fold:m1:part-1" to newDisplayedValue) }
        // New path: what the migrated ComposerController will dispatch.
        newStore.dispatch(
            AppAction.PartExpansionToggled("fold:m1:part-1", expanded = newDisplayedValue)
        )

        assertEquals(
            "dispatch PartExpansionToggled(key, !currentValue) produces the same StoreState as " +
                "ComposerController's current mutateExpandedParts { it + (key to !currentValue) }",
            oldStore.stateFlow.value,
            newStore.stateFlow.value,
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Builds a freshly-seeded [SharedStateStore] with NON-DEFAULT values in
     * multiple slices so a partial mutation would be observable. Used by the
     * equivalence tests to seed two stores identically before applying old
     * vs new paths.
     */
    private fun seedStore(): SharedStateStore = SharedStateStore().apply {
        mutateExpandedParts { mapOf("existing" to true, "other" to false) }
        mutateChat { it.copy(currentSessionId = "sess-A") }
        mutateComposer { it.copy(inputText = "seed-draft") }
        mutateSessionList {
            it.copy(sessions = listOf(Session(id = "sess-A", directory = "/proj")))
        }
    }
}
