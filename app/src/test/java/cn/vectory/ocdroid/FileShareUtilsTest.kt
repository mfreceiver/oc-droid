package cn.vectory.ocdroid

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import cn.vectory.ocdroid.data.model.FileContent
import cn.vectory.ocdroid.ui.files.shareFileContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * §F5-ZLM (C4): Tests for [shareFileContent] — main-thread file write offload.
 *
 * Runs under Robolectric so [Context.cacheDir] returns a real temp directory
 * and [Context.startActivity] is intercepted by Robolectric's shadow without
 * throwing "not mocked". The same pattern ([ApplicationProvider.getApplicationContext],
 * [@Config](sdk = [34])) is used across the project (SettingsManagerTest,
 * CrashLoggerTest, etc.).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [TestFileProviderShadow::class])
@OptIn(ExperimentalCoroutinesApi::class)
class FileShareUtilsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Use Unconfined for Dispatchers.Main so withContext(Dispatchers.Main) inside
        // shareFileContent runs inline rather than posting to the main looper.
        // runBlocking blocks the test thread, so the main looper can never pump
        // posted messages — without this override, withContext(Dispatchers.Main) deadlocks.
        Dispatchers.setMain(Dispatchers.Unconfined)
        // Wipe residual state from the shared dir so each test starts clean.
        File(context.cacheDir, "shared").let {
            if (it.exists()) it.deleteRecursively()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        File(context.cacheDir, "shared").let {
            if (it.exists()) it.deleteRecursively()
        }
    }

    @Test
    fun `shareFileContent writes text file to cacheDir shared dir`() = runBlocking {
        shareFileContent(
            context = context,
            path = "notes/test.md",
            content = FileContent(type = "text", content = "# Hello\nWorld")
        )

        val writtenFile = File(context.cacheDir, "shared/test.md")
        assertTrue("Expected file to exist: ${writtenFile.absolutePath}", writtenFile.exists())
        assertEquals("# Hello\nWorld", writtenFile.readText(Charsets.UTF_8))
    }

    @Test
    fun `shareFileContent writes binary file from base64`() = runBlocking {
        val rawBytes = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()) // PNG header
        val base64 = Base64.encodeToString(rawBytes, Base64.DEFAULT)

        shareFileContent(
            context = context,
            path = "images/photo.png",
            content = FileContent(type = "binary", content = base64)
        )

        val writtenFile = File(context.cacheDir, "shared/photo.png")
        assertTrue("Expected binary file to exist: ${writtenFile.absolutePath}", writtenFile.exists())
        assertArrayEquals(
            "Decoded bytes should match",
            rawBytes,
            writtenFile.readBytes()
        )
    }

    @Test
    fun `shareFileContent propagates IO failure when write fails`() = runBlocking {
        // Pre-create "shared" as a regular file instead of a directory.
        // mkdirs() will return false without throwing; writeBytes will then
        // fail because the parent of the target path is not a directory.
        val sharedFile = File(context.cacheDir, "shared")
        sharedFile.createNewFile()

        var caught: Exception? = null
        try {
            shareFileContent(
                context = context,
                path = "test.txt",
                content = FileContent(type = "text", content = "hello")
            )
        } catch (e: Exception) {
            caught = e
        }

        assertTrue(
            "Expected IOException to propagate, got ${caught?.javaClass?.simpleName}: ${caught?.message}",
            caught is IOException
        )
    }

    @Test
    fun `shareFileContent skips when content is null`() = runBlocking {
        // Should not throw — early return on null content.
        shareFileContent(
            context = context,
            path = "test.txt",
            content = FileContent(type = "text", content = null)
        )
        // No file should have been written.
        assertFalse("Expected no file written when content is null", File(context.cacheDir, "shared/test.txt").exists())
    }

    @Test
    fun `shareFileContent writes empty file for empty string content`() = runBlocking {
        shareFileContent(
            context = context,
            path = "empty.md",
            content = FileContent(type = "text", content = "")
        )

        val writtenFile = File(context.cacheDir, "shared/empty.md")
        assertTrue("Expected empty file to exist", writtenFile.exists())
        assertEquals("Expected empty file content", "", writtenFile.readText())
    }

    @Test
    fun `shareFileContent writes file and completes without exception`() = runBlocking {
        // Under Robolectric, startActivity on the Application context is
        // intercepted by shadows and does not throw. The key assertion is
        // "no crash + file written" — intent introspection via shadows is
        // not used elsewhere in this project's test suite.
        shareFileContent(
            context = context,
            path = "echo.txt",
            content = FileContent(type = "text", content = "alive")
        )

        val writtenFile = File(context.cacheDir, "shared/echo.txt")
        assertTrue("File should be written when no error occurs", writtenFile.exists())
        assertEquals("alive", writtenFile.readText())
    }
}
