package cn.vectory.ocdroid.ui

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * §chat-list-detail §8.1: sealed parameterized-route hierarchy — the
 * design-layer replacement for the bare-string + [NavRoute] identity model.
 * Each variant models a single destination the NavController can host,
 * carrying its parsed path/query arguments (URL-decoded).
 *
 * B0 scaffolding (PURE ADDITIVE): [parseRoute] + this sealed hierarchy
 * exist but are NOT YET wired into any live navigation flow. [NavRoute]
 * (the enum identity model) stays byte-for-byte untouched and remains the
 * live path; B1/B3 swap the call sites over to [parseRoute]. The case set
 * mirrors the routes registered in AppShell's NavHost today (Chat /
 * ChatPreview / Sessions / Files / Git / Settings + nested SettingsHosts)
 * PLUS the new parameterized `chat/{sessionId}` ([ChatDetail]) and
 * `chat/new` ([NewConversation]) destinations that B1 will register.
 *
 * Cold-start fail-safe (§5 P3 / §8.1): null / empty / unknown top-level /
 * legacy bare `"chat"` all map to [Sessions] — the render gate
 * (§7.1 `content.sessionId == routeId`) combined with `Sessions ⟹
 * placeholder` (P2) makes a stale persisted route structurally
 * unrenderable as a transcript.
 */
sealed interface AppRoute {
    /** Home hub (§home-hub T3). startDestination + cold-start fail-safe target. */
    data object Sessions : AppRoute

    /** `files?workdir=&path=` (browser). Either/both args optional. */
    data class Files(val workdir: String?, val path: String?) : AppRoute

    /** `git?session=&workdir=` (workspace). Either/both args optional. */
    data class Git(val session: String?, val workdir: String?) : AppRoute

    /** `settings` top-level destination. */
    data object Settings : AppRoute

    /**
     * `settings/hosts` — the one nested Settings route modeled as its own
     * case per §8.1's literal case set. Other nested settings routes
     * (appearance / models / notifications / about / debug) classify to
     * [Settings] as their top-level identity, matching
     * [NavRoute.fromRouteKey]'s `settings/…` prefix rule.
     */
    data object SettingsHosts : AppRoute

    /**
     * `chat/{sessionId}` — the parameterized conversation route (D1). The
     * id MUST satisfy the `ses_` branded-id grammar; [parseRoute] rejects
     * ill-formed ids by mapping to [Sessions] (§5 P4 fail-safe).
     */
    data class ChatDetail(val sessionId: String) : AppRoute

    /** `chat/new?workdir=…` — explicit new-conversation draft route (D4). */
    data class NewConversation(val workdir: String?) : AppRoute

    /** `chat/preview?workdir=&path=` — existing fullscreen file preview. */
    data class ChatPreview(val workdir: String?, val path: String?) : AppRoute
}

