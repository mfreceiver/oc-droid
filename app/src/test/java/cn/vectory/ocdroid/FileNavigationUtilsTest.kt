package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.FileNode
import cn.vectory.ocdroid.ui.files.buildDirectoryPreviewContent
import cn.vectory.ocdroid.ui.files.resolveRelativePreviewPath
import org.junit.Assert.assertEquals
import org.junit.Test

class FileNavigationUtilsTest {

    @Test
    fun `resolveRelativePreviewPath strips session directory prefix`() {
        assertEquals(
            "src/Main.kt",
            resolveRelativePreviewPath(
                pathToShow = "/workspace/session/src/Main.kt",
                sessionDirectory = "/workspace/session"
            )
        )
    }

    @Test
    fun `resolveRelativePreviewPath strips session directory prefix when absolute paths lost leading slash`() {
        assertEquals(
            "contexts/thought_review/context_operating_model_high_level_spec_20260323.md",
            resolveRelativePreviewPath(
                pathToShow = "Users/grapeot/co/knowledge_working/contexts/thought_review/context_operating_model_high_level_spec_20260323.md",
                sessionDirectory = "/Users/grapeot/co/knowledge_working"
            )
        )
    }

    @Test
    fun `resolveRelativePreviewPath keeps full path when session prefix does not match`() {
        assertEquals(
            "workspace/other/src/Main.kt",
            resolveRelativePreviewPath(
                pathToShow = "/workspace/other/src/Main.kt",
                sessionDirectory = "/workspace/session"
            )
        )
    }

    @Test
    fun `buildDirectoryPreviewContent renders file listing`() {
        val tree = listOf(
            FileNode(name = "src", path = "src", type = "directory"),
            FileNode(name = "Main.kt", path = "src/Main.kt", type = "file")
        )

        assertEquals(
            "Directory:\nsrc\nsrc/Main.kt",
            buildDirectoryPreviewContent("src", tree)
        )
    }

    @Test
    fun `buildDirectoryPreviewContent handles empty directory`() {
        assertEquals(
            "Directory (empty or path not found): src",
            buildDirectoryPreviewContent("src", emptyList())
        )
    }
}
