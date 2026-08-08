# ocdroid Architecture-Debt Follow-up Batch — Design SSOT (bundle `archdebt-followup-20260807`)

- **Date**: 2026-08-07
- **Head OID**: `9d1efb8d` (= origin/main = annotated tag v0.21.6, includes Batch 1 items 14/16/17 + Batch 2 items 13/15)
- **Scope**: 8 follow-up items — **F1** (StatusAggregatorInput retirement), **F2** (lastRoute production write-point deletion), **F4** (slim/standard boundary: routeToken branches + StatusPollOrchestrator narrowing), **F5a** (PartExpandState kdoc), **F5b** (Refresh/SendOrchestrator concrete-repo narrowing), **bF2** (cachedContextUsage write-during-composition), **bF3** (TSC kdoc), **ControllerModule** (orphaned note). **F3 is EXCLUDED by user decision — not designed.**
- **Verification basis**: every file:line below was re-verified against `9d1efb8d` by reading the actual code. Explorer findings were independently re-derived; **three explorer claims were corrected** (§3.1 read-side liveness, §7.1 SendOrchestrator "clean narrowing", F4 gateway `@Suppress` detail) and one behavior-preserving subtlety the explorer missed was caught (§8.2 sticky-null semantics).

---

## 1. Executive summary

| Item | Decision (one line) | Status |
|---|---|---|
| **F1** | Retire the **entire** `StatusAggregatorInput` interface (all 3 methods — the write surface is dead by design, not by accident) + delete `StatusFetchService` + `SlimStatusFetchCache` + the `statusAggregatorInput` plumbing (SSC/SseDispatchHost/ControllerModule/StatusModule). Read side (`StatusAggregator`/`StatusAggregatorImpl` derivation) **stays** — but see §12-F6: it is *also* production-consumer-less (explorer's "stateAtNow stays live" is wrong); full read-side retirement is logged, not executed, as a product/seam decision. | **IN** (highest churn) |
| **F2** | Delete the 10 production `settingsManager.lastRoute = …` write points (each is co-located with the authoritative `mutateNav { copy(lastRoute=…) }` — verified all 10); convert `SettingsManager.lastRoute`/`NavigationPrefs.lastRoute` to getter-only `val`s; **KEEP** `KEY_LAST_NAV_PAGE` + the getter migration branch verbatim. | **IN** |
| **F4a** | **Decline** the InteractionRepository capability-seam move (routeToken is per-call UI data + upstream spec §7:231 deleted it from the wire — the gateway variants are identity-equivalent, so the "seam" is fake). Instead **collapse the 3 slimapi write methods** into the legacy ones: the VM fork shrinks to directory-resolution-only; align the one real divergence (legacy `respondPermission` swallowed HTTP errors; the slim variant checked) as a called-out micro-fix. | **IN** (narrowed) |
| **F4b** | Narrow `StatusPollOrchestrator`'s per-call param to **dual seams** (`ConnectionRepository` + `SessionRepository`) at the orchestrator only; the `SessionListActions.launchLoadSessionStatus` wrapper keeps its concrete signature (zero caller/test churn). Slim gating preserved verbatim. | **IN** |
| **F5a** | Comment-only: fix 2 stale kdoc refs + drop the now-unused `OpenCodeRepository` import in PartExpandState.kt. | **IN** (trivial) |
| **F5b** | **DEFER both** — the explorer's "SendOrchestrator is clean (Session+Interaction)" is **wrong**: it also feeds `launchLoadMessages` (MessageRepository + FileVcsRepository) at SendOrchestrator.kt:271-281. True footprints: Send = 4/6 repo interfaces, Refresh = 5/6. Narrowing to ≥4 params is the concrete repo with extra steps — fails YAGNI rigorously (§7.1). | **DEFERRED** |
| **bF2** | Redesign in place: keep per-composition `computeContextUsage` + `remember { mutableStateOf }` handle, move the write-through from composition-phase into **`SideEffect`** — preserves (a) per-composition recompute, (b) the **sticky last-non-null cache semantics** (explorer missed this: `?.let` means null never overwrites), (c) snapshot tracking for `rememberChatTopBarState`. `derivedStateOf` alternative rejected (clears on null = behavior change). | **IN** |
| **bF3** | **NO-OP, verified.** TSC.kt is 435 lines; class kdoc (:10-49) is already the component map (4 collaborators + one-monitor/reentrancy + lateinit wiring + companion note); zero `§Stage-D1/D2` refs remain anywhere in the package (grep-verified). No work. | **NO-OP** |
| **ControllerModule** | **IN (comment-only)**: rewrite the stale "orphaned bindings" parenthetical (:289-291) — there are **no** Hilt bindings for `OkHttpClientFactory`/`SslConfigFactory` (private ctor; instances owned by `RepositoryNetworkGraph`:115/:122; the classes are live in 13 files). **DEFER** the structural provider-body refactor (touches `synchronized(repository)` + baseline publish + drift-check wiring; medium risk, zero behavior gain, gating batch). | note **IN** / refactor **DEFERRED** |

**Ship list for the release gate**: F1, F2, F4a, F4b, F5a, bF2, ControllerModule-note. Deferred/no-op: F5b, bF3, ControllerModule-structural. Logged follow-up: F6 (read-side aggregator retirement).

---

## 2. slim/standard boundary investigation (mandatory pre-work)

### 2.1 Mode-flag stack (carried from batch1 §2.1 / batch2 §2.1, spot-verified at `9d1efb8d`)

```
HostConfig._slim (per-profile user toggle 省流模式)
        │  (configure succeeds → ServerCompatProfile.updateSlimapi)
        ▼
ServerCompatProfile.slimConnection (@Volatile; live mode)
        │
        ├─► tokenStreamEnabled — DERIVED: get() = slimConnection (ServerCompatProfile.kt:121)
        ├─► ConnectionGateway supports* / usesSlimStatusFanOut (≡ slimConnection)
        │       → ConnectionRepository.usesSlimStatusFanOut (ConnectionRepository.kt:15)
        ├─► OpenCodeRepository forwarders (OpenCodeRepository.kt:441/446/451)
        └─► ConnectionState.isSlimActive (UI mirror; SessionsScreen/ServerStatusIconButton only)
Secondary: slimPerSessionStatusEndpointAvailable (default false, StreamingModule.kt:129) — inert fan-out seam (batch1 F3, unchanged).
```

### 2.2 Item ↔ boundary interactions this batch

| Item | Boundary touch | Proof obligation |
|---|---|---|
| F1 | **Removes dead boundary-crossing code** (`StatusFetchService.fetch` had the `usesSlimStatusFanOut` slim/legacy fork, StatusFetchService.kt:86-110) | The fork it deletes is unreachable; the LIVE slim status paths (digest relay, `launchLoadSessionStatusSlim`, SSE-loss fall-through) do not transit through it (§3.6) |
| F2 | None | Navigation persistence is mode-independent |
| F4a | **Touches the boundary directly** (VM-level routeToken fork + gateway slimapi variants) | §5.4 proves the collapsed code is branch-equivalent in both modes; slim directory routing (entry.directory) preserved verbatim |
| F4b | **Touches the boundary directly** (`usesSlimStatusFanOut` gates at StatusPollOrchestrator.kt:147/:170) | Gates move verbatim to the `connectionRepository` param; same singleton, same reads (§5.6) |
| F5a / F5b | None / deferred | — |
| bF2 | None | ChatScaffold/ChatDerivedState are mode-agnostic (batch2 §2.2) |
| bF3 | None | No-op |
| ControllerModule note | None | Comment-only; the provider's slim-transport wiring untouched |

---

## 3. F1 — StatusAggregatorInput retirement (+ fetch services + wiring)

### 3.1 Liveness findings (verified at `9d1efb8d`; one explorer correction)

**Fact 1 — the entire `StatusAggregatorInput` interface is production-caller-less.** Grep over `app/src/main`: the only non-declaration references are DI wiring (StatusModule.kt:56, ControllerModule.kt:216/237), the impl (`override`s), and kdoc mentions. `refresh` (StatusAggregator.kt:185), `applySseStatus` (:198), `markRequestFailed` (:215-219) have **zero** production call sites. The impl's bodies (StatusAggregatorImpl.kt:352-443, :462-476, :488-528) are reachable only through the dead interface.

**Fact 2 — the SSE replacement is deliberate and documented.** `LegacySseHandler.kt:135-137` records that the `aggregatorInput.applySseStatus(...)` block was **DELETED** in favor of the single `host.applyStatusViaAuthority(...)` dispatch (:152) — the aggregator now *derives* from authority. `SseDispatchHost.applyStatusViaAuthority` (SseDispatchHost.kt:164-223) dispatches `AuthorityOp.ApplyEvent` directly; it never touches `statusAggregatorInput`. The `SseDispatchHost.statusAggregatorInput` property (:42) and `SessionSyncCoordinator.statusAggregatorInput` override (:97) are **declared but never read** anywhere (grep-verified).

**Fact 3 — the fetch cascade is singly-reachable.** `StatusFetchService.fetch` (:82) is called only from the dead `refresh` (StatusAggregatorImpl.kt:374); `SlimStatusFetchCache.fetchGlobal` (:88) is called only from `StatusFetchService.fetch` (:100). Both classes exist solely to serve the dead path.

**Fact 4 (CORRECTION to explorer/batch1-F1 framing) — the read side is ALSO production-consumer-less.** Batch1 §7/F1 said "the SSE-driven `applySseStatus` + `stateAtNow` stay live" and the explorer echoed "LIVE path that STAYS: stateAtNow()". Verified false at this head: **no main-source constructor injects `StatusAggregator`** (the only binding, StatusModule.kt:51, has no requesters); `stateAtNow`/`globalState`/`globalBusy`/`statusByKey` appear outside `service/status/` only as kdoc refs (SourceActivation.kt:25/48, SharedStateStore.kt comments) and test fakes. The FGS lifecycle coordinator (Lane C) that was the intended consumer was removed in Phase 1. **Disposition**: this batch does NOT delete the read side (scope discipline — the task names the input side + fetch wiring; read-side retirement also deletes the `GlobalBusyState`/TTL/coverage projection model, a product/seam call like batch1's F3). Logged as follow-up **F6** (§12) with this evidence. The read side stays exactly as-is minus its kdoc references to the deleted write API.

### 3.2 Decision: retire all three methods + the whole interface (not just the named two)

The task offers a choice (all 3 vs only refresh/markRequestFailed). **Retire all 3 + the interface itself.** Justification:

1. The interface's raison d'être (kdoc :143-149) was letting injectors "feed the aggregator without depending on the concrete impl". With **zero** production feeders, the separation has no consumer on either side.
2. Keeping `applySseStatus` alone would preserve a write API whose only caller was **deliberately deleted** (Fact 2) and whose semantics are now a strict duplicate of `AuthorityOp.ApplyEvent` — any future SSE feed must go through `applyStatusViaAuthority` (the documented single funnel), not resurrect a parallel adapter. Keeping it invites exactly the dual-write regression Lane 2 eliminated.
3. Constraint 1 (no dead abstraction): a one-method dead interface is still a dead interface.

### 3.3 Exact deletion surface (production)

| # | File:line | Action |
|---|---|---|
| 1 | StatusAggregator.kt:143-220 | Delete the entire `StatusAggregatorInput` interface + its kdoc. Fix the remaining `[StatusAggregatorInput.refresh]` ref at :48 (→ plain "first successful snapshot") |
| 2 | StatusAggregatorImpl.kt:91-97 | Ctor: drop `private val statusFetchService: StatusFetchService` (:94); class decl `: StatusAggregator, StatusAggregatorInput` → `: StatusAggregator` |
| 3 | StatusAggregatorImpl.kt:333-528 | Delete the whole "StatusAggregatorInput → authority-dispatch adapters" section: `refresh` (:335-443), `applySseStatus` (:445-476), `markRequestFailed` (:478-503), `markRequestFailedInternal` (:505-528) |
| 4 | StatusAggregatorImpl.kt:28-89 (class kdoc) | Rewrite the Lane-2 essay: the "mutation API preserved VERBATIM in signature (the 6 call sites + ~13 test files are unchanged)" claims (:43-49) are now false — state that the input side was retired in F1 (callers deliberately rerouted to direct authority dispatch); the read side derives from `store.state.authority` via the `init` collect + `publishFromState`. Also fix :86-88 (`applySseStatus` sourceTimeMs ref) and :225 (`ProcessStatusPoller` mention — stale since batch1) |
| 5 | StatusFetchService.kt | **Delete file** (121 lines) |
| 6 | SlimStatusFetchCache.kt | **Delete file** (120 lines) |
| 7 | StatusModule.kt:53-56 | Delete `bindStatusAggregatorInput` |
| 8 | StatusModule.kt:74-93 | Delete `provideStatusFetchService` + `provideSlimStatusFetchCache`; drop `statusFetchService` from `provideStatusAggregatorImpl` (:61-72); drop now-unused imports (`ConnectionRepository`, `SessionRepository`); rewrite module kdoc (:16-43 — remove the "AND [StatusAggregatorInput] (the feed surface)" + "mutation API dispatches" claims) |
| 9 | SessionSyncCoordinator.kt:14, :97 | Drop the import + the `override val statusAggregatorInput: StatusAggregatorInput? = null` param |
| 10 | SseDispatchHost.kt:5, :36-42 | Drop the import + the `statusAggregatorInput` property (kdoc :36-41 + decl :42). Rewrite the `sseClock` kdoc (:81-85 — its "used by the aggregator feed branch (for [StatusAggregatorInput])" rationale → "connectionTimeMs for authority ApplyEvent, TTL/tie-break"). Comment at :173 ("before calling applySseStatus") → "before the Lane-2 authority rework" |
| 11 | ControllerModule.kt:216, :233-237 | `provideSessionSyncCoordinator`: drop the `statusAggregatorInput` param + the CP4 comment (:233-236) + the arg (:237) |
| 12 | SessionBusyStatusMapping.kt:26-40 | Delete `toSessionStatus()` (sole caller was `applySseStatus`, StatusAggregatorImpl.kt:468 — grep-verified). **Keep** `toSessionBusyStatus()` (live: authorityToAggregate, StatusAggregatorImpl.kt:274); fix its kdoc refs (:11-13, :28-34) |
| 13 | Kdoc touch-ups (comment-only, no behavior) | StatusSnapshot.kt:9/:34 (refresh/markRequestFailed refs); SessionSnapshotProvider.kt:9/:21; BootstrapRunner.kt:41; AuthorityOp.kt:109-110 ("`markRequestFailed` adapter dispatches this op" → historical note: the adapter was retired in F1; **the `MarkSourceFailed` op + reducer branch STAY** — see §3.7); StreamingModule.kt:121 (`StatusAggregatorImpl.refresh` ref); ServerCompatProfile.kt:193 (`runRefresh` ref — stale since batch1); StatusAggregatorImpl.kt:341-350 (dies with #3) |

**Kept verbatim (the live read side)**: `StatusAggregator` interface (:31-97 minus the :48 ref), `StatusAggregatorImpl`'s `init` collect (:183-208), `authorityToAggregate` (:256-291), `publishFromState` (:310-319), `stateAtNow` (:330-331), `publishLocked`/`project`/`rescheduleFreshnessLocked` (:546-654), `STATUS_TTL_MS` (:665). `StatusSnapshot`, `GlobalBusyState`, `SessionStatusKey`/`SessionBusyStatus`, `SlimFanOutRetryScheduler`'s `snapshotProvider` all stay (the scheduler consumes `StatusSnapshot` independently).

### 3.4 Test dispositions (exact)

| Test file | Disposition |
|---|---|
| `service/status/StatusAggregatorImplTest.kt` (1218 lines, ~40 tests) | **Major rewrite.** Setup: `newAggregator` (:78-92) drops the `SlimStatusFetchCache`/`StatusFetchService` construction; same at :498/:530/:612/:1075/:1112. **DELETE the adapter tests** (the behavior they pinned is deleted or already owned+pinned by `SessionListActionsTest`/`StatusPollingDowngradeRegressionTest`): REST success/failure mapping (:108-220), REST-vs-SSE merge-timing-via-refresh (:221-262, :276-338), epoch guard (:597-641), T-R1 slim refresh routing (:830-1010), `markRequestFailed` entries (:642-689, :1165-1199), refresh consistency (:1200-end). **REWRITE the projection tests to seed authority directly** — replace each `aggregator.refresh(identity, snapshot)` Act with `store.dispatch(AppAction.AuthorityEvent(buildAuthorityApplySnapshot(...)))` (success), `AuthorityOp.MarkSourceFailed` (failure), or `AuthorityOp.ApplyEvent` (SSE) — the same ops the deleted adapters built; all Given/Assert structure (TTL :468-596, coverage/ghost :263-275/:690-794, cold-start :419-437/:795-829, `U-PUBLISH` :1011-1146, `stateAtNow` :546-574, scope derivation :1147-1164) is preserved. Add a private `seedSnapshot(...)` helper mirroring the deleted refresh-op construction so tests don't hand-build 10-arg ops repeatedly |
| `service/status/SlimStatusFetchCacheTest.kt` (148 lines) | **DELETE file** (class under test deleted) |
| `ui/controller/SessionSyncCoordinatorStatusFeedTest.kt` (292 lines) | **Keep + edit**: the assertions were already migrated to the authority path (:123-131 etc.). Remove: `RecordingStatusAggregatorInput` (:270-291), the `statusAggregatorInput` ctor args (:81, :252), the `aggregatorInput.applyCalls.isEmpty()` assert (:188 area → replace with `assertNull(slices.store.stateFlow.value.authority.bySid["ghost"])`), the `assertNull(aggregatorInput.lastApply())` (:263), the field (:59/:73). The `no statusAggregatorInput wired...` test (:242-265) collapses to a plain "badge fold unchanged" test using the shared `coordinator`. Update header kdoc (:37-51) |
| `service/bridge/SseEventStreamBridgeWiringTest.kt:395-449` | **Delete both dead fixtures**: `BridgeFakeStatusInput` (:399-413) and `BridgeFakeStatusAggregator` (:422+) — grep-verified neither is instantiated anywhere (declaration-only dead code) |
| `service/streaming/ServiceSseConnectionOwnerResyncTest.kt` | Delete the dead `FakeAggregator` (:461-500) + the `aggregator` field/init (:70/:94) + all 13 `aggregator.nextState = …` lines (:147-440) — grep-verified the fake is never passed to any SUT; `nextState` is write-only dead state. (If the fixer prefers minimal diff: only the `, StatusAggregatorInput` implements-clause + 3 overrides strictly must go for compile — but the whole fixture is dead; delete it) |
| `ui/controller/RetryQueueWireTest.kt:68` | Drop the `statusAggregatorInput = null,` named arg from the SSC construction |
| `B2RouteWiringSequenceTest.kt:107` | Drop the `override val statusAggregatorInput … = null` from `FakeSseHost` |

### 3.5 Ordered steps

1. Delete the adapters + ctor param + implements-clause in `StatusAggregatorImpl` (#2-#4). Build breaks at StatusModule + tests — expected.
2. Delete `StatusAggregatorInput` (#1), `StatusFetchService` (#5), `SlimStatusFetchCache` (#6).
3. StatusModule rewiring (#8).
4. SSC / SseDispatchHost / ControllerModule plumbing removal (#9-#11).
5. `toSessionStatus` + kdoc sweep (#12-#13).
6. Test dispositions (§3.4) — do StatusAggregatorImplTest last within the lane.
7. `./scripts/check.sh` + targeted runs (§13).

### 3.6 slim/standard impact

`StatusFetchService.fetch` contained a slim/legacy fork (`usesSlimStatusFanOut` → slim global call vs legacy `getSessionStatus`, :86-110) — but it is reachable **only** from the dead `refresh` (Fact 3), so deleting it removes zero live behavior in either mode. The **live** slim status paths are untouched and unaffected: (a) SSE digest `status` relay → `SessionSyncCoordinator.handleSessionDigest` → `applyStatusViaAuthority` (EntryOrigin.SSE_SLIM); (b) slim cold-start bulk `launchLoadSessionStatusSlim` (StatusPollOrchestrator — F4b keeps it verbatim); (c) SSE-loss REST fall-through (:170-173). The **live** legacy paths: `LegacySseHandler` → `applyStatusViaAuthority` (SSE_LEGACY) + `launchLoadSessionStatus` legacy REST fan-out. The aggregator's read-side derivation consumes `store.state.authority` in both modes identically — no mode flag anywhere in the retained code. ∴ byte-identical behavior in slim and standard modes.

### 3.7 Risks

1. **`MarkSourceFailed` becomes dispatcher-less** (its only dispatcher was the deleted adapters; `launchLoadSessionStatus`'s failure path deliberately only `complete(false)`s). The op + reducer branch (AuthorityReducer.kt:704-732) **stay** — deleting reducer semantics is out of scope; kdoc at AuthorityOp.kt:109-110 marked historical. Same "preserved seam, no emitter" category as batch1's `RequestPollerBackoff` — not a regression.
2. **Test rewrite fidelity** (the real cost): the rewritten projection tests must build `buildAuthorityApplySnapshot` ops with the same `scopeKey`/`requestToken` discipline the adapter used. Mitigation: the `seedSnapshot` helper copies the deleted adapter's op construction verbatim; the `scope derivation is consistent` test (:1147) stays as the invariant pin.
3. **Do NOT** let the lane drift into deleting the read side (F6) or `MarkSourceFailed` — both are logged, not executed.

---

## 4. F2 — lastRoute production write-point deletion (getter migration retained)

### 4.1 Verification (all 10 write sites confirmed, each co-located with the authoritative `mutateNav`)

| # | File:line | Site | Co-located `mutateNav { copy(lastRoute…) }` |
|---|---|---|---|
| 1 | OrchestratorViewModel.kt:102 | `setLastRoute` | :103 (same value, no epoch) |
| 2 | OrchestratorViewModel.kt:126 | `requestNavigate` | :127-133 (+epoch) |
| 3 | OrchestratorViewModel.kt:232 | `navigateToChat` | token-capture mutate :233+ |
| 4 | SessionViewModel.kt:308 | `forceNavigateToSessionsInternal` | :309-314 |
| 5 | HostProfileController.kt:315 | `switchToHostProfile` | :316-321 |
| 6 | SessionMutationActions.kt:197 | archive-current | :198-203 |
| 7 | SessionMutationActions.kt:285 | delete-active-detail | :286-291 |
| 8 | RefreshOrchestrator.kt:238 | bulk-archived | :239-244 |
| 9 | DraftSessionOrchestrator.kt:122 | createSession onSuccess | `adoptMaterializedSessionRoute` commits nav |
| 10 | LegacySseHandler.kt:106 | session.archived SSE | :107-112 |

**Verified zero production getter readers**: every production `lastRoute` read is `navFlow.value.lastRoute` / `state.nav.lastRoute` (DraftSessionOrchestrator.kt:236, SliceFlows.kt:81, AppCoreOrchestration.kt:88, OrchestratorViewModel.kt:101, SessionMutationActions.kt:277, LegacySseHandler.kt:101) — the in-memory authority. `SettingsManager.lastRoute`'s getter has no production callers. The persisted `last_route` is write-only today; `restoreLastRoute()` was deleted in T7 (product decision: no cold-start route restore). ∴ every persisted write is redundant; deleting all 10 is byte-identical in production behavior.

Bonus observation (no action): sites #3/#9 persist `"chat/$sid"`, which the setter's `TOP_LEVEL_ROUTE_KEYS` filter normalizes to `"chat"` — the parameterized persistence never round-tripped anyway.

### 4.2 Exact changes (production)

| File:line | Before → After |
|---|---|
| NavigationPrefs.kt:33-56 | `var lastRoute` → **`val lastRoute: String`** — keep the getter (:34-52: workspace→files normalization :36-39, TOP_LEVEL_ROUTE_KEYS passthrough :40-41, unknown→chat quarantine :42-43, **KEY_LAST_NAV_PAGE first-read migration :45-51**) **verbatim**; delete the setter (:53-56). Class kdoc (:5-16): add "the persisted route is now READ-ONLY (migration/legacy source); production authority is in-memory `NavState.lastRoute`; write points deleted in F2" |
| SettingsManager.kt:162-164 | `var lastRoute` → **`val lastRoute: String get() = navigationPrefs.lastRoute`** + one-line kdoc (read-only migration source) |
| The 10 write sites | Delete the `settingsManager.lastRoute = …` line at each §4.1 location. Adjacent comment touch-ups: OrchestratorViewModel.kt:94-97/:121-123 (drop "settingsManager.lastRoute write" from the main-thread contract), :163/:195 (kdoc), :231 ("Persist the parameterized route" comment dies with the line); DraftSessionOrchestrator.kt:87/:121 (drop step "A. persistence side-effect", re-letter B/C); HostProfileController.kt:313-314 (trim) |

### 4.3 Test changes

| File:line | Action |
|---|---|
| OrchestratorViewModelPassThroughTest.kt:78 | `verify(exactly = 0) { settingsManager.lastRoute = any() }` → **delete** (the setter no longer exists; the test's `navFlow` emission assertions :66-77 remain the real pin) |
| OrchestratorViewModelPassThroughTest.kt:107 | `verify(exactly = 1) { settingsManager.lastRoute = … }` → **delete** (same) |
| SessionViewModelPassThroughTest.kt:236 | `verify { settingsManager.lastRoute = NavRoute.Sessions.route }` → **delete**; navFlow assert :235 stays |
| SessionViewModelPassThroughTest.kt:272 | `verify(exactly = 0) { settingsManager.lastRoute = … }` → **delete**; navFlow assert :271 stays |
| SettingsManagerTest.kt:399-405 (`accepts files and git`) | Rewrite to seed raw prefs (`rawEncryptedPrefs().edit().putString("last_route","files").commit()`) → getter returns verbatim (keeps TOP_LEVEL_ROUTE_KEYS passthrough coverage) |
| SettingsManagerTest.kt:407-411 (`setter rejects unknown route to chat`) | **DELETE** (setter gone; the unknown-route quarantine is already pinned by the getter test :413-424) |
| SettingsManagerTest.kt:391-397, :413-464 | **KEEP verbatim** (workspace migration, getter quarantine, last_nav_page→last_route first-read migration — the retained behavior) |

### 4.4 Ordered steps / risks / slim impact

Steps: (1) NavigationPrefs val-ification → compile breaks at the 10 sites + 4 test sites; fix in same commit. (2) SettingsManager val-ification. (3) Test edits. (4) `./scripts/check.sh`.
Risks: nil behavioral (write-only data). `clearAllLocalData` wipe coverage unchanged (it iterates non-preserved keys; `last_route`/`last_nav_page` both still wiped — SettingsManagerTest:509+ untouched). **Write-domain overlap flagged**: RefreshOrchestrator.kt:238 (this item) — no conflict with F5b because F5b-Refresh is **deferred** (§7); if a future batch revives F5b it rebases over this deletion.
slim/standard impact: **none** (mode-independent persistence).

---

## 5. F4 — slim/standard boundary cleanups

### 5.1 F4a verification (explorer claim confirmed, one detail corrected)

At the gateway level the three slimapi write variants are **functionally identical** to legacy (InteractionGateway.kt):
- `replySlimapiQuestion` (:263-276) ≡ `replyQuestion` (:118-128) — same `mutationApi.replyQuestion(requestId, QuestionReplyRequest(answers), directory)` call, same isSuccessful check. **`routeToken` is accepted and never used** (the explorer's "@Suppress("UNUSED_PARAMETER")" detail is wrong — no such annotation exists on these methods; the one @Suppress in the file is on `getSlimapiQuestions`' `directories`, :187. Substance holds: the token is unused).
- `rejectSlimapiQuestion` (:280-290) ≡ `rejectQuestion` (:130-136) — identical.
- `respondSlimapiPermission` (:292-305) ≡ `respondPermission` (:107-112) **except** the slim variant checks `isSuccessful` and throws; the legacy one ignores the `Response<Unit>` code — silent success on HTTP error (real divergence, §5.3).

**Why routeToken is a pure client-side fork signal**: upstream spec §7:231 **deleted routeToken from the wire** (SlimapiErrorCodes.kt:101 "V2: INVALID_ROUTE_TOKEN removed (spec §7:231 — routeToken deleted)"). The gateway kdoc's "sidecar re-validates the [routeToken] HMAC for write-side authentication" (:256-258) is stale — the token is never sent. The only genuine slim/legacy difference lives in the **VM**: directory resolution — slim reads `pendingQuestions.firstOrNull { it.id == requestId }?.directory` (OrchestratorViewModel.kt:353-354/:394-395); legacy calls `core.resolveQuestionDirectory(requestId)` (:363/:399, AppCoreOrchestration.kt:140). That logic reads UI slice state + AppCore helpers — it **cannot** move below the repo seam.

### 5.2 F4a decision: decline the capability-seam move; collapse the fake seam

**Declined**: pushing the fork into `InteractionRepository`. (a) routeToken is per-call UI data, not a connection capability — a capability seam keys on `slimConnection`, which is *not* what the VM forks on (it forks on whether the entry arrived via slim SSE, per-entry). (b) The directory-resolution half of the fork must stay in the VM regardless (§5.1), so the VM keeps an if/else either way — the move relocates nothing, it just splits one fork across two layers. (c) The repo already exposes the *fetch-side* capability flags (`supportsGlobalQuestionFetch` :72, `supportsSlimQuestions` :81) where a real capability exists.

**Instead — collapse the 3 slimapi write methods into the legacy ones** (this removes a *false* boundary — the pretense of a distinct slim write path that upstream already deleted):

| Location | Change |
|---|---|
| InteractionRepository.kt:43-59 | Delete `replySlimapiQuestion`/`rejectSlimapiQuestion`/`respondSlimapiPermission` |
| OpenCodeRepository.kt:1378-1396 | Delete the 3 forwarders |
| InteractionGateway.kt:251-305 | Delete the 3 slimapi methods + their kdocs |
| InteractionGateway.kt:107-112 | **Micro-fix (deliberate, called out)**: legacy `respondPermission` gains the isSuccessful check the slim variant had: `val resp = mutationApi.respondPermission(...); if (!resp.isSuccessful) throw Exception("Permission respond failed ${resp.code()}: …")`. Aligns both modes; fixes the standard-mode silent-success-on-4xx bug (a failed respond currently removes the pending chip via `onSuccess`). This is the item's only intentional behavior delta — error-path only, standard mode only |
| OrchestratorViewModel.kt:304-333 | `respondPermission`: fork collapses — single `core.repository.respondPermission(sessionId, permissionId, response)` call. **`routeToken` param KEPT** (ChatScaffold.kt:703 passes `p.routeToken`; call-site stability) with rewritten kdoc: "client-side provenance signal only; upstream spec §7:231 deleted it from the wire; both modes hit the same endpoint" |
| OrchestratorViewModel.kt:335-381 | `replyQuestion`: fork shrinks to directory resolution: `val directory = if (routeToken != null) core.sessionListFlow.value.pendingQuestions.firstOrNull { it.id == requestId }?.directory else core.resolveQuestionDirectory(requestId)` → single `core.repository.replyQuestion(requestId, answers, directory)` |
| OrchestratorViewModel.kt:383-423 | `rejectQuestion`: same shape → `core.repository.rejectQuestion(requestId, directory)` |
| Kdoc stale-refs | data/model/Permission.kt:20, SlimAggregationOutcome.kt:114 (`respondSlimapiPermission` refs → `respondPermission`); InteractionGateway.kt kdoc for `getSlimapiQuestions` untouched |
| T3RepositoryExtractFreezeTest.kt:498-504 | Remove the 3 names from the frozen `InteractionRepository` method list |
| OrchestratorViewModelPassThroughTest.kt:240-366 | Rewrite the 6 slim-dispatch tests: stub/verify `replyQuestion`/`rejectQuestion`/`respondPermission` (the collapsed targets) instead of the slimapi names; the directory-resolution assertions (`"/workdir-slim"` threaded :255, resolveQuestionDirectory fallback :293/:331/:366) carry over — they pin the retained fork |

**New test** (micro-fix pin, place in `data/repository/gateway/InteractionGatewaySlimQuestionsTest.kt` — the existing gateway harness): legacy `respondPermission` HTTP 500 → `Result.failure` (was silent success).

### 5.3 F4a slim/standard impact

Slim mode: identical HTTP calls (the slimapi variants *were* the legacy calls), directory still threaded from the pending entry, error semantics preserved via the micro-fix alignment. Standard mode: identical success path; error path now surfaces instead of swallowing (the called-out delta; OrchestratorViewModel's `onFailure → UiEvent.Error` handling already exists :329-331). Fetch-side boundary (`getSlimapiQuestions`/`getSlimapiPermissions` + capability flags) untouched. ∴ the boundary becomes *honest* (write path is mode-agnostic; only directory provenance differs) without moving any gate.

### 5.4 F4b decision: dual-param narrowing at the orchestrator; wrapper keeps concrete

**Verified seam** (StatusPollOrchestrator.kt): `usesSlimStatusFanOut` (:147, :170 — `ConnectionRepository.kt:15`), `getSessionStatus` (:204), `getActiveSessionIds` (:205), `getSlimapiSessionsStatus` (:390 — all `SessionRepository.kt:16-18`). The §Wave2.3 nit#2 deferral note (:31-41) gated on "B3 (slim-token retirement)" — that retirement has shipped (PartExpandState.kt:216 "§B3-retirement: the slim-token shim has been retired"), so the precondition is met.

**Exact signature changes:**

```kotlin
// StatusPollOrchestrator.kt:102-108 — BEFORE: repository: OpenCodeRepository
internal fun launchLoadSessionStatus(
    scope: CoroutineScope,
    connectionRepository: ConnectionRepository,   // usesSlimStatusFanOut (gates :147, :170)
    sessionRepository: SessionRepository,         // getSessionStatus / getActiveSessionIds / slim bulk
    slices: SliceFlows,
    trigger: SessionStatusLoadTrigger = SessionStatusLoadTrigger.SWEEP,
    onComplete: (Boolean) -> Unit = {},
)
// :336-344 launchLoadSessionStatusSlim — param retype: repository: OpenCodeRepository → sessionRepository: SessionRepository
// Body edits: :147/:170 → connectionRepository.usesSlimStatusFanOut; :171 passes sessionRepository;
//             :204/:205/:390 → sessionRepository.*
// Imports: drop OpenCodeRepository, add ConnectionRepository + SessionRepository
// Kdoc :31-41: rewrite (narrowing DONE; why the wrapper keeps concrete)
```

```kotlin
// SessionListActions.kt:158-171 — signature UNCHANGED (repository: OpenCodeRepository);
// delegate body passes the seam pair:
) = StatusPollOrchestrator.launchLoadSessionStatus(
    scope = scope, connectionRepository = repository, sessionRepository = repository,
    slices = slices, trigger = trigger, onComplete = onComplete,
)
```

**Why the wrapper keeps the concrete param**: its callers — AppCore.kt:632/636 (AppCore legitimately holds the concrete) and RefreshOrchestrator.kt:188 (F5b-deferred) — plus ~45 positional test call sites (SessionListActionsTest, StatusPollingDowngradeRegressionTest) would all churn for zero additional decoupling: the concrete they pass is the same singleton Hilt binds to both interfaces. The logic owner gets the honest seam; the compat delegate documents it. No new combined interface (YAGNI — one consumer, internal object).

**Test churn: zero** (all tests drive the wrapper or `mergeStatusSnapshot`).

### 5.5 F4b slim/standard impact

The two `usesSlimStatusFanOut` gates (the SWEEP short-circuit :147-153 incl. the 🔴 epoch-order landmine and the slim branch :170-173) read the same boolean off the same singleton — param retype only. `sseDigestRelayEffective` (:95-99) untouched. Slim SWEEP no-op / slim bulk / legacy fan-out byte-identical. `StatusPollingDowngradeRegressionTest` (groups 1-4) is the unchanged green net.

---

## 6. F5a — PartExpandState.kt kdoc cleanup (trivial)

Verified: `ExpandPartsUseCase` ctor takes `MessageRepository` (:204); the only repo call is `repository.expandMessagesFullBatch` (:248).

| File:line | Change |
|---|---|
| PartExpandState.kt:7 | Delete `import cn.vectory.ocdroid.data.repository.OpenCodeRepository` (unused after kdoc fixes — kdoc refs don't need imports, and no code ref remains) |
| :158 | `T3's [OpenCodeRepository.expandMessagesFullBatch]` → `T3's [MessageRepository.expandMessagesFullBatch]` |
| :193 | "Tests construct it directly with a mockk `OpenCodeRepository`" → "… with a mockk `MessageRepository`" |

No test changes. slim/standard impact: none (comment-only).

---

## 7. F5b — RefreshOrchestrator & SendOrchestrator narrowing: **DEFERRED (both)**

### 7.1 The explorer's "Send is clean" claim is wrong — verified footprints

`SendOrchestrator` (repo param :34) uses, beyond `updateSessionArchived` (:118, :186 — SessionRepository) and `launchSendMessage` (:95/:218/:243 — already takes `InteractionRepository`, SessionMutationActions.kt:315): **`launchLoadMessages(repository = repository)` at :271-281** (`loadMessagesForEffect`, also reached via `loadMessagesWithRetry` :288+). `launchLoadMessages` (MessageLoader.kt:41-43) takes the concrete and uses `getMessagesPaged`/`getMessagesPagedUnanchored` (MessageRepository) **and** `getSessionTodos` (:598 — **FileVcsRepository**). True SendOrchestrator footprint: **4 of 6 interfaces** (Session + Interaction + Message + FileVcs).

`RefreshOrchestrator` (repo param :41) uses: `getSession` (:263 — Session); via `launchLoadMessages` (:156 — Message + FileVcs); via `launchCatchUp` (:116 — `probeLatestMessageIdForCurrent` + `getMessagesPaged` — Message); via `launchLoadSessions` (:181 — `getSessions`/`getSessionsForDirectory` — Session, `getSessionDiff` — FileVcs); via `launchLoadSessionStatus` (:188 — Session + Connection); via `foregroundCatchUpController.catchUpPendingQuestionsAllWorkdirs` (:139, param typed concrete at ForegroundCatchUpController.kt:289 — Interaction: `supportsGlobalQuestionFetch`/`supportsSlimQuestions`/`getSlimapiQuestions`). True footprint: **5 of 6 interfaces**.

### 7.2 YAGNI verdict (rigorous)

Narrowing these two means: Send → 4 narrow params, Refresh → 5 narrow params, **plus** cascading signature changes into the shared free functions (`launchLoadMessages`, `launchCatchUp`, `launchLoadSessions`, `catchUpPendingQuestionsAllWorkdirs`) and their ~25 positional test call sites (MessageLoaderBaselineTest/MessageActionsTest/SessionListActionsTest…), plus the two positional test factories (MainViewModelTestBase.kt:288/:294, ForkSessionTest.kt:202/:208), plus reopening RefreshOrchestrator.kt against F2's deletion at :238.

What it buys: nothing decoupling-wise (Hilt binds all six interfaces to the same `OpenCodeRepository` singleton; the runtime graph is identical), nothing testability-wise (every existing test mockks the concrete; no test uses per-interface fakes; the factories would pass the same mock 4-5×). A "seam" covering 4-5 of 6 domains is not a narrow interface — it is the concrete repo with extra steps, and it *worsens* the signal the genuinely-narrow siblings provide (SessionOpener → SessionRepository and CommandOrchestrator → Session+Interaction are meaningful *because* their footprints are 1-2 domains).

**Contrast with F4b (accepted)**: StatusPollOrchestrator's footprint is genuinely 2 domains with zero caller/test churn. That is what "earns its keep" looks like; F5b is the mirror image.

**Deferral triggers** (revisit when any holds): (a) `loadMessagesForEffect` moves out of SendOrchestrator (drops it to Session+Interaction — then narrowing *is* clean); (b) a `MessageRepository + FileVcsRepository` pair seam is introduced for the loader family; (c) a future batch splits RefreshOrchestrator's catch-up/session-load arms.

---

## 8. bF2 — cachedContextUsage write-during-composition redesign

### 8.1 Verified current state (ChatDerivedState.kt:263-269)

The smell moved verbatim from ChatScaffold into the batch2 factory:

```kotlin
val computedContextUsage: ContextUsage? =
    computeContextUsage(renderedMessages.value, settingsState.value.providers)   // :263-264 — every composition, deliberate
val cachedContextUsageState = remember { mutableStateOf(computedContextUsage) }  // :268
computedContextUsage?.let { cachedContextUsageState.value = it }                 // :269 — WRITE DURING COMPOSITION
```

Consumers: `rememberChatTopBarState` (ChatScaffold.kt:352-366, param :361) reads `cachedContextUsageState.value` **inside** its `derivedStateOf` lambda (ChatTopBar.kt:254 — snapshot-tracked, must not be a remember key per ChatTopBar.kt:174-176); ChatScaffold.kt:242 reads it via `by` delegate for `ChatOverlayHost` (:928).

### 8.2 The subtlety the explorer missed: sticky null semantics

Line 269's `?.let` means **a null computation never overwrites the cache** — the state holds the *last non-null* usage (e.g., navigating to a session with no assistant messages keeps showing the previous usage instead of blanking). Any correct redesign must preserve this stickiness. A pure `derivedStateOf { computeContextUsage(...) }` — although it would be the idiomatic fix and `computeContextUsage` is verified pure over snapshot inputs (AppStateDerived.kt:209-253; `ContextUsage` is a data class → structural-equality stability) — **clears on null = behavior change → rejected**.

### 8.3 Design: SideEffect write-through (sticky, Compose-safe)

```kotlin
// ── Context usage — Compose-safe redesign (bF2) ─────────────────────────────
// computeContextUsage is PURE over (renderedMessages, providers) — both
// snapshot-backed — and is still evaluated EVERY composition (freshness over
// memoization: a host-switch/provider refresh updates usage even when messages
// are unchanged; kept per the pre-bF2 intent). The write-through into the
// snapshot-backed cache handle moves from the composition phase into
// SideEffect (apply phase): writing a MutableState mid-composition can
// invalidate readers of the same pass; SideEffect runs once per APPLIED
// composition, off-phase. Sticky semantics preserved VERBATIM: a null
// computation NEVER overwrites the last non-null usage (`?.let`).
val computedContextUsage: ContextUsage? =
    computeContextUsage(renderedMessages.value, settingsState.value.providers)
val cachedContextUsageState = remember { mutableStateOf(computedContextUsage) }
SideEffect {
    computedContextUsage?.let { cachedContextUsageState.value = it }
}
```

Edits: ChatDerivedState.kt — replace :265-269 with the above; add `import androidx.compose.runtime.SideEffect`; update the comment block :256-262, the class-kdoc invariant #3 (:61-65 — "write-during-composition preserved verbatim" → "write-through runs in SideEffect, sticky last-non-null semantics preserved"), the field kdoc (:98-100), and the file-header bullet (:14).

**Contract preservation proof**:
- (a) Per-composition recompute: unchanged — `computedContextUsage` is still a plain per-composition `val` (:263-264 verbatim).
- (b) Snapshot tracking: the handle is still a `MutableState` read inside `rememberChatTopBarState`'s `derivedStateOf` — writes invalidate its readers exactly as today; `mutableStateOf`'s structural-equality policy means equal values trigger no invalidation (same as today's write-same-value behavior).
- (c) Behavior: identical values, identical stickiness. The one mechanical delta: the write lands at apply-phase rather than mid-composition, so an in-pass reader (ChatScaffold.kt:242) sees the previous value in the exact composition where inputs flip, converging on the next pass — at most one extra recomposition, **loop-safe** (deterministic inputs: the follow-up pass computes the same value → equal write → no further invalidation).

### 8.4 Risks

**Recomposition-correctness (the flagged risk)**: low and bounded (§8.3-c). Nets: `ChatScaffoldSaveableTest` (unchanged) + full unit suite; no existing unit test pins this path (compose runtime) — reviewers verify by reading the diff against §8.3 and running `--tests "*ChatScaffold*"`. Do NOT "improve" further (no derivedStateOf conversion, no de-stickyfication) in this batch.

---

## 9. bF3 — TokenStreamCoordinator kdoc: **NO-OP (verified)**

TSC.kt is 435 lines; the class kdoc (:10-49) is already the batch2 component map: architecture table for the 4 collaborators (:20-25), one-monitor/reentrancy invariant (:27-33), supervisor↔dispatcher lateinit wiring (:35-44), companion-constants note (:46-49). The 4 components exist (TokenFrameGuard 164L, ReconnectPolicy 46L, TokenStateDispatcher 439L, StreamLifecycleSupervisor 460L). `rg "Stage-D1|Stage-D2|§D1|§D2"` over `ui/controller/sse/` → **zero hits**. The old scoping essay is fully replaced. **No work; do not invent any.**

---

## 10. ControllerModule — orphaned-note cleanup (IN) / structural refactor (DEFERRED)

**Note cleanup (IN, comment-only)**: ControllerModule.kt:289-291's parenthetical — "(The @Singleton OkHttpClientFactory / SslConfigFactory bindings are now orphaned here — left in place for the Option A follow-up that unifies ownership; do NOT delete.)" — is **false at this head** (verified): there are **no** Hilt bindings for either class anywhere under `di/` (`OkHttpClientFactory` has a private constructor, OkHttpClientFactory.kt:62; instances are owned by `RepositoryNetworkGraph`:115/:122; both classes are live across 13 main-source files). The note is a stale fossil of the §tokenstream-mtls-fix param change. Rewrite :284-291 to: keep the mTLS-fix history (why the provider routes via `OpenCodeRepository.tokenStreamClient`) and replace the parenthetical with "OkHttpClientFactory/SslConfigFactory are owned by `RepositoryNetworkGraph` (no Hilt bindings exist); the historical 'orphaned bindings' remark was removed in the archdebt follow-up batch as stale."

**Structural provider-body refactor (DEFERRED)**: splitting `provideTokenStreamCoordinator` (:279-373) into functions/builder touches the `synchronized(repository)` onBundlePublished install + baseline publish (:302-316), the drift-checked `streamConnectionProvider` (:317-334), and the hooks wiring — all interlock-sensitive (batch2 §4.1-4.3 established these as wiring logic a constructor cannot host; the batch2 split deliberately left this body untouched). Zero behavior gain, medium interlock risk, gating batch → defer to a non-gating batch.

---

## 11. Implementation ordering & write-domain analysis

| Lane | Items | Production files | Test files |
|---|---|---|---|
| **A** | F1 + ControllerModule-note | StatusAggregator.kt, StatusAggregatorImpl.kt, StatusFetchService.kt (del), SlimStatusFetchCache.kt (del), StatusModule.kt, SessionSyncCoordinator.kt, SseDispatchHost.kt, **ControllerModule.kt** (:216/:233-237 F1 + :284-291 note — same file, hence same lane), SessionBusyStatusMapping.kt, + kdoc-only: StatusSnapshot/SessionSnapshotProvider/BootstrapRunner/AuthorityOp/StreamingModule/ServerCompatProfile | StatusAggregatorImplTest.kt (rewrite), SlimStatusFetchCacheTest.kt (del), SessionSyncCoordinatorStatusFeedTest.kt, SseEventStreamBridgeWiringTest.kt, ServiceSseConnectionOwnerResyncTest.kt, RetryQueueWireTest.kt, B2RouteWiringSequenceTest.kt |
| **B** | F2 + F4a | **OrchestratorViewModel.kt** (F2 :102/:126/:232 + F4a :304-423 — same file, hence same lane), SessionViewModel.kt, HostProfileController.kt, SessionMutationActions.kt, RefreshOrchestrator.kt (:238 only), DraftSessionOrchestrator.kt, LegacySseHandler.kt, SettingsManager.kt, NavigationPrefs.kt, InteractionGateway.kt, InteractionRepository.kt, OpenCodeRepository.kt, Permission.kt (kdoc), SlimAggregationOutcome.kt (kdoc) | **OrchestratorViewModelPassThroughTest.kt** (F2 :78/:107 + F4a :240-366 — same lane), SessionViewModelPassThroughTest.kt, SettingsManagerTest.kt, T3RepositoryExtractFreezeTest.kt, InteractionGatewaySlimQuestionsTest.kt (+1 test) |
| **C** | F4b + F5a | StatusPollOrchestrator.kt, SessionListActions.kt (delegate body), PartExpandState.kt | none |
| **D** | bF2 | ChatDerivedState.kt | none (ChatScaffoldSaveableTest must stay green unmodified) |

**Deliberate non-overlaps**: RefreshOrchestrator.kt appears only in lane B (:238 deletion; F5b deferred, F4b touches only SessionListActions' delegate). MainViewModelTestBase.kt/ForkSessionTest.kt are touched by **no** lane (F1: SSC constructed without the removed param, MainViewModelTestBase.kt:246-256 — verified; F5b deferred). ChatScaffold.kt untouched by all lanes (F4a keeps VM signatures; bF2 stays in ChatDerivedState).

**Landing order if serialized**: C → D → B → A (ascending churn; A's test rewrite is the batch's long pole). If parallel: 4 lanes, any merge order; A/B share nothing.

---

## 12. Cross-item risks & follow-ups

1. **F1 test-rewrite fidelity** (dominant batch risk): §3.4's seed-helper must replicate the deleted adapter's op construction (scopeKey/requestToken/localBefore discipline). Mitigation: helper copies it verbatim; `scope derivation is consistent` + `U-PUBLISH` tests pin the survivors.
2. **F4a's one intentional behavior delta** (legacy respondPermission error surfacing): called out in §5.2; reviewers confirm the micro-fix test + the 6 rewritten VM tests.
3. **bF2 recomposition**: §8.4; bounded; do not couple with any "improvement".
4. **Read-side drift discipline**: no lane may delete `StatusAggregator`/`StatusAggregatorImpl`-read-side, `MarkSourceFailed`, or `launchLoadSessionStatus`'s failure semantics — all logged below.

**Follow-ups (log, do not implement):**
- **F6 (new, from §3.1 Fact 4)**: the aggregator **read side** is production-consumer-less at this head (no injector of `StatusAggregator`; `stateAtNow`/`globalState` referenced only in kdocs + test fakes). Full retirement (StatusAggregator, StatusAggregatorImpl, StatusModule, GlobalBusyState projection, ~600 rewritten test lines from F1) is a product/seam decision — same category as batch1's F3 (inert preserved seams). If the FGS idle-debounce is never re-introduced, a dedicated deletion batch should take the whole `service/status` projection stack.
- **F7 (carried)**: batch1-F3 (slim fan-out seam has no production entry trigger) — unchanged by this batch.
- **F8 (new, from §7)**: F5b deferral triggers (a)/(b)/(c) — revisit when SendOrchestrator's loader arm moves or a loader pair-seam exists.
- **F9 (new)**: ControllerModule.kt:46-53's "internal classes can't use `@Inject constructor`" folklore (batch2 §3.1 disproved it; batch2-F1) — still uncorrected; fold into any future ControllerModule comment pass.

---

## 13. Verification plan (per-lane gates; mandatory before handoff)

1. **All lanes**: `./scripts/check.sh` green (compile + `testDebugUnitTest`); LSP diagnostics clean after each edit; each lane lands green or splits smaller.
2. **Lane A targeted**: `--tests "*StatusAggregatorImplTest*" --tests "*SessionSyncCoordinatorStatusFeedTest*" --tests "*RetryQueueWireTest*" --tests "*B2RouteWiringSequenceTest*" --tests "*ServiceSseConnectionOwnerResyncTest*" --tests "*SseEventStreamBridgeWiringTest*"`; grep proofs: zero `StatusAggregatorInput` / `StatusFetchService` / `SlimStatusFetchCache` in `app/src/main`; zero `applySseStatus|markRequestFailed` call sites; `StatusAggregator` + `stateAtNow` + `toSessionBusyStatus` retained; Hilt graph validates via `assembleDebug` (StatusModule rewiring).
3. **Lane B targeted**: `--tests "*OrchestratorViewModelPassThroughTest*" --tests "*SessionViewModelPassThroughTest*" --tests "*SettingsManagerTest*" --tests "*UnifiedNavTest*" --tests "*T3RepositoryExtractFreezeTest*" --tests "*InteractionGateway*"`; grep proofs: zero `settingsManager.lastRoute =` in `app/src/main` (10 sites gone); `KEY_LAST_NAV_PAGE` + getter migration present in NavigationPrefs.kt; zero `SlimapiPermission`/`SlimapiQuestion` write-method refs (`replySlimapiQuestion|rejectSlimapiQuestion|respondSlimapiPermission`) outside kdoc history; migration tests :426-464 green.
4. **Lane C targeted**: `--tests "*SessionListActionsTest*" --tests "*StatusPollingDowngradeRegressionTest*"` (the slim-gating net — unchanged and green); grep proof: `OpenCodeRepository` gone from StatusPollOrchestrator.kt; gates at :147/:170 read `connectionRepository`.
5. **Lane D targeted**: `--tests "*ChatScaffoldSaveableTest*"` + diff review of ChatDerivedState.kt against §8.3 (sticky `?.let` preserved; SideEffect placement; no other edits).
6. **Boundary re-verification (reviewers)**: §3.6/§5.3/§5.5 proofs — the slim gates enumerated there must diff-clean except the documented param retypes; `grep -n "slimConnection\|usesSlimStatusFanOut" app/src/main/java/cn/vectory/ocdroid/ui/controller/StatusPollOrchestrator.kt` shows only `connectionRepository.usesSlimStatusFanOut` at the two gate sites.
7. **Batch-level**: final `./scripts/check.sh` on the integration commit; `./scripts/check.sh --full` (lint + coverage) before the release tag. No emulator/instrumented runs (no behavior change reachable by UI except F4a's error-path micro-fix, which is unit-covered; device-safety policy).

---

### Verification appendix — corrected explorer claims (for the gate reviewer)

| Explorer claim | Verified truth at `9d1efb8d` | Design consequence |
|---|---|---|
| F1: "stateAtNow() (:330), the read-side StateFlows… STAY (live)" | Read side is retained but **also consumer-less** (§3.1 Fact 4) | Retained per scope; F6 logged with evidence |
| F4: routeToken marked `@Suppress("UNUSED_PARAMETER")` (InteractionGateway.kt:263-305) | No such annotation; params are simply unused (Kotlin doesn't warn on params); the file's only @Suppress is :187 on `getSlimapiQuestions` | Substance unchanged (identity-equivalence holds); §5.1 corrected |
| F5b: SendOrchestrator "NARROW — only updateSessionArchived + sendMessage" | Also feeds `launchLoadMessages` (:271-281 → Message + FileVcs); true footprint 4/6 | F5b fully deferred (§7) |
| bF2: "freshness over memoization" must be preserved | True, **plus** the sticky last-non-null semantics (`?.let` never clears) which the explorer didn't mention | §8.2 — derivedStateOf alternative rejected on this ground |
| bF3: "already done" | Confirmed exactly (§9) | NO-OP |
| ControllerModule: classes "LIVE elsewhere (49 matches)" | Confirmed; stronger: **no Hilt bindings ever exist** (private ctor; RepositoryNetworkGraph-owned) | §10 note rewrite |
