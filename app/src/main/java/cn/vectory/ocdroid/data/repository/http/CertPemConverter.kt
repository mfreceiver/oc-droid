package cn.vectory.ocdroid.data.repository.http

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * §mtls-paste: 把粘贴的 PEM 文本（客户端证书 + 无口令 PKCS8 私钥）转成空口令
 * PKCS12 字节，供现有 [buildMutualTlsConfig] 的 PKCS12 路径直接消费——下游零改动。
 *
 * 仅纯 JDK（主 classpath 无 BouncyCastle）：
 *  - 证书：[CertificateFactory] "X.509"（按 CERTIFICATE 块逐个 generateCertificate，
 *    喂已剥离装甲的 DER；避免 generateCertificates(plural) 在合并粘贴时把 PRIVATE KEY
 *    块当证书解析而抛 CertificateParsingException）。
 *  - 私钥：仅接受无口令 PKCS8（`-----BEGIN PRIVATE KEY-----`）。拒绝加密 PKCS8
 *    （`ENCRYPTED PRIVATE KEY`）与传统 PKCS1/SEC1（`RSA/EC PRIVATE KEY`，纯 JDK
 *    无法解析）——在 base64 解码 *之前* 即按 type 拒绝，抛 [IllegalArgumentException]
 *    引导用户用 openssl 转 PKCS8（避免裸 base64 / 低级解析错误泄露给用户）。
 *  - 密钥算法取自证书公钥（RSA/EC/Ed25519…，minSdk 34 支持 EdDSA KeyFactory）。
 *  - §C2 (cgpt blocker2): setKeyEntry 前做一次 Signature 签名/验证往返，确认私钥
 *    与叶子证书公钥配对——不配对则抛 [IllegalArgumentException("私钥与证书不匹配")]。
 *
 * @throws IllegalArgumentException 未找到证书 / 私钥缺失或多余 / 私钥类型不受支持 /
 *   私钥与证书不匹配 / 密钥算法或格式在当前设备不支持。
 */
internal fun pemMaterialToP12(clientPem: String): ByteArray {
    // §item1 (cgpt#3+grok#5+glm#3): 单次 parse；body 延迟到过滤后再 base64 解码，
    // 这样不支持的 PRIVATE KEY 类型（ENCRYPTED/RSA/EC）在解码前就被 require 拒绝。
    val blocks = parsePemBlocks(clientPem)

    val cf = CertificateFactory.getInstance("X.509")
    val certBlocks = blocks.filter { it.type == "CERTIFICATE" }
    require(certBlocks.isNotEmpty()) { "未找到证书 (-----BEGIN CERTIFICATE-----)" }
    val certs = certBlocks.map { block ->
        cf.generateCertificate(ByteArrayInputStream(decodeBase64(block.body)))
    }

    val keyBlocks = blocks.filter { it.type.endsWith("PRIVATE KEY") }
    require(keyBlocks.size == 1) { "需要且仅需要一份私钥，找到 ${keyBlocks.size} 份" }
    val keyBlock = keyBlocks.first()
    // §item1: 在解码 key body *之前* 校验类型——ENCRYPTED/RSA/EC PRIVATE KEY 在此
    // 被拒绝，绝不会触发其 body 的 base64 解码（避免低级错误泄露给用户）。
    require(keyBlock.type == "PRIVATE KEY") {
        "仅支持无口令 PKCS8 私钥 (BEGIN PRIVATE KEY)；不支持 ${keyBlock.type}，请用 openssl 转无口令 PKCS8"
    }

    val leafCert = certs.first()
    // §C3: 把 NoSuchAlgorithmException / §item3 InvalidKeySpecException 转成有针对性
    // 的 IllegalArgumentException——EdDSA 等算法在低 minSdk / 缺 Provider 的设备上
    // KeyFactory 可能找不到或拒绝该 spec；裸异常会让用户看到不知所云的栈轨迹。
    val key = try {
        KeyFactory.getInstance(leafCert.publicKey.algorithm)
            .generatePrivate(PKCS8EncodedKeySpec(decodeBase64(keyBlock.body)))
    } catch (e: java.security.NoSuchAlgorithmException) {
        throw IllegalArgumentException("客户端证书密钥算法 ${leafCert.publicKey.algorithm} 在当前设备不支持")
    } catch (e: InvalidKeySpecException) {
        throw IllegalArgumentException("私钥格式或算法不受当前设备支持（需无口令 PKCS8）")
    }

    // §C2 (cgpt blocker2 + grok#3 + glm#2): setKeyEntry 前验证私钥与叶子证书配对。
    // 用一次性随机挑战做签名→验证往返；不配对 → IllegalArgumentException 中止
    // （否则 PKCS12 能写入但 TLS 握手时才失败，错误更难定位）。
    val sigAlg = when (leafCert.publicKey) {
        is RSAPublicKey -> "SHA256withRSA"
        is ECPublicKey -> "SHA256withECDSA"
        else -> leafCert.publicKey.algorithm  // Ed25519/Ed448（JDK 15+ / minSdk 34 OK）
    }
    val sig = Signature.getInstance(sigAlg)
    val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
    sig.initSign(key)
    sig.update(challenge)
    val signed = sig.sign()
    sig.initVerify(leafCert.publicKey)
    sig.update(challenge)
    require(sig.verify(signed)) { "私钥与证书不匹配" }

    val ks = KeyStore.getInstance("PKCS12").apply { load(null, CharArray(0)) }
    ks.setKeyEntry("client", key, CharArray(0), certs.toTypedArray())
    return ByteArrayOutputStream().use { out ->
        ks.store(out, CharArray(0))
        out.toByteArray()
    }
}

/**
 * §item1: 仅存 type + 原始 base64 文本（不解码）——推迟解码到过滤之后，使不支持
 * 的 PRIVATE KEY 类型在 require 阶段被拒绝，不触碰其 body。
 */
private data class PemBlock(val type: String, val body: String)

/** §item1: 集中的 base64 解码（去空白后解码）。 */
private fun decodeBase64(body: String): ByteArray =
    Base64.getDecoder().decode(body.filterNot { it.isWhitespace() })

private val PEM_BLOCK_REGEX = Regex(
    "-----BEGIN ([A-Z0-9 ]+)-----(.*?)-----END \\1-----",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
)

private fun parsePemBlocks(text: String): List<PemBlock> =
    PEM_BLOCK_REGEX.findAll(text).map { match ->
        PemBlock(
            // §C4: 统一 uppercase，使下游 endsWith("PRIVATE KEY") / == "PRIVATE KEY"
            // 对小写 header（如 "private key"）也命中。正则已是 IGNORE_CASE。
            type = match.groupValues[1].trim().uppercase(),
            body = match.groupValues[2]
        )
    }.toList()
