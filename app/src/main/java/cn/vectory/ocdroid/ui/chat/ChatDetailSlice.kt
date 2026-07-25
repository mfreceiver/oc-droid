package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.LoadedContent
import cn.vectory.ocdroid.ui.OrchestratorViewModel

/**
 * §chat-list-detail §12 B0.5-rework: the THIN chat/{id} render slice — RENDER
 * ONLY. Proves (P1) content.sessionId==routeId render authority + (P6)
 * freshness CAS end-to-end. This composable is registered in AppShell's
 * NavHost for the `chat/{sessionId}` route pattern; it coexists with the old
 * bare-`chat` composable (ChatScreen / ChatScaffold) that the non-migrated
 * entries still use.
 *
 * # B0.5-rework: bridge DELETED
 *
 * The prior version had a LaunchedEffect bridge that manufactured
 * ChatContentLoaded from the flat fields using the CURRENT token — a broken
 * design (the bridge's token was always "now", not the load-start token, so
 * the §7.2 CAS was meaningless). The rework threads expectedRouteInstance
 * through the ENTIRE load pipeline (navigateToChat → openForRoute →
 * VerifyAndHydrate → launchLoadMessages → ChatContentLoaded), so the
 * composable no longer needs to manufacture content — it is a PURE render
 * path that reads [ChatState.content] and applies the guard. No UI effect
 * converts anonymous flat data into owned content.
 *
 * # P1 render guard (structural authority)
 *
 * The transcript renders IFF `content.sessionId == routeId` — the route's
 * sessionId is the sole authority. Because [LoadedContent] welds
 * sessionId+messages (data-class ctor), a mismatch is structurally
 * unconstructable. A mismatch (navigate to B while content still holds A)
 * shows Loading, not A's transcript.
 *
 * # P6 freshness CAS (temporal acceptance)
 *
 * The guard also checks `content.routeInstance == chatRouteInstance` — the
 * route-instance token minted by navigateToChat at navigation time and
 * threaded through the load pipeline. A stale load from an older incarnation
 * (A→B→A) carries an older routeInstance and is rejected by the reducer's
 * CAS before it even reaches this composable; the render guard is the last
 * line of defense.
 */
@Composable
fun ChatDetailSlice(
    routeId: String,
    chatVM: ChatViewModel,
    orchestratorVM: OrchestratorViewModel,
    modifier: Modifier = Modifier,
) {
    val chat by chatVM.chatFlow.collectAsStateWithLifecycle()
    val routeInstance by orchestratorVM.chatRouteInstanceFlow.collectAsStateWithLifecycle()

    // P1/P6 render guard: render IFF content belongs to THIS route AND the
    // routeInstance matches (no stale incarnation). PURE read — no
    // LaunchedEffect, no dispatch, no flat-field bridging.
    val content = chat.content
    val showContent = isRouteContentRenderable(routeId, content, routeInstance)

    if (showContent) {
        ChatDetailSliceContent(content!!, modifier)
    } else {
        // Loading / Missing state — shows for: no content yet (load in flight),
        // content belongs to a different session (P1 mismatch), or content's
        // routeInstance is stale (P6 mismatch).
        Box(
            modifier = modifier.fillMaxSize().testTag("chat-detail-loading"),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    text = "Loading session $routeId…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

/**
 * Minimal transcript render from [LoadedContent]. B0.5 shows a simple message
 * list (role + id) — the full chat UI (ChatMessageList / streaming overlay /
 * composer) is the old path's job; B2 migrates it onto LoadedContent.
 */
@Composable
internal fun ChatDetailSliceContent(content: LoadedContent, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp).testTag("chat-detail-content"),
    ) {
        items(content.messages) { msg ->
            Text(
                text = "${msg.role}: ${msg.id}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}
