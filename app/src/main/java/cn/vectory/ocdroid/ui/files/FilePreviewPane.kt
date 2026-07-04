package cn.vectory.ocdroid.ui.files

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.vectory.ocdroid.R
import com.mikepenz.markdown.m3.Markdown
import cn.vectory.ocdroid.data.model.FileContent
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.theme.LocalMarkdownFontSizes
import cn.vectory.ocdroid.ui.theme.markdownTypography
import cn.vectory.ocdroid.ui.util.DataUriImageTransformer
import cn.vectory.ocdroid.ui.util.HttpImageHolder
import cn.vectory.ocdroid.ui.util.ImageDecodeUtils
import cn.vectory.ocdroid.ui.util.MarkdownImageResolver
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class MarkdownPreviewMode {
    Web,
    Native,
    Source
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilePreviewPane(
    path: String,
    fileContent: FileContent,
    repository: OpenCodeRepository,
    sessionDirectory: String? = null,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val content = fileContent.content.orEmpty()
    val previewKind = remember(path, fileContent.isBinary) {
        FilePreviewUtils.previewContentKind(path, fileContent.isBinary)
    }
    // R-02a: decode base64 image payloads OFF the main thread. The former
    // `remember(path, content){ decodeImagePayload(content) }` ran
    // BitmapFactory.decodeByteArray synchronously on the UI thread — the root
    // cause of preview ANRs and a major OOM source on large pasted images.
    // produceState runs the decode on Dispatchers.Default and downsamples via
    // inSampleSize so we never allocate a full-res bitmap for a multi-MB
    // base64 blob. The state cycles Idle→Loading→Loaded/Failed; while Loading
    // we render a spinner placeholder instead of falling through to
    // PreviewPlainText (which would otherwise dump raw base64 text).
    val imageState by produceState<ImageLoadState>(ImageLoadState.Idle, path, content, previewKind) {
        value = if (previewKind == FilePreviewUtils.PreviewContentKind.IMAGE) {
            ImageLoadState.Loading
        } else {
            ImageLoadState.NotImage
        }
        if (value is ImageLoadState.Loading) {
            value = withContext(Dispatchers.Default) {
                decodeImagePayloadSampled(content, targetPx = ImageDecodeUtils.IMAGE_DECODE_TARGET_PX)
                    ?.let(ImageLoadState::Loaded)
                    ?: ImageLoadState.Failed
            }
        }
    }
    val imagePayload = (imageState as? ImageLoadState.Loaded)?.payload
    var markdownPreviewMode by remember(path) { mutableStateOf(MarkdownPreviewMode.Web) }
    var modeMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                }
            },
            actions = {
                if (previewKind == FilePreviewUtils.PreviewContentKind.MARKDOWN) {
                    Box {
                        IconButton(onClick = { modeMenuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.files_preview_mode))
                        }
                        DropdownMenu(
                            expanded = modeMenuExpanded,
                            onDismissRequest = { modeMenuExpanded = false }
                        ) {
                            MarkdownPreviewMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (mode) {
                                                MarkdownPreviewMode.Web -> stringResource(R.string.files_open_web_preview)
                                                MarkdownPreviewMode.Native -> stringResource(R.string.files_open_native_preview)
                                                MarkdownPreviewMode.Source -> stringResource(R.string.files_open_markdown_source)
                                            }
                                        )
                                    },
                                    onClick = {
                                        markdownPreviewMode = mode
                                        modeMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.common_refresh))
                }
                if (imagePayload != null) {
                    IconButton(onClick = { shareImage(context, path, imagePayload.bytes) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.files_share))
                    }
                }
            }
        )

        // §A-2: replaced the hairline HorizontalDivider under the TopAppBar with
        // a tonal surface. The body sits on surfaceContainerLow so it reads as
        // distinct from the TopAppBar's surface without a drawn line.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            when {
                imagePayload != null -> ImageViewer(bitmap = imagePayload.bitmap)
                // R-02a: while the image is decoding (or failed to decode) keep the
                // user on a spinner / placeholder. Without this guard the `when`
                // would fall through to PreviewPlainText and dump the raw base64
                // source onto the screen for one or more frames.
                previewKind == FilePreviewUtils.PreviewContentKind.IMAGE -> ImageDecodePlaceholder()
                previewKind == FilePreviewUtils.PreviewContentKind.MARKDOWN -> when (markdownPreviewMode) {
                    MarkdownPreviewMode.Web -> MarkdownWebPreviewPane(
                        content = content,
                        filePath = path,
                        repository = repository,
                        sessionDirectory = sessionDirectory,
                        onOpenNative = { markdownPreviewMode = MarkdownPreviewMode.Native },
                        onOpenSource = { markdownPreviewMode = MarkdownPreviewMode.Source }
                    )
                    MarkdownPreviewMode.Native -> PreviewMarkdown(
                        content = content,
                        filePath = path,
                        repository = repository,
                        sessionDirectory = sessionDirectory
                    )
                    MarkdownPreviewMode.Source -> PreviewPlainText(content = content)
                }
                previewKind == FilePreviewUtils.PreviewContentKind.BINARY -> PreviewBinaryFallback()
                else -> PreviewPlainText(content = content)
            }
        }
    }
}

