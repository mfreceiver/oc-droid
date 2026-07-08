package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.ui.theme.AppMotion
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import cn.vectory.ocdroid.ui.theme.SemanticColors

/**
 * §issue-1(1): 会话级文件变更卡片。数据来自 SessionListState.sessionDiffs
 * （GET /session/{id}/diff 初始拉取 + session.diff SSE 增量覆盖）。
 *
 * 渲染为聊天 timeline 内的单张 accordion：
 *  - 折叠态：单行 header——图标 + 标题 + 文件数 + 聚合 +X -Y + 展开箭头。
 *  - 展开态：每文件一行（状态色点 + basename + 路径 + 该文件 +n -m + 打开图标），
 *    点按文件行切换其 unified patch 文本显隐（可滚、+/- 行着色）。
 *
 * 视觉语言对齐 [MultiFilePatchAccordion]（labelSmall + BundledMonoFamily +
 * SemanticColors），保持工具卡片家族一致。纯展示组件，不持有状态业务。
 *
 * @param sessionId 当前会话 id——用于 [rememberSaveable] key 维度，避免不同会话
 *  首个 diff 文件同名时展开态跨会话串读（maxer B1）。
 * @param diffs 当前会话的文件变更列表；调用方保证非空（空则不渲染本卡片）。
 * @param onFileClick 点击文件行尾"打开"图标时回调（全路径）。
 */
@Composable
internal fun SessionDiffCard(
    sessionId: String,
    diffs: List<FileDiff>,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (diffs.isEmpty()) return

    // 聚合统计：服务端给的 additions/deletions 可能缺省，按 0 计。
    val (totalAdd, totalDel) = remember(diffs) {
        var a = 0
        var d = 0
        for (fd in diffs) {
            a += fd.additions ?: 0
            d += fd.deletions ?: 0
        }
        a to d
    }

    // rememberSaveable 以 sessionId + 首文件 id 复合为 key：列表替换时不继承旧展开态，
    // 且不同会话首个 diff 文件同名也不会串读（maxer B1）。
    var expanded by rememberSaveable(sessionId, diffs.firstOrNull()?.id) { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("session.diff.card"),
        shape = RectangleShape,
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent
    ) {
        Column(modifier = Modifier.animateContentSize(AppMotion.expandSizeSpec)) {
            // ── Header ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Difference,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.session_diff_card_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.session_diff_files_count, diffs.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.weight(1f))
                if (totalAdd > 0) {
                    Text(
                        text = "+$totalAdd",
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.stateSuccessFg(),
                        fontFamily = BundledMonoFamily
                    )
                }
                if (totalDel > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "-$totalDel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = BundledMonoFamily
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    contentDescription = stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Per-file rows ─────────────────────────────────────────
            if (expanded) {
                diffs.forEachIndexed { index, fd ->
                    DiffFileRow(sessionId = sessionId, indexInGroup = index, fd = fd, onFileClick = onFileClick)
                }
            }
        }
    }
}

@Composable
private fun DiffFileRow(sessionId: String, indexInGroup: Int, fd: FileDiff, onFileClick: (String) -> Unit) {
    val path = fd.file
    val basename = path.substringAfterLast("/").ifEmpty { path }
    val fileAdd = fd.additions ?: 0
    val fileDel = fd.deletions ?: 0
    val statusColor = statusColor(fd.status)
    // 文件级二级展开：点 basename 行切换 patch 文本显隐。key 复合 sessionId + 索引 + fd.id
    // （maxer-B1 / 🟡-1）：sessionId 防跨会话串读；indexInGroup 兜底 fd.id 为空串（服务端
    // 异常缺 file/path 字段时）时同会话内多条空 id diff 的 patchOpen 串读。
    var patchOpen by rememberSaveable(sessionId, indexInGroup, fd.id) { mutableStateOf(false) }
    val patchText = fd.patch
    val canExpandPatch = !patchText.isNullOrBlank()
    val expandLabel = stringResource(if (patchOpen) R.string.common_collapse else R.string.common_expand)
    val openFileLabel = stringResource(R.string.session_diff_open_file)

    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 文件行主体（状态色点 + basename + 路径 + +/- 统计）——点击切换 patch 展开。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = canExpandPatch) { patchOpen = !patchOpen },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(20.dp))
                Spacer(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color = statusColor, shape = CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = basename,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (fileAdd > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "+$fileAdd",
                        style = MaterialTheme.typography.labelSmall,
                        color = SemanticColors.stateSuccessFg(),
                        fontFamily = BundledMonoFamily
                    )
                }
                if (fileDel > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "-$fileDel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = BundledMonoFamily
                    )
                }
                if (canExpandPatch) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        if (patchOpen) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                        contentDescription = expandLabel,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // §gpter-B1 / kimo：文件行尾"打开"affordance——对齐 MultiFilePatchAccordion /
            //  ChatPatchCards 家族约定，兑现 onFileClick（之前是死参数）。
            if (path.isNotEmpty()) {
                IconButton(onClick = { onFileClick(path) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = openFileLabel,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (patchOpen && canExpandPatch && patchText != null) {
            DiffPatchView(
                patch = patchText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, bottom = 4.dp, end = 4.dp)
            )
        }
    }
}

