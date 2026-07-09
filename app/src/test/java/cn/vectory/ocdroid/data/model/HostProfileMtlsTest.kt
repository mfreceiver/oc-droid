package cn.vectory.ocdroid.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4 / v3: [HostProfile] 新字段 `mtlsEnabled` / `clientCertId` 的序列化往返 +
 * 旧 JSON 默认值覆盖（plan §2.2 "向后兼容：旧 JSON 无此字段 → false/null"）。
 *
 * 命名对应 plan §5 测试清单的 "HostProfileTest"。
 */
class HostProfileMtlsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `HostProfile round trips mtlsEnabled and clientCertId`() {
        val profile = HostProfile(
            id = "mtls-1",
            name = "mTLS Host",
            serverUrl = "https://stunnel.example.com:8443",
            mtlsEnabled = true,
            clientCertId = "cert-uuid-123",
        )
        val encoded = json.encodeToString(profile)
        assertTrue(encoded.contains("\"mtlsEnabled\":true"))
        assertTrue(encoded.contains("\"clientCertId\":\"cert-uuid-123\""))

        val decoded = json.decodeFromString<HostProfile>(encoded)
        assertEquals(profile, decoded)
        assertTrue(decoded.mtlsEnabled)
        assertEquals("cert-uuid-123", decoded.clientCertId)
    }

    @Test
    fun `HostProfile defaults are mtlsEnabled false and clientCertId null`() {
        val profile = HostProfile(id = "x", name = "n", serverUrl = "u")
        assertFalse(profile.mtlsEnabled)
        assertNull(profile.clientCertId)
    }

    @Test
    fun `legacy JSON without mTLS fields decodes to safe defaults`() {
        // 旧版本 JSON 无 mtlsEnabled / clientCertId → 反序列化后 false / null
        // （不会悬空引用已不存在的 clientCertId，也不会误开 mTLS）。
        val legacy = """{"id":"legacy","name":"old","serverURL":"http://old:4096","allowInsecureConnections":true}"""
        val decoded = json.decodeFromString<HostProfile>(legacy)

        assertEquals("legacy", decoded.id)
        assertFalse("legacy mtlsEnabled defaults false", decoded.mtlsEnabled)
        assertNull("legacy clientCertId defaults null", decoded.clientCertId)
        assertTrue(decoded.allowInsecureConnections)
    }

    @Test
    fun `HostProfile copy preserves mtls fields`() {
        val original = HostProfile(
            id = "x", name = "n", serverUrl = "u",
            mtlsEnabled = true, clientCertId = "c1",
        )
        val renamed = original.copy(name = "renamed")

        assertTrue(renamed.mtlsEnabled)
        assertEquals("c1", renamed.clientCertId)
    }

    @Test
    fun `export payload does NOT carry mtls fields`() {
        // §2.2：导出 payload 不复制 mtlsEnabled / clientCertId —— 证书材料绝不离开设备。
        val profile = HostProfile(
            id = "x", name = "sensitive", serverUrl = "https://h",
            mtlsEnabled = true, clientCertId = "secret-cert-id",
        )
        val payload = HostProfileExportPayload.from(profile)

        val encoded = json.encodeToString(payload)
        assertFalse("export must not include mtlsEnabled", encoded.contains("mtlsEnabled"))
        assertFalse("export must not include clientCertId", encoded.contains("clientCertId"))
    }

    @Test
    fun `import payload yields a profile with mTLS disabled`() {
        // 导入路径（makeProfile）不设置 mTLS 字段 → 新建 profile 无 mTLS。
        val payload = HostProfileImportPayload(name = "Imported", serverUrl = "https://h:8443")
        val profile = payload.makeProfile()

        assertFalse(profile.mtlsEnabled)
        assertNull(profile.clientCertId)
    }
}
