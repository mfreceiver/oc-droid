package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import cn.vectory.ocdroid.data.repository.http.InMemoryTofuPinStore
import cn.vectory.ocdroid.data.repository.http.SslConfig
import cn.vectory.ocdroid.data.repository.http.SslConfigFactory
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * §tokenstream-mtls-fix: regression tests for [OpenCodeRepository.tokenStreamClient].
 *
 * Root cause being guarded against: the token-stream SSE was wired (in
 * `ControllerModule.provideTokenStreamCoordinator`) to the Hilt-singleton
 * [cn.vectory.ocdroid.data.repository.http.OkHttpClientFactory], whose OWN
 * [SslConfigFactory] never received `configureClientCert` → `sslConfigFor` fell
 * back to [SslConfig.SystemDefault] → "Trust anchor for certification path not
 * found" under mTLS + slim (REST/SSE worked because they read the REPOSITORY's
 * factory). The fix routes the token stream through
 * [OpenCodeRepository.tokenStreamClient], which delegates to the repository's own
 * `clientFactory` (the one whose `sslConfigFactory` holds the mTLS material).
 *
 * These tests PROVE the routing goes through the repository factory (not an
 * independent Hilt-style factory): the private-CA handshake via
 * `repository.tokenStreamClient(...)` succeeds ONLY because the repository's
 * `sslConfigFactory` was loaded with the client cert by `configure(...)`. An
 * independent factory (simulating the pre-fix Hilt singleton) stays
 * [SslConfig.SystemDefault] and would reject the same server cert.
 *
 * Cert/MockWebServer mTLS setup mirrors `SslConfigFactoryTest` (accepted) — only
 * the client is built via the repository facade instead of directly.
 */
class OpenCodeRepositoryTokenStreamMtlsTest {

    private val server = MockWebServer()
    private val pw = "test-p12-pw"

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── cert helpers (mirror SslConfigFactoryTest; self-contained) ───────────

    /** Self-signed root CA (with keyPair, can sign leaves). */
    private fun newRootCa(cn: String = "opencode-test-ca"): HeldCertificate =
        HeldCertificate.Builder().commonName(cn).certificateAuthority(0).build()

    /** Leaf certificate signed by [parent] (client / server通用). */
    private fun newSigned(
        parent: HeldCertificate,
        cn: String = "leaf",
        san: String? = null,
    ): HeldCertificate {
        val b = HeldCertificate.Builder().commonName(cn)
        if (san != null) b.addSubjectAlternativeName(san)
        return b.signedBy(parent).build()
    }

    /** Pack a [HeldCertificate] (with private key) + cert chain into PKCS12 bytes. */
    private fun p12(
        holder: HeldCertificate,
        chain: List<X509Certificate>,
        alias: String = "single",
        password: String = pw,
    ): ByteArray {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry(alias, holder.keyPair.private, password.toCharArray(), chain.toTypedArray())
        val baos = ByteArrayOutputStream()
        ks.store(baos, password.toCharArray())
        return baos.toByteArray()
    }

