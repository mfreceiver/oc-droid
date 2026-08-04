package cn.vectory.ocdroid.ui.controller.sse

import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **INVARIANT: all access must occur on Dispatchers.Main.immediate — this is a
 * thread-imprisonment contract, do not introduce concurrent primitives.**
 *
 * Verbose SSE diagnostic coalescing + noise filter for the dispatchSseEvent log
 * flood. Coalesces `message.part.delta` + `message.part.updated` (decades–100s/sec
 * during AI output) into ONE summary line per 1s window per current session.
 */
internal class SseDiagLogger(
    private val slices: SliceFlows,
    /** Set of noisy event types to suppress from dispatch logging. */
    private val noisySseLogEvents: Set<String>,
) {
    @Volatile
    private var verboseSseDeltaFirstAt: Long = 0L
    @Volatile
    private var verboseSseDeltaCount: Int = 0
    @Volatile
    private var verboseSseDeltaSid: String? = null

    /**
     * Log a verbose SSE diag entry (coalesced for high-frequency event types).
     * Called from [SessionSyncCoordinator.dispatchSseEvent] before the actual
     * routing.
     *
     * @param event the SSE event being dispatched
     * @param currentSid the current session ID from [slices.chat.value.currentSessionId]
     */
    fun logVerbose(event: SSEEvent, currentSid: String?) {
        if (!DebugLog.verboseDiagEnabled) return
        val t = event.payload.type
        val evtSid = event.payload.getString("sessionID")
        val sidMatches = evtSid == null || evtSid == currentSid
        when {
            (t == "message.part.delta" || t == "message.part.updated") &&
                evtSid != null && evtSid == currentSid -> {
                val now = System.currentTimeMillis()
                if (verboseSseDeltaCount == 0 || now - verboseSseDeltaFirstAt >= 1000L) {
                    flushVerboseSseDeltaWindow()
                    verboseSseDeltaFirstAt = now
                    verboseSseDeltaCount = 1
                    verboseSseDeltaSid = currentSid
                } else {
                    verboseSseDeltaCount++
                }
            }
            sidMatches -> {
                flushVerboseSseDeltaWindow()
                val props = event.payload.properties
                val extra = if (t == "session.digest" && props != null) {
                    val obj = props as? JsonObject
                    val sid = obj?.get("sessionID")?.toString()?.trim('"')
                    val st = obj?.get("status")?.toString()?.trim('"')
                    val ua = obj?.get("updatedAt")
                    val mid = obj?.get("messageID")
                    " sid=$sid status=$st updatedAt=$ua messageId=$mid"
                } else ""
                DebugLog.d("SseDiag", "frame type=$t$extra")
            }
        }
    }

    /** Flush + reset the verbose delta coalesce window (emit summary if non-empty). */
    private fun flushVerboseSseDeltaWindow() {
        if (verboseSseDeltaCount > 0) {
            DebugLog.d(
                "SseDiag",
                "part.delta/updated ×$verboseSseDeltaCount in window sid=$verboseSseDeltaSid",
            )
            verboseSseDeltaCount = 0
            verboseSseDeltaFirstAt = 0L
            verboseSseDeltaSid = null
        }
    }
}
