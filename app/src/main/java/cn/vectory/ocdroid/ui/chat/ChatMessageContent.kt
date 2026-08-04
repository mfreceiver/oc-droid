package cn.vectory.ocdroid.ui.chat

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.isEffectivelyRenderableEmpty
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.LoadedContent
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.ScrollBehavior
import cn.vectory.ocdroid.ui.currentSessionStatus
import cn.vectory.ocdroid.ui.filterBeforeRevert
import cn.vectory.ocdroid.ui.injectMetadataMarkers
import cn.vectory.ocdroid.ui.isStaleQuestionPart
import cn.vectory.ocdroid.ui.isInterruptedQuestionPart
import cn.vectory.ocdroid.ui.isStaleRunningPart
import cn.vectory.ocdroid.ui.theme.AppTextStyles
import cn.vectory.ocdroid.ui.theme.CardWidthScope
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.util.FLICKER_TAG
import cn.vectory.ocdroid.util.STREAMING_FLICKER_DEBUG
import cn.vectory.ocdroid.util.flickerFilterOutCount
import cn.vectory.ocdroid.util.workdirBasename
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

// ── Chat message list container ──────────────────────────────────────────
// The top-level scrollable list of chat turns. Owns auto-follow-bottom,
// history paging, and the §Phase1C gap divider. Per-message rendering is
// delegated to [MessageRow] (in ChatMessageRow.kt); all card composables
// live in their own sibling files.

