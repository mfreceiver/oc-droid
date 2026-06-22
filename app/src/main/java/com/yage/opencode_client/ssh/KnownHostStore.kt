package com.yage.opencode_client.ssh

import com.yage.opencode_client.util.SettingsManager
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KnownHostStore @Inject constructor(
    private val settingsManager: SettingsManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun identity(host: String, port: Int): String = "${host.trim().lowercase()}:$port"

    @Synchronized
    fun fingerprint(host: String, port: Int): String? = knownHosts()[identity(host, port)]

    @Synchronized
    fun checkOrTrust(host: String, port: Int, key: ByteArray): KnownHostCheck {
        val fingerprint = sha256Fingerprint(key)
        val id = identity(host, port)
        val current = knownHosts().toMutableMap()
        val expected = current[id]
        return when {
            expected == null -> {
                current[id] = fingerprint
                save(current)
                KnownHostCheck.TrustedFirstUse(fingerprint)
            }
            expected == fingerprint -> KnownHostCheck.Match(fingerprint)
            else -> KnownHostCheck.Mismatch(expected = expected, actual = fingerprint)
        }
    }

    @Synchronized
    fun clear(host: String, port: Int) {
        val current = knownHosts().toMutableMap()
        current.remove(identity(host, port))
        save(current)
    }

    private fun knownHosts(): Map<String, String> {
        val raw = settingsManager.knownHostsJson ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrElse { emptyMap() }
    }

    private fun save(hosts: Map<String, String>) {
        settingsManager.knownHostsJson = json.encodeToString(hosts)
    }

    companion object {
        fun sha256Fingerprint(key: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            return "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
        }
    }
}

sealed class KnownHostCheck {
    data class TrustedFirstUse(val fingerprint: String) : KnownHostCheck()
    data class Match(val fingerprint: String) : KnownHostCheck()
    data class Mismatch(val expected: String, val actual: String) : KnownHostCheck()
}
