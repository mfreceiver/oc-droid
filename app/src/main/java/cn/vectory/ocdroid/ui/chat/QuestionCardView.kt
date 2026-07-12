package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionOption
import cn.vectory.ocdroid.data.model.QuestionRequest

/**
 * D2.1–D2.4: floating question card.
 *
 * Renders as a bottom-anchored overlay card that can collapse to a single-line
 * pill. The card owns its own answer state, submit/reject guards, and in-card
 * error display. It reports nothing to the host composable.
 *
 * @param queuePosition 1-based position of this question in the current session queue.
 * @param queueTotal total number of pending questions for the current session.
 * @param onReply called when the user submits answers; the second lambda should be
 *                invoked by the caller when the reply fails.
 * @param onReject called when the user dismisses the question; the lambda should be
 *                 invoked by the caller when the reject fails.
 */
@Composable
fun QuestionCardView(
    question: QuestionRequest,
    queuePosition: Int,
    queueTotal: Int,
    onReply: (List<List<String>>, onError: () -> Unit) -> Unit,
    onReject: (onError: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val count = question.questions.size

    // D2.3: collapse state is ephemeral — reset to expanded whenever the
    // active question id changes.
    var expanded by remember(question.id) { mutableStateOf(true) }
    var errorText by remember(question.id) { mutableStateOf<String?>(null) }

    // Key all state by question.id — resets cleanly when a new question arrives.
    var currentTab by remember(question.id) { mutableIntStateOf(0) }
    val answers = remember(question.id) {
        mutableStateListOf<MutableList<String>>().also { list ->
            repeat(count) { list.add(mutableStateListOf()) }
        }
    }
    val customTexts = remember(question.id) {
        mutableStateListOf<String>().also { list ->
            repeat(count) { list.add("") }
        }
    }
    val customActive = remember(question.id) {
        mutableStateListOf<Boolean>().also { list ->
            repeat(count) { list.add(false) }
        }
    }
    var isCustomEditing by remember(question.id) { mutableStateOf(false) }
    var isSending by remember(question.id) { mutableStateOf(false) }
    var isRejecting by remember(question.id) { mutableStateOf(false) }
    // §Phase 2 gpter fix: unified in-flight invariant. Submit and Reject are
    // mutually exclusive — neither can START while the other is in flight, and
    // ALL answer-mutation/navigation buttons (Submit/Next/Back/Reject) are
    // DISABLED while either runs. Prevents the reject-then-submit race that
    // fired concurrent rejectQuestion + replyQuestion for the same request id,
    // and blocks navigating away (Back) mid-operation.
    val anyInFlight = isSending || isRejecting

    val accent = MaterialTheme.colorScheme.primary
    val submitErrorMessage = stringResource(R.string.question_submit_failed)
    val dismissErrorMessage = stringResource(R.string.question_dismiss_failed)

    val currentQuestion = question.questions.getOrNull(currentTab)
    val currentAnswers = answers.getOrNull(currentTab)
    val isCustomActiveNow = customActive.getOrNull(currentTab) == true
    val customText = customTexts.getOrNull(currentTab) ?: ""

    fun isSelected(option: QuestionOption): Boolean = currentAnswers?.contains(option.label) == true

    fun clearError() { errorText = null }

    fun commitCustom() {
        val text = customTexts[currentTab].trim()
        isCustomEditing = false
        val cq = currentQuestion ?: return

        if (cq.allowMultiple) {
            val optionLabels = cq.options.map { it.label }.toSet()
            val kept = answers[currentTab].filter { optionLabels.contains(it) }
            val newAnswers = mutableStateListOf<String>().also { it.addAll(kept) }
            customTexts[currentTab] = text
            if (text.isNotEmpty()) {
                if (!newAnswers.contains(text)) newAnswers.add(text)
                customActive[currentTab] = true
            } else {
                customActive[currentTab] = false
            }
            answers[currentTab] = newAnswers
        } else {
            customTexts[currentTab] = text
            customActive[currentTab] = text.isNotEmpty()
            answers[currentTab] = if (text.isEmpty()) {
                mutableStateListOf()
            } else {
                mutableStateListOf(text)
            }
        }
    }

    fun selectOption(option: QuestionOption) {
        clearError()
        val cq = currentQuestion ?: return
        if (cq.allowMultiple) {
            if (currentAnswers?.contains(option.label) == true) {
                answers[currentTab].remove(option.label)
            } else {
                answers[currentTab].add(option.label)
            }
        } else {
            answers[currentTab].clear()
            answers[currentTab].add(option.label)
            customActive[currentTab] = false
        }
    }

    fun activateCustom() {
        clearError()
        customActive[currentTab] = true
        isCustomEditing = true
        if (currentQuestion?.allowMultiple == false) {
            answers[currentTab].clear()
        }
    }

    fun commitCustomIfNeeded() {
        if (customActive[currentTab] || isCustomEditing) {
            commitCustom()
        }
    }

    fun hasAnswer(index: Int): Boolean {
        return answers[index].isNotEmpty() ||
            (customActive[index] && customTexts[index].isNotBlank())
    }

    fun back() {
        clearError()
        commitCustomIfNeeded()
        if (currentTab > 0) {
            currentTab--
            isCustomEditing = false
        }
    }

    fun submit() {
        // §Phase 2 gpter round-3: read LIVE delegated state (`isSending ||
        // isRejecting`), NOT the `anyInFlight` snapshot val captured at
        // composition. A rapid 2nd click fired before recomposition would see
        // the stale captured anyInFlight==false and start a concurrent op
        // (reject-then-submit race). Button `enabled` states still use
        // anyInFlight — enablement is a composition output corrected by the
        // next recomposition, which is acceptable; only the function guards
        // need live reads to close the pre-recomposition window.
        if (isSending || isRejecting) return
        clearError()
        isSending = true
        onReply(answers.map { it.toList() }) {
            // D2.4 / Fix C: surface the failure in-card so the user can retry.
            errorText = submitErrorMessage
            isSending = false
        }
    }

    fun next() {
        clearError()
        commitCustomIfNeeded()
        if (currentTab >= question.questions.size - 1) {
            submit()
        } else {
            currentTab++
            isCustomEditing = false
        }
    }

    fun reject() {
        // §Phase 2 gpter round-3: read LIVE delegated state (see submit() note).
        if (isSending || isRejecting) return
        clearError()
        isRejecting = true
        onReject {
            errorText = dismissErrorMessage
            isRejecting = false
        }
    }

    // D2.3: a new question id auto-expands the card.
    LaunchedEffect(question.id) { expanded = true }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // The status slot supplies the chat area's bounded height. Let a long
        // question use all of it instead of reserving an arbitrary lower gap;
        // short cards still use their intrinsic height.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(max = maxHeight)
                .animateContentSize(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            AnimatedContent(
                targetState = expanded,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 2 }) togetherWith
                        (fadeOut() + slideOutVertically { it / 2 })
                },
                label = "questionExpandCollapse"
            ) { isExpanded ->
                if (isExpanded) {
                    ExpandedQuestionContent(
                        question = question,
                        currentTab = currentTab,
                        currentQuestion = currentQuestion,
                        currentAnswers = currentAnswers,
                        hasAnswer = ::hasAnswer,
                        isCustomActiveNow = isCustomActiveNow,
                        customText = customText,
                        isCustomEditing = isCustomEditing,
                        isSending = isSending,
                        isRejecting = isRejecting,
                        anyInFlight = anyInFlight,
                        errorText = errorText,
                        queuePosition = queuePosition,
                        queueTotal = queueTotal,
                        accent = accent,
                        onCollapse = { expanded = false },
                        onSelectOption = ::selectOption,
                        onCustomTextChange = {
                            clearError()
                            customTexts[currentTab] = it
                        },
                        onCommitCustom = ::commitCustom,
                        onActivateCustom = ::activateCustom,
                        onBack = ::back,
                        onNext = ::next,
                        onReject = ::reject
                    )
                } else {
                    CollapsedQuestionPill(
                        queuePosition = queuePosition,
                        queueTotal = queueTotal,
                        onExpand = { expanded = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsedQuestionPill(
    queuePosition: Int,
    queueTotal: Int,
    onExpand: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Help,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = "${stringResource(R.string.question_agent_asking)} · ${stringResource(R.string.question_tap_to_expand)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (queueTotal > 1) {
            Badge(
                containerColor = accent.copy(alpha = 0.15f),
                contentColor = accent
            ) {
                Text(
                    text = stringResource(R.string.question_of, queuePosition, queueTotal),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = stringResource(R.string.common_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ExpandedQuestionContent(
    question: QuestionRequest,
    currentTab: Int,
    currentQuestion: QuestionInfo?,
    currentAnswers: MutableList<String>?,
    hasAnswer: (Int) -> Boolean,
    isCustomActiveNow: Boolean,
    customText: String,
    isCustomEditing: Boolean,
    isSending: Boolean,
    isRejecting: Boolean,
    anyInFlight: Boolean,
    errorText: String?,
    queuePosition: Int,
    queueTotal: Int,
    accent: Color,
    onCollapse: () -> Unit,
    onSelectOption: (QuestionOption) -> Unit,
    onCustomTextChange: (String) -> Unit,
    onCommitCustom: () -> Unit,
    onActivateCustom: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onReject: () -> Unit
) {
    if (question.questions.isEmpty()) {
        EmptyQuestionCard(onReject = onReject)
        return
    }

    val cq = currentQuestion ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Help,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.question_title),
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                modifier = Modifier.weight(1f)
            )
            if (queueTotal > 1) {
                Badge(
                    containerColor = accent.copy(alpha = 0.15f),
                    contentColor = accent,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.question_of, queuePosition, queueTotal),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            IconButton(onClick = onCollapse) {
                Icon(
                    imageVector = Icons.Default.ExpandLess,
                    contentDescription = stringResource(R.string.common_collapse),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Progress dots
        if (question.questions.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(question.questions.size) { index ->
                    val dotColor: Color = when {
                        index == currentTab -> accent
                        hasAnswer(index) -> accent.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }
        }

        // §qcard-scroll: 可滚动内容区。weight(1f, fill=false) + verticalScroll：
        // 内容短时取自然高（不撑开、不滚）；内容超出 overlay 容器透传下来的 bounded
        // maxHeight 时，Column 被 cap 到该高度，weight 把剩余空间分给此区并滚动。
        // Header(含收起按钮)与 Action buttons 留在外层，始终可见——避免长问题把选项
        // 挤出屏幕导致用户无法作答而被卡死。
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Question text
            Text(
                text = cq.question,
                style = MaterialTheme.typography.bodyLarge
            )

            // Hint text
            Text(
                text = if (cq.allowMultiple) stringResource(R.string.question_multi_hint) else stringResource(R.string.question_single_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Options
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                cq.options.forEach { option ->
                    val selected = currentAnswers?.contains(option.label) == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(if (selected) accent.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable { onSelectOption(option) }
                            .padding(vertical = 10.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (selected) {
                                if (cq.allowMultiple) Icons.Filled.CheckBox else Icons.Filled.RadioButtonChecked
                            } else {
                                if (cq.allowMultiple) Icons.Outlined.CheckBoxOutlineBlank else Icons.Outlined.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) accent else MaterialTheme.colorScheme.onSurface
                            )
                            if (option.description.isNotEmpty()) {
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Custom input option
                if (cq.allowCustom) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(if (isCustomActiveNow) accent.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable { onActivateCustom() }
                                .padding(vertical = 10.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isCustomActiveNow) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                                contentDescription = null,
                                tint = if (isCustomActiveNow) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.question_type_own_answer),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCustomActiveNow) accent else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (isCustomActiveNow) {
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = customText,
                                onValueChange = onCustomTextChange,
                                label = { Text(stringResource(R.string.question_custom_placeholder)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { onCommitCustom() }),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onReject,
                enabled = !anyInFlight,
                modifier = Modifier.weight(1f)
            ) {
                if (isRejecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(stringResource(R.string.question_dismiss))
                }
            }

            if (currentTab > 0) {
                OutlinedButton(
                    onClick = onBack,
                    enabled = !anyInFlight,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.question_back))
                }
            }

            Button(
                onClick = onNext,
                enabled = (
                    currentAnswers?.isNotEmpty() == true ||
                        (isCustomActiveNow && customText.isNotBlank())
                    ) && !anyInFlight,
                modifier = Modifier.weight(1f)
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (currentTab >= question.questions.size - 1) stringResource(R.string.question_submit) else stringResource(R.string.question_next))
                }
            }
        }

        // Fix C: unobtrusive in-card error line.
        errorText?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun EmptyQuestionCard(onReject: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.question_empty),
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(onClick = onReject) { Text(stringResource(R.string.question_dismiss)) }
    }
}
