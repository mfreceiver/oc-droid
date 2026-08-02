package cn.vectory.ocdroid.ui

/**
 * §R-17 batch3d: Domain orchestration free functions. These are NOT the deleted
 * batch-2 AppState mirror helpers (aggregateFromSlices/syncSlicesFromAppState etc.).
 * They are coroutine-launch helpers called by the domain ViewModels and AppCore
 * orchestration extensions to perform async operations (load/refresh/mutate).
 * Future cleanup (batch3e+): may be inlined into individual VM private methods.
 */

import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun launchLoadProviders(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    slices: SliceFlows,
    settingsManager: SettingsManager,
    hostProfileStore: HostProfileStore,
    onNonFatalError: (String, Throwable?) -> Unit,
    /**
     * §需求13 rev-8 #2 (council #2 fix): invoked on REAL fetch failure
     * (getProvidersOrFailure returns Result.failure). The caller wires this
     * to [ConnectionCoordinator.resetProvidersFirstFetchGate] so the
     * single-flight latch DISARMS on failure → the next loadInitialData /
     * ON_RESUME auto-retries the catalog fetch (weak-network cold-start
     * recovery). NOT called on success (latch stays armed = no duplicate
     * fetch) and NOT called on empty-catalog success (that is Result.success).
     * Default `{}` preserves backward compat for tests / legacy callers.
     */
    onProvidersFirstFetchFailed: () -> Unit = {},
    /**
     * §需求4 host/fp guard (mirrors launchLoadMessages expectedProfileId):
     * the serverGroupFp captured AT CALL TIME (when the REST request was
     * initiated). Compared against [currentProfileId] at onSuccess — a
     * mismatch means the user switched host during the async REST call, so
     * the stale response from the OLD host must NOT be written into the NEW
     * host's persisted state (lost/cross data). Default "" → guard is a
     * no-op (both sides "" → equal), preserving backward compat for tests /
     * legacy callers.
     */
    expectedProfileId: String = "",
    /**
     * §需求4 host/fp guard: provider for the CURRENT host's serverGroupFp,
     * read at onSuccess time. See [expectedProfileId].
     */
    currentProfileId: () -> String = { "" },
    /**
     * §ABA-triple-guard (F1): the endpoint fingerprint (`ClientBundle.
     * endpointFp` = `hostSnapshot.baseUrl`) captured AT CALL TIME, alongside
     * [expectedProfileId]. Compared against [currentEndpointFp] at onSuccess.
     * Closes the ABA window the profileId-only guard leaves open: a stale
     * in-flight `/config/providers` response from the SAME profile but a
     * DIFFERENT URL (user edited the same profile's serverUrl → a new
     * ClientBundle with a new endpointFp was published mid-flight) would
     * otherwise穿透 the profileId guard and write stale catalog/model data
     * into the new URL's persisted state. mTLS-only / Basic-Auth changes do
     * NOT clear model data (same-endpoint stale write-back is harmless), so
     * endpointFp=baseUrl is SUFFICIENT — no HostInstanceKey introduced.
     * Default "" → guard is a no-op (both sides "" → equal), preserving
     * backward compat for tests / legacy callers.
     */
    expectedEndpointFp: String = "",
    /**
     * §ABA-triple-guard (F1): provider for the CURRENT endpoint fingerprint,
     * read at onSuccess time. See [expectedEndpointFp].
     */
    currentEndpointFp: () -> String = { "" },
    /**
     * §ABA-triple-guard (F1): the connection generation
     * (`ClientBundle.generation`, Long) captured AT CALL TIME. Compared
     * against [currentGeneration] at onSuccess. Generation is bumped on
     * EVERY bundle publication, including `resetLocalDataAndResync` (which
     * bumps +1) and any full reconfigure — so a stale in-flight response
     * captured under an older generation is correctly discarded even when
     * profileId + endpointFp happen to coincide (e.g. a reset that re-points
     * at the same URL). Reuses the EXISTING [ClientBundle.generation]; no
     * new epoch field. Default 0L → guard is a no-op (both sides 0L →
     * equal), preserving backward compat for tests / legacy callers.
     */
    expectedGeneration: Long = 0L,
    /**
     * §ABA-triple-guard (F1): provider for the CURRENT connection generation,
     * read at onSuccess time. See [expectedGeneration].
     */
    currentGeneration: () -> Long = { 0L },
) {
    // §需求13: flip the loading flag SYNCHRONOUSLY on the calling thread
    // (mirrors launchLoadMessages setting isLoadingMessages at
    // MessageActions.kt:132) so the Model management IconButton + per-row
    // Switches disable immediately on user tap, not after the coroutine
    // dispatches. Cleared in the `finally` below on success / failure /
    // cancellation.
    //
    // §需求13 rev-8 #2d (rev-gpt finding #3): guard against pre-start
    // cancellation. If the scope is already cancelled (viewModelScope cleared
    // by config change / process death, or appScope shutdown racing the click),
    // scope.launch returns a Job that NEVER enters its body → the `finally`
    // below never runs → isLoadingProviders would stay true forever (stuck
    // loading state, not a duplicate-fetch data bug). Check [CoroutineScope.
    // isActive] AFTER setting the flag but BEFORE launching: if the scope is
    // dead, clear the flag back and bail. The flag-set + isActive-read +
    // launch call are all on Dispatchers.Main.immediate (non-suspending), so
    // no interleaving is possible between them; if isActive is true here the
    // launched coroutine WILL start, and its `finally` covers subsequent
    // cancellation (Kotlin structured concurrency runs finally on cancel).
    slices.mutateSettings { it.copy(isLoadingProviders = true) }
    if (!scope.isActive) {
        // Scope already cancelled — clear the flag back and bail. The caller's
        // AppCore sink guard (rev-8 #2c) means a subsequent LoadProviders will
        // re-fire once a live scope is available.
        slices.mutateSettings { it.copy(isLoadingProviders = false) }
        return
    }
    scope.launch {
        try {
            // §需求13 rev-7 #2: call getProvidersOrFailure (NOT getProviders)
            // so real network/HTTP/parse failures propagate as
            // Result.failure → .onFailure fires → UiEvent.Error snackbar.
            // getProviders masks failures as empty-catalog success (last-mile
            // defense for latent callers), which made the error-feedback
            // feature dead. An empty catalog from a healthy server still
            // returns Result.success(empty) — that is NOT an error.
            repository.getProvidersOrFailure()
                .onSuccess { providers ->
                    // §ABA-triple-guard (F1): the profileId-only guard left an
                    // ABA window open — a stale in-flight `/config/providers`
                    // response from the SAME profile but a DIFFERENT URL (or
                    // published under an older connection generation) could
                    //穿透 and write stale catalog/model data into the new
                    // endpoint's persisted state. Now re-validate the FULL
                    // triple `(profileId, endpointFp, generation)` captured at
                    // request-start against the live values read at onSuccess:
                    //   - profileId mismatch → user switched host.
                    //   - endpointFp mismatch → same profile edited its URL
                    //     (new ClientBundle published mid-flight). mTLS-only /
                    //     Basic-Auth changes do NOT bump endpointFp and the
                    //     resulting same-endpoint stale write-back is harmless,
                    //     so endpointFp=baseUrl is sufficient (no HostInstanceKey).
                    //   - generation mismatch → any bundle publication since
                    //     request-start (reconfigure OR resetLocalDataAndResync,
                    //     which already bumps +1 → semantically consistent, no
                    //     special-casing needed).
                    // Any component mismatch → drop the stale response (no
                    // write to the live endpoint's persisted state). The
                    // loading flag is still cleared by the outer `finally`.
                    if (expectedProfileId != currentProfileId() ||
                        expectedEndpointFp != currentEndpointFp() ||
                        expectedGeneration != currentGeneration()) return@onSuccess
                    // §R-17 M3 / batch2 step d: providers lives on the settings
                    // slice — written directly via thread-safe update.
                    //
                    // §bug5 / R-20 Phase 5 / §需求4: also reconcile the per-
                    // serverGroupFp model data against the freshly-fetched catalog
                    // so disable status is inherited ONLY for models still present
                    // on the server (was per-baseUrl before Phase 5):
                    //  1. Build the new availability set (`"$providerId/$modelId"`).
                    //  2. Atomically (§需求4: single monitor-held RMW via
                    //     [SettingsManager.reconcileModelData] →
                    //     [ModelPrefs.reconcileModelData], so a concurrent manual
                    //     model toggle cannot lose its update against this RMW):
                    //     read the previously-persisted disabled set, intersect
                    //     with the new availability (drop disabled entries that no
                    //     longer exist server-side), persist availability + the
                    //     trimmed disabled set, return the inherited disabled set.
                    //  3. Update the in-memory slice's disabledModels atomically
                    //     with providers so the UI stays consistent.
                    //
                    // §需求4: single read of currentProfile() — closes the TOCTOU
                    // where two reads (one for .serverGroupFp, one for .id fallback)
                    // could observe different profiles if the user switched host
                    // between them.
                    //
                    // §需求13: isLoadingProviders=false folded into this same
                    // mutateSettings call (single CAS) — the `finally` below is
                    // the canonical clearer (this is a redundant-but-harmless
                    // early clear on the success path that minimizes the
                    // perceived loading window by one dispatch).
                    val profile = hostProfileStore.currentProfile()
                    val fp = profile.id
                    val newAvailableKeys = buildSet {
                        providers.providers.forEach { provider ->
                            provider.models.keys.forEach { modelId ->
                                add("${provider.id}/$modelId")
                            }
                        }
                    }
                    val inheritedDisabled = settingsManager.reconcileModelData(fp, newAvailableKeys)
                    slices.mutateSettings {
                        it.copy(
                            providers = providers,
                            disabledModels = inheritedDisabled,
                            isLoadingProviders = false,
                        )
                    }
                }
                .onFailure { error ->
                    onNonFatalError("Failed to load providers", error)
                    // §需求13 rev-8 #2 (council #2 fix): disarm the single-flight latch so
                    // the next loadInitialData/ON_RESUME retries — weak-network cold-start
                    // recovery. No-op on success path (latch stays armed = no duplicate).
                    onProvidersFirstFetchFailed()
                }
        } finally {
            // §需求13: clear the loading flag on EVERY exit path — success,
            // failure (onNonFatalError above), AND CancellationException (host
            // switch during the in-flight REST call, viewModelScope clear,
            // etc.). MutateSettings is a CAS so this is safe even if the
            // success path already cleared it (idempotent write of false).
            slices.mutateSettings { it.copy(isLoadingProviders = false) }
        }
    }
}
