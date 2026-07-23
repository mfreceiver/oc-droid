package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.SlimColdStartSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1d freeze — **RED until impl**. P1-1/P1-2/P1-3 slimOnlyStateWrite
 * structural hardening (task2 §2.2 P1-1~P1-3 + plan §3.3 slimOnlyStateWrite).
 *
 * **Goal**: downgrade the risk of slim-only state writes touching legacy
 * shared state from "caller remembers to gate" to "fail-fast assertion".
 *
 * **Expected impl surface** (impl MUST add):
 *
 * ```kotlin
 * // ── Exception type (assertThrows(ISE) accepts this subclass) ───────────
 * package cn.vectory.ocdroid.ui.controller
 * class SlimOnlyStateWriteException(message: String) : IllegalStateException(message)
 *
 * // ── Pure helper (callable from SSC entry points + unit-testable) ───────
 * package cn.vectory.ocdroid.ui.controller
 * internal fun requireSlimOnlyStateWrite(isSlim: Boolean, label: String) {
 *     if (!isSlim) {
 *         throw SlimOnlyStateWriteException(
 *             "slim-only state write [$label] invoked in legacy mode — leak risk"
 *         )
 *     }
 * }
 * ```
 *
 * **Production write points the helper guards** (impl wraps each entry):
 *
 * | # | write point                         | label (frozen)        | file:line       |
 * |---|-------------------------------------|-----------------------|-----------------|
 * | P1-1 | `applySlimColdStartSnapshot`     | `"cold-start-snapshot"` | SSC:3354/3375  |
 * | P1-2 | `mergeSlimMessagesIntoChat`      | `"merge-slim-messages"` | SSC:3307       |
 * | P1-3 | `applyReconcileResult` ClearLocal| `"clear-local"`         | SSC:2794-2799  |
 *
 * **NOT guarded** (shared branches — correct by design): `message.part.*` /
 * `message.updated` SSE handlers (C3: legacy-only wire; shared reducers are
 * NOT slim-only writes).
 *
 * **RED kind**: `compile-error` — `SlimOnlyStateWriteException` and
 * `requireSlimOnlyStateWrite` are unresolved references. Once impl adds them,
 * the pure-helper tests go GREEN; the SSC-integration test (P1-1 subclass
 * pin) goes GREEN when P1-1 wraps the entry.
 */
class T1dSlimOnlyStateWriteOwnershipTest {

    // ═══════════════════════════════════════════════════════════════════════
    // 1. SlimOnlyStateWriteException type existence + hierarchy
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `SlimOnlyStateWriteException extends IllegalStateException`() {
        // P0-5 uses assertThrows(IllegalStateException::class.java) — it MUST
        // accept the subclass. Pin the hierarchy so a future refactor can't
        // accidentally change the parent.
        val ex = SlimOnlyStateWriteException("test")
        assertTrue(
            "SlimOnlyStateWriteException MUST be an IllegalStateException subtype " +
                "(P0-5 assertThrows(ISE) relies on this)",
            ex is IllegalStateException,
        )
    }

    @Test
    fun `SlimOnlyStateWriteException carries the label in its message`() {
        val ex = SlimOnlyStateWriteException("slim-only state write [cold-start-snapshot] invoked in legacy mode — leak risk")
        assertTrue(
            "message carries the label for debugging",
            ex.message?.contains("cold-start-snapshot") == true,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. requireSlimOnlyStateWrite pure helper — throw / no-throw contract
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `requireSlimOnlyStateWrite throws SlimOnlyStateWriteException when isSlim is false`() {
        val ex = assertThrows(SlimOnlyStateWriteException::class.java) {
            requireSlimOnlyStateWrite(isSlim = false, label = "cold-start-snapshot")
        }
        assertTrue(
            "exception message contains the label",
            ex.message?.contains("cold-start-snapshot") == true,
        )
        assertTrue(
            "exception message mentions legacy mode / leak risk",
            ex.message?.contains("legacy mode") == true,
        )
    }

    @Test
    fun `requireSlimOnlyStateWrite does NOT throw when isSlim is true`() {
        // No exception → the call simply returns. If it threw, the slim
        // production paths (cold-start / reconcile) would break.
        requireSlimOnlyStateWrite(isSlim = true, label = "cold-start-snapshot")
        // Reaching this line = pass.
    }

    @Test
    fun `requireSlimOnlyStateWrite throws for each frozen P1 label`() {
        // P1-1 / P1-2 / P1-3 each carry a distinct label for debugging.
        // Pin all three so the impl can't use a single generic label.
        for (label in listOf("cold-start-snapshot", "merge-slim-messages", "clear-local")) {
            val ex = assertThrows(SlimOnlyStateWriteException::class.java) {
                requireSlimOnlyStateWrite(isSlim = false, label = label)
            }
            assertTrue(
                "label [$label] present in exception message",
                ex.message?.contains(label) == true,
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. P1-1 SSC integration — legacy applySlimColdStartSnapshot throws
    // ═══════════════════════════════════════════════════════════════════════
    //
    // Mirrors P0-5 in LegacyGoldenPathRegressionTest but pins the EXCEPTION
    // SUBTYPE (SlimOnlyStateWriteException, not just IllegalStateException).
    // P0-5 uses assertThrows(ISE::class.java) which accepts the subclass;
    // this test pins the EXACT subtype so a future refactor can't downgrade
    // to a generic ISE without test coverage.

    @Test
    fun `P1-1 legacy applySlimColdStartSnapshot throws SlimOnlyStateWriteException specifically`() {
        // Build a legacy-mode SSC directly (no MainViewModelTestBase dependency).
        val store = cn.vectory.ocdroid.ui.SharedStateStore()
        val settingsManager = io.mockk.mockk<cn.vectory.ocdroid.util.SettingsManager>(relaxed = true)
        val repository = io.mockk.mockk<cn.vectory.ocdroid.data.repository.OpenCodeRepository>(relaxed = true)
        val scope = kotlinx.coroutines.test.TestScope(kotlinx.coroutines.test.UnconfinedTestDispatcher())

        val coordinator = SessionSyncCoordinator(
            scope = scope,
            slices = store.slices,
            settingsManager = settingsManager,
            effects = cn.vectory.ocdroid.ui.SharedEffectBus(),
            currentServerGroupFp = { "test-fp" },
            supportsWatermarkResync = { false }, // LEGACY mode
            repository = repository,
        )

        // Seed a legacy session list so we can assert it's untouched.
        val prior = listOf(
            Session(id = "legacy-A", directory = "/A"),
            Session(id = "legacy-B", directory = "/B"),
        )
        store.mutateSessionList { it.copy(sessions = prior) }

        val snapshot = SlimColdStartSnapshot(
            sessions = listOf(Session(id = "slim-X", directory = "/slim")),
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
            ),
            messages = null,
        )

        // P1-1: the entry guard MUST throw SlimOnlyStateWriteException (the
        // documented subclass), not just a generic IllegalStateException.
        val ex = assertThrows(SlimOnlyStateWriteException::class.java) {
            coordinator.applySlimColdStartSnapshot(snapshot)
        }
        assertTrue(
            "exception label mentions cold-start",
            ex.message?.contains("cold-start") == true,
        )
        // The entry guard threw BEFORE any mutateSessionList — legacy list untouched.
        assertEquals(
            "legacy session list MUST NOT be rewritten (entry guard rejected before any mutation)",
            prior,
            store.sessionListFlow.value.sessions,
        )
    }
}
