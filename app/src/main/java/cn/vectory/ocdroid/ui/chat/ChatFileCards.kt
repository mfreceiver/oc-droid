package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.ui.theme.opencode
import cn.vectory.ocdroid.ui.util.DataUriImageTransformer

// ── File / folder / image attachment cards ───────────────────────────────
// FileCard (read/write/edit/patch tool with file navigation) + its directory
// listing bottom sheet (FolderContents), plus the inline image attachment and
// generic file attachment parts rendered inside PartView.

@Composable
internal fun ImageFilePart(part: Part, modifier: Modifier = Modifier.fillMaxWidth()) {
    // §16.3 (评审 Stage C #6): reuse [DataUriImageTransformer]'s LruCache so
    // the same data-URI is decoded at most once across the chat surface and
    // inline markdown. The transformer already handles memory caching and
    // Compose-side `remember` keyed by URL — keeping a separate decoder here
    // would silently bypass the cache and re-decode on every recomposition.
    val imageData = part.url?.let { DataUriImageTransformer.transform(it) }
    if (imageData == null) {
        FileAttachmentPart(part, modifier)
        return
    }
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Image(
            painter = imageData.painter,
            contentDescription = part.filename ?: "Attached image",
            modifier = Modifier
                .fillMaxWidth()
                // §5.6 v2: markdown image radius = 4dp.
                .clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.FillWidth
        )
        part.filename?.let { filename ->
            Text(
                text = filename,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
internal fun FileAttachmentPart(part: Part, modifier: Modifier = Modifier.fillMaxWidth()) {
    // §5.6 v2: layer01 chip at 6dp radius with accentText doc icon.
    val oc = MaterialTheme.opencode
    Surface(
        color = oc.layer01,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = oc.accentText,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = part.filename ?: part.mime ?: "Attached file",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Compact file card for file-operation tools (patch / edit / write / read). Quiet
 * Tech styling: neutral surface body, single blue accent on the doc icon, monospace
 * basename, chevron to navigate. When the part is a `read` of a *directory* (server
 * reports `<type>directory</type>`), the card switches to a folder icon and tapping
 * opens a bottom sheet listing the already-returned `<entries>` — no API call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileCard(
    part: Part,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    // Prefer an explicit path; fall back to the first navigable path so the card
    // always has a label even when metadata.path is absent (matches iOS priority).
    val displayPath = part.metadata?.path?.takeIf { it.isNotEmpty() }
        ?: part.state?.pathFromInput?.takeIf { it.isNotEmpty() }
        ?: part.filePathsForNavigation.firstOrNull()
    val basename = displayPath?.takeIf { it.isNotEmpty() }
        ?.substringAfterLast("/")?.takeIf { it.isNotEmpty() }
        ?: "file"

    val isDirectoryRead = ToolCardClassifier.isDirectoryRead(part)
    val isReadOnlyFileTool = part.tool?.lowercase()?.let { tool ->
        ToolCardClassifier.readToolPrefixes.any { tool.startsWith(it) }
    } == true
    var showFolderSheet by remember { mutableStateOf(false) }

    // testTag encodes the read/write nature of the card so the semantics tree can
    // distinguish them. A directory listing is always a read. For files, the tool
    // prefix decides: readToolPrefixes -> read, otherwise write/edit/patch -> write.
    // Layers 2-4 (component / integration-UI / LLM-driven) all rely on this; the icon
    // color alone is invisible to the semantics tree.
    val tag = when {
        isDirectoryRead -> "toolcard.folder.$basename"
        isReadOnlyFileTool -> "toolcard.read.$basename"
        else -> "toolcard.write.$basename"
    }
    val iconDescription = when {
        isDirectoryRead -> "Read directory $basename"
        isReadOnlyFileTool -> "Read file $basename"
        else -> "Write file $basename"
    }

    // §5.3 v2 file card: layer01 surface at 6dp with borderBase, accentText icon.
    val oc = MaterialTheme.opencode
    Surface(
        color = oc.layer01,
        border = BorderStroke(1.dp, oc.borderBase),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
            .padding(vertical = 4.dp)
            .testTag(tag)
            .clickable {
                if (isDirectoryRead) {
                    showFolderSheet = true
                } else {
                    val paths = part.filePathsForNavigation
                    val target = paths.firstOrNull() ?: displayPath
                    if (target != null) onFileClick(target)
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDirectoryRead) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = iconDescription,
                modifier = Modifier.size(16.dp),
                tint = oc.accentText
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = basename,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = BundledMonoFamily,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }

    if (showFolderSheet) {
        val entries = remember(part.id, part.toolOutput) {
            ToolCardClassifier.parseDirectoryEntries(part.toolOutput)
        }
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showFolderSheet = false },
            sheetState = sheetState,
            modifier = Modifier.testTag("toolcard.folder.sheet.$basename")
        ) {
            FolderContents(folderName = basename, entries = entries)
        }
    }
}

/** Sheet body listing a read directory's contents. Subdirectories sort above files. */
@Composable
internal fun FolderContents(
    folderName: String,
    entries: List<ToolCardClassifier.DirectoryEntry>
) {
    val oc = MaterialTheme.opencode
    val sorted = remember(entries) {
        entries.sortedWith(
            compareByDescending<ToolCardClassifier.DirectoryEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = folderName,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = BundledMonoFamily,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (sorted.isEmpty()) {
            Text(
                text = "This directory has no entries.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            sorted.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .testTag("toolcard.folder.entry.${entry.name}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = oc.accentText
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = BundledMonoFamily,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
    }
}