@Composable
internal fun ChatMessageList(
    chatVM: ChatViewModel,
    composerVM: ComposerViewModel,
    sessionVM: SessionViewModel,
    orchestratorVM: OrchestratorViewModel,
    onFileClick: (String) -> Unit,
    onOpenChanges: (String) -> Unit = {},
    onCopyMessage: (messageId: String, text: String) -> Unit = { _, _ -> },
    onEditAndRerun: (messageId: String) -> Unit = {},
    onFork: (messageId: String) -> Unit = {},
    isCurrentSessionSending: Boolean = false,
    routeSessionId: String? = null,
    routeContent: LoadedContent? = null,
    onOpenSubAgentNavigate: (childSessionId: String, checkpoint: cn.vectory.ocdroid.ui.ScrollCheckpoint) -> Unit = { _, _ -> },
) {
    // ── §R-17 Stage 2: subscribe to chatFlow + sessionListFlow ──────────
    val chatState by chatVM.chatFlow.collectAsStateWithLifecycle()
    val sessionListState by chatVM.sessionListFlow.collectAsStateWithLifecycle()
    val expandedParts by chatVM.expandedParts.collectAsStateWithLifecycle()

    // ── derived state ──────────────────────────────────────────────────
    val effectiveSessionId = routeSessionId ?: chatState.currentSessionId
    val currentSession = (sessionListState.sessions + sessionListState.directorySessions.values.flatten() + sessionListState.childSessions.values.flatten())
        .firstOrNull { it.id == effectiveSessionId }
    val currentCutoff = effectiveSessionId?.let(chatState.revertCutoffs::get)
    val revertMessageId = if (currentSession != null) currentSession.revert?.messageId else currentCutoff?.messageId
    LaunchedEffect(chatState.currentSessionId, revertMessageId, currentSession?.revert?.messageId) {
        if (revertMessageId != null && currentCutoff?.state !is cn.vectory.ocdroid.data.model.RevertCutoffState.Failed) {
            chatVM.retryRevertCutoff()
        }
    }

    val loadedMessages = if (routeContent != null) routeContent.messages else chatState.messages
    val messages: List<Message> = remember(loadedMessages, revertMessageId, currentCutoff) {
        val reverted = loadedMessages.filterBeforeRevert(revertMessageId, currentCutoff)
        val visible = reverted.filter { !it.isToolRole || it.role in cn.vectory.ocdroid.ui.METADATA_MARKER_ROLES }
        injectMetadataMarkers(visible)
    }
    val partsByMessage: Map<String, List<Part>> =
        if (routeContent != null) routeContent.partsByMessage else chatState.partsByMessage
    val streamingPartTexts: Map<String, String> =
        if (routeContent != null) routeContent.streamingPartTexts else chatState.streamingPartTexts
    val streamingReasoningPart: Part? =
        if (routeContent != null) routeContent.streamingReasoningPart else chatState.streamingReasoningPart
    val chatHasStreamingText: Boolean = streamingPartTexts.isNotEmpty()
    val partExpandStates: Map<PartKey, PartExpandState> = chatState.partExpandStates
    val isLoading: Boolean = chatState.isLoadingMessages
    val isLoadingMore: Boolean = chatState.isLoadingMoreMessages
    val sessionIsRunning = effectiveSessionId?.let { id ->
        sessionListState.sessionStatuses[id]?.let { it.isBusy || it.isRetry }
    } == true
    val currentSessionStatus = effectiveSessionId?.let { sid ->
        sessionListState.sessionStatuses[sid]
    }
    val hasMoreMessages: Boolean =
        if (routeContent != null) routeContent.hasMoreMessages else chatState.hasMoreMessages
    val olderMessagesCursor: String? =
        if (routeContent != null) routeContent.olderMessagesCursor else chatState.olderMessagesCursor
    val repository: OpenCodeRepository = chatVM.repository
    val workspaceDirectory: String? = currentSession?.directory
    val onLoadMore: () -> Unit = chatVM::loadMoreMessages
    val onToggleExpand: (String, Boolean) -> Unit = composerVM::togglePartExpand

    // ── stale question detection ───────────────────────────────────────
    val pendingQuestions = sessionListState.pendingQuestions
    val questionRunningSince = remember { mutableStateMapOf<String, Long>() }
    val questionGraceTick = remember { mutableIntStateOf(0) }
    val staleQuestionPartKeys: Set<String> =
        remember(partsByMessage, pendingQuestions, currentSessionStatus, questionGraceTick.intValue) {
            val now = System.currentTimeMillis()
            val liveQuestionCandidates = HashSet<String>()
            val keys = HashSet<String>()
            for (part in partsByMessage.values.flatten()) {
                if (isStaleQuestionPart(part, pendingQuestions)) {
                    liveQuestionCandidates.add(part.id)
                    questionRunningSince.putIfAbsent(part.id, now)
                }
                if (isStaleRunningPart(part, pendingQuestions, currentSessionStatus, now, questionRunningSince[part.id])) {
                    keys.add(part.id)
                }
            }
            questionRunningSince.keys.retainAll(liveQuestionCandidates)
            keys
        }
    val sessionCanInterrupt = currentSessionStatus?.isIdle == true
    val hasPendingGraceCandidates = sessionCanInterrupt &&
        questionRunningSince.isNotEmpty() &&
        staleQuestionPartKeys.size < questionRunningSince.size
    LaunchedEffect(hasPendingGraceCandidates) {
        while (isActive && hasPendingGraceCandidates) {
            delay(1_000)
            questionGraceTick.intValue++
        }
    }

    // ── scroll controller (must stay INSIDE ChatMessageList body) ──────
    val scroll = rememberScrollController(sessionId = effectiveSessionId)
    val listState = scroll.listState
    val onOpenSubAgent: (String) -> Unit = { childSessionId ->
        onOpenSubAgentNavigate(childSessionId, scroll.captureCheckpoint())
    }

    // ── session diff ───────────────────────────────────────────────────
    val sessionId = effectiveSessionId
    val sessionDiff = sessionId?.let { sessionListState.sessionDiffs[it] }
    LaunchedEffect(sessionId) {
        if (!sessionId.isNullOrBlank()) sessionVM.loadSessionDiff(sessionId)
    }

    // ── pipeline single-sourcing (M3 reversedMessages + M4 renderBlocks + L2 keys) ──
    val isStreaming = sessionIsRunning || streamingReasoningPart != null || streamingPartTexts.isNotEmpty()
    val reversedMessages = remember(messages, partsByMessage, streamingPartTexts.keys, streamingReasoningPart, sessionIsRunning) {
        computeFilteredReversedMessages(
            messages = messages,
            partsByMessage = partsByMessage,
            streamingPartTextKeys = streamingPartTexts.keys,
            streamingReasoningPart = streamingReasoningPart,
            sessionIsRunning = sessionIsRunning,
        ).also { filtered ->
            if (STREAMING_FLICKER_DEBUG) {
                messages.reversed().forEach { msg ->
                    if (msg !in filtered) {
                        val count = flickerFilterOutCount.incrementAndGet()
                        Log.w(FLICKER_TAG, "FILTERED OUT msgId=${msg.id} filterOutCount=$count")
                    }
                }
            }
        }
    }
    val pipeline = rememberRenderPipeline(
        reversedMessages = reversedMessages,
        partsByMessage = partsByMessage,
        streamingPartTexts = streamingPartTexts,
        staleQuestionPartKeys = staleQuestionPartKeys,
        streamingReasoningPart = streamingReasoningPart,
        sessionIsRunning = sessionIsRunning,
        sessionDiff = sessionDiff,
        messages = messages,
        hasMoreMessages = hasMoreMessages,
        olderMessagesCursor = olderMessagesCursor,
    )
    val renderBlocks = pipeline.renderBlocks
    val lazyColumnKeys = pipeline.lazyColumnKeys

    // ── 3 mixed scroll effects ────────────────────────────────────────
    ReselectScrollEffect(
        orchestratorVM = orchestratorVM,
        scroll = scroll,
        listState = listState,
    )
    ContentVersionScrollEffect(
        sessionId = sessionId,
        scroll = scroll,
        listState = listState,
        messages = messages,
        isStreaming = isStreaming,
        streamingPartTextKeys = streamingPartTexts.keys,
        streamingReasoningPart = streamingReasoningPart,
        expandedParts = expandedParts,
        pendingScrollRequest = chatState.pendingScrollRequest,
    )
    UnifiedScrollConsumerEffect(
        sessionId = sessionId,
        pendingScrollRequest = chatState.pendingScrollRequest,
        messages = messages,
        scroll = scroll,
        listState = listState,
        lazyColumnKeys = lazyColumnKeys,
        chatVM = chatVM,
    )

    // ── LazyColumn + overlay ───────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize().padding(vertical = Dimens.spacing2)) {
        // Watermark (behind the message list)
        WorkdirWatermark(
            workspaceDirectory = workspaceDirectory,
            modifier = Modifier.align(Alignment.Center),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            verticalArrangement = Arrangement.Top,
            contentPadding = Dimens.chatListContentPadding
        ) {
            if (streamingReasoningPart != null) {
                val streamingKey = streamingReasoningPart.id
                val streamingText = streamingPartTexts[streamingKey] ?: ""
                item(key = "streaming-reasoning") {
                    StreamingReasoningOverlay(
                        streamingReasoningPart = streamingReasoningPart,
                        streamingText = streamingText,
                        expandedParts = expandedParts,
                        onToggleExpand = onToggleExpand,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = Dimens.spacing4, vertical = Dimens.spacing1),
                    )
                }
            }
            if (!sessionDiff.isNullOrEmpty()) {
                sessionId?.let { diffSessionId ->
                    item(key = "session-diff") {
                        SessionDiffCard(
                            sessionId = diffSessionId,
                            diffs = sessionDiff,
                            onOpenChanges = onOpenChanges,
                        )
                    }
                }
            }
            itemsIndexed(
                renderBlocks,
                key = { _, block -> block.id },
                contentType = { _, block -> block::class },
            ) { index, block ->
                Box(modifier = Modifier.fillMaxWidth().padding(top = renderBlockTopPaddingDp(block, index).dp)) {
                    when (block) {
                        is RenderBlock.Conversation -> {
                            val message = block.message
                            if (message.role in cn.vectory.ocdroid.ui.METADATA_MARKER_ROLES) {
                                MetadataMarkerRow(
                                    role = message.role,
                                    label = markerLabelFor(message),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                val msgParts = block.parts
                                val isInFlightEmpty = shouldRenderInFlightEmpty(block, sessionIsRunning)
                                if (isInFlightEmpty) {
                                    InFlightEmptyLoading()
                                } else {
                                    val isEditAndRerunEnabled = canEditAndRerun(
                                        cn.vectory.ocdroid.ui.chat.DestructiveGateInputs(
                                            isUser = message.isUser,
                                            sessionIsBusy = currentSessionStatus?.isBusy == true,
                                            sessionIsRetry = currentSessionStatus?.isRetry == true,
                                            isSending = isCurrentSessionSending,
                                            hasStreamingText = chatHasStreamingText,
                                            hasStreamingReasoning = streamingReasoningPart != null,
                                        )
                                    )
                                    val isMessageStreaming = block.isMessageStreaming
                                    MessageCard(
                                        message = message,
                                        parts = msgParts,
                                        streamingPartTexts = block.streamingPartTexts,
                                        streamingReasoningPartId = streamingReasoningPart?.id,
                                        repository = repository,
                                        workspaceDirectory = workspaceDirectory,
                                        onFileClick = onFileClick,
                                        onOpenSubAgent = onOpenSubAgent,
                                        expandedParts = expandedParts,
                                        onToggleExpand = onToggleExpand,
                                        staleQuestionPartKeys = staleQuestionPartKeys,
                                        showMessageDecoration = block.showMessageDecoration,
                                        canCopy = true,
                                        canEditAndRerun = isEditAndRerunEnabled,
                                        canFork = true,
                                        onCopy = { text -> onCopyMessage(message.id, text) },
                                        onEditAndRerun = onEditAndRerun,
                                        onFork = onFork,
                                        partExpandStates = partExpandStates,
                                        onExpandParts = { parts ->
                                            val sid = sessionId
                                            if (sid != null) chatVM.expandParts(sid, parts)
                                        },
                                        isMessageStreaming = isMessageStreaming,
                                    )
                                }
                            }
                        }
                        is RenderBlock.ToolRun -> {
                            CardWidthScope(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spacing4, vertical = Dimens.spacing1)
                            ) { cardMax ->
                                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                    block.items.forEach { contextual ->
                                        androidx.compose.runtime.key(
                                            "${contextual.message.id}|${stableItemId(contextual.item, contextual.message.id)}"
                                        ) {
                                            renderToolItem(
                                                item = contextual.item,
                                                message = contextual.message,
                                                expandedParts = expandedParts,
                                                onToggleExpand = onToggleExpand,
                                                onFileClick = onFileClick,
                                                onOpenSubAgent = onOpenSubAgent,
                                                staleQuestionPartKeys = staleQuestionPartKeys,
                                                cardMax = cardMax
                                            )
                                        }
                                    }
                                    block.messageDecorations.forEach { message ->
                                        MessageDecoration(message = message, cardMax = cardMax)
                                    }
                                }
                            }
                        }
                        is RenderBlock.Fold -> {
                            val folded = block.asFoldedToolRun()
                            val foldKey = foldKey(block.firstPartId)
                            CardWidthScope(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spacing4, vertical = Dimens.spacing1)
                            ) { cardMax ->
                                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                    if (isCrossMessageFoldExpanded(folded, expandedParts)) {
                                        block.items.forEach { contextual ->
                                            androidx.compose.runtime.key(
                                                "${contextual.message.id}|${stableItemId(contextual.item, contextual.message.id)}"
                                            ) {
                                                renderToolItem(
                                                    item = contextual.item,
                                                    message = contextual.message,
                                                    expandedParts = expandedParts,
                                                    onToggleExpand = onToggleExpand,
                                                    onFileClick = onFileClick,
                                                    onOpenSubAgent = onOpenSubAgent,
                                                    staleQuestionPartKeys = staleQuestionPartKeys,
                                                    cardMax = cardMax
                                                )
                                            }
                                        }
                                    } else {
                                        ToolCallFoldBar(
                                            counts = folded.foldCounts(),
                                            isRunning = foldIsRunning(folded),
                                            onToggleExpand = { onToggleExpand(foldKey, false) },
                                            modifier = Modifier.widthIn(max = cardMax)
                                        )
                                    }
                                    FoldMessageDecoration(messages = block.messageDecorations, cardMax = cardMax)
                                }
                            }
                        }
                    }
                }
            }
            if (messages.isNotEmpty() && hasMoreMessages && olderMessagesCursor != null) {
                item(key = "load-more") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(Dimens.spacing4),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(Dimens.iconStd))
                        } else {
                            Text(
                                text = stringResource(R.string.chat_load_more_history),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable(onClick = onLoadMore)
                                    .padding(horizontal = Dimens.spacing4, vertical = Dimens.spacing2)
                            )
                        }
                    }
                }
            }
            if (isLoading && messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(Dimens.spacing7),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Dimens.iconXl),
                                strokeWidth = Dimens.chatDividerStrokeWidth,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(Dimens.spacing3))
                            Text(
                                text = stringResource(R.string.chat_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (!isLoading && messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(Dimens.spacing7),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.chat_no_messages),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        ChatMessageNavFab(
            listState = listState,
            visible = scroll.navFabVisible,
            onJump = {
                scroll.navFabVisible = false
                scroll.navJumping = true
                scroll.followBottom = true
            },
            onJumpDone = { scroll.navJumping = false },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = Dimens.spacing4, bottom = Dimens.spacing4),
        )
    }
}
