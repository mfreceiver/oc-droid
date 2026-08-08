package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.model.DirectoryEntry

/**
 * §slimapi-directories — outcome of `GET /slimapi/directories` (repo layer).
 *
 * The VM translates these into
 * [cn.vectory.ocdroid.ui.sessions.DirectoriesUiState] and commits the UI state
 * write inside [OpenCodeRepository.commitIfConnectionCaptureCurrent] so the
 * check-and-write is atomic under the repo monitor + identityStore lock (closes
 * the repo-return → VM-write TOCTOU, incl. bundle-generation rotation that
 * leaves epoch+identity unchanged).
 */
internal sealed interface DirectoriesOutcome {
    /** Server-authoritative list; [isComplete] mirrors the envelope's `discoveryComplete`. */
    class ServerList(val items: List<DirectoryEntry>, val isComplete: Boolean) : DirectoriesOutcome

    /**
     * Marker — sidecar too old (404 `thin_route_not_found`) or non-slim. Carries
     * NO items; the VM assembles the MRU fallback itself (repo does not own MRU).
     */
    object Degraded : DirectoriesOutcome

    /** Transient / non-thin-route error → VM retains previous render + retries. */
    class Error(val cause: DirectoriesErrorCause) : DirectoriesOutcome

    /** Connection changed during the call → VM restores last render (stale) or errors. */
    object Dropped : DirectoriesOutcome
}

/** Classified failure cause for [DirectoriesOutcome.Error]. */
internal sealed interface DirectoriesErrorCause {
    /** 2xx with null/un-decodable body — must NOT be treated as Empty. */
    object MalformedBody : DirectoriesErrorCause
    /** Non-thin HTTP status (incl. 503 non-busy, 413, 4xx/5xx, auth) → retain + retry. */
    class Http(val code: Int) : DirectoriesErrorCause
    /** Transport / timeout / cancellation-free exception → retain + retry. */
    class Transient(val throwable: Throwable) : DirectoriesErrorCause
    /** Repo returned Dropped (stale capture); used by VM when no render history. */
    object ConnectionChanged : DirectoriesErrorCause
}

/**
 * Atomic snapshot of the slim mode + directories-capability flag, captured
 * under the repo monitor alongside [OpenCodeRepository.ConnectionCapture].
 *
 * `setSlimConnection()` resets [ServerCompatProfile.supportsSlimDirectories]
 * under the same monitor inside `configure()`; snapshotting the flags outside it
 * could capture a stale capability during a bundle-generation-only rotation
 * (new generation + old flag → wrong Degraded that the 4-field commit would
 * still accept). This snapshot travels in [DirectoriesResult] and is
 * re-validated by `OpenCodeRepository.commitDirectoriesIfCurrent`.
 */
internal data class ModeSnapshot(
    val slim: Boolean,
    val supportsDirectories: Boolean,
)

/**
 * Pairs the outcome with the [OpenCodeRepository.ConnectionCapture] AND the
 * [ModeSnapshot] validated during the call. The VM commits its state write via
 * `repository.commitDirectoriesIfCurrent(result) { … }` which re-validates BOTH
 * the mode snapshot AND the 4 connection-stamp fields atomically under the repo
 * monitor + identityStore lock.
 */
internal data class DirectoriesResult(
    val outcome: DirectoriesOutcome,
    val cap: OpenCodeRepository.ConnectionCapture,
    val modeSnap: ModeSnapshot,
)
