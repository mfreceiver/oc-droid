package cn.vectory.ocdroid.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §C1 / P1-gate-fix — dedicated debounce tests for [SessionPrefs].
 *
 * Three groups:
 *  1. Single-key contract: coalescing / timer-restart / immediate-flush /
 *     blank-removal.
 *  2. **Cross-key isolation** (the P1-gate defect): rapid writes to
 *     DIFFERENT `(serverGroupFp, sessionId)` composite keys must NEVER
 *     clobber each other. The prior single-slot debounce (one global
 *     AtomicReference + one Job) lost key A when key B was written before
 *     A's timer fired; the per-key refactor keys pending state by
 *     [SessionPrefs.compositeSessionKey] so each key has its own mutation
 *     AND its own timer.
 *  3. **Cross-key concurrent persistence** (the §P1-gate-persist defect):
 *     the `session_drafts` whole-map read-modify-write in
 *     [SessionPrefs.performDraftWrite] runs from independent debounce jobs
 *     on Dispatchers.Default (multi-threaded). Without the persistence lock,
 *     two jobs reading the same old JSON snapshot and each writing back a
 *     single-key update would lose the other's update (lost-update race).
 *     Group 3 drives N distinct keys from concurrent coroutines on a REAL
 *     multi-threaded dispatcher and asserts no key is lost.
 *
 * Groups 1+2 use VIRTUAL TIME ([TestScope] + [advanceTimeBy] + [runCurrent]
 * — StandardTestDispatcher does not run dispatched tasks eagerly); group 3
 * uses REAL time ([runBlocking] + [Dispatchers.Default]) to force genuine
 * cross-thread concurrency. Probes [SessionPrefs.peekPendingDraft] /
 * [SessionPrefs.peekAllPendingDrafts] for deterministic assertions without
 * sleeping the real clock.
 *
 * Uses a PLAIN [android.content.SharedPreferences] (Robolectric in-memory) —
 * the EncryptedSharedPreferences encryption layer is orthogonal to the
 * debounce logic under test. Convention follows
 * [cn.vectory.ocdroid.di.AppLifecycleMonitorTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SessionPrefsDebounceTest {

    private lateinit var prefs: SessionPrefs
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sp = context.getSharedPreferences("session_prefs_debounce_test", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        testScope = TestScope(StandardTestDispatcher())
        prefs = SessionPrefs(sp, testScope)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Group 1 — single-key debounce contract
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `rapid typing coalesces multiple setDraftText into one write`() = testScope.runTest {
        // Three rapid keystrokes for the same (fp, sid) collapse into ONE
        // pending mutation and ONE ESP write after the debounce window.
        prefs.setDraftText("g1", "s1", "a")
        prefs.setDraftText("g1", "s1", "ab")
        prefs.setDraftText("g1", "s1", "abc")

        // Pending holds the LATEST mutation only; ESP has NOT been written yet.
        assertEquals("abc", prefs.peekPendingDraft("g1", "s1")?.text)
        assertEquals("ESP must not yet be written (within debounce window)", "", prefs.getDraftText("g1", "s1"))

        // Fire the debounce — single write of the latest value. advanceTimeBy
        // moves the virtual clock + schedules the due task; runCurrent
        // executes it (StandardTestDispatcher does not run dispatched tasks
        // eagerly).
        advanceTimeBy(SessionPrefs.DEBOUNCE_MS)
        runCurrent()
        assertEquals("abc", prefs.getDraftText("g1", "s1"))
        assertNull("pending mutation cleared after write", prefs.peekPendingDraft("g1", "s1"))
    }

    @Test
    fun `a new edit cancels the pending debounce timer and restarts it`() = testScope.runTest {
        prefs.setDraftText("g1", "s1", "a")
        // Advance to just before the deadline — still pending, not written.
        advanceTimeBy(SessionPrefs.DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals("still inside first debounce window", "", prefs.getDraftText("g1", "s1"))

        // A new edit cancels the pending timer and starts a fresh DEBOUNCE_MS.
        prefs.setDraftText("g1", "s1", "b")
        // Advancing 1ms reaches the ORIGINAL deadline but NOT the new one → no write.
        advanceTimeBy(1)
        runCurrent()
        assertEquals("original deadline reached but timer was restarted", "", prefs.getDraftText("g1", "s1"))

        // Reaching the NEW deadline fires the write of the latest value.
        advanceTimeBy(SessionPrefs.DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals("b", prefs.getDraftText("g1", "s1"))
    }

    @Test
    fun `flushDraftText writes immediately and clears pending`() = testScope.runTest {
        prefs.setDraftText("g1", "s1", "x")
        assertEquals("pending write not yet flushed to ESP", "", prefs.getDraftText("g1", "s1"))
        assertEquals("x", prefs.peekPendingDraft("g1", "s1")?.text)

        prefs.flushDraftText()
        assertEquals("x", prefs.getDraftText("g1", "s1"))
        assertNull("pending cleared by flush", prefs.peekPendingDraft("g1", "s1"))

        // Advancing virtual time must NOT double-fire: the debounce job was
        // cancelled by flush, so a delayed write-back cannot land later and
        // clobber/rewrite the value.
        advanceTimeBy(SessionPrefs.DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals("x", prefs.getDraftText("g1", "s1"))
    }

    @Test
    fun `flushDraftText is a no-op when there is no pending draft`() = testScope.runTest {
        // No prior setDraftText → flush is a harmless no-op.
        prefs.flushDraftText()
        assertNull(prefs.peekPendingDraft("g1", "s1"))
        assertTrue(prefs.peekAllPendingDrafts().isEmpty())
        assertEquals("", prefs.getDraftText("g1", "s1"))
    }

    @Test
    fun `blank text still removes the entry under debounce`() = testScope.runTest {
        // Seed a durable entry first.
        prefs.setDraftText("g1", "s1", "hello")
        prefs.flushDraftText()
        assertEquals("hello", prefs.getDraftText("g1", "s1"))

        // A blank mutation scheduled via the debounce path still removes the
        // entry once it fires (blank-removal semantic preserved under debounce).
        prefs.setDraftText("g1", "s1", "   ")
        assertEquals("removal pending, not yet written", "hello", prefs.getDraftText("g1", "s1"))
        advanceTimeBy(SessionPrefs.DEBOUNCE_MS)
        runCurrent()
        assertEquals("blank draft must remove the composite-key entry", "", prefs.getDraftText("g1", "s1"))
    }

    @Test
    fun `sequential per-key composite-key isolation is preserved`() = testScope.runTest {
        // The realistic single-writer pattern: each write flushes before the
        // next composite key is touched (exactly what SessionSwitcher /
        // SessionViewModel.closeSession do after the §C1 flush wiring).
        prefs.setDraftText("g1", "s1", "a")
        advanceTimeBy(SessionPrefs.DEBOUNCE_MS)
        runCurrent()
        prefs.setDraftText("g1", "s2", "b")
        advanceTimeBy(SessionPrefs.DEBOUNCE_MS)
        runCurrent()
        prefs.setDraftText("g2", "s1", "c")
        advanceTimeBy(SessionPrefs.DEBOUNCE_MS)
        runCurrent()

        // Same sessionId under different fps → independent slots (clone/reset
        // servers issuing the same branded ses_xxx id cannot leak drafts).
        assertEquals("a", prefs.getDraftText("g1", "s1"))
        assertEquals("b", prefs.getDraftText("g1", "s2"))
        assertEquals("c", prefs.getDraftText("g2", "s1"))
        assertEquals("", prefs.getDraftText("g2", "s2"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Group 2 — CROSS-KEY isolation (P1-gate defect: the prior single-slot
    //  debounce lost key A when key B was written before A's timer fired).
    //  Each scenario would FAIL on the old one-AtomicReference design.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `cross-key - key A then key B before debounce both persist after flush`() = testScope.runTest {
        // Two different sessions edited back-to-back, BEFORE either timer fires.
        prefs.setDraftText("g1", "s1", "draft-A")
        prefs.setDraftText("g1", "s2", "draft-B")

        // BOTH pending independently — the old single-slot design would have
        // overwritten A's pending mutation with B's, leaving only B.
        assertEquals("draft-A", prefs.peekPendingDraft("g1", "s1")?.text)
        assertEquals("draft-B", prefs.peekPendingDraft("g1", "s2")?.text)
        assertEquals(2, prefs.peekAllPendingDrafts().size)
        // Neither written to ESP yet.
        assertEquals("", prefs.getDraftText("g1", "s1"))
        assertEquals("", prefs.getDraftText("g1", "s2"))

        // Flush ALL → both durable. (Old design: only B would survive.)
        prefs.flushDraftText()
        assertEquals("draft-A", prefs.getDraftText("g1", "s1"))
        assertEquals("draft-B", prefs.getDraftText("g1", "s2"))
        assertTrue("all pending drained after flush", prefs.peekAllPendingDrafts().isEmpty())
    }

    @Test
    fun `cross-key - same sessionId across host A and host B both persist`() = testScope.runTest {
        // The privacy-critical case: the SAME branded ses_xxx id issued by two
        // different hosts must NOT leak one host's draft into the other.
        prefs.setDraftText("hostA", "ses-x", "on-host-A")
        prefs.setDraftText("hostB", "ses-x", "on-host-B")

        prefs.flushDraftText()
        assertEquals("on-host-A", prefs.getDraftText("hostA", "ses-x"))
        assertEquals("on-host-B", prefs.getDraftText("hostB", "ses-x"))
    }

    @Test
    fun `cross-key - flush racing a new write on a different key loses nothing`() = testScope.runTest {
        // Two keys pending; flush drains them synchronously.
        prefs.setDraftText("g1", "s1", "first")
        prefs.setDraftText("g1", "s2", "second")
        prefs.flushDraftText()

        // A brand-new write on a DIFFERENT key arrives right after the flush
        // (the transition path: SessionSwitcher flushes the old session, then
        // the new session's edits begin). The flush must not have stranded or
        // resurrected anything; the new key gets a fresh pending slot.
        prefs.setDraftText("g1", "s3", "third")
        assertEquals("third", prefs.peekPendingDraft("g1", "s3")?.text)
        assertNull("old key is clean post-flush", prefs.peekPendingDraft("g1", "s1"))
        assertNull("old key is clean post-flush", prefs.peekPendingDraft("g1", "s2"))

        prefs.flushDraftText()
        assertEquals("first", prefs.getDraftText("g1", "s1"))
        assertEquals("second", prefs.getDraftText("g1", "s2"))
        assertEquals("third", prefs.getDraftText("g1", "s3"))
    }

    @Test
    fun `cross-key - blank removal on key A with concurrent update to key B`() = testScope.runTest {
        // Seed both durably.
        prefs.setDraftText("g1", "s1", "seeded-A")
        prefs.setDraftText("g1", "s2", "seeded-B")
        prefs.flushDraftText()
        assertEquals("seeded-A", prefs.getDraftText("g1", "s1"))
        assertEquals("seeded-B", prefs.getDraftText("g1", "s2"))

        // Blank A (pending entry removal) AND update B (pending new value),
        // interleaved before the debounce fires.
        prefs.setDraftText("g1", "s1", "") // blank → A removal pending
        prefs.setDraftText("g1", "s2", "updated-B")
        prefs.flushDraftText()

        // A's entry removed, B's update persisted — the two must NOT bleed.
        assertEquals("blank on A must remove only A's entry", "", prefs.getDraftText("g1", "s1"))
        assertEquals("B must persist its own update", "updated-B", prefs.getDraftText("g1", "s2"))
    }

    @Test
    fun `cross-key - cancelling key A timer does not cancel key B timer`() = testScope.runTest {
        // Arm two independent timers, both ending at t=500.
        prefs.setDraftText("g1", "s1", "A1") // A timer until t=500
        prefs.setDraftText("g1", "s2", "B1") // B timer until t=500
        advanceTimeBy(300)
        runCurrent() // t=300; neither fired

        // Re-edit A → cancels + restarts ONLY A's timer (now until t=800).
        // B's timer must be UNTOUCHED (still ending at t=500).
        prefs.setDraftText("g1", "s1", "A2") // A timer until t=800
        advanceTimeBy(200)
        runCurrent() // t=500 → B fires on its OWN schedule; A still pending

        assertEquals(
            "B fires on its own schedule despite A being re-edited",
            "B1",
            prefs.getDraftText("g1", "s2"),
        )
        assertEquals("A not yet written (its timer was restarted)", "", prefs.getDraftText("g1", "s1"))
        assertEquals("A2", prefs.peekPendingDraft("g1", "s1")?.text)

        advanceTimeBy(300)
        runCurrent() // t=800 → A's restarted timer fires
        assertEquals("A2", prefs.getDraftText("g1", "s1"))
        assertEquals("B unchanged after A's late write", "B1", prefs.getDraftText("g1", "s2"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Group 3 — CROSS-KEY CONCURRENT PERSISTENCE (§P1-gate-persist defect)
    //
    //  performDraftWrite does a whole-map read-modify-write of the shared
    //  session_drafts JSON and is invoked from independent debounce jobs on
    //  Dispatchers.Default (multi-threaded). Without persistLock, two jobs
    //  reading the same old snapshot and each writing back one key's update
    //  lose the other's update. These tests force that concurrency on a REAL
    //  multi-threaded dispatcher (runBlocking + Dispatchers.Default — NOT
    //  virtual time) and assert every key survives (no lost updates).
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Tightest form of the persistence race: the null-scope construction makes
     * [SessionPrefs.setDraftText] call [SessionPrefs.performDraftWrite]
     * SYNCHRONOUSLY in the calling coroutine (no debounce), so N coroutines on
     * [Dispatchers.Default] each perform a whole-map RMW on a distinct key
     * concurrently. Without persistLock this is a guaranteed lost update; with
     * it, the RMWs serialize and every key round-trips. Runs many iterations to
     * make the race window likely to surface a regression.
     */
    @Test
    fun `concurrent direct persistence across distinct keys has no lost updates`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val csp = ctx.getSharedPreferences("session_prefs_concurrency_direct", Context.MODE_PRIVATE)
        // null scope → every setDraftText is a direct performDraftWrite (the
        // tightest possible stress on the shared-map RMW).
        val directPrefs = SessionPrefs(csp, debounceScope = null)
        val keyCount = 16
        val iterations = 40
        repeat(iterations) { iter ->
            csp.edit().remove(SessionPrefs.KEY_SESSION_DRAFTS).commit()
            // Each key written from its OWN coroutine on a real multi-threaded
            // dispatcher → concurrent whole-map RMWs on distinct keys.
            coroutineScope {
                (0 until keyCount).map { i ->
                    launch(Dispatchers.Default) {
                        directPrefs.setDraftText("fp-$i", "ses-$i", "v-iter$iter-$i")
                    }
                }
            }
            // After all concurrent writes join, EVERY key must round-trip — a
            // lost update would drop one key's value (return "" or a stale val).
            for (i in 0 until keyCount) {
                assertEquals(
                    "lost update: iter=$iter key=$i (concurrent persistence RMW race)",
                    "v-iter$iter-$i",
                    directPrefs.getDraftText("fp-$i", "ses-$i"),
                )
            }
        }
    }

    /**
     * End-to-end form: a REAL debounced scope ([Dispatchers.Default]) with the
     * public [SessionPrefs.setDraftText] + [SessionPrefs.flushDraftText] API.
     * The per-key debounce write-back jobs AND the flush drain loop all reach
     * [SessionPrefs.performDraftWrite] on multiple Default threads → genuine
     * cross-thread concurrency on the shared `session_drafts` map. Asserts no
     * key is lost after all flushes join.
     */
    @Test
    fun `concurrent debounced persistence with flush across distinct keys has no lost updates`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val csp = ctx.getSharedPreferences("session_prefs_concurrency_flush", Context.MODE_PRIVATE)
        val realScope = CoroutineScope(Dispatchers.Default)
        val concurrentPrefs = SessionPrefs(csp, realScope)
        try {
            val keyCount = 16
            val iterations = 30
            repeat(iterations) { iter ->
                csp.edit().remove(SessionPrefs.KEY_SESSION_DRAFTS).commit()
                coroutineScope {
                    (0 until keyCount).map { i ->
                        launch(Dispatchers.Default) {
                            concurrentPrefs.setDraftText("fp-$i", "ses-$i", "v-iter$iter-$i")
                            // flush forces the write immediately (cancels the
                            // just-scheduled debounce job + drains the mutation),
                            // exercising the flush drain loop's RMW too.
                            concurrentPrefs.flushDraftText()
                        }
                    }
                }
                for (i in 0 until keyCount) {
                    assertEquals(
                        "lost update: iter=$iter key=$i (flush concurrency race)",
                        "v-iter$iter-$i",
                        concurrentPrefs.getDraftText("fp-$i", "ses-$i"),
                    )
                }
            }
        } finally {
            realScope.cancel()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Group 4 — clearDraftsForProfile (§需求12 rev-4 blocker B)
    //
    //  Profile deletion must remove ALL of the deleted profile's draft entries
    //  from the shared session_drafts JSON map. The method scans the composite
    //  keys (`profileId\u0000sessionId`) and removes every entry whose
    //  profileId portion matches. Other profiles' drafts MUST survive
    //  untouched. Uses the test's plain SharedPreferences fixture (the ESP
    //  encryption layer is orthogonal to the scan/remove logic).
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `clearDraftsForProfile removes only the targeted profile's drafts and preserves others`() {
        // Seed drafts for profile-A (2 sessions) + profile-B (1 session).
        prefs.setDraftText("profile-A", "ses-1", "a-1")
        prefs.setDraftText("profile-A", "ses-2", "a-2")
        prefs.setDraftText("profile-B", "ses-9", "b-9")
        prefs.flushDraftText()
        assertEquals("a-1", prefs.getDraftText("profile-A", "ses-1"))
        assertEquals("a-2", prefs.getDraftText("profile-A", "ses-2"))
        assertEquals("b-9", prefs.getDraftText("profile-B", "ses-9"))

        prefs.clearDraftsForProfile("profile-A")

        assertEquals("A's draft ses-1 must be gone", "", prefs.getDraftText("profile-A", "ses-1"))
        assertEquals("A's draft ses-2 must be gone", "", prefs.getDraftText("profile-A", "ses-2"))
        assertEquals("B's draft must survive untouched", "b-9", prefs.getDraftText("profile-B", "ses-9"))
    }

    @Test
    fun `clearDraftsForProfile is a no-op when no drafts match the profileId`() {
        prefs.setDraftText("profile-A", "ses-1", "a-1")
        prefs.flushDraftText()

        // No entries match → no-op (no rewrite, no crash).
        prefs.clearDraftsForProfile("profile-nonexistent")

        assertEquals("A's draft survives the no-op clear", "a-1", prefs.getDraftText("profile-A", "ses-1"))
    }

    @Test
    fun `clearDraftsForProfile is a no-op on blank profileId`() {
        prefs.setDraftText("profile-A", "ses-1", "a-1")
        prefs.flushDraftText()

        // Blank profileId → defensive no-op (a real profile.id is always a
        // non-blank UUID; the guard protects against misuse).
        prefs.clearDraftsForProfile("")

        assertEquals("blank profileId clear is a no-op", "a-1", prefs.getDraftText("profile-A", "ses-1"))
    }

    @Test
    fun `clearDraftsForProfile is a no-op when the session_drafts map is absent`() {
        // No setDraftText yet → session_drafts key doesn't exist. The method
        // must short-circuit cleanly (no crash, no spurious write creating an
        // empty map).
        prefs.clearDraftsForProfile("profile-A")

        val sp = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("session_prefs_debounce_test", Context.MODE_PRIVATE)
        assertNull("session_drafts key must not be created by a no-op clear",
            sp.getString(SessionPrefs.KEY_SESSION_DRAFTS, null))
    }

    @Test
    fun `clearDraftsForProfile leaves corrupt session_drafts JSON untouched`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sp = ctx.getSharedPreferences("session_prefs_corrupt_clear_test", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        val corrupt = "{not valid json"
        sp.edit().putString(SessionPrefs.KEY_SESSION_DRAFTS, corrupt).commit()
        // null scope → direct construction (no debounce) for a deterministic probe.
        val directPrefs = SessionPrefs(sp, debounceScope = null)

        directPrefs.clearDraftsForProfile("profile-A")

        assertEquals(
            "corrupt JSON must be left untouched (parse failure → no-op)",
            corrupt,
            sp.getString(SessionPrefs.KEY_SESSION_DRAFTS, null),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Group 5 — clearDraftsForProfile DELETION BARRIER (§需求12 rev-6 blocker C)
    //
    //  A naive ESP-only clear leaks via the in-memory debounce path: a pending
    //  write-back job (or one whose RMW is waiting on persistLock) fires AFTER
    //  the clear and re-persists the deleted profile's draft — resurrecting
    //  sensitive unsent text the user expected gone. The deletion barrier
    //  closes every window:
    //   - pending-not-yet-claimed: clearDraftsForProfile cancels the job.
    //   - pending-claimed-but-waiting-on-persistLock: performDraftWrite re-checks
    //     the barrier inside persistLock and drops the write.
    //   - re-selected profile: setDraftText lifts the barrier so new writes
    //     persist again.
    //   - cross-profile isolation: clearing A does not touch B's pending.
    //  rev-6 explicitly flagged the existing Group 4 tests all flushDraftText()
    //  first (no pending state at clear time) → the resurrection race was
    //  uncovered. These tests arm the pending state + the barrier.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `clearDraftsForProfile cancels pending debounce and prevents resurrection`() = testScope.runTest {
        // Seed a pending draft for profile-A (debounceScope active, NOT flushed).
        prefs.setDraftText("profile-A", "ses-1", "secret-unsent")
        assertEquals(
            "pending mutation armed, not yet written",
            "secret-unsent",
            prefs.peekPendingDraft("profile-A", "ses-1")?.text,
        )
        assertEquals("ESP untouched inside debounce window", "", prefs.getDraftText("profile-A", "ses-1"))

        // Bulk-clear profile-A's drafts. Step 1 cancels the pending debounce
        // job + removes the pending mutation; step 2 clears ESP (empty here).
        prefs.clearDraftsForProfile("profile-A")

        assertNull("pending mutation removed by the clear", prefs.peekPendingDraft("profile-A", "ses-1"))

        // Advance PAST the debounce window. Without the barrier the cancelled
        // job would have fired here and resurrected the draft; with it, the
        // job is cancelled so nothing fires.
        advanceTimeBy(SessionPrefs.DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(
            "draft MUST NOT resurrect after clear (pending job was cancelled)",
            "",
            prefs.getDraftText("profile-A", "ses-1"),
        )
    }

    @Test
    fun `performDraftWrite drops a claimed write-back whose profile is under the deletion barrier`() {
        // §需求12 rev-6 blocker C step 3: the hardest window — a write-back job
        // already CLAIMED its mutation (removed it from pendingDrafts under
        // debounceLock) BEFORE clearDraftsForProfile ran, then waited on
        // persistLock while clearDraftsForProfile set the barrier + cleared
        // ESP. By the time the job's performDraftWrite acquires persistLock,
        // the profile is under barrier → the write MUST be dropped.
        //
        // StandardTestDispatcher virtual time can't reliably position a job
        // between its claim and performDraftWrite (the body is synchronous, no
        // suspension point), so this test drives the guard directly: arm the
        // barrier via a real clearDraftsForProfile, then invoke
        // performDraftWrite (as the claimed write-back job would) and assert
        // it's a no-op.
        val profileId = "profile-A"
        // Sanity: without the barrier, a direct performDraftWrite writes.
        invokePerformDraftWrite(profileId, "ses-1", "should-persist-without-barrier")
        assertEquals(
            "sanity: no barrier → write lands",
            "should-persist-without-barrier",
            prefs.getDraftText(profileId, "ses-1"),
        )

        // clearDraftsForProfile arms the barrier (step 1) + clears ESP (step 2).
        // This simulates the race: a write-back job already claimed its
        // mutation before the clear and is now waiting on persistLock.
        prefs.clearDraftsForProfile(profileId)
        assertEquals("ESP cleared by clearDraftsForProfile", "", prefs.getDraftText(profileId, "ses-1"))

        // The claimed write-back fires AFTER the clear (the resurrection
        // window). The barrier re-check inside performDraftWrite MUST drop it.
        invokePerformDraftWrite(profileId, "ses-1", "RESURRECT-attempt")
        assertEquals(
            "claimed write-back MUST be dropped when profile is under barrier",
            "",
            prefs.getDraftText(profileId, "ses-1"),
        )

        // A DIFFERENT profile's write is unaffected (barrier is per-profile).
        invokePerformDraftWrite("profile-B", "ses-1", "b-draft")
        assertEquals(
            "B's write unaffected by A's barrier",
            "b-draft",
            prefs.getDraftText("profile-B", "ses-1"),
        )
    }

    @Test
    fun `setDraftText after clear lifts the deletion barrier so a re-selected profile persists again`() = testScope.runTest {
        // Profile-A is seeded then cleared (barrier armed for A).
        prefs.setDraftText("profile-A", "ses-1", "old")
        prefs.flushDraftText()
        assertEquals("old draft durable", "old", prefs.getDraftText("profile-A", "ses-1"))
        prefs.clearDraftsForProfile("profile-A")
        assertEquals("old draft cleared", "", prefs.getDraftText("profile-A", "ses-1"))

        // The profile is re-selected / re-created and the user types a new
        // draft. setDraftText lifts the barrier; the new draft MUST persist.
        prefs.setDraftText("profile-A", "ses-1", "new-draft")
        advanceTimeBy(SessionPrefs.DEBOUNCE_MS)
        runCurrent()
        assertEquals(
            "new draft persists after barrier lift",
            "new-draft",
            prefs.getDraftText("profile-A", "ses-1"),
        )
    }

    @Test
    fun `clearDraftsForProfile does not affect a different profile's pending draft`() = testScope.runTest {
        // Two profiles' drafts pending independently.
        prefs.setDraftText("profile-A", "ses-1", "a-draft")
        prefs.setDraftText("profile-B", "ses-1", "b-draft")
        assertEquals(2, prefs.peekAllPendingDrafts().size)

        // Clear ONLY profile-A.
        prefs.clearDraftsForProfile("profile-A")

        // A's pending gone; B's pending UNTOUCHED.
        assertNull("A pending removed", prefs.peekPendingDraft("profile-A", "ses-1"))
        assertEquals(
            "B pending survives clear of A",
            "b-draft",
            prefs.peekPendingDraft("profile-B", "ses-1")?.text,
        )

        // Fire the debounce. B's write-back fires + persists; A's was cancelled.
        advanceTimeBy(SessionPrefs.DEBOUNCE_MS)
        runCurrent()
        assertEquals("A's draft not resurrected", "", prefs.getDraftText("profile-A", "ses-1"))
        assertEquals(
            "B's draft persists (clear of A did not touch it)",
            "b-draft",
            prefs.getDraftText("profile-B", "ses-1"),
        )
    }

    // ─── reflection helper for the blocker-C barrier re-check ──────────────

    /**
     * Invokes the private [SessionPrefs.performDraftWrite] directly, simulating
     * a claimed write-back job whose mutation was removed from pendingDrafts
     * (under debounceLock) before [SessionPrefs.clearDraftsForProfile] ran.
     * Used to deterministically test the barrier re-check without trying to
     * position a coroutine between its claim and its persistLock RMW (which
     * StandardTestDispatcher virtual time can't do — the body is synchronous).
     */
    private fun invokePerformDraftWrite(profileId: String, sessionId: String, text: String) {
        val method = SessionPrefs::class.java.getDeclaredMethod(
            "performDraftWrite",
            String::class.java, String::class.java, String::class.java,
        )
        method.isAccessible = true
        method.invoke(prefs, profileId, sessionId, text)
    }
}
