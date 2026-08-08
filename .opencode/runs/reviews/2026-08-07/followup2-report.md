# ocdroid Architecture-Debt Follow-up Batch 2 — Final Report (bundle `archdebt-followup2-20260808`)

- **Date**: 2026-08-08
- **Base → Head**: `2e6e5331` (v0.21.7) → `fix/archdebt-followup2` (`b094fbfe` + `5be76e82`)
- **Release**: v0.21.8 (patch from v0.21.7)
- **Scope**: exactly 2 items — **NIT** + **F6** (read-side `StatusAggregator` retirement). Deferred (unchanged): F5b / bF3 / ControllerModule-structural.
- **Design SSOT**: `.opencode/runs/reviews/2026-08-07/followup2-design.md`
- **Gate**: rev-glm single-node **9.7 / 10 APPROVED** (axes: 代码质量 9.5 / 架构 10 / 测试 9.5 / slim 边界 10 / 抽象 10).

---

## 1. Status: DONE — shipped v0.21.8

## 2. Per-item outcome

### NIT — OrchestratorViewModel `forceNavigateToSessions` kdoc (trivial, DONE)
The `:160` kdoc described a `settingsManager.lastRoute = Sessions` write that F2 (v0.21.7) deleted. Rewritten comment-only to reference `[NavState.lastRoute]` / `[NavState.navEpoch]` + the F2 deletion note, matching the sibling-kdoc style (`setLastRoute` :98-99, `requestNavigate` :123-124). Zero behavior impact; `internal fun forceNavigateToSessions()` body unchanged.

### F6 — read-side aggregator retirement (DONE)
Retired the entire `StatusAggregator` read side (logged follow-up from batch1 §12-F6). Oracle independently re-verified at `2e6e5331` (§3.1 Facts 1-6) and confirmed the read side is production-consumer-less:
- `StatusAggregator` has **zero DI requesters** (the only binding `StatusModule.bindStatusAggregator` provisions an instance nobody injects).
- `globalState` / `globalBusy` / `statusByKey` / `stateAtNow` have **zero production call sites** (only kdoc refs + test fakes).
- The leaf types (`SessionBusyStatus` / `SessionStatusKey` / `toSessionBusyStatus` / `GlobalBusyState` / `isKeepAlive`) exist solely to serve the aggregator.
- `StatusSnapshot` / `SlimStatusFanOut` / `SlimFanOutBackoffPolicy` / `SlimFanOutRetryScheduler` are **independently live** → KEPT.
- The omni task's "保留 SSE-driven 的 applySseStatus/stateAtNow" parenthetical was stale: `applySseStatus` was a **write-side adapter already deleted by F1**; `stateAtNow`'s host has no consumer. **No live read path exists to preserve** — total retirement is the honest disposition (user constraint #1: no dead abstraction).

**Deletion surface**: 742 production lines (5 files) + 889 test lines (1 file) + 7 stale imports + 16-site production kdoc sweep + 1 test kdoc:
- `StatusAggregator.kt` (141), `StatusAggregatorImpl.kt` (456), `StatusModule.kt` (61), `SessionBusyStatus.kt` (59), `SessionBusyStatusMapping.kt` (25) — deleted.
- `StatusAggregatorImplTest.kt` (889) — deleted (only exercised the deleted impl).
- Stale imports cleaned: `LegacySseHandler.kt` (2), `SlimFanOutRunnerGateTest.kt` (3), `SlimFanOutRetryWiringTest.kt` (1), `ConnectionCoordinatorTest.kt` (1).
- 16 kdoc sites (K1-K16) rewritten across 11 files + `ArchiveSubtreeAuthorityPruneTest.kt` test kdoc + `SlimapiErrorCodes.kt:22` (rev-glm 🟡-1 completion of the sweep).

## 3. Testing
- `./scripts/check.sh` green (compile + `testDebugUnitTest`) — verified twice (before + after the 🟡-1 comment fix).
- `./scripts/check.sh --full` green (lint + coverage) before the release gate.
- Safety nets intact and green: `SlimFanOut*`, `ArchiveSubtreeAuthorityPruneTest`, `AuthorityReducerTest`, `StatusPollingDowngradeRegressionTest`, `SessionSyncCoordinatorStatusFeedTest`, `SessionListActionsTest`.
- Hilt graph validated by `assembleDebug` (StatusModule removal is fail-closed at compile time — a missing requester would surface as a missing-binding error; none occurred).
- Grep proofs (post-change, all zero): no `StatusAggregator` / `GlobalBusyState` / `isKeepAlive` / `SessionBusyStatus` / `SessionStatusKey` / `toSessionBusyStatus` / `stateAtNow` / `statusByKey` / `globalBusy` code/import/kdoc-LINK reference survives; remaining literal mentions are intentional F6-historical backtick prose. `service/status/` = exactly 3 files.

## 4. slim/standard boundary (byte-identical)
- The deleted projection contained **no mode flag** (grep-verified) and derived from `store.state.authority` identically in both modes.
- The 6 slim/standard gate files (`ServerCompatProfile`, `ConnectionGateway`, `StatusPollOrchestrator`, `StreamingModule`, `SessionSyncCoordinator`, `SseDispatchHost`) are **absent from the diffstat** → byte-identical.
- Every live write path (SSE_SLIM relay `SessionSyncCoordinator:265`, SSE_LEGACY relay `LegacySseHandler:152`, slim cold-start + SSE-loss gates `StatusPollOrchestrator:147/:170`, slim fan-out `StreamingModule:114`) and read path (UI slices, `sessionStatuses`, `StatusFanOutSummary`) bypasses the deleted read side.
- `slimConnection` / `usesSlimStatusFanOut` / `isSlimActive` gating: zero diff.

## 5. rev-glm 9.5 gate
- Single-node rev-glm (rev-gpt lacks git_ro; rev-glm has it). review_prep binding `rv_20260808-013638_e044adb0_1051778` provisioned + generation ID injected; rev-glm used `git_ro` for all evidence.
- **Score 9.7 / 10 APPROVED** (≥9.5 gate). 1 round, no fixes required by the gate.
- One 🟡 non-blocking finding (SlimapiErrorCodes.kt:22 stale backtick prose) — addressed in commit `5be76e82` (trivial comment-only completion of the F6 sweep the design Fact 2 had mandated).

## 6. Audit
- **Feature branch**: `fix/archdebt-followup2`
  - `b094fbfe` — archdebt: followup batch2 — F6 status read-side retirement + forceNavigateToSessions kdoc NIT (the substantive reviewed commit; rev-glm reviewed this exact diff)
  - `5be76e82` — fix(review): complete F6 kdoc sweep — SlimapiErrorCodes historical note (rev-glm 🟡-1, comment-only)
- **Merge**: feature → main (merge commit created on release)
- **Tag**: v0.21.8 (patch from v0.21.7)
- **shipped = reviewed**: the substantive commit `b094fbfe` is exactly what rev-glm reviewed; `5be76e82` is a comment-only follow-up addressing rev-glm's sole finding (no substantive code delta post-review); this report + the design doc are committed alongside.

## 7. Next step
None — released as v0.21.8. No new follow-ups beyond the carried F7 (slim fan-out entry trigger, unchanged by this batch).
