package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.repository.http.CaptureTrustManager
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.HttpHeaders
import cn.vectory.ocdroid.data.repository.http.SlimapiContract
import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.data.repository.http.TofuPinStore
import cn.vectory.ocdroid.data.repository.http.classifyValidation
import cn.vectory.ocdroid.data.repository.http.spkiSha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * §L4a1 (plan v3, Wave ζ): behavior-preserving extraction of the TOFU
 * (trust-on-first-use cert pinning) concern out of [OpenCodeRepository].
 *
 * Holds the four TOFU operations previously inlined in OCR
 * (`captureServerCert` / `applyTofuDecision` / `pinnedSpkiFor` /
 * `clearTofuPin`). OCR keeps thin `@Synchronized` delegate methods that
 * forward to this class so every existing call site
 * (ConnectionCoordinator / ConnectionBootstrapEngine / all unit &
 * instrumented tests) resolves unchanged — the compat surface that L4a3
 * will later formalize.
 *
 * ## Preserved invariants (L4a0 invariant map)
 *
 * **I4 — single shared [TofuPinStore].** Constructed with the SAME
 * [tofuStore] instance that OCR's `SslConfigFactory` reads during SSL
 * negotiation. No second store is created; writes here are immediately
 * visible to the pin lookup in `SslConfigFactory.sslConfigFor`.
 *
 * **I9 — synchronous client rebuild after a pin write.** After writing the
 * pin, [applyTofuDecision] invokes [onTofuApplied] **synchronously on the
 * calling thread** (no coroutine dispatch, no defer) so the live OkHttp
 * clients are rebuilt and the pin takes effect on the next SSL handshake.
 * OCR wires `onTofuApplied` to its own `@Synchronized rebuildClients()`
 * guarded by the current-host check (original OCR L857-L859 semantics).
 *
 * **I7 — `@Synchronized` serialization.** [applyTofuDecision] remains
 * `@Synchronized` on THIS repository's monitor. The mutual-exclusion with
 * `configure()` / `currentSslConfig()` / `rebuildClients()` is preserved
 * two ways: (a) OCR's public delegate is ALSO `@Synchronized` on the OCR
 * monitor, so the real call path holds the OCR monitor across write+rebuild
 * (fully restoring the original OCR-level exclusion); and (b) the rebuild
 * itself flows through [onTofuApplied] into OCR's own
 * `@Synchronized rebuildClients()` (reentrant under the delegate's held
 * monitor). On the public path the pin write happens INSIDE the OCR
 * monitor (via the @Synchronized delegate above). Even if a future caller
 * invoked [applyTofuDecision] directly, bypassing the OCR delegate, the
 * write would still be safe because [TofuPinStore] is independently
 * thread-safe (both backings use `ConcurrentHashMap` + ESP) and is already
 * read concurrently by `SslConfigFactory` during handshakes outside any
 * monitor.
 *
 * **I3 — no independent client rebuild.** This class builds NO OkHttp /
 * Retrofit clients except the one-shot probe inside [captureServerCert]
 * (side-effect-free w.r.t. the live REST/SSE/command clients). Only
 * [onTofuApplied] triggers OCR's `rebuildClients()`.
 *
 * **I20 — `TofuCaptureResult` backward-compat.** That data class STAYS
 * nested in [OpenCodeRepository] (FQN
 * `cn.vectory.ocdroid.data.repository.OpenCodeRepository.TofuCaptureResult`,
 * referenced by ~10 callers across main/test/androidTest). Kotlin forbids
 * member typealiases, so the nested type is kept verbatim in OCR — see the
 * "keep the type in OCR" escape hatch in the L4a1 contract. This class
 * references it as [OpenCodeRepository.TofuCaptureResult] (same package).
 */
