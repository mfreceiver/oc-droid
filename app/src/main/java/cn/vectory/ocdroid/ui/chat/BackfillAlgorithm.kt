package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.cache.CachedSessionLayout
import cn.vectory.ocdroid.data.model.Message

/**
 * R-20 Phase 2 (plan §2 / §3): pure functions for the gap backfill algorithm.
 *
 * Every member is a pure function — no slice reads, no effect emits, no
 * coroutine launches. This is the unit-testable invariant surface for gap
 * detection, fill-resolution, and the G6 SSE-coverage probe gate. The stateful
 * orchestration (probe → detect → 50-step fill) lives in [GapFillCoordinator];
 * [cn.vectory.ocdroid.ui.launchCatchUp] calls [detectGap] on its probe result.
 *
 * **Probe size**: the catch-up probe fetches
 * [cn.vectory.ocdroid.ui.MainViewModelTimings.gapProbeMessagePageSize] (=5)
 * newest messages. detectGap classifies this window against the local anchor:
 * the sentinel off-by-one boundary now sits at exactly-4-new (anchor in the
 * 5th/oldest slot → contiguous) vs exactly-5-new (anchor absent → gap), the
 * Phase-2 generalisation of the legacy "fetch 4 = 3 display + 1 sentinel".
 */
object BackfillAlgorithm {

    /** Probe page size — matches [cn.vectory.ocdroid.ui.MainViewModelTimings.gapProbeMessagePageSize]. */
    const val PROBE_SIZE = 5

    /**
     * Plan §2: the resolution anchor for a contiguous cache. Returns the
     * message 3-from-the-newest (`dropLast(3).lastOrNull()`), i.e. the point
     * just below the ~3-message overlap zone a normal newest-page would cover.
     * For caches with ≤3 messages returns null (no deep anchor → no gap to
     * detect against; the page merges directly).
     *
     * Provided as a utility; the production catch-up detect path
     * ([cn.vectory.ocdroid.ui.launchCatchUp]) uses the **newest** local
     * message as its detect anchor to preserve the exact sentinel off-by-one
     * boundary (exactly-4-new → contiguous, exactly-5-new → gap). [anchorOf]
     * is exposed for callers/tests that want the deeper bridge-point anchor.
     */
    fun anchorOf(cached: List<Message>): Message? = cached.dropLast(3).lastOrNull()

    /**
     * Extract the [Message]s from a [CachedSessionLayout], oldest-first. The
     * layout now carries messages + gapMarkers separately (Phase 4 ring-break),
     * so this is a trivial accessor kept for callers that want the message list
     * without the gaps.
     */
    fun extractMessages(layout: CachedSessionLayout): List<Message> = layout.messages

    /**
     * Classify a probe window against the local anchor.
     *
     * Cases (plan §3 Phase 2 test table):
     *  1. [anchor] == null (no local history / cache too small) → [GapDetection.NoGap].
     *  2. [anchor].id is in [fetched5] → [GapDetection.NoGap] (contiguous tail;
     *     the sentinel slot absorbs the exactly-4-new boundary).
     *  3. [anchor].id NOT in [fetched5] AND `fetched5.size < [PROBE_SIZE]` →
     *     [GapDetection.NoGap] (the server returned a short page = history
     *     exhausted within the probe; the "gap ≤ probe" direct-merge case —
     *     there is nothing further to page, so merge without opening a gap).
     *  4. [anchor].id NOT in [fetched5] AND `fetched5.size == [PROBE_SIZE]` →
     *     [GapDetection.GapExists] (a full page came back without the anchor ⇒
     *     the gap is larger than one probe page ⇒ open a marker).
     *
     * @param probeNextCursor the probe response's `X-Next-Cursor` (pages OLDER
     *   from the fetched window). Carried into [GapDetection.GapExists.
     *   initialNextBeforeCursor] verbatim — the client never synthesizes the
     *   cursor (plan §3 GapMarker invariant). May be null if the probe reached
     *   history end; a null cursor with a full-size page + missing anchor still
     *   opens a gap (the boundary is recorded; the first fill step immediately
     *   transitions to exhausted). This param is the one faithful completion
     *   of the plan §2 sketch (whose `detectGap(anchor, fetched5)` signature
     *   cannot otherwise produce the non-null `initialNextBeforeCursor` its
     *   own `GapExists` data class mandates).
     */
    fun detectGap(
        anchor: Message?,
        fetched5: List<Message>,
        probeNextCursor: String?
    ): GapDetection {
        if (anchor == null) return GapDetection.NoGap
        val anchorId = anchor.id
        if (fetched5.any { it.id == anchorId }) return GapDetection.NoGap
        if (fetched5.size < PROBE_SIZE) return GapDetection.NoGap
        // anchor missing from a full probe page ⇒ real gap (> probe page).
        val upperBoundary = fetched5.minByOrNull { it.time?.created ?: Long.MAX_VALUE }
            ?: return GapDetection.NoGap
        return GapDetection.GapExists(
            lowerAnchorMessageId = anchorId,
            upperBoundaryMessageId = upperBoundary.id,
            // Cursor carried verbatim from the probe response header (may be null).
            initialNextBeforeCursor = probeNextCursor ?: ""
        )
    }

