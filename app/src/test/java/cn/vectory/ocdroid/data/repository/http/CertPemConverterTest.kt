package cn.vectory.ocdroid.data.repository.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyStore

class CertPemConverterTest {
    private fun testPem(): String =
        javaClass.getResourceAsStream("/mtls_test_client.pem")!!.use { it.readBytes() }
            .toString(Charsets.UTF_8)

    private fun testPemB(): String =
        javaClass.getResourceAsStream("/mtls_test_client_b.pem")!!.use { it.readBytes() }
            .toString(Charsets.UTF_8)

    /**
     * §cgpt-reval 🟡: 验异常 *消息文案* 而非仅类型——未来若更早的错抛同类型，
     * 仅验类型的用例会误绿。JUnit 4.13.2 已有 assertThrows。
     */
    private fun assertIaeMessage(pem: String, expectedSubstring: String) {
        val ex = assertThrows(IllegalArgumentException::class.java) { pemMaterialToP12(pem) }
        assertTrue("actual message: ${ex.message}", ex.message!!.contains(expectedSubstring))
    }

    @Test
    fun pemToP12_roundTrip_oneKeyEntry_matchingCert() {
        val p12Bytes = pemMaterialToP12(testPem())
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(p12Bytes), CharArray(0))
        }
        val keyAliases = ks.aliases().toList().filter { ks.isKeyEntry(it) }
        assertEquals(1, keyAliases.size)
        val alias = keyAliases.first()
        val key = ks.getKey(alias, CharArray(0))
        val chain = ks.getCertificateChain(alias)
        assertNotNull(chain)
        assertTrue(chain!!.isNotEmpty())
        assertEquals(chain[0].publicKey.algorithm, key.algorithm)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingCert_rejected() {
        // has a PRIVATE KEY block but no CERTIFICATE block
        pemMaterialToP12(
            "-----BEGIN PRIVATE KEY-----\nMIIBVwIBADANBgkqhkiG9w0BAQEFAASCAQAwggEKAgEAAoGBAQ\n-----END PRIVATE KEY-----\n"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun encryptedPrivateKey_rejected() {
        val pem = testPem()
            .replace("BEGIN PRIVATE KEY", "BEGIN ENCRYPTED PRIVATE KEY")
            .replace("END PRIVATE KEY", "END ENCRYPTED PRIVATE KEY")
        pemMaterialToP12(pem)
    }

    // §C9: 边界用例——空 / 仅证书 / 仅私钥，均应 IllegalArgumentException。

    @Test(expected = IllegalArgumentException::class)
    fun blankInput_rejected() {
        pemMaterialToP12("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun certOnly_rejected() {
        // only the CERTIFICATE block (no PRIVATE KEY) -> 0 keys
        val pem = testPem()
        val certOnly = pem.substring(
            pem.indexOf("-----BEGIN CERTIFICATE-----"),
            pem.indexOf("-----END CERTIFICATE-----") + "-----END CERTIFICATE-----".length
        )
        pemMaterialToP12(certOnly)
    }

    @Test(expected = IllegalArgumentException::class)
    fun keyOnly_rejected() {
        // only the PRIVATE KEY block (no CERTIFICATE) -> no cert
        val pem = testPem()
        val keyOnly = pem.substring(
            pem.indexOf("-----BEGIN PRIVATE KEY-----"),
            pem.indexOf("-----END PRIVATE KEY-----") + "-----END PRIVATE KEY-----".length
        )
        pemMaterialToP12(keyOnly)
    }

    // §item12 (cgpt#10 + grok#9): 配对/格式/大小写/重复块 用例。

    @Test
    fun mismatch_rejected() {
        // fixture A 的证书 + fixture B 的私钥（不配对）→ Signature 往返验证失败。
        val pemA = testPem()
        val pemB = testPemB()
        val certA = pemA.substring(
            pemA.indexOf("-----BEGIN CERTIFICATE-----"),
            pemA.indexOf("-----END CERTIFICATE-----") + "-----END CERTIFICATE-----".length
        )
        val keyB = pemB.substring(
            pemB.indexOf("-----BEGIN PRIVATE KEY-----"),
            pemB.indexOf("-----END PRIVATE KEY-----") + "-----END PRIVATE KEY-----".length
        )
        assertIaeMessage(certA + "\n" + keyB, "私钥与证书不匹配")
    }

    @Test
    fun pkcs1Key_rejected() {
        // 传统 PKCS1（BEGIN RSA PRIVATE KEY）+ 证书 → 应在解码前按 type 拒绝，
        // 抛 IllegalArgumentException（而非裸 base64/低级解析错误）。
        val pem = testPem()
        val cert = pem.substring(
            pem.indexOf("-----BEGIN CERTIFICATE-----"),
            pem.indexOf("-----END CERTIFICATE-----") + "-----END CERTIFICATE-----".length
        )
        val pkcs1Key = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIBVwIBADANBgkqhkiG9w0BAQEFAASCAQAwggEKAgEAAoGBAQ
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        assertIaeMessage(cert + "\n" + pkcs1Key, "RSA PRIVATE KEY")
    }

    @Test
    fun lowercaseHeader_roundTrip() {
        // §C4: 小写 header（begin/end）也应被 IGNORE_CASE 正则 + uppercase 规范化接受。
        val pem = testPem()
            .replace("BEGIN CERTIFICATE", "begin certificate")
            .replace("END CERTIFICATE", "end certificate")
            .replace("BEGIN PRIVATE KEY", "begin private key")
            .replace("END PRIVATE KEY", "end private key")
        // 应成功（不抛），且产出可被 PKCS12 读回的有效 p12。
        val p12Bytes = pemMaterialToP12(pem)
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(p12Bytes), CharArray(0))
        }
        assertEquals(1, ks.aliases().toList().count { ks.isKeyEntry(it) })
    }

    @Test
    fun multipleKeys_rejected() {
        // 证书 + 两份私钥（重复 key 块）→ require(size==1) 失败。
        val pem = testPem()
        val cert = pem.substring(
            pem.indexOf("-----BEGIN CERTIFICATE-----"),
            pem.indexOf("-----END CERTIFICATE-----") + "-----END CERTIFICATE-----".length
        )
        val key = pem.substring(
            pem.indexOf("-----BEGIN PRIVATE KEY-----"),
            pem.indexOf("-----END PRIVATE KEY-----") + "-----END PRIVATE KEY-----".length
        )
        // 同一份 key 出现两次 → keyBlocks.size == 2
        assertIaeMessage(cert + "\n" + key + "\n" + key, "找到 2 份")
    }
}
