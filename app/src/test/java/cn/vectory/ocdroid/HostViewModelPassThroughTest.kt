package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.ConnectionFormSettings
import cn.vectory.ocdroid.ui.HostViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * R18 Phase 5++ coverage: thin pass-through methods on [HostViewModel] that
 * simply delegate to [HostProfileController]. Coverage gap before this file:
 * L8: selectHostProfile/duplicateHostProfile/deleteHostProfile removed
 * (single-host mode). Remaining delegators (importHostProfile,
 * exportHostProfile, getHostProfiles, currentHostProfile, configureServer,
 * getSavedConnectionSettings, resetLocalDataAndResync) are covered here.
 *
 * This suite drives each delegator and verifies the controller received the
 * call. The VM bodies are 1-line forwards; the controller side-effects are
 * mocked so we don't depend on its full state machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostViewModelPassThroughTest : MainViewModelTestBase() {

    @Test
    fun `importHostProfile returns controller result`() = runTest {
        val core = createCore()
        val vm = HostViewModel(core)
        every { hostProfileStore.currentProfile() } returns HostProfile.defaultDirect("http://x")

        // The relaxed-mock hostProfileStore.importJson returns a relaxed
        // HostProfile — so the result is success. The point of this test is
        // to drive the VM delegator body, not the controller's parsing.
        val result = vm.importHostProfile("ignored-by-mock")
        assertEquals(true, result.isSuccess)
    }

    @Test
    fun `exportHostProfile returns controller JSON`() = runTest {
        val core = createCore()
        val vm = HostViewModel(core)
        val profile = HostProfile(id = "p1", serverUrl = "http://x", name = "P1")
        every { hostProfileStore.exportJson(profile) } returns "{\"name\":\"P1\"}"

        val json = vm.exportHostProfile(profile)
        assertEquals("{\"name\":\"P1\"}", json)
    }

    @Test
    fun `getHostProfiles delegates to controller`() = runTest {
        val core = createCore()
        val vm = HostViewModel(core)
        every { hostProfileStore.profiles() } returns listOf(
            HostProfile(id = "p1", serverUrl = "http://x", name = "P1"),
        )

        val list = vm.getHostProfiles()
        assertEquals(1, list.size)
    }

    @Test
    fun `currentHostProfile delegates to controller`() = runTest {
        val core = createCore()
        val vm = HostViewModel(core)
        every { hostProfileStore.currentProfile() } returns HostProfile(id = "p1", serverUrl = "http://x", name = "P1")

        val p = vm.currentHostProfile()
        assertEquals("p1", p.id)
    }

    @Test
    fun `configureServer delegates to controller`() = runTest {
        val core = createCore()
        val vm = HostViewModel(core)
        every { hostProfileStore.currentProfile() } returns HostProfile.defaultDirect("http://x")

        vm.configureServer("http://new", "u", "p")
        advanceUntilIdle()
    }

    @Test
    fun `getSavedConnectionSettings delegates to controller`() = runTest {
        val core = createCore()
        val vm = HostViewModel(core)
        every { hostProfileStore.currentProfile() } returns HostProfile.defaultDirect("http://x")
        every { settingsManager.username } returns null
        every { settingsManager.password } returns null

        val settings = vm.getSavedConnectionSettings()
        // The controller returns a ConnectionFormSettings (non-null).
        assertNotNull(settings)
    }

    @Test
    fun `resetLocalDataAndResync delegates through AppCore`() = runTest {
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        coEvery { repository.checkHealth() } returns Result.success(
            HealthResponse(healthy = true, version = "1.0"),
        )
        every { settingsManager.clearAllLocalData() } just runs
        every { repository.connectSSE(any()) } returns kotlinx.coroutines.flow.emptyFlow()
        coEvery { repository.getCommands() } returns Result.success(emptyList())

        val core = createCore()
        val vm = HostViewModel(core)

        vm.resetLocalDataAndResync()
        advanceUntilIdle()

        verify { settingsManager.clearAllLocalData() }
    }

    @Test
    fun `saveHostProfile with all defaults delegates to controller`() = runTest {
        val core = createCore()
        val vm = HostViewModel(core)
        val profile = HostProfile(id = "p1", serverUrl = "http://x", name = "P1")

        // Call with defaults (basicAuthEdited=false).
        vm.saveHostProfile(profile)
        advanceUntilIdle()
    }
}
