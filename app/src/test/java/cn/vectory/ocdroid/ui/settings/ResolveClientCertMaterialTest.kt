package cn.vectory.ocdroid.ui.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §fix-3 (max-1 M4): 纯函数 [resolveClientCert] 的密码 / CA 归一逻辑覆盖。
 *
 * Dialog 是纯 UI，把编辑意图（stagedP12 / caStage / p12Password / p12PasswordEdited /
 * hasImportedP12）外流给 VM；本函数把这些意图 + 已存材料（通过 load\* lambda）归一为
 * 生效 [ResolvedClientCert]。这里钉死密码（首导空串 / 沿用 / edited）与 CA（三态）语义。
 */
class ResolveClientCertMaterialTest {

    private val storedP12 = byteArrayOf(1, 2, 3)
    private val storedPw = "stored-pw"
    private val storedCa = byteArrayOf(9, 9, 9)
    private val stagedP12 = byteArrayOf(4, 5, 6)

    private fun loadP12(id: String): ByteArray? = if (id == "old") storedP12 else null
    private fun loadPassword(id: String): String? = if (id == "old") storedPw else null
    private fun loadCa(id: String): ByteArray? = if (id == "old") storedCa else null

    private fun resolve(
        mtlsEnabled: Boolean = true,
        stagedP12: ByteArray? = null,
        hasImportedP12: Boolean = false,
        caStage: CaStage = CaStage.Unchanged,
        p12Password: String? = null,
        p12PasswordEdited: Boolean = false,
        oldId: String? = "old",
    ) = resolveClientCert(
        mtlsEnabled, stagedP12, hasImportedP12, caStage,
        p12Password, p12PasswordEdited, oldId,
        ::loadP12, ::loadPassword, ::loadCa,
    )

    @Test
    fun `mtlsEnabled false returns null`() {
        assertNull(resolve(mtlsEnabled = false))
    }

    @Test
    fun `first import stagedP12 non-null and password not edited yields empty password`() {
        // 首导未编辑密码 → ""（新 id 无已存，不沿用）。
        val r = resolve(stagedP12 = stagedP12, hasImportedP12 = true, p12PasswordEdited = false, oldId = null)
        assertArrayEquals(stagedP12, r!!.p12)
        assertEquals("", r.password)
        // CaStage.Unchanged + oldId=null → 无已存 CA → null。
        assertNull(r.ca)
    }

    @Test
    fun `first import stagedP12 non-null and password edited yields the form password`() {
        val r = resolve(
            stagedP12 = stagedP12, hasImportedP12 = true,
            p12Password = "newpw", p12PasswordEdited = true, oldId = null,
        )
        assertEquals("newpw", r!!.password)
    }

    @Test
    fun `editing existing without re-import reuses stored password via oldId`() {
        // stagedP12==null && hasImportedP12 && oldId → loadPassword(oldId) 沿用。
        val r = resolve(stagedP12 = null, hasImportedP12 = true, p12PasswordEdited = false, oldId = "old")
        assertArrayEquals(storedP12, r!!.p12)
        assertEquals(storedPw, r.password)
    }

    @Test
    fun `editing existing without re-import but oldId null returns null (missing material)`() {
        // stagedP12==null && hasImportedP12 && oldId=null → loadP12(null-id)=null → missing。
        assertNull(resolve(stagedP12 = null, hasImportedP12 = true, oldId = null))
    }

    @Test
    fun `no stagedP12 and not hasImportedP12 returns null`() {
        assertNull(resolve(stagedP12 = null, hasImportedP12 = false))
    }

    @Test
    fun `CaStage Unchanged reuses stored CA`() {
        val r = resolve(stagedP12 = stagedP12, hasImportedP12 = true, caStage = CaStage.Unchanged, oldId = "old")
        assertArrayEquals(storedCa, r!!.ca)
    }

    @Test
    fun `CaStage Replace uses the supplied bytes`() {
        val caBytes = byteArrayOf(7, 7)
        val r = resolve(stagedP12 = stagedP12, hasImportedP12 = true, caStage = CaStage.Replace(caBytes))
        assertArrayEquals(caBytes, r!!.ca)
    }

    @Test
    fun `CaStage Clear yields null CA (explicit platform-CA mode)`() {
        val r = resolve(stagedP12 = stagedP12, hasImportedP12 = true, caStage = CaStage.Clear, oldId = "old")
        assertNull(r!!.ca)
    }
}
