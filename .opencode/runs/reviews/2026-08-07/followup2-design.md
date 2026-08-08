# ocdroid Architecture-Debt Follow-up Batch 2 — Design SSOT (bundle `archdebt-followup2-20260808`)

- **Date**: 2026-08-08
- **Head OID**: `2e6e5331` (= origin/main = annotated tag v0.21.7, includes follow-up batch 1: F1/F2/F4/F5a/bF2/ControllerModule-note)
- **Scope**: exactly 2 items — **NIT** (OrchestratorViewModel `forceNavigateToSessions` stale kdoc) + **F6** (read-side `StatusAggregator` retirement, logged in batch-1 design §12-F6).
- **Reference**: `/home/mar/personal_projects/ocdroid/.opencode/runs/reviews/2026-08-07/followup-design.md` — esp. §3.1 Fact 4 (read side is *also* production-consumer-less), §3.3 (F1 deletion surface), §12-F6 (F6 logged with evidence).
- **Verification basis**: every file:line below was re-verified at `2e6e5331` by reading the actual code + exhaustive greps (`StatusAggregator`, `GlobalBusyState`, `isKeepAlive`, `SessionBusyStatus`, `SessionStatusKey`, `toSessionBusyStatus`, `stateAtNow`, `globalState`, `globalBusy`, `statusByKey`, `StatusSnapshot`, `import cn.vectory.ocdroid.service.status.*`, `EntryOrigin.`, `usesSlimStatusFanOut|isSlimActive|slimConnection`) over both `app/src/main` and `app/src/test`. The orchestrator's hypothesis was independently re-derived; it is **substantively correct on liveness/scope**, with **six completeness corrections** (kdoc sweep was under-scoped; one test file wrongly flagged; one KEEP row needs a kdoc edit) — see the corrections appendix at the end of §5.

---

## 1. Executive summary

| Item | Decision (one line) | Status |
|---|---|---|
| **F6** | **Total read-side retirement.** Delete `StatusAggregator` (interface + `GlobalBusyState` + `isKeepAlive`), `StatusAggregatorImpl`, `StatusModule`, `SessionBusyStatus`/`SessionStatusKey`, `SessionBusyStatusMapping` (`toSessionBusyStatus`) — 742 production lines in 5 files — plus `StatusAggregatorImplTest` (889 lines), 5 stale test imports, and a 16-site production kdoc sweep. The task's parenthetical ("保留 SSE-driven 的 applySseStatus/stateAtNow") is based on stale understanding: `applySseStatus` was a *write-side* adapter **already deleted by F1**; `stateAtNow` lives on `StatusAggregator`, which has **zero** production consumers (re-proven below). There are **no live read paths to preserve**. `StatusSnapshot` / `SlimStatusFanOut` / `SlimFanOutBackoffPolicy` / `SlimFanOutRetryScheduler` stay (independently live). | **IN** |
| **NIT** | Comment-only: fix the stale `forceNavigateToSessions` kdoc (OrchestratorViewModel.kt:160-169) — the `settingsManager.lastRoute` write it describes was deleted in F2 (v0.21.7). Exact rewrite in §4. Zero behavior impact. | **IN** (trivial) |

**Ship list**: both items. No deferred work, no new follow-ups beyond the already-logged F7 (slim fan-out entry trigger, carried from batch 1 §12).

---

## 2. slim/standard boundary investigation (mandatory pre-work)

### 2.1 Mode-flag stack (spot-verified at `2e6e5331`)

- `ServerCompatProfile.slimConnection` (`data/repository/ServerCompatProfile.kt:76`) — single managed write point (:103), `@Volatile`.
- `ConnectionGateway.usesSlimStatusFanOut` (`data/repository/gateway/ConnectionGateway.kt:64`) = `slimConnection`.
- `OpenCodeRepository.usesSlimStatusFanOut` (`data/repository/OpenCodeRepository.kt:441`) forwards the gateway.
- UI mirror `ConnectionState.isSlimActive` (`ui/ConnectionState.kt:152`), written at `ConnectionViewModel.kt:214/:228` and `ConnectionHealthProbe.kt:444/:609`.

### 2.2 The deleted read side contains NO mode flag

