package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-16 M2 → R-17 batch3b: independent unit test for [ComposerController].
 *
 * Zero reflection — the controller is driven entirely through its public API
 * (setInputText / addImageAttachments / removeImageAttachment /
 * clearDraftIfActive / togglePartExpand / clearExpandedParts) and asserted
 * via direct flow reads + a mockk [SettingsManager]. The batch 3b migration
 * eliminated [ComposerCallbacks]: the controller now calls
 * [SettingsManager.setDraftText] / [SettingsManager.currentWorkdir] directly.
 */
class ComposerControllerTest {

    // §R18 Phase 4 (P0-9): the controller takes a SharedStateStore; the test
    // holds read-only StateFlow views of the three slices it cares about so
    // assertions keep the same `.value.X` shape. Writes go through mutateXxx.
    private lateinit var store: SharedStateStore
    private lateinit var chatFlow: StateFlow<ChatState>
    private lateinit var composerFlow: StateFlow<ComposerState>
    private lateinit var expandedParts: StateFlow<Map<String, Boolean>>
    private lateinit var settingsManager: SettingsManager
    private lateinit var controller: ComposerController

    @Before
    fun setUp() {
        store = SharedStateStore()
        chatFlow = store.chatFlow
        composerFlow = store.composerFlow
        expandedParts = store.expandedParts
        settingsManager = mockk(relaxed = true)
        controller = ComposerController(
            store = store,
            settingsManager = settingsManager,
        )
    }

    // ── setInputText ───────────────────────────────────────────────────────

    @Test
    fun `setInputText updates the composer slice`() {
        controller.setInputText("hello world")

        assertEquals("hello world", composerFlow.value.inputText)
    }

    @Test
    fun `setInputText saves draft for active session`() {
        store.mutateChat { it.copy(currentSessionId = "s1") }

        controller.setInputText("draft text")

        verify { settingsManager.setDraftText("s1", "draft text") }
    }

    @Test
    fun `setInputText does not save draft when no session is active`() {
        store.mutateChat { it.copy(currentSessionId = null) }

        controller.setInputText("no session")

        assertEquals("no session", composerFlow.value.inputText)
        verify(exactly = 0) { settingsManager.setDraftText(any(), any()) }
    }

    @Test
    fun `setInputText overwrites previous text`() {
        controller.setInputText("first")
        controller.setInputText("second")

        assertEquals("second", composerFlow.value.inputText)
    }

    // ── addImageAttachments ─────────────────────────────────────────────────

    @Test
    fun `addImageAttachments appends to existing list`() {
        controller.setInputText("with images")
        controller.addImageAttachments(
            listOf(
                ComposerImageAttachment(id = "img1", filename = "f1.png", mime = "image/png", dataUrl = "", thumbnailData = ByteArray(0), byteSize = 0),
                ComposerImageAttachment(id = "img2", filename = "f2.jpg", mime = "image/jpeg", dataUrl = "", thumbnailData = ByteArray(0), byteSize = 0)
            )
        )

        assertEquals(2, composerFlow.value.imageAttachments.size)
        assertEquals("img1", composerFlow.value.imageAttachments[0].id)
        assertEquals("img2", composerFlow.value.imageAttachments[1].id)
    }

    @Test
    fun `addImageAttachments caps at 4`() {
        val five = (1..5).map {
                ComposerImageAttachment(id = "img$it", filename = "f$it.png", mime = "image/png", dataUrl = "", thumbnailData = ByteArray(0), byteSize = 0)
        }
        controller.addImageAttachments(five)

        assertEquals(4, composerFlow.value.imageAttachments.size)
        // Should keep the first 4 (take(4))
        assertEquals("img1", composerFlow.value.imageAttachments[0].id)
        assertEquals("img4", composerFlow.value.imageAttachments[3].id)
    }

    @Test
    fun `addImageAttachments with empty list is no-op`() {
        controller.addImageAttachments(emptyList())
        assertTrue(composerFlow.value.imageAttachments.isEmpty())
    }

    // ── removeImageAttachment ───────────────────────────────────────────────

