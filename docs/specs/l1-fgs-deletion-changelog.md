# L1 FGS Deletion — Changelog

> Records the outcome of L1 (delete FGS + background SSE) per
> `state-machine-simplification-v5.3-final.md` §1/§2.1/§6.1/§7.1.
> Branch: `feature/v5lean-l1-fgs` (worktree `.slim/worktrees/delete-fgs`).

## What was deleted (4 green commits)

| Commit | Hash | Content | Tests |
|---|---|---|---|
| 1 | `f48cc267` | Additive: new `ForegroundTransportDropHandler` + `ServiceSseConnectionOwner` `@Provides @Singleton`; `ConnectionCoordinator` rewired (`startSSE`/`cancelSse*`/fg-bg observer + `connectSseAndAwait` seam); `ConnectionHealthProbe` launcher→lambda; `ControllerModule` wired | 4713 pass |
| 2 | `c85c408b` | Delete FGS shell + cluster: `SessionStreamingService` (1466), `StreamingServiceLauncher` (282), `SessionStreamingController` (480), `ServiceShell`, `BootstrapJobHolder` (34), `OptimisticClaimWatchdogCoordinator` (209), `SseNotificationBridge`, notify machinery (`ForegroundNotificationPublisher`/`SessionStatusNotifier`), parsers (`UserCloseRequestParser`/`StartCommandRouter`/`OwnershipRequestParser`); Manifest `<service>` + FGS perms removed; CC launcher fallback removed; ~18 test files deleted | 4564 pass |
| 3 | `bfab44ec` | Delete `StreamingLifecycleCoordinator` (1692) + `CoordinatorModels` (235) + `DefaultSseReconnectSupervisor` (633) + `SseReconnectContracts` (57) + `TeardownReason`; CC coordinator param/diagLayer/fallbacks removed; `UnexpectedTransportDropHandler` relocated to `ServiceSseConnectionOwner`; 16 tests refactored | 4495 pass |
| 4 | (this commit) | Notification channel cleanup: drop `CHANNEL_SESSION_STATUS` + `CHANNEL_SESSION_STATUS_MIN` (zero consumers post-cluster deletion); keep `DECISIONS`/`IDLE`/`ERRORS` (AppLifecycleMonitor path). Doc touchups: README/build-apk audited — zero FGS refs (no edits needed). Historical spec docs (v3-v5.x, ocmar, l4-design) preserved per §8 | (gate below) |

## Cumulative impact

- **~46 production files deleted/modified**, **~28 test files deleted/modified**.
- **Net line delta vs main**: `-15,616` (see `git diff main --shortstat`).
- **L7-kept files** (dead-but-compiling, deleted in L7 worktree): `ConnectionBootstrapEngine`, `ConnectionBootstrapRunner`, `ConnectionBootstrapCoordinator`, `BootstrapRunner` interface + `bindBootstrapRunner`.
- **L4/L5-kept** (statically-open fences): `SseLifecyclePolicy`.

## Deviations from spec §2.1 (frozen adjudications, recorded in `.opencode/runs/l1-fgs-boundary-freeze.md`)

| Spec says | Frozen call | Why |
|---|---|---|
| §2.1 #4: Service "retain 660" | Full deletion | §6.1/§7.1 (post-rev-gpt) say Service is deleted; gutted `Service` subclass is uninstantiable |
| §2.1 #1: coordinator "retain 677" | Full deletion | Sole command consumer (`SessionStreamingController`) is itself §2.1 #5 整文件删 |
| §2.1 #7: reconnect/bootstrap cluster one unit | Supervisor in L1; Engine/Runner in L7 | Supervisor launch-driven dead code post-L1; Engine/Runner carry TOFU capture path |

## Boundary honored

- `ConnectionBootstrapEngine`/`ConnectionBootstrapRunner`/`ConnectionBootstrapCoordinator` (L7): untouched.
- `AuthorityReducer` (L3): untouched.
- `StreamingOwnershipGate` internals: untouched (only callers rewired; Starting/Ready/attemptId machinery now provably unreachable, rewrite deferred to its own stage).

## Unblocks

L2 (simplify `ServiceSseConnectionOwner` 1471→400), L4 (simplify `TokenStreamCoordinator`), L5 (replace `ProcessStatusPoller`), L7 (TOFU→trust-all), OwnershipGate rewrite (664→200).
