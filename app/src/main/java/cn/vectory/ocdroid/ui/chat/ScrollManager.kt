package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import cn.vectory.ocdroid.ui.ScrollCheckpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive

// ── Scroll state holder + pure scroll side-effects ───────────────────────
// Extracted from [ChatMessageList] (§wave1-c r2 god-object split). Owns the
// chat list's scroll-control surface that depends ONLY on scroll state +
// sessionId + the hoisted per-session position cache:
//   - the per-session LazyListState + saveable followBottom
//   - NavFab visibility / navJump guard / restore-in-flight guard
//   - direction detection (followBottom + NavFab visibility from scroll delta)
//   - unified bottom-position tracker (followBottom from canScrollBackward)
//   - per-session scroll-offset mirroring into the LRU cache
//   - checkpoint capture for sub-agent navigation
//
// The THREE MIXED scroll effects stay in ChatMessageList because they read
// NON-scroll state and would drag it in if moved:
//   - reselect-scroll   : reads orchestratorVM.reselectFlow
//   - content-version   : reads messages.size / isStreaming /
//                          streamingPartTexts.keys / streamingReasoningPart /
//                          expandedParts
//   - Restore/Latest    : reads chatState.pendingScrollRequest / messages.isEmpty() /
//                          lazyColumnKeys
// Those effects read/write through [ScrollController]'s exposed properties
// (followBottom / listState / pendingRestoreSession / navFabVisible) so the
// shared MutableState instances are the same ones these pure effects mutate.
//
// BEHAVIOR-PRESERVATION CONTRACT (verified against HEAD c2f338ae):
//  - listState + followBottom keep `rememberSaveable(sessionId, …)` with the
//    SAME Saver / initializer. rememberScrollController() is called at a
//    stable position in ChatMessageList's body (no conditional), so the
//    SaveableStateHolder maps the saveable slots identically — scroll memory
//    for Chat→preview→back / session re-entry is unchanged.
//  - The 5 pure LaunchedEffects keep their EXACT key tuples:
//        mirror        : (listState, sessionId)
//        direction     : (listState, sessionId)
//        bottom-track  : (listState, sessionId)
//        navFab-hide   : (navFabTick)
//        session-reset : (sessionId)
//    Effect bodies are byte-for-byte the prior logic (guards, drop(1),
//    absDelta>3, navJumping / pendingRestoreSession skips all preserved).

/**
 * Mutable holder for the chat list's scroll-control state. The underlying
 * [MutableState] instances are created by [rememberScrollController] (which
 * keeps the rememberSaveable registration in the composable scope) and shared
 * by reference, so writes from the mixed effects in ChatMessageList are
 * observed by the pure effects here (and vice-versa) with zero copy.
 */
internal class ScrollController(
    val listState: LazyListState,
    private val followBottomState: MutableState<Boolean>,
    private val navFabVisibleState: MutableState<Boolean>,
    private val navFabTickState: MutableIntState,
    private val navJumpingState: MutableState<Boolean>,
    private val pendingRestoreSessionState: MutableState<String?>,
) {
    var followBottom: Boolean
        get() = followBottomState.value
        set(value) { followBottomState.value = value }

    var navFabVisible: Boolean
        get() = navFabVisibleState.value
        set(value) { navFabVisibleState.value = value }

    var navFabTick: Int
        get() = navFabTickState.intValue
        set(value) { navFabTickState.intValue = value }

    var navJumping: Boolean
        get() = navJumpingState.value
        set(value) { navJumpingState.value = value }

    var pendingRestoreSession: String?
        get() = pendingRestoreSessionState.value
        set(value) { pendingRestoreSessionState.value = value }

    /**
     * §Wave5b-Q13: capture the PARENT's scroll checkpoint SYNCHRONOUSLY at the
     * click site, before delegating navigation. Built from the LIVE listState
     * (first visible item's key + index + offset) so the parent's exact
     * viewport is recoverable on return. Oracle explicitly forbade reading
     * from the async savedPositions mirror — it cannot guarantee the last
     * pre-navigation frame.
     */
    fun captureCheckpoint(): ScrollCheckpoint = ScrollCheckpoint(
        anchorKey = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key?.toString(),
        fallbackIndex = listState.firstVisibleItemIndex,
        offset = listState.firstVisibleItemScrollOffset,
    )
}

/**
 * Creates the per-session [ScrollController] and runs the FIVE pure scroll
 * side-effects. The saveable state (listState, followBottom) is keyed by
 * [sessionId] exactly as before the extraction, so a real sessionId change
 * re-initializes while a same-session re-entry (Chat→preview→back) restores.
 *
 * @param sessionId active chat session id (null ⇒ effects no-op)
 * @param savedPositions hoisted per-session scroll-position cache (owned by
 *   ChatScaffold; WRITE-ONLY today — restore consumer removed, retained for a
 *   future cross-session restore). Mutated by the mirror effect.
 * @param accessOrder parallel LRU ledger for [savedPositions] (SnapshotStateMap
 *   has HashMap semantics, so index-stable SnapshotStateList carries true LRU
 *   eviction order).
 */
