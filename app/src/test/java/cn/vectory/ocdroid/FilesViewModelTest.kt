package cn.vectory.ocdroid

import android.content.Context
import cn.vectory.ocdroid.data.model.FileContent
import cn.vectory.ocdroid.data.model.FileNode
import cn.vectory.ocdroid.data.model.FileStatusEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.files.FilesViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: OpenCodeRepository
    // §F5: FilesViewModel 现注入 Application Context（长按分享用）；测试用 relaxed mock。
    private val context: Context = mockk(relaxed = true)

    private val workdir = "workspace"

    @Before
    fun setUp() {
        repository = mockk(relaxed = true)

        // §R-17 batch4: file API signatures now take an explicit `directory`
        // parameter as their first argument. Relaxed mock defaults return
        // empty results; per-test stubs override specific (directory, path)
        // pairs when the test asserts on the returned values.
        coEvery { repository.getFileTree(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getFileStatus(any()) } returns Result.success(emptyList())
        coEvery { repository.getFileContent(any(), any()) } returns Result.success(
            FileContent(type = "text", content = "file body")
        )
    }

    @Test
    fun `refresh loads root files and statuses`() = runTest {
        val files = listOf(
            FileNode(name = "src", path = "src", type = "directory"),
            FileNode(name = "README.md", path = "README.md", type = "file")
        )
        val statuses = listOf(
            FileStatusEntry(path = "README.md", status = "modified"),
            FileStatusEntry(path = "new.txt", status = null)
        )
        coEvery { repository.getFileTree(workdir, null) } returns Result.success(files)
        coEvery { repository.getFileStatus(workdir) } returns Result.success(statuses)

        val viewModel = FilesViewModel(repository, context)
        viewModel.bindWorkdir(workdir)
        advanceUntilIdle()

        assertEquals(files, viewModel.state.value.files)
        assertEquals(
            mapOf("README.md" to "modified", "new.txt" to "untracked"),
            viewModel.state.value.fileStatuses
        )
        assertEquals("", viewModel.state.value.currentPath)
        assertEquals(workdir, viewModel.state.value.workdir)
        coVerify { repository.getFileTree(workdir, null) }
        coVerify { repository.getFileStatus(workdir) }
    }

    @Test
    fun `selectFile on directory updates path and loads tree`() = runTest {
        coEvery { repository.getFileTree(workdir, null) } returns Result.success(emptyList())
        coEvery { repository.getFileTree(workdir, "src") } returns Result.success(
            listOf(FileNode(name = "Main.kt", path = "src/Main.kt", type = "file"))
        )

        val viewModel = FilesViewModel(repository, context)
        viewModel.bindWorkdir(workdir)
        advanceUntilIdle()

        viewModel.selectFile(FileNode(name = "src", path = "src", type = "directory"))
        advanceUntilIdle()

        assertEquals("src", viewModel.state.value.currentPath)
        assertEquals(listOf(FileNode(name = "Main.kt", path = "src/Main.kt", type = "file")), viewModel.state.value.files)
        coVerify { repository.getFileTree(workdir, "src") }
    }

    @Test
    fun `selectFile on file loads preview and invokes callback`() = runTest {
        val callbackPaths = mutableListOf<String>()
        val content = FileContent(type = "text", content = "hello")
        coEvery { repository.getFileTree(workdir, null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent(workdir, "src/Main.kt") } returns Result.success(content)

        val viewModel = FilesViewModel(repository, context)
        viewModel.bindWorkdir(workdir)
        advanceUntilIdle()

        viewModel.selectFile(
            FileNode(name = "Main.kt", path = "src/Main.kt", type = "file"),
            onFileClick = { callbackPaths += it }
        )
        advanceUntilIdle()

        assertEquals(listOf("src/Main.kt"), callbackPaths)
        assertEquals("src/Main.kt", viewModel.state.value.selectedFilePath)
        assertEquals(content, viewModel.state.value.selectedFileContent)
    }

    @Test
    fun `syncPathToShow loads relative file preview when session directory matches`() = runTest {
        val content = FileContent(type = "text", content = "preview")
        coEvery { repository.getFileTree(workdir, null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent(workdir, "src/Main.kt") } returns Result.success(content)

        val viewModel = FilesViewModel(repository, context)
        advanceUntilIdle()

        viewModel.syncPathToShow("workspace/src/Main.kt", workdir)
        advanceUntilIdle()

        coVerify { repository.getFileContent(workdir, "src/Main.kt") }
        assertEquals("workspace/src/Main.kt", viewModel.state.value.selectedFilePath)
        assertEquals(content, viewModel.state.value.selectedFileContent)
    }

    @Test
    fun `syncPathToShow shows empty text file as a file, not as a directory`() = runTest {
        // §R18 P1-5 regression: an empty text file legitimately returns
        // content == "" from getFileContent. The prior logic treated this as
        // "blank → must be a directory" and swapped in a tree preview; the new
        // logic discriminates by FileContent.type, so the empty file is shown
        // as-is and getFileTree is NOT called for it.
        coEvery { repository.getFileTree(workdir, null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent(workdir, "empty.txt") } returns Result.success(
            FileContent(type = "text", content = "")
        )

        val viewModel = FilesViewModel(repository, context)
        advanceUntilIdle()

        viewModel.syncPathToShow("workspace/empty.txt", workdir)
        advanceUntilIdle()

        assertEquals("workspace/empty.txt", viewModel.state.value.selectedFilePath)
        assertEquals("", viewModel.state.value.selectedFileContent?.content)
        assertEquals("text", viewModel.state.value.selectedFileContent?.type)
        coVerify(exactly = 0) { repository.getFileTree(workdir, "empty.txt") }
    }

    @Test
    fun `syncPathToShow falls back to directory preview when getFileContent fails`() = runTest {
        // §R18 P1-5: when the deep-link target is actually a directory, the
        // single-shot probe sees getFileContent fail and then resolves the
        // tree. (Server's getFileContent on a directory path returns an error,
        // which is the natural "not a file" signal — no double request.)
        val tree = listOf(
            FileNode(name = "Main.kt", path = "src/Main.kt", type = "file"),
            FileNode(name = "util", path = "src/util", type = "directory")
        )
        coEvery { repository.getFileTree(workdir, null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent(workdir, "src") } returns Result.failure(
            java.io.IOException("is a directory")
        )
        coEvery { repository.getFileTree(workdir, "src") } returns Result.success(tree)

        val viewModel = FilesViewModel(repository, context)
        advanceUntilIdle()

        viewModel.syncPathToShow("workspace/src", workdir)
        advanceUntilIdle()

        assertEquals("workspace/src", viewModel.state.value.selectedFilePath)
        assertEquals(
            "Directory:\n" + tree.joinToString("\n") { it.path },
            viewModel.state.value.selectedFileContent?.content
        )
    }

    @Test
    fun `syncPathToShow null clears preview state`() = runTest {
        coEvery { repository.getFileTree(workdir, null) } returns Result.success(emptyList())

        val viewModel = FilesViewModel(repository, context)
        viewModel.bindWorkdir(workdir)
        advanceUntilIdle()

        viewModel.selectFile(FileNode(name = "Main.kt", path = "src/Main.kt", type = "file"))
        advanceUntilIdle()
        assertEquals("src/Main.kt", viewModel.state.value.selectedFilePath)

        viewModel.syncPathToShow(null, workdir)

        assertNull(viewModel.state.value.selectedFilePath)
        assertNull(viewModel.state.value.selectedFileContent)
        assertTrue(viewModel.state.value.error == null)
    }

    @Test
    fun `refreshPreview reloads selected file content`() = runTest {
        coEvery { repository.getFileTree(workdir, null) } returns Result.success(emptyList())
        coEvery { repository.getFileContent(workdir, "src/Main.kt") } returnsMany listOf(
            Result.success(FileContent(type = "text", content = "before")),
            Result.success(FileContent(type = "text", content = "after"))
        )

        val viewModel = FilesViewModel(repository, context)
        viewModel.bindWorkdir(workdir)
        advanceUntilIdle()

        viewModel.selectFile(FileNode(name = "Main.kt", path = "src/Main.kt", type = "file"))
        advanceUntilIdle()
        assertEquals("before", viewModel.state.value.selectedFileContent?.content)

        viewModel.refreshPreview(sessionDirectory = workdir)
        advanceUntilIdle()

        assertEquals("src/Main.kt", viewModel.state.value.selectedFilePath)
        assertEquals("after", viewModel.state.value.selectedFileContent?.content)
        assertEquals(false, viewModel.state.value.isPreviewRefreshing)
        coVerify(exactly = 2) { repository.getFileContent(workdir, "src/Main.kt") }
    }

    @Test
    fun `refreshPreview reloads directory preview using relative session path`() = runTest {
        val initialTree = listOf(FileNode(name = "Old.kt", path = "src/Old.kt", type = "file"))
        val refreshedTree = listOf(FileNode(name = "New.kt", path = "src/New.kt", type = "file"))
        coEvery { repository.getFileTree(workdir, null) } returns Result.success(emptyList())
        // §R18 P1-5: "src" is a directory — getFileContent fails for it (the
        // server's natural "not a file" signal), so the probe falls back to
        // getFileTree. (Previously stubbed as empty content; that now reads as
        // a legitimately-empty text file instead of a directory.)
        coEvery { repository.getFileContent(workdir, "src") } returns Result.failure(
            java.io.IOException("is a directory")
        )
        coEvery { repository.getFileTree(workdir, "src") } returnsMany listOf(
            Result.success(initialTree),
            Result.success(refreshedTree)
        )

        val viewModel = FilesViewModel(repository, context)
        advanceUntilIdle()

        viewModel.syncPathToShow("workspace/src", workdir)
        advanceUntilIdle()
        assertEquals("Directory:\nsrc/Old.kt", viewModel.state.value.selectedFileContent?.content)

        viewModel.refreshPreview(sessionDirectory = workdir)
        advanceUntilIdle()

        assertEquals("workspace/src", viewModel.state.value.selectedFilePath)
        assertEquals("Directory:\nsrc/New.kt", viewModel.state.value.selectedFileContent?.content)
        assertEquals(false, viewModel.state.value.isPreviewRefreshing)
        coVerify(exactly = 2) { repository.getFileContent(workdir, "src") }
        coVerify(exactly = 2) { repository.getFileTree(workdir, "src") }
    }

    /**
     * §R-17 batch4: switching the workdir mid-flight bumps the generation
     * counter; the in-flight load for the OLD workdir must NOT overwrite the
     * state set by the NEW workdir's load. We model this by stubbing the old
     * workdir's response with a distinguishable file list and asserting it
     * never appears in the state — only the new workdir's tree wins.
     */
    @Test
    fun `bindWorkdir invalidates stale loads via generation guard`() = runTest {
        val staleTree = listOf(FileNode(name = "STALE.kt", path = "STALE.kt", type = "file"))
        val freshTree = listOf(FileNode(name = "FRESH.kt", path = "FRESH.kt", type = "file"))
        coEvery { repository.getFileTree("old", null) } returns Result.success(staleTree)
        coEvery { repository.getFileTree("new", null) } returns Result.success(freshTree)

        val viewModel = FilesViewModel(repository, context)
        viewModel.bindWorkdir("old")
        // Switch BEFORE the old load resolves — old response must be discarded.
        viewModel.bindWorkdir("new")
        advanceUntilIdle()

        assertEquals("new", viewModel.state.value.workdir)
        assertEquals(freshTree, viewModel.state.value.files)
    }

    /**
     * §R-17 batch4: when no workdir is bound, refresh() must NOT call into
     * the repository (it would have no directory to scope the call to).
     */
    @Test
    fun `refresh without workdir skips repository calls`() = runTest {
        val viewModel = FilesViewModel(repository, context)
        viewModel.refresh()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getFileTree(any(), any()) }
        coVerify(exactly = 0) { repository.getFileStatus(any()) }
        assertEquals(null, viewModel.state.value.workdir)
    }

    /**
     * §R18 P1-6 regression: navigateUp must compute the parent from a
     * trailing-slash-stripped path. "a/b/".substringBeforeLast("/") returns
     * "a/b" (the trailing slash is the last match), so the user was stuck.
     * After normalization the parent of "src/util/" must be "src". We set up
     * the trailing slash by selecting a directory whose server-returned path
     * carries one — no test-only production helper needed.
     */
    @Test
    fun `navigateUp normalizes trailing slash before computing parent`() = runTest {
        coEvery { repository.getFileTree(workdir, any()) } returns Result.success(emptyList())

        val viewModel = FilesViewModel(repository, context)
        viewModel.bindWorkdir(workdir)
        advanceUntilIdle()

        // Server returned a directory node whose path carries a trailing slash;
        // selectFile stores it verbatim into currentPath.
        viewModel.selectFile(FileNode(name = "util", path = "src/util/", type = "directory"))
        advanceUntilIdle()
        assertEquals("src/util/", viewModel.state.value.currentPath)

        viewModel.navigateUp()
        advanceUntilIdle()

        assertEquals("src", viewModel.state.value.currentPath)
        coVerify { repository.getFileTree(workdir, "src") }
    }

    @Test
    fun `navigateUp is a no-op at root`() = runTest {
        coEvery { repository.getFileTree(workdir, any()) } returns Result.success(emptyList())

        val viewModel = FilesViewModel(repository, context)
        viewModel.bindWorkdir(workdir)
        advanceUntilIdle()
        // currentPath is "" at root.
        viewModel.navigateUp()
        advanceUntilIdle()

        assertEquals("", viewModel.state.value.currentPath)
        // navigateUp at root issues NO additional getFileTree call beyond the
        // initial bindWorkdir load (which used null path).
        coVerify(exactly = 0) { repository.getFileTree(workdir, "") }
    }

    /**
     * §R18 P1-6 fix regression (maxer+glmer): a top-level child whose path
     * contains no '/' (e.g. "src") must navigateUp back to "" (the workdir
     * root). The prior guard `substringBeforeLast('/', "").isEmpty()`
     * returned "" for any '/'-less path (the delimiter was absent, so
     * substringBeforeLast returned its default ""), misclassifying every
     * first-level child as root and blocking the child → root transition.
     * Only a truly-empty currentPath is a no-op.
     */
    @Test
    fun `navigateUp from first-level child returns to root`() = runTest {
        coEvery { repository.getFileTree(workdir, any()) } returns Result.success(emptyList())

        val viewModel = FilesViewModel(repository, context)
        viewModel.bindWorkdir(workdir)
        advanceUntilIdle()

        // selectFile on a directory stores file.path verbatim into currentPath
        // (see FilesViewModel.selectFile), so a "src" directory node puts us
        // at a '/'-less first-level path — exactly the case the bug blocked.
        viewModel.selectFile(FileNode(name = "src", path = "src", type = "directory"))
        advanceUntilIdle()
        assertEquals("src", viewModel.state.value.currentPath)

        viewModel.navigateUp()
        advanceUntilIdle()

        assertEquals("", viewModel.state.value.currentPath)
        // loadFiles routes an empty path through `path.ifEmpty { null }`, so
        // the root reload arrives as getFileTree(workdir, null) — once from
        // the initial bindWorkdir and once from this navigateUp. Asserting
        // exactly 2 confirms navigateUp actually triggered a root load rather
        // than no-op'ing.
        coVerify(exactly = 2) { repository.getFileTree(workdir, null) }
    }
}
