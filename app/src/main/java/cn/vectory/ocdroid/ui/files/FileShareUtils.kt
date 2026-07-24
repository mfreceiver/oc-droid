package cn.vectory.ocdroid.ui.files

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.content.FileProvider
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.FileContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * §F5: 分享文件内容。文件存储在远端 opencode 服务器， getFileContent 返回的
 * [FileContent.content] 对文本是 UTF-8 字符串、对二进制（含图片）是 base64 字符串。
 * 本函数据此落盘到 cacheDir/shared，复用既定 `${packageName}.fileprovider` +
 * ACTION_SEND 链路（与 FilePreviewPane.shareImage 同模式）。
 *
 * 覆盖所有文件格式：md/文本直写 UTF-8；二进制 base64 解码后写原始字节。
 * 用于 md 预览页分享按钮 + 文件浏览页长按分享。
 */
internal suspend fun shareFileContent(context: Context, path: String, content: FileContent) {
    // §gpter-B1/kimo: 仅 null 返回——空文本文件（content=""）是合法的 0 字节文件，
    // 应写出空文件并分享；只有完全无内容（null）才放弃。
    val text = content.content ?: return

    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    val fileName = path.substringAfterLast('/').ifBlank { "file" }
    val shareFile = File(sharedDir, fileName)
    val bytes = if (content.isBinary) {
        // 二进制（图片等）以 base64 传输——解码回原始字节。
        runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrNull() ?: return
    } else {
        text.toByteArray(Charsets.UTF_8)
    }
    // §F5-ZLM (C4): 写盘切 IO 线程，避免主线程 ANR。File.writeBytes 是阻塞 IO。
    withContext(Dispatchers.IO) {
        shareFile.writeBytes(bytes)
    }

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        shareFile
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = shareMimeType(path, content.isBinary)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // §F5-ZLM (C4): 系统分享 chooser/startActivity 必须回到 Main 线程。
    withContext(Dispatchers.Main) {
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.files_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 按 path/类型推断分享 MIME。 */
private fun shareMimeType(path: String, isBinary: Boolean): String = when {
    path.endsWith(".md", ignoreCase = true) -> "text/markdown"
    FilePreviewUtils.isImagePath(path) -> FilePreviewUtils.imageMimeType(path)
    !isBinary -> "text/plain"
    else -> "application/octet-stream"
}