    /** X509Certificate → PEM bytes (CertificateFactory.generateCertificates eats PEM). */
    private fun X509Certificate.toPem(): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("-----BEGIN CERTIFICATE-----\n".toByteArray())
        baos.write(Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encode(encoded))
        baos.write("\n-----END CERTIFICATE-----\n".toByteArray())
        return baos.toByteArray()
    }

    // ── 1. mTLS path: one candidate bundle serves REST and token stream ───────

    @Test
    fun `tokenStreamClient uses the repository mTLS factory after configure - private-CA handshake succeeds`() {
        val ca = newRootCa()
        val client = newSigned(ca, cn = "ocdroid-test-client")
        val p12Bytes = p12(client, listOf(client.certificate, ca.certificate))
        val caPem = ca.certificate.toPem()

        val repo = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )
        val hostPort = "localhost:${server.port}"

        // Server presents a leaf signed by the private CA and trusts client
        // certificates issued by that CA.
        val serverCert = newSigned(ca, cn = "server", san = "localhost")
        val serverHs = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            // Trust the issuing CA so JSSE advertises that CA as an acceptable
            // client-certificate issuer. Trusting only the leaf can make the
            // client key manager decline the alias before the handshake.
            .addTrustedCertificate(ca.certificate)
            .build()
        server.useHttps(serverHs.sslContext().socketFactory, false)

        // §tokenstream-mtls-fix: configure() loads the mTLS material into the
        // REPOSITORY's sslConfigFactory (the same one REST/SSE use).
        repo.configure(
            baseUrl = server.url("/").toString(),
            hostPort = hostPort,
            username = "bundle-user",
            password = "bundle-password",
            clientCert = ClientCertMaterial(p12Bytes, pw.toCharArray(), caPem),
        )

        // (1) The repository's sslConfigFactory (the one tokenStreamClient reads)
        //     now holds mTLS — NOT SystemDefault. An independent Hilt-style
        //     factory that never got configureClientCert stays SystemDefault —
        //     that divergence IS the bug surface this fix closes.
        assertTrue(
            "repository currentSslConfig is MutualTLS after configure(clientCert)",
            repo.currentSslConfig() is SslConfig.MutualTLS,
        )
        val independentFactory = SslConfigFactory(InMemoryTofuPinStore())
        assertTrue(
            "an independent (Hilt-singleton-style) SslConfigFactory is still SystemDefault — the pre-fix bug",
            independentFactory.sslConfigFor(hostPort) is SslConfig.SystemDefault,
        )

        // (2) End-to-end through the REPOSITORY factory: tokenStreamClient must
        //     trust the private CA (server cert) AND present the client cert → 200.
        //     A SystemDefault client (the bug) would throw SSLHandshakeException
        //     ("Trust anchor for certification path not found") here.
        // Require client authentication: a client that merely trusts the
        // private CA but does not present the candidate's PKCS12 certificate
        // must fail the TLS handshake.
        server.requireClientAuth()

        // REST and token-stream requests must both use the published bundle's
        // private-CA trust and client certificate, and both must capture the
        // same immutable credentials rather than reading a later HostConfig.
        server.enqueue(MockResponse().setBody("rest-ok"))
        val restBundle = repo.currentClientBundle()!!
        val restResponse = restBundle.restHttp.newCall(
            Request.Builder().url(server.url("/rest")).build(),
        ).execute()
        assertEquals("REST mTLS handshake via candidate bundle succeeds", 200, restResponse.code)
        assertEquals("rest-ok", restResponse.body?.string())
        restResponse.close()
        val restRequest = server.takeRequest()
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("bundle-user:bundle-password".toByteArray()),
            restRequest.getHeader("Authorization"),
        )

        server.enqueue(MockResponse().setBody("token-ok"))
        val streamClient = repo.tokenStreamClient(hostPort)
        val res = streamClient
            .newCall(Request.Builder().url(server.url("/token").toString()).build())
            .execute()
        assertEquals("mTLS handshake via repository.tokenStreamClient succeeds", 200, res.code)
        assertEquals("token-ok", res.body?.string())
        res.close()
        val tokenRequest = server.takeRequest()
        assertEquals(
            "REST and token stream must carry the candidate bundle credentials",
            restRequest.getHeader("Authorization"),
            tokenRequest.getHeader("Authorization"),
        )
        assertSame(
            "mTLS token-stream construction must remain on the same immutable generation as REST",
            restBundle,
            repo.currentClientBundle(),
        )
        assertEquals(
            "REST and token stream must share the published generation",
            restBundle.generation,
            repo.currentClientBundle()!!.generation,
        )
    }

    // ── 2. reconfigure timing: each open reads LIVE SSL state ───────────────

    @Test
    fun `reconfigure changes the token-stream SSL state live - clear drops mTLS, new host picks up the new CA`() {
        val repo = OpenCodeRepository(
            mockk<TrafficTracker>(relaxed = true),
            mockk<TrafficLogger>(relaxed = true),
        )

        // (a) mTLS configured with CA-A → MutualTLS.
        val caA = newRootCa(cn = "ca-a")
        val clientA = newSigned(caA, cn = "client-a")
        repo.configure(
            baseUrl = "https://host-a:1234/",
            hostPort = "host-a:1234",
            clientCert = ClientCertMaterial(
                p12(clientA, listOf(clientA.certificate, caA.certificate)),
                pw.toCharArray(),
                caA.certificate.toPem(),
            ),
        )
        assertTrue("(a) mTLS configured → MutualTLS", repo.currentSslConfig() is SslConfig.MutualTLS)

        // (b) Clear clientCert → SystemDefault (no mTLS residue — switching to a
        //     plain profile stops presenting the cert; configureClientCert(null)
        //     nulls mutualTlsConfig).
        repo.configure(
            baseUrl = "https://host-a:1234/",
            hostPort = "host-a:1234",
            clientCert = null,
        )
        assertTrue(
            "(b) cleared clientCert → SystemDefault (no mTLS residue)",
            repo.currentSslConfig() is SslConfig.SystemDefault,
        )

        // (c) Reconfigure to a NEW host + NEW CA-B → MutualTLS again, and the
        //     tokenStreamClient handshake succeeds against a CA-B-signed server.
        //     This proves a post-reconfigure open reads LIVE state (the live-host-
        //     config invariant): had tokenStreamClient cached/stale-read CA-A from
        //     step (a), the CA-B-signed server cert would be rejected → handshake
        //     fail. (The resolver-URL half of the invariant — "open resolves the
        //     URL at call time, not DI-construction time" — is the DI provider's
        //     concern and is already covered by its existing wiring comment.)
        val caB = newRootCa(cn = "ca-b")
        val clientB = newSigned(caB, cn = "client-b")
        val hostB = "localhost:${server.port}"
        repo.configure(
            baseUrl = server.url("/").toString(),
            hostPort = hostB,
            clientCert = ClientCertMaterial(
                p12(clientB, listOf(clientB.certificate, caB.certificate)),
                pw.toCharArray(),
                caB.certificate.toPem(),
            ),
        )
        assertTrue("(c) reconfigured to new CA → MutualTLS", repo.currentSslConfig() is SslConfig.MutualTLS)

        val serverCert = newSigned(caB, cn = "server", san = "localhost")
        val serverHs = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .addTrustedCertificate(caB.certificate)
            .build()
        server.useHttps(serverHs.sslContext().socketFactory, false)
        server.enqueue(MockResponse().setBody("ok-b"))
        val res = repo.tokenStreamClient(hostB)
            .newCall(Request.Builder().url(server.url("/").toString()).build())
            .execute()
        assertEquals("(c) handshake via the new CA succeeds (live SSL state read)", 200, res.code)
        res.close()
    }
}
