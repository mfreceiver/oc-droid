// ChatSelectionToolbar.kt — Self-rendering TextContextMenuProvider that
// replaces the platform's default selection toolbar (ActionMode) with a
// Compose Popup. This is necessary because wrapping the default
// LocalTextContextMenuToolbarProvider with a delegating custom provider
// does NOT work: ProvideDefaultPlatformTextContextMenuProviders (inside
// each SelectionContainer) skips installing the platform toolbar when it
// detects a non-null local — so the delegate target is null and no toolbar
// renders. (Root cause found by Oracle via foundation 1.10 bytecode analysis.)
//
// The provider receives the enriched/filtered TextContextMenuData (Copy +
// Copy full message + Fork from the append/filter modifiers on MessageCard's
// Column), captures the selection bounds via dataProvider.contentBounds(),
// and renders a floating Popup above the selection. awaitCancellation()
// suspends until the handler cancels (selection cleared or item tapped).
//
// Side effect: LocalSelectionToolbarVisible signals MessageCard to dismiss
// its DropdownMenu when the selection toolbar shows, resolving the
// dual-trigger overlap (combinedClickable + SelectionContainer both fire
// on long-press in Compose 1.10).

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cn.vectory.ocdroid.ui.theme.Dimens
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CompletableDeferred

/**
 * Signals that the text selection toolbar is currently visible.
 * MessageCard reads this to dismiss its DropdownMenu (mutual exclusion).
 */
internal val LocalSelectionToolbarVisible = compositionLocalOf { false }

/**
 * Wraps [content] with a self-rendering [TextContextMenuProvider] that shows
 * a Compose Popup toolbar when text is selected. The provider captures the
 * enriched menu items (from append/filter modifiers) and selection bounds
 * from the framework's [TextContextMenuDataProvider], then renders its own
 * floating toolbar instead of delegating to the platform ActionMode.
 *
 * Also provides [LocalSelectionToolbarVisible] so descendants (MessageCard)
 * can detect when the selection toolbar is showing and dismiss their own
 * DropdownMenu to avoid dual-popups on long-press.
 *
 * @param content the chat message list (must contain the SelectionContainers).
 */
@Composable
internal fun ChatSelectionToolbarHost(
    content: @Composable () -> Unit,
) {
    val visibleState = remember { mutableStateOf(false) }
    val componentsState = remember { mutableStateOf<List<TextContextMenuComponent>>(emptyList()) }
    val selectionRectState = remember { mutableStateOf(Rect.Zero) }
    val containerCoordsState = remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Session captured inside the provider's suspend scope and handed to each
    // item's onClick (component.onClick(session)). Items call session.close()
    // after performing their action; close() completes the provider's
    // closeSignal so its suspend function returns and the finally block hides
    // the Popup. Mirrors BasicTextContextMenuProvider's SessionImpl + channel.
    val sessionState = remember { mutableStateOf<TextContextMenuSession?>(null) }

    val provider = remember {
        object : TextContextMenuProvider {
            override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
                // Capture the selection bounds in window coordinates for
                // Popup positioning. contentBounds returns a Rect in the
                // destinationCoordinates' local space; convert via localToWindow.
                val coords = containerCoordsState.value
                if (coords != null) {
                    val localRect = dataProvider.contentBounds(coords)
                    val tl = coords.localToWindow(localRect.topLeft)
                    val br = coords.localToWindow(localRect.bottomRight)
                    selectionRectState.value = Rect(tl.x, tl.y, br.x, br.y)
                }
                // data() returns the final component list after append/filter
                // modifiers have been applied — Copy + Copy full message + Fork.
                componentsState.value = dataProvider.data().components
                // Session passed to each item's onClick. Items call
                // session.close() after their action (the framework's Copy item
                // and our custom items both follow this contract — see
                // MessageCard's appendTextContextMenuComponents). close()
                // completes closeSignal, making await() below return so the
                // finally block hides the Popup.
                val closeSignal = CompletableDeferred<Unit>()
                val session = object : TextContextMenuSession {
                    override fun close() {
                        closeSignal.complete(Unit)
                    }
                }
                sessionState.value = session
                visibleState.value = true
                try {
                    // Contract: suspend until an item calls session.close()
                    // (completes closeSignal) OR the handler cancels this job
                    // (selection cleared / dismissed) — both run the finally.
                    closeSignal.await()
                } finally {
                    visibleState.value = false
                    sessionState.value = null
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalTextContextMenuToolbarProvider provides provider,
        LocalSelectionToolbarVisible provides visibleState.value,
    ) {
        Box(modifier = Modifier.onGloballyPositioned { containerCoordsState.value = it }) {
            content()
        }
        if (visibleState.value && componentsState.value.isNotEmpty()) {
            val session = sessionState.value
            if (session != null) {
                SelectionToolbarPopup(
                    components = componentsState.value,
                    selectionRect = selectionRectState.value,
                    session = session,
                )
            }
        }
    }
}

/**
 * Renders the floating selection toolbar as a Compose [Popup] positioned
 * above (or below if no room) the selection rectangle. Each item is a
 * tappable label; tapping calls the framework-provided [TextContextMenuItem.onClick]
 * which handles the action (copy text, copy full message, fork) and closes
 * the session — the handler then cancels the provider's suspend function,
 * hiding this Popup via the finally block.
 */
@Composable
private fun SelectionToolbarPopup(
    components: List<TextContextMenuComponent>,
    selectionRect: Rect,
    session: TextContextMenuSession,
) {
    val marginPx = 24 // ~8dp at 3x density; keeps the toolbar clear of handles

    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val centerX = selectionRect.center.x.toInt() - popupContentSize.width / 2
                val aboveY = selectionRect.top.toInt() - popupContentSize.height - marginPx
                val y = if (aboveY >= 0) aboveY else selectionRect.bottom.toInt() + marginPx
                val x = centerX.coerceIn(marginPx, (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx))
                return IntOffset(x, y.coerceAtLeast(marginPx))
            }
        },
        onDismissRequest = {
            // Dismissal is handled by the handler cancelling the provider's
            // suspend function (via selection clearing or item onClick → close).
            // No action needed here.
        },
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = Dimens.spacingCompact,
            shadowElevation = Dimens.spacingCompact,
        ) {
            Row(
                modifier = Modifier.padding(vertical = Dimens.spacing1),
            ) {
                components.forEach { component ->
                    if (component is TextContextMenuItem) {
                        TextButton(
                            onClick = { component.onClick(session) },
                            contentPadding = PaddingValues(start = Dimens.spacing3, end = Dimens.spacing3),
                        ) {
                            Text(
                                text = component.label,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}
