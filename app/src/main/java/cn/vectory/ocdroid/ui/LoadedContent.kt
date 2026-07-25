package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part

/**
 * §chat-list-detail §7.1: the STRUCTURAL owner of the chat content. A single
 * nullable slot of this type is the eventual replacement for the ~8 flat
 * content fields currently spread across [ChatState] (messages /
 * partsByMessage / streamingPartTexts / streamOwned /
 * streamingReasoningPart / olderMessagesCursor / hasMoreMessages /
 * currentModel). B0.5/B2 wire `ChatState.content: LoadedContent?`; B0 only
 * defines the type (PURE ADDITIVE — no state slice references it yet).
 *
 * # Why a value object (opus+bgpt consensus, §0.3)
 *
 * Today's 8 flat fields can each be written independently, so a reducer
 * that updates messages can forget to re-stamp the owner id — producing a
 * torn "messages belong to X but the slot thinks it's Y" state that the
 * render layer (`currentSessionId != null`) cannot detect. [LoadedContent]
 * WELDS the owner id to the content: construction is atomic, so the pair
 * `(sessionId, messages)` is always consistent. The render gate
 * (§7.1 / §5 P1):
 *
 * ```
 * detail(routeId, chat) = chat.content
 *     ?.takeIf { it.sessionId == routeId }
 *     ?.let { Content(it) }
 *     ?: Loading(routeId)
 * ```
 *
 * makes "render session X's transcript while the route is Y" structurally
 * unexpressible — the single AST-auditable seam (§14 G2).
 *
 * # §7.2 freshness / incarnation token (bgpt's A→B→A race)
 *
 * [routeInstance] is the TEMPORAL acceptance token — it answers "is this
 * content the product of the CURRENT incarnation of this session's route?"
 * The structural owner alone cannot: a stale REST message-load (req-1)
 * kicked off under an earlier `chat/A` incarnation can return AFTER a
 * later `chat/A` incarnation (A→B→A) has already committed newer content
 * (req-2). The sessionId guard only checks "is this A" — both incarnations
 * are A. [routeInstance] disambiguates.
 *
 * # Token form chosen: ROUTE-INSTANCE TOKEN (§7.2 option 1, recommended)
 *
 * The doc offers three forms (§7.2, "三选一，实施时定"); B0 settles on
 * option 1 (route-instance token) because:
 *  1. The doc explicitly recommends it ("（推荐）").
 *  2. It mirrors the existing [StoreState.sseConnectedGeneration]
 *     monotonic-CAS pattern (§7.2 "可复用项目既有 sseConnectedGeneration
 *     单调 CAS 模式") — a single Long per route-entry, minted at navigation
 *     time, CAS-validated at content submit. Lowest blast radius / fewest
 *     new types.
 *  3. It covers the A→B→A REST-message-load window that the existing
 *     tokens (completenessEpoch / sseConnectedGeneration / token-stream
 *     epoch) provably do not (§7.2 race trace).
 *
 * Mechanism: [cn.vectory.ocdroid.ui.OrchestratorViewModel.navigateToChat]
 * mints a fresh [routeInstance] = `store.chatRouteInstance + 1` at each
 * `chat/{id}` navigation and stamps it onto [StoreState.chatRouteInstance].
 * A content load (B0.5+) captures the live instance at request time and
 * stamps the returned [LoadedContent.routeInstance] with it. The render
 * gate accepts the content IFF `content.routeInstance ==
 * store.chatRouteInstance && content.sessionId == routeId`; a stale req-1
 * (older instance) is structurally rejected. The token is reducer-
 * internal — never reaches UI.
 *
 * §14 G1 (design-layer): this class + [StoreState.chatRouteInstance]
 * together make §5 P1 (structural) + P6 (temporal) unexpressible at the
 * content layer. B0.5's failing-first tests prove it end-to-end.
 */
data class LoadedContent(
    /** §7.1: content attribution — welded to [messages], never torn. */
    val sessionId: String,
    val messages: List<Message> = emptyList(),
    val partsByMessage: Map<String, List<Part>> = emptyMap(),
    val streamingPartTexts: Map<String, String> = emptyMap(),
    /** Token-stream ownership per partId (mirrors [ChatState.streamOwned]). */
    val streamOwned: Map<String, StreamOwnedState> = emptyMap(),
    val streamingReasoningPart: Part? = null,
    val olderMessagesCursor: String? = null,
    val hasMoreMessages: Boolean = false,
    val currentModel: Message.ModelInfo? = null,
    /**
     * §7.2: route-instance freshness token — the incarnation of the
     * `chat/{sessionId}` route under which this content was loaded.
     * CAS-compared against [StoreState.chatRouteInstance] at submit/render.
     * See class kdoc for the A→B→A race this closes.
     */
    val routeInstance: Long = 0L,
)
