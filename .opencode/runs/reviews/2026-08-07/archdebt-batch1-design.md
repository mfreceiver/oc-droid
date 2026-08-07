# ocdroid Architecture-Debt Batch 1 — Design SSOT (Items 14, 16, 17)

- **Date**: 2026-08-07
- **Head OID**: `dad3b3b2` (= origin/main = tag v0.21.4)
- **Scope**: architecture-debt items **14** (Repository 收敛), **16** (lastNavPage migration + delete), **17** (ProcessStatusPoller 去留)
- **Batch**: 1 of 2. Batch 2 (item 13 DI Wave2.2, item 15 God-class split) is **out of scope** — this design makes no structural changes to AppCore beyond item 17's param rename, and does not touch TokenStreamCoordinator/ChatScaffold.
- **Verification basis**: all file:line references below were re-verified against `dad3b3b2` by reading the actual code (not the stale aeb6e67 handoff).

---

## 1. Executive summary

| Item | Decision (one line) |
|---|---|
| **14** | Narrow the chat render chain `ChatViewModel → ChatMessageContent → MessageCard → MessageRow → PartView` from the concrete `OpenCodeRepository` to the existing `FileVcsRepository` seam — the leaf (`TextPart`) already wants it; the only real consumer is markdown image resolution, which is mode-agnostic. |
| **16** | A prefs migration read **does exist** (`NavigationPrefs.lastRoute` getter reads `KEY_LAST_NAV_PAGE` on first read) → **keep the persisted key as a read-only migration source**; delete the in-memory `NavState.lastNavPage`, `OrchestratorViewModel.setLastNavPage`, `SettingsManager.lastNavPage`, `NavigationPrefs.lastNavPage`, and `NavRoute.legacyPage`/`fromLegacyPage`. |
| **17** | The handoff premise is **stale in both directions** (see §5.1 — the fan-out/backoff/retry cycle is a *closed circuit with no production entry point*, doubly inert under the `slimPerSessionStatusEndpointAvailable=false` gate). Decision: **(B) shrink-and-rename** — delete the dead 30s loop machinery, keep the backoff + single-flight-retry seam (the documented re-enablement vector), rename `ProcessStatusPoller` → `SlimFanOutRetryScheduler`, extract backoff constants to `SlimFanOutBackoffPolicy`. |

---

## 2. slim/standard mode boundary investigation (mandatory pre-work)

### 2.1 The mode flag stack (single source → derived surfaces)

```
HostConfig._slim (HostConfig.kt:57, per-profile user toggle 省流模式;
                  exposed as HostProfile.slimEnabled in the editor UI —
                  HostProfileEditorDialog.kt:143/446/623, MtlsDialogCallBuilders.kt:125/147/160;
                  orthogonal to mTLS, which has its own cert triplet)
        │  (configure succeeds → ServerCompatProfile.updateSlimapi)
        ▼
ServerCompatProfile.slimConnection (ServerCompatProfile.kt:76, @Volatile;
                  single managed write point :103; = "最近一次成功 configure 后的 live mode")
        │
        ├─► ConnectionGateway (gateway/ConnectionGateway.kt:60-64)
        │       supportsWatermarkResync / supportsTokenStreamResync / usesSlimStatusFanOut
        │       — all ≡ slimConnection
        ├─► OpenCodeRepository forwarders (OpenCodeRepository.kt:441/446/451)
        │       usesSlimStatusFanOut / supportsBulkSessionTree / supportsGlobalQuestionFetch
        └─► ConnectionState.isSlimActive (ConnectionState.kt:152) — UI mirror, written by
                ConnectionViewModel.kt:214/228 and ConnectionHealthProbe.kt:444/609;
                consumed by SessionsScreen.kt:365, ServerStatusIconButton.kt:81
```

`tokenStreamEnabled` is derived (`ServerCompatProfile.kt:121: get() = slimConnection`). A secondary flag `slimPerSessionStatusEndpointAvailable` (default **false** under lite-v2-dev, StreamingModule.kt:129) gates the per-session status fan-out independently — important for item 17.

### 2.2 Where the boundary runs, per layer

