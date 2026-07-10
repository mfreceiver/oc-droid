package cn.vectory.ocdroid.ui.util

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import com.mikepenz.markdown.model.ImageData
import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.data.repository.http.applySsl
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
     * §2.6: SSL-aware image HTTP client. Built via [newImageHttpClient] which
     * routes through [applySsl] so that, when the active host opts into
     * `allowInsecureConnections` OR presents an mTLS client cert, self-signed
     * HTTPS markdown images are fetched with the matching trust / client-cert
     * config — previously this client was a bare `OkHttpClient.Builder()`
     * with no SSL wiring and failed the TLS handshake on self-signed image
     * hosts even with the toggle ON.
     *
     * Defaults to system trust ([SslConfig.SystemDefault]) to preserve the
     * pre-fix behaviour when no host has been configured. Rebuilt in place by
     * [updateSsl], which is invoked from
     * `HostProfileController.configureRepositoryForProfile` /
     * `configureServer` / `ConnectionActions.applySavedSettings` alongside the
     * repository reconfigure so the image client tracks the same trust policy
     * as REST / SSE (now including mTLS — v3: cold-start images must also
     * present the client cert, gpter#4).
     *
     * §2.6: the SSL config (and resulting client) is supplied by the caller —
     * this object no longer owns a [cn.vectory.ocdroid.data.repository.http.SslConfigFactory];
     * it receives the fully-resolved [SslConfig] (mTLS / TrustAll /
     * SystemDefault) from [OpenCodeRepository.currentSslConfig], which reads
     * the shared factory the repository holds. Keeps the SSL decision in one
     * place and lets the image client reuse the exact live material (same
     * client cert, same private CA) without a second factory diverging.
     *
     * Thread safety: the reference is `@Volatile` (read by prefetch
     * coroutines on [Dispatchers.IO], written under `@Synchronized` in
     * [updateSsl]); OkHttp clients are themselves thread-safe, so a stale
     * in-flight call may finish on the previous client instance but no
     * read/write race is possible.
     */
    @Volatile
    private var imageSslConfig: SslConfig = SslConfig.SystemDefault

    @Volatile
    private var imageHttpClient: OkHttpClient = newImageHttpClient(SslConfig.SystemDefault)

    /**
     * #12 / §2.6 测试钩子（@VisibleForTesting）：记录最近一次 [updateSsl] 调用解析到
     * 的 SSL 模式——无论该次调用是否实际触发 client 重建（no-op 也记录）。值域
     * `{"SYSTEM", "TRUST_ALL", "MUTUAL_TLS"}`，null 表进程启动后尚未调用过
     * [updateSsl]。仅供
     * [cn.vectory.ocdroid.ui.controller.HostProfileControllerTest] 断言
     * "controller 确实把信任策略同步到了 image client"；生产行为从不读取此字段。
     * 通过 [resetTestState] 重置。
     *
     * 取代旧的 `lastUpdateSslAllowInsecure: Boolean?`（v3：mTLS 引入后布尔已不足
     * 区分三态信任模式）。`@Volatile` 与同类字段保持一致——该字段由 [updateSsl]
     * 在 `@Synchronized` 块内写入，但测试线程可能从任意上下文读取。
     */
    @VisibleForTesting
    @Volatile
    internal var lastUpdateSslMode: String? = null

    private fun newImageHttpClient(cfg: SslConfig): OkHttpClient =
        OkHttpClient.Builder()
            .apply { applySsl(cfg) }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    /**
     * #12 / §2.6: rebuilds the image HTTP client so its TLS trust policy
     * matches the resolved [cfg] supplied by the caller
     * ([OpenCodeRepository.currentSslConfig]). Called from the host profile
     * controller on every repository reconfigure and from cold-start
     * [ConnectionActions.applySavedSettings].
     *
     * No-op when [cfg] is unchanged (avoids needless SSLContext churn on
     * unrelated reconfigures). 注：mTLS 下 [SslConfig.MutualTLS] 每次 configure
     * 都由 buildMutualTlsConfig 新建实例（身份相等），故 mTLS host 每次 configure
     * 都会重建 image client——可接受（configure 罕见）。no-op 守卫仅对
     * SYSTEM/TRUST_ALL 稳定单例有效（§2.6 / glmer I2）。
     *
     * Thread-safe: synchronized write + volatile field.
     */
    @Synchronized
    fun updateSsl(cfg: SslConfig) {
        // 测试钩子：记录每次调用（含 no-op）的解析模式，供单测断言 controller→image
        // client 的信任策略同步。赋值开销可忽略，不影响生产行为。
        // §tofu R2: 新增 TOFU_PINNED 模式——当 host:port 有 TOFU pin 时，image
        // client 也用 SPKI pinning TM（与 REST/SSE 对称，自签图片 host 同样放行）。
        lastUpdateSslMode = when (cfg) {
            SslConfig.SystemDefault -> "SYSTEM"
            is SslConfig.MutualTLS -> "MUTUAL_TLS"
            is SslConfig.TofuPinned -> "TOFU_PINNED"
        }
        if (cfg == imageSslConfig) return
        imageSslConfig = cfg
        imageHttpClient = newImageHttpClient(cfg)
    }

    /**
     * #12 / §2.6 测试钩子（@VisibleForTesting）：把单例的可变 SSL 状态
     * （[imageSslConfig] / [imageHttpClient] / [lastUpdateSslMode]）重置为进程
     * 启动初值（[SslConfig.SystemDefault]），避免 object 单例在跨单测间残留状态
     * 污染——例如前一个用例切到 TrustAll / mTLS 后，本用例的 `updateSsl(...)`
     * 会变成 no-op 而无法验证"调用事实"。仅供
     * [cn.vectory.ocdroid.ui.controller.HostProfileControllerTest] 的
     * @Before/@After 调用。
     */
    @VisibleForTesting
    @Synchronized
    fun resetTestState() {
        imageSslConfig = SslConfig.SystemDefault
        imageHttpClient = newImageHttpClient(SslConfig.SystemDefault)
        lastUpdateSslMode = null
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

    // §lint: CoroutineCreationDuringComposition — the launch here is a
    // deliberate fire-and-forget disk decode on the long-lived prefetchScope
    // (a class-level CoroutineScope that outlives composition). It populates
    // the shared cachedBitmaps LRU for ALL composables, so cancelling on
    // leave (LaunchedEffect semantics) would be wrong — a decode kicked off
    // for one markdown image should complete to benefit sibling renders.
    // Dedup is via inflightUrls (added before launch, removed in finally).
    @Suppress("CoroutineCreationDuringComposition")
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
     * (called from [cn.vectory.ocdroid.OpenCodeApp.onTrimMemory]). The
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