    /**
     * Does a backward fill [step] reach the [anchor]? Returns true iff [anchor]
     * is non-null AND some message in [step] has [anchor]'s id. The coordinator
     * resolves the gap the moment a step covers the anchor (the two slices are
     * now contiguous). `before=` is inclusive, so the anchor may sit exactly at
     * the page boundary — the raw-step check (before dedup) is correct.
     */
    fun stepCoversAnchor(step: List<Message>, anchor: Message?): Boolean {
        if (anchor == null) return false
        val anchorId = anchor.id
        return step.any { it.id == anchorId }
    }

    /**
     * Plan §0 G6 / §3 Phase 2: should the catch-up path PROBE the server for
     * gaps, or is the session already SSE-covered (skip the probe — the live
     * SSE feed is delivering its updates)?
     *
     * A session is "covered" (no probe) iff BOTH hold:
     *  - the current SSE job is attached to [currentWorkdir] (i.e.
     *   [sseCurrentWorkdir] != null AND == [currentWorkdir] — the feed is live
     *   for this session's workdir), AND
     *  - [sessionId] ∈ [sessionsEverColdSnapshotted] — a cold snapshot has
     *    already established the session's baseline (so SSE deltas since are
     *    trusted; a never-snapshotted session may have pre-SSE history the
     *    feed never delivered).
     *
     * Returns true (probe) when NOT covered. [BackfillAlgorithm.shouldProbe]
     * is the negation of "covered": probe unless the SSE feed reliably covers
     * this session's workdir AND the session was cold-snapshotted.
     *
     * Rationale (plan §0 G6): avoids a redundant REST probe on every reconnect
     * when the SSE feed stayed live for the session's workdir, while still
     * probing the moment either guarantee drops (different workdir, or a
     * session the user just opened that has no snapshot yet).
     */
    fun shouldProbe(
        sessionId: String,
        currentWorkdir: String,
        sessionsEverColdSnapshotted: Set<String>,
        sseCurrentWorkdir: String?
    ): Boolean {
        val sseCoversThisWorkdir =
            sseCurrentWorkdir != null && sseCurrentWorkdir == currentWorkdir
        val wasColdSnapshotted = sessionId in sessionsEverColdSnapshotted
        val covered = sseCoversThisWorkdir && wasColdSnapshotted
        return !covered
    }
}

/**
 * Plan §2: the result of classifying a probe window against the local anchor.
 * Sealed so the coordinator's `when` is exhaustive.
 */
sealed class GapDetection {
    /** No gap: merge the fetched window directly (contiguous or exhausted). */
    data object NoGap : GapDetection()

    /**
     * A gap larger than one probe page. Carries the complete boundary + cursor
     * needed to [cn.vectory.ocdroid.data.cache.CacheRepository.openGap].
     *
     * @param lowerAnchorMessageId the anchor id (newest local message).
     * @param upperBoundaryMessageId the oldest id in the fetched probe window
     *   (the seam the divider renders at; advances toward the anchor as fill lands).
     * @param initialNextBeforeCursor the probe response cursor (pages older
     *   from the probe window). "" if the probe returned no cursor (the gap's
     *   first fill step will immediately exhaust).
     */
    data class GapExists(
        val lowerAnchorMessageId: String,
        val upperBoundaryMessageId: String,
        val initialNextBeforeCursor: String
    ) : GapDetection()
}
