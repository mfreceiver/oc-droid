package com.yage.opencode_client

import com.jcraft.jsch.JSch
import com.yage.opencode_client.ssh.SSHKeyManager
import com.yage.opencode_client.util.SettingsManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SSHKeyManagerTest {
    private lateinit var settings: SettingsManager
    private lateinit var manager: SSHKeyManager
    private var privateKey: String? = null
    private var publicKey: String? = null

    @Before
    fun setUp() {
        settings = mockk(relaxed = true)
        every { settings.sshPrivateKeyPem } answers { privateKey }
        every { settings.sshPrivateKeyPem = any() } answers { privateKey = firstArg(); Unit }
        every { settings.sshPublicKey } answers { publicKey }
        every { settings.sshPublicKey = any() } answers { publicKey = firstArg(); Unit }
        manager = SSHKeyManager(settings)
    }

    @Test
    fun `ensureKeyPair generates OpenSSH public key and private key`() {
        val generated = manager.ensureKeyPair()

        assertTrue(generated.startsWith("ssh-ed25519 "))
        assertTrue(generated.endsWith(" opencode-android"))
        assertNotNull(manager.privateKeyBytes())
        assertTrue(privateKey.orEmpty().contains("PRIVATE KEY"))
        JSch().addIdentity("test", manager.privateKeyBytes(), null, null)
    }

    @Test
    fun `rotateKey replaces existing public key`() {
        val first = manager.ensureKeyPair()
        val second = manager.rotateKey()

        assertNotEquals(first, second)
        assertTrue(second.startsWith("ssh-ed25519 "))
    }
}