/**
 * §chat-list-detail §8.1 + §14 G4: the parameterized-route parser. Replaces
 * the identity-only [NavRoute.fromRouteKey] with a grammar that carries the
 * path/query arguments (sessionId / workdir / path) needed for the
 * route-driven render gate (§7.1).
 *
 * Grammar (G4 edge cases handled):
 *  - null / empty / blank / unknown top-level → [AppRoute.Sessions]
 *    (fail-safe).
 *  - `sessions` → [AppRoute.Sessions].
 *  - `files[?workdir=…&path=…]` → [AppRoute.Files] (query optional).
 *  - `git[?session=…&workdir=…]` → [AppRoute.Git] (query optional).
 *  - `settings` → [AppRoute.Settings]; `settings/hosts` →
 *    [AppRoute.SettingsHosts]; any other `settings/…` → [AppRoute.Settings]
 *    (top-level identity).
 *  - `chat/{id}` → [AppRoute.ChatDetail] iff `{id}` matches the `ses_`
 *    grammar; ill-formed id → [AppRoute.Sessions] (P4 fail-safe).
 *  - `chat/new[?workdir=…]` → [AppRoute.NewConversation] (D1
 *    disambiguation: `chat/new` is reserved and never treated as a session
 *    id — the `ses_` brand makes the two unambiguous).
 *  - `chat/preview[?workdir=…&path=…]` → [AppRoute.ChatPreview].
 *  - bare legacy `chat` (pre-D1 persistence / old notification intents) →
 *    [AppRoute.Sessions] (§10 cold-start fail-safe — never [AppRoute.ChatDetail]).
 *
 * Grammar strictness (§14 G4): each route accepts ONLY its expected number
 * of path segments; UNEXPECTED trailing segments classify to [Sessions]
 * (fail-safe, §5 P3). Concretely — `chat/ses_valid/garbage` → Sessions
 * (3 segments, expected 2); `settings/hosts/garbage` → Sessions (3 segments,
 * expected ≤2); `files/wd/extra` → Sessions (`files` takes query params,
 * not extra path segments). This closes the "ignore trailing junk" gap that
 * would otherwise let a malformed/deep-link construct a [AppRoute.ChatDetail]
 * from a valid prefix + attacker-controlled suffix.
 *
 * URL decoding: path segments + query values are URL-decoded (handles
 * `%2F`, `%20`, etc.). RFC 3986 distinction: `+` is LITERAL in a path
 * segment (only form-urlencoded query treats `+` as space), so path decode
 * preserves `+` as-is — an external deep link `chat/ses+a` yields id `ses+a`
 * which [isValidSessionId] rejects (not `ses_`-branded) → Sessions fail-safe.
 * Query decode treats `+` as space (form-encoding, mirroring [NavRoute]'s
 * `encodeParam` round-trip). Both the raw (pre-decode) and decoded forms are
 * accepted because NavController's argument-decoding timing varies
 * (G4 — "NavController 解码时序"). The parser is total — never throws.
 */
/**
 * §B4 / §10: extract the chat/{id} session id from a raw [NavState.lastRoute]
 * (or any route string). Returns null when the route is not a ChatDetail
 * (Sessions / bare chat / Files / …). Used as the sole route-id判据 for
 * delete/archive/close/refresh transitions — never read open-tabs-list.
 */
internal fun routeChatSessionId(raw: String?): String? =
    (parseRoute(raw) as? AppRoute.ChatDetail)?.sessionId

fun parseRoute(raw: String?): AppRoute {
    if (raw.isNullOrBlank()) return AppRoute.Sessions
    // Strip fragment first; then split path from query.
    val noFragment = raw.substringBefore('#')
    val rawPath = noFragment.substringBefore('?')
    val query = noFragment.substringAfter('?', "")
    // URL-decode the path (NavController may deliver either raw or decoded
    // form depending on argument-decoding timing — G4). '+' is LITERAL in a
    // path per RFC 3986 (only form-encoding treats it as space).
    val path = decodePathSegment(rawPath) ?: rawPath
    // Filter empty segments (handles leading/trailing/duplicate slashes).
    val segments = path.split('/').filter { it.isNotEmpty() }
    return when (segments.firstOrNull()) {
        // sessions is query-only (home hub); extra path segments fall through
        // to the same Sessions fail-safe (no distinct malformed state).
        null, "sessions" -> AppRoute.Sessions
        // files takes query params (workdir / path), NOT extra path segments.
        // A 2nd+ path segment → malformed → Sessions (§5 P3).
        "files" -> if (segments.size > 1) AppRoute.Sessions
                   else AppRoute.Files(
                       workdir = queryParam(query, "workdir"),
                       path = queryParam(query, "path"),
                   )
        // git takes query params (session / workdir), NOT extra path segments.
        "git" -> if (segments.size > 1) AppRoute.Sessions
                 else AppRoute.Git(
                     session = queryParam(query, "session"),
                     workdir = queryParam(query, "workdir"),
                 )
        // settings: at most 2 segments (settings / settings/hosts). Extra → Sessions.
        "settings" -> when {
            segments.size > 2 -> AppRoute.Sessions // trailing junk (§5 P3)
            segments.getOrNull(1) == null -> AppRoute.Settings
            segments[1] == "hosts" -> AppRoute.SettingsHosts
            // Other nested settings (appearance / models / …) classify to
            // their top-level identity, matching NavRoute.fromRouteKey.
            else -> AppRoute.Settings
        }
        // chat: exactly 2 segments (chat + {id|new|preview}). 1 = legacy bare;
        // 3+ = malformed. Both → Sessions (fail-safe).
        "chat" -> when {
            segments.size > 2 -> AppRoute.Sessions // trailing junk (§5 P3)
            segments.getOrNull(1) == null -> AppRoute.Sessions // legacy bare "chat" (§10)
            segments[1] == "new" -> AppRoute.NewConversation(workdir = queryParam(query, "workdir"))
            segments[1] == "preview" -> AppRoute.ChatPreview(
                workdir = queryParam(query, "workdir"),
                path = queryParam(query, "path"),
            )
            else -> {
                val id = segments[1]
                if (isValidSessionId(id)) AppRoute.ChatDetail(id)
                else AppRoute.Sessions // P4: ill-formed id → fail-safe
            }
        }
        // Unknown top-level → Sessions (fail-safe, §5 P3).
        else -> AppRoute.Sessions
    }
}

