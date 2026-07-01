package cn.vectory.ocdroid.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_IMAGE_DIMENSION = 2048
private const val JPEG_QUALITY = 82
private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

internal suspend fun loadImageAttachments(context: Context, uris: List<Uri>): List<ComposerImageAttachment> = withContext(Dispatchers.IO) {
    uris.take(4).mapNotNull { uri ->
        runCatching { loadImageAttachment(context, uri) }.getOrNull()
    }
}

private fun loadImageAttachment(context: Context, uri: Uri): ComposerImageAttachment? {
    val resolver = context.contentResolver
    val bitmap = resolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input) } ?: return null
    val scaled = bitmap.scaledToFit(MAX_IMAGE_DIMENSION)
    if (scaled !== bitmap) bitmap.recycle()

    val bytes = ByteArrayOutputStream().use { output ->
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        output.toByteArray()
    }
    if (scaled !== bitmap) scaled.recycle()
    if (bytes.size > MAX_IMAGE_BYTES) return null

    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
    return ComposerImageAttachment(
        id = UUID.randomUUID().toString(),
        filename = resolver.displayName(uri) ?: "image.jpg",
        mime = "image/jpeg",
        dataUrl = "data:image/jpeg;base64,$encoded",
        thumbnailData = bytes,
        byteSize = bytes.size
    )
}

private fun Bitmap.scaledToFit(maxDimension: Int): Bitmap {
    val longest = max(width, height)
    if (longest <= maxDimension) return this
    val scale = maxDimension.toFloat() / longest.toFloat()
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

private fun android.content.ContentResolver.displayName(uri: Uri): String? {
    return query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.takeIf { it.isNotBlank() }
}
