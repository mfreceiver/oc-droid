package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.ui.resolveTestConnectionPassword
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §fix-401-credential (gpter 🔴): 回归测试——"测试连接"的密码解析必须区分
 * "未碰密码框"（回退已保存密码）与"主动清空/改密码"（不回退，避免把旧凭据
 * 发到用户改过的新 URL）。这是曾实际出 401 的安全敏感路径。
 */
class TestConnectionPasswordTest {

    private val saved = mapOf("profile-1" to "saved-secret")

    private fun lookup(id: String): String? = saved[id]

    @Test
    fun untouched_password_fallsBackToSaved() {
        // 编辑已有 host，没碰密码框 → 表单密码为 null → 回退已保存密码。
        val result = resolveTestConnectionPassword(
            formPassword = null,
            passwordEdited = false,
            profileId = "profile-1",
            savedPasswordLookup = ::lookup,
        )
        assertEquals("saved-secret", result)
    }

    @Test
    fun cleared_password_doesNotFallBack_credentialSafety() {
        // §gpter-🔴: 用户主动清空密码（passwordEdited=true）→ 不回退，按无 auth 测试。
        // 防止把已保存凭据误发到用户改过的新 URL。
        val result = resolveTestConnectionPassword(
            formPassword = null,
            passwordEdited = true,
            profileId = "profile-1",
            savedPasswordLookup = ::lookup,
        )
        assertEquals(null, result)
    }

    @Test
    fun edited_password_usesFormValue() {
        // 用户输入了新密码 → 用表单值。
        val result = resolveTestConnectionPassword(
            formPassword = "new-password",
            passwordEdited = true,
            profileId = "profile-1",
            savedPasswordLookup = ::lookup,
        )
        assertEquals("new-password", result)
    }

    @Test
    fun newProfile_noFallback() {
        // 新建 profile（profileId=null）+ 未输密码 → 不回退（无已保存密码可查）。
        val result = resolveTestConnectionPassword(
            formPassword = null,
            passwordEdited = false,
            profileId = null,
            savedPasswordLookup = ::lookup,
        )
        assertEquals(null, result)
    }

    @Test
    fun newProfile_withFormPassword_usesFormValue() {
        // 新建 profile + 输了密码 → 用表单值。
        val result = resolveTestConnectionPassword(
            formPassword = "fresh",
            passwordEdited = true,
            profileId = null,
            savedPasswordLookup = ::lookup,
        )
        assertEquals("fresh", result)
    }

    @Test
    fun untouched_password_unknownProfileId_noFallback() {
        // 编辑已有 host 但 profileId 对应的已保存密码不存在（从未设过）→ null。
        val result = resolveTestConnectionPassword(
            formPassword = null,
            passwordEdited = false,
            profileId = "never-set",
            savedPasswordLookup = ::lookup,
        )
        assertEquals(null, result)
    }
}
