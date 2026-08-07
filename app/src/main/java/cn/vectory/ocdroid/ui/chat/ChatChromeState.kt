// ChatChromeState.kt — chrome/overlay state holder extracted from ChatScaffold.
// Contains the overlay flags (showAgentPicker / showModelPicker / showSessionPicker
// / pendingWorkdirPick — 4 rememberSaveable as ONE ordered block preserving slot
// positionality), dialog flags (remember), drawer state/actions, snackbar host,
// and image picker.
//
// §5.2 extraction targets:
//  • showAgentPicker / showModelPicker / showSessionPicker / pendingWorkdirPick
//    (rememberSaveable)                     — :345-347, :365
//  • errorDetail / showTodoDialog /
//    showContextDialog / showForceAbortConfirm (remember) — :348, :354-357
//  • drawerState / openDrawerAction / closeDrawerAction      — :407-422
//  • snackbarHostState                                        — :374
//  • imagePicker / onAddImages                                — :426-435

package cn.vectory.ocdroid.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cn.vectory.ocdroid.ui.ComposerViewModel
import kotlinx.coroutines.launch

/**
 * §Item15b: chrome/overlay state holder for [ChatScaffold]. Contains overlay
 * picker flags, dialog flags, drawer state/actions, snackbar host, and image
 * picker action.
 *
 * Non-negotiable invariant: the 4 `rememberSaveable` flags move as ONE ordered
 * block inside the factory, preserving their relative slot positionality so
 * [ChatScaffoldSaveableTest] stays green unmodified.
 */
internal class ChatChromeState(
    // ── Saveable flags (ONE ordered block) ──────────────────────────────────
    _showAgentPicker: MutableState<Boolean>,
    _showModelPicker: MutableState<Boolean>,
    _showSessionPicker: MutableState<Boolean>,
    _pendingWorkdirPick: MutableState<Boolean>,
    // ── Dialog flags (plain remember) ───────────────────────────────────────
    _errorDetail: MutableState<String?>,
    _showTodoDialog: MutableState<Boolean>,
    _showContextDialog: MutableState<Boolean>,
    _showForceAbortConfirm: MutableState<Boolean>,
    // ── Drawer / snackbar / image ───────────────────────────────────────────
    val drawerState: DrawerState,
    val openDrawerAction: () -> Unit,
    val closeDrawerAction: () -> Unit,
    val snackbarHostState: SnackbarHostState,
    val onAddImages: () -> Unit,
) {
    // Saveable flags
    var showAgentPicker by _showAgentPicker
    var showModelPicker by _showModelPicker
    var showSessionPicker by _showSessionPicker
    var pendingWorkdirPick by _pendingWorkdirPick

    // Dialog flags
    var errorDetail by _errorDetail
    var showTodoDialog by _showTodoDialog
    var showContextDialog by _showContextDialog
    var showForceAbortConfirm by _showForceAbortConfirm
}

/**
 * §Item15b: remember-factory for chrome/overlay state. Creates the 4
 * `rememberSaveable` flags as ONE ordered block (preserving slot positionality),
 * plus `remember` flags, drawer state + actions, snackbar host, and image
 * picker. Accepts [ComposerViewModel] for the image-picker callback.
 *
 * @param composerVM  used to add image attachments when the picker returns URIs.
 * @param onOpenDrawer external hook fired when the drawer opens (tablet menu).
 */
@Composable
internal fun rememberChatChromeState(
    composerVM: ComposerViewModel,
    onOpenDrawer: () -> Unit = {},
): ChatChromeState {
    // ── Saveable flags — ONE ordered block, SLOT-POSITIONALITY PRESERVED ────
    val showAgentPicker = rememberSaveable { mutableStateOf(false) }
    val showModelPicker = rememberSaveable { mutableStateOf(false) }
    val showSessionPicker = rememberSaveable { mutableStateOf(false) }
    val pendingWorkdirPick = rememberSaveable { mutableStateOf(false) }

    // ── Dialog / error flags (plain remember) ──────────────────────────────
    val errorDetail = remember { mutableStateOf<String?>(null) }
    val showTodoDialog = remember { mutableStateOf(false) }
    val showContextDialog = remember { mutableStateOf(false) }
    val showForceAbortConfirm = remember { mutableStateOf(false) }

    // ── Context / scope ────────────────────────────────────────────────────
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── Snackbar host ──────────────────────────────────────────────────────
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Drawer state ───────────────────────────────────────────────────────
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val openDrawerAction: () -> Unit = {
        scope.launch {
            if (drawerState.isOpen) drawerState.close() else drawerState.open()
        }
        onOpenDrawer()
    }
    val closeDrawerAction: () -> Unit = remember(scope, drawerState) {
        { scope.launch { drawerState.close() } }
    }

    // ── Image picker ───────────────────────────────────────────────────────
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        scope.launch {
            composerVM.addImageAttachments(loadImageAttachments(context, uris))
        }
    }
    val onAddImages: () -> Unit = remember(imagePicker) {
        { imagePicker.launch("image/*") }
    }

    return ChatChromeState(
        _showAgentPicker = showAgentPicker,
        _showModelPicker = showModelPicker,
        _showSessionPicker = showSessionPicker,
        _pendingWorkdirPick = pendingWorkdirPick,
        _errorDetail = errorDetail,
        _showTodoDialog = showTodoDialog,
        _showContextDialog = showContextDialog,
        _showForceAbortConfirm = showForceAbortConfirm,
        drawerState = drawerState,
        openDrawerAction = openDrawerAction,
        closeDrawerAction = closeDrawerAction,
        snackbarHostState = snackbarHostState,
        onAddImages = onAddImages,
    )
}
