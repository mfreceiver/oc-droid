package cn.vectory.ocdroid.data.cache.contract

/**
 * The authoritative gap record. Mirrors the persisted
 * [cn.vectory.ocdroid.data.cache.GapMarkerEntity] (minus timestamps) and is
 * the return type of [cn.vectory.ocdroid.data.cache.CacheRepository.gapsOf].
 *
 * Moved here from `ui.chat` (Phase 4 architecture: break the
 * `ui → data.cache → ui.chat` dependency ring). This is the pure DOMAIN
 * record carrying the full gap state. The slim UI projection
 * [cn.vectory.ocdroid.ui.chat.Entry.GapMarker] + the
 * [cn.vectory.ocdroid.ui.chat.toEntry] extension (a top-level function in
 * the `ui.chat` package, on this contract type) live in the UI layer —
 * deliberately NOT on this data class, so the contract stays UI-free (the
 * cursor/boundaries never reach the UI layer).
 *
 * @param gapId synthetic UUID (generated at openGap time). Stable across the
 *   gap's lifecycle.
 * @param lowerAnchorMessageId the newest message id of the OLDER slice — the
 *   fill resolution target. A backward step containing this id resolves the gap.
 * @param upperBoundaryMessageId the oldest message id of the NEWER slice — the
 *   visual seam + the boundary that advances toward the anchor as fill lands.
 * @param nextBeforeCursor the server `before` cursor for the NEXT older step.
 *   `null` ⇒ history exhausted (state = Exhausted). Client never synthesizes
 *   this — it is the raw `X-Next-Cursor` / `Link rel=next` response-header value.
 * @param fillState drives both the UI divider label and the coordinator resume.
 */
data class GapMarker(
    val gapId: String,
    val lowerAnchorMessageId: String,
    val upperBoundaryMessageId: String,
    val nextBeforeCursor: String?,
    val fillState: GapFillState
)
