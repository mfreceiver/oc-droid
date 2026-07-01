// ChatImageAttachmentStrip.kt — the horizontal thumbnail strip of pending
// composer image attachments (with per-thumbnail remove buttons and off-main-
// thread decode). Pure relocation from ChatInputBar.kt; the strip is a
// self-contained presentational component shared across the composer surface,
// so it lives in its own file. The visibility was widened private → internal
// because the call site is now in a sibling file (ChatInputBar.kt). No
// behaviour change.

package cn.vectory.ocdroid.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ImageAttachmentStrip(
    attachments: List<ComposerImageAttachment>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            // R-02c: decode the attachment thumbnail off the main thread. Even
            // though thumbnails are small, BitmapFactory.decodeByteArray is
            // blocking native decode and does not belong on the UI thread.
            // produceState returns null on the first frame; the state write
            // after the background decode triggers recomposition so the Image
            // renders once ready. (Same keyed-in-loop pattern as the former
            // `remember`; stable attachment.id keys keep it correct.)
            val bitmap by produceState<ImageBitmap?>(null, attachment.id, attachment.thumbnailData) {
                value = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(
                        attachment.thumbnailData, 0, attachment.thumbnailData.size
                    )?.asImageBitmap()
                }
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (bitmap != null) {
                    // Local val so Kotlin can smart-cast the delegated
                    // produceState property (delegates cannot be smart-cast).
                    val decoded = bitmap!!
                    Image(
                        bitmap = decoded,
                        contentDescription = attachment.filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                // R-12 (WCAG 2.5.5): 36dp touch target wrapping a compact 24dp
                // scrim circle + 14dp X. The scrim stays small so it does not
                // visually dominate the 64dp thumbnail; the larger hit box is
                // centered over it. (Outer Box is the click target; inner Box
                // carries the scrim background + icon — modifier-order safe.)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(36.dp)
                        .clickable { onRemoveImage(attachment.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.chat_remove_image),
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
