package com.yage.opencode_client.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import com.mikepenz.markdown.model.ImageData

/**
 * File-level utilities for two-pass sampled bitmap decoding and conversion to
 * mikepenz [ImageData]. Extracted verbatim from `DataUriImageTransformer.kt`
 * so the decode helpers can be shared — without duplication — by the data-URI
 * transformer, the HTTP image cache ([HttpImageHolder]), and the file preview
 * pane (`ui.files.FilePreviewPane`).
 *
 * These are pure, stateless helpers: no caching, no Compose snapshot state, no
 * SSL wiring. That makes them trivially unit-testable in isolation and safe to
 * call from any thread (callers wrap IO/decode in `Dispatchers.IO`/`Default`
 * as appropriate). Behaviour is byte-for-byte identical to the former
 * file-private top-level functions; this is a pure relocation.
 */
object ImageDecodeUtils {

    /**
     * R-02: long-edge decode target for sampled image decoding (2048px). The
     * chat/markdown image render target is `fillMaxWidth` of a phone-width
     * column, so 2048 px on the long edge is far above display density —
     * sampling to this cap has no visible effect but bounds peak bitmap memory
     * regardless of source resolution (data-URI pastes, large HTTP
     * attachments, cached disk files). Shared across ui.util (data-URI / HTTP
     * image decode) and ui.files (FilePreviewPane base64 image decode) via
     * `internal` visibility — single source of truth, no duplicate copies.
     */
    internal const val IMAGE_DECODE_TARGET_PX = 2048

    /**
     * Computes a power-of-two [BitmapFactory.Options.inSampleSize] so the
     * decoded long edge is at most [target] pixels. Uses [Long] arithmetic
     * throughout to avoid [Int] overflow on very large source dimensions (a
     * pathological payload could report outWidth/outHeight near Int.MAX_VALUE,
     * and the intermediate `(w/s)*(h/s)` product would otherwise overflow Int
     * and produce a wrong — too small — sample size, causing an OOM on the
     * second decode pass).
     *
     * Returns 0 for non-positive dimensions so the caller can treat the image
     * as undecodable rather than attempting a full-resolution fallback decode.
     */
    internal fun calcInSampleSize(w: Int, h: Int, target: Int): Int {
        if (w <= 0 || h <= 0 || target <= 0) return 0
        var sample = 1
        val longW = w.toLong()
        val longH = h.toLong()
        val longT = target.toLong()
        while ((longW / sample) * (longH / sample) > longT * longT) {
            sample *= 2
        }
        return sample
    }

    /**
     * R-02: two-pass sampled decode of an in-memory image byte array. Pass 1
     * probes dimensions with [BitmapFactory.Options.inJustDecodeBounds] = true
     * (no pixel allocation); [calcInSampleSize] then picks the smallest
     * power-of-two sample keeping the long edge ≤ [targetPx]; pass 2 decodes
     * the actual downsampled bitmap. Bounds peak memory regardless of source
     * size.
     *
     * Returns null if the bounds probe reports non-positive dimensions
     * (corrupt / unknown format), matching the previous `decodeByteArray`
     * null-on-failure contract.
     */
    internal fun decodeSampled(bytes: ByteArray, targetPx: Int = IMAGE_DECODE_TARGET_PX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = calcInSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        if (sample <= 0) return null
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
    }

    /**
     * Wraps a decoded [Bitmap] as a fill-width mikepenz [ImageData] for the
     * markdown/chat image renderer. Stateless apart from the [Bitmap] argument.
     */
    internal fun bitmapToImageData(bitmap: Bitmap): ImageData = ImageData(
        painter = BitmapPainter(bitmap.asImageBitmap()),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth(),
        alignment = Alignment.Center,
        contentScale = ContentScale.FillWidth
    )
}