/**
 * Unified diff 文本渲染。逐行着色（+ 绿 / - 红 / @@ 段头 主色 / 头部 meta 灰），
 * 等宽字体（项目统一 BundledMonoFamily），**限高可滚**（glmer-B2 / kimo / maxer-S2）。
 *
 * 背景色用前景色的低 alpha（maxer-B2）：避免 [SemanticColors.addedLine]/[deletedLine]
 * 固定浅色在暗色主题下刺眼；跟随主题前景色派生，明暗皆柔和。
 *
 * §P3-3A: renders the patch (up to a generous MAX_LINES cap that bounds the
 * one-time AnnotatedString + layout cost — Compose Text virtualises DRAWING by
 * visible region but lays out the whole string to compute scroll extent) into a
 * colourised AnnotatedString, bounded by heightIn + verticalScroll so large
 * diffs scroll. Real session diffs stay untruncated; only pathological patches
 * hit the cap, with a trailing note.
 */
@Composable
private fun DiffPatchView(patch: String, modifier: Modifier = Modifier) {
    val addFg = SemanticColors.stateSuccessFg()
    val delFg = MaterialTheme.colorScheme.error
    val hunkFg = MaterialTheme.colorScheme.primary
    val metaFg = MaterialTheme.colorScheme.onSurfaceVariant
    val ctxFg = MaterialTheme.colorScheme.onSurface
    // §maxer-B2：背景由前景色派生低 alpha，暗色主题不再刺眼。
    val addBg = addFg.copy(alpha = 0.16f)
    val delBg = delFg.copy(alpha = 0.16f)

    val totalLines = remember(patch) { patch.lineSequence().count() }
    // §gpter-复审：截断提示走 i18n（避免 dangling 的 session_diff_truncated 资源）。
    val truncationSuffix = stringResource(R.string.session_diff_truncated, MAX_LINES, totalLines)
    val annotated = remember(patch, addFg, delFg, hunkFg, metaFg, ctxFg, addBg, delBg, truncationSuffix) {
        buildAnnotatedDiff(patch, addFg, delFg, hunkFg, metaFg, ctxFg, addBg, delBg, totalLines, truncationSuffix)
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        // heightIn 限高 + verticalScroll：渲染不超过 MAX_LINES 行（§P3-3A：慷慨上限，
        // 真实 diff 不会被截断；仅病态大 patch 触顶并附尾注），patch 可内部滚动；
        // DiffPatchView 位于 LazyColumn item 内的 Column 中，限高后内层 verticalScroll
        // 的手势由 Compose 嵌套滚动正常承载。Compose Text 只对绘制按可见区域虚拟化
        // （布局仍对整个 AnnotatedString 一次性测量），故用 MAX_LINES 限制该一次性成本。
        Text(
            text = annotated,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = BundledMonoFamily,
                lineHeight = MaterialTheme.typography.labelSmall.fontSize * 1.2f
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        )
    }
}

private const val MAX_LINES = 2000

private fun buildAnnotatedDiff(
    patch: String,
    addFg: Color,
    delFg: Color,
    hunkFg: Color,
    metaFg: Color,
    ctxFg: Color,
    addBg: Color,
    delBg: Color,
    totalLines: Int,
    truncationSuffix: String
): AnnotatedString = buildAnnotatedString {
    // §P3-3A: render up to MAX_LINES lines. Compose Text virtualises DRAWING by
    // visible region (the scroll only rasterises visible glyphs), but it still
    // LAYS OUT the whole AnnotatedString once to compute the scroll extent — so
    // an unbounded patch would allocate/jank on huge diffs. MAX_LINES=2000 is a
    // generous safety bound (real session diffs are far smaller): it bounds the
    // one-time AnnotatedString + layout cost while never truncating a realistic
    // patch; a trailing note explains the rare cap. `take()` streams without
    // materialising the full line list.
    val renderCount = minOf(totalLines, MAX_LINES)
    patch.lineSequence().take(renderCount).forEachIndexed { i, line ->
        if (i > 0) append("\n")
        val color: Color
        val bg: Color
        when {
            line.startsWith("@@") -> { color = hunkFg; bg = Color.Transparent }
            line.startsWith("+++") || line.startsWith("---") -> { color = metaFg; bg = Color.Transparent }
            line.startsWith("+") -> { color = addFg; bg = addBg }
            line.startsWith("-") -> { color = delFg; bg = delBg }
            line.startsWith("\\") -> { color = metaFg; bg = Color.Transparent }
            else -> { color = ctxFg; bg = Color.Transparent }
        }
        pushStyle(SpanStyle(color = color, background = bg))
        append(line)
        pop()
    }
    if (totalLines > MAX_LINES) {
        pushStyle(SpanStyle(color = metaFg))
        if (renderCount > 0) append("\n")
        append(truncationSuffix)
        pop()
    }
}

private fun statusColor(status: String?): Color = when (status?.lowercase()) {
    "added" -> SemanticColors.addedFile
    "deleted" -> SemanticColors.deletedFile
    "modified" -> SemanticColors.modifiedFile
    else -> SemanticColors.untrackedFile
}
