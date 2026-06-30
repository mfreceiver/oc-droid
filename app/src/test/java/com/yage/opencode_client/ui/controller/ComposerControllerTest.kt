package com.yage.opencode_client.ui.controller

import com.yage.opencode_client.data.model.ComposerImageAttachment
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.ChatState
import com.yage.opencode_client.ui.ComposerState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-16 M2: independent unit test for [ComposerController].
 *
 * Zero reflection — the controller is driven entirely through its public API
 * (setInputText / addImageAttachments / removeImageAttachment /
 * clearDraftIfActive / togglePartExpand / clearExpandedParts) and asserted
 * via the [RecordingComposerCallbacks] spy + direct flow reads. Follows the
 * [ForegroundCatchUpControllerTest] pattern from M1.
 *
 * R-17 M5: the controller reads currentSessionId from [chatFlow] (slice) but
 * still mirrors composer writes onto [appState] (the free helpers read
 * `state.value.draftWorkdir`), so both are exercised.
 */
class ComposerControllerTest {

    private lateinit var appState: MutableStateFlow<AppState>
    private lateinit var chatFlow: MutableStateFlow<ChatState>
    private lateinit var composerFlow: MutableStateFlow<ComposerState>
    private lateinit var expandedParts: MutableStateFlow<Map<String, Boolean>>
    private lateinit var callbacks: RecordingComposerCallbacks
    private lateinit var controller: ComposerController

    @Before
    fun setUp() {
        appState = MutableStateFlow(AppState())
        chatFlow = MutableStateFlow(ChatState())
        composerFlow = MutableStateFlow(ComposerState())
        expandedParts = MutableStateFlow(emptyMap())
        callbacks = RecordingComposerCallbacks()
        controller = ComposerController(
            state = appState,
            composerFlow = composerFlow,
            chatFlow = chatFlow,
            expandedParts = expandedParts,
            callbacks = callbacks
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
        chatFlow.value = chatFlow.value.copy(currentSessionId = "s1")

        controller.setInputText("draft text")

        assertEquals(1, callbacks.saveDraftCalls.size)
        assertEquals("s1", callbacks.saveDraftCalls.single().first)
        assertEquals("draft text", callbacks.saveDraftCalls.single().second)
    }

    @Test
    fun `setInputText does not save draft when no session is active`() {
        chatFlow.value = chatFlow.value.copy(currentSessionId = null)

        controller.setInputText("no session")

        assertEquals("no session", composerFlow.value.inputText)
        assertTrue("no draft saved when session is null", callbacks.saveDraftCalls.isEmpty())
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
        composerFlow.value = composerFlow.value.copy(draftWorkdir = "/tmp/proj")
        chatFlow.value = chatFlow.value.copy(currentSessionId = null)
        controller.setInputText("draft content")

        controller.clearDraftIfActive()

        assertEquals(null, composerFlow.value.draftWorkdir)
        assertEquals("", composerFlow.value.inputText)
        assertTrue(composerFlow.value.imageAttachments.isEmpty())
        assertEquals(1, callbacks.clearPersistedWorkdirCalls)
    }

    @Test
    fun `clearDraftIfActive is no-op when draftWorkdir is null`() {
        composerFlow.value = composerFlow.value.copy(draftWorkdir = null)
        chatFlow.value = chatFlow.value.copy(currentSessionId = null)
        controller.setInputText("not a draft")

        controller.clearDraftIfActive()

        // Input text preserved — this is NOT a draft session
        assertEquals("not a draft", composerFlow.value.inputText)
        assertEquals(0, callbacks.clearPersistedWorkdirCalls)
    }

    @Test
    fun `clearDraftIfActive is no-op when session is active (real session)`() {
        composerFlow.value = composerFlow.value.copy(draftWorkdir = "/tmp/proj")
        chatFlow.value = chatFlow.value.copy(currentSessionId = "s1")

        controller.clearDraftIfActive()

        // Should NOT clear — currentSessionId != null means the draft has materialised
        assertEquals("/tmp/proj", composerFlow.value.draftWorkdir)
        assertEquals(0, callbacks.clearPersistedWorkdirCalls)
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
        composerFlow.value = composerFlow.value.copy(sendingSessionIds = setOf("s1"))

        controller.setInputText("new text")

        assertEquals(setOf("s1"), composerFlow.value.sendingSessionIds)
    }

    // ── RecordingComposerCallbacks ──────────────────────────────────────────

    private class RecordingComposerCallbacks : ComposerCallbacks {
        val saveDraftCalls = mutableListOf<Pair<String, String>>()
        var clearPersistedWorkdirCalls = 0

        override fun saveDraft(sessionId: String, text: String) {
            saveDraftCalls.add(sessionId to text)
        }

        override fun clearPersistedWorkdir() {
            clearPersistedWorkdirCalls++
        }
    }
}
