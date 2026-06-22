package com.yage.opencode_client.ssh

import com.yage.opencode_client.util.SettingsManager
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SSHKeyManager @Inject constructor(
    private val settingsManager: SettingsManager
) {
    fun ensureKeyPair(): String {
        val existingPublic = settingsManager.sshPublicKey
        val existingPrivate = settingsManager.sshPrivateKeyPem
        if (!existingPublic.isNullOrBlank() && !existingPrivate.isNullOrBlank()) {
            return existingPublic
        }
        return rotateKey()
    }

    fun publicKey(): String? = settingsManager.sshPublicKey

    fun privateKeyBytes(): ByteArray? = settingsManager.sshPrivateKeyPem?.toByteArray(Charsets.UTF_8)

    fun rotateKey(): String {
        ensureBouncyCastleProvider()
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val seed = privateKey.encoded
        val public = privateKey.generatePublicKey().encoded
        val publicBlob = sshString(ALGORITHM) + sshString(public)
        val publicKeyLine = "$ALGORITHM ${Base64.getEncoder().encodeToString(publicBlob)} $COMMENT"
        val privatePem = openSshPrivateKey(seed, public, publicBlob)
        settingsManager.sshPrivateKeyPem = privatePem
        settingsManager.sshPublicKey = publicKeyLine
        return publicKeyLine
    }

    private fun ensureBouncyCastleProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    companion object {
        private const val ALGORITHM = "ssh-ed25519"
        private const val COMMENT = "opencode-android"

        private fun openSshPrivateKey(seed: ByteArray, public: ByteArray, publicBlob: ByteArray): String {
            val check = SecureRandom().nextInt()
            val privateBlob = ByteArrayOutputStream().apply {
                writeInt(check)
                writeInt(check)
                writeSshString(ALGORITHM.toByteArray(Charsets.US_ASCII))
                writeSshString(public)
                writeSshString(seed + public)
                writeSshString(COMMENT.toByteArray(Charsets.UTF_8))
                val padding = 8 - (size() % 8)
                repeat(if (padding == 8) 0 else padding) { write(it + 1) }
            }.toByteArray()

            val envelope = ByteArrayOutputStream().apply {
                write("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII))
                writeSshString("none".toByteArray(Charsets.US_ASCII))
                writeSshString("none".toByteArray(Charsets.US_ASCII))
                writeSshString(ByteArray(0))
                writeInt(1)
                writeSshString(publicBlob)
                writeSshString(privateBlob)
            }.toByteArray()

            val encoded = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(envelope)
            return "-----BEGIN OPENSSH PRIVATE KEY-----\n$encoded\n-----END OPENSSH PRIVATE KEY-----\n"
        }

        private fun sshString(value: String): ByteArray = sshString(value.toByteArray(Charsets.US_ASCII))

        private fun sshString(value: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            writeSshString(value)
        }.toByteArray()

        private fun ByteArrayOutputStream.writeSshString(value: ByteArray) {
            writeInt(value.size)
            write(value)
        }

        private fun ByteArrayOutputStream.writeInt(value: Int) {
            write(byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte()
            ))
        }
    }
}
