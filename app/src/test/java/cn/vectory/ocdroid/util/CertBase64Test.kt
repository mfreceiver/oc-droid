package cn.vectory.ocdroid.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Unit coverage for `util/CertBase64.kt` (mTLS clipboard-import redesign §A —
 * `docs/superpowers/specs/2026-07-10-mtls-clipboard-import-design.md`, Testing).
 *
 * Fixtures are generated **programmatically inside the test** (no external
 * files) using pure public JDK only — a hand-rolled minimal DER encoder mints a
 * self-signed X.509 cert, which is then stored into a passwordless PKCS12 via
 * `KeyStore.getInstance("PKCS12")`. No BouncyCastle, no `sun.security.x509`
 * (the latter is module-restricted on the JDK 21 host and explicitly out of
 * scope). See [buildFixtures] / [Der] below.
 */
class CertBase64Test {

    // ---------------------------------------------------------------- sanitize

    @Test
    fun `pure base64 is returned unchanged`() {
        val raw = "U29tZUNlcnRGaXh0dXJlRGF0YSsrKw=="
        assertEquals(raw, sanitizeBase64(raw))
    }

    @Test
    fun `newlines spaces and CR inside a pasted base64 blob are removed`() {
        // The design's real input is pure base64 of the DER (upstream bundles
        // base64-encoded material), often pasted with wrapped newlines/spaces.
        assertEquals(
            "U29tZUNlcnRGaXh0dXJlRGF0YSsrKw==",
            sanitizeBase64("U29tZUNlcnRGaXh0\ndXJlRGF0YSsr\r\nKw== "),
        )
    }

    @Test
    fun `PEM armor headers and footers are stripped, leaving the pure base64 body`() {
        // sanitizeBase64 strips the `-----BEGIN/END …-----` armor tokens
        // (including the label WORDS — BEGIN/END/CERTIFICATE — which are
        // base64-alphabet letters) BEFORE the char-filter runs, so a pasted PEM
        // reduces to just its decodable base64 body. Pure-base64 input carries
        // no armor tokens and is unaffected (covered by the test above).
        val body = "U29tZUNlcnQ="
        val pem = "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----"
        assertEquals(body, sanitizeBase64(pem))
    }

    @Test
    fun `non-alphabet junk is stripped`() {
        assertEquals("dGVzdA==", sanitizeBase64("\t!dGVzdA==?\r\n#"))
    }

    // ------------------------------------------------------------ decodeBase64

    @Test
    fun `valid base64 round-trips to the expected bytes`() {
        val payload = "Hello, mTLS!".toByteArray()
        val encoded = Base64.getEncoder().encodeToString(payload)
        val decoded = decodeBase64OrNull(encoded)
        assertNotNull(decoded)
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun `non-base64 garbage sanitizes to empty and returns null`() {
        assertNull(decodeBase64OrNull("@#\$%^&*!"))
    }

    @Test
    fun `empty input returns null`() {
        assertNull(decodeBase64OrNull(""))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(decodeBase64OrNull("   \n\t\r"))
    }

    @Test
    fun `missing padding still decodes via MIME leniency`() {
        val payload = "pad-test".toByteArray()
        val padded = Base64.getEncoder().encodeToString(payload) // "cGFkLXRlc3Q="
        val noPad = padded.trimEnd('=')
        assertTrue("precondition: padding was actually stripped", noPad.length != padded.length)
        val decoded = decodeBase64OrNull(noPad)
        assertNotNull(decoded)
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun `a full pasted PEM decodes back to the original DER bytes`() {
        // Realistic pasted-PEM input: the programmatically-minted cert's base64
        // body, wrapped at 64 chars/line, between `-----BEGIN/END CERTIFICATE-----`
        // armor. sanitizeBase64 drops the armor + newlines leaving a decodable
        // body; decodeBase64OrNull then yields the original DER.
        val body = Base64.getEncoder().encodeToString(fixtures.certDer)
        val wrapped = body.chunked(64).joinToString("\n")
        val pem = "-----BEGIN CERTIFICATE-----\n$wrapped\n-----END CERTIFICATE-----"
        val decoded = decodeBase64OrNull(pem)
        assertNotNull(decoded)
        assertArrayEquals(fixtures.certDer, decoded)
    }

    // ---------------------------------------------------------- parseCaCertOrNull

    @Test
    fun `real cert bytes parse to a non-null X509 with the expected CN`() {
        val parsed = parseCaCertOrNull(fixtures.certDer)
        assertNotNull(parsed)
        assertEquals("CN=ocdroid-test-client", certSubjectOrNull(parsed!!))
    }

    @Test
    fun `random bytes are not a cert and return null`() {
        assertNull(parseCaCertOrNull(ByteArray(32) { it.toByte() }))
    }

    // -------------------------------------------------------- loadClientP12OrNull

    @Test
    fun `real passwordless p12 loads to a keystore with a key entry`() {
        val ks = loadClientP12OrNull(fixtures.p12Bytes)
        assertNotNull(ks)
        val aliases = ks!!.aliases().toList()
        assertTrue(
            "p12 must contain at least one key entry with a cert chain",
            aliases.any { ks.isKeyEntry(it) && ((ks.getCertificateChain(it)?.size ?: 0) > 0) },
        )
    }

    @Test
    fun `garbage is not a p12 and returns null`() {
        assertNull(loadClientP12OrNull(ByteArray(64) { it.toByte() }))
    }

    @Test
    fun `a p12 with two key entries is rejected (exactly-one-key contract)`() {
        // buildMutualTlsConfig requires EXACTLY one key entry; loadClientP12OrNull
        // must mirror that so a bundle that "imports" is actually usable at
        // Test/Save. Build a PKCS12 carrying two distinct key entries → must
        // return null. The single-key fixture (fixtures.p12Bytes) is covered by
        // the test above and is asserted to stay non-null (not weakened).
        val (cert1, kp1) = makeSelfSignedCert("ocdroid-test-client")
        val (cert2, kp2) = makeSelfSignedCert("ocdroid-test-second")
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, CharArray(0))
            setKeyEntry("client", kp1.private, CharArray(0), arrayOf(cert1))
            setKeyEntry("second", kp2.private, CharArray(0), arrayOf(cert2))
        }
        val baos = ByteArrayOutputStream()
        ks.store(baos, CharArray(0))
        assertNull(loadClientP12OrNull(baos.toByteArray()))
    }