Grep proof: `StatusAggregatorImpl.kt` / `StatusAggregator.kt` / `SessionBusyStatus.kt` / `SessionBusyStatusMapping.kt` / `StatusModule.kt` contain **zero** occurrences of `slimConnection` / `usesSlimStatusFanOut` / `isSlimActive`. The derivation (`authorityToAggregate`, StatusAggregatorImpl.kt:242-277) consumes `store.state.authority` identically in both modes — the authority reducer is mode-agnostic; mode only selects *which writer* dispatches `AuthorityOp`s upstream (SSE_LEGACY vs SSE_SLIM vs REST), which is outside the deleted surface.

### 2.3 The live slim/standard status paths do NOT transit the deleted read side

| Live path | Mode | Route (verified) | Touches deleted symbols? |
|---|---|---|---|
| SSE digest relay → authority | slim | `SessionSyncCoordinator.kt:265` (`origin = EntryOrigin.SSE_SLIM`) → `applyStatusViaAuthority` | No |
| SSE legacy relay → authority | standard | `LegacySseHandler.kt:152-156` (`host.applyStatusViaAuthority`, `EntryOrigin.SSE_LEGACY`) | No (only 2 stale *imports* at :8-9) |
| slim cold-start bulk | slim | `StatusPollOrchestrator.kt:147` gate → `launchLoadSessionStatusSlim` | No |
| SSE-loss REST fall-through | both | `StatusPollOrchestrator.kt:170` gate | No |
| slim per-session fan-out | slim | `StreamingModule.kt:114` gate (`!repository.usesSlimStatusFanOut → null`) → `SlimStatusFanOut` → `SlimFanOutRetryScheduler.runSlimFanOut` (:222-244) → `StatusFanOutSummary` sink | No — consumes `StatusSnapshot` (:8, :224), `SlimFanOutBackoffPolicy` (:6), `StatusFanOutSummary` (:7) only |
| snapshot provision | both | `SessionSnapshotProvider.current()` → `SharedStateStoreSessionSnapshotProvider.kt:38-60` returns `StatusSnapshot` | No |

**Verdict**: the retirement removes a pure *projection* with no consumers in either mode; every live write path (authority dispatch) and every live read path (UI slices, `sessionStatuses`, fan-out summaries) is untouched. `slimConnection`/`isSlimActive`/`usesSlimStatusFanOut` gating has **zero diff**. Byte-identical behavior in slim and standard modes.

---

## 3. F6 — read-side aggregator retirement

### 3.1 Liveness findings (re-verified at `2e6e5331`; orchestrator hypothesis confirmed)

