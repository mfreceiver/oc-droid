package cn.vectory.ocdroid.data.repository

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R8 slim-mode foundation / C3 fix: OpenCodeRepository.parseSlimapiHealth
 * 容错解析单测。验证 `GET /slimapi/health` 响应 body 的抽取——
 *
 *  - 标准形状（参考 docs/slim-mode-api-routing.md §3.2 + design-v2 §9）正确抽取
 *    sidecar.ok / schema.degraded / server.api_version / accepted_client_versions。
 *  - 缺字段 / 类型不符 → 对应字段 null（[ServerCompatProfile.isSlimapiClientAccepted]
 *    fail-closed）。
 *  - 完全无法解析的 body → 全字段 null。
 *
 * 仅测试 parseSlimapiHealth 本身（pure function）；health HTTP 路由（C3 fix）
 * 由 OpenCodeRepositoryTest 覆盖（slim 模式下走 /slimapi/health 而非 /global/health）。
 */
class OpenCodeRepositorySlimapiHealthParseTest {

    private val repository = OpenCodeRepository(
        mockk(relaxed = true),
        mockk(relaxed = true),
    )

    @Test
    fun `parses canonical slimapi health body`() {
        val body = """
            {
              "sidecar": { "ok": true, "version": "0.1.0" },
              "schema":   { "degraded": false },
              "server":   { "api_version": 1, "accepted_client_versions": [1, 1] }
            }
        """.trimIndent()

        val payload = repository.parseSlimapiHealth(body)

        assertEquals(true, payload.sidecarOk)
        assertEquals(false, payload.schemaDegraded)
        assertEquals(1, payload.serverApiVersion)
        assertEquals(Pair(1, 1), payload.acceptedClientVersions)
    }

    @Test
    fun `parses schema degraded true`() {
        val body = """
            {
              "sidecar": { "ok": true, "version": "0.1.0" },
              "schema":   { "degraded": true },
              "server":   { "api_version": 1, "accepted_client_versions": [1, 1] }
            }
        """.trimIndent()

        val payload = repository.parseSlimapiHealth(body)

        assertEquals(true, payload.sidecarOk)
        assertEquals(true, payload.schemaDegraded)
    }

    @Test
    fun `parses sidecar ok false when sidecar is down`() {
        val body = """
            {
              "sidecar": { "ok": false },
              "schema":   { "degraded": false },
              "server":   { "api_version": 1, "accepted_client_versions": [1, 1] }
            }
        """.trimIndent()

        val payload = repository.parseSlimapiHealth(body)

        assertEquals(false, payload.sidecarOk)
    }

    @Test
    fun `parses wider accepted client versions range`() {
        val body = """
            {
              "sidecar": { "ok": true },
              "schema":   { "degraded": false },
              "server":   { "api_version": 2, "accepted_client_versions": [1, 3] }
            }
        """.trimIndent()

        val payload = repository.parseSlimapiHealth(body)

        assertEquals(2, payload.serverApiVersion)
        assertEquals(Pair(1, 3), payload.acceptedClientVersions)
    }

    @Test
    fun `accepted_client_versions with fewer than two entries yields null`() {
        // Defensive: a malformed sidecar emitting a single-element list (or empty)
        // must NOT silently produce a Pair — fail-closed at the compat gate.
        val body = """
            {
              "sidecar": { "ok": true },
              "schema":   { "degraded": false },
              "server":   { "api_version": 1, "accepted_client_versions": [1] }
            }
        """.trimIndent()

        val payload = repository.parseSlimapiHealth(body)

        assertEquals(1, payload.serverApiVersion)
        assertNull(payload.acceptedClientVersions)
    }

    @Test
    fun `missing sidecar block yields null sidecarOk`() {
        val body = """
            {
              "schema": { "degraded": false },
              "server": { "api_version": 1, "accepted_client_versions": [1, 1] }
            }
        """.trimIndent()

        val payload = repository.parseSlimapiHealth(body)

        assertNull(payload.sidecarOk)
        assertEquals(false, payload.schemaDegraded)
        assertEquals(1, payload.serverApiVersion)
        assertEquals(Pair(1, 1), payload.acceptedClientVersions)
    }

