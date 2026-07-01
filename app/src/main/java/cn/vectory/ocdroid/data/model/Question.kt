package cn.vectory.ocdroid.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QuestionOption(
    val label: String,
    val description: String
)

@Serializable
data class QuestionInfo(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiple: Boolean? = false,
    val custom: Boolean? = true
) {
    val allowMultiple: Boolean
        get() = multiple ?: false
    
    val allowCustom: Boolean
        get() = custom ?: true
}

@Serializable
data class QuestionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val questions: List<QuestionInfo>,
    val tool: ToolRef? = null
) {
    @Serializable
    data class ToolRef(
        @SerialName("messageID") val messageId: String,
        @SerialName("callID") val callId: String
    )
}
