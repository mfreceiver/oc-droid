package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.util.runSuspendCatching
import java.io.IOException

/**
 * ι-P2 message 域端口。签名域语言，无 slim/legacy 字眼。
 *
 * # I15 (token threading)
 *
 * [OpenCodeRepository.SlimCommitToken] 透传——外层
 * （[OpenCodeRepository.captureSlimCommitToken]）在公共 wrapper 默认参数处捕获，
 * 经 [getMessagesPaged] 穿到实现，实现内调注入的 [SlimMessageSource.bumpBookmark]
 * lambda 回 OCR.bumpSlimBookmarkFromItems，require 一致。capture/bump 逻辑不改。
 *
 * # anchored 语义
 *
 * slim 实现里 `anchored = true` 读缓存 watermark
 * （`since = slimSessionUpdatedAt(sid)`），`anchored = false` 强制 `since = 0L`
 * （unanchored initial window，§empty-window-fix）。
 */
internal interface MessageSource {
    suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int?,
        before: String?,
        token: OpenCodeRepository.SlimCommitToken,
        anchored: Boolean,
    ): Result<MessagesPage>
}

/**
 * Standard: 只 legacy。`apiProvider` lambda 防陈旧（复用 SlimGetRepository /
 * SessionSource 模式）——每次调用读最新 [OpenCodeApi]（host 重建后即生效）。
 *
 * `anchored` 在 legacy 分支无意义（无 watermark 概念）——参数接收但忽略，
 * 与原 [OpenCodeRepository.getMessagesPagedImpl] legacy 分支 byte-for-byte 一致。
 */
internal class StandardMessageSource(
    private val apiProvider: () -> OpenCodeApi,
) : MessageSource {
    override suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int?,
        before: String?,
        token: OpenCodeRepository.SlimCommitToken,
        anchored: Boolean,
    ): Result<MessagesPage> = runSuspendCatching {
        val response = apiProvider().getMessages(sessionId, limit, before)
        if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
        val items = response.body() ?: emptyList()
        MessagesPage(items = items, nextCursor = response.headers()["X-Next-Cursor"])
    }
}

/**
 * Slim: 只 slimapi。**共享态协作者**（§4.4 最坏例）——不持锁、不持 slimStateMachine
 * 对象，只持三条注入的纯函数 lambda：
 *  - [apiProvider]: 读最新 [OpenCodeApi]（防陈旧）。
 *  - [slimSessionUpdatedAt]: 读缓存 watermark（注入；源 = OCR.slimStateMachine
 *    .getSlimSessionState(sid)?.updatedAt ?: 0L；**只读**，不暴露状态机对象）。
 *  - [bumpBookmark]: 回调 OCR.bumpSlimBookmarkFromItems（注入；源 = OCR 私有方法，
 *    内部 `synchronized(slimStateLock)`）→ **锁与 bookmark 状态留 OCR**（I5 保持）。
 *
 * token threading（I15）：[token] 透传到 [bumpBookmark]——stale 时 bumpBookmark
 * 返回 false → 抛 [OpenCodeRepository.StaleSlimCommitException]（嵌套 FQN 不变）。
 */
internal class SlimMessageSource(
    private val apiProvider: () -> OpenCodeApi,
    private val slimSessionUpdatedAt: (String) -> Long,
    private val bumpBookmark: suspend (
        sessionId: String,
        items: List<MessageWithParts>,
        token: OpenCodeRepository.SlimCommitToken,
    ) -> Boolean,
) : MessageSource {
    override suspend fun getMessagesPaged(
        sessionId: String,
        limit: Int?,
        before: String?,
        token: OpenCodeRepository.SlimCommitToken,
        anchored: Boolean,
    ): Result<MessagesPage> = runSuspendCatching {
        val since = if (anchored) slimSessionUpdatedAt(sessionId) else 0L
        val response = apiProvider().getSlimapiMessagesSince(sessionId, since, limit, before)
        if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
        val items = response.body() ?: emptyList()
        // bookmark bump 经注入 lambda 回调 OCR.bumpSlimBookmarkFromItems
        // （锁内 mutation；token 透传，stale → throw StaleSlimCommitException）。
        if (!bumpBookmark(sessionId, items, token)) {
            throw OpenCodeRepository.StaleSlimCommitException()
        }
        MessagesPage(items = items, nextCursor = response.headers()["X-Next-Cursor"])
    }
}
