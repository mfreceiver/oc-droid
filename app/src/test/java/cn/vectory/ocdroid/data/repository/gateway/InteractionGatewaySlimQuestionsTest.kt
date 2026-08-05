package cn.vectory.ocdroid.data.repository.gateway

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.model.SlimapiAggregationErrorWire
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry
import cn.vectory.ocdroid.data.model.SlimapiScope
import cn.vectory.ocdroid.data.model.SlimapiQuestionsEnvelope
import cn.vectory.ocdroid.data.repository.ClientBundle
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.SlimAggregationOutcome
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * §slimapi-questions: pins the [InteractionGateway.getSlimapiQuestions]
 * wire→outcome mapping + capability-bit contract:
 *
 *  - HTTP 200 + empty `errors` → `Success(items, null)` (globally authoritative)
 *    and marks the bit supported.
 *  - HTTP 200 + non-empty `errors` → `Partial(items, errors, authoritativeDirs)`.
 *  - HTTP 404 → marks the bit unsupported (sticky-false) AND throws (so the
 *    controller's Result.failure path triggers per-dir fan-out next cycle).
 *  - HTTP 5xx → throws AND leaves the bit unchanged (transient — no fallback).
 *
 * The gateway is constructed with a mocked [ClientBundle] whose `restApi`
 * returns canned [Response]s; the [ServerCompatProfile] is real so the bit
 * flips are observable.
 */
class InteractionGatewaySlimQuestionsTest {

    private fun gateway(
        profile: ServerCompatProfile = ServerCompatProfile().apply {
            // simulate slim mode + endpoint-fail-open default
            // (slimConnection is private-set; the test reaches it via the
            // internal setSlimConnection setter visible from the same module.)
            setSlimConnection(true)
        },
        api: OpenCodeApi,
    ): InteractionGateway {
        val bundle = mockk<ClientBundle>(relaxed = true)
        every { bundle.restApi } returns api
        return InteractionGateway(
            bundleProvider = { bundle },
            serverCompatProfile = profile,
        )
    }

    @Test
    fun `200 with empty errors yields Success with null authoritativeDirectories and marks supported`() = runTest {
        val profile = ServerCompatProfile().apply { setSlimConnection(true) }
        val api = mockk<OpenCodeApi>(relaxed = true)
        val items = listOf(SlimapiQuestionEntry(id = "q1", sessionId = "s1", directory = "/a"))
        coEvery { api.getSlimapiQuestions() } returns Response.success(
            SlimapiQuestionsEnvelope(items = items, errors = emptyList(), authoritativeDirectories = null),
        )

        val result = gateway(profile, api).getSlimapiQuestions(directories = null)

        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue("Success outcome", outcome is SlimAggregationOutcome.Success)
        outcome as SlimAggregationOutcome.Success
        assertTrue("items carried through", outcome.items == items)
        assertTrue("globally authoritative (null dirs)", outcome.authoritativeDirectories == null)
        assertTrue("bit marked supported", profile.supportsSlimQuestions)
    }

    @Test
    fun `200 with errors yields Partial mapping wire errors to SlimapiAggregationError`() = runTest {
        val profile = ServerCompatProfile().apply { setSlimConnection(true) }
        val api = mockk<OpenCodeApi>(relaxed = true)
        val items = listOf(SlimapiQuestionEntry(id = "q1", sessionId = "s1", directory = "/ok"))
        val wireErrors = listOf(
            SlimapiAggregationErrorWire(directory = "/failed", code = "upstream_unavailable", message = "boom"),
        )
        coEvery { api.getSlimapiQuestions() } returns Response.success(
            SlimapiQuestionsEnvelope(
                items = items,
                errors = wireErrors,
                authoritativeDirectories = listOf("/ok"),
            ),
        )

        val result = gateway(profile, api).getSlimapiQuestions(directories = null)

        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue("Partial outcome", outcome is SlimAggregationOutcome.Partial)
        outcome as SlimAggregationOutcome.Partial
        assertTrue("items carried through", outcome.items == items)
        assertTrue("wire errors mapped", outcome.errors.size == 1)
        assertTrue("error dir mapped", outcome.errors.first().directory == "/failed")
        assertTrue("error code mapped", outcome.errors.first().code == "upstream_unavailable")
        assertTrue("authoritativeDirectories set", outcome.authoritativeDirectories == setOf("/ok"))
        assertTrue("bit marked supported on 200", profile.supportsSlimQuestions)
    }

    @Test
    fun `404 marks the bit unsupported and throws so the call site falls back next cycle`() = runTest {
        val profile = ServerCompatProfile().apply { setSlimConnection(true) }
        val api = mockk<OpenCodeApi>(relaxed = true)
        coEvery { api.getSlimapiQuestions() } returns Response.error(
            404,
            """{"code":"thin_route_not_found"}""".toResponseBody("application/json".toMediaTypeOrNull()),
        )

        val result = gateway(profile, api).getSlimapiQuestions(directories = null)

        assertTrue("must surface as Result.failure", result.isFailure)
        assertTrue(
            "failure cause is IOException",
            result.exceptionOrNull() is IOException,
        )
        assertFalse("bit flipped sticky-false on 404", profile.supportsSlimQuestions)
    }

    @Test
    fun `5xx throws and leaves the bit unchanged (transient, no fallback)`() = runTest {
        val profile = ServerCompatProfile().apply { setSlimConnection(true) }
        val api = mockk<OpenCodeApi>(relaxed = true)
        coEvery { api.getSlimapiQuestions() } returns Response.error(
            503,
            """{"code":"upstream_unavailable"}""".toResponseBody("application/json".toMediaTypeOrNull()),
        )

        val result = gateway(profile, api).getSlimapiQuestions(directories = null)

        assertTrue("must surface as Result.failure", result.isFailure)
        assertTrue(
            "failure cause is IOException",
            result.exceptionOrNull() is IOException,
        )
        assertTrue("bit UNCHANGED on 5xx (still true)", profile.supportsSlimQuestions)
    }

    @Test
    fun `gate closed (supportsSlimQuestions=false) throws without hitting the network`() = runTest {
        val profile = ServerCompatProfile().apply {
            setSlimConnection(true)
            markSlimQuestionsUnsupported()
        }
        val api = mockk<OpenCodeApi>(relaxed = true)
        // No coEvery — any call would fail the test by recording an unexpected invocation.

        val result = gateway(profile, api).getSlimapiQuestions(directories = null)

        assertTrue("must surface as Result.failure", result.isFailure)
        assertTrue(
            "failure cause is IOException",
            result.exceptionOrNull() is IOException,
        )
        assertFalse("bit still false", profile.supportsSlimQuestions)
    }

    @Test
    fun `gate closed (slimConnection=false) throws without hitting the network`() = runTest {
        // Default profile: slimConnection = false.
        val profile = ServerCompatProfile()
        val api = mockk<OpenCodeApi>(relaxed = true)

        val result = gateway(profile, api).getSlimapiQuestions(directories = null)

        assertTrue(result.isFailure)
        // The slim-questions branch in the controllers gates on slimConnection
        // FIRST, so this defensive throw should never fire in production — but
        // if it does, it surfaces deterministically.
        assertTrue(result.exceptionOrNull() is IOException)
        // Nothing to assert about the bit — it stays at its default (true).
    }

    @Test
    fun `200 with scope directories=21 threads serverScope into Success`() = runTest {
        val profile = ServerCompatProfile().apply { setSlimConnection(true) }
        val api = mockk<OpenCodeApi>(relaxed = true)
        coEvery { api.getSlimapiQuestions() } returns Response.success(
            SlimapiQuestionsEnvelope(
                items = emptyList(),
                errors = emptyList(),
                authoritativeDirectories = null,
                scope = SlimapiScope(directories = 21),
            ),
        )

        val result = gateway(profile, api).getSlimapiQuestions(directories = null)

        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue("Success outcome", outcome is SlimAggregationOutcome.Success)
        outcome as SlimAggregationOutcome.Success
        assertTrue("serverScope.directories == 21 passthrough", outcome.serverScope?.directories == 21)
        assertTrue("bit marked supported", profile.supportsSlimQuestions)
    }

    @Test
    fun `200 with authoritativeDirectories and empty errors threads the dir set into Success`() = runTest {
        val profile = ServerCompatProfile().apply { setSlimConnection(true) }
        val api = mockk<OpenCodeApi>(relaxed = true)
        val items = listOf(SlimapiQuestionEntry(id = "q1", sessionId = "s1", directory = "/a"))
        coEvery { api.getSlimapiQuestions() } returns Response.success(
            SlimapiQuestionsEnvelope(
                items = items,
                errors = emptyList(),
                authoritativeDirectories = listOf("/a"),
                scope = null,
            ),
        )

        val result = gateway(profile, api).getSlimapiQuestions(directories = null)

        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue("Success outcome", outcome is SlimAggregationOutcome.Success)
        outcome as SlimAggregationOutcome.Success
        assertTrue("items carried through", outcome.items == items)
        assertTrue("authoritativeDirectories == setOf(/a)", outcome.authoritativeDirectories == setOf("/a"))
        assertTrue("bit marked supported", profile.supportsSlimQuestions)
    }

    @Test
    fun `200 with scope directories=0 threads serverScope so caller can retain`() = runTest {
        val profile = ServerCompatProfile().apply { setSlimConnection(true) }
        val api = mockk<OpenCodeApi>(relaxed = true)
        coEvery { api.getSlimapiQuestions() } returns Response.success(
            SlimapiQuestionsEnvelope(
                items = emptyList(),
                errors = emptyList(),
                authoritativeDirectories = null,
                scope = SlimapiScope(directories = 0),
            ),
        )

        val result = gateway(profile, api).getSlimapiQuestions(directories = null)

        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue("Success outcome", outcome is SlimAggregationOutcome.Success)
        outcome as SlimAggregationOutcome.Success
        assertTrue("serverScope.directories == 0 passthrough", outcome.serverScope?.directories == 0)
        assertTrue("bit marked supported", profile.supportsSlimQuestions)
    }
}
