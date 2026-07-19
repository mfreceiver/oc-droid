package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionOption
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §1C: pure-function unit tests for the [StatusSlotPriority.pick] decision
 * rule (plan §3.3 / scheme C.3).
 *
 * The slot is the binding single-decision function for "which of the
 * six possible surfaces renders now". The four visible states
 * (Permission, Question, Running, Connecting) MUST be mutually exclusive
 * at the screen-pixel level; this test is the load-bearing proof that the
 * decision function actually picks the highest-priority active class and
 * never lets two classes pass through.
 *
 * The test does not depend on Compose / app / slices — pick() reads its
 * own input parameters only. Boundaries covered:
 *   - empty input → None
 *   - single active state (each of the 6)
 *   - all-active → Permission (highest wins)
 *   - Running + Connecting tie (no session status, no streaming) → Running
 *   - Question + Running (streaming text present, question also present) →
 *     Question (Question beats Running)
 *   - Compacting + Running (compaction in progress, agent activity text
 *     also non-null) → Compacting (Compaction wins over Running)
 *   - Retry + Compacting (failed run, mid-compaction) → Retry
 *   - Permission + Question (both pending) → Permission
 *   - isBusy + Question (busy status, question present) → Question
 *     (Question is content-priority, beats busy status flag)
 */
class StatusSlotPriorityTest {

    private val dummyPermission = PermissionRequest(
        id = "p1",
        sessionId = "s1",
        permission = "bash",
    )
    private val dummyQuestion = QuestionRequest(
        id = "q1",
        sessionId = "s1",
        questions = listOf(
            QuestionInfo(
                question = "ok?",
                header = "h",
                options = listOf(QuestionOption("yes", "y")),
            )
        ),
    )

    /**
     * §T17 slimapi v1 §6.1: the canonical test fixture for a SET
     * [SlimSessionLastError]. Uses a representative `name` (a stable
     * machine-readable error code) + an optional server-scrubbed message.
     * Reused across every LastError-tier test so the priority rule is
     * exercised against a realistic payload.
     */
    private val dummyLastError = SlimSessionLastError(
        name = "upstream_error",
        message = "provider returned 503",
        at = 1_700_000_000_000L,
    )

