package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * Jump-to-bottom FAB — shown when the user has scrolled away from the latest
 * message during streaming (`followBottom == false`). Tapping re-enables
 * follow-bottom and animates to index 0 (newest, reverseLayout bottom).
 *
 * Separate from [ChatMessageNavFab] (which navigates between user messages):
 * this FAB is a single button at bottom-center; the nav FAB is a paired
 * up/down cluster at bottom-end. The two can coexist when the user scrolled
 * up (both are visible).
 */
@Composable
internal fun ChatJumpToBottomFab(
    visible: Boolean,
    listState: LazyListState,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = modifier,
    ) {
        FloatingActionButton(
            onClick = {
                onJump()
                scope.launch { listState.animateScrollToItem(0) }
            },
        ) {
            Icon(
                Icons.Default.ArrowDownward,
                contentDescription = "Jump to latest",
            )
        }
    }
}
