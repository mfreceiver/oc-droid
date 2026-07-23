package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.MessageWithParts

/**
 * One page of cursor-paginated messages. [nextCursor] is the opaque V1 cursor
 * (`X-Next-Cursor` response header) to pass as `before` for the next older
 * page; null means no more history.
 */
data class MessagesPage(
    val items: List<MessageWithParts>,
    val nextCursor: String?,
)

/**
 * §slim-v1-paging (Task 5 / G5 cursor): the slimapi `/messages` and
 * `/messages/{sid}/since/{ts}` endpoints accept a server-side `?limit`.
 * Each fetch returns ONE bounded page; v1 now surfaces the sidecar's
 * `X-Next-Cursor` response header so the caller can decide whether to
 * follow via the `?before=<opaque>` query (T5 flipped the earlier
 * "single-page; no cursor follow" decision — see [getMessagesPaged] slim
 * branch + [getSlimapiMessagesPage]).
 *
 * Pinned to 200 (the value the contract §3 resync pseudocode calls out
 * for the no-bookmark cold-start skeleton walk: "GET /slimapi/messages/
 * {sid}?mode=skeleton&limit=200; 按cursor分页拉至本地历史边界"). Sits in
 * one place so cold-start, resync, and the digest-triggered incremental
 * fetch all agree.
 */
internal const val SLIMAPI_DEFAULT_PAGE_LIMIT = 200

/**
 * §slim-v1-paging (Task 5 / G5 cursor): the upper bound on the total
 * number of message skeletons the cold-start cursor-follow
 * ([coldStartSlimSync] no-bookmark branch via [drainSlimapiMessagesBounded])
 * will aggregate per session before stopping — even if the sidecar keeps
 * returning `X-Next-Cursor`. Guards against an unbounded history pull on
 * a fresh client (a session with thousands of messages would otherwise
 * stall the cold-start sync behind a long cursor walk).
 *
 * **Honest sourcing note (rev-gpt MINOR #1):** 250 is an INDEPENDENT
 * cold-start budget decision — the max unique items cold-start will pull
 * via cursor-follow before stopping. It is NOT a cache-retention cap and
 * NOT a "product-recognized local history budget": the actual UI history
 * strategies live in [cn.vectory.ocdroid.ui.ViewModelSupport] and are
 * unrelated numerically (initial page = 40, history page = 30, full-load
 * limit = 500, catch-up = 10/5 — see `ViewModelSupport.kt:67,87,94,100-
 * 107`). No authoritative retention constant exists in the repo; this is
 * the closest applicable budget for the cold-start path.
 *
 * The NUMERIC value 250 is aligned with
 * [cn.vectory.ocdroid.ui.RevertCutoffCoordinator.MAX_PAGES] (5) ×
 * [cn.vectory.ocdroid.ui.RevertCutoffCoordinator.PAGE_SIZE] (50) purely
 * for cross-component consistency — that walk is the only other multi-
 * page cursor drain in the codebase (`RevertCutoffCoordinator.kt:35-48`)
 * and happens to use the same 5×50=250 budget shape, so reusing the
 * number keeps the two cursor-walk budgets readable as one family. If a
 * future task introduces a real local-history retention constant, this
 * value should be re-sourced to that constant.
 *
 * See [SLIMAPI_DEFAULT_PAGE_LIMIT] (200) for the per-page size; the
 * bound implies a 2-page cold-start follow worst case (200 + 50).
 */
internal const val SLIMAPI_LOCAL_HISTORY_BOUND = 250

/**
 * Outcome of a bounded cursor-walk drain.
 *
 *  - [Success]: drained cleanly (cursor-null, item-bound, or page-count cap).
 *  - [Partial]: mid-walk transport/page failure. Items are partial aggregate;
 *    caller should keep dirty / retry.
 *  - [Degraded]: loop detected (same cursor returned OR zero-new-items page)
 *    — the server is misbehaving but partial aggregate is still useful.
 *    Caller should keep dirty / retry, same as Partial.
 */
sealed interface SlimDrainOutcome {
    val items: List<MessageWithParts>
    data class Success(override val items: List<MessageWithParts>) : SlimDrainOutcome
    data class Partial(
        override val items: List<MessageWithParts>,
        val cause: Throwable,
    ) : SlimDrainOutcome
    /** Loop or zero-progress page. Treated same as [Partial] by callers. */
    data class Degraded(
        override val items: List<MessageWithParts>,
        val cause: Throwable,
    ) : SlimDrainOutcome
}

/**
 * G-F1: loop detected during cursor-walk drain. The server returned the
 * same cursor again or a page with zero new message IDs. Carried as
 * the [cause] inside [SlimDrainOutcome.Degraded] / [Partial].
 */
class SlimDrainLoopException(message: String) : java.io.IOException(message)
