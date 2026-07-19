package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.HostProfileSaveState
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.controller.HostProfileController
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * C-D3 rev-3 round-7 (review I5-R7): lifecycle coverage for the
 * [HostViewModel.saveHostProfile] / [HostViewModel.saveState] state machine.
 *
 * The save transaction is now owned by `viewModelScope` (survives screen
 * navigation; a reconfigure transaction MUST complete once begun). These
 * tests pin four invariants:
 *
 *  1. **Single-flight**: while [HostProfileSaveState.Saving] is current, a
 *     second `saveHostProfile` call is IGNORED (a reconfigure must finish
 *     once begun — never cancel/restart). Verified by checking state
 *     synchronously AFTER the first call but BEFORE the dispatcher advances
 *     (the launch body is still queued; state is Saving).
 *  2. **Recovery**: after [HostProfileSaveState.Done] (success OR failure),
 *     retry IS accepted (the state check is `is Saving`, not `is Done`);
 *     [HostViewModel.consumeSaveState] then returns to Idle so the screen
 *     observes a clean state on the next cycle.
 *  3. **profileId carried in Done**: the screen's stale-completion guard
 *     (`editingProfile?.id == s.profileId`) relies on Done carrying the id
 *     of the save that produced it. Verified at the VM level — a Compose
 *     test would just re-derive the same id matching.
 *  4. **viewModelScope ownership** (implicit): single-flight only holds
 *     across rapid successive calls because the same viewModelScope owns
 *     the in-flight job (a screen-level scope cancelled between submits
 *     would let the second submit launch a new save).
 *
 * Pattern: a mockk [HostProfileController] so we can control saveHostProfile's
 * suspend return (failure / success sequences) without dragging the full
 * controller state machine. MainDispatcherRule sets `Dispatchers.Main` to a
 * StandardTestDispatcher so `viewModelScope.launch` only runs when
 * [advanceUntilIdle] is called.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostViewModelSaveStateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var store: SharedStateStore
    private lateinit var controller: HostProfileController
    private lateinit var settingsManager: SettingsManager
    private lateinit var vm: HostViewModel

    @Before
    fun setUp() {
        // mockk the controller so we can control saveHostProfile's suspend
        // return (failure / success) without dragging the full controller
        // state machine into these lifecycle tests.
        controller = mockk(relaxed = true)
        store = SharedStateStore()
        settingsManager = mockk(relaxed = true)
        vm = HostViewModel(store, controller, settingsManager)
    }

    /**
     * Single-flight: while Saving is current, a second saveHostProfile call is
     * silently ignored — the in-flight reconfigure transaction must finish.
     *
     * Verified by checking state SYNCHRONOUSLY after the first call: the VM
     * sets Saving synchronously before launching (StandardTestDispatcher
     * doesn't run the launch body until advanced), so the second call sees
     * Saving and returns early. After advanceUntilIdle the first launch runs
     * to completion (Done) and the controller is verified to have been called
     * exactly once (for profileA).
     */
    @Test
    fun `saveHostProfile double-submit while Saving is ignored (single-flight)`() = runTest {
        coEvery {
            controller.saveHostProfile(any(), any(), any(), any(), any(), any())
        } returns Result.success(Unit)

        val profileA = HostProfile(id = "p-a", serverUrl = "http://a", name = "A")
        val profileB = HostProfile(id = "p-b", serverUrl = "http://b", name = "B")

        // First call: state goes Saving(pA) SYNCHRONOUSLY (before the launch
        // body runs — StandardTestDispatcher queues the launch).
        vm.saveHostProfile(profileA)
        assertEquals(
            "first call must transition to Saving(pA) synchronously",
            "p-a",
            (vm.saveState.value as HostProfileSaveState.Saving).profileId,
        )

        // Second call IMMEDIATELY (no advance): state is Saving → single-flight
        // ignores. State stays Saving(pA); controller is NOT called for profileB.
        vm.saveHostProfile(profileB)
        assertEquals(
            "second call must be ignored — state stays Saving(pA), not pB",
            "p-a",
            (vm.saveState.value as HostProfileSaveState.Saving).profileId,
        )

        // Now advance: first launch runs, completes with success → Done(pA).
        advanceUntilIdle()
        val done = vm.saveState.value as HostProfileSaveState.Done
        assertEquals(
            "Done carries pA (the in-flight save), NOT pB (the ignored submit)",
            "p-a",
            done.profileId,
        )
        assertTrue("Done.result is success", done.result.isSuccess)

        // Controller received exactly ONE call — for profileA. profileB never
        // reached it (single-flight).
        coVerify(exactly = 1) {
            controller.saveHostProfile(any(), any(), any(), any(), any(), any())
        }
    }

    /**
     * Recovery: after Done(error), retry IS accepted (the state check is
     * `is Saving`; Done doesn't block). [consumeSaveState] then returns to
     * Idle so the screen observes a clean state on the next cycle. The VM's
     * job is no longer active once Done is reached.
     */
    @Test
    fun `saveHostProfile recovery after error accepts retry`() = runTest {
        coEvery {
            controller.saveHostProfile(any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            Result.failure(IOException("first fails")),
            Result.success(Unit),
        )
        val profile = HostProfile(id = "p-retry", serverUrl = "http://x", name = "X")

        // First save: fails → Done(p-retry, failure).
        vm.saveHostProfile(profile)
        advanceUntilIdle()
        val firstDone = vm.saveState.value as HostProfileSaveState.Done
        assertTrue("first save fails", firstDone.result.isFailure)
        assertEquals("p-retry", firstDone.profileId)

        // Retry IS accepted (state is Done, not Saving — does not block).
        vm.saveHostProfile(profile)
        assertEquals(
            "retry accepted → Saving(p-retry) synchronously",
            "p-retry",
            (vm.saveState.value as HostProfileSaveState.Saving).profileId,
        )
        advanceUntilIdle()
        val secondDone = vm.saveState.value as HostProfileSaveState.Done
        assertTrue("second save succeeds", secondDone.result.isSuccess)

        // Screen consumes → state returns to Idle (clean for next cycle).
        vm.consumeSaveState()
        assertEquals(
            "consumeSaveState returns to Idle",
            HostProfileSaveState.Idle,
            vm.saveState.value,
        )

        // Controller was called exactly twice (first fail + retry success).
        coVerify(exactly = 2) {
            controller.saveHostProfile(any(), any(), any(), any(), any(), any())
        }
    }

    /**
     * profileId carried in Done: the screen's stale-completion guard
     * (`editingProfile?.id == s.profileId`) only works if Done.profileId is
     * the id of the save that produced it. Verified at the VM level — a
     * Compose-level test would just re-derive the same id matching logic.
     *
     * Screen guard reference (HostProfilesManagerScreen LaunchedEffect):
     * ```
     * if (s is HostProfileSaveState.Done) {
     *     s.result.onSuccess {
     *         if (editingProfile?.id == s.profileId) editingProfile = null
     *     }...
     * }
     * ```
     */
    @Test
    fun `Done carries the profileId of the save that produced it (screen guard invariant)`() = runTest {
        coEvery {
            controller.saveHostProfile(any(), any(), any(), any(), any(), any())
        } returns Result.success(Unit)
        val profile = HostProfile(id = "p-specific", serverUrl = "http://x", name = "X")

        vm.saveHostProfile(profile)
        advanceUntilIdle()

        val done = vm.saveState.value as HostProfileSaveState.Done
        assertEquals(
            "Done.profileId must match the save that produced it (screen's stale-completion guard relies on this)",
            "p-specific",
            done.profileId,
        )
        assertTrue(done.result.isSuccess)
    }

    /**
     * Initial state: a fresh VM exposes [HostProfileSaveState.Idle] so the
     * screen's Save button is enabled on first render (no false "saving"
     * state leaking across VM instances).
     */
    @Test
    fun `saveState initial value is Idle`() {
        assertEquals(
            "fresh VM must expose Idle so the Save button is enabled",
            HostProfileSaveState.Idle,
            vm.saveState.value,
        )
    }

    /**
     * consumeSaveState is idempotent: calling it while Idle or Saving is a
     * no-op (no spurious transitions). The screen may call it from a
     * LaunchedEffect on recomposition; it must be safe to call anytime.
     */
    @Test
    fun `consumeSaveState is idempotent when already Idle`() {
        assertEquals(HostProfileSaveState.Idle, vm.saveState.value)
        vm.consumeSaveState()
        assertEquals(
            "consumeSaveState on Idle is a no-op",
            HostProfileSaveState.Idle,
            vm.saveState.value,
        )
    }

    /**
     * M1 (post-release polish): [HostViewModel.consumeSaveState] while
     * [HostProfileSaveState.Saving] is current is a no-op — the KDoc contract
     * (see [HostViewModel.consumeSaveState]) says "calling while Idle / Saving
     * is a no-op", and consuming mid-flight would clobber the in-flight
     * reconfigure transaction (orphaning it half-done). State stays Saving;
     * the launch still runs to Done on advance.
     */
    @Test
    fun `consumeSaveState while Saving is a no-op (does not clobber in-flight save)`() = runTest {
        coEvery {
            controller.saveHostProfile(any(), any(), any(), any(), any(), any())
        } returns Result.success(Unit)
        val profile = HostProfile(id = "p-m1", serverUrl = "http://m", name = "M")

        // saveHostProfile sets Saving(p-m1) SYNCHRONOUSLY before the launch
        // body runs (StandardTestDispatcher queues the launch).
        vm.saveHostProfile(profile)
        assertEquals(
            "state is Saving(p-m1) before the launch body runs",
            "p-m1",
            (vm.saveState.value as HostProfileSaveState.Saving).profileId,
        )

        // consumeSaveState during Saving MUST be a no-op — clobbering to Idle
        // here would orphan the in-flight reconfigure transaction.
        vm.consumeSaveState()
        assertEquals(
            "consumeSaveState while Saving is a no-op — state stays Saving(p-m1)",
            "p-m1",
            (vm.saveState.value as HostProfileSaveState.Saving).profileId,
        )

        // Advance: the launch runs to completion → Done(p-m1). The save was
        // NOT orphaned by the mid-flight consumeSaveState.
        advanceUntilIdle()
        val done = vm.saveState.value as HostProfileSaveState.Done
        assertEquals("p-m1", done.profileId)
        assertTrue("save completed despite mid-flight consumeSaveState", done.result.isSuccess)

        coVerify(exactly = 1) {
            controller.saveHostProfile(any(), any(), any(), any(), any(), any())
        }
    }
}