/**
 * §chat-list-detail §14 G4 / §B3-C1: session-id grammar check (BRAND-LEVEL).
 * Session ids are branded `ses_` strings (util/SessionPrefs §3 Phase 5 / N1:
 * NOT UUIDs — clone/reset servers can collide, hence the brand). This is the
 * parser's P4 gate: an id that is not branded `ses_<nonempty>` cannot index
 * a real session, so [parseRoute] routes it to [AppRoute.Sessions] rather
 * than constructing an [AppRoute.ChatDetail] that the render gate (§7.1)
 * would have to reject at render time. Whitespace is rejected (would produce
 * a malformed route string `"chat/$id"` that NavController cannot handle);
 * non-whitespace tail chars are lenient (the brand is the contract; the
 * tail is opaque server-side — the `ses_%2F` path-decoding edge case is
 * caught by [parseRoute]'s trailing-segment guard instead).
 *
 * **DO NOT use this predicate alone for notification/Intent entry**.
 * Notification ids come from untrusted Intent extras and bypass [parseRoute]'s
 * full grammar, including the trailing-segment guard. Use [isNavigableChatSessionId]
 * instead, which validates the id through the COMPLETE route grammar —
 * rejecting path separators (`ses_foo/bar`), spaces, bare prefix, and any
 * id that would produce a non-navigable `"chat/$id"` route string.
 */
internal fun isValidSessionId(id: String): Boolean =
    id.startsWith("ses_") && id.length > "ses_".length && id.none { it.isWhitespace() }

/**
 * §B3-C1: route-safe validation for notification/Intent session ids. Checks
 * whether the given id produces a navigable [AppRoute.ChatDetail] when
 * slotted into the canonical chat route string. This is the AUTHORITATIVE
 * gate for entry-point validation (e.g. [MainActivity.handleSessionExtra])
 * because it runs the id through the FULL [parseRoute] grammar:
 *
 *  - `ses_abc` → `parseRoute("chat/ses_abc")` → [AppRoute.ChatDetail]("ses_abc")
 *    and the parsed sessionId equals the raw id ✓
 *  - `ses_foo/bar` → `parseRoute("chat/ses_foo/bar")` → 3 segments → [Sessions] ✗
 *  - `ses_valid?x` / `ses_valid#frag` → parseRoute strips query/fragment and
 *    would yield ChatDetail("ses_valid"), but the raw id ≠ parsed sessionId
 *    → reject ✗ (B3 rev-gpt #3: Intent extras must not smuggle route syntax)
 *  - `ses_` → `parseRoute("chat/ses_")` → [isValidSessionId] rejects → [Sessions] ✗
 *  - `foo` → `parseRoute("chat/foo")` → unbranded → [Sessions] ✗
 *  - null / blank → early false ✗
 *
 * This is the SINGLE validation call site for notification entry;
 * [isValidSessionId] remains as the brand-level predicate used inside
 * [parseRoute] itself.
 */
