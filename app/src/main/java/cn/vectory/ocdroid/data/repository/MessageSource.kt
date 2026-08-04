package cn.vectory.ocdroid.data.repository

// Wave2-cleanup: MessageSource interface + StandardMessageSource removed.
// Routing inlined into OpenCodeRepository — getMessagesPagedImpl calls
// api.getMessages() directly with extractNextCursor helper.
// extractNextCursor moved to OpenCodeRepository.kt.
