package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.QuestionOption
import com.yage.opencode_client.data.model.QuestionRequest

@Composable
fun QuestionCardView(
    question: QuestionRequest,
    onReply: (List<List<String>>, onError: () -> Unit) -> Unit,
    onReject: () -> Unit
) {
    val count = question.questions.size

    // Guard: empty questions list — show dismissible placeholder
    if (count == 0) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.question_empty), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onReject) { Text(stringResource(R.string.question_dismiss)) }
            }
        }
        return
    }

    // Key all state by question.id — resets cleanly when a new question arrives
    var currentTab by remember(question.id) { mutableIntStateOf(0) }
    // Per-question selected answers — inner lists are snapshot-aware
    val answers = remember(question.id) {
        mutableStateListOf<MutableList<String>>().also { list ->
            repeat(count) { list.add(mutableStateListOf()) }
        }
    }
    // Per-question custom text
    val customTexts = remember(question.id) {
        mutableStateListOf<String>().also { list ->
            repeat(count) { list.add("") }
        }
    }
    // Per-question whether "custom" option is active
    val customActive = remember(question.id) {
        mutableStateListOf<Boolean>().also { list ->
            repeat(count) { list.add(false) }
        }
    }
    var isCustomEditing by remember(question.id) { mutableStateOf(false) }
    var isSending by remember(question.id) { mutableStateOf(false) }

    val accent = MaterialTheme.colorScheme.primary
    val cornerRadius = 12.dp

    val currentQuestion = question.questions[currentTab]
    val currentAnswers = answers[currentTab]
    val isCustomActiveNow = customActive[currentTab]
    val customText = customTexts[currentTab]

    fun isSelected(option: QuestionOption): Boolean = currentAnswers.contains(option.label)

    fun commitCustom() {
        val text = customTexts[currentTab].trim()
        isCustomEditing = false

        if (currentQuestion.allowMultiple) {
            val optionLabels = currentQuestion.options.map { it.label }.toSet()
            // Keep only predefined-option answers; replace with snapshot-aware list
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
        if (currentQuestion.allowMultiple) {
            if (currentAnswers.contains(option.label)) {
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
        customActive[currentTab] = true
        isCustomEditing = true
        if (!currentQuestion.allowMultiple) {
            answers[currentTab].clear()
        }
    }

    fun commitCustomIfNeeded() {
        if (customActive[currentTab] || isCustomEditing) {
            commitCustom()
        }
    }

    fun canProceed(): Boolean {
        return answers[currentTab].isNotEmpty() ||
                (customActive[currentTab] && customTexts[currentTab].isNotBlank())
    }

    fun hasAnswer(index: Int): Boolean {
        return answers[index].isNotEmpty() ||
                (customActive[index] && customTexts[index].isNotBlank())
    }

    fun back() {
        commitCustomIfNeeded()
        if (currentTab > 0) {
            currentTab--
            isCustomEditing = false
        }
    }

    fun submit() {
        if (isSending) return
        isSending = true
        onReply(answers.map { it.toList() }) {
            // onError: reset so the user can retry
            isSending = false
        }
    }

    fun next() {
        commitCustomIfNeeded()
        if (currentTab >= question.questions.size - 1) {
            submit()
        } else {
            currentTab++
            isCustomEditing = false
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
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
                    color = accent
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.question_of, currentTab + 1, question.questions.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

            // Question text
            Text(
                text = currentQuestion.question,
                style = MaterialTheme.typography.bodyLarge
            )

            // Hint text
            Text(
                text = if (currentQuestion.allowMultiple) stringResource(R.string.question_multi_hint) else stringResource(R.string.question_single_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Options
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                currentQuestion.options.forEach { option ->
                    val selected = isSelected(option)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) accent.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable { selectOption(option) }
                            .padding(vertical = 10.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (selected) {
                                if (currentQuestion.allowMultiple) Icons.Filled.CheckBox else Icons.Filled.RadioButtonChecked
                            } else {
                                if (currentQuestion.allowMultiple) Icons.Outlined.CheckBoxOutlineBlank else Icons.Outlined.RadioButtonUnchecked
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
                if (currentQuestion.allowCustom) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isCustomActiveNow) accent.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable { activateCustom() }
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
                                onValueChange = { customTexts[currentTab] = it },
                                label = { Text(stringResource(R.string.question_custom_placeholder)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { commitCustom() }),
                                modifier = Modifier.fillMaxWidth()
                            )
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
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.question_dismiss))
                }

                if (currentTab > 0) {
                    OutlinedButton(
                        onClick = { back() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.question_back))
                    }
                }

                Button(
                    onClick = { next() },
                    enabled = canProceed() && !isSending,
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
        }
    }
}
