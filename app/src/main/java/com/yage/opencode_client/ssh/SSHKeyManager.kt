package com.yage.opencode_client.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.yage.opencode_client.util.SettingsManager
import java.io.ByteArrayOutputStream
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
        val keyPair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 4096)
        return try {
            val privateOut = ByteArrayOutputStream()
            keyPair.writePrivateKey(privateOut)
            val publicOut = ByteArrayOutputStream()
            keyPair.writePublicKey(publicOut, "opencode-android")
            val privateKey = privateOut.toString(Charsets.UTF_8.name())
            val publicKey = publicOut.toString(Charsets.UTF_8.name()).trim()
            settingsManager.sshPrivateKeyPem = privateKey
            settingsManager.sshPublicKey = publicKey
            publicKey
        } finally {
            keyPair.dispose()
        }
    }
}
