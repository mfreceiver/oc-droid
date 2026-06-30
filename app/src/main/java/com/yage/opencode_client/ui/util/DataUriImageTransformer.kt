package com.yage.opencode_client.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

/**
 * R-02: long-edge decode target for sampled image decoding in this file.
 * Matches [com.yage.opencode_client.ui.files.IMAGE_DECODE_TARGET_PX] (2048):
 * the chat/markdown image render target is `fillMaxWidth` of a phone-width
 * column, so 2048 px on the long edge is far above display density — sampling
 * to this cap has no visible effect but bounds peak bitmap memory regardless
 * of source resolution (data-URI pastes, large HTTP attachments, cached disk
 * files). Defined here as a private copy rather than referenced cross-package
 * to keep `ui/util` decoupled from `ui/files`.
 */
private const val IMAGE_DECODE_TARGET_PX = 2048

/**
 * R-02: two-pass sampled decode of an in-memory image byte array. Pass 1
 * probes dimensions with [BitmapFactory.Options.inJustDecodeBounds] = true
 * (no pixel allocation); [calcInSampleSize] then picks the smallest
 * power-of-two sample keeping the long edge ≤ [targetPx]; pass 2 decodes the
 * actual downsampled bitmap. Bounds peak memory regardless of source size.
 *
 * Returns null if the bounds probe reports non-positive dimensions (corrupt /
 * unknown format), matching the previous `decodeByteArray` null-on-failure
 * contract.
 */
private fun decodeSampled(bytes: ByteArray, targetPx: Int = IMAGE_DECODE_TARGET_PX): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = calcInSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
    if (sample <= 0) return null
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
}

/**
 * Computes a power-of-two [BitmapFactory.Options.inSampleSize] so the decoded
 * long edge is at most [target] pixels. Uses [Long] arithmetic throughout to
 * avoid [Int] overflow on very large source dimensions (a pathological payload
 * could report outWidth/outHeight near Int.MAX_VALUE, and the intermediate
 * `(w/s)*(h/s)` product would otherwise overflow Int and produce a wrong —
 * too small — sample size, causing an OOM on the second decode pass).
 *
 * Returns 0 for non-positive dimensions so the caller can treat the image as
 * undecodable rather than attempting a full-resolution fallback decode.
 */
private fun calcInSampleSize(w: Int, h: Int, target: Int): Int {
    if (w <= 0 || h <= 0 || target <= 0) return 0
    var sample = 1
    val longW = w.toLong()
    val longH = h.toLong()
    val longT = target.toLong()
    while ((longW / sample) * (longH / sample) > longT * longT) {
        sample *= 2
    }
    return sample
}

