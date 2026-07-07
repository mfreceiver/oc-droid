package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.ui.ComposerViewModel
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5++ coverage: thin delegators on [ComposerViewModel] that the
 * existing suite does not exercise. Coverage gap before this file:
 * 5/14 methods, 11/27 lines (mostly toggleModelDisabled,
 * reloadDisabledModelsForCurrentHost, togglePartExpand, addImageAttachments,
 * removeImageAttachment, clearDraftIfActive).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerViewModelPassThroughTest : MainViewModelTestBase() {

    @Test
    fun `toggleModelDisabled adds key when not currently disabled`() = runTest {
        every { hostProfileStore.currentProfile() } returns cn.vectory.ocdroid.data.model.HostProfile.defaultDirect("http://x")
        val core = createCore()
        val vm = ComposerViewModel(core)

        // Initial state: empty disabledModels.
        assertTrue(core.composerFlow.value.let { true })  // sanity
        assertEquals(emptySet<String>(), core.settingsFlow.value.disabledModels)

        vm.toggleModelDisabled("openai", "gpt-5")

        assertEquals(setOf("openai/gpt-5"), core.settingsFlow.value.disabledModels)
        verify { settingsManager.setModelDisabled(any(), "openai", "gpt-5", disabled = true) }
    }

    @Test
    fun `toggleModelDisabled removes key when currently disabled`() = runTest {
        every { hostProfileStore.currentProfile() } returns cn.vectory.ocdroid.data.model.HostProfile.defaultDirect("http://x")
        val core = createCore()
        val vm = ComposerViewModel(core)

        // Pre-disable the key.
        core.writeSettings { it.copy(disabledModels = setOf("openai/gpt-5")) }

        vm.toggleModelDisabled("openai", "gpt-5")

        assertEquals(emptySet<String>(), core.settingsFlow.value.disabledModels)
        verify { settingsManager.setModelDisabled(any(), "openai", "gpt-5", disabled = false) }
    }

    @Test
    fun `reloadDisabledModelsForCurrentHost delegates to the shared helper`() = runTest {
        every { hostProfileStore.currentProfile() } returns cn.vectory.ocdroid.data.model.HostProfile.defaultDirect("http://x")
        every { settingsManager.getDisabledModels(any()) } returns emptySet()

        val core = createCore()
        val vm = ComposerViewModel(core)

        vm.reloadDisabledModelsForCurrentHost()
        advanceUntilIdle()

        // The helper queried the disabled set for the current host's URL.
        verify(atLeast = 1) { settingsManager.getDisabledModels(any()) }
    }

    @Test
    fun `togglePartExpand flips the expanded state for the key`() = runTest {
        val core = createCore()
        val vm = ComposerViewModel(core)

        // Initial: empty expandedParts → toggle to true.
        vm.togglePartExpand("p1", currentValue = false)
        assertEquals(true, core.store.expandedParts.value["p1"])

        // Toggle back.
        vm.togglePartExpand("p1", currentValue = true)
        assertEquals(false, core.store.expandedParts.value["p1"])
    }

    @Test
    fun `addImageAttachments appends to the composer slice`() = runTest {
        val core = createCore()
        val vm = ComposerViewModel(core)

        val attachment = ComposerImageAttachment(
            id = "a1",
            filename = "x.png",
            mime = "image/png",
            dataUrl = "data:image/png;base64,Zm9v",
            thumbnailData = ByteArray(0),
            byteSize = 3,
        )
        vm.addImageAttachments(listOf(attachment))

        assertEquals(1, core.composerFlow.value.imageAttachments.size)
        assertEquals("a1", core.composerFlow.value.imageAttachments.first().id)
    }

    @Test
    fun `removeImageAttachment drops the attachment with the given id`() = runTest {
        val core = createCore()
        val vm = ComposerViewModel(core)

        val a1 = ComposerImageAttachment(
            id = "a1", filename = "x.png", mime = "image/png",
            dataUrl = "data:image/png;base64,Zm9v",
            thumbnailData = ByteArray(0), byteSize = 3,
        )
        val a2 = ComposerImageAttachment(
            id = "a2", filename = "y.png", mime = "image/png",
            dataUrl = "data:image/png;base64,YmFy",
            thumbnailData = ByteArray(0), byteSize = 3,
        )
        vm.addImageAttachments(listOf(a1, a2))
        assertEquals(2, core.composerFlow.value.imageAttachments.size)

        vm.removeImageAttachment("a1")
        assertEquals(1, core.composerFlow.value.imageAttachments.size)
        assertEquals("a2", core.composerFlow.value.imageAttachments.first().id)
    }

    @Test
    fun `clearDraftIfActive clears the draftWorkdir when set`() = runTest {
        val core = createCore()
        val vm = ComposerViewModel(core)
        core.writeComposer { it.copy(draftWorkdir = "/draft") }

        vm.clearDraftIfActive()

        assertEquals(null, core.composerFlow.value.draftWorkdir)
    }

    @Test
    fun `setInputText delegates to composerController`() = runTest {
        val core = createCore()
        val vm = ComposerViewModel(core)

        vm.setInputText("hello")

        assertEquals("hello", core.composerFlow.value.inputText)
    }

    @Test
    fun `selectAgent with no active session still writes the settings slice`() = runTest {
        val core = createCore()
        val vm = ComposerViewModel(core)

        vm.selectAgent("oracle")

        verify { settingsManager.selectedAgentName = "oracle" }
        assertEquals("oracle", core.settingsFlow.value.selectedAgentName)
    }

    @Test
    fun `switchSessionModel with no session only writes the slice`() = runTest {
        val core = createCore()
        val vm = ComposerViewModel(core)

        vm.switchSessionModel("anthropic", "claude")

        assertEquals(
            Message.ModelInfo("anthropic", "claude"),
            core.chatFlow.value.currentModel,
        )
        // No session id → no persistence call.
        verify(exactly = 0) { settingsManager.setModelForSession(any(), any(), any(), any()) }
    }

    @Test
    fun `composerFlow settingsFlow chatFlow are exposed on the VM`() = runTest {
        val core = createCore()
        val vm = ComposerViewModel(core)
        assertEquals(core.composerFlow, vm.composerFlow)
        assertEquals(core.settingsFlow, vm.settingsFlow)
        assertEquals(core.chatFlow, vm.chatFlow)
    }
}
