package cn.vectory.ocdroid

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.io.File

/**
 * Robolectric shadow that replaces [FileProvider.getUriForFile] so tests
 * don't depend on a real file_paths.xml provider registration under the
 * Robolectric temp directory.
 *
 * Returns a synthetic `content://` URI — sufficient to verify that
 * [cn.vectory.ocdroid.ui.files.shareFileContent] writes the file and
 * proceeds to [android.content.Intent.createChooser] / [startActivity]
 * without an [IllegalArgumentException].
 */
@Implements(FileProvider::class)
class TestFileProviderShadow {

    companion object {
        @JvmStatic
        @Implementation
        fun getUriForFile(context: Context, authority: String, file: File): Uri {
            return Uri.parse("content://$authority/${file.name}")
        }
    }
}
