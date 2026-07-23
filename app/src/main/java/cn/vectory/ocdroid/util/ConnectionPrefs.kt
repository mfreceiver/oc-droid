package cn.vectory.ocdroid.util

import android.content.SharedPreferences
import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial
import java.util.Base64

/**
 * L4b domain split of [SettingsManager] — CONNECTION + ESP-SECRET domain.
 *
 * Owns the persisted server-connection scalars and ALL encrypted per-host
 * secrets: legacy direct-form credentials, host-profile JSON, the active
 * profile id, the effective-connection-source marker, per-host basic-auth
 * passwords, per-host tunnel passwords, and per-host mTLS client-cert
 * triplets (p12 / password / CA).
 *
 * §L4b ESP-key ownership: this class is the SOLE owner of read/write for
 * every encrypted secret key. The crypto path is byte-identical to the
 * pre-split [SettingsManager] — same single `EncryptedSharedPreferences`
 * instance (created once in [SettingsManager] and injected here), same key
 * strings, same `edit().apply()` cadence, same Base64 codec for byte
 * payloads, same null-vs-blank clearing semantics. A user upgrading across
 * the refactor sees identical values.
 *
 * Backing store: the shared ESP file `ocdroid_settings` (the
 * `encryptedPrefs` instance owned by [SettingsManager]). NO key renames.
 */
