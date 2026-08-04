package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import cn.vectory.ocdroid.ui.theme.AppTextStyles
import cn.vectory.ocdroid.util.workdirBasename

// ── §watermark-B5: workdir watermark (decorative, non-interactive) ──────────

/**
 * Renders the workspace directory basename as a subtle horizontal watermark
 * behind the message list. Purely decorative — click/pointer/hover are
 * completely silent.
 *
 * §watermark-autosize: font size auto-scales between 32sp and 48sp based
 * on the available width (88% of the container) and text length (CJK-aware
 * double-width estimation).
 *
 * §watermark-B5: replaces the old top-bar workdir-initial icon AND the
 * prior 45°-tilted watermark. Single horizontal line, centered, tinted
 * with [workdirTone], alpha 0.07f.
 *
 * @param workspaceDirectory the session's workspace directory path (nullable)
 * @param modifier composable modifier for the outer container
 */
@Composable
internal fun WorkdirWatermark(
    workspaceDirectory: String?,
    modifier: Modifier = Modifier,
) {
    workspaceDirectory?.let { dir ->
        val basename = dir.workdirBasename() ?: dir
        if (basename.isNotBlank()) {
        BoxWithConstraints(
            modifier = modifier.fillMaxWidth(0.88f),
            ) {
                val density = LocalDensity.current
                val availWidthPx = with(density) { maxWidth.toPx() }
                // CJK 双宽启发式：code > 0x2E80 覆盖 CJK Radicals / CJK
                // Unified / Hiragana / Katakana / Hangul 等双宽字符。
                val textLen = basename.sumOf { if (it.code > 0x2E80) 2 else 1 }
                    .coerceAtLeast(1)
                val rawSizePx = availWidthPx / (textLen * 0.62f)
                val minPx = with(density) { 32.sp.toPx() }
                val maxPx = with(density) { 48.sp.toPx() }
                val fontSize = with(density) {
                    rawSizePx.coerceIn(minPx, maxPx).toSp()
                }
                Text(
                    text = basename,
                    modifier = Modifier.align(Alignment.Center),
                    color = workdirTone(dir).copy(alpha = 0.07f),
                    style = AppTextStyles.watermark.copy(fontSize = fontSize),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
