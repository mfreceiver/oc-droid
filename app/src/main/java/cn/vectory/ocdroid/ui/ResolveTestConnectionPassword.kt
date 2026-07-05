package cn.vectory.ocdroid.ui

/**
 * §fix-401-credential (gpter 🔴): 纯函数——解析"测试连接"应使用的有效密码。
 *
 * 规则（区分"未碰密码框"与"主动清空"，避免凭据泄漏）：
 *  - passwordEdited=true（用户动过框）→ 用表单值（可能 null = 无 auth）。**不回退**已保存
 *    密码——避免用户改了 URL/清空密码后仍把旧凭据发到新地址。
 *  - passwordEdited=false（未碰，编辑已有 host 时的常态）→ 表单值为空 → 回退已保存密码
 *    （write-only 字段不回填，"改 URL 不重输密码"也能正确带 Basic Auth 测试）。
 *
 * 抽成顶层纯函数便于单测覆盖凭据路径（曾实际出 401 的安全敏感逻辑）。
 */
internal fun resolveTestConnectionPassword(
    formPassword: String?,
    passwordEdited: Boolean,
    profileId: String?,
    savedPasswordLookup: (String) -> String?,
): String? = when {
    passwordEdited -> formPassword
    else -> formPassword ?: profileId?.let { savedPasswordLookup(it) }
}
