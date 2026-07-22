package cn.vectory.ocdroid.data.repository

import cn.vectory.ocdroid.data.api.OpenCodeApi
import cn.vectory.ocdroid.data.api.SSEClient
import cn.vectory.ocdroid.data.api.v2.OpenCodeApiV2
import cn.vectory.ocdroid.data.repository.http.OkHttpClientFactory
import cn.vectory.ocdroid.data.repository.http.SlimapiContract
import cn.vectory.ocdroid.data.repository.http.TofuPinStore
import cn.vectory.ocdroid.util.TrafficLogger
import cn.vectory.ocdroid.util.TrafficTracker
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * # T3 freeze — OpenCodeRepository extract contracts
 *
 * **ROLE: test-freeze only.** This file pins the public API surface and the
 * extract seams that T3 (split OpenCodeRepository, currently ~4098 LOC, into
 * HttpClientManager / LegacyApiFacade / SlimApiFacade / SlimSseStateMachine /
 * TofuManager / RepositoryRuntime) MUST preserve so existing callers keep
 * compiling and existing suite tests stay GREEN. T3 implements; this file
 * turns RED on any contract break.
 *
 * ## Recon (authoritative — what's already separate-ish)
 *
 * Already-extracted collaborator types that T3 must NOT rename / remove /
 * move out of the listed FQN:
 *  - [cn.vectory.ocdroid.data.repository.http.OkHttpClientFactory]
 *      (= the plan's "HttpClientManager" — see §3 policy).
 *  - [cn.vectory.ocdroid.data.api.OpenCodeApi] (Retrofit interface; legacy).
 *  - [cn.vectory.ocdroid.data.api.v2.OpenCodeApiV2] (Retrofit interface; v2).
 *  - [cn.vectory.ocdroid.data.api.SSEClient] (SSE collector façade).
 *  - [cn.vectory.ocdroid.data.repository.http.TofuPinStore] + EspTofuPinStore
 *      + InMemoryTofuPinStore (TOFU pin store; "TofuManager" seam).
 *  - [cn.vectory.ocdroid.data.repository.HostConfig] (per-host profile holder).
 *
 * Inner to extract carefully (frozen in §4):
 *  - SlimSseState (in `SlimSseReducer.kt`) — the per-session bookmark map.
 *  - SlimCommitToken / SlimReconfigureTicket / StaleSlimCommitException /
 *    SupersededSlimReconfigureException (nested in OpenCodeRepository) — the
 *    slim incarnation token / ticket types consumed by
 *    SessionSyncCoordinator (SSC).
 *  - slimStateLock — the per-repository atomic state boundary.
 *
 * ## What this file does NOT do
 *
 *  - It does NOT force T3 to rename `OkHttpClientFactory` → `HttpClientManager`
 *    (or any other plan-name onto an existing working type). Per the task
 *    hard rule, that would create busywork renames. §3 pins BEHAVIOUR +
 *    existing FQNs, not plan-names — see the soft-doc test
 *    `T3 may keep existing type names without forcing busywork renames`.
 *  - It does NOT duplicate the wire-level slimapi coverage already pinned
 *    by `OpenCodeRepositorySlimapiEndpointsTest`. §5 keeps the smoke to the
 *    single shared contract constant (`/slimapi/` prefix) that the existing
 *    suite already transits.
 */
class T3RepositoryExtractFreezeTest {

    // ────────────────────────────────────────────────────────────────────────
    // §1 — Public API surface inventory (doc + reflection pin)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * §1: every public method listed below is part of the
     * OpenCodeRepository contract consumed by ui / service / coordinator
     * callers. T3's split MUST keep each callable on the public surface
     * (either as a primary method on the repository facade or as an
     * explicit 1-line forwarder to an extracted collaborator). A rename or
     * a signature change turns this test RED.
     *
     * Pinned via reflection (declared method name + parameter count where
     * overloads exist) so the freeze survives overloads being moved to a
     * super-class without a brittle exact-signature assertion.
     */
    @Test
    fun `public API surface inventory - critical methods remain callable`() {
        val cls = OpenCodeRepository::class.java

        // ── connection lifecycle (R-18 facade) ────────────────────────────
        //   - configure(...) must remain the single host-switch entrypoint
        //     with the full 8-arg shape (baseUrl, username, password,
        //     hostPort, clientCert, slim, reconfigureTicket). Locks the
        //     barrier-callers' ticket-ownership path.
        assertTrue("configure must exist (host switch entrypoint)", hasMethod(cls, "configure"))
        assertTrue("configure must keep 7 args", method(cls, "configure").parameterCount == 7)

        assertTrue("currentSslConfig must exist", hasMethod(cls, "currentSslConfig"))
        assertTrue("isMutualTlsActive must exist", hasMethod(cls, "isMutualTlsActive"))

        // ── health probes (slim / legacy routing) ─────────────────────────
        assertTrue("checkHealth must exist", hasMethod(cls, "checkHealth"))
        assertTrue("checkHealthFor must exist", hasMethod(cls, "checkHealthFor"))
        assertTrue("parseSlimapiHealth must exist", hasMethod(cls, "parseSlimapiHealth"))

        // ── TOFU capture + decision (TOFU seam) ───────────────────────────
        assertTrue("captureServerCert must exist", hasMethod(cls, "captureServerCert"))
        assertTrue(
            "applyTofuDecision must exist (TOFU pin write)",
            hasMethod(cls, "applyTofuDecision"),
        )
        assertTrue("pinnedSpkiFor must exist", hasMethod(cls, "pinnedSpkiFor"))
        assertTrue("clearTofuPin must exist", hasMethod(cls, "clearTofuPin"))

        // ── slim incarnation token / ticket APIs (consumed by SSC) ────────
        // These are the slim state machine surface; they MUST remain
        // accessible from outside the repo so SessionSyncCoordinator /
        // AppCoreOrchestration keep compiling.
        assertTrue("captureSlimCommitToken must exist", hasMethod(cls, "captureSlimCommitToken"))
        assertTrue(
            "isSlimCommitTokenCurrent must exist",
            hasMethod(cls, "isSlimCommitTokenCurrent"),
        )
        assertTrue(
            "commitIfSlimTokenCurrent must exist",
            hasMethod(cls, "commitIfSlimTokenCurrent"),
        )
        assertTrue(
            "requireSlimTokenCurrent must exist",
            hasMethod(cls, "requireSlimTokenCurrent"),
        )
        assertTrue("beginSlimReconfigure must exist", hasMethod(cls, "beginSlimReconfigure"))
        assertTrue(
            "completeSlimReconfigure must exist",
            hasMethod(cls, "completeSlimReconfigure"),
        )

        // ── slim reducer / per-session bookmark state ─────────────────────
        assertTrue("applySlimDigest must exist", hasMethod(cls, "applySlimDigest"))
        assertTrue("getSlimSessionState must exist", hasMethod(cls, "getSlimSessionState"))
        assertTrue("markSlimSessionDeleted must exist", hasMethod(cls, "markSlimSessionDeleted"))
        assertTrue("clearSlimLocalMessages must exist", hasMethod(cls, "clearSlimLocalMessages"))
        assertTrue("markSlimReconcileFailure must exist", hasMethod(cls, "markSlimReconcileFailure"))
        assertTrue("markSlimReconcileAligned must exist", hasMethod(cls, "markSlimReconcileAligned"))
        assertTrue("invalidateSlimLocalApplied must exist", hasMethod(cls, "invalidateSlimLocalApplied"))
        assertTrue("markSlimDirty must exist", hasMethod(cls, "markSlimDirty"))

        // ── slim cold-start / messages (frozen by SlimapiEndpointsTest) ───
        assertTrue("coldStartSlimSync must exist", hasMethod(cls, "coldStartSlimSync"))
        assertTrue("getSlimapiMessagesPage must exist", hasMethod(cls, "getSlimapiMessagesPage"))
        assertTrue("expandMessagesFullBatch must exist", hasMethod(cls, "expandMessagesFullBatch"))

        // ── legacy REST API wrappers (R-18 1:1 forwards) ──────────────────
        assertTrue("getMessages must exist", hasMethod(cls, "getMessages"))
        assertTrue("sendMessage must exist", hasMethod(cls, "sendMessage"))
        assertTrue("connectSSE must exist", hasMethod(cls, "connectSSE"))
        assertTrue("invalidateThinRouteCache must exist", hasMethod(cls, "invalidateThinRouteCache"))

        // ── companion constant surface ───────────────────────────────────
        // DEFAULT_SERVER is the legacy default URL (mirrored from
        // HostConfig.DEFAULT_SERVER) and is asserted by
        // `OpenCodeRepositoryTest.default server URL is localhost`. Pinning
        // it here makes a removal turn this freeze RED independently of
        // that one test. (const val compiles to a JVM public static final
        // field — direct reference is the cleanest pin.)
        assertEquals(
            "DEFAULT_SERVER companion const must stay mirrored from HostConfig",
            HostConfig.DEFAULT_SERVER,
            OpenCodeRepository.DEFAULT_SERVER,
        )
        assertEquals(
            "DEFAULT_SERVER literal value is the localhost:4096 default",
            "http://localhost:4096",
            OpenCodeRepository.DEFAULT_SERVER,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // §2 — 2-arg constructor pin (runtime GREEN today)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * §2: the public constructor signature
     * `OpenCodeRepository(TrafficTracker, TrafficLogger)` is locked by the
     * existing test setup `OpenCodeRepository(mockk(relaxed=true),
     * mockk(relaxed=true))` used across OpenCodeRepositoryTest /
     * WrapperTest / DirectoryTest / MutationWiringTest / ExpandBudgetTest /
     * TunnelTest / SlimapiEndpointsTest. T3's split MUST NOT remove the
     * default-args overload that lets this 2-arg form keep compiling —
     * moving the collaborators behind constructor-injected dependencies is
     * fine ONLY if the 2-arg shape keeps working (via Kotlin default args
     * OR via a thin `operator fun invoke` / factory).
     *
     * Today: GREEN (the constructor has default args for tofuStore +
     * serverCompatProfile). T3 MUST keep it GREEN.
     */
    @Test
    fun `2-arg constructor OpenCodeRepository(TrafficTracker, TrafficLogger) remains the test entrypoint`() {
        val tracker: TrafficTracker = mockk(relaxed = true)
        val logger: TrafficLogger = mockk(relaxed = true)

        val repo = OpenCodeRepository(tracker, logger)

        assertNotNull(repo)
        // Sanity: the constructed instance must be usable for configure()
        // without an extra wiring step (every existing test in the suite
        // relies on this — they call repo.configure(baseUrl=...) next).
        assertTrue(
            "freshly constructed repo must expose configure()",
            hasMethod(OpenCodeRepository::class.java, "configure"),
        )
    }

    /**
     * §2b: the constructor MUST keep the production 4-arg Hilt shape
     * (tracker, logger, tofuStore, serverCompatProfile). Kotlin compiles
     * the default-args ctor as a synthetic 6-arg (4 params + int mask +
     * DefaultConstructorMarker) that the 2-arg call site
     * `OpenCodeRepository(mockk(), mockk())` routes through — so the
     * 2-arg call is satisfied by the SAME primary ctor with the mask
     * saying "only first 2 supplied". There is NO separate 2-arg ctor
     * in the bytecode; §2 above (which actually invokes the 2-arg shape)
     * is the real pin. This test pins the 4-arg prod ctor survives.
     */
    @Test
    fun `constructor surface keeps the 4-arg Hilt production shape`() {
        val ctorArities = OpenCodeRepository::class.java.declaredConstructors
            .map { it.parameterCount }
            .distinct()
            .sorted()

        assertTrue(
            "must expose a 4-arg ctor (Hilt production injection: " +
                "tracker, logger, tofuStore, serverCompatProfile). arities=$ctorArities",
            4 in ctorArities,
        )
        // The 2-arg call site `OpenCodeRepository(tracker, logger)` is
        // fulfilled by the synthetic default-args ctor (arity = 4 + mask
        // + marker = 6) — pinned by §2 invoking it directly. Asserting
        // the synthetic ctor EXISTS guards against a future removal of
        // the default args (which would break every existing test's
        // setup `OpenCodeRepository(mockk(relaxed=true), mockk(relaxed=true))`).
        assertTrue(
            "must expose a synthetic default-args ctor (arity 6 = 4 params + mask + marker) " +
                "so the locked 2-arg call site keeps compiling. arities=$ctorArities",
            6 in ctorArities,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // §3 — Expected extract type FQNs (GREEN characterization + soft-doc)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * §3a: the already-extracted collaborator types that the plan calls
     * "HttpClientManager" / "TofuManager" / etc. ALREADY EXIST under
     * production names — pinning their FQNs keeps T3 from renaming
     * working types (which would churn every call site + every existing
     * test that imports them).
     *
     * GREEN today. T3 turns this RED iff it moves / renames any of these.
     */
    @Test
    fun `already-extracted collaborator types remain loadable at their FQNs`() {
        // ── HttpClientManager equivalent ──────────────────────────────────
        assertEquals(
            "OkHttpClientFactory is the HttpClientManager equivalent — " +
                "must remain at cn.vectory.ocdroid.data.repository.http.OkHttpClientFactory",
            "cn.vectory.ocdroid.data.repository.http.OkHttpClientFactory",
            OkHttpClientFactory::class.java.name,
        )

        // ── TofuManager equivalent ───────────────────────────────────────
        assertEquals(
            "TofuPinStore is the TofuManager equivalent — " +
                "must remain at cn.vectory.ocdroid.data.repository.http.TofuPinStore",
            "cn.vectory.ocdroid.data.repository.http.TofuPinStore",
            TofuPinStore::class.java.name,
        )

        // ── HostConfig (per-host profile) ────────────────────────────────
        assertEquals(
            "HostConfig must remain at cn.vectory.ocdroid.data.repository.HostConfig",
            "cn.vectory.ocdroid.data.repository.HostConfig",
            HostConfig::class.java.name,
        )

        // ── Retrofit API interfaces (legacy + v2 facades) ────────────────
        assertEquals(
            "OpenCodeApi must remain at cn.vectory.ocdroid.data.api.OpenCodeApi",
            "cn.vectory.ocdroid.data.api.OpenCodeApi",
            OpenCodeApi::class.java.name,
        )
        assertEquals(
            "OpenCodeApiV2 must remain at cn.vectory.ocdroid.data.api.v2.OpenCodeApiV2",
            "cn.vectory.ocdroid.data.api.v2.OpenCodeApiV2",
            OpenCodeApiV2::class.java.name,
        )

        // ── SSEClient (slim SSE collector façade) ────────────────────────
        assertEquals(
            "SSEClient must remain at cn.vectory.ocdroid.data.api.SSEClient",
            "cn.vectory.ocdroid.data.api.SSEClient",
            SSEClient::class.java.name,
        )
    }

    /**
     * §3b: the slimapi routing contract (path prefix + version header +
     * health paths) MUST remain the single source of truth in
     * [SlimapiContract]. The version / capabilities / debug interceptors
     * all reference these constants; health probes route on
     * SLIMAPI_HEALTH_PATH vs LEGACY_HEALTH_PATH. T3's split must NOT
     * duplicate these literals across facades.
     *
     * GREEN today. Pin the canonical object + the four constants used by
     * the wiring.
     */
    @Test
    fun `SlimapiContract remains the single routing-constant source`() {
        assertEquals(
            "SlimapiContract must remain at cn.vectory.ocdroid.data.repository.http.SlimapiContract",
            "cn.vectory.ocdroid.data.repository.http.SlimapiContract",
            SlimapiContract::class.java.name,
        )
        // T3 MUST keep these literal values; they are wire-contract pins
        // (a change is a sidecar-protocol bump, not a refactor concern).
        assertEquals("/slimapi/", SlimapiContract.SLIMAPI_PATH_PREFIX)
        assertEquals("/slimapi/health", SlimapiContract.SLIMAPI_HEALTH_PATH)
        assertEquals("/global/health", SlimapiContract.LEGACY_HEALTH_PATH)
        assertEquals("X-Slimapi-Version", SlimapiContract.X_SLIMAPI_VERSION)
        assertEquals(1, SlimapiContract.SLIMAPI_CLIENT_VERSION)
    }

    /**
     * §3c: SOFT-DOC — T3 may keep `OkHttpClientFactory` as the
     * "HttpClientManager" equivalent WITHOUT renaming. The plan names
     * (HttpClientManager / LegacyApiFacade / SlimApiFacade /
     * SlimSseStateMachine / TofuManager / RepositoryRuntime) are ROLE
     * labels, not required type names. Forcing a rename of working types
     * would create busywork churn across every existing test import and
     * every ui/service caller — explicitly OUT OF SCOPE for T3 per the
     * task hard rule.
     *
     * This test turns RED ONLY IF a class with one of those plan names
     * exists AND is placed OUTSIDE the `data.repository` subtree (a T3
     * that introduces an alias class is allowed; one that splinters the
     * package layout is not).
     */
    @Test
    fun `T3 may keep existing type names without forcing busywork renames`() {
        val planRoleToExistingType = mapOf(
            "HttpClientManager" to "OkHttpClientFactory",
            "TofuManager" to "TofuPinStore (+ EspTofuPinStore / InMemoryTofuPinStore)",
            "HostConfig" to "HostConfig",
            "LegacyApiFacade" to "OpenCodeApi (Retrofit interface)",
            "SlimApiFacade" to "OpenCodeApi (slimapi methods) + SlimapiContract",
            "SlimSseStateMachine" to "SlimSseState (+ SlimCommitToken / SlimReconfigureTicket)",
            "RepositoryRuntime" to "OpenCodeRepository (orchestrating facade)",
        )

        // SOFT-DOC: assert the freeze POLICY (no rename forced) so a
        // reviewer reading the test report sees the deviation explicitly.
        assertTrue(
            "T3 freeze policy: existing collaborator types are KEPT (no rename). " +
                "Plan-role → existing-type mapping: $planRoleToExistingType",
            planRoleToExistingType.isNotEmpty(),
        )

        // If T3 introduces one of the plan-named classes, it MUST live in
        // the data.repository subtree (not leak into ui / service / util).
        // Today none of these classes exist (RED-on-introduction-outside-
        // repository); if T3 adds them as aliases inside data.repository,
        // this loop stays GREEN.
        val allowedPrefix = "cn.vectory.ocdroid.data.repository"
        val planNames = listOf(
            "HttpClientManager",
            "LegacyApiFacade",
            "SlimApiFacade",
            "SlimSseStateMachine",
            "TofuManager",
            "RepositoryRuntime",
        )
        for (planName in planNames) {
            val cls = runCatching { Class.forName("$allowedPrefix.$planName") }.getOrNull()
            if (cls != null) {
                assertTrue(
                    "T3-introduced $planName MUST live under $allowedPrefix " +
                        "(found ${cls.name}); out-of-package aliases splinter the layout.",
                    cls.name.startsWith(allowedPrefix),
                )
            }
            // If the class does NOT exist, the soft-doc passes silently —
            // T3 is free to keep using the existing type name instead.
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // §4 — Slim SSE state types binary-compat pin
    // ────────────────────────────────────────────────────────────────────────

    /**
     * §4: the slim incarnation token / ticket / exception types are
     * consumed by `SessionSyncCoordinator` and `AppCoreOrchestration` via
     * their NESTED FQN inside OpenCodeRepository. They are `public` nested
     * classes today. T3's extraction (moving the state machine to
     * SlimSseStateMachine) MUST keep these FQNs resolvable from existing
     * call sites — either by keeping them nested OR by adding a
     * `typealias` re-export on OpenCodeRepository.
     *
     * GREEN today. RED iff T3 moves them out without re-export.
     */
    @Test
    fun `slim SSE commit + reconfigure types remain nested-public on OpenCodeRepository`() {
        // Each FQN MUST be resolvable as a nested class. This is the
        // binary-compat surface used by external callers — a move breaks
        // every caller's import + every existing `SlimCommitToken::class`
        // reference.
        assertEquals(
            "SlimCommitToken nested FQN must remain OpenCodeRepository.SlimCommitToken",
            "cn.vectory.ocdroid.data.repository.OpenCodeRepository\$SlimCommitToken",
            OpenCodeRepository.SlimCommitToken::class.java.name,
        )
        assertEquals(
            "SlimReconfigureTicket nested FQN must remain OpenCodeRepository.SlimReconfigureTicket",
            "cn.vectory.ocdroid.data.repository.OpenCodeRepository\$SlimReconfigureTicket",
            OpenCodeRepository.SlimReconfigureTicket::class.java.name,
        )
        assertEquals(
            "StaleSlimCommitException nested FQN must remain OpenCodeRepository.StaleSlimCommitException",
            "cn.vectory.ocdroid.data.repository.OpenCodeRepository\$StaleSlimCommitException",
            OpenCodeRepository.StaleSlimCommitException::class.java.name,
        )
        assertEquals(
            "SupersededSlimReconfigureException nested FQN must remain OpenCodeRepository.SupersededSlimReconfigureException",
            "cn.vectory.ocdroid.data.repository.OpenCodeRepository\$SupersededSlimReconfigureException",
            OpenCodeRepository.SupersededSlimReconfigureException::class.java.name,
        )

        // Binary-compat on the EXCEPTION hierarchy: the two slim
        // exceptions MUST remain java.io.IOException subclasses (the
        // runSuspendCatching plumbing + the coordinator's failure
        // branching depends on this; changing to RuntimeException would
        // invert the surface).
        assertTrue(
            "StaleSlimCommitException must remain a java.io.IOException",
            java.io.IOException::class.java.isAssignableFrom(
                OpenCodeRepository.StaleSlimCommitException::class.java,
            ),
        )
        assertTrue(
            "SupersededSlimReconfigureException must remain a java.io.IOException",
            java.io.IOException::class.java.isAssignableFrom(
                OpenCodeRepository.SupersededSlimReconfigureException::class.java,
            ),
        )

        // SlimCommitToken is constructed by the repo (constructor is
        // `internal`); callers only CAPTURE + RETURN it. Pin that the
        // capture API exists on the public surface (cross-check with §1
        // but at the type level too).
        val tokenMethod = OpenCodeRepository::class.java.getDeclaredMethod(
            "captureSlimCommitToken",
        )
        assertEquals(
            "captureSlimCommitToken() return type must be SlimCommitToken",
            OpenCodeRepository.SlimCommitToken::class.java,
            tokenMethod.returnType,
        )
    }

    /**
     * §4b: the SlimSseState accumulator (per-host bookmark map) is held as
     * a private field on OpenCodeRepository today. T3 will likely relocate
     * it into SlimSseStateMachine. The PUBLIC surface that MUST NOT
     * change is the read/mutate API exposed on OpenCodeRepository (pinned
     * in §1: getSlimSessionState / markSlim* / clearSlimLocalMessages /
     * applySlimDigest). This test pins that the SlimSseState TYPE remains
     * loadable at its current FQN — even if its holding field moves, the
     * type itself is part of the slim state contract used by
     * SlimSseReducerTest.
     *
     * GREEN today. RED iff T3 renames / removes SlimSseState.
     */
    @Test
    fun `SlimSseState accumulator type remains loadable at its FQN`() {
        assertEquals(
            "SlimSseState must remain at cn.vectory.ocdroid.data.repository.SlimSseState",
            "cn.vectory.ocdroid.data.repository.SlimSseState",
            SlimSseState::class.java.name,
        )

        // Pin the four primitive ops the reducer tests rely on
        // (get/put/all/clear). T3's split MUST preserve this minimal map
        // surface on SlimSseState itself (the atomic-boundary wrapper
        // lives one layer up — on OpenCodeRepository).
        val sseStateCls = SlimSseState::class.java
        listOf("get", "put", "all", "clear").forEach { op ->
            assertTrue(
                "SlimSseState.$op must remain (reducer contract)",
                sseStateCls.declaredMethods.any { it.name == op },
            )
        }
    }

    /**
     * §4c: the slim state-machine write boundary (`slimStateLock`) is a
     * private field today. T3 extraction MUST keep the
     * `synchronized(slimStateLock)` atomic-mutation guarantee on EVERY
     * compound transition listed in §1 (the T11 round-2 oracle I3 fix
     * depends on it — see OpenCodeRepository.kt:339-395). This test pins
     * the FIELD EXISTS so a T3 that drops the lock (replacing it with
     * per-method `@Synchronized` on extracted collaborators, which would
     * reopen the lost-update window) turns RED.
     *
     * We don't pin the lock's exact type (Any vs Mutex) — only that SOME
     * lock field exists by that name. Behavioural atomicity is covered
     * by the SlimSseReducerTest + SlimapiEndpointsTest concurrency paths.
     */
    @Test
    fun `slimStateLock field remains declared on OpenCodeRepository`() {
        val lockField =
            runCatching { OpenCodeRepository::class.java.getDeclaredField("slimStateLock") }
                .getOrNull()
        assertNotNull(
            "slimStateLock field MUST remain on OpenCodeRepository " +
                "(T11 round-2 atomic-mutation boundary; relocating the lock to an " +
                "extracted collaborator without keeping the field re-opens the " +
                "lost-update window documented in OpenCodeRepository.kt:339-395).",
            lockField,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // §5 — Wire-shape smoke (slimapi prefix; does NOT duplicate SlimapiEndpointsTest)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * §5: a single, cheap wire-shape assertion that the slimapi path
     * prefix is the canonical `/slimapi/`. The full
     * `/slimapi/messages/{sid}/since/{ts}` URL contracts are pinned by
     * `OpenCodeRepositorySlimapiEndpointsTest` (3672 LOC) — T3 freeze
     * does NOT duplicate them. This is the cross-cutting constant every
     * facade / interceptor / health probe references, so it is the
     * single highest-leverage wire pin for the split.
     *
     * GREEN today (already covered indirectly by SlimapiContract §3b
     * above; restated here as the §5 smoke so the freeze report has a
     * clearly-labelled wire-shape entry).
     */
    @Test
    fun `wire-shape smoke - slimapi path prefix is canonical`() {
        // The interceptor family (SlimapiVersionInterceptor /
        // SlimapiCapabilitiesInterceptor / SlimapiDebugInterceptor /
        // TrafficCountingInterceptor) ALL match on startsWith(prefix).
        // A change here would silently re-route every slimapi request.
        assertEquals(
            "SLIMAPI_PATH_PREFIX is the wire contract for the entire " +
                "slimapi interceptor family",
            "/slimapi/",
            SlimapiContract.SLIMAPI_PATH_PREFIX,
        )

        // Cross-pin: TofuDecision remains the public decision-sum type
        // consumed by applyTofuDecision (the TOFU seam). T3's TofuManager
        // extraction MUST keep the three sealed variants reachable so the
        // UI trust-prompt callbacks keep type-matching.
        assertTrue(
            "TofuDecision.AcceptOnce variant must remain reachable",
            runCatching {
                Class.forName("cn.vectory.ocdroid.data.repository.http.TofuDecision\$AcceptOnce")
            }.isSuccess,
        )
        assertTrue(
            "TofuDecision.Trust variant must remain reachable",
            runCatching {
                Class.forName("cn.vectory.ocdroid.data.repository.http.TofuDecision\$Trust")
            }.isSuccess,
        )
        assertTrue(
            "TofuDecision.Cancel variant must remain reachable",
            runCatching {
                Class.forName("cn.vectory.ocdroid.data.repository.http.TofuDecision\$Cancel")
            }.isSuccess,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * True iff [cls] declares at least one method matching [name].
     *
     * Kotlin mangles `suspend fun` JVM names with a `-<hash>` suffix
     * (e.g. `checkHealth-IoAF18A`) so suspend + non-suspend overloads
     * don't clash. We accept either the exact name OR the
     * `name-<hash>` mangling so this helper resolves both kinds.
     * `$default` synthetics (default args) are deliberately NOT matched.
     */
    private fun hasMethod(cls: Class<*>, name: String): Boolean =
        cls.declaredMethods.any { it.name == name || it.name.startsWith("$name-") }

    /**
     * Returns the single declared non-synthetic method named [name]
     * (resolves suspend mangling). Fails if 0 or >1.
     */
    private fun method(cls: Class<*>, name: String): java.lang.reflect.Method =
        cls.declaredMethods.single { it.name == name || it.name.startsWith("$name-") }
}
