package cn.vectory.ocdroid.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * ③ ServerCompat — central, version-aware profile of the connected opencode
 * server, populated from `GET /global/health`'s `version` field.
 *
 * ## Why this exists
 *
 * ocdroid carries several compatibility shims that were originally written as
 * hardcoded assumptions about a specific server version's *observed* behaviour
 * (e.g. "1.17.11 emits `message.updated` for new messages", "1.17.12 loses
 * in-memory pending questions on restart"). Those are fragile: if the server
 * changes back, the shim breaks the other way; if it changes differently, a new
 * shim must be hand-added. [ServerCompatProfile] is the single entry point that
 * future shim migrations hang capability flags off, so each shim reads a flag
 * instead of guessing a version, and "which server versions are supported"
 * becomes an auditable property rather than scattered folklore.
 *
 * ## Current scope (layer A — scaffolding)
 *
 * This first increment only establishes the entry point: it parses the version
 * string into semver components and exposes an [isAtLeast] helper. No shim
 * consumes a flag yet (the existing shims are either already version-agnostic
 * or restored via tolerant parsing elsewhere). Capability flags will be added
 * here as individual shims are migrated in follow-up increments, each paired
 * with the [cn.vectory.ocdroid.data.api.OpenCodeApi] / controller site that
 * reads them and a version-fixture unit test.
 *
 * ## Population
 *
 * [update] is called from [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator]
 * whenever a health probe succeeds (or surfaces a version while still warming
 * up). Re-parsing the same version is idempotent and cheap, so callers need not
 * guard against redundant updates. Until the first successful health probe,
 * every field stays `null` and [isAtLeast] returns `true` (fail-open: treat an
 * unknown server as the newest, so feature gates default on rather than
 * silently disabling functionality).
 */
@Singleton
class ServerCompatProfile @Inject constructor() {

    /** The raw version string reported by the server (e.g. `"1.17.13"`), or null before first health. */
    @Volatile
    var version: String? = null
        private set

    @Volatile var major: Int? = null
        private set
    @Volatile var minor: Int? = null
        private set
    @Volatile var patch: Int? = null
        private set

    /**
     * Parses [value] (opencode `version` string, e.g. `"1.17.13"` or
     * `"1.17.13+abc"`) into semver components. Tolerant: a missing/short/
     * non-numeric version resets the components to null rather than throwing,
     * so a malformed server response can never break the profile.
     */
    fun update(value: String?) {
        version = value
        if (value.isNullOrBlank()) {
            major = null; minor = null; patch = null
            return
        }
        // Strip any build metadata / pre-release suffix after the first non-
        // numeric run, then take up to the first three numeric components.
        val parts = VERSION_RE.find(value)?.groupValues
            ?.drop(1)?.filter { it.isNotBlank() } ?: emptyList()
        major = parts.getOrNull(0)?.toIntOrNull()
        minor = parts.getOrNull(1)?.toIntOrNull()
        patch = parts.getOrNull(2)?.toIntOrNull()
    }

    /**
     * True when the connected server is at least [major].[minor].[patch].
     * Fail-open: returns `true` when the version is unknown (see class doc) so
     * feature gates default ON for an unrecognized server rather than silently
     * regressing. Callers that must distinguish "unknown" from "known old"
     * should check [version] == null explicitly.
     */
    fun isAtLeast(major: Int, minor: Int = 0, patch: Int = 0): Boolean {
        val ma = this.major ?: return true
        val mi = this.minor ?: return true
        val pa = this.patch ?: 0
        if (ma != major) return ma > major
        if (mi != minor) return mi > minor
        return pa >= patch
    }

    private companion object {
        // Greedy leading numeric components separated by dots, stopping at the
        // first non-numeric segment (covers "1.17.13", "1.17.13-rc1",
        // "1.17.13+sha", "v1.17.13"). Each group is \d+ so non-numeric builds
        // don't partially parse into garbage.
        val VERSION_RE = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?(?:[.\-+]\D.*)?""")
    }
}