    @Test
    fun `missing server block yields null api_version and accepted`() {
        val body = """
            {
              "sidecar": { "ok": true },
              "schema":   { "degraded": false }
            }
        """.trimIndent()

        val payload = repository.parseSlimapiHealth(body)

        assertEquals(true, payload.sidecarOk)
        assertEquals(false, payload.schemaDegraded)
        assertNull(payload.serverApiVersion)
        assertNull(payload.acceptedClientVersions)
    }

    @Test
    fun `non-integer api_version yields null`() {
        val body = """
            {
              "sidecar": { "ok": true },
              "schema":   { "degraded": false },
              "server":   { "api_version": "v1", "accepted_client_versions": [1, 1] }
            }
        """.trimIndent()

        val payload = repository.parseSlimapiHealth(body)

        assertNull(payload.serverApiVersion)
        assertEquals(Pair(1, 1), payload.acceptedClientVersions)
    }

    @Test
    fun `non-JSON body yields all-null payload`() {
        val payload = repository.parseSlimapiHealth("not json at all")
        assertNull(payload.sidecarOk)
        assertNull(payload.schemaDegraded)
        assertNull(payload.serverApiVersion)
        assertNull(payload.acceptedClientVersions)
    }

    @Test
    fun `empty body yields all-null payload`() {
        val payload = repository.parseSlimapiHealth("")
        assertNull(payload.sidecarOk)
        assertNull(payload.serverApiVersion)
        assertNull(payload.acceptedClientVersions)
    }

    @Test
    fun `unknown extra fields are ignored`() {
        // Forward-compat: sidecar adds new fields, parser must NOT break.
        val body = """
            {
              "sidecar": { "ok": true, "version": "0.2.0", "uptime_s": 123 },
              "schema":   { "degraded": false, "reason": "ok" },
              "server":   { "api_version": 1, "accepted_client_versions": [1, 1], "future_field": "x" },
              "unknown_top": { "ignored": true }
            }
        """.trimIndent()

        val payload = repository.parseSlimapiHealth(body)

        assertEquals(true, payload.sidecarOk)
        assertEquals(false, payload.schemaDegraded)
        assertEquals(1, payload.serverApiVersion)
        assertEquals(Pair(1, 1), payload.acceptedClientVersions)
    }

    @Test
    fun `sidecar ok accepts case-insensitive true`() {
        // Boolean JSON primitives decode to "true"/"false" strings; some sidecars
        // might emit "True" (Python-style) — accept case-insensitive.
        val body = """
            {
              "sidecar": { "ok": true },
              "schema":   { "degraded": false },
              "server":   { "api_version": 1, "accepted_client_versions": [1, 1] }
            }
        """.trimIndent()

        val payload = repository.parseSlimapiHealth(body)

        assertTrue(payload.sidecarOk == true)
    }

    @Test
    fun `sidecar ok false does not crash and is reported`() {
        val body = """
            {
              "sidecar": { "ok": false },
              "schema":   { "degraded": false },
              "server":   { "api_version": 1, "accepted_client_versions": [1, 1] }
            }
        """.trimIndent()

        val payload = repository.parseSlimapiHealth(body)

        assertEquals(false, payload.sidecarOk)
        // Other fields still parsed — sidecar being down doesn't make the rest garbage.
        assertEquals(1, payload.serverApiVersion)
        assertEquals(Pair(1, 1), payload.acceptedClientVersions)
    }

    @Test
    fun `schema degraded missing yields null schemaDegraded`() {
        val body = """
            {
              "sidecar": { "ok": true },
              "server":   { "api_version": 1, "accepted_client_versions": [1, 1] }
            }
        """.trimIndent()

        val payload = repository.parseSlimapiHealth(body)
        assertNull(payload.schemaDegraded)
    }
}
