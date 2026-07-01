package cn.vectory.ocdroid

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies configuration required for InputBar keyboard/IME insets.
 * When using physical keyboard, a small IME bar may appear at the bottom;
 * imePadding() ensures the input bar stays visible above it.
 * Requires windowSoftInputMode="adjustResize" in AndroidManifest.
 */
class InputBarInsetsTest {

    @Test
    fun `AndroidManifest has adjustResize for IME keyboard insets`() {
        val manifest = (
            File("app/src/main/AndroidManifest.xml").takeIf { it.exists() }
                ?: File("src/main/AndroidManifest.xml")
        ).readText()
        assertTrue(
            "MainActivity must have windowSoftInputMode=adjustResize for imePadding() to work",
            manifest.contains("android:windowSoftInputMode=\"adjustResize\"")
        )
    }
}