@Composable
private fun PreviewMarkdown(
    content: String,
    filePath: String,
    repository: OpenCodeRepository,
    sessionDirectory: String?
) {
    var resolvedContent by remember(content, filePath) { mutableStateOf<String?>(null) }
    val normalizedContent = remember(content) { MarkdownImageResolver.normalizeStandaloneImageBlocks(content) }
    val resolverMarkdownPath = remember(filePath, sessionDirectory) {
        resolveRelativePreviewPath(filePath, sessionDirectory)
    }

    LaunchedEffect(normalizedContent, resolverMarkdownPath, sessionDirectory, repository) {
        resolvedContent = null
        resolvedContent = MarkdownImageResolver.resolveImages(
            text = normalizedContent,
            markdownFilePath = resolverMarkdownPath,
            workspaceDirectory = sessionDirectory,
            fetchContent = { path -> repository.getFileContent(path).getOrThrow() }
        )
        val finalText = resolvedContent ?: normalizedContent
        val httpsUrls = """!\[[^\]]*\]\((https?://[^)]+)\)""".toRegex().findAll(finalText).map { it.groupValues[1] }.toList().distinct()
        for (url in httpsUrls) {
            HttpImageHolder.prefetch(url)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            val fontSizes = LocalMarkdownFontSizes.current
            Markdown(
                content = resolvedContent ?: normalizedContent,
                typography = markdownTypography(fontSizes),
                modifier = Modifier.fillMaxWidth(),
                imageTransformer = DataUriImageTransformer
            )
        }
    }
}

@Composable
private fun PreviewBinaryFallback() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Binary file preview is not supported.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun PreviewPlainText(content: String) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = BundledMonoFamily
            )
        }
    }
}

@Composable
internal fun ImageViewer(bitmap: Bitmap) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()
        val fitRatio = remember(containerWidth, containerHeight, bitmap.width, bitmap.height) {
            if (bitmap.width == 0 || bitmap.height == 0 || containerWidth == 0f || containerHeight == 0f) {
                1f
            } else {
                min(containerWidth / bitmap.width.toFloat(), containerHeight / bitmap.height.toFloat())
            }
        }
        val fittedWidth = bitmap.width * fitRatio
        val fittedHeight = bitmap.height * fitRatio
        val maxDoubleTapScale = remember(fitRatio) { (1f / fitRatio).coerceIn(2f, 5f) }

        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        fun clampOffset(candidate: Offset, targetScale: Float): Offset {
            val maxX = max(0f, (fittedWidth * targetScale - containerWidth) / 2f)
            val maxY = max(0f, (fittedHeight * targetScale - containerHeight) / 2f)
            return Offset(
                x = candidate.x.coerceIn(-maxX, maxX),
                y = candidate.y.coerceIn(-maxY, maxY)
            )
        }

        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
            scale = newScale
            offset = clampOffset(
                candidate = offset + if (newScale > 1f) panChange else Offset.Zero,
                targetScale = newScale
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = transformState)
                .pointerInput(maxDoubleTapScale) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = maxDoubleTapScale
                                offset = Offset.Zero
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        }
    }
}

private data class DecodedImagePayload(
    val bytes: ByteArray,
    val bitmap: Bitmap
)

/**
 * R-02a: tri-state for the async image decode driven by [produceState].
 * - [Idle]: initial value before the producer first runs.
 * - [NotImage]: the preview kind is not an image — render non-image branches.
 * - [Loading]: decode in flight on Dispatchers.Default — render the placeholder.
 * - [Loaded]: decode succeeded — render [ImageViewer].
 * - [Failed]: decode returned null (corrupt / undecodable payload) — keep the
 *   placeholder so we never fall through to raw-base64 text mode.
 */
private sealed interface ImageLoadState {
    data object Idle : ImageLoadState
    data object NotImage : ImageLoadState
    data object Loading : ImageLoadState
    data class Loaded(val payload: DecodedImagePayload) : ImageLoadState
    data object Failed : ImageLoadState
}

/**
 * R-02a: decodes a base64 image payload off the main thread with two-pass
 * sampling. The first pass reads only the image bounds ([BitmapFactory.Options]
 * with [BitmapFactory.Options.inJustDecodeBounds] = true, no pixel allocation);
 * [ImageDecodeUtils.calcInSampleSize] then picks the smallest power-of-two
 * sample that keeps the decoded long edge at or below [targetPx]; the second
 * pass decodes the actual (downsampled) bitmap. This bounds peak memory
 * regardless of source size.
 *
 * Multiple base64 candidate spellings are attempted (raw + whitespace/newline-
 * stripped) because some server transports wrap or pad the payload. A candidate
 * whose bounds probe reports non-positive dimensions is treated as corrupt and
 * skipped (returns null if no candidate decodes).
 */
private suspend fun decodeImagePayloadSampled(rawContent: String, targetPx: Int): DecodedImagePayload? {
    val candidates = listOf(
        rawContent,
        rawContent.replace("\n", "").replace("\r", "").replace(" ", "")
    ).distinct()

    for (candidate in candidates) {
        val bytes = try {
            Base64.decode(candidate, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            continue
        }
        // Pass 1: probe dimensions without allocating pixel memory.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = ImageDecodeUtils.calcInSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        if (sample <= 0) continue // bounds probe failed (outWidth/outHeight <= 0): corrupt/unknown
        // Pass 2: real decode at the computed sample size.
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: continue
        return DecodedImagePayload(bytes = bytes, bitmap = bitmap)
    }

    return null
}

/**
 * R-02a: placeholder rendered while [decodeImagePayloadSampled] is in flight
 * (or after it failed). Keeps the pane off the raw-base64 text fallback.
 */
@Composable
private fun ImageDecodePlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

private fun shareImage(context: Context, path: String, bytes: ByteArray) {
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    val fileName = path.substringAfterLast('/').ifBlank { "image" }
    val shareFile = File(sharedDir, fileName)
    shareFile.writeBytes(bytes)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        shareFile
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = FilePreviewUtils.imageMimeType(path)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(
        Intent.createChooser(intent, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