| Layer | Slim path | Standard path | Branch point |
|---|---|---|---|
| data/repository | gateways branch on `serverCompatProfile.slimConnection` (MessageGateway.kt:33/54/72, SessionGateway.kt:38/45/74, CatalogGateway.kt:56/81; InteractionGateway.kt:187 gates on slim + supportsSlimQuestions) | same gateways, else-branch | per-method `if (slimConnection)` |
| service (SSE/status) | slim digest relay → `SessionSyncCoordinator.handleSessionDigest` with `EntryOrigin.SSE_SLIM` (SessionSyncCoordinator.kt:267); slim bulk status `getSlimapiSessionsStatus`; `SlimStatusFanOut` per-session sweep (gated) | `LegacySseHandler.kt:155` with `EntryOrigin.SSE_LEGACY`; legacy `/session/status` + `/api/session/active` | `EntryOrigin.SSE_LEGACY/SSE_SLIM` (AuthorityState.kt:197-198) + lex monotonic guard in AuthorityReducer.kt:222; SseDispatchHost.kt:155-156 |
| ui (status) | `StatusPollOrchestrator.launchLoadSessionStatus` — slim SWEEP short-circuits to a no-op when the SSE digest relay is effective (transport-grounded predicate, StatusPollOrchestrator.kt:95-99/147-153); falls through to slim REST bulk on SSE loss (:170-173 → `launchLoadSessionStatusSlim`) | same entry, legacy REST fan-out (:174+) | `repository.usesSlimStatusFanOut` (:147, :170); 4s foreground sweep owned by UnreadSoakController (`ACTIVE_REFRESH_INTERVAL_MS = 4_000L`, UnreadSoakController.kt:173) |
| ui (interactions) | `OrchestratorViewModel.respondPermission/replyQuestion/rejectQuestion` with non-null `routeToken` → `respondSlimapiPermission/replySlimapiQuestion/rejectSlimapiQuestion` (sidecar re-injects directory from HMAC token) | `routeToken == null` → legacy `respondPermission/replyQuestion/rejectQuestion` with resolved directory | nullable `routeToken` param (OrchestratorViewModel.kt:317-436) |
| ui (fan-out consumer) | `SessionSyncCoordinator.applySlimStatusFanOutSummary` (:358) → `StatusFanOutApplier` (Main.immediate-imprisoned, StatusFanOutApplier.kt:19-20) → `EvictSession` per missing sid + `RetryQueued`/`RetryFired` authority ops + `RequestPollerBackoff`/`ResetPollerBackoff` effects (:87-112) | never reached (runner returns null in legacy mode, StreamingModule.kt:117) | `repository.usesSlimStatusFanOut` gate inside the runner lambda |
| service (fan-out runner) | `SlimStatusFanOut` (service/status/SlimStatusFanOut.kt:86) — slim-mode ONLY, per-sid `GET /slimapi/sessions/{sid}/status`, Semaphore(4), fake-idle cross-check, `StatusFanOutSummary` with `sweepStartEpoch` causal fence | never invoked | constructed + gated in `StreamingModule.provideProcessStatusPoller` (:96-155) |

### 2.3 Item ↔ boundary interaction summary

- **Item 14**: entirely *below* the boundary. `TextPart` → `ResolvedMarkdownText` → `FileVcsRepository.getFileContent` → `FileVcsGateway.getFileContent` → `api.getFileContent` (FileVcsGateway.kt:37-38) — a plain REST call with **no slim branching** (verified: no `slimConnection` reference in FileVcsGateway). Slim and standard render text parts identically. Mode-agnostic; zero boundary risk.
- **Item 16**: navigation persistence — no interaction with the boundary at all.
- **Item 17**: the poller/scheduler sits *astride* the boundary: the class itself is mode-agnostic plumbing (identity + backoff + retry), but its entire purpose is slim-mode fan-out (the runner is the only effect-producing path, and it is slim-gated). The design in §5 keeps all slim gating in the `StreamingModule` runner lambda (unchanged) and keeps the scheduler mode-agnostic — boundary preserved.

### 2.4 Boundary follow-ups discovered (out of Batch-1 scope — see §7)

1. `StatusAggregatorInput.refresh` / `markRequestFailed` public entries have exactly one production caller: the poller's `runRefresh`. After item 17 they become production-caller-less (the SSE-driven `applySseStatus` + `stateAtNow` stay live).
2. The three `routeToken`-nullable if/else branches in OrchestratorViewModel duplicate the slim/standard fork at VM level; could be pushed behind `InteractionRepository` (which already capability-gates, InteractionRepository.kt:67-68).
3. `StatusPollOrchestrator`'s concrete `OpenCodeRepository` param is a documented deferred narrowing (kdoc §Wave2.3 nit#2, StatusPollOrchestrator.kt:31-41).

---

## 3. Item 14 — Repository 收敛 (UI depends on concrete OpenCodeRepository)

### 3.1 Decision

Narrow the pass-through chain to `FileVcsRepository`. Verified chain (current lines, all confirmed by reading):

| Hop | File:line | Current | After |
|---|---|---|---|
| VM exposure | ChatViewModel.kt:113-115 | `val repository: OpenCodeRepository get() = core.repository` | `val fileVcsRepository: FileVcsRepository get() = core.repository` |
| local val | ChatMessageContent.kt:139 (+ import :47) | `val repository: OpenCodeRepository = chatVM.repository` | `val fileVcsRepository: FileVcsRepository = chatVM.fileVcsRepository` |
| pass | ChatMessageContent.kt:329 | `repository = repository` | `repository = fileVcsRepository` |
| param | MessageCard.kt:160 (+ kdoc :124) | `repository: cn.vectory.ocdroid.data.repository.OpenCodeRepository` | `repository: FileVcsRepository` (add import, drop FQN) |
| pass | MessageCard.kt:261 | `repository = repository` | unchanged |
| param | ChatMessageRow.kt:109 (+ import :46) | `repository: OpenCodeRepository` | `repository: FileVcsRepository` |
| pass | ChatMessageRow.kt:277 | `repository = repository` | unchanged |
| param | ChatMessageRow.kt:678 (PartView) | `repository: OpenCodeRepository` | `repository: FileVcsRepository` |
| pass | ChatMessageRow.kt:746 | `repository = repository` | unchanged |
| leaf | ChatTextParts.kt:154 | `repository: FileVcsRepository? = null` | **no change** (already the narrow seam) |

`OpenCodeRepository` already implements `FileVcsRepository` (OpenCodeRepository.kt:93), and `RepositoryInterfaceModule.kt:36` binds it — no DI changes. `TextPart` only consumes `getFileContent` (ChatTextParts.kt:287), which is in the seam (FileVcsRepository.kt:17).

### 3.2 Exact signatures

```kotlin
// ChatViewModel.kt:113-115 (replacement)
/** Narrow FileVcs seam exposed for the chat render chain. Sole consumer:
 *  markdown image resolution in TextPart (getFileContent). The concrete
 *  OpenCodeRepository stays internal to this VM (message ops etc.). */
val fileVcsRepository: FileVcsRepository get() = core.repository
```

