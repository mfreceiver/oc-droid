package cn.vectory.ocdroid.ui.util

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Logcat tag. Preserved as the historical shared tag `"DataUriImage"` so logcat
 * filtering for data-URI decode messages is unchanged after the split.
 */
private const val TAG = "DataUriImage"

private val dataUriPattern = Regex("""^data:([^;]+);base64,(.+)$""", RegexOption.DOT_MATCHES_ALL)

/**
 * §16.3: file-level LRU bitmap cache for decoded data-URI images.
 *
 * The decode path runs in a non-Composable code path (inside the
 * `@Composable transform()`), so we cannot use `remember` alone — a process-
 * level LruCache survives recomposition AND is shared across distinct
 * Composable instances (so the same base64 image is not re-decoded on every
 * re-render). R-15: the key is the full data-URI string (String keys are
 * cheap references — no per-entry copy of the base64 payload), which also
 * avoids the hashCode() collision risk between distinct images.
 *
 * Sized at 16 MB; [sizeOf] reports [Bitmap.byteCount] so eviction is
 * byte-accurate rather than entry-count-based.
 *
 * §评审 Stage C #6: the chat attachment renderer [ImageFilePart] now calls
 * [DataUriImageTransformer.transform] (which reads this cache) instead of
 * decoding independently, so the same data-URI is decoded at most once across
 * the whole chat surface (inline markdown + standalone attachment cards).
 */
private val dataUriBitmapCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

// MikePenz defaults to NoOpImageTransformerImpl (returns null), so images show
// only a placeholder. This handles data:image/...;base64,... and HTTPS URLs.
object DataUriImageTransformer : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        val dataMatch = dataUriPattern.matchEntire(link)
        if (dataMatch != null) {
            val mimeType = dataMatch.groupValues[1]
            if (!mimeType.startsWith("image/")) return null
            // R-15: cross-instance sharing keyed by the full data-URI string.
            // String keys are references, so this shares the caller's existing
            // String instance with no extra copy cost; using the full link
            // (instead of hashCode()) avoids collision-induced cross-talk
            // between distinct base64 images that happen to share a hashCode.
            val key = link
            // Fast path: a previous decode (this instance or another) already
            // populated the process-level LRU. Returning synchronously here is
            // cheap (map lookup) and main-thread-safe.
            dataUriBitmapCache.get(key)?.let(ImageDecodeUtils::bitmapToImageData)?.let { return it }
            // R-02b: cache miss. The former path decoded synchronously inside
            // `remember{}`, i.e. BitmapFactory.decodeByteArray ran ON THE MAIN
            // THREAD during composition — an ANR/OOM risk for large data-URI
            // images embedded in markdown. produceState moves the decode to
            // Dispatchers.Default: it returns null on the composing frame and
            // writes the decoded ImageData once the background decode finishes;
            // that state write recomposes this @Composable, at which point the
            // fast path above (now a cache hit, because decodeDataUri populated
            // the LRU) returns synchronously. The ImageTransformer contract
            // (return ImageData? from a @Composable) is preserved, so the
            // mikepenz markdown renderer needs no changes.
            val pending = produceState<ImageData?>(initialValue = null, link) {
                value = withContext(Dispatchers.Default) {
                    decodeDataUri(dataMatch.groupValues[2], key)
                }
            }
            return pending.value
        }

        if (link.startsWith("https://") || link.startsWith("http://")) {
            return HttpImageHolder.load(link)
        }

        return null
    }

    private fun decodeDataUri(base64: String, cacheKey: String): ImageData? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            // R-02: sampled decode (inJustDecodeBounds + inSampleSize) so a
            // multi-MB pasted data-URI image does not allocate a full-res
            // bitmap. See [ImageDecodeUtils.decodeSampled] /
            // [ImageDecodeUtils.calcInSampleSize].
            val bitmap = ImageDecodeUtils.decodeSampled(bytes)
            if (bitmap != null) {
                dataUriBitmapCache.put(cacheKey, bitmap)
                ImageDecodeUtils.bitmapToImageData(bitmap)
            } else {
                Log.w(TAG, "Decoded null bitmap for data URI image")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load data URI image", e)
            null
        }
    }
}