@Composable
internal fun rememberScrollController(
    sessionId: String?,
    savedPositions: SnapshotStateMap<String, Pair<Int, Int>>,
    accessOrder: SnapshotStateList<String>,
): ScrollController {
    val listState = rememberSaveable(sessionId, saver = LazyListState.Saver) { LazyListState() }
    val followBottomState = rememberSaveable(sessionId) { mutableStateOf(true) }
    var followBottom by followBottomState
    val navFabVisibleState = remember { mutableStateOf(false) }
    var navFabVisible by navFabVisibleState
    val navFabTickState = remember { mutableIntStateOf(0) }
    var navFabTick by navFabTickState
    val navJumpingState = remember { mutableStateOf(false) }
    var navJumping by navJumpingState
    val pendingRestoreSessionState = remember { mutableStateOf<String?>(null) }
    var pendingRestoreSession by pendingRestoreSessionState

    // #3 — continuously mirror the current scroll offset against the active
    // session id. There is no "before session change" hook in Compose, so a
    // reactive mirror is the simplest robust way to ensure the latest offset
    // is on file the instant the user navigates into a sub-session.
    //
    // 🔴 Race fix (glmer 🔴-1 + kimo 🔴-1): when `sessionId` changes this
    // LaunchedEffect re-launches, and `snapshotFlow` emits its *current* value
    // on the first collection — which is still the *previous* session's scroll
    // position. Writing that stale position to `savedPositions[newSessionId]`
    // clobbered the new session's true history. Two guards fix this:
    //   (1) `.drop(1)` — skip the first emit on each (re)launch.
    //   (2) `pendingRestoreSession == sessionId` — skip writes while a restore
    //       is in flight (programmatic scrolls must NOT be recorded).
    LaunchedEffect(listState, sessionId) {
        if (sessionId == null) return@LaunchedEffect
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .drop(1)
            .collect { pos ->
                if (pendingRestoreSession == sessionId) return@collect
                savedPositions[sessionId] = pos
                accessOrder.remove(sessionId)
                accessOrder.add(sessionId)
                while (savedPositions.size > MAX_SAVED_SESSIONS && accessOrder.isNotEmpty()) {
                    val oldest = accessOrder.removeAt(0)
                    savedPositions.remove(oldest)
                }
            }
    }

    // ── 滚动方向检测 ─────────────────────────────────────────────────────
    // reverseLayout semantics: index 0 = newest (visual bottom); larger index
    // = older (visual top). delta>0 ⇒ scrolling toward older; delta<0 ⇒ toward
    // newer. Task: toward-newer shows NavFab, hides on reaching bottom.
    // Guards: delay(300) post session-switch (let auto-scroll/restore settle),
    // pendingRestoreSession skip, |delta|>3 (programmatic) skip, navJumping skip.
    LaunchedEffect(listState, sessionId) {
        if (sessionId == null) return@LaunchedEffect
        delay(300)
        var prevIndex = listState.firstVisibleItemIndex
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (navJumping) {
                    prevIndex = index
                    return@collect
                }
                if (pendingRestoreSession == sessionId) {
                    prevIndex = index
                    return@collect
                }
                if (listState.layoutInfo.totalItemsCount == 0) {
                    prevIndex = index
                    return@collect
                }
                val delta = index - prevIndex
                prevIndex = index
                val absDelta = if (delta < 0) -delta else delta
                if (absDelta > 3) return@collect
                when {
                    delta > 0 -> {
                        followBottom = false
                        navFabVisible = false
                    }
                    delta < 0 -> {
                        if (index == 0) {
                            followBottom = true
                            navFabVisible = false
                        } else {
                            navFabVisible = true
                            navFabTick++
                        }
                    }
                }
            }
    }

    // §Q4-scroll-track: unified bottom-position tracker. Watches
    // canScrollBackward + firstVisibleItemIndex + scrollOffset together so ANY
    // position change triggers an update. atExactBottom guard distinguishes
    // "user genuinely at bottom" from "content grew above but user hasn't
    // moved". followBottom = if (canBack) atExactBottom else true.
    LaunchedEffect(listState, sessionId) {
        if (sessionId == null) return@LaunchedEffect
        delay(300)
        snapshotFlow {
            Triple(
                listState.canScrollBackward,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
            .drop(1)
            .collect { (canBack, index, offset) ->
                if (pendingRestoreSession == sessionId) return@collect
                if (listState.layoutInfo.totalItemsCount == 0) return@collect
                val atExactBottom = index == 0 && offset <= 24
                followBottom = if (canBack) atExactBottom else true
            }
    }

    // §navfab-redesign: 3s idle auto-hide (navFabTick bumps on show/interact).
    LaunchedEffect(navFabTick) {
        if (navFabVisible) {
            delay(3000)
            navFabVisible = false
        }
    }

    // §B1: on session enter do NOT force followBottom (it is saveable now).
    // Reset only the plain-remember scroll flags (fresh on re-entry anyway).
    LaunchedEffect(sessionId) {
        pendingRestoreSession = null
        navFabVisible = false
        navJumping = false
    }

    return ScrollController(
        listState = listState,
        followBottomState = followBottomState,
        navFabVisibleState = navFabVisibleState,
        navFabTickState = navFabTickState,
        navJumpingState = navJumpingState,
        pendingRestoreSessionState = pendingRestoreSessionState,
    )
}

/**
 * 🟡 (glmer 🟡-6) Max per-session scroll positions retained in the hoisted
 * `savedPositions` cache. WRITE-ONLY today (restore consumer removed); cap +
 * LRU retained so a future restore consumer lands on a bounded cache.
 */
internal const val MAX_SAVED_SESSIONS = 30