internal class ConnectionPrefs(
    private val encryptedPrefs: SharedPreferences,
) {
    var serverUrl: String
        get() = encryptedPrefs.getString(KEY_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = encryptedPrefs.edit().putString(KEY_SERVER_URL, value).apply()

    var username: String?
        get() = encryptedPrefs.getString(KEY_USERNAME, null)
        set(value) = encryptedPrefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String?
        get() = encryptedPrefs.getString(KEY_PASSWORD, null)
        set(value) = encryptedPrefs.edit().putString(KEY_PASSWORD, value).apply()

    var hostProfilesJson: String?
        get() = encryptedPrefs.getString(KEY_HOST_PROFILES, null)
        set(value) = encryptedPrefs.edit().putString(KEY_HOST_PROFILES, value).apply()

    var currentHostProfileId: String?
        get() = encryptedPrefs.getString(KEY_CURRENT_HOST_PROFILE_ID, null)
        set(value) = encryptedPrefs.edit().putString(KEY_CURRENT_HOST_PROFILE_ID, value).apply()

    /** Explicit Manual/Profile bootstrap source; null means legacy migration is pending. */
    var effectiveConnectionSourceMarker: String?
        get() = encryptedPrefs.getString(KEY_EFFECTIVE_CONNECTION_SOURCE, null)
        set(value) = encryptedPrefs.edit().apply {
            if (value == null) remove(KEY_EFFECTIVE_CONNECTION_SOURCE)
            else putString(KEY_EFFECTIVE_CONNECTION_SOURCE, value)
        }.apply()

    fun basicAuthPassword(passwordId: String): String? {
        if (passwordId == LEGACY_BASIC_AUTH_PASSWORD_ID) return password
        return encryptedPrefs.getString(basicAuthPasswordKey(passwordId), null)
    }

    fun setBasicAuthPassword(passwordId: String, value: String?) {
        encryptedPrefs.edit().apply {
            if (value.isNullOrBlank()) remove(basicAuthPasswordKey(passwordId)) else putString(basicAuthPasswordKey(passwordId), value)
        }.apply()
    }

    fun getTunnelPassword(id: String): String? {
        return encryptedPrefs.getString(tunnelPasswordKey(id), null)
    }

    fun setTunnelPassword(id: String, password: String?) {
        encryptedPrefs.edit().apply {
            if (password.isNullOrBlank()) remove(tunnelPasswordKey(id)) else putString(tunnelPasswordKey(id), password)
        }.apply()
    }

    fun clearTunnelPassword(id: String) {
        encryptedPrefs.edit().remove(tunnelPasswordKey(id)).apply()
    }

    // ── §2.3: mTLS 客户端证书（PKCS12 / 密码 / CA）存取 ─────────────────────
    //
    // 三个分项 key（`client_cert_p12_<id>` / `client_cert_pw_<id>` /
    // `client_cert_ca_<id>`）+ 一个原子批量写 [saveClientCert]。p12 / CA 走
    // ByteArray + java.util.Base64（与 OpenCodeRepository 一致；JVM 可单测）。
    // 密码用 `== null` 判清除（非 isNullOrBlank）——允许空串密码
    // （openssl `-password pass:` 合法）。CA 用 ByteArray（PEM / DER 都二进制
    // 安全；CertificateFactory 都吃）——readText() 对 DER 会坏。

    fun getClientCertP12(id: String): ByteArray? =
        encryptedPrefs.getString(clientCertP12Key(id), null)?.let { Base64.getDecoder().decode(it) }

    fun setClientCertP12(id: String, bytes: ByteArray?) {
        encryptedPrefs.edit().apply {
            if (bytes == null) remove(clientCertP12Key(id))
            else putString(clientCertP12Key(id), Base64.getEncoder().encodeToString(bytes))
        }.apply()
    }

    /** 用 `== null` 判清除，允许空字符串密码（openssl `-password pass:` 合法）。 */
    fun getClientCertPassword(id: String): String? =
        encryptedPrefs.getString(clientCertPasswordKey(id), null)

    fun setClientCertPassword(id: String, value: String?) {
        encryptedPrefs.edit().apply {
            if (value == null) remove(clientCertPasswordKey(id))
            else putString(clientCertPasswordKey(id), value)
        }.apply()
    }

    /** CA 用 ByteArray（PEM 或 DER 都二进制安全；CertificateFactory 都吃）。 */
    fun getClientCertCa(id: String): ByteArray? =
        encryptedPrefs.getString(clientCertCaKey(id), null)?.let { Base64.getDecoder().decode(it) }

    fun setClientCertCa(id: String, bytes: ByteArray?) {
        encryptedPrefs.edit().apply {
            if (bytes == null) remove(clientCertCaKey(id))
            else putString(clientCertCaKey(id), Base64.getEncoder().encodeToString(bytes))
        }.apply()
    }

    fun clearClientCert(id: String) {
        encryptedPrefs.edit()
            .remove(clientCertP12Key(id))
            .remove(clientCertPasswordKey(id))
            .remove(clientCertCaKey(id))
            .apply()
    }

    /**
     * §2.3 / v3-gpter R2#2: 单次原子提交 p12 + 密码 + CA（三个独立 apply() 非原子，
     * 崩溃 / 并发可半写 → 悬空引用）。null 表清除该项。
     */
    fun saveClientCert(id: String, p12: ByteArray?, password: String?, ca: ByteArray?) {
        encryptedPrefs.edit().apply {
            if (p12 == null) remove(clientCertP12Key(id))
            else putString(clientCertP12Key(id), Base64.getEncoder().encodeToString(p12))
            if (password == null) remove(clientCertPasswordKey(id))
            else putString(clientCertPasswordKey(id), password)
            if (ca == null) remove(clientCertCaKey(id))
            else putString(clientCertCaKey(id), Base64.getEncoder().encodeToString(ca))
        }.apply()
    }

    /**
     * §2.3: 载入 [clientCertId] 对应的完整 [ClientCertMaterial]。p12 或 密码
     * 缺失（null）→ 返回 null（空字符串密码 != null，能通过）。CA 缺失 →
     * [ClientCertMaterial.caBytes] = null（平台 CA 模式）。
     */
    fun loadClientCertMaterial(clientCertId: String): ClientCertMaterial? {
        val p12 = getClientCertP12(clientCertId) ?: return null
        val pw = getClientCertPassword(clientCertId) ?: return null
        return ClientCertMaterial(p12, pw.toCharArray(), getClientCertCa(clientCertId))
    }

    companion object {
        const val DEFAULT_SERVER = "http://localhost:4096"
        const val LEGACY_BASIC_AUTH_PASSWORD_ID = "legacy_basic_auth_password"

        internal const val KEY_SERVER_URL = "server_url"
        internal const val KEY_USERNAME = "username"
        internal const val KEY_PASSWORD = "password"
        internal const val KEY_HOST_PROFILES = "host_profiles_json"
        internal const val KEY_CURRENT_HOST_PROFILE_ID = "current_host_profile_id"
        internal const val KEY_EFFECTIVE_CONNECTION_SOURCE = "effective_connection_source"

        internal fun basicAuthPasswordKey(passwordId: String): String = "basic_auth_password_$passwordId"
        internal fun tunnelPasswordKey(id: String): String = "tunnel_password_$id"

        // §2.3: mTLS 客户端证书 key 后缀（与 basic_auth_password_ / tunnel_password_ 同构）。
        internal fun clientCertP12Key(id: String): String = "client_cert_p12_$id"
        internal fun clientCertPasswordKey(id: String): String = "client_cert_pw_$id"
        internal fun clientCertCaKey(id: String): String = "client_cert_ca_$id"
    }
}
