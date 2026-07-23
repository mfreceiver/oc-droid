package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.repository.http.InMemoryTofuPinStore
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * # ι-A — ServerCompatProfile capability read-model (derived queries)
 *
 * **ROLE: capability-model pin.** This file pins the truth table of the
 * ι-A derived capability queries ([ServerCompatProfile.supportsWatermarkResync]
 * / [ServerCompatProfile.supportsTokenStreamResync] /
 * [ServerCompatProfile.usesSlimStatusFanOut]) over the
 * (slimConnection × slimapiTokenStreamEnabled) input space, plus the
 * [ServerCompatProfile.setSlimConnection] write contract that
 * [OpenCodeRepository.configure] uses to populate [ServerCompatProfile.slimConnection].
 *
 * ## Why this exists
 *
 * L4+ (coordinator / service / UI) needs a *semantic* capability query to
 * replace the bare `repository.isSlimMode` reads scattered across the
 * stack. The derived queries encode the actual capability semantics
 * (watermark resync needs slim mode; token-stream resync needs slim mode
 * *and* the sidecar advertising tokenStream; status fan-out is slim-only).
 * This file turns RED iff the truth table drifts — e.g. someone makes
 * `supportsTokenStreamResync` forget the `slimConnection` half, or makes
 * `usesSlimStatusFanOut` consult a probe field it shouldn't.
 *
 * ## What this file does NOT do
 *
 * It does NOT pin the [OpenCodeRepository.configure] call site (that's the
 * freeze's job in [T3RepositoryExtractFreezeTest]); it pins the
 * capability-model semantics + the setter contract via direct invocation.
 */
class ServerCompatProfileCapabilitiesTest {

    // ────────────────────────────────────────────────────────────────────────
    // §1 — slimConnection default + setter contract
    // ────────────────────────────────────────────────────────────────────────

    /**
     * §1a: [ServerCompatProfile.slimConnection] defaults to false (= legacy)
     * so every derived query fails-closed to legacy behaviour before any
     * configure() runs. A regression here would let a freshly-injected
     * profile (e.g. in a unit test that never calls configure) claim slim
     * capabilities it doesn't have.
     */
    @Test
    fun `slimConnection defaults to false - legacy fail-closed before configure`() {
        val profile = ServerCompatProfile()
        assertFalse(
            "slimConnection must default to false (=legacy) so derived queries " +
                "fail-closed before configure()",
            profile.slimConnection,
        )
    }

    /**
     * §1b: [ServerCompatProfile.setSlimConnection] is the single managed
     * write point for the connection-mode bit (ι-A I8 extension). It must
     * flip [ServerCompatProfile.slimConnection] to the supplied value,
     * supporting both directions (legacy→slim and slim→legacy) since a
     * host switch can go either way.
     */
    @Test
    fun `setSlimConnection flips the mode bit in both directions`() {
        val profile = ServerCompatProfile()

        // legacy → slim (the configure(slim=true) path)
        profile.setSlimConnection(true)
        assertTrue("setSlimConnection(true) must set slimConnection=true", profile.slimConnection)

        // slim → legacy (host switch back to a non-slim profile)
        profile.setSlimConnection(false)
        assertFalse("setSlimConnection(false) must set slimConnection=false", profile.slimConnection)
    }

    // ────────────────────────────────────────────────────────────────────────
    // §2 — derived-query truth table over (slimConnection × tokenStream)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * §2a: [ServerCompatProfile.supportsWatermarkResync] truth table.
     * Watermark resync is a slim-connection-only concept; legacy has no
     * bookmark/watermark path (it goes through bulk /session/status). The
     * query MUST depend solely on [ServerCompatProfile.slimConnection]
     * (NOT on probe state — slim mode is authoritative at configure time,
     * even before the first /slimapi/health succeeds).
     */
    @Test
    fun `supportsWatermarkResync truth table - slim-only, probe-independent`() {
        val profile = ServerCompatProfile()

        // legacy: false regardless of probe state
        profile.setSlimConnection(false)
        assertFalse("legacy → supportsWatermarkResync=false", profile.supportsWatermarkResync)

        // slim: true even before any health probe (slimapi* still null/default)
        profile.setSlimConnection(true)
        assertTrue(
            "slim (unprobed) → supportsWatermarkResync=true (mode authoritative)",
            profile.supportsWatermarkResync,
        )

        // slim + probed healthy (tokenStream irrelevant to watermark): still true
        profile.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = false,
                serverApiVersion = 1,
                acceptedClientVersions = 1 to 1,
                features = SlimapiFeatures(tokenStream = true),
            )
        )
        assertTrue(
            "slim + healthy probe → supportsWatermarkResync=true",
            profile.supportsWatermarkResync,
        )
    }

    /**
     * §2b: [ServerCompatProfile.supportsTokenStreamResync] truth table.
     * Token-stream resync needs BOTH (a) slim connection AND (b) the
     * sidecar advertising `features.tokenStream == true`. A regression
     * that drops either half (e.g. only checks slimapiTokenStreamEnabled)
     * would let a legacy connection claim token-stream capability, or let
     * a slim connection without sidecar support claim it.
     */
    @Test
    fun `supportsTokenStreamResync truth table - needs slim AND tokenStream`() {
        // exhaustive over the 2x2 input space
        // (slimConnection, slimapiTokenStreamEnabled) → expected
        data class Case(
            val slimConn: Boolean,
            val tokenStream: Boolean,
            val expected: Boolean,
            val label: String,
        )
        val cases = listOf(
            Case(false, false, false, "legacy × no-tokenStream → false"),
            // legacy but tokenStream somehow set (shouldn't happen but pin the AND):
            // legacy MUST short-circuit to false regardless of the probe field
            Case(false, true, false, "legacy × tokenStream → false (slim short-circuit)"),
            // slim but sidecar didn't advertise tokenStream → false
            Case(true, false, false, "slim × no-tokenStream → false"),
            // slim + sidecar advertised tokenStream → true (the only true cell)
            Case(true, true, true, "slim × tokenStream → true"),
        )

        for (c in cases) {
            val profile = ServerCompatProfile()
            // populate tokenStream via the real write path (updateSlimapi) when
            // the case wants it on; otherwise leave the probe field at its
            // default false (no health call).
            if (c.tokenStream) {
                profile.updateSlimapi(
                    SlimapiHealthPayload(
                        sidecarOk = true,
                        schemaDegraded = false,
                        serverApiVersion = 1,
                        acceptedClientVersions = 1 to 1,
                        features = SlimapiFeatures(tokenStream = true),
                    )
                )
            }
            profile.setSlimConnection(c.slimConn)

            // sanity: the probe field actually reflects what we set
            assertEquals(
                "test setup sanity: slimapiTokenStreamEnabled for '${c.label}'",
                c.tokenStream,
                profile.slimapiTokenStreamEnabled,
            )

            assertEquals(c.label, c.expected, profile.supportsTokenStreamResync)
        }
    }

    /**
     * §2c: [ServerCompatProfile.usesSlimStatusFanOut] truth table.
     * StatusAggregator's slim fan-out vs legacy bulk choice depends solely
     * on the connection mode (slim → per-session fan-out; legacy → bulk
     * /session/status). MUST NOT consult any probe field (the choice is
     * available immediately at configure time, before health).
     */
    @Test
    fun `usesSlimStatusFanOut truth table - slim-only, probe-independent`() {
        val profile = ServerCompatProfile()

        profile.setSlimConnection(false)
        assertFalse("legacy → usesSlimStatusFanOut=false (bulk path)", profile.usesSlimStatusFanOut)

        profile.setSlimConnection(true)
        assertTrue(
            "slim (unprobed) → usesSlimStatusFanOut=true (mode authoritative)",
            profile.usesSlimStatusFanOut,
        )

        // probe state must not change it
        profile.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = true,
                serverApiVersion = 1,
                acceptedClientVersions = 1 to 1,
                features = SlimapiFeatures(tokenStream = false),
            )
        )
        assertTrue(
            "slim + degraded probe → usesSlimStatusFanOut still true (probe-independent)",
            profile.usesSlimStatusFanOut,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // §3 — derived queries are pure (read-only, no field writes)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * §3: the derived queries MUST be pure reads — reading them must not
     * mutate any ServerCompatProfile field. (A regression that, say,
     * accidentally caches a derived value into a backing field would create
     * a hidden write point outside the I8-managed setter, re-opening the
     * "scattered mode-source" problem ι-A exists to fix.)
     *
     * Asserted by snapshotting all writable fields, reading every derived
     * query multiple times under different probe state, and asserting the
     * snapshot is unchanged.
     */
    @Test
    fun `derived queries are pure reads - no field mutation`() {
        val profile = ServerCompatProfile()
        profile.setSlimConnection(true)
        profile.updateSlimapi(
            SlimapiHealthPayload(
                sidecarOk = true,
                schemaDegraded = false,
                serverApiVersion = 2,
                acceptedClientVersions = 1 to 3,
                features = SlimapiFeatures(tokenStream = true),
            )
        )
        profile.update("1.17.13")

        fun snapshot() = listOf(
            profile.slimConnection,
            profile.slimapiTokenStreamEnabled,
            profile.slimapiServerApiVersion,
            profile.slimapiAcceptedMin,
            profile.slimapiAcceptedMax,
            profile.slimapiSidecarOk,
            profile.slimapiSchemaDegraded,
            profile.version,
            profile.major,
            profile.minor,
            profile.patch,
        )

        val before = snapshot()

        // exercise every derived query (multiple reads)
        repeat(3) {
            profile.supportsWatermarkResync
            profile.supportsTokenStreamResync
            profile.usesSlimStatusFanOut
        }

        val after = snapshot()
        assertEquals(
            "derived-query reads must not mutate any writable field",
            before,
            after,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // §4 — OCR.configure integration contract (mode bit mirrors hostConfig.slim)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * §4: [OpenCodeRepository.configure] is the managed writer of
     * [ServerCompatProfile.slimConnection] (ι-A I8 extension). The mode bit
     * MUST mirror the `slim` parameter passed to configure() — i.e. what
     * becomes `hostConfig.slim`. Asserted via the real OCR.configure() path
     * (the same @Synchronized monitor the production code uses), in both
     * directions (slim=true and slim=false), to pin the wiring.
     *
     * Mock style mirrors [T3RepositoryExtractFreezeTest] §2 (mockk(relaxed=true)
     * for tracker/logger) so we don't pull in the production SettingsManager
     * dependency those classes otherwise need. The profile is injected via
     * the 4-arg production ctor shape (frozen in §2b) so the instance we
     * hold is the SAME one OCR writes to.
     */
    @Test
    fun `configure writes slimConnection mirroring the slim parameter`() {
        val profile = ServerCompatProfile()
        val tracker: TrafficTracker = mockk(relaxed = true)
        val logger: TrafficLogger = mockk(relaxed = true)
        val repo = OpenCodeRepository(
            tracker,
            logger,
            InMemoryTofuPinStore(),
            profile,
        )

        // legacy configure
        repo.configure(baseUrl = "http://127.0.0.1:4096", slim = false)
        assertFalse(
            "configure(slim=false) must set slimConnection=false",
            profile.slimConnection,
        )
        assertFalse(
            "configure(slim=false) → legacy derived queries all false",
            profile.supportsWatermarkResync,
        )
        assertFalse(
            "configure(slim=false) → usesSlimStatusFanOut=false",
            profile.usesSlimStatusFanOut,
        )

        // host switch to slim
        repo.configure(baseUrl = "http://127.0.0.1:4097", slim = true)
        assertTrue(
            "configure(slim=true) must set slimConnection=true",
            profile.slimConnection,
        )
        assertTrue(
            "configure(slim=true) → supportsWatermarkResync=true",
            profile.supportsWatermarkResync,
        )
        assertTrue(
            "configure(slim=true) → usesSlimStatusFanOut=true",
            profile.usesSlimStatusFanOut,
        )
        assertFalse(
            "configure(slim=true) but no health probe yet → supportsTokenStreamResync=false",
            profile.supportsTokenStreamResync,
        )

        // host switch back to legacy (mode bit must flip back)
        repo.configure(baseUrl = "http://127.0.0.1:4098", slim = false)
        assertFalse(
            "host switch back to legacy must flip slimConnection=false",
            profile.slimConnection,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // §5 — configure failure path: slimConnection must NOT flip (ι-A blocker)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * §5a (ι-A blocker, rev-bgpt gate): when [OpenCodeRepository.configure]
     * throws partway through (after hostConfig.configure but during
     * `rebuildClients()` / `completeSlimReconfigure()`), [slimConnection]
     * MUST stay at its pre-configure value. The capability mode is published
     * **only** after the whole ssl/host/client/readiness transaction succeeds —
     * mirroring `completeSlimReconfigure`'s "readiness published only on full
     * success" discipline. Otherwise the capability read-model would advertise
     * a mode that was never actually wired live, and L4+ would route into a
     * non-existent slim/legacy stack.
     *
     * **Real failure injection** (not a source-order assertion): a malformed
     * `baseUrl` (invalid host) is accepted by `hostConfig.configure` (which
     * stores it verbatim) but rejected by `Retrofit.baseUrl()` inside
     * `rebuildClients()` → the throw lands exactly in the window the fix
     * concerns (after the OLD setSlimConnection site, before the NEW one).
     *
     * This test turns RED iff `setSlimConnection` is ever moved back to before
     * `rebuildClients()` / `completeSlimReconfigure()` in configure's body —
     * in that case the slim→new flip would land before the throw and
     * `slimConnection` would be wrong (=true) after the failed configure.
     *
     * Direction under test: legacy(false) established, then a failing
     * configure(slim=true) must leave slimConnection=false.
     */
    @Test
    fun `configure failure leaves slimConnection unchanged - legacy preserved when slim configure throws`() {
        val profile = ServerCompatProfile()
        val tracker: TrafficTracker = mockk(relaxed = true)
        val logger: TrafficLogger = mockk(relaxed = true)
        val repo = OpenCodeRepository(
            tracker,
            logger,
            InMemoryTofuPinStore(),
            profile,
        )

        // establish legacy mode (full success)
        repo.configure(baseUrl = "http://127.0.0.1:4096", slim = false)
        assertFalse("precondition: legacy configure sets slimConnection=false", profile.slimConnection)

        // now attempt a slim configure with a host that hostConfig.configure
        // accepts verbatim but rebuildClients()->buildRetrofit->Retrofit rejects
        // (invalid host with a space -> HttpUrl parse fails -> NPE in Retrofit).
        var threw = false
        try {
            repo.configure(baseUrl = MALFORMED_HOST_URL, slim = true)
        } catch (expected: Throwable) {
            threw = true
        }
        assertTrue("configure with malformed host URL must throw", threw)

        // BLOCKER assertion: the mode bit must NOT have flipped to the never-
        // wired-live slim=true. It reflects "the most recent mode that went
        // fully live" (=legacy), NOT the in-flight attempt.
        assertFalse(
            "configure(slim=true) threw inside rebuildClients -> slimConnection " +
                "must stay false (mode published only on full success)",
            profile.slimConnection,
        )
        assertFalse(
            "derived query must also reflect preserved legacy mode",
            profile.supportsWatermarkResync,
        )
    }

    /**
     * §5b: the mirror direction of §5a — a slim(true) mode is established, then
     * a failing configure(slim=false) must leave slimConnection=true. Pins both
     * directions of a host switch (a switch can fail in either direction), and
     * documents the reconfigure-mid-flight read semantic: while the new stack
     * is being wired (and throws), L4+ lock-free reads of `slimConnection`
     * observe the still-operative OLD mode.
     */
    @Test
    fun `configure failure leaves slimConnection unchanged - slim preserved when legacy configure throws`() {
        val profile = ServerCompatProfile()
        val tracker: TrafficTracker = mockk(relaxed = true)
        val logger: TrafficLogger = mockk(relaxed = true)
        val repo = OpenCodeRepository(
            tracker,
            logger,
            InMemoryTofuPinStore(),
            profile,
        )

        // establish slim mode (full success)
        repo.configure(baseUrl = "http://127.0.0.1:4097", slim = true)
        assertTrue("precondition: slim configure sets slimConnection=true", profile.slimConnection)

        // failing configure attempting to switch back to legacy with a bad host
        var threw = false
        try {
            repo.configure(baseUrl = MALFORMED_HOST_URL, slim = false)
        } catch (expected: Throwable) {
            threw = true
        }
        assertTrue("configure with malformed host URL must throw", threw)

        assertTrue(
            "configure(slim=false) threw inside rebuildClients -> slimConnection " +
                "must stay true (the still-live old mode)",
            profile.slimConnection,
        )
        assertTrue(
            "derived query must reflect the preserved (still-live) slim mode",
            profile.supportsWatermarkResync,
        )
    }

    private companion object {
        /**
         * A baseUrl that [HostConfig.configure] stores verbatim (no validation)
         * but [OpenCodeRepository.rebuildClients] -> buildRetrofit ->
         * Retrofit.baseUrl() rejects: the host contains a space, which OkHttp's
         * `HttpUrl` parser flags as an invalid host -> `HttpUrl.get` returns
         * null -> Retrofit `checkNotNull` throws. This lands the throw inside
         * `rebuildClients()` — after the OLD `setSlimConnection` call site and
         * before the NEW one — so the failure-path assertion distinguishes the
         * fix from the bug.
         */
        const val MALFORMED_HOST_URL = "http://invalid host"
    }
}