    @Test
    fun `a p12 with one private-key entry plus one secret-key entry is rejected`() {
        // Round-2 review (gpter): the previous count logic only counted key
        // entries that ALREADY had a non-empty chain, so a bundle with one
        // private-key entry (chain-bearing) + one secret-key entry
        // (isKeyEntry=true, chain-less) counted as "1" here but as "2" in
        // buildMutualTlsConfig (which counts every isKeyEntry alias) — so it
        // showed Imported at import but was rejected at Test/Save. The fix
        // aligns the count: count ALL isKeyEntry aliases. This fixture (one
        // private-key-with-chain + one AES SecretKeyEntry) must therefore now
        // return null. Built with pure JDK javax.crypto.KeyGenerator +
        // KeyStore.SecretKeyEntry, no BouncyCastle.
        val (cert, kp) = makeSelfSignedCert("ocdroid-test-client")
        val aesKey = javax.crypto.KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, CharArray(0))
            setKeyEntry("client", kp.private, CharArray(0), arrayOf(cert))
            setEntry(
                "secret",
                KeyStore.SecretKeyEntry(aesKey),
                KeyStore.PasswordProtection(CharArray(0)),
            )
        }
        val baos = ByteArrayOutputStream()
        ks.store(baos, CharArray(0))
        assertNull(loadClientP12OrNull(baos.toByteArray()))
    }

    // --------------------------------------------------------- certSubjectOrNull

    @Test
    fun `certSubjectOrNull returns the CN label`() {
        assertEquals("CN=ocdroid-test-client", certSubjectOrNull(fixtures.cert))
    }

    // ------------------------------------------------------------- fixtures (pure JDK)

    private class Fixtures(
        val cert: X509Certificate,
        val certDer: ByteArray,
        val p12Bytes: ByteArray,
    )

    private companion object {
        /** Built once per test-class run (keygen + PKCS12 KDF are the costly bits). */
        private val fixtures: Fixtures by lazy { buildFixtures() }

        /**
         * Mints a self-signed RSA-2048 X.509 cert via a hand-rolled DER encoder,
         * then stores its private key + cert chain into a passwordless PKCS12.
         * Returns the cert (reused as the "CA" fixture per the spec note), its
         * DER encoding, and the PKCS12 bytes.
         */
        private fun buildFixtures(): Fixtures {
            val (cert, keyPair) = makeSelfSignedCert("ocdroid-test-client")

            val ks = KeyStore.getInstance("PKCS12").apply {
                load(null, CharArray(0))
                setKeyEntry("client", keyPair.private, CharArray(0), arrayOf(cert))
            }
            val baos = ByteArrayOutputStream()
            ks.store(baos, CharArray(0))
            return Fixtures(cert = cert, certDer = cert.encoded, p12Bytes = baos.toByteArray())
        }

        private fun makeSelfSignedCert(cn: String): Pair<X509Certificate, KeyPair> {
            val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            val keyPair = kpg.generateKeyPair()

            // SubjectPublicKeyInfo (X.509 format) straight from the public key.
            val spki = keyPair.public.encoded

            val name = Der.seq(
                listOf(
                    Der.set(
                        listOf(
                            Der.seq(listOf(Der.oid(2, 5, 4, 3), Der.utf8String(cn))),
                        ),
                    ),
                ),
            )

            // sha256WithRSAEncryption (1.2.840.113549.1.1.11) with NULL params.
            val sigAlgId = Der.seq(listOf(Der.oid(1, 2, 840, 113549, 1, 1, 11), Der.nullVal()))

            val now = System.currentTimeMillis()
            val validity = Der.seq(
                listOf(
                    Der.utcTime(utcTimeString(now)),
                    Der.utcTime(utcTimeString(now + 365L * 24 * 3600 * 1000)),
                ),
            )

            val tbs = Der.seq(
                listOf(
                    Der.tlv(0xA0, Der.integer(2)), // [0] EXPLICIT version = v3
                    Der.integer(BigInteger.valueOf(now)), // serialNumber
                    sigAlgId, // signature algorithm
                    name, // issuer
                    validity,
                    name, // subject
                    spki, // subjectPublicKeyInfo
                ),
            )

            val sig = Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(tbs)
            }
            val certDer = Der.seq(
                listOf(
                    tbs,
                    sigAlgId,
                    Der.bitString(sig.sign()),
                ),
            )

            // Round-trip through the real parser to validate our DER; this also
            // produces the X509Certificate the rest of the test asserts on.
            val x509 = CertificateFactory.getInstance("X.509")
                .generateCertificate(certDer.openStream()) as X509Certificate
            return x509 to keyPair
        }

        private fun utcTimeString(epochMs: Long): String {
            val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = epochMs
            fun two(v: Int) = v.toString().padStart(2, '0')
            val yy = two(cal.get(Calendar.YEAR) % 100)
            val mm = two(cal.get(Calendar.MONTH) + 1)
            val dd = two(cal.get(Calendar.DAY_OF_MONTH))
            val hh = two(cal.get(Calendar.HOUR_OF_DAY))
            val mi = two(cal.get(Calendar.MINUTE))
            val ss = two(cal.get(Calendar.SECOND))
            return "$yy$mm$dd$hh$mi${ss}Z"
        }

        // Minimal DER (ITU-T X.690) TLV encoder — pure java.io.ByteArrayOutputStream.
        private object Der {
            fun tlv(tag: Int, content: ByteArray): ByteArray {
                val out = ByteArrayOutputStream()
                out.write(tag)
                writeLength(out, content.size)
                out.write(content)
                return out.toByteArray()
            }

            private fun writeLength(out: ByteArrayOutputStream, len: Int) {
                when {
                    len < 0x80 -> out.write(len)
                    len <= 0xFF -> { out.write(0x81); out.write(len) }
                    len <= 0xFFFF -> {
                        out.write(0x82)
                        out.write((len ushr 8) and 0xFF)
                        out.write(len and 0xFF)
                    }
                    else -> {
                        out.write(0x83)
                        out.write((len ushr 16) and 0xFF)
                        out.write((len ushr 8) and 0xFF)
                        out.write(len and 0xFF)
                    }
                }
            }

            private fun concat(parts: List<ByteArray>): ByteArray {
                val out = ByteArrayOutputStream()
                for (p in parts) out.write(p)
                return out.toByteArray()
            }

            fun seq(parts: List<ByteArray>) = tlv(0x30, concat(parts))
            fun set(parts: List<ByteArray>) = tlv(0x31, concat(parts))
            fun integer(value: Int) = integer(BigInteger.valueOf(value.toLong()))
            fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())
            fun nullVal() = byteArrayOf(0x05, 0x00)
            fun utf8String(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
            fun utcTime(s: String) = tlv(0x17, s.toByteArray(Charsets.US_ASCII))
            fun bitString(content: ByteArray): ByteArray {
                val c = ByteArray(content.size + 1).also { System.arraycopy(content, 0, it, 1, content.size) }
                return tlv(0x03, c) // leading 0x00 = zero unused bits
            }

            /** Encodes an OID from its arc list (e.g. 2.5.4.3 → oid(2,5,4,3)) as a tagged TLV (06 …). */
            fun oid(vararg arcs: Int): ByteArray = tlv(0x06, oidContent(arcs))

            private fun oidContent(arcs: IntArray): ByteArray {
                require(arcs.size >= 2)
                val out = ByteArrayOutputStream()
                out.write(40 * arcs[0] + arcs[1])
                for (i in 2 until arcs.size) out.write(encodeBase128(arcs[i]))
                return out.toByteArray()
            }

            private fun encodeBase128(v: Int): ByteArray {
                if (v == 0) return byteArrayOf(0)
                val tmp = ArrayList<Int>()
                var x = v
                while (x > 0) {
                    tmp.add(x and 0x7F)
                    x = x ushr 7
                }
                tmp.reverse()
                return ByteArray(tmp.size) { i ->
                    val b = tmp[i]
                    (if (i == tmp.lastIndex) b else (b or 0x80)).toByte()
                }
            }
        }

        private fun ByteArray.openStream() = java.io.ByteArrayInputStream(this)
    }
}