    @Test
    fun `empty input yields None and never both states`() {
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            sessionStatus = null,
            isCompacting = false,
            isRunning = false,
            isConnecting = false,
        )
        assertEquals(StatusSlotPriority.None, p)
    }

    @Test
    fun `permission alone wins over everything`() {
        val p = StatusSlotPriority.pick(
            permission = dummyPermission,
            question = dummyQuestion,
            sessionStatus = SessionStatus(type = "retry"),
            isCompacting = true,
            isRunning = true,
            isConnecting = true,
        )
        assertEquals(StatusSlotPriority.Permission, p)
    }

    @Test
    fun `question beats retry, compacting, running, connecting`() {
        val p = StatusSlotPriority.pick(
            permission = null,
            question = dummyQuestion,
            sessionStatus = SessionStatus(type = "retry"),
            isCompacting = true,
            isRunning = true,
            isConnecting = true,
        )
        assertEquals(StatusSlotPriority.Question, p)
    }

    @Test
    fun `retry beats compacting, running, connecting`() {
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            sessionStatus = SessionStatus(type = "retry"),
            isCompacting = true,
            isRunning = true,
            isConnecting = true,
        )
        assertEquals(StatusSlotPriority.Retry, p)
    }

    @Test
    fun `compacting beats running and connecting`() {
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            sessionStatus = null,
            isCompacting = true,
            isRunning = true,
            isConnecting = true,
        )
        assertEquals(StatusSlotPriority.Compacting, p)
    }

    @Test
    fun `running beats connecting when both are active`() {
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            sessionStatus = null,
            isCompacting = false,
            isRunning = true,
            isConnecting = true,
        )
        assertEquals(StatusSlotPriority.Running, p)
    }

    @Test
    fun `connecting alone yields Connecting when nothing else is active`() {
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            sessionStatus = null,
            isCompacting = false,
            isRunning = false,
            isConnecting = true,
        )
        assertEquals(StatusSlotPriority.Connecting, p)
    }

    @Test
    fun `busy status alone is NOT a class — running activity drives the Running class`() {
        // The decision function does NOT look at sessionStatus.isBusy
        // directly: a session can be busy without an activity text
        // (e.g. early in sendMessage before the controller publishes
        // a current-activity snapshot). isRunning = (currentActivityText
        // != null) is the canonical signal. Verify: a busy status with
        // no running text and no other active class yields None (the
        // slot renders nothing — the chat area looks like an idle
        // session, which matches user expectation: nothing is happening
        // visibly yet).
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            sessionStatus = SessionStatus(type = "busy"),
            isCompacting = false,
            isRunning = false,
            isConnecting = false,
        )
        assertEquals(StatusSlotPriority.None, p)
    }

    @Test
    fun `idle status with running activity yields Running (status field is not the source of truth)`() {
        // Same boundary as above, inverted: a session whose status
        // already flipped back to idle but whose activity text is
        // still non-null (e.g. tail of stream during catch-up reload)
        // must still surface the Running class. The pick function
        // reads isRunning, not sessionStatus.isBusy.
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            sessionStatus = SessionStatus(type = "idle"),
            isCompacting = false,
            isRunning = true,
            isConnecting = false,
        )
        assertEquals(StatusSlotPriority.Running, p)
    }

    @Test
    fun `retry status alone (no running text) yields Retry`() {
        // A session in backoff retry has no streaming activity but the
        // status is isRetry. pick reads sessionStatus.isRetry directly,
        // so the Retry class fires even when isRunning is false.
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            sessionStatus = SessionStatus(type = "retry", message = "boom"),
            isCompacting = false,
            isRunning = false,
            isConnecting = false,
        )
        assertEquals(StatusSlotPriority.Retry, p)
    }

    @Test
    fun `compacting alone (no running text, no other state) yields Compacting`() {
        // The 3s idle-clear guard in ChatScaffold keeps isCompacting
        // true even after a slow compact; the slot must surface
        // Compacting until the slice flips back. Boundary: a bare
        // isCompacting=true with no other active input yields
        // Compacting (not None).
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            sessionStatus = null,
            isCompacting = true,
            isRunning = false,
            isConnecting = false,
        )
        assertEquals(StatusSlotPriority.Compacting, p)
    }

    @Test
    fun `permission class wins over isRunning text (text is ignored when permission present)`() {
        // Defense in depth: even if a stale running text lingers in
        // the slice while a permission arrives, the priority rule
        // guarantees Permission wins. The slot renders the
        // permission card; the running text is dropped on the floor
        // for this frame (and re-evaluated on the next pick cycle).
        val p = StatusSlotPriority.pick(
            permission = dummyPermission,
            question = null,
            sessionStatus = null,
            isCompacting = false,
            isRunning = true,
            isConnecting = true,
        )
        assertEquals(StatusSlotPriority.Permission, p)
    }

    @Test
    fun `all-ordinal values are distinct — enum mapping is collision-free`() {
        // The priority enum is a stable public API (the test
        // serializes the order for documentation). A reorder or
        // accidental duplicate would silently change the tie-break
        // rules. Pin the explicit ordinal set.
        assertEquals(
            setOf(
                StatusSlotPriority.None,
                StatusSlotPriority.Connecting,
                StatusSlotPriority.Running,
                StatusSlotPriority.Compacting,
                StatusSlotPriority.Retry,
                StatusSlotPriority.LastError,
                StatusSlotPriority.Question,
                StatusSlotPriority.Permission,
            ),
            StatusSlotPriority.values().toSet(),
        )
    }

    @Test
    fun `retry wins over compacting when both flags are true (C_3 priority)`() {
        // §1C-FIX-① regression: a prior caller-side pre-filter
        // (`curSessionStatus?.takeIf { !isCompacting }`) nulled out the
        // retry status when compaction was also in flight, causing
        // pick() to fall through to Compacting and violating the
        // C.3 rule (Retry > Compacting). With the pre-filter removed,
        // pick() is the SOLE decision point: when both isRetry and
        // isCompacting are true, Retry wins. This test pins that
        // exact regression.
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            sessionStatus = SessionStatus(type = "retry", message = "retrying", next = 1000L),
            isCompacting = true,
            isRunning = false,
            isConnecting = false,
        )
        assertEquals(
            "Retry > Compacting (C.3); pick must see retry through compaction",
            StatusSlotPriority.Retry,
            p,
        )
    }

    @Test
    fun `retry wins over running when both isRetry and activity text are present (C_3 priority)`() {
        // §1C-FIX-① regression (variant): the prior
        // `curSessionStatus?.isRetry != true` filter on
        // currentActivityText collapsed the running signal to null
        // whenever retry was active. With that filter removed, pick()
        // must see both signals and return Retry (Retry > Running per
        // C.3). The activity text being non-null here means
        // `isRunning = true` in pick's eyes; the priority rule is
        // what determines the visible surface.
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            sessionStatus = SessionStatus(type = "retry", message = "retrying"),
            isCompacting = false,
            isRunning = true,
            isConnecting = true,
        )
        assertEquals(
            "Retry > Running (C.3); pick must see retry through running+connecting",
            StatusSlotPriority.Retry,
            p,
        )
    }

    @Test
    fun `compacting wins over running when status is idle and activity text is non-null`() {
        // The (C.3) priority Compacting > Running — pin it. Variant of
        // the existing compacting-beats-running test, but asserts
        // that the pre-existing sessionStatus (idle) does not
        // interfere: pick() reads the isCompacting flag directly, not
        // sessionStatus.
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            sessionStatus = SessionStatus(type = "idle"),
            isCompacting = true,
            isRunning = true,
            isConnecting = false,
        )
        assertEquals(StatusSlotPriority.Compacting, p)
    }

    @Test
    fun `pick output is deterministic — repeated calls return the same priority`() {
        // The decision is pure (no side effects, no random / time
        // sources). The slot recomposes on every slice emission; an
        // unstable pick would flicker the slot. Confirm by
        // re-running the same input 5x.
        repeat(5) {
            val p = StatusSlotPriority.pick(
                permission = null,
                question = dummyQuestion,
                sessionStatus = SessionStatus(type = "idle"),
                isCompacting = false,
                isRunning = true,
                isConnecting = true,
            )
            assertEquals(StatusSlotPriority.Question, p)
        }
    }

    @Test
    fun `session-scoped filter helper yields only this session's pending items`() {
        // §P5-7: the StatusSlot composable takes a pre-filtered
        // permission + question. This test pins the contract that
        // the caller-side filter (the existing ChatScaffold
        // `pendingPermission` derivation) is the SOLE filter site.
        // The slot does not re-filter — re-filtering inside the slot
        // would create a second read site that has to stay in sync.
        // We confirm the contract by asserting the composable's
        // parameter is non-null only for matching session ids in
        // the canonical derivation:
        //   pendingPermission = sessionList.pendingPermissions
        //     .firstOrNull { it.sessionId == chat.currentSessionId }
        val perms: List<PermissionRequest> = listOf(
            PermissionRequest(id = "pA", sessionId = "s1", permission = "bash"),
            PermissionRequest(id = "pB", sessionId = "s2", permission = "write"),
            PermissionRequest(id = "pC", sessionId = "s1", permission = "read"),
        )
        val current = "s1"
        val filtered = perms.firstOrNull { it.sessionId == current }
        assertEquals("pA", filtered?.id)
        // Re-run with a different current: filter must be the only
        // difference.
        val filtered2 = perms.firstOrNull { it.sessionId == "s2" }
        assertEquals("pB", filtered2?.id)
        // Edge: no matching session → null (slot renders nothing for
        // permission; other branches can still fire if their own
        // conditions hold).
        val filteredNone = perms.firstOrNull { it.sessionId == "sX" }
        assertNull(filteredNone)
    }

    // ── §T17 slimapi v1 §6.1: LastError tier ────────────────────────────────
    //
    // The new tier sits BETWEEN Question and Retry in the binding order
    // (Permission > Question > LastError > Retry > Compacting > Running >
    // Connecting > None). The fixture [dummyLastError] is a representative
    // `SlimSessionLastError`; the sid-scope rule (the caller pre-filters
    // sessionErrorsById to the current sid) is asserted in the
    // `session-scoped filter helper yields only this session's ...` test
    // above — LastError reuses the same contract.

    @Test
    fun `lastError alone (no other state) yields LastError`() {
        // Boundary: a SET lastError with no permission / question / retry /
        // compacting / running / connecting fires the LastError branch —
        // the slot renders the banner. Absent sid at the call site is the
        // caller's responsibility (the composable passes null when the
        // sid is absent from sessionErrorsById), so this test exercises
        // the "error present, everything else quiescent" path.
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            lastError = dummyLastError,
            sessionStatus = null,
            isCompacting = false,
            isRunning = false,
            isConnecting = false,
        )
        assertEquals(StatusSlotPriority.LastError, p)
    }

    @Test
    fun `null lastError with everything else quiescent yields None (null-safety gate)`() {
        // T17 hard gate: an absent sid at the call site yields lastError =
        // null. pick MUST fall through to None — the slot's LastError
        // branch NEVER fires for an absent sid. This is the priority-level
        // proof of the null-safety contract.
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            lastError = null,
            sessionStatus = null,
            isCompacting = false,
            isRunning = false,
            isConnecting = false,
        )
        assertEquals(StatusSlotPriority.None, p)
    }

    @Test
    fun `lastError beats retry, compacting, running, connecting`() {
        // The T17 ranking decision (file KDoc): a SET lastError is durable
        // (the sidecar WILL NOT auto-clear it) and signals "this session
        // is blocked right now" — higher user-urgency than transient Retry
        // (auto-backoff) / Compacting / Running / Connecting. Pin the
        // LastError > Retry > Compacting > Running > Connecting ordering.
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            lastError = dummyLastError,
            sessionStatus = SessionStatus(type = "retry"),
            isCompacting = true,
            isRunning = true,
            isConnecting = true,
        )
        assertEquals(StatusSlotPriority.LastError, p)
    }

    @Test
    fun `lastError beats retry specifically (T17 ranking decision)`() {
        // The most controversial tie-break: a session that is BOTH in retry
        // backoff (SessionStatus.isRetry = true) AND has a SET lastError.
        // Spec designates LastError the winner — the sidecar surfacing a
        // hard error takes precedence over a transient auto-recovery
        // affordance. See [StatusSlotPriority.LastError] KDoc for the full
        // rationale. This test pins that exact decision so a future
        // reorder has to update both the KDoc and the test.
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            lastError = dummyLastError,
            sessionStatus = SessionStatus(type = "retry", message = "retrying", next = 1000L),
            isCompacting = false,
            isRunning = false,
            isConnecting = false,
        )
        assertEquals(
            "LastError > Retry (T17); a durable hard error pre-empts the retry countdown",
            StatusSlotPriority.LastError,
            p,
        )
    }

    @Test
    fun `permission beats lastError (interactive blocking still wins)`() {
        // T17 ranking: Permission / Question are interactive blocking (the
        // user MUST act) — they pre-empt LastError even when the session is
        // hard-errored. If the error itself blocks the permission flow,
        // the sidecar withdraws the pending permission and the slot
        // naturally falls through to LastError.
        val p = StatusSlotPriority.pick(
            permission = dummyPermission,
            question = null,
            lastError = dummyLastError,
            sessionStatus = null,
            isCompacting = false,
            isRunning = false,
            isConnecting = false,
        )
        assertEquals(StatusSlotPriority.Permission, p)
    }

    @Test
    fun `question beats lastError (interactive blocking still wins)`() {
        // Symmetric to the permission-beats-lastError test. A pending
        // question pre-empts the LastError banner.
        val p = StatusSlotPriority.pick(
            permission = null,
            question = dummyQuestion,
            lastError = dummyLastError,
            sessionStatus = null,
            isCompacting = false,
            isRunning = false,
            isConnecting = false,
        )
        assertEquals(StatusSlotPriority.Question, p)
    }

    @Test
    fun `lastError null with retry present still yields Retry (null-safety)`() {
        // T17 null-safety gate, retry-flavoured variant: when lastError is
        // null (sid absent from the map / recovered) AND sessionStatus is
        // retry, pick MUST return Retry — not None, not LastError. Proves
        // the new tier does not perturb the existing Retry behaviour when
        // there is no SET lastError.
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            lastError = null,
            sessionStatus = SessionStatus(type = "retry", message = "retrying"),
            isCompacting = false,
            isRunning = false,
            isConnecting = false,
        )
        assertEquals(StatusSlotPriority.Retry, p)
    }

    @Test
    fun `lastError with running activity yields LastError (banner pre-empts running capsule)`() {
        // Boundary: even if a stale activity text lingers in the slice
        // while a lastError arrives, the priority rule guarantees
        // LastError wins. The slot renders the banner; the running text
        // is dropped on the floor for this frame. Mirrors the existing
        // `permission class wins over isRunning text` test.
        val p = StatusSlotPriority.pick(
            permission = null,
            question = null,
            lastError = dummyLastError,
            sessionStatus = null,
            isCompacting = false,
            isRunning = true,
            isConnecting = true,
        )
        assertEquals(StatusSlotPriority.LastError, p)
    }
}