**Fact 1 — `StatusAggregator` is injected nowhere.** Full-tree grep for `StatusAggregator` over `app/src/main`: the only *code* occurrences are inside `service/status/` itself — the interface (StatusAggregator.kt:30), the impl (StatusAggregatorImpl.kt:78/83), and the DI module (StatusModule.kt:45 `bindStatusAggregator` + :50-59 `provideStatusAggregatorImpl`). **No main-source constructor parameter, no `@Inject` site, no `Provider<…>`** requests `StatusAggregator` anywhere. A Hilt binding with no requester provisions an instance that is constructed and then observed by nobody (the impl's `init` collect at StatusAggregatorImpl.kt:186-193 spins a coroutine on `UiApplicationScope` purely to write three `StateFlow`s nobody reads).

**Fact 2 — every production reference outside `service/status/` is kdoc/comment-only.** Verified sites (all comments, no code):

| File:line | Content |
|---|---|
| `service/streaming/SourceActivation.kt:25, :48` | `[…StatusAggregator.stateAtNow]` kdoc links |
| `service/streaming/BootstrapRunner.kt:40-41` | kdoc "the aggregator's read side … [StatusAggregatorInput] is retired" |
| `service/streaming/SessionSnapshotProvider.kt:19` | kdoc "so the aggregator can positively enforce" |
| `service/status/SlimStatusFanOut.kt:17, :36` | kdoc `[StatusAggregatorImpl.refresh]` — **doubly stale** (refresh deleted in F1) |
| `service/status/StatusSnapshot.kt:9-10, :34` | kdoc "retained for the read-side derivation pipeline ([StatusAggregatorImpl.publishFromState])" |
| `data/repository/SlimapiStatusOutcome.kt:12` | kdoc "(T7 reconcile / T11 StatusAggregator)" |
| `data/repository/OpenCodeRepository.kt:439, :1000, :1059` | kdoc comments |
| `data/repository/http/SlimapiErrorCodes.kt:22` | kdoc "`StatusAggregatorImpl` / `ProcessStatusPoller`" (both gone after F6) |
| `data/state/AuthorityState.kt:175, :204` | kdoc references to `StatusAggregatorImpl` |
| `ui/AuthorityReducer.kt:270, :724` | kdoc "consumer of origin is StatusAggregatorImpl.fresh" / `[GlobalBusyState.Unknown]` |
| `ui/CrossSliceFieldsReducer.kt:588` | kdoc `[StatusAggregatorImpl.currentScope]` |
| `ui/SharedStateStore.kt:61, :199-200` | kdoc "globalState degraded" / `[StatusAggregatorImpl.currentScope]` |
| `ui/controller/sse/LegacySseHandler.kt:8, :9` | **code (imports)** — `SessionStatusKey` + `toSessionBusyStatus` imported but used nowhere in the 260-line body (read in full; the `session.status` path dispatches `host.applyStatusViaAuthority` at :152) |

**Fact 3 — the read API has zero production call sites.** `globalState` / `globalBusy` / `statusByKey` appear outside `service/status/` only at `SharedStateStore.kt:61` (kdoc). `stateAtNow` appears outside only at `SourceActivation.kt:25/:48` (kdoc). The intended consumer (FGS lifecycle coordinator, Lane C) was removed in Phase 1 — as logged in batch-1 §3.1 Fact 4.

**Fact 4 — the leaf types exist solely for the aggregator.** Import grep over `app/src/main` for `service.status.{StatusAggregator, GlobalBusyState, SessionBusyStatus, SessionStatusKey, SessionBusyStatusMapping}`: the *only* hit is LegacySseHandler.kt:8 (stale). `toSessionBusyStatus` (SessionBusyStatusMapping.kt:18-23) has exactly one production caller: `StatusAggregatorImpl.authorityToAggregate` (:260). `SessionBusyStatus.Fresh` is referenced only in `StatusAggregatorImplTest.kt:158`. `isKeepAlive` (StatusAggregator.kt:139) has zero call sites outside its own file.

**Fact 5 — `StatusSnapshot` is independently LIVE.** Production imports: `SlimFanOutRetryScheduler.kt:8` (used at :76, :224), `SessionSnapshotProvider.kt:3` (return type :29), `SharedStateStoreSessionSnapshotProvider.kt:5` (constructed :60). **KEEP** — but its kdoc (:9-10, :34) must be rewritten: it currently claims it was "retained for the read-side derivation pipeline ([StatusAggregatorImpl.publishFromState])", which will be false.

**Fact 6 — the task's "keep applySseStatus/stateAtNow" parenthetical is stale.** `applySseStatus` was a **write-side adapter** of `StatusAggregatorInput`, deleted by F1 in v0.21.7 (batch-1 design §3.3 #3; LegacySseHandler.kt:134-139 records the deletion). `stateAtNow` IS a read method — but its host interface has no consumer (Fact 1/3). **There is no live read path to preserve**; the honest disposition is total retirement. Preserving `stateAtNow` would mean preserving a 456-line impl + DI module + projection model to serve a kdoc link — a textbook dead abstraction (violates user constraint #1).

### 3.2 Decision: total read-side retirement (no partial variant)

Delete the whole read side **including** the leaf types. Rationale:

1. **No consumer on either side.** The interface's contract kdoc (StatusAggregator.kt:6-12) says it exists so "Lane A (impl) and Lane C (consumer) can be built in parallel". Lane C was removed in Phase 1. A contract with zero implementors-except-one and zero consumers is dead weight.
2. **The leaf types are not independently reusable.** `SessionBusyStatus` duplicates what `cn.vectory.ocdroid.data.model.SessionStatus` (idle/busy/retry) already carries for every live path; `SessionStatusKey`'s composite scoping lives on in `data/state/ScopeKey` (AuthorityState.kt:180-184) which is the *live* scoping mechanism; `GlobalBusyState`'s tri-state verdict has no observer; `toSessionBusyStatus` maps into the deleted enum.
3. **Partial retirement is strictly worse.** Keeping `GlobalBusyState`/`SessionBusyStatus` "for future use" is the inert-preserved-seam category batch-1 F3 already judged; keeping the interface without the impl is a dead contract; keeping the impl without DI is dead code. Each partial variant preserves ~300-700 lines whose only function would be documentation — and the *history* is already documented in the kdoc rewrites below + the git record (v0.21.7 tag).
4. **The projection semantics being deleted are lossless to lose**: every live verdict the app actually acts on is derived from `sessionStatuses` (the `SessionListState` projection written by `reduceAuthority`, pinned by `ArchiveSubtreeAuthorityPruneTest` §B10) and the `StatusFanOutSummary` sink — neither transits the aggregator.

### 3.3 Exact deletion surface (production)

| # | File | Lines | Action |
|---|---|---|---|
| 1 | `service/status/StatusAggregator.kt` | 141 | **DELETE file** (interface + `GlobalBusyState` enum + `isKeepAlive` ext + kdoc) |
| 2 | `service/status/StatusAggregatorImpl.kt` | 456 | **DELETE file** |
| 3 | `service/status/StatusModule.kt` | 61 | **DELETE file** (`@Binds` + `@Provides`; zero requesters — no other module edits needed; Hilt auto-discovers `@InstallIn` modules, nothing else references `StatusModule`) |
| 4 | `service/status/SessionBusyStatus.kt` | 59 | **DELETE file** (enum + `SessionStatusKey` data class) |
| 5 | `service/status/SessionBusyStatusMapping.kt` | 25 | **DELETE file** (`toSessionBusyStatus`) |
| 6 | `ui/controller/sse/LegacySseHandler.kt:8-9` | 2 | **Delete the 2 stale imports** (`SessionStatusKey`, `toSessionBusyStatus`) — body verified clean |

**Production deletion total: 742 lines + 2 import lines.**

**Kept verbatim (live, zero edits except where noted):**
- `service/status/StatusSnapshot.kt` (43) — KEEP; kdoc rewrite (#K6 below).
- `service/status/SlimStatusFanOut.kt` (317, defines `SlimStatusFanOut` + `StatusFanOutSummary` + `foldStatusOutcomes`) — KEEP; kdoc rewrite (#K5).
- `service/status/SlimFanOutBackoffPolicy.kt` (56) — KEEP untouched.
- `service/streaming/SlimFanOutRetryScheduler.kt` (249) — KEEP untouched (no aggregator refs).
- `data/state/AuthorityState.kt` `Coverage` / `EntryOrigin` / `ScopeKey` / `scopeKeyOf` — KEEP (live; used by reducer + snapshot provider + prune paths). Only kdoc edits (#K11).
- `AuthorityOp.MarkSourceFailed` + its reducer branch (`AuthorityReducer.kt:732` `applyMarkFailed`) — KEEP (batch-1 §3.7 ruling: preserved reducer semantics, out of scope).

### 3.4 Production kdoc sweep (comment-only, 16 sites — Before → After)

| # | File:line | Before (gist) | After |
|---|---|---|---|
| K1 | `SourceActivation.kt:24-26` | "The coordinator consults […StatusAggregator.stateAtNow] at handoff commit to decide the layer transition" | "The coordinator consults the status authority at handoff commit to decide the layer transition. (D4-B M3's `StatusAggregator.stateAtNow` consumer was retired in F6 — the read side had no production consumer.)" |
| K2 | `SourceActivation.kt:47-50` | "The coordinator consults […StatusAggregator.stateAtNow] at commit to decide the layer transition + whether to retire the supplemental poller." | "The coordinator consults the status authority at commit to decide the layer transition + whether to retire the supplemental poller." |
| K3 | `BootstrapRunner.kt:39-41` | "for the caller to feed into the aggregator's read side (via the authority derivation pipeline, now that [StatusAggregatorInput] is retired)" | "for the caller's status-authority pipeline (both the `StatusAggregatorInput` write side (F1) and the aggregator read side (F6) are retired — authority state is consumed via `SharedStateStore` projections directly)." |
| K4 | `SessionSnapshotProvider.kt:16-21` | "The new contract returns [StatusSnapshot] so the aggregator can positively enforce registered-workdir coverage AND positively classify an active id…" | "The new contract returns [StatusSnapshot] so the caller (slim fan-out retry path) can positively enforce registered-workdir coverage AND positively classify an active id returned by `/session/status` that is NOT in `sessionsById` as `Busy` (not skipped). (The pre-F6 consumer was the retired `StatusAggregator`.)" |
| K5 | `SlimStatusFanOut.kt:17, :36` | "Differs from [StatusAggregatorImpl.refresh] (the host-wide bulk L3 path)" / "[StatusAggregatorImpl.refresh] stays byte-for-byte unchanged" | :17 → "Differs from the host-wide bulk L3 path (`StatusPollOrchestrator.launchLoadSessionStatus`; the former `StatusAggregatorImpl.refresh` was retired in F1)". :36 → "the legacy bulk path (`StatusPollOrchestrator.launchLoadSessionStatus`) stays byte-for-byte unchanged." |
| K6 | `StatusSnapshot.kt:9-10, :34` | "Carried the deleted [StatusAggregatorInput] feed surface (retired in F1); retained for the read-side derivation pipeline ([StatusAggregatorImpl.publishFromState])" | "Carried the deleted `StatusAggregatorInput` feed surface (retired in F1). Retained for its independent live consumers: `SessionSnapshotProvider` / `SharedStateStoreSessionSnapshotProvider` / `SlimFanOutRetryScheduler` (the aggregator read side itself was retired in F6)." :34 → drop the trailing "with the identity they pair it with in the deleted [StatusAggregatorInput] feed surface." → "with the identity scope they operate in." |
| K7 | `SlimapiStatusOutcome.kt:12` | "so the caller (T7 reconcile / T11 StatusAggregator) never pattern-matches" | "so the caller (T7 reconcile / slim status fan-out) never pattern-matches" |
| K8 | `OpenCodeRepository.kt:439-440` | "StatusAggregator 是否走 slim 扇出（vs legacy bulk `/session/status`）" | "session status 是否走 slim 扇出（vs legacy bulk `/session/status`）" |
| K9 | `OpenCodeRepository.kt:999-1001` | "so callers ([launchLoadSessionStatus] slim cold-start + […StatusAggregatorImpl] L2Idle/L3 disconnect fallback) consume it identically" | "so callers ([launchLoadSessionStatus] slim cold-start) consume it identically" |
| K10 | `OpenCodeRepository.kt:1059` | "so the caller (T7 reconcile / T11 StatusAggregator) never pattern-matches" | "so the caller (T7 reconcile / slim status fan-out) never pattern-matches" |
| K11 | `AuthorityState.kt:175, :203-205` | :175 "The consistency assertion in StatusAggregatorImpl guards steady-state agreement." / :204 "TTL/liveness is now solely computed by StatusAggregatorImpl.project from sourceTimeMs — the freshness field had no consumers (discovery §0.C in design)." | :175 → "Steady-state agreement was historically pinned by the retired StatusAggregatorImpl's consistency test (F6); the conservative SOURCE separation above remains the ruling." :203-205 → "§U-MN8: Freshness classification was eliminated — the freshness field had no consumers (discovery §0.C in design). (Its former consumer, `StatusAggregatorImpl.project`'s TTL/liveness computation, was retired in F6.)" |
| K12 | `AuthorityReducer.kt:269-273` | "The CURRENT sole behavioral consumer of origin is StatusAggregatorImpl.fresh = (origin == REST); both OPTIMISTIC and SSE are fresh=false, so the mixed semantic has NO behavioral impact today." | "The sole behavioral consumer of origin was the retired StatusAggregatorImpl's fresh derivation (F6); origin is still written/stored (§B9 ServerBusy classification) and the mixed semantic has NO behavioral impact." |
| K13 | `AuthorityReducer.kt:720-726` | "`lastSuccessTimeMs = -1` so the derived aggregator `project()` returns [GlobalBusyState.Unknown] (cold-start / stale-success guard) — matching the old markFailed → Unknown semantics" | "`lastSuccessTimeMs = -1` marks the coverage as failed/stale (cold-start guard) — preserving the FGS-lifecycle guarantee that a failure never reads as idle (the former aggregator projection's `Unknown` verdict, retired in F6)." |
| K14 | `CrossSliceFieldsReducer.kt:587-588` | "Mirrors the derivation in [SharedStateStore.authorityScope] and [StatusAggregatorImpl.currentScope] so prune operations…" | "Mirrors the derivation in [SharedStateStore.authorityScope] so prune operations…" |
| K15 | `SharedStateStore.kt:60-62` | "coverage was written under a key the aggregator never reads → globalState degraded to Unknown" | "coverage was written under a key no reader consulted → the (F6-retired) global busy projection degraded to Unknown" |
| K16 | `SharedStateStore.kt:196-201` | "so coverage is written under the SAME key the aggregator reads ([StatusAggregatorImpl.currentScope] derives identically from `identityStore.currentIdentity.value`). MUST match the aggregator's derivation — no second scope source." | "so coverage is written under the connection identity's key. Scope construction is centralized in `scopeKeyOf` — no second scope source." |

### 3.5 Test dispositions (exact)

| Test file | Verified state at `2e6e5331` | Disposition |
|---|---|---|
| `service/status/StatusAggregatorImplTest.kt` (889 lines) | Tests the deleted impl end-to-end (`StatusAggregatorImpl` ctor at :208/:506/:537/:760/:809/:833; `STATUS_TTL_MS`; `SessionStatusKey`/`SessionBusyStatus`/`GlobalBusyState` assertions throughout). Sole test-dir user of `SessionBusyStatus.Fresh` (:158). | **DELETE file** |
| `service/streaming/SlimFanOutRunnerGateTest.kt:10-12` | Imports `GlobalBusyState`/`SessionBusyStatus`/`SessionStatusKey` — body uses **none** (grep-verified; legit imports at :13-15 are `SlimStatusFanOut`/`StatusFanOutSummary`/`StatusSnapshot`, all kept) | Delete 3 import lines only |
| `service/streaming/SlimFanOutRetryWiringTest.kt:7` | Import `StatusAggregator` — body uses **none** (legit imports :8-9 kept) | Delete 1 import line |
| `ui/controller/ConnectionCoordinatorTest.kt:4` | Import `StatusAggregator` — body uses **none** (only occurrence in file) | Delete 1 import line |
| `ui/ArchiveSubtreeAuthorityPruneTest.kt:22-23, :28-30` | Kdoc :22-23 "stayed Busy in the aggregator's derived view forever"; :28-30 "Deliberately NOT in [AuthorityReducerTest] / […StatusAggregatorImplTest] (CORE lane owns those files)" | Kdoc rewrite: :22-23 → "stayed Busy in any downstream busy projection forever"; :28-30 → "Deliberately NOT in [AuthorityReducerTest] (CORE lane owns that file)" (drop the deleted-class reference). **No code change** |
| `ui/controller/SessionSyncCoordinatorStatusFeedTest.kt:41` | Kdoc "The `StatusAggregatorInput` feed surface was retired in the archdebt follow-up (F1)" — historical and **still accurate** | **KEEP verbatim** (no edit) |
| `ui/SessionListActionsTest.kt`, `ui/AuthorityReducerTest.kt` | **Zero** references to any deleted symbol (grep-verified) | No action |
| `service/streaming/SlimFanOutRetrySchedulerTest.kt`, `ui/controller/RetryQueueWireTest.kt` | Import only kept symbols (`SlimFanOutBackoffPolicy`/`StatusFanOutSummary`/`StatusSnapshot`) | No action |

### 3.6 Ordered steps

1. Delete the 5 production files (§3.3 #1-#5) + the 2 LegacySseHandler imports (#6). Compile breaks land only at the kdoc-link sites — all comment-only.
2. Production kdoc sweep (§3.4, K1-K16).
3. Test dispositions (§3.5): delete `StatusAggregatorImplTest.kt`; clean 5 import lines across 3 test files; rewrite the ArchiveSubtreeAuthorityPruneTest kdoc.
4. `./scripts/check.sh` (compile + `testDebugUnitTest`) — the Hilt graph is validated by `assembleDebug` inside check.sh; `StatusModule` removal is safe iff no `StatusAggregator` requester exists (Fact 1 is the proof; compile is the enforcement).
5. Targeted test runs + grep proofs (§5).

### 3.7 slim/standard impact

None — see §2. The deleted projection was mode-agnostic and consumer-less; every live mode gate (`StatusPollOrchestrator.kt:147/:170`, `StreamingModule.kt:114`, `ConnectionGateway.kt:64`) and every live write path (SSE_LEGACY :155 / SSE_SLIM :265 / REST / OPTIMISTIC :423) is untouched. Byte-identical in both modes.

### 3.8 Risks

1. **Hilt graph break** — if Fact 1 were wrong, removing `StatusModule` fails at `assembleDebug` with a missing-binding error. This is the *desired* failure mode (fail-closed, compile-time). Probability ~0 (grep-verified); enforcement is automatic.
2. **Hidden reflective/string reference** — no reflection over these types exists (no `Class.forName` / navigation by name in the codebase for service classes); grep over raw strings `StatusAggregator` covers it (Fact 2's table is the complete hit list).
3. **Scope creep** — do NOT let the lane drift into: `EntryOrigin` (still written at 4 live sites + §B9), `AuthorityState.Coverage` (live), `MarkSourceFailed` (batch-1 §3.7 preserved-seam ruling), `StatusSnapshot` (Fact 5), or `SlimFanOutRetryScheduler`'s dormant-but-preserved seam (logged F7, carried).
4. **Kdoc dangling links** — the 16-site sweep (§3.4) is the complete set; the §5 grep proofs make any miss visible (a `[StatusAggregator…]` link left in a kept file would re-appear in the post-deletion grep).

---

## 4. NIT — OrchestratorViewModel.forceNavigateToSessions kdoc

**Verified**: OrchestratorViewModel.kt:160-169 reads "Behavior is identical to the prior inline impl: writes settingsManager.lastRoute = Sessions + bumps navEpoch". The `settingsManager.lastRoute` persistence write was deleted in F2 — the sibling kdocs already record this: `setLastRoute` :98-99 ("F2: the `settingsManager.lastRoute` persistence write was a redundant mirror of the in-memory [NavState.lastRoute]; deleted (F2).") and `requestNavigate` :123-124. The body (:170 `internal fun forceNavigateToSessions() = requestNavigate(NavRoute.Sessions)`) is correct; only the kdoc is stale.

**Exact rewrite** (replace lines 160-169, matching the sibling-kdoc F2 style):

```kotlin
    /**
     * §unified-nav (A1 optional cleanup): force-navigate to Sessions is now
     * delegated to [requestNavigate] (Sessions) so there is ONE explicit nav-
     * command API. Behavior is identical to the prior inline impl: writes
     * [NavState.lastRoute] = Sessions + bumps [NavState.navEpoch]
     * (requestNavigate always bumps). F2: the redundant
     * settingsManager.lastRoute persistence write was deleted (see
     * [setLastRoute] / [requestNavigate]). Used by the MainActivity deep-link
     * fail-safe (malformed session id → Sessions) where [setLastRoute] would
     * short-circuit when the mirror already reads "sessions" (which it does
     * on Files/Git — those destinations do not update navState).
     */
```

Zero behavior impact; comment-only. Disjoint file set vs F6 → order-independent; may land in the same commit series.

---

## 5. Cross-item risks & verification plan

1. **Gate (mandatory)**: `./scripts/check.sh` green (compile + `testDebugUnitTest`) on the integration commit; Hilt graph validated by `assembleDebug` inside check.sh (`StatusModule` removal). `./scripts/check.sh --full` before any release tag. No emulator/instrumented runs — no behavior change reachable by UI (comment edits + dead-code deletion only); device-safety policy applies.
2. **F6 targeted tests**: `--tests "*SlimFanOut*"` (RunnerGateTest / RetrySchedulerTest / RetryWiringTest) `--tests "*ConnectionCoordinatorTest*" `--tests "*ArchiveSubtreeAuthorityPruneTest*" `--tests "*SessionSyncCoordinatorStatusFeedTest*"` `--tests "*SessionListActionsTest*"` `--tests "*AuthorityReducerTest*"` `--tests "*StatusPollingDowngradeRegressionTest*"` — the slim-gating + authority-projection safety net, unchanged and green.
3. **F6 grep proofs (post-change, all must return ZERO hits in `app/src`)**:
   - `StatusAggregator` / `GlobalBusyState` / `isKeepAlive` / `SessionBusyStatus` / `SessionStatusKey` / `toSessionBusyStatus` / `stateAtNow` / `statusByKey` / `globalBusy` — zero in `app/src/main` **and** `app/src/test`.
   - `import cn\.vectory\.ocdroid\.service\.status\.` in `app/src` — only `SlimFanOutBackoffPolicy` / `SlimStatusFanOut` / `StatusFanOutSummary` / `StatusSnapshot` remain.
   - `service/status/` directory listing — exactly 3 files: `SlimFanOutBackoffPolicy.kt`, `SlimStatusFanOut.kt`, `StatusSnapshot.kt`.
4. **Boundary re-verification (reviewers)**: `git diff` on `ServerCompatProfile.kt`, `ConnectionGateway.kt`, `StatusPollOrchestrator.kt`, `StreamingModule.kt`, `SessionSyncCoordinator.kt`, `SseDispatchHost.kt` must be **empty** (the slim/standard gate files are untouched); `grep -n "usesSlimStatusFanOut" app/src/main` pre/post must be identical.
5. **NIT proof**: `git diff` on OrchestratorViewModel.kt contains only the kdoc block (:160-169); no code lines.
6. **Cross-item risk**: none — disjoint file sets, comment-only NIT vs deletion F6. Land as two commits (F6, then NIT) or one; either satisfies the gate.

### Corrections appendix — orchestrator-hypothesis deltas (for the rev-glm gate reviewer)

| Orchestrator claim | Verified truth at `2e6e5331` | Design consequence |
|---|---|---|
| Read side is production-consumer-less; no DI requester; leaf types serve only the aggregator; `StatusSnapshot` independently live | **Confirmed in full** (§3.1 Facts 1-5) | Total retirement (§3.2) |
| "Other test files … may be stale": `AuthorityReducerTest` | **Clean** — zero references to any deleted symbol | Removed from the edit list (§3.5) |
| `SessionSyncCoordinatorStatusFeedTest` kdoc (:41) possibly stale | **Accurate historical F1 note** | KEEP verbatim — no edit |
| `StatusSnapshot.kt` — "KEEP (independently live)" | Correct, **but its own kdoc (:9-10, :34) claims it was retained for the deleted derivation pipeline** | KEEP + kdoc rewrite added (K6) |
| Kdoc sweep = SourceActivation / AuthorityReducer / SlimapiStatusOutcome / OpenCodeRepository / SharedStateStore / SlimStatusFanOut | **Under-scoped**: 6 additional sites — BootstrapRunner.kt:40-41, SessionSnapshotProvider.kt:16-21, SharedStateStore.kt:196-201, AuthorityReducer.kt:269-273, AuthorityState.kt:175/:203-205, SlimapiErrorCodes.kt:22, CrossSliceFieldsReducer.kt:587-588; OpenCodeRepository has **3** sites (:439, :999-1001, :1059), not 2; ArchiveSubtreeAuthorityPruneTest.kt:22-23 + :28-30 also needs the kdoc edit | Full 16-site prod sweep + 1 test kdoc rewrite (§3.4/§3.5) |
| Candidate retirement table (files/lines) | **Exact**: StatusAggregator.kt 141, StatusAggregatorImpl.kt 456, StatusModule.kt 61, SessionBusyStatus.kt 59, SessionBusyStatusMapping.kt 25, StatusAggregatorImplTest.kt 889 | §3.3/§3.5 — 742 prod + 889 test lines deleted |