internal fun bitmapToImageData(bitmap: Bitmap): ImageData = ImageData(
    painter = BitmapPainter(bitmap.asImageBitmap()),
    contentDescription = null,
    modifier = Modifier.fillMaxWidth(),
    alignment = Alignment.Center,
    contentScale = ContentScale.FillWidth
)

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
            dataUriBitmapCache.get(key)?.let(::bitmapToImageData)?.let { return it }
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
            // bitmap. See [decodeSampled] / [calcInSampleSize].
            val bitmap = decodeSampled(bytes)
            if (bitmap != null) {
                dataUriBitmapCache.put(cacheKey, bitmap)
                bitmapToImageData(bitmap)
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

/**
 * §16.2 方案 β: HTTPS image prefetch + disk cache.
 *
 * Interface shape is preserved ([load] is `@Composable` returning `ImageData?`,
 * [prefetch] is fire-and-forget) so chat / files call sites compile unchanged.
 * Underlying storage is swapped from hand-rolled sequential `HttpURLConnection`
 * fetches to a parallel OkHttpClient-backed disk+memory cache:
 *
 * - **Disk cache** (`<cacheDir>/opencode_images/<urlHash>.bin`): raw response
 *   bytes, so re-rendering the same chat does NOT re-download. Bounded by
 *   [DISK_CACHE_MAX_BYTES] with simple LRU eviction (oldest files first).
 * - **Memory cache** (bounded `LruCache`): decoded `Bitmap`s; writes bump
 *   [HttpImageHolder.cacheVersion] to recompose any [load] caller. R-03: the
 *   former `mutableStateMapOf` was unbounded and retained every decoded
 *   image for the process lifetime — an OOM risk under heavy chat image
 *   load. It is now a 16 MB `LruCache` sized by `Bitmap.byteCount`; on
 *   memory pressure `OpenCodeApp.onTrimMemory` calls [onLowMemory] to
 *   evict (disk cache survives).
 * - **Parallel prefetch**: [prefetch] returns immediately and runs the
 *   download on [prefetchScope]; existing sequential `for (url in urls)
 *   { prefetch(url) }` call sites therefore fan out concurrently.
 *
 * Cache directory is resolved from `java.io.tmpdir`, which on Android resolves
 * to the app-private `/data/data/<pkg>/cache` dir (same as `Context.cacheDir`).
 */
object HttpImageHolder {
    private const val DISK_CACHE_DIR_NAME = "opencode_images"
    private const val DISK_CACHE_MAX_BYTES = 50L * 1024 * 1024
    private const val MEMORY_CACHE_MAX_BYTES = 16 * 1024 * 1024

    /**
     * R-03: bounded LRU bitmap cache keyed by URL. [sizeOf] reports
     * [Bitmap.byteCount] so eviction is byte-accurate rather than
     * entry-count-based. The LruCache itself is NOT snapshot-aware, so
     * Compose observation is provided by [cacheVersion]: every write bumps
     * the int and any @Composable [load] caller that read it recomposes.
     */
    private val cachedBitmaps = object : LruCache<String, Bitmap>(MEMORY_CACHE_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    /** Compose-observable recomposition signal; bumped on every cache write. */
    private val cacheVersion = mutableIntStateOf(0)

    private val inflightUrls = ConcurrentHashMap.newKeySet<String>()
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val imageHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val diskCacheDir: File? by lazy {
        val base = System.getProperty("java.io.tmpdir")?.let(::File) ?: return@lazy null
        val dir = File(base, DISK_CACHE_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) null else dir
    }

    private fun fileForUrl(url: String): File? {
        val dir = diskCacheDir ?: return null
        // R-15: hash the URL to a stable, filesystem-safe filename via SHA-256.
        // hashCode() is only 32 bits and collides often enough across real
        // image URLs to cause cross-contamination of cached files; SHA-256
        // truncated to 32 hex chars removes that risk.
        val name = diskName(url)
        return File(dir, "$name.bin")
    }

    private fun diskName(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)

    @Composable
    fun load(url: String): ImageData? {
        // Fast path: already decoded into memory. Reading cacheVersion first
        // registers a Compose snapshot dependency so that a background write
        // (downloadAndCache / loadFromDiskIntoMemory bumping it) recomposes
        // this caller and the fast path then hits the freshly populated LRU.
        @Suppress("unused") val version = cacheVersion.intValue
        cachedBitmaps.get(url)?.let { return bitmapToImageData(it) }

        // Disk-cached but not yet decoded: kick off an async decode into memory.
        // Returns null on this frame; the state-map write triggers recomposition.
        // §评审 Stage C #7: dedupe against [prefetch] (and against re-entrant
        // calls during markdown re-assembly) via [inflightUrls]. Without this
        // every recomposition that misses memory but hits disk would launch a
        // fresh decode, accumulating redundant IO.
        val file = fileForUrl(url)
        if (file != null && file.exists() && inflightUrls.add(url)) {
            prefetchScope.launch {
                try {
                    loadFromDiskIntoMemory(url, file)
                } finally {
                    inflightUrls.remove(url)
                }
            }
        }
        return null
    }

    /**
     * Fire-and-forget prefetch. Returns immediately; the actual fetch/decode
     * runs on [prefetchScope]. Existing sequential `for { prefetch(url) }`
     * call sites thus become effectively parallel.
     *
     * No-op when the URL is already in memory or disk, or currently downloading.
     */
    fun prefetch(url: String) {
        if (cachedBitmaps.get(url) != null) return
        val file = fileForUrl(url)
        if (file != null && file.exists()) {
            // Disk hit: just promote into memory asynchronously.
            if (!inflightUrls.add(url)) return
            prefetchScope.launch {
                try {
                    loadFromDiskIntoMemory(url, file)
                } finally {
                    inflightUrls.remove(url)
                }
            }
            return
        }
        if (!inflightUrls.add(url)) return
        prefetchScope.launch { downloadAndCache(url) }
    }

    private suspend fun downloadAndCache(url: String) {
        try {
            val target = fileForUrl(url)
            val request = Request.Builder().url(url).build()
            imageHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Image download failed: ${response.code} for ${url.take(120)}")
                    return
                }
                val bytes = response.body?.bytes() ?: return
                if (target != null) {
                    runCatching {
                        target.writeBytes(bytes)
                        evictIfNeeded()
                    }.onFailure {
                        Log.w(TAG, "Failed to persist image to disk cache", it)
                    }
                }
                val bitmap = withContext(Dispatchers.IO) {
                    // R-02: sampled decode — bounds peak bitmap memory for
                    // large HTTP attachments (see [decodeSampled]).
                    decodeSampled(bytes)
                }
                if (bitmap != null) {
                    putBitmap(url, bitmap)
                } else {
                    Log.w(TAG, "Decoded null bitmap for markdown image: ${url.take(120)}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prefetch image from ${url.take(120)}", e)
        } finally {
            inflightUrls.remove(url)
        }
    }

    private suspend fun loadFromDiskIntoMemory(url: String, file: File) {
        if (cachedBitmaps.get(url) != null) return
        try {
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val bitmap = withContext(Dispatchers.IO) {
                // R-02: sampled decode — a cached multi-MB image should not
                // be re-decoded at full resolution on every cache miss
                // (see [decodeSampled]).
                decodeSampled(bytes)
            }
            if (bitmap != null) {
                putBitmap(url, bitmap)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached image from disk for ${url.take(120)}", e)
        }
    }

    /**
     * Writes [bitmap] under [url] into the bounded LRU and bumps the
     * [cacheVersion] recomposition signal so any @Composable [load] caller
     * observing this cache recomposes and picks up the fast path.
     */
    private fun putBitmap(url: String, bitmap: Bitmap) {
        cachedBitmaps.put(url, bitmap)
        cacheVersion.intValue++
    }

    /**
     * R-03: drops the in-memory bitmap cache under system memory pressure
     * (called from [com.yage.opencode_client.OpenCodeApp.onTrimMemory]). The
     * disk cache is left intact — it is bounded and self-evicting. Bumping
     * [cacheVersion] recomposes any @Composable [load] caller so a cleared
     * entry cleanly resolves to a cache miss (null) and re-decodes from disk
     * on the next load.
     */
    fun onLowMemory() {
        cachedBitmaps.evictAll()
        cacheVersion.intValue++
    }

    /**
     * Simple size-based LRU eviction: when the disk cache exceeds
     * [DISK_CACHE_MAX_BYTES], delete the oldest-accessed files first until
     * under the cap. Called after each successful write.
     */
    private fun evictIfNeeded() {
        val dir = diskCacheDir ?: return
        val files = dir.listFiles()?.toMutableList() ?: return
        var totalSize = files.sumOf { it.length() }
        if (totalSize <= DISK_CACHE_MAX_BYTES) return
        files.sortBy { it.lastModified() }
        val iter = files.iterator()
        while (iter.hasNext() && totalSize > DISK_CACHE_MAX_BYTES) {
            val f = iter.next()
            val size = f.length()
            if (f.delete()) {
                totalSize -= size
            }
        }
    }
}
