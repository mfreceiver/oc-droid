package cn.vectory.ocdroid.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R8 slim-mode foundation / M2 自检：ServerCompatProfile slimapi 字段单测。
 *
 * 覆盖：
 *  - updateSlimapi 落库正确（sidecarOk / schemaDegraded / serverApiVersion /
 *    accepted 区间）。
 *  - isSlimapiClientAccepted fail-closed：未探过 slimapi health（任一区间端点
 *    null）→ false；客户端版本在区间内 → true；在区间外 → false。
 *  - opencode semver 字段（version/major/minor/patch）与 slim 字段独立——
 *    updateSlimapi 不污染 opencode 字段，update(value) 不污染 slim 字段。
 *
 * 契约参考 docs/slim-mode-api-routing.md §3.2 + design-v2 §9。
 */
class ServerCompatProfileSlimapiTest {

    @Test
    fun `updateSlimapi populates all slim fields`() {
        val p = ServerCompatProfile()
        p.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = false,
                serverApiVersion = 1,
                acceptedClientVersions = Pair(1, 1)
            )
        )
        assertEquals(true, p.slimapiSidecarOk)
        assertEquals(false, p.slimapiSchemaDegraded)
        assertEquals(1, p.slimapiServerApiVersion)
        assertEquals(1, p.slimapiAcceptedMin)
        assertEquals(1, p.slimapiAcceptedMax)
    }

    @Test
    fun `updateSlimapi accepts nullable fields for tolerant parsing`() {
        // Sidecar may omit any field — parser degrades per-field to null.
        val p = ServerCompatProfile()
        p.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = null,
                schemaDegraded = null,
                serverApiVersion = null,
                acceptedClientVersions = null
            )
        )
        assertNull(p.slimapiSidecarOk)
        assertNull(p.slimapiSchemaDegraded)
        assertNull(p.slimapiServerApiVersion)
        assertNull(p.slimapiAcceptedMin)
        assertNull(p.slimapiAcceptedMax)
    }

    // ── isSlimapiClientAccepted：fail-closed ─────────────────────────────

    @Test
    fun `isSlimapiClientAccepted is fail-closed before any probe`() {
        // Fresh profile — never probed slimapi health.
        val p = ServerCompatProfile()
        assertFalse(
            "未探过 slimapi health 时必须 fail-closed（绝不静默放行）",
            p.isSlimapiClientAccepted()
        )
    }

    @Test
    fun `isSlimapiClientAccepted returns true when client in accepted range`() {
        val p = ServerCompatProfile()
        p.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = false,
                serverApiVersion = 1,
                acceptedClientVersions = Pair(1, 1)
            )
        )
        assertTrue(
            "客户端版本 1 ∈ [1,1] → 兼容",
            p.isSlimapiClientAccepted()
        )
    }

    @Test
    fun `isSlimapiClientAccepted returns true when client inside wider range`() {
        val p = ServerCompatProfile()
        p.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = false,
                serverApiVersion = 2,
                // Forward-compat: sidecar bumped to v2 but still accepts v1.
                acceptedClientVersions = Pair(1, 2)
            )
        )
        assertTrue(
            "客户端版本 1 ∈ [1,2] → 兼容（前向兼容窗口）",
            p.isSlimapiClientAccepted()
        )
    }

    @Test
    fun `isSlimapiClientAccepted returns false when client below range`() {
        val p = ServerCompatProfile()
        p.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = false,
                serverApiVersion = 5,
                // Sidecar deprecated v1 support; min accepted = 3.
                acceptedClientVersions = Pair(3, 5)
            )
        )
        assertFalse(
            "客户端版本 1 < 3 → 不兼容（sidecar 已弃用 v1）",
            p.isSlimapiClientAccepted()
        )
    }

    @Test
    fun `isSlimapiClientAccepted returns false when client above range`() {
        val p = ServerCompatProfile()
        p.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = false,
                serverApiVersion = 0,
                // Old sidecar: max accepted = 0.
                acceptedClientVersions = Pair(0, 0)
            )
        )
        assertFalse(
            "客户端版本 1 > 0 → 不兼容（sidecar 太老）",
            p.isSlimapiClientAccepted()
        )
    }

    @Test
    fun `isSlimapiClientAccepted returns false when range partially missing`() {
        val p = ServerCompatProfile()
        p.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = false,
                serverApiVersion = 1,
                // Parser could only decode one end → fail-closed.
                acceptedClientVersions = null
            )
        )
        assertFalse(
            "区间两端任一缺失 → fail-closed",
            p.isSlimapiClientAccepted()
        )
    }

    // ── opencode semver 字段与 slim 字段正交 ────────────────────────────

    @Test
    fun `updateSlimapi does not touch opencode semver fields`() {
        val p = ServerCompatProfile()
        p.update("1.17.13")
        p.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = false,
                serverApiVersion = 1,
                acceptedClientVersions = Pair(1, 1)
            )
        )
        // opencode semver fields preserved.
        assertEquals("1.17.13", p.version)
        assertEquals(1, p.major)
        assertEquals(17, p.minor)
        assertEquals(13, p.patch)
        // Slim fields populated.
        assertEquals(1, p.slimapiServerApiVersion)
        assertTrue(p.isSlimapiClientAccepted())
    }

    @Test
    fun `update opencode semver does not touch slim fields`() {
        val p = ServerCompatProfile()
        p.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = true,
                serverApiVersion = 1,
                acceptedClientVersions = Pair(1, 1)
            )
        )
        // Then update opencode semver.
        p.update("2.0.0")
        // Slim fields preserved.
        assertEquals(true, p.slimapiSidecarOk)
        assertEquals(true, p.slimapiSchemaDegraded)
        assertEquals(1, p.slimapiServerApiVersion)
        assertTrue(p.isSlimapiClientAccepted())
        // opencode semver now populated.
        assertEquals("2.0.0", p.version)
        assertEquals(2, p.major)
    }

    @Test
    fun `schema degraded true does not affect compatibility gate`() {
        // schema.degraded → skeleton auto-falls-back to full (M2 docs), but
        // does NOT itself gate slimapi compatibility — sidecar is still up,
        // client version is still accepted.
        val p = ServerCompatProfile()
        p.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = true,
                serverApiVersion = 1,
                acceptedClientVersions = Pair(1, 1)
            )
        )
        assertTrue(p.isSlimapiClientAccepted())
        assertEquals(true, p.slimapiSchemaDegraded)
    }
}
