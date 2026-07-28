package cn.vectory.ocdroid.ui.shell

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §unified-nav A8 (instrumented skeletons): NavHost integration tests for the
 * unified navigation-state refactor (items 6, 8, 10-A). These SKELETONS define
 * the test matrix the orchestrator fills in + runs on the emulator. Each test
 * drives a real Compose NavHost via [createComposeRule] and asserts on the
 * NavController back-stack + the store's nav/chat slices.
 *
 * The scenarios cover the EXPLICIT GATING the spec calls out:
 *  - item-6 two-round repro (new session → BACK → "+" again → must navigate).
 *  - item-8 stale-mirror (server popup → Settings when mirror already "settings").
 *  - backToHome no double Sessions (popBackStack + requestNavigate → single
 *    Sessions entry, not a duplicate push).
 *  - observer no loop (passive mirror reconciliation does not feed the syncer).
 *  - predictive-back cancel (destination unchanged → no mirror/closeDetail/
 *    token change) & commit (mirror Sessions, token+1 once, content cleared).
 *  - parent→child→parent (pop-restore, no closeDetail).
 *  - chat preview open/close (no closeDetail).
 *  - Files/Git/Settings back, nested-Settings back one level.
 *  - materialization shows body (not ChatEmptyState).
 *  - attachment-only first send, /command first send, materialize-failure rollback.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedNavHostInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── Item 6: new session → BACK → "+" again ──────────────────────────────

    @Test
    fun item6_newSession_back_plusAgain_navigatesToChat() {
        // TODO(orchestrator): wire AppShell with a mock repository. Create a
        // draft session, verify the bare "chat" composable shows. Press system
        // BACK → destination listener reconciles mirror to Sessions. Tap "+"
        // again → requestNavigate(Chat) bumps navEpoch → synchronizer re-fires
        // → bare "chat" composable shows (not stranded on Sessions).
    }

    @Test
    fun item6_twoRoundRepro_secondPlusNavigatesEvenWhenMirrorAlreadyChat() {
        // TODO(orchestrator): two full rounds of (draft → BACK → "+") verify
        // each round re-enters the chat composable (navEpoch bumped each time).
    }

    // ── Item 8: server popup → Settings (stale mirror) ─────────────────────

    @Test
    fun item8_staleMirror_requestNavigateSettings_firesSynchronizer() {
        // TODO(orchestrator): set navState.lastRoute = "settings" (stale),
        // current NavController destination = Sessions. Call requestNavigate(
        // Settings) → navEpoch bumps → synchronizer re-fires → navigates to
        // Settings (not a no-op).
    }

    // ── backToHome: no double Sessions ──────────────────────────────────────

    @Test
    fun backToHome_doesNotPushDuplicateSessions() {
        // TODO(orchestrator): from chat/{sessionId}, call backToHome(). Verify
        // the back stack has exactly ONE Sessions entry (popBackStack + syncer's
        // alreadyThere guard prevents a double-push).
    }

    // ── Destination observer: no feedback loop ──────────────────────────────

    @Test
    fun observer_passiveMirrorReconciliation_doesNotFeedSyncerLoop() {
        // TODO(orchestrator): navigate to chat/{id}, system BACK → Sessions.
        // The listener calls setLastRoute(Sessions) (passive, no epoch bump).
        // Verify the syncer does NOT re-navigate (alreadyThere guard no-ops)
        // and no infinite loop / stack overflow occurs.
    }

    // ── Predictive back ─────────────────────────────────────────────────────

    @Test
    fun predictiveBack_cancel_doesNotChangeMirrorOrTokenOrContent() {
        // TODO(orchestrator): on chat/{sessionId}, start a predictive back
        // gesture but CANCEL it (destination unchanged). Verify: no
        // closeDetail() fired, chatRouteInstance unchanged, content NOT
        // cleared, navState.lastRoute unchanged.
    }

    @Test
    fun predictiveBack_commit_clearsContentAndBumpsTokenOnce() {
        // TODO(orchestrator): on chat/{sessionId}, complete a predictive back
        // gesture (committed pop to Sessions). Verify: closeDetail() fired
        // exactly once (token+1), content cleared, mirror reconciled to
        // Sessions, no duplicate Sessions entry.
    }

    // ── chat→chat transitions: no closeDetail ───────────────────────────────

    @Test
    fun parentToChild_doesNotCallCloseDetail() {
        // TODO(orchestrator): openSubAgent child push (chat/parent →
        // chat/child). Verify closeDetail NOT called (token unchanged),
        // parent's SavedStateHandle preserved.
    }

    @Test
    fun childToParent_popRestore_doesNotCallCloseDetail() {
        // TODO(orchestrator): pop from chat/child back to chat/parent.
        // Verify closeDetail NOT called, parent entry restored (not a new
        // push), checkpoint consumed.
    }

    @Test
    fun chatPreviewOpenClose_doesNotCallCloseDetail() {
        // TODO(orchestrator): from chat/{id}, open chat/preview, close it.
        // Verify closeDetail NOT called on either transition.
    }

    // ── Spoke back navigation ───────────────────────────────────────────────

    @Test
    fun filesBack_returnsToSessions() {
        // TODO(orchestrator): navigate to files/{workdir}, system BACK →
        // Sessions via backToHome.
    }

    @Test
    fun gitBack_returnsToSessions() {
        // TODO(orchestrator): navigate to git/{session}, system BACK → Sessions.
    }

    @Test
    fun settingsBack_returnsToSessions() {
        // TODO(orchestrator): navigate to settings, system BACK → Sessions.
    }

    @Test
    fun nestedSettingsBack_popsOneLevelToSettings() {
        // TODO(orchestrator): navigate to settings/hosts, system BACK → settings
        // (NOT Sessions — nested settings pops one level).
    }

    // ── Item 10-A: materialization shows body ───────────────────────────────

    @Test
    fun materialization_showsBody_notChatEmptyState() {
        // TODO(orchestrator): create a draft session, type a message, SEND.
        // Verify the chat body shows the sent message immediately (NOT
        // ChatEmptyState / "connecting"). The staged-route bridge renders the
        // materialized session's detail during the one-frame gap.
    }

    @Test
    fun attachmentOnlyFirstSend_materializesAndShowsBody() {
        // TODO(orchestrator): draft session, attach an image (no text), SEND.
        // Verify the session materializes + the body shows (attachment-only
        // first send path).
    }

    @Test
    fun commandFirstSend_materializesAndShowsBody() {
        // TODO(orchestrator): draft session, type /compact, execute. Verify the
        // session materializes + the body shows (command-first-send path).
    }

    @Test
    fun materializeFailure_rollsBackDraftWorkdir() {
        // TODO(orchestrator): stub createSession to fail. Draft session, type a
        // message, SEND. Verify draftWorkdir restored (ownership guard), error
        // UiEvent emitted, no route hijack.
    }
}
