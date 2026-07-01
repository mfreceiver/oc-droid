package com.yage.opencode_client.ui.util

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import com.mikepenz.markdown.model.ImageData
import com.yage.opencode_client.data.repository.http.SslConfigFactory
import com.yage.opencode_client.data.repository.http.applySsl
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

/**
 * Logcat tag. Preserved as the historical shared tag `"DataUriImage"` so logcat
 * filtering for image load/decode messages is unchanged after the split.
 */
private const val TAG = "DataUriImage"

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
 *
 * Extracted verbatim from `DataUriImageTransformer.kt`; sampled decode now
 * delegates to [ImageDecodeUtils.decodeSampled] and ImageData wrapping to
 * [ImageDecodeUtils.bitmapToImageData]. Behaviour is unchanged.
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

    /**
     * #12: SSL-aware image HTTP client. Built via [newImageHttpClient] which
     * routes through the shared [SslConfigFactory] / [applySsl] entry point
     * so that, when the active host opts into `allowInsecureConnections`,
     * self-signed HTTPS markdown images are fetched with the trust-all
     * config — previously this client was a bare `OkHttpClient.Builder()`
     * with no SSL wiring and failed the TLS handshake on self-signed image
     * hosts even with the toggle ON.
     *
     * Defaults to system trust (allowInsecure=false) to preserve the
     * pre-fix behaviour when no host has been configured / the toggle is
     * OFF. Rebuilt in place by [updateSsl], which is invoked from
     * `HostProfileController.configureRepositoryForProfile` /
     * `configureServer` alongside the repository reconfigure so the image
     * client tracks the same trust policy as REST / SSE.
     *
     * Thread safety: the reference is `@Volatile` (read by prefetch
     * coroutines on [Dispatchers.IO], written under `@Synchronized` in
     * [updateSsl]); OkHttp clients are themselves thread-safe, so a stale
     * in-flight call may finish on the previous client instance but no
     * read/write race is possible.
     */
    private val sslConfigFactory = SslConfigFactory()

    @Volatile
    private var imageAllowInsecure: Boolean = false

    @Volatile
    private var imageHttpClient: OkHttpClient = newImageHttpClient(allowInsecure = false)

    /**
     * #12 测试钩子（@VisibleForTesting）：记录最近一次 [updateSsl] 调用传入的
     * allowInsecure 值——无论该次调用是否实际触发 client 重建（no-op 也记录）。
     * 仅供 [com.yage.opencode_client.ui.controller.HostProfileControllerTest]
     * 断言 "controller 确实把信任策略同步到了 image client"；生产行为从不读取
     * 此字段。通过 [resetTestState] 重置。
     *
     * S-2: `@Volatile` 与同类字段（[imageAllowInsecure] / [imageHttpClient]）
     * 保持一致——该字段由 [updateSsl] 在 `@Synchronized` 块内写入，但测试线程
     * 可能从任意上下文读取，缺少 happens-before 边界时存在理论 stale read 风险。
     */
    @VisibleForTesting
    @Volatile
    internal var lastUpdateSslAllowInsecure: Boolean? = null

    private fun newImageHttpClient(allowInsecure: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .apply { applySsl(sslConfigFactory.sslConfigFor(allowInsecure)) }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    /**
     * #12: rebuilds the image HTTP client so its TLS trust policy matches the
     * active host's [allowInsecure] flag. No-op when the flag is unchanged
     * (avoids needless SSLContext churn on unrelated reconfigures). Called
     * from the host profile controller on every repository reconfigure.
     *
     * Thread-safe: synchronized write + volatile field. The default
     * (allowInsecure=false) is the system trust store, so this is a pure
     * no-op until a trust-all host is configured — zero behaviour
     * regression for the OFF case.
     */
    @Synchronized
    fun updateSsl(allowInsecure: Boolean) {
        // #12 测试钩子：记录每次调用（含 no-op），供单测断言 controller→image
        // client 的信任策略同步。赋值开销可忽略，不影响生产行为。
        lastUpdateSslAllowInsecure = allowInsecure
        if (allowInsecure == imageAllowInsecure) return
        imageAllowInsecure = allowInsecure
        imageHttpClient = newImageHttpClient(allowInsecure)
    }

    /**
     * #12 测试钩子（@VisibleForTesting）：把单例的可变 SSL 状态
     * （[imageAllowInsecure] / [imageHttpClient] / [lastUpdateSslAllowInsecure]）
     * 重置为进程启动初值（allowInsecure=false），避免 object 单例在跨单测间
     * 残留状态污染——例如前一个用例把 toggle 置 true 后，本用例的
     * `updateSsl(true)` 会变成 no-op 而无法验证"调用事实"。仅供
     * [com.yage.opencode_client.ui.controller.HostProfileControllerTest] 的
     * @Before/@After 调用。
     */
    @VisibleForTesting
    @Synchronized
    fun resetTestState() {
        imageAllowInsecure = false
        imageHttpClient = newImageHttpClient(allowInsecure = false)
        lastUpdateSslAllowInsecure = null
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
        cachedBitmaps.get(url)?.let { return ImageDecodeUtils.bitmapToImageData(it) }

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
                    // large HTTP attachments (see [ImageDecodeUtils.decodeSampled]).
                    ImageDecodeUtils.decodeSampled(bytes)
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
                // (see [ImageDecodeUtils.decodeSampled]).
                ImageDecodeUtils.decodeSampled(bytes)
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