internal class TofuRepository(
    private val tofuStore: TofuPinStore,
    /**
     * §I9: invoked **synchronously** by [applyTofuDecision] after a non-Cancel
     * decision, with the `hostPort` of the decision. OCR wires this to call
     * its own `@Synchronized rebuildClients()` ONLY when `hostPort` matches
     * the currently-configured host — preserving the original OCR L857-L859
     * guard. MUST be synchronous (no coroutine dispatch): pin-then-rebuild
     * is atomic from the caller's view.
     */
    private val onTofuApplied: (hostPort: String) -> Unit,
) {
    /**
     * §tofu R2: one-shot TLS handshake probe that RECORDS the server's leaf
     * cert chain for the trust prompt. Used by the connection coordinator
     * when `checkHealth` / `checkHealthFor` fails with an SSL/cert error and
     * no pin yet exists for [hostPort] — i.e. the user has never trusted this
     * endpoint.
     *
     * Builds its OWN one-shot OkHttpClient (does NOT touch the live mTLS
     * cache nor the held client-cert state — mirrors `SslConfigFactory.resolveProbe`'s
     * non-polluting contract): a [CaptureTrustManager] records the chain, the
     * optional [clientCert] is presented via a fresh KeyManager (so an mTLS
     * server that requires client auth still completes the handshake far
     * enough to present its chain), and a permissive hostnameVerifier lets
     * the handshake complete for self-signed certs whose SAN doesn't match.
     *
     * Returns null on total failure (unreachable host, no cert presented,
     * UI-thread cancellation) — the coordinator surfaces the original
     * SSLHandshakeException in that case.
     */
    suspend fun captureServerCert(
        baseUrl: String,
        hostPort: String,
        clientCert: ClientCertMaterial? = null,
        /**
         * R8 slim-mode foundation / C2 fix: slim=true 时探 `{baseUrl}/slimapi/health`
         * （带 `X-Slimapi-Version` 头）；slim=false（默认）保持 legacy
         * `/global/health`。captureTrustManager 仅记录 TLS 握手 chain——path
         * 选择不影响捕获的 leaf，但路径正确性确保 sidecar 自身（而非经
         * catch-all 透传到的 opencode）的 leaf 被记录。
         */
        slim: Boolean = false
    ): OpenCodeRepository.TofuCaptureResult? = withContext(Dispatchers.IO) {
        val capture = CaptureTrustManager()
        // Build the one-shot SSLContext: CaptureTrustManager (records) +
        // optional KeyManagers from the supplied p12 (mTLS client auth).
        val keyManagers = clientCert?.let { material ->
            runCatching {
                val p12 = KeyStore.getInstance("PKCS12").apply {
                    load(ByteArrayInputStream(material.p12Bytes), material.p12Password)
                }
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    .apply { init(p12, material.p12Password) }
                kmf.keyManagers
            }.getOrNull()
        }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf<TrustManager>(capture), SecureRandom())
        }
        val oneShot = OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, capture)
            // §tofu R2: pin即身份 — capture 阶段放行 hostnameVerifier，使自签证书
            // （SAN 常不匹配）也能完成握手并暴露 leaf。安全由随后 SPKI pin 保证。
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        // R8 slim-mode foundation / C2 fix: slim=true → /slimapi/health（带版本头）;
        // slim=false → /global/health（行为字节级不变）。capture 不在乎 path 的 HTTP
        // 语义——它只想要 leaf；但 path 正确性保证 leaf 是 sidecar 自己的，而非经
        // catch-all 反代暴露的 upstream。
        val healthPath = if (slim) SlimapiContract.SLIMAPI_HEALTH_PATH
            else SlimapiContract.LEGACY_HEALTH_PATH
        val normalizedUrl = (if (baseUrl.startsWith("http")) baseUrl else "https://$baseUrl")
            .trimEnd('/') + healthPath
        val requestBuilder = Request.Builder()
            .url(normalizedUrl)
            .header(HttpHeaders.SKIP_DIR_HEADER, "1")
        if (slim) {
            requestBuilder.header(
                SlimapiContract.X_SLIMAPI_VERSION,
                SlimapiContract.SLIMAPI_CLIENT_VERSION.toString()
            )
        }
        // Surface the leaf + classification regardless of whether the GET
        // itself succeeded — a 4xx/5xx after a completed handshake STILL
        // captured the chain (the handshake happens before any HTTP
        // exchange). Only total handshake/connection failures return null.
        runCatching {
            oneShot.newCall(requestBuilder.build()).execute().use { /* drain */ }
        }
        val chain = capture.capturedChain
        if (chain.isNullOrEmpty()) return@withContext null
        val leaf = chain.first()
        val spki = leaf.spkiSha256Hex()
        val host = hostPort.substringBefore(':')
        val validation = classifyValidation(chain, host)
        OpenCodeRepository.TofuCaptureResult(
            hostPort = hostPort,
            leaf = leaf,
            spkiHex = spki,
            validation = validation
        )
    }

    /**
     * §tofu R2: applies the UI's [TofuDecision] for [hostPort] to the shared
     * [tofuStore]. After [TofuDecision.AcceptOnce] / [TofuDecision.Trust]
     * the next `checkHealth` resolves a TofuPinned SSL config and the
     * handshake succeeds; [TofuDecision.Cancel] writes nothing (the user
     * declined — the in-flight connect is settled false by the coordinator).
     *
     * The decision is keyed by [hostPort] so two profiles reaching the same
     * endpoint share the trust state (known_hosts model — grill Q4=a).
     *
     * `@Synchronized` to serialize concurrent decisions; the live-client
     * rebuild (I9) is triggered synchronously via [onTofuApplied] — see the
     * class KDoc for the full I7 serialization story.
     */
    @Synchronized
    fun applyTofuDecision(hostPort: String, decision: TofuDecision) {
        when (decision) {
            is TofuDecision.AcceptOnce -> tofuStore.acceptSession(hostPort, decision.spki)
            is TofuDecision.Trust -> tofuStore.trustPersistent(hostPort, decision.spki)
            TofuDecision.Cancel -> { /* no-op — user declined */ }
        }
        // §tofu R2 round-1 fix + §I9: live OkHttp clients are snapshotted at
        // configure()/rebuildClients() time with the PRE-decision SSL config
        // (SystemDefault); the socket factory is immutable post-build, so
        // writing the pin alone leaves retries on SystemDefault → handshake
        // fails again → pin now exists → no re-prompt → retries exhausted →
        // Disconnected (Accept/Trust becomes a dead end). Hence rebuild the
        // clients for the current host SYNCHRONOUSLY so sslConfigFor(hostPort)
        // re-resolves to TofuPinned before the caller's retry. The current-host
        // guard (original OCR L857) is enforced INSIDE the OCR-wired callback,
        // so this class stays host-agnostic.
        if (decision !is TofuDecision.Cancel) {
            onTofuApplied(hostPort)
        }
    }

    /**
     * §tofu R2: query the current pinned SPKI for [hostPort] (persistent OR
     * session tier). Used by the coordinator's "should I prompt?" guard so
     * we never re-prompt an endpoint the user has already trusted.
     */
    fun pinnedSpkiFor(hostPort: String): String? = tofuStore.pinnedSpki(hostPort)

    /**
     * §tofu R2: forget the pin for [hostPort] (both tiers). Re-prompt is
     * forced on the next connect. Used by the host management UI's "forget
     * trust" affordance.
     */
    fun clearTofuPin(hostPort: String) = tofuStore.clear(hostPort)
}