    @Test
    fun `removeImageAttachment filters by id`() {
        controller.addImageAttachments(
            listOf(
                ComposerImageAttachment(id = "keep", filename = "keep.png", mime = "image/png", dataUrl = "", thumbnailData = ByteArray(0), byteSize = 0),
                ComposerImageAttachment(id = "drop", filename = "drop.jpg", mime = "image/jpeg", dataUrl = "", thumbnailData = ByteArray(0), byteSize = 0)
            )
        )
        assertEquals(2, composerFlow.value.imageAttachments.size)

        controller.removeImageAttachment("drop")

        assertEquals(1, composerFlow.value.imageAttachments.size)
        assertEquals("keep", composerFlow.value.imageAttachments.single().id)
    }

    @Test
    fun `removeImageAttachment with unknown id is no-op`() {
        controller.addImageAttachments(
            listOf(ComposerImageAttachment(id = "a", filename = "a.png", mime = "image/png", dataUrl = "", thumbnailData = ByteArray(0), byteSize = 0))
        )
        controller.removeImageAttachment("nonexistent")

        assertEquals(1, composerFlow.value.imageAttachments.size)
        assertEquals("a", composerFlow.value.imageAttachments.single().id)
    }

    // ── clearDraftIfActive ──────────────────────────────────────────────────

    @Test
    fun `clearDraftIfActive clears composer when draftWorkdir is set and no session`() {
        store.mutateComposer { it.copy(draftWorkdir = "/tmp/proj") }
        store.mutateChat { it.copy(currentSessionId = null) }
        controller.setInputText("draft content")

        controller.clearDraftIfActive()

        assertEquals(null, composerFlow.value.draftWorkdir)
        assertEquals("", composerFlow.value.inputText)
        assertTrue(composerFlow.value.imageAttachments.isEmpty())
        // §batch 3b: clearPersistedWorkdir is now an inline SettingsManager
        // currentWorkdir=null write (rule A) — verify the mock received it.
        verify { settingsManager.currentWorkdir = null }
    }

    @Test
    fun `clearDraftIfActive is no-op when draftWorkdir is null`() {
        store.mutateComposer { it.copy(draftWorkdir = null) }
        store.mutateChat { it.copy(currentSessionId = null) }
        controller.setInputText("not a draft")

        controller.clearDraftIfActive()

        // Input text preserved — this is NOT a draft session
        assertEquals("not a draft", composerFlow.value.inputText)
        verify(exactly = 0) { settingsManager.currentWorkdir = any() }
    }

    @Test
    fun `clearDraftIfActive is no-op when session is active (real session)`() {
        store.mutateComposer { it.copy(draftWorkdir = "/tmp/proj") }
        store.mutateChat { it.copy(currentSessionId = "s1") }

        controller.clearDraftIfActive()

        // Should NOT clear — currentSessionId != null means the draft has materialised
        assertEquals("/tmp/proj", composerFlow.value.draftWorkdir)
        verify(exactly = 0) { settingsManager.currentWorkdir = any() }
    }

    // ── togglePartExpand ────────────────────────────────────────────────────

    @Test
    fun `togglePartExpand toggles expansion state`() {
        controller.togglePartExpand("msg1|key1", currentValue = false)

        assertEquals(true, expandedParts.value["msg1|key1"])

        controller.togglePartExpand("msg1|key1", currentValue = true)
        assertEquals(false, expandedParts.value["msg1|key1"])
    }

    @Test
    fun `togglePartExpand handles multiple keys independently`() {
        controller.togglePartExpand("a|1", false)
        controller.togglePartExpand("b|2", false)
        controller.togglePartExpand("a|1", true)

        assertEquals(false, expandedParts.value["a|1"])
        assertEquals(true, expandedParts.value["b|2"])
    }

    // ── clearExpandedParts ──────────────────────────────────────────────────

    @Test
    fun `clearExpandedParts empties all expansion state`() {
        controller.togglePartExpand("a", false)
        controller.togglePartExpand("b", false)
        assertEquals(2, expandedParts.value.size)

        controller.clearExpandedParts()

        assertTrue(expandedParts.value.isEmpty())
    }

    // ── ComposerState isolation ─────────────────────────────────────────────

    @Test
    fun `setInputText does not affect sendingSessionIds`() {
        store.mutateComposer { it.copy(sendingSessionIds = setOf("s1")) }

        controller.setInputText("new text")

        assertEquals(setOf("s1"), composerFlow.value.sendingSessionIds)
    }
}
