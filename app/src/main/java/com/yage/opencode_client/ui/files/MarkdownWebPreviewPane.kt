package com.yage.opencode_client.ui.files

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mikepenz.markdown.m3.Markdown
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.theme.markdownTypographyCompact
import com.yage.opencode_client.ui.util.DataUriImageTransformer
import com.yage.opencode_client.ui.util.MarkdownImageResolver
import org.json.JSONObject

private const val webPreviewMaxTotalLength = 60_000
private const val webPreviewMaxLineLength = 5_000

@Composable
internal fun MarkdownWebPreviewPane(
    content: String,
    filePath: String,
    repository: OpenCodeRepository,
    sessionDirectory: String?,
    onOpenNative: () -> Unit,
    onOpenSource: () -> Unit
) {
    var confirmedOversize by remember(content, filePath) { mutableStateOf(false) }
    val normalizedContent = remember(content) { MarkdownImageResolver.normalizeStandaloneImageBlocks(content) }
    val isOversize = remember(normalizedContent) {
        normalizedContent.length > webPreviewMaxTotalLength ||
            normalizedContent.lineSequence().any { it.length > webPreviewMaxLineLength }
    }

    when {
        isOversize && !confirmedOversize -> WebPreviewOversizeGate(
            onContinue = { confirmedOversize = true },
            onOpenNative = onOpenNative,
            onOpenSource = onOpenSource
        )
        else -> ResolvedMarkdownWebPreview(
            content = normalizedContent,
            filePath = filePath,
            repository = repository,
            sessionDirectory = sessionDirectory,
            onOpenNative = onOpenNative,
            onOpenSource = onOpenSource
        )
    }
}

@Composable
private fun ResolvedMarkdownWebPreview(
    content: String,
    filePath: String,
    repository: OpenCodeRepository,
    sessionDirectory: String?,
    onOpenNative: () -> Unit,
    onOpenSource: () -> Unit
) {
    var resolvedContent by remember(content, filePath) { mutableStateOf(content) }
    var error by remember(content, filePath) { mutableStateOf<String?>(null) }
    val resolverMarkdownPath = remember(filePath, sessionDirectory) {
        resolveRelativePreviewPath(filePath, sessionDirectory)
    }

    LaunchedEffect(content, resolverMarkdownPath, sessionDirectory, repository) {
        error = null
        resolvedContent = try {
            MarkdownImageResolver.resolveImages(
                text = content,
                markdownFilePath = resolverMarkdownPath,
                workspaceDirectory = sessionDirectory,
                fetchContent = { path -> repository.getFileContent(path).getOrThrow() }
            )
        } catch (e: Exception) {
            error = e.message ?: "Failed to resolve Markdown images"
            content
        }
    }

    if (error != null && resolvedContent.isBlank()) {
        WebPreviewError(
            message = error ?: "Web Preview failed",
            onOpenNative = onOpenNative,
            onOpenSource = onOpenSource
        )
    } else {
        MarkdownWebView(
            markdown = resolvedContent,
            onRenderError = { error = it },
            onOpenNative = onOpenNative,
            onOpenSource = onOpenSource
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MarkdownWebView(
    markdown: String,
    onRenderError: (String) -> Unit,
    onOpenNative: () -> Unit,
    onOpenSource: () -> Unit
) {
    val context = LocalContext.current
    val theme = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) "dark" else "light"
    var webContentReady by remember { mutableStateOf(false) }

    LaunchedEffect(markdown, theme) {
        webContentReady = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (webContentReady) 1f else 0f),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    // Avoid a hardware-composited black first frame before the asset shell renders.
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    settings.javaScriptEnabled = true
                    // Required for file:///android_asset preview.html to load sibling JS/CSS assets.
                    // Workspace files still never go through WebView file URLs; images are pre-resolved to data URIs.
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    addJavascriptInterface(
                        AndroidPreviewBridge(
                            onRendered = { webContentReady = true },
                            onError = onRenderError,
                            onExternalLink = { url ->
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                runCatching { context.startActivity(intent) }
                            }
                        ),
                        "AndroidPreviewBridge"
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.renderMarkdown(markdown, theme)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url ?: return true
                            if (uri.scheme == "file" && uri.toString().startsWith("file:///android_asset/web_preview/")) {
                                return false
                            }
                            if (uri.fragment != null && uri.scheme == null) return false
                            return true
                        }
                    }
                    loadUrl("file:///android_asset/web_preview/preview.html")
                }
            },
            update = { webView ->
                webView.renderMarkdown(markdown, theme)
            }
        )

        if (!webContentReady) {
            MarkdownFallbackOverlay(markdown = markdown)
        }
    }
}

private class AndroidPreviewBridge(
    private val onRendered: () -> Unit,
    private val onError: (String) -> Unit,
    private val onExternalLink: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(message: String) {
        val payload = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (payload.optString("type")) {
            "rendered" -> mainHandler.post { onRendered() }
            "error" -> mainHandler.post { onError(payload.optString("message", "Web Preview render error")) }
            "link" -> {
                val href = payload.optString("href")
                if (href.startsWith("http://") || href.startsWith("https://")) {
                    mainHandler.post { onExternalLink(href) }
                }
            }
        }
    }
}

private fun WebView.renderMarkdown(markdown: String, theme: String) {
    val payload = JSONObject()
        .put("markdown", markdown)
        .put("theme", theme)
        .toString()
    evaluateJavascript(
        """
        (function renderWhenReady(attempt) {
          if (typeof window.renderMarkdown === 'function') {
            window.renderMarkdown($payload);
          } else if (attempt < 40) {
            setTimeout(function () { renderWhenReady(attempt + 1); }, 50);
          } else if (document && document.body) {
            document.body.innerHTML = '<pre style="padding:16px;color:#ffd7d7;background:#5a1f1f">Markdown Web Preview failed to load renderer assets.</pre>';
          }
        })(0);
        """.trimIndent(),
        null
    )
}

@Composable
private fun MarkdownFallbackOverlay(markdown: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Markdown(
                    content = markdown,
                    typography = markdownTypographyCompact(),
                    modifier = Modifier.fillMaxWidth(),
                    imageTransformer = DataUriImageTransformer
                )
            }
        }
    }
}

@Composable
private fun WebPreviewOversizeGate(
    onContinue: () -> Unit,
    onOpenNative: () -> Unit,
    onOpenSource: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "This Markdown file is large. Web Preview may be slow or memory-heavy.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onContinue, modifier = Modifier.padding(top = 16.dp)) {
            Text("Open Web Preview")
        }
        OutlinedButton(onClick = onOpenNative, modifier = Modifier.padding(top = 8.dp)) {
            Text("Open Native Preview")
        }
        OutlinedButton(onClick = onOpenSource, modifier = Modifier.padding(top = 8.dp)) {
            Text("Open Markdown Source")
        }
    }
}

@Composable
private fun WebPreviewError(
    message: String,
    onOpenNative: () -> Unit,
    onOpenSource: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onOpenNative, modifier = Modifier.padding(top = 16.dp)) {
            Text("Open Native Preview")
        }
        OutlinedButton(onClick = onOpenSource, modifier = Modifier.padding(top = 8.dp)) {
            Text("Open Markdown Source")
        }
    }
}
