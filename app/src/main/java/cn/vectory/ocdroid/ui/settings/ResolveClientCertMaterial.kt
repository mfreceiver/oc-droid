package cn.vectory.ocdroid.ui.settings

import cn.vectory.ocdroid.data.repository.http.ClientCertMaterial

/**
 * §2.7 fix-3（gpt-2#3 阻断）: 显式 mTLS 编辑意图。saveHostProfile 的旧默认参数
 * `mtlsEnabled=false/hasImportedP12=false/stagedP12=null` 让任何走默认的调用
 * （4-arg/5-arg test pass-through、未来调用方）落入 `!mtlsEnabled` 分支→清掉既有
 * 证书材料。本 sealed interface 把「未提供」([Unchanged]) 与「禁用」([Disable])
 * 显式区分：默认 [Unchanged] 不动 ESP、不改 profile 的 mTLS 字段。
 *
 * 仅 [Update] / [Disable] 由 mTLS 编辑对话框构造；其它调用方默认 [Unchanged]。
 * `public` 因被 public 的 `HostProfileController.saveHostProfile` 签名引用。
 */
sealed interface ClientCertEditIntent {
    /** 不动 ESP 中的客户端证书材料，也不改 profile 的 mtlsEnabled/clientCertId。 */
    data object Unchanged : ClientCertEditIntent
    /**
     * 开启 / 保持 mTLS（mtlsEnabled=true），按携带字段试构建 + 原子写 ESP。
     * [stagedP12]==null 表示本次未重导（沿用已存 p12，由 VM 回 ESP 取）。
     */
    data class Update(
        val stagedP12: ByteArray?,
        val caStage: CaStage,
        val p12Password: String?,
        val p12PasswordEdited: Boolean,
        val hasImportedP12: Boolean,
    ) : ClientCertEditIntent
    /** 关闭 mTLS（mtlsEnabled=false）：清已存证书材料，profile 置无 mTLS。 */
    data object Disable : ClientCertEditIntent
}


/**
 * §2.7: 纯函数——把"Dialog 编辑意图"归一为 mTLS 客户端证书的**生效材料**。
 *
 * Dialog 是纯 UI（不注入 SettingsManager，gpter#6/glmer I3），只把编辑意图
 * （[stagedP12] / [caStage] / [p12Password] / [p12PasswordEdited] /
 * [hasImportedP12]）经回调外流给 VM。VM（save / testConnection）持有
 * SettingsManager，调本函数把这些意图 + 已存材料（通过 load\* lambda 查 ESP）
 * 解析成可用于 试构建（[cn.vectory.ocdroid.data.repository.http.buildMutualTlsConfig]）
 * / 持久化（[cn.vectory.ocdroid.util.SettingsManager.saveClientCert]）/
 * 探测（[cn.vectory.ocdroid.data.repository.OpenCodeRepository.checkHealthFor]）
 * 的 [ResolvedClientCert]。
 *
 * 返回 `null` 表示"无需 mTLS 材料"——两种语义：
 *  - [mtlsEnabled]=false → 调用方应清除已存证书（保存路径）或探测不带 clientCert。
 *  - [mtlsEnabled]=true 但确无 p12（既未 [stagedP12] 也无已存）→ 调用方据此判定
 *    "保存拒绝"（§2.7：mtlsEnabled && !hasImportedP12）。
 *
 * 生效密码（v3-glmer R2 首导空密码修复）：
 *  - [stagedP12] != null（本次新导入 p12）：[p12PasswordEdited]=false → `""`（首导，
 *    新 id 无已存，**不沿用**）；edited → [p12Password]。
 *  - [stagedP12] == null 且 [hasImportedP12]（编辑既有，未重导）：edited → [p12Password]；
 *    未编辑 → loadPassword([oldId]) 沿用。
 *
 * 生效 CA（[caStage] 三态，v3-gpter R2#3 防静默私有CA→平台CA降级）：
 *  - [CaStage.Unchanged] → loadCa([oldId])（若无已存则 null）。
 *  - [CaStage.Replace] → [CaStage.Replace.bytes]。
 *  - [CaStage.Clear] → null（显式转平台 CA 模式）。
 *
 * 抽成顶层纯函数（+ lambda 注入 ESP 读取）便于单测覆盖密码 / CA 归一逻辑，
 * 与 [cn.vectory.ocdroid.ui.resolveTestConnectionPassword] 同风格。
 */
internal data class ResolvedClientCert(
    val p12: ByteArray,
    val password: String,
    val ca: ByteArray?,
)

internal fun resolveClientCert(
    mtlsEnabled: Boolean,
    stagedP12: ByteArray?,
    hasImportedP12: Boolean,
    caStage: CaStage,
    p12Password: String?,
    p12PasswordEdited: Boolean,
    oldId: String?,
    loadP12: (String) -> ByteArray?,
    loadPassword: (String) -> String?,
    loadCa: (String) -> ByteArray?,
): ResolvedClientCert? {
    if (!mtlsEnabled) return null
    // 有效 p12：本次新导入优先；否则沿用已存（仅当 hasImportedP12 且 oldId 存在）。
    val effectiveP12: ByteArray = stagedP12
        ?: (if (hasImportedP12 && oldId != null) loadP12(oldId) else null)
        ?: return null
    val effectivePassword: String = when {
        p12PasswordEdited -> p12Password ?: ""
        stagedP12 != null -> ""   // 首导未编辑密码 → 当空串写（新 id 无已存，不沿用）
        hasImportedP12 && oldId != null -> loadPassword(oldId) ?: ""
        else -> ""
    }
    val effectiveCa: ByteArray? = when (caStage) {
        CaStage.Unchanged -> if (hasImportedP12 && oldId != null) loadCa(oldId) else null
        is CaStage.Replace -> caStage.bytes
        CaStage.Clear -> null
    }
    return ResolvedClientCert(effectiveP12, effectivePassword, effectiveCa)
}

/** §2.7: 把生效材料转成握手用 [ClientCertMaterial]（密码 toCharArray）。 */
internal fun ResolvedClientCert.toMaterial(): ClientCertMaterial =
    ClientCertMaterial(p12Bytes = p12, p12Password = password.toCharArray(), caBytes = ca)
