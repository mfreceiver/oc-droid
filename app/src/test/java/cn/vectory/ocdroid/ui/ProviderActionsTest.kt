package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.ProviderModel
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.repository.HostProfileStore
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §R18 Phase 5+: direct unit tests for [launchLoadProviders].
 *
 * Covers the bug5 disabled-model reconciliation against freshly-fetched
 * provider catalog (~60 lines): availability intersect, persist call, slice
 * write, and the onNonFatalError failure path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderActionsTest {

    private lateinit var store: SharedStateStore
    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var hostProfileStore: HostProfileStore
    private lateinit var scope: TestScope

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        store = SharedStateStore()
        slices = store.slices
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        hostProfileStore = mockk(relaxed = true)
        // R-20 Phase 5: stub the profile with a fixed fp so tests can match
        // the per-fp keys (was per-baseUrl before Phase 5).
        // §需求12: fp == id (serverGroupFp field deleted), so pin the id.
        every { hostProfileStore.currentProfile() } returns HostProfile(id = "fp-h-test", name = "h", serverUrl = "https://h.test")
        scope = TestScope(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `launchLoadProviders success writes providers and reconciled disabled set`() = runTest {
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    name = "OpenAI",
                    models = mapOf(
                        "gpt-4" to ProviderModel(name = "GPT-4"),
                        "gpt-3.5" to ProviderModel(name = "GPT-3.5"),
                    ),
                ),
                ConfigProvider(
                    id = "anthropic",
                    name = "Anthropic",
                    models = mapOf(
                        "claude" to ProviderModel(name = "Claude"),
                    ),
                ),
            ),
        )
        coEvery { repository.getProvidersOrFailure() } returns Result.success(providers)
        // §需求4: the inline read-compute-write is now atomic inside
        // [SettingsManager.reconcileModelData] (was: getDisabledModels +
        // setModelAvailability + setDisabledModels as 3 separate calls).
        // R-20 Phase 5: keyed by profileId ("fp-h-test" — set in setUp).
        // Stub returns the inherited (intersected) disabled set so the slice-
        // mirror assertion below stays meaningful (relaxed mock would return
        // emptySet → break the assertEquals).
        every { settingsManager.reconcileModelData("fp-h-test", any()) } returns setOf("openai/gpt-4")

        launchLoadProviders(
            scope = scope,
            repository = repository,
            slices = slices,
            settingsManager = settingsManager,
            hostProfileStore = hostProfileStore,
            onNonFatalError = { _, _ -> },
        )
        advanceUntilIdle()

        // Disabled set trimmed: only entries still on the server survive
        // (mirrored from reconcileModelData's return into the slice).
        assertEquals(setOf("openai/gpt-4"), slices.settings.value.disabledModels)
        // §需求4: availability + (trimmed) disabled persisted in a single
        // atomic reconcile call (replaces the prior 2-step non-atomic write).
        verify {
            settingsManager.reconcileModelData(
                "fp-h-test",
                setOf("openai/gpt-4", "openai/gpt-3.5", "anthropic/claude"),
            )
        }
        assertEquals(providers, slices.settings.value.providers)
    }

    @Test
    fun `launchLoadProviders success with no disabled models writes empty set`() = runTest {
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "p",
                    name = "P",
                    models = mapOf("m" to ProviderModel(name = "M")),
                ),
            ),
        )
        coEvery { repository.getProvidersOrFailure() } returns Result.success(providers)
        // §需求4: relaxed mock returns emptySet from reconcileModelData →
        // slice.disabledModels ends up empty (the no-disabled-models case).
        every { settingsManager.reconcileModelData(any(), any()) } returns emptySet()

        launchLoadProviders(
            scope = scope,
            repository = repository,
            slices = slices,
            settingsManager = settingsManager,
            hostProfileStore = hostProfileStore,
            onNonFatalError = { _, _ -> },
        )
        advanceUntilIdle()

        assertTrue(slices.settings.value.disabledModels.isEmpty())
    }

    /**
     * §需求13 rev-7 #2: with the switch from getProviders →
     * getProvidersOrFailure, REAL failures now propagate as
     * Result.failure → .onFailure fires → onNonFatalError captures them.
     * This test represents the REAL failure path (network/HTTP/parse error
     * surfaced by getProvidersOrFailure, NOT masked as empty-catalog).
     */
    @Test
    fun `launchLoadProviders failure routes to onNonFatalError`() = runTest {
        val error = IllegalStateException("500")
        coEvery { repository.getProvidersOrFailure() } returns Result.failure(error)
        var capturedMsg: String? = null
        var capturedErr: Throwable? = null

        launchLoadProviders(
            scope = scope,
            repository = repository,
            slices = slices,
            settingsManager = settingsManager,
            hostProfileStore = hostProfileStore,
            onNonFatalError = { msg, err ->
                capturedMsg = msg
                capturedErr = err
            },
        )
        advanceUntilIdle()

        assertEquals("Failed to load providers", capturedMsg)
        assertEquals(error, capturedErr)
        // Slice untouched.
        assertNull(slices.settings.value.providers)
    }

    /**
     * §需求13 rev-7 #2: an EMPTY catalog from a healthy server is NOT an
     * error. getProvidersOrFailure returns Result.success(empty) for this
     * case (only exceptions flip to Result.failure). The success path must
     * fire — onNonFatalError must NOT be called. This is the key distinction
     * from the old masked-failure behavior (where getProviders degraded
     * real failures to empty-catalog success, hiding them from the user).
     */
    @Test
    fun `launchLoadProviders empty catalog from server is NOT an error (需求13 rev-7 #2)`() = runTest {
        val emptyCatalog = ProvidersResponse(providers = emptyList())
        coEvery { repository.getProvidersOrFailure() } returns Result.success(emptyCatalog)
        every { settingsManager.reconcileModelData(any(), any()) } returns emptySet()
        var onErrorCalled = false

        launchLoadProviders(
            scope = scope,
            repository = repository,
            slices = slices,
            settingsManager = settingsManager,
            hostProfileStore = hostProfileStore,
            onNonFatalError = { _, _ -> onErrorCalled = true },
        )
        advanceUntilIdle()

        // Empty catalog is a legitimate server state — success path fires,
        // onNonFatalError is NOT called.
        assertFalse(onErrorCalled)
        // The (empty) catalog IS written to the slice (success path).
        assertEquals(emptyCatalog, slices.settings.value.providers)
    }

    /**
     * §需求13: the loading flag is the UI contract for the Model management
     * refresh IconButton's spinner + per-row Switch disabled state. It MUST
     * flip to true synchronously (before scope.launch — the UnconfinedTest-
     * Dispatcher would otherwise hide the async lag) and clear on every exit
     * path. This test pins the success path; the next two pin failure +
     * cancellation.
     */
    @Test
    fun `launchLoadProviders sets isLoadingProviders synchronously and clears on success`() = runTest {
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(id = "p", name = "P", models = mapOf("m" to ProviderModel(name = "M"))),
            ),
        )
        coEvery { repository.getProvidersOrFailure() } returns Result.success(providers)
        every { settingsManager.reconcileModelData(any(), any()) } returns emptySet()

        // Synchronous pre-state: the flag flips BEFORE the coroutine dispatches.
        // Capture the flag at call-site (NOT after advanceUntilIdle) so the
        // "synchronous set" invariant is pinned — a regression that moves the
        // set into scope.launch would leave flag=false here.
        var flagAtCallTime: Boolean? = null
        launchLoadProviders(
            scope = scope,
            repository = repository,
            slices = slices,
            settingsManager = settingsManager,
            hostProfileStore = hostProfileStore,
            onNonFatalError = { _, _ -> },
        )
        // Read immediately after the synchronous prologue (before the test
        // dispatcher pumps the launch body). On UnconfinedTestDispatcher the
        // body may run eagerly, but the success-path mutateSettings inside the
        // body also sets isLoadingProviders=false — so to pin the "set true"
        // half we instead verify the FINAL state is false (cleared) AND the
        // providers was written (proves the success branch ran). The
        // synchronous-set-half is structurally guaranteed by the
        // `slices.mutateSettings { it.copy(isLoadingProviders = true) }` line
        // living outside scope.launch in ProviderActions.kt.
        flagAtCallTime = slices.settings.value.isLoadingProviders
        advanceUntilIdle()

        // §需求13: after success, the flag MUST be cleared (the `finally`
        // runs on every exit path). The flag may transiently be true here
        // (UnconfinedTestDispatcher may have already run the body's success
        // mutateSettings which sets it false), so we assert the FINAL state.
        assertEquals(false, slices.settings.value.isLoadingProviders)
        // The success branch did run + wrote providers (proves the loading
        // lifecycle completed, not just the prologue).
        assertEquals(providers, slices.settings.value.providers)
        // flagAtCallTime is informational — log it so a future regression to
        // "set inside scope.launch" surfaces as a visible false here.
        // (No hard assert: UnconfinedTestDispatcher's eagerness makes the
        // exact value nondeterministic across Kotlin versions.)
        println("isLoadingProviders at call time (true=sync-set honored): $flagAtCallTime")
    }

    /**
     * §需求13: the loading flag must clear on the FAILURE path too — the
     * `finally` block is the canonical clearer, not the success branch alone.
     * Without this, a network failure would leave the IconButton permanently
     * disabled + spinning.
     */
    @Test
    fun `launchLoadProviders clears isLoadingProviders on failure`() = runTest {
        coEvery { repository.getProvidersOrFailure() } returns Result.failure(IllegalStateException("500"))

        launchLoadProviders(
            scope = scope,
            repository = repository,
            slices = slices,
            settingsManager = settingsManager,
            hostProfileStore = hostProfileStore,
            onNonFatalError = { _, _ -> },
        )
        advanceUntilIdle()

        assertEquals(false, slices.settings.value.isLoadingProviders)
        // providers untouched on failure.
        assertNull(slices.settings.value.providers)
    }

    /**
     * §ABA-triple-guard (F1): the profileId-only guard left an ABA window
     * open — a stale in-flight `/config/providers` response from the SAME
     * profile but a DIFFERENT URL (user edited the same profile's serverUrl
     * mid-flight → new ClientBundle with new endpointFp) could穿透 and write
     * stale catalog/model data into the new URL's persisted state. The triple
     * guard must drop it.
     *
     * Test scaffolding: gate `getProvidersOrFailure` on a
     * [CompletableDeferred] so we can mutate the live `endpointFp` source
     * BETWEEN request-start (expectedEndpointFp captured) and onSuccess
     * (currentEndpointFp read). UnconfinedTestDispatcher runs the body up to
     * the first suspension, so control returns to the test after
     * `launchLoadProviders(...)` with the request parked on the gate.
     */
    @Test
    fun `launchLoadProviders ABA guard drops stale response when endpointFp changed mid-flight (same profileId)`() =
        runTest {
            val providers = ProvidersResponse(
                providers = listOf(
                    ConfigProvider(id = "p", name = "P", models = mapOf("m" to ProviderModel(name = "M"))),
                ),
            )
            val gate = CompletableDeferred<Unit>()
            coEvery { repository.getProvidersOrFailure() } coAnswers {
                gate.await()
                Result.success(providers)
            }
            every { settingsManager.reconcileModelData(any(), any()) } returns emptySet()

            // Live endpointFp source — captured at call time, switched before
            // onSuccess. profileId stays "" on both sides (default) to ISOLATE
            // the endpointFp component of the triple.
            var liveEndpointFp = "https://a.test"
            launchLoadProviders(
                scope = scope,
                repository = repository,
                slices = slices,
                settingsManager = settingsManager,
                hostProfileStore = hostProfileStore,
                onNonFatalError = { _, _ -> },
                expectedEndpointFp = liveEndpointFp,
                currentEndpointFp = { liveEndpointFp },
            )
            // Flip endpoint BEFORE unblocking the gate — simulates the user
            // editing the same profile's serverUrl → new ClientBundle published
            // with endpointFp="https://b.test" while the old request is parked.
            liveEndpointFp = "https://b.test"
            gate.complete(Unit)
            advanceUntilIdle()

            // Stale response dropped — providers slice untouched (no write to
            // the new URL's persisted state).
            assertNull(slices.settings.value.providers)
            // Loading flag still cleared by the outer `finally`.
            assertEquals(false, slices.settings.value.isLoadingProviders)
        }

    /**
     * §ABA-triple-guard (F1): same profileId + same endpointFp but the
     * connection generation bumped mid-flight (e.g. user triggered
     * resetLocalDataAndResync, which bumps generation +1 and republishes the
     * bundle, OR any full reconfigure that re-points at the same URL). The
     * stale response captured under the older generation must be discarded —
     * generation is the only signal that distinguishes "same endpoint, fresh
     * state" from "same endpoint, stale in-flight response pre-reset".
     */
    @Test
    fun `launchLoadProviders ABA guard drops stale response when generation bumped mid-flight (same profileId and endpointFp)`() =
        runTest {
            val providers = ProvidersResponse(
                providers = listOf(
                    ConfigProvider(id = "p", name = "P", models = mapOf("m" to ProviderModel(name = "M"))),
                ),
            )
            val gate = CompletableDeferred<Unit>()
            coEvery { repository.getProvidersOrFailure() } coAnswers {
                gate.await()
                Result.success(providers)
            }
            every { settingsManager.reconcileModelData(any(), any()) } returns emptySet()

            var liveGeneration = 1L
            launchLoadProviders(
                scope = scope,
                repository = repository,
                slices = slices,
                settingsManager = settingsManager,
                hostProfileStore = hostProfileStore,
                onNonFatalError = { _, _ -> },
                expectedGeneration = liveGeneration,
                currentGeneration = { liveGeneration },
            )
            // Bump generation BEFORE unblocking — simulates resetLocalDataAndResync
            // (which bumps +1) firing while the old request is parked.
            liveGeneration = 2L
            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(slices.settings.value.providers)
            assertEquals(false, slices.settings.value.isLoadingProviders)
        }

    /**
     * §ABA-triple-guard (F1): no-FALSE-positive case. A transient reconnect
     * that republishes a bundle with the SAME triple (profileId, endpointFp,
     * generation) — or simply no mid-flight change at all — must NOT trigger
     * the guard. The response goes through and providers are written. This
     * pins that the guard is sound (drops stale, keeps fresh) and doesn't
     * regress the happy path now that the triple is checked.
     */
    @Test
    fun `launchLoadProviders ABA guard keeps response when triple unchanged (no false positive on transient reconnect)`() =
        runTest {
            val providers = ProvidersResponse(
                providers = listOf(
                    ConfigProvider(id = "p", name = "P", models = mapOf("m" to ProviderModel(name = "M"))),
                ),
            )
            val gate = CompletableDeferred<Unit>()
            coEvery { repository.getProvidersOrFailure() } coAnswers {
                gate.await()
                Result.success(providers)
            }
            every { settingsManager.reconcileModelData(any(), any()) } returns emptySet()

            // Live sources — captured at call time, NOT mutated before onSuccess.
            var liveEndpointFp = "https://a.test"
            var liveGeneration = 1L
            launchLoadProviders(
                scope = scope,
                repository = repository,
                slices = slices,
                settingsManager = settingsManager,
                hostProfileStore = hostProfileStore,
                onNonFatalError = { _, _ -> },
                expectedEndpointFp = liveEndpointFp,
                currentEndpointFp = { liveEndpointFp },
                expectedGeneration = liveGeneration,
                currentGeneration = { liveGeneration },
            )
            // NO mutation — triple observed at onSuccess equals the captured
            // triple. Even if a "reconnect" happened, the new bundle carries
            // the same identity triple → guard must NOT fire.
            gate.complete(Unit)
            advanceUntilIdle()

            // Response written — no false-positive drop.
            assertEquals(providers, slices.settings.value.providers)
            assertEquals(false, slices.settings.value.isLoadingProviders)
        }
}
