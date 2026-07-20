package cn.vectory.ocdroid.util

/**
 * §grouping-rewrite Round-4 C4: single source of truth for workdir-path
 * normalization across the codebase.
 *
 * The disconnect pipeline crosses THREE layers that MUST agree on what
 * "same workdir" means:
 *
 *  1. **`buildWorkdirGroups`** (`ui/sessions/SessionsScreen.kt`) gates workdir
 *     visibility by normalized key — a workdir is visible iff its normalized
 *     form is in `recentWorkdirs` (normalized) or equals the draftWorkdir
 *     (normalized).
 *  2. **`SettingsManager.removeRecentWorkdir`** removes a workdir from the
 *     persistent `recent_workdirs_<fp>` list by matching the incoming display
 *     dir against stored entries.
 *  3. **`SettingsManager.addRecentWorkdir` / `getRecentWorkdirs`** store and
 *     return the ORIGINAL server-facing path (the server needs the real path
 *     for `getSessionsForDirectory`; normalizing on store would break the
 *     cold-start fan-out).
 *
 * Pre-C4, layers 1 and 2 used DIFFERENT normalizations: `buildWorkdirGroups`
 * trimmed + stripped surrounding slashes, while `removeRecentWorkdir` only
 * trimmed. So a stored `"proj-a/"` would be visible under key `"proj-a"` but
 * a disconnect with `"/proj-a"` (the display dir, absolute leading-slash
 * form) would fail to match → the variant persisted → the workdir
 * reappeared. C4 closes that gap by funnelling both layers through this
 * helper.
 *
 * The normalization itself is intentionally minimal: `trim()` (strip
 * surrounding whitespace) + `trim('/')` (strip surrounding slashes). It does
 * NOT canonicalize case, resolve `.`, resolve `..`, or collapse repeated
 * slashes — those transformations would require filesystem access (symlinks,
 * case sensitivity) the app cannot reliably perform cross-platform, and the
 * known-variant cases (phone-created "/proj-a" vs web-created "/proj-a/" or
 * "proj-a") are all surrounding-slash + whitespace. Adding more rules here
 * would change the visible-set contract; do NOT extend without coordinating
 * with `buildWorkdirGroups` + the SessionsScreenTest pinning cases.
 *
 * `internal` so it stays inside the app module (no need to expose to tests
 * beyond the module boundary; they get `internal` visibility for free).
 */
internal object WorkdirPaths {
    /**
     * Normalize a raw workdir path for COMPARISON KEYING only. The original
     * string is what gets stored / displayed / passed to server-facing APIs;
     * this function only produces the key used to decide "two paths refer to
     * the same project for grouping / disconnect-matching purposes."
     *
     * Empty/whitespace-only input returns an empty string (the only sensible
     * canonical form); callers should treat an empty result as "no key" and
     * skip the entry rather than grouping under `""`.
     */
    fun normalize(raw: String): String = raw.trim().trim('/')

    /**
     * **Server-facing** normalize for the `?directory=` query-param fan-out
     * (slimapi `/sessions` / `/questions` / `/permissions`). Aligns with
     * oc-slimapi v0.2.2's server-side `normalize_directory`:
     *
     *   `s.rstrip("/") or "/"`
     *
     * i.e. strip the trailing slash, but **preserve root `/`** (the empty
     * string and `/` both normalize to `/`). Idempotent.
     *
     * **This is intentionally DISTINCT from [normalize]**:
     *  - [normalize] is comparison-keying only — surrounding-slash-strip
     *    (`trim().trim('/')`) used to decide "two paths refer to the same
     *    project for grouping / disconnect-matching." The RAW string is
     *    what gets stored / displayed / passed to server APIs (per the
     *    existing contract documented above; do NOT change it).
     *  - [normalizeDirectory] is the value actually SENT to the server in
     *    the fan-out — it must agree with the server's `normalize_directory`
     *    so the client's dedup count matches the server's dedup count
     *    (slimapi v0.2.2 makes the server MORE lenient: it now strips +
     *    dedups too, so this is a tighten-to-align, non-breaking).
     *
     * Without this client-side normalize, `/app` + `/app/` could fan out
     * as 2 distinct entries (pre-server-normalize), inflating the
     * `?directory=` list and producing redundant routeTokens. The slim
     * q/p fan-out ([`computeQuestionFanOutWorkdirs`] + the resync
     * `directories` construction in `SessionStreamingService.onResync`)
     * runs this BEFORE `.distinct()` so both client and server see the
     * same single canonical entry per logical workdir.
     *
     * Boundary behaviour:
     *  - `""` → `"/"` (empty input is treated as root; never returns `""`)
     *  - `"/"` → `"/"` (root preserved, NOT collapsed to `""`)
     *  - `"/app/"` → `"/app"`
     *  - `"/app//"` → `"/app"` (all trailing slashes stripped, matching
     *    Python `rstrip("/")`)
     *  - interior slashes preserved (`/a/b/c` stays `/a/b/c`)
     *  - case / `.` / `..` / symlinks NOT touched (same minimalism as
     *    [normalize] — the app cannot reliably resolve them cross-platform).
     */
    fun normalizeDirectory(directory: String): String {
        val t = directory.trim()
        if (t.isEmpty()) return "/"
        // Server semantics: `s.rstrip("/") or "/"` — if stripping trailing
        // slashes empties the string (input was all slashes, e.g. "//"),
        // return root "/" rather than the empty string.
        val stripped = t.trimEnd('/')
        return if (stripped.isEmpty()) "/" else stripped
    }
}
