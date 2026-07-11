package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.repository.HostProfileStore
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
    private lateinit var hostProfileStore: HostProfileStore
    private lateinit var controller: ComposerController

    @Before
    fun setUp() {
        store = SharedStateStore()
        chatFlow = store.chatFlow
        composerFlow = store.composerFlow
        expandedParts = store.expandedParts
        settingsManager = mockk(relaxed = true)
        hostProfileStore = mockk(relaxed = true)
        // R-20 Phase 5: ComposerController derives the current serverGroupFp
        // from the host profile; stub it so the draft-write path resolves.
        val profile = HostProfile.defaultDirect("http://test")
        every { hostProfileStore.currentProfile() } returns profile
        controller = ComposerController(
            store = store,
            settingsManager = settingsManager,
            hostProfileStore = hostProfileStore,
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

        verify { settingsManager.setDraftText(any(), "s1", "draft text") }
    }

    @Test
    fun `setInputText does not save draft when no session is active`() {
        store.mutateChat { it.copy(currentSessionId = null) }

        controller.setInputText("no session")

        assertEquals("no session", composerFlow.value.inputText)
        verify(exactly = 0) { settingsManager.setDraftText(any(), any(), any()) }
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
        // §1B-FIX (I4): clearDraftIfActive only strips `File: <path>`
        // lines (and clears fileReferences / imageAttachments); the
        // user's plain text is left intact so the next draft session
        // can pick up where the user left off.
        assertEquals("draft content", composerFlow.value.inputText)
        assertTrue(composerFlow.value.imageAttachments.isEmpty())
        assertTrue(composerFlow.value.fileReferences.isEmpty())
        // §batch 3b: clearPersistedWorkdir is now an inline SettingsManager
        // currentWorkdir=null write (rule A) — verify the mock received it.
        verify { settingsManager.currentWorkdir = null }
    }

    @Test
    fun `clearDraftIfActive strips File lines but preserves plain text`() {
        // §1B-FIX (I4): chip strip + text strip in the same call — the
        // user's plain text is kept; any `File: <path>` lines are
        // dropped because the fileReferences list is wiped.
        store.mutateComposer {
            it.copy(
                draftWorkdir = "/tmp/proj",
                inputText = "user typed this\nFile: /a/b.kt\nand more",
                fileReferences = listOf(cn.vectory.ocdroid.ui.ComposerFileReference("/a/b.kt")),
            )
        }
        store.mutateChat { it.copy(currentSessionId = null) }

        controller.clearDraftIfActive()

        assertEquals(null, composerFlow.value.draftWorkdir)
        // The File: line is stripped, the rest of the text is preserved.
        assertEquals("user typed this\nand more", composerFlow.value.inputText)
        assertTrue(composerFlow.value.fileReferences.isEmpty())
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

    // ── File references (Phase 1B / F.4) ──────────────────────────────────
    // §1B (F.4): additive ComposerState.fileReferences. addFileReference
    // appends a ComposerFileReference + a "File: <path>" line to inputText;
    // removeFileReference strips the entry + the matching line. Both are
    // pure additions — no other writer is touched.

    @Test
    fun `addFileReference appends to fileReferences and inputText`() {
        controller.setInputText("hello")

        controller.addFileReference("/tmp/proj/foo.kt")

        assertEquals(1, composerFlow.value.fileReferences.size)
        assertEquals("/tmp/proj/foo.kt", composerFlow.value.fileReferences[0].path)
        // Scheme A: the literal `File: <path>` is appended as a new line.
        assertEquals("hello\nFile: /tmp/proj/foo.kt", composerFlow.value.inputText)
    }

    @Test
    fun `addFileReference with empty inputText seeds a single line`() {
        controller.addFileReference("/a/b/c.txt")

        assertEquals("File: /a/b/c.txt", composerFlow.value.inputText)
    }

    @Test
    fun `addFileReference is a no-op for blank paths`() {
        controller.addFileReference("")
        controller.addFileReference("   ")

        assertTrue(composerFlow.value.fileReferences.isEmpty())
        assertEquals("", composerFlow.value.inputText)
    }

    @Test
    fun `addFileReference does not duplicate an existing path`() {
        controller.addFileReference("/a/b.kt")
        controller.addFileReference("/a/b.kt")

        assertEquals(1, composerFlow.value.fileReferences.size)
    }

    @Test
    fun `addFileReference dedups against hand-typed File lines (I4)`() {
        // §1B-FIX (I4): if the user has manually typed a `File: <path>`
        // line in the composer text, calling addFileReference with the
        // same path must not create a duplicate (text-line or chip).
        controller.setInputText("prelude\nFile: /a/b.kt\npostlude")

        controller.addFileReference("/a/b.kt")

        // chip count stays 1
        assertEquals(1, composerFlow.value.fileReferences.size)
        // text contains exactly one `File: /a/b.kt` line
        val fileCount = composerFlow.value.inputText
            .split('\n')
            .count { it.trimStart() == "File: /a/b.kt" }
        assertEquals(1, fileCount)
    }

    @Test
    fun `addFileReference only adds chip when text line already present (I4)`() {
        // Edge case: the user has the text line but no chip — adding
        // the reference should create a chip WITHOUT re-appending the
        // text line (no duplicate).
        controller.setInputText("File: /x/y.kt")

        controller.addFileReference("/x/y.kt")

        assertEquals(1, composerFlow.value.fileReferences.size)
        assertEquals("File: /x/y.kt", composerFlow.value.inputText)
    }

    @Test
    fun `addFileReference only adds text line when chip already present (I4)`() {
        // Edge case: the user has a chip but somehow the text line is
        // missing — adding the reference should append the text line
        // (mirroring the chip). In practice the chip + text are always
        // written together, so this is a defensive symmetry.
        store.mutateComposer {
            it.copy(
                fileReferences = listOf(cn.vectory.ocdroid.ui.ComposerFileReference("/x/y.kt")),
                inputText = "",
            )
        }

        controller.addFileReference("/x/y.kt")

        assertEquals(1, composerFlow.value.fileReferences.size)
        assertEquals("File: /x/y.kt", composerFlow.value.inputText)
    }

    @Test
    fun `removeFileReference strips all matching File lines including duplicates (I4)`() {
        // §1B-FIX (I4): if the user typed the same `File: <path>` line
        // twice, removing the chip must strip BOTH lines (consistent
        // with the chip-removal intent: the user no longer wants this
        // file referenced).
        store.mutateComposer {
            it.copy(
                inputText = "User text\nFile: /x/y.kt\nMore text\nFile: /x/y.kt",
                fileReferences = listOf(
                    cn.vectory.ocdroid.ui.ComposerFileReference("/x/y.kt")
                ),
            )
        }

        controller.removeFileReference(composerFlow.value.fileReferences[0].id)

        // All matching `File:` lines are stripped; other content is
        // preserved.
        assertEquals("User text\nMore text", composerFlow.value.inputText)
        assertTrue(composerFlow.value.fileReferences.isEmpty())
    }

    @Test
    fun `addFileReference appends after a trailing newline without smushing`() {
        controller.setInputText("line1\n")

        controller.addFileReference("/a/b.kt")

        assertEquals("line1\nFile: /a/b.kt", composerFlow.value.inputText)
    }

    @Test
    fun `removeFileReference strips the matching path and its inputText line`() {
        controller.addFileReference("/a/b.kt")
        controller.addFileReference("/c/d.kt")
        val firstId = composerFlow.value.fileReferences[0].id
        val secondId = composerFlow.value.fileReferences[1].id

        controller.removeFileReference(firstId)

        assertEquals(1, composerFlow.value.fileReferences.size)
        assertEquals("/c/d.kt", composerFlow.value.fileReferences[0].path)
        // Scheme A: the matching "File: <path>" line is removed from
        // inputText as well — the outgoing prompt must not still
        // reference the removed file.
        assertEquals("File: /c/d.kt", composerFlow.value.inputText)
        // secondId untouched
        assertEquals(secondId, composerFlow.value.fileReferences[0].id)
    }

    @Test
    fun `removeFileReference is a no-op for unknown id`() {
        controller.addFileReference("/a/b.kt")

        controller.removeFileReference("non-existent-id")

        assertEquals(1, composerFlow.value.fileReferences.size)
        assertEquals("File: /a/b.kt", composerFlow.value.inputText)
    }

    @Test
    fun `removeFileReference is a no-op on empty list`() {
        controller.removeFileReference("missing")

        assertTrue(composerFlow.value.fileReferences.isEmpty())
        assertEquals("", composerFlow.value.inputText)
    }

    @Test
    fun `addFileReference persists draft for active session`() {
        store.mutateChat { it.copy(currentSessionId = "s1") }

        controller.addFileReference("/a/b.kt")

        verify { settingsManager.setDraftText(any(), "s1", "File: /a/b.kt") }
    }

    @Test
    fun `addFileReference then removeFileReference round-trips to empty state`() {
        controller.addFileReference("/a/b.kt")
        val id = composerFlow.value.fileReferences[0].id

        controller.removeFileReference(id)

        assertTrue(composerFlow.value.fileReferences.isEmpty())
        assertEquals("", composerFlow.value.inputText)
    }
}