internal fun isNavigableChatSessionId(id: String?): Boolean {
    if (id.isNullOrBlank()) return false
    val route = parseRoute("chat/$id")
    // Require the parsed ChatDetail to carry the EXACT raw id — not a stripped
    // prefix after parseRoute peels off `?` / `#`. Without this equality check,
    // `ses_valid?x` would still navigate as ChatDetail("ses_valid").
    return route is AppRoute.ChatDetail && route.sessionId == id
}

/**
 * URL-decode a PATH segment. Per RFC 3986, `+` is LITERAL in a path (only
 * form-urlencoded query treats `+` as space), so `+` is pre-encoded to `%2B`
 * before [URLDecoder.decode] (which otherwise mangles `+`→space per Java's
 * x-www-form-urlencoded contract). Returns null on malformed input (never
 * throws — the parser is total).
 */
private fun decodePathSegment(value: String): String? =
    try {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
    } catch (_: Exception) {
        null
    }

/**
 * URL-decode a QUERY value. Form-encoding treats `+` as space — mirrors
 * [NavRoute]'s private `encodeParam` (`+`→`%20` swap) so a round-trip
 * through encode → decode is lossless. Returns null on malformed input.
 */
private fun decodeQueryValue(value: String): String? =
    try {
        URLDecoder.decode(value.replace("+", "%20"), StandardCharsets.UTF_8)
    } catch (_: Exception) {
        null
    }

/**
 * Extract the FIRST occurrence of [name] from a `k=v&k2=v2` query string.
 * URL-decoded (form-encoding: `+`→space). Returns null if absent or empty.
 * G4 "重复 query" — only the first occurrence wins (matches NavController's
 * single-value argument model; a duplicate key cannot smuggle a second
 * value through).
 */
private fun queryParam(query: String, name: String): String? {
    if (query.isEmpty()) return null
    val prefix = "$name="
    return query.split('&')
        .firstOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.takeIf { it.isNotEmpty() }
        ?.let { decodeQueryValue(it) }
}

/**
 * §chat-list-detail §8.1: build helpers — the inverse of [parseRoute]. Used
 * (B1+) by [OrchestratorViewModel.navigateToChat] and the entry-point call
 * sites to construct the canonical route strings the NavController
 * registers. Provided here so the route-string grammar has a single
 * authoritative source: parser + builder share the same module, and the
 * shared routes (Files / Git / ChatPreview) delegate to [NavRoute]'s
 * existing public builders to avoid a second encoding implementation.
 */
fun AppRoute.routeString(): String = when (this) {
    AppRoute.Sessions -> NavRoute.Sessions.route
    is AppRoute.Files -> NavRoute.filesRoute(workdir, path)
    is AppRoute.Git -> NavRoute.gitRoute(session, workdir)
    AppRoute.Settings -> NavRoute.Settings.route
    AppRoute.SettingsHosts -> NavRoute.settingsHostsRoute
    is AppRoute.ChatDetail -> "chat/${encodeSegment(sessionId)}"
    is AppRoute.NewConversation -> newConversationRoute(workdir)
    is AppRoute.ChatPreview -> NavRoute.chatPreviewRoute(workdir, path)
}

/** §chat-list-detail §8.1 D4: `chat/new[?workdir=…]` builder. */
private fun newConversationRoute(workdir: String?): String =
    if (workdir.isNullOrBlank()) "chat/new" else "chat/new?workdir=${encodeSegment(workdir)}"

/** URL-encode a single path/query segment; mirrors [NavRoute]'s `encodeParam`. */
private fun encodeSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
