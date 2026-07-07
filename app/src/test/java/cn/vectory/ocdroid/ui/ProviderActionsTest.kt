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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
        every { hostProfileStore.currentProfile() } returns HostProfile.defaultDirect(serverUrl = "https://h.test").copy(serverGroupFp = "fp-h-test")
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
        coEvery { repository.getProviders() } returns Result.success(providers)
        // Persisted disabled set: one still-available + one removed-server-side.
        // R-20 Phase 5: keyed by serverGroupFp ("fp-h-test" — set in setUp).
        every { settingsManager.getDisabledModels("fp-h-test") } returns
            setOf("openai/gpt-4", "ghost/model")

        launchLoadProviders(
            scope = scope,
            repository = repository,
            slices = slices,
            settingsManager = settingsManager,
            hostProfileStore = hostProfileStore,
            onNonFatalError = { _, _ -> },
        )
        advanceUntilIdle()

        // Disabled set trimmed: only entries still on the server survive.
        assertEquals(setOf("openai/gpt-4"), slices.settings.value.disabledModels)
        // Availability set persisted.
        verify {
            settingsManager.setModelAvailability(
                "fp-h-test",
                setOf("openai/gpt-4", "openai/gpt-3.5", "anthropic/claude"),
            )
        }
        verify { settingsManager.setDisabledModels("fp-h-test", setOf("openai/gpt-4")) }
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
        coEvery { repository.getProviders() } returns Result.success(providers)
        every { settingsManager.getDisabledModels(any()) } returns emptySet()

        launchLoadProviders(scope, repository, slices, settingsManager, hostProfileStore) { _, _ -> }
        advanceUntilIdle()

        assertTrue(slices.settings.value.disabledModels.isEmpty())
    }

    @Test
    fun `launchLoadProviders failure routes to onNonFatalError`() = runTest {
        val error = IllegalStateException("500")
        coEvery { repository.getProviders() } returns Result.failure(error)
        var capturedMsg: String? = null
        var capturedErr: Throwable? = null

        launchLoadProviders(scope, repository, slices, settingsManager, hostProfileStore) { msg, err ->
            capturedMsg = msg
            capturedErr = err
        }
        advanceUntilIdle()

        assertEquals("Failed to load providers", capturedMsg)
        assertEquals(error, capturedErr)
        // Slice untouched.
        assertNull(slices.settings.value.providers)
    }
}