```kotlin
// MessageCard.kt:160 / ChatMessageRow.kt:109 / ChatMessageRow.kt:678
repository: FileVcsRepository,   // was OpenCodeRepository; still non-null, no default
```

Note on `ChatViewModel`: it legitimately keeps using `core.repository` (concrete) internally for message ops (ChatViewModel.kt:142/186/241/468/…). That is **not** leakage — the VM's dependency is `AppCore`; what we remove is the *concrete type from the VM's public surface that exists only to serve a composable chain*. The narrow property means a composable can no longer reach e.g. `abortSession` through the render chain.

### 3.3 Migration steps (ordered)

1. ChatViewModel.kt: rename + retype the property (:113-115); update import if `OpenCodeRepository` import becomes unused (check — other `core.repository` uses don't need the import since it's `AppCore`'s member type… verify: the file may still need it for explicit types elsewhere; fixer confirms).
2. ChatMessageContent.kt: swap import (:47), retype local (:139), rename pass (:329).
3. MessageCard.kt: retype param (:160), add `import cn.vectory.ocdroid.data.repository.FileVcsRepository`, update kdoc (:124).
4. ChatMessageRow.kt: swap import (:46), retype both params (:109, :678).
5. ChatTextParts.kt: **no change**.
6. Test: ChatViewModelPassThroughTest.kt:729 — `assertEquals(core.repository, vm.repository)` → `assertEquals(core.repository, vm.fileVcsRepository)`; check whether the `OpenCodeRepository` import (:8) is still needed elsewhere in the file.
7. `./scripts/check.sh`.

### 3.4 Test plan

- No new behavior → no new tests required. Existing render/compose coverage is unaffected (params are internal composables).
- Updated: ChatViewModelPassThroughTest.kt:729 (above).
- Compile-time proof is the primary gate: if any call site still needs the concrete type, the build fails — there are none (verified: `repository` in MessageCard/MessageRow/PartView is pass-through only).

### 3.5 Risks

- **Preview/test composables** constructing MessageRow/PartView directly: grep shows no out-of-file callers of `MessageCard(` / `MessageRow(` / `PartView(` (only ChatMessageContent.kt:324 → MessageCard; in-file hops otherwise). Risk: nil.
- **PartExpandState.kt** imports `OpenCodeRepository` (kdoc-only references at :158/:193; the constructor takes `MessageRepository` at :204). Not part of this chain — left untouched (optional kdoc cleanup, §7).

### 3.6 Abstraction-layering justification

Constraint 1 satisfied: the removed concrete dependency (`OpenCodeRepository` in the render chain) does not leak back — the new seam is the pre-existing `FileVcsRepository` interface already bound in DI, not a new wrapper around the concrete class. Single responsibility: the render chain needs exactly file-content reads; the seam's 10 methods are all file/VCS reads (no session/message mutation surface). Testability: composables become testable against a `FileVcsRepository` fake without mocking the 100+-method concrete repository.

### 3.7 slim/standard impact

None. Verified `getFileContent` has no slim branch (FileVcsGateway.kt:37-38). Both modes render text parts identically; markdown image resolution uses the same REST endpoint in both modes.

---

## 4. Item 16 — lastNavPage migration + delete

### 4.1 Migration-completion analysis (the critical question)

**A prefs migration read exists.** `NavigationPrefs.lastRoute` getter (NavigationPrefs.kt:53-59): when `KEY_LAST_ROUTE` is absent, it reads `KEY_LAST_NAV_PAGE` (0/1/2 → chat/sessions/settings) and writes the migrated route — a one-shot first-read-and-write migration. `KEY_LAST_NAV_PAGE` therefore **cannot be deleted** without breaking the migration contract for any install that has `last_nav_page` but not `last_route`.

Two additional findings that shape the decision:

1. **The migration never fires in production today**: `settingsManager.lastRoute`'s *getter* has no production callers (all production readers use the in-memory `navFlow.value.lastRoute`; every `settingsManager.lastRoute` use in main is a *write* — OrchestratorViewModel.kt:89/115/139/245, DraftSessionOrchestrator.kt:122, RefreshOrchestrator.kt:238, SessionViewModel.kt:308, SessionMutationActions.kt:197/285). Cold start does not restore the persisted route (`restoreLastRoute()` was removed in T7 — NavState.kt:12-16 kdoc). So the persisted `last_route` is currently write-only in production and the legacy key is dormant.
2. **Decision**: **keep `KEY_LAST_NAV_PAGE` + the migration branch in the `lastRoute` getter as a read-only migration source** (option "delete everything except the migration read"). Rationale: deleting the key is irreversible against unknown install state; keeping it costs one constant + one `when` branch, and if route-restore is ever re-wired the migration fires correctly. The alternative ("confirm migration complete and delete the whole chain") is unprovable — the migration has literally never executed in production, so no install can be confirmed migrated by telemetry.

### 4.2 Exact deletion list

**Production:**

| # | File:line | Delete | Keep |
|---|---|---|---|
| 1 | NavState.kt:53-55 | `@Deprecated val lastNavPage: Int = NavRoute.Sessions.legacyPage` | rest of NavState |
| 2 | NavState.kt:3-10 (kdoc) | lastNavPage mentions | — (rewrite kdoc) |
| 3 | OrchestratorViewModel.kt:83-92 | `fun setLastNavPage(page: Int)` (verified: **zero production callers**) | — |
| 4 | OrchestratorViewModel.kt:21 (kdoc) | `([setLastNavPage])` mention | — |
| 5 | SettingsManager.kt:162-164 | `var lastNavPage: Int` accessor | — |
| 6 | NavigationPrefs.kt:29-31 | `var lastNavPage: Int` property | — |
| 7 | NavigationPrefs.kt:24-28, 34-40, 8-11 (kdoc) | update: legacy key is now a **read-only migration source** with no public accessor | `KEY_LAST_NAV_PAGE` (:68) + the migration read in `lastRoute` getter (:53-59) — **untouched** |
| 8 | NavRoute.kt:7 | `legacyPage: Int` enum param (becomes dead: its only main-code consumer was `setLastNavPage`; `Files`/`Git` share bogus value 0) | — |
| 9 | NavRoute.kt (companion) | `fromLegacyPage` (only main caller was setLastNavPage:88; NavigationPrefs migration does its own `when`, :53-57) | — |

**Tests:**

| # | File:line | Action |
|---|---|---|
| 10 | OrchestratorViewModelPassThroughTest.kt:2 (header), :37 (kdoc), :50-97 (4 tests) | delete the `setLastNavPage` section entirely (clamps-below-zero / clamps-above-two / writes-through / same-value-no-op) |
| 11 | AppStateSlicesTest.kt:2 (header), :186-200 (2 tests) | delete `NavState default lastNavPage…` + copy test |
| 12 | SessionViewModelTest.kt:879; SessionViewModelPassThroughTest.kt:230,236,249,266,354; TokenStreamCoordinatorIdempotencyTest.kt:277; B4RouteTransitionStateMachineTest.kt:31 | drop the `lastNavPage = …` argument from `mutateNav { it.copy(lastRoute = …, lastNavPage = …) }` calls (keep `lastRoute`) |
| 13 | NavRouteTest.kt:95-98 | delete the 4 `fromLegacyPage` assertions |
| 14 | SettingsManagerTest.kt:391-404 | delete `last nav page round trip and clamping` + `last nav page default is zero` |
| 15 | SettingsManagerTest.kt:509 | replace seed `settings.lastNavPage = 2` with `rawEncryptedPrefs().edit().putInt("last_nav_page", 2).commit()` (the wipe test must still seed a wipeable nav key) |
| 16 | SettingsManagerTest.kt (new) | **ADD** the missing migration tests (the first-read migration is currently *untested* — verified: no test seeds absent-`last_route` + present-`last_nav_page`): seed `last_nav_page` ∈ {0,1,2} with no `last_route` → `settings.lastRoute` returns chat/sessions/settings **and** persists `last_route`; plus unknown-value → chat |

### 4.3 Ordered steps

1. Delete `OrchestratorViewModel.setLastNavPage` + kdoc touch-ups (#3, #4).
2. Delete `NavState.lastNavPage` + kdoc (#1, #2). Build breaks at test call sites (#12) — fix them in the same commit.
3. Delete `SettingsManager.lastNavPage` (#5) → fix SettingsManagerTest (#14, #15, #16).
4. Delete `NavigationPrefs.lastNavPage` property + kdoc rewrite (#6, #7).
5. Delete `NavRoute.legacyPage` + `fromLegacyPage` (#8, #9) → fix NavRouteTest (#13).
6. `./scripts/check.sh`.

### 4.4 Test plan

- Existing nav tests that must stay green: UnifiedNavTest (requestNavigate/setLastRoute epoch semantics), OrchestratorViewModelPassThroughTest setLastRoute section (:102+), SessionViewModelPassThroughTest force-home tests (:230+ — note :236 documents "lastNavPage is a deprecated mirror; force-home no longer co-writes it" — after removal this comment can be dropped with the argument).
- New migration tests (#16) pin the retained migration read.
- SettingsManagerTest clearAllLocalData wipe test still validates `last_nav_page` is wiped (it iterates non-preserved keys — unchanged behavior).

### 4.5 Risks

- **Behavioral**: nil for navigation (authority was already `lastRoute`; `setLastNavPage` had no callers). The only retained behavior is the dormant migration read — pinned by new tests.
- **NavRoute enum constructor change** (`Chat("chat", 0)` → `Chat("chat")`): mechanical; all call sites are in-file plus NavRouteTest.
- Do **not** touch `lastRoute` persistence itself (see §7 — it's write-only in production; that's a separate future decision).

### 4.6 slim/standard impact

None. Navigation persistence is mode-independent.

---

## 5. Item 17 — ProcessStatusPoller 去留

### 5.1 The liveness finding (corrected — evidence-cited)

The handoff said "inert"; the orchestrator's re-survey said "partially live — the retry is load-bearing". **Both are wrong at `dad3b3b2`.** The truth is more nuanced and must drive the design:

**Fact 1 — the 30s loop is dead.** `startAndAwaitFirstPoll` / `ensureRunning` / `startLoop` have zero production callers. The only former caller (`ConnectionCoordinator` foreground→background `ensureRunning`) was removed in Phase 1 后台驻留移除 (ConnectionCoordinator.kt:354-364 comment; the init block at :365-383 now only disconnects SSE). Confirmed by grep: no main-source call sites.

**Fact 2 — the "live" backoff/retry surface is wired but *unreachable*: the fan-out cycle is a closed circuit with no production entry point.** Trace every edge (all verified by grep over `app/src/main`):

- `runSlimFanOut` (ProcessStatusPoller.kt:400) is called from exactly two places: `startLoop` (:242/:262 — dead per Fact 1) and `requestSlimFanOutRetry` (:452).
- `requestSlimFanOutRetry` (:433) is called from exactly one place: AppCore's `RequestPollerBackoff` handler (AppCore.kt:1141).
- `ControllerEffect.RequestPollerBackoff` is emitted from exactly one place: `StatusFanOutApplier.applySlimStatusFanOutSummary` (StatusFanOutApplier.kt:109).
- `applySlimStatusFanOutSummary` is called from exactly one place: the poller's `slimFanOutSummarySink` (StreamingModule.kt:163-165).
- ∴ The only way to obtain a `StatusFanOutSummary` is a sweep; the only sweep triggers are the (dead) loop and the retry — which itself requires a prior summary. **Circular: no entry.** The `resetBackoff` path (AppCore.kt:1148) is equally unreachable (same single emitter, StatusFanOutApplier.kt:111).

**Fact 3 — even if entered, the runner is gated off.** `StreamingModule.kt:129`: `if (!serverCompatProfile.slimPerSessionStatusEndpointAvailable) return@runner null` — the flag defaults false under lite-v2-dev (per-session endpoint delegates to bulk; the P0 B-slim-storm-fix deliberately short-circuits "until the per-session endpoint is independently available again"). `SlimFanOutRunnerGateTest` pins both arms.

**Conclusion**: at current head the poller is **fully inert in production, twice over** (no entry trigger + runner gate). The AppCore kdoc's "inert-by-design" claim (AppCore.kt:215-225) is therefore *accidentally correct* about the loop but *wrong in mechanism* — it claims the backoff API is exercised, when in fact the whole effect circuit can never fire. Neither the handoff's "remove everything" nor the orchestrator's "the retry is load-bearing" survives contact with the code.

### 5.2 Decision: (B) shrink-and-rename — with the corrected rationale

**Chosen**: remove the dead loop machinery; keep the backoff + single-flight-retry seam; rename `ProcessStatusPoller` → **`SlimFanOutRetryScheduler`**; extract the shared backoff constants into **`SlimFanOutBackoffPolicy`**.

Why not (A) keep-as-is + fix comments: retains ~150 lines of dead loop machinery (`startLoop`/`ensureRunning`/`startAndAwaitFirstPoll`/`runRefresh`/`stop` + `loopJob`/`runningIdentity`/`generation`/`mutex`) whose design premise (background 30s polling) was **deliberately rejected** in Phase 1 — it is not a deferred feature, it is a removed one. Violates constraint 1 (dead code carrying a misleading "TimedRefreshWithSlimFanOut" identity).

Why not (C) full delete of the whole T13 chain (poller + effects + applier arms + DI + tests): the chain is a **deliberately preserved, heavily unit-tested re-enablement seam**. StreamingModule.kt:119-129 documents the per-session fan-out as temporarily short-circuited pending endpoint availability; AppCore.kt:218-221 documents the retained seam for a future foreground-degraded-polling path; `StatusFanOutApplier`'s `RetryQueued`/`RetryFired` authority ops (:88-108) share the backoff constants with the retry queue (AuthorityReducer.kt:799-852). Full deletion would be a *feature-removal decision* disguised as debt cleanup — that call belongs to the product owner, not this batch. (If the per-session fan-out is judged permanently dead, the whole chain — `RequestPollerBackoff`/`ResetPollerBackoff` effects, AppCore branches, applier backoff arm, retry-queue ops, SlimStatusFanOut, ~5 test files — becomes a dedicated future batch; see §7.)

Why (B) is safe despite Fact 2: since the circuit currently has **no entry**, removing the loop cannot change production behavior (byte-identical), and the preserved seam (`scheduleBackoff`/`resetBackoff`/`requestSlimFanOutRetry` + runner + sink + effect routing + retry-queue constants) is exactly the backbone a future trigger needs. The future entry point post-(B) is "call `requestSlimFanOutRetry(0)` from the new degraded-foreground path" — strictly simpler than today's documented "re-wire `ensureRunning`".

### 5.3 Exact removal surface

**In `ProcessStatusPoller.kt` (deleted with the class):**

- Methods: `startAndAwaitFirstPoll` (:165-169), `ensureRunning` (:182-200), `startLoop` (:218-280), `stop` (:291-303), `runRefresh` (:370-379).
- Fields: `loopJob` (:115), `mutex` (:116), `generation` (:118), `runningIdentity` (:126-127).
- Constructor params (only used by the loop/refresh): `statusAggregatorInput` (:73), `statusAggregator` (:76), `clock` (:77).
- Constants: `DEFAULT_INTERVAL_MS` (:465) — dies with the loop. (`BACKOFF_MAX_MS`'s "equals DEFAULT_INTERVAL_MS" comment goes too.)
- `generation` **is dropped entirely** (it is read by `requestSlimFanOutRetry` at :435/:440/:447, but in production it can never change — only `startLoop`/`stop` bump it, and both are dead/uncalled — so removal is behavior-neutral; the real host-switch guard is the `identityStore.isCurrent` 3-point discipline, which is **kept verbatim**).

**Surviving → new class** `service/streaming/SlimFanOutRetryScheduler.kt` (same package, minimal churn; a move to `service/status` alongside `SlimStatusFanOut` is a valid optional follow-up, not required):

```kotlin
@Singleton
class SlimFanOutRetryScheduler internal constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val snapshotProvider: SessionSnapshotProvider,          // kept — retry re-reads snapshot (:445)
    private val identityStore: ConnectionIdentityStore,             // kept — 3-point identity discipline
    private val slimFanOutRunner:
        suspend (ConnectionIdentity, StatusSnapshot) -> StatusFanOutSummary? = { _, _ -> null },
    private val slimFanOutSummarySink: (StatusFanOutSummary) -> Unit = {},
) {
    private val stateLock = Any()
    private var backoffAttempt: Int = 0
    private var pendingBackoffMs: Long = 0L
    private var slimRetryJob: Job? = null
    private val slimFanOutMutex = Mutex()

    fun scheduleBackoff(jitter: Float = SlimFanOutBackoffPolicy.DEFAULT_BACKOFF_JITTER): Long
    fun resetBackoff()                 // keeps retry-job cancellation (:355-362 verbatim)
    fun currentBackoffDelayMs(): Long  // test/diagnostic accessor (:368)
    fun requestSlimFanOutRetry(delayMs: Long)   // :433-459 minus generation checks
    private suspend fun runSlimFanOut(identity: ConnectionIdentity, snapshot: StatusSnapshot)
        // :400-422 verbatim — the 3-point identity discipline + slimFanOutMutex + CE discipline
}
```

**New file** `service/status/SlimFanOutBackoffPolicy.kt` — the constants are shared by two layers (the scheduler AND `StatusFanOutApplier`'s per-sid `RetryQueued` nominal backoff, StatusFanOutApplier.kt:91-95), so they must not live on the scheduler companion (constraint 1: the applier needs the policy, not the scheduler):

```kotlin
object SlimFanOutBackoffPolicy {
    const val BACKOFF_BASE_MS = 200L
    const val BACKOFF_MAX_MS = 30_000L
    const val BACKOFF_MAX_SHIFT = 8
    const val DEFAULT_BACKOFF_JITTER: Float = Float.NaN

    /** Pure: exponential + jitter + cap. jitter in [-0.2,+0.2] (clamped);
     *  mirrors SseRecoveryPolicy.applyJitter (inlined to keep service/status
     *  free of a service.streaming import). */
    fun computeDelayMs(attempt: Int, jitter: Float): Long { … }
}
```

### 5.4 Call-site changes (exact)

| File:line | Change |
|---|---|
| AppCore.kt:198-227 | constructor param type → `SlimFanOutRetryScheduler`; **rewrite the kdoc** (the "Phase 1 inert-by-design" note is wrong — replace with an accurate statement: loop removed in Batch-1 item 17; the backoff/retry seam is retained as the documented re-enablement vector; the fan-out circuit currently has no production entry — see StreamingModule kdoc). Also fix the stale "SAME instance SessionStreamingService injects (the L3 background loop owner)" claim (:211-213) — `SessionStreamingService` no longer injects the poller (verified: no such injection exists) |
| AppCore.kt:1111-1150 | unchanged logic; rename receiver + kdoc refs (`processStatusPoller.scheduleBackoff()` → `slimFanOutRetryScheduler.scheduleBackoff()` at :1140; `requestSlimFanOutRetry` at :1141; `resetBackoff` at :1148) |
| StreamingModule.kt:64-170 | `ProcessStatusPollerModule` → `SlimFanOutRetrySchedulerModule`; `provideProcessStatusPoller` → `provideSlimFanOutRetryScheduler`; **drop** the `statusAggregatorInput` + `statusAggregator` params (:83-86); runner lambda (:115-155) and summary sink (:163-165) move **verbatim** (all slim gating stays here: `isCurrent` :116, `usesSlimStatusFanOut` :117, `slimPerSessionStatusEndpointAvailable` :129, `sweepStartEpoch` capture :137) |
| StatusFanOutApplier.kt:14, 91-95 | import `SlimFanOutBackoffPolicy`; constants refs `ProcessStatusPoller.BACKOFF_*` → `SlimFanOutBackoffPolicy.BACKOFF_*` |
| ControllerEffect.kt:217-234 | kdoc refs → new names |
| ConnectionCoordinator.kt:354-364 | comment: `ProcessStatusPoller.ensureRunning` → historical note (loop deleted in item 17) |
| TokenStreamCoordinator.kt:427 | stale comment ("ProcessStatusPoller keeps refreshing status") → correct or delete |
| AuthorityReducer.kt:249 | stale comment ("ProcessStatusPoller starts ONLY on foreground→…") → correct (optional but recommended) |
| SseRecoveryPolicy.kt:18-22 | kdoc: sole main-source consumer ref → `SlimFanOutRetryScheduler.scheduleBackoff` |
| SlimStatusFetchCache.kt:11-16, StatusAggregatorImpl.kt:225/383/516, SlimapiErrorCodes.kt:22, ServerCompatProfile.kt:187 | kdoc mentions of ProcessStatusPoller — comment-only touch-ups, lowest priority |

### 5.5 slim/standard impact analysis (the critical proof)

**Standard (legacy) mode — before and after**: runner returns null at the `usesSlimStatusFanOut` gate (StreamingModule.kt:117) → no HTTP, no summary, no sink, no effects. The scheduler is never exercised. **Identical before/after** (and identical to today, where the circuit never fires).

**Slim mode — before**: as proven in §5.1 (Facts 1-3), the fan-out/backoff/retry circuit **cannot execute**: no entry trigger (loop dead, retry requires a prior summary) and the runner short-circuits at the `slimPerSessionStatusEndpointAvailable` gate. Steady-state slim status flows through *other* live paths that item 17 does not touch: the SSE digest `status` relay (`SessionSyncCoordinator.handleSessionDigest` → `applySessionStatus`, SSE_SLIM origin), the slim cold-start bulk (`launchLoadSessionStatusSlim`), and the SSE-loss REST fall-through in `StatusPollOrchestrator` (:147-173). **After**: the same three live paths remain; the preserved seam is behaviorally identical (same backoff schedule, same single-flight semantics, same 3-point identity discipline, same epoch fence via `sweepStartEpoch`). ∴ **slim fan-out retry correctness is preserved by construction** — the kept code paths are verbatim moves; nothing that executes in production is altered.

**If the seam is re-enabled later** (per-session endpoint returns + a new trigger calls `requestSlimFanOutRetry(0)`): the full T13 contract is intact — sweep → summary (sweepStartEpoch) → epoch-fenced applier → EvictSession/RetryQueued/RetryFired → RequestPollerBackoff → bounded backoff → single-flight retry, with the fake-idle cross-check and Semaphore(4) in SlimStatusFanOut unchanged.

### 5.6 Risks

1. **Wrong-premise risk (mitigated)**: both prior surveys mis-stated liveness; §5.1's edge-trace is the evidence base. The fixer must re-run the four greps in §5.1 before cutting.
2. **Test churn is the real cost**: ~5 test files change (§5.7). The loop tests encode real single-flight/supersession *concepts* — but they test machinery whose design was rejected; deleting them is correct, not a coverage loss (the kept retry path's single-flight coverage migrates).
3. **`StatusAggregatorInput.refresh` becomes production-caller-less** (its only caller was `runRefresh`). It is NOT deleted in this batch (blast radius: StatusAggregatorImpl + StatusFetchService + SlimStatusFetchCache + StatusModule + 2 test files) — logged as follow-up F1 (§7).
4. **Batch-2 collision**: AppCore's constructor is touched here (param rename) and again in item 13 (Wave2.2 DI). Batch 1 lands first; Batch 2 rebases. No semantic conflict.

### 5.7 Test plan (exact file dispositions)

| Test file | Disposition |
|---|---|
| `service/streaming/ProcessStatusPollerTest.kt` (541 lines) | **Split**: loop tests (:61-298 — immediate refresh at t=0, stop-cancels-loop, single-flight restart, interval ticks, ensureRunning idempotency/stale-identity, `runningIdentityOf` reflection helper) → **DELETE**. Backoff tests (:351-541 — growth/cap/reset/jitter-clamp/default-sampler) → **MIGRATE** to new `SlimFanOutRetrySchedulerTest.kt`, constants from `SlimFanOutBackoffPolicy` |
| `service/streaming/SlimFanOutPollerWiringTest.kt` (588 lines) | **Rename** → `SlimFanOutRetryWiringTest.kt`. Tests 1-4 (immediate fan-out via `startAndAwaitFirstPoll`, per-tick fan-out, per-tick snapshot re-fetch — :77-330) → **DELETE** (they test the removed loop). Tests 5-6 (single-flight retry :391; `resetBackoff` cancels pending retry :424-472) → **MIGRATE** to drive the scheduler directly. Test 7 (:506-533, stale-identity rejection) → delete or rewrite against `requestSlimFanOutRetry` identity early-return (:434) |
| `service/streaming/SlimFanOutRunnerGateTest.kt` (221 lines) | **KEEP + migrate trigger**: both tests currently drive via `startAndAwaitFirstPoll` (:108); switch to `requestSlimFanOutRetry(0L)` + `runCurrent`/`advanceTimeBy` (identity bound first). The gate assertions (:112-113, gate-open twin) are unchanged — they pin the runner lambda, which moves verbatim |
| `ui/controller/StatusPollingDowngradeSeamsRegressionTest.kt` (94 lines) | **DELETE the file**. It freezes `ProcessStatusPoller.DEFAULT_INTERVAL_MS` ∈ [10s,30s] as the "slim SSE-loss fallback cadence" — a premise that is false at current head (the loop never runs; the actual SSE-loss fallback is StatusPollOrchestrator's transport-grounded 4s-sweep fall-through, already pinned by `StatusPollingDowngradeRegressionTest` Groups 1/4 per this file's own kdoc :31-37). Record the deletion rationale in the commit message |
| `ui/controller/RetryQueueWireTest.kt:354` | constant ref → `SlimFanOutBackoffPolicy.BACKOFF_MAX_MS` |
| `ui/AppCoreDispatcherTest.kt:395-445` | receiver rename (`processStatusPoller` → new name); the three dispatch tests (scheduleBackoff / resetBackoff / end-to-end cascade) stay — they pin the AppCore branch that is preserved |
| `MainViewModelTestBase.kt:68-70, 309-314`; `ForkSessionTest.kt:223-225` | mockk type rename |
| **New** `SlimFanOutRetrySchedulerTest.kt` | migrated backoff tests **plus**: (a) `requestSlimFanOutRetry` no-ops when `identityStore.currentIdentity` is null; (b) pending retry is dropped after host switch (identity re-check at fire time); (c) single-flight: second request cancels the first; (d) `resetBackoff` cancels a pending retry and re-bases the schedule |

### 5.8 Item-17 ordered steps

1. Add `SlimFanOutBackoffPolicy` (service/status).
2. Create `SlimFanOutRetryScheduler` (moved code, verbatim where marked); delete `ProcessStatusPoller.kt`.
3. Rewire `StreamingModule` (module + provider rename, drop 2 params, move lambdas).
4. Update `StatusFanOutApplier` constants import.
5. Update AppCore (ctor param, kdoc rewrite, dispatch receiver rename) + comment-only files.
6. Migrate/delete tests per §5.7.
7. `./scripts/check.sh` (+ targeted runs below).

---

## 6. Implementation ordering & write-domain analysis

**Write domains are disjoint in production code AND in test code** — all three items can be executed by independent fixers in parallel:

| Item | Production files | Test files |
|---|---|---|
| 14 | ChatViewModel.kt, ChatMessageContent.kt, MessageCard.kt, ChatMessageRow.kt | ChatViewModelPassThroughTest.kt |
| 16 | NavState.kt, NavRoute.kt, OrchestratorViewModel.kt, SettingsManager.kt, NavigationPrefs.kt | OrchestratorViewModelPassThroughTest.kt, AppStateSlicesTest.kt, SessionViewModelTest.kt, SessionViewModelPassThroughTest.kt, TokenStreamCoordinatorIdempotencyTest.kt, B4RouteTransitionStateMachineTest.kt, NavRouteTest.kt, SettingsManagerTest.kt |
| 17 | ProcessStatusPoller.kt (del), SlimFanOutRetryScheduler.kt (new), SlimFanOutBackoffPolicy.kt (new), StreamingModule.kt, AppCore.kt, StatusFanOutApplier.kt, ControllerEffect.kt (kdoc), ConnectionCoordinator.kt (comment), TokenStreamCoordinator.kt (comment), AuthorityReducer.kt (comment, optional), SseRecoveryPolicy.kt (kdoc) | ProcessStatusPollerTest.kt (split), SlimFanOutPollerWiringTest.kt (rename+migrate), SlimFanOutRunnerGateTest.kt (migrate), StatusPollingDowngradeSeamsRegressionTest.kt (delete), RetryQueueWireTest.kt, AppCoreDispatcherTest.kt, MainViewModelTestBase.kt, ForkSessionTest.kt |

**Zero overlapping files** between items. Suggested landing order if serialized: **16 → 14 → 17** (ascending risk/churn). If parallelized: three lanes, item 17 rebases last only if AppCore.kt drift appears (no other item touches it, so even that is unlikely).

---

## 7. Cross-item risks & out-of-scope notes

**Batch 2 boundary (do not cross)**:
- AppCore.kt is touched by item 17 **only** at the poller param (:198-227), the dispatch branch (:1134-1150), and their kdocs. No AppCore structural refactor (that is item 13/Wave2.2, runs after Batch 1 — it must rebase over the renamed param).
- No TokenStreamCoordinator/ChatScaffold changes (item 15). Item 17's only TokenStreamCoordinator touch is a one-line stale comment (:427).

**Follow-ups discovered (NOT in this batch)**:
- **F1** (from item 17): `StatusAggregatorInput.refresh` / `markRequestFailed` become production-caller-less. A future cleanup can retire them (+ `StatusFetchService`/`SlimStatusFetchCache`/`StatusModule` wiring + `StatusAggregatorImplTest`/`SlimStatusFetchCacheTest`). Deliberately excluded: blast radius is its own batch.
- **F2** (from item 16): persisted `settingsManager.lastRoute` is **write-only in production** (no getter callers outside tests; cold start never restores it). Decide in a future batch: wire a cold-start restore, or delete the persistence write points. Until then, `KEY_LAST_NAV_PAGE` must stay (the migration read is the only path that would ever consume it).
- **F3** (from item 17): the preserved slim fan-out seam currently has **no production entry trigger** (§5.1 Fact 2). If/when `slimPerSessionStatusEndpointAvailable` flips true, an entry trigger (e.g. degraded-foreground sweep calling `requestSlimFanOutRetry(0)`) must be added for the seam to do anything. Conversely, if the per-session fan-out is judged permanently dead, the *whole* T13 chain (effects, applier arms, retry-queue ops, SlimStatusFanOut, scheduler, ~5 test files) is a candidate for a dedicated deletion batch — a product decision, not debt cleanup.
- **F4** (from §2.4): slim/standard boundary improvements — `routeToken`-nullable VM branches → `InteractionRepository` capability seam; `StatusPollOrchestrator`'s concrete repo param (already kdoc-documented as deferred).
- **F5** (from item 14): `PartExpandState.kt:158/193` kdoc references the concrete `OpenCodeRepository` though the constructor takes `MessageRepository` — comment-only cleanup.

**Cross-item risk**: none of items 14/16/17 alters runtime behavior in production (14: type narrowing only; 16: deletion of write points with no callers; 17: removal of provably unreachable code + verbatim moves). The dominant risk in all three is **test-fixture drift**, mitigated by per-item check.sh.

---

## 8. Verification plan

Per-lane gates (each fixer, before handoff):

1. **Compile + unit tests (mandatory, all lanes)**: `./scripts/check.sh` (compile + `testDebugUnitTest`). LSP diagnostics must be clean after each edit.
2. **Item 14 targeted**: `./gradlew testDebugUnitTest --tests "*ChatViewModelPassThroughTest*"`; plus a grep proof that `OpenCodeRepository` no longer appears in `ui/chat/MessageCard.kt`, `ui/chat/ChatMessageRow.kt` (param positions), or `ui/chat/ChatMessageContent.kt:139`.
3. **Item 16 targeted**: `./gradlew testDebugUnitTest --tests "*SettingsManagerTest*" --tests "*UnifiedNavTest*" --tests "*OrchestratorViewModelPassThroughTest*" --tests "*AppStateSlicesTest*" --tests "*NavRouteTest*"`; plus grep proof: no `lastNavPage` in `app/src/main`; `KEY_LAST_NAV_PAGE` still present in NavigationPrefs.kt; new first-read migration tests green.
4. **Item 17 targeted**: `./gradlew testDebugUnitTest --tests "*SlimFanOut*" --tests "*AppCoreDispatcherTest*" --tests "*RetryQueueWireTest*"`; plus the §5.1 edge-trace greps re-run as a pre-merge checklist (no new callers of the removed API; no production entry added accidentally).
5. **Batch-level**: after all lanes merge, one final `./scripts/check.sh` on the integration commit; optionally `./scripts/check.sh --full` (lint + coverage) before the next release tag.
6. **No emulator/instrumented testing required**: no item changes behavior reachable by the UI; unit-level proof suffices (per the device-safety policy, no physical-device runs).
