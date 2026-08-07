# ocdroid Architecture-Debt Batch 2 — Design SSOT (Items 13, 15)

- **Date**: 2026-08-07
- **Head OID**: `45dfe0db` (= origin/main = tag v0.21.5, includes Batch 1 items 14/16/17)
- **Scope**: architecture-debt items **13** (DI Wave2.2 — Hilt provides the 5 Orchestrators) and **15** (God-class split — TokenStreamCoordinator + ChatScaffold)
- **Batch**: 2 of 2. Builds directly on `archdebt-batch1-design.md`; its §2 mode-flag stack is carried forward and spot-verified at this head.
- **Verification basis**: every file:line below was re-verified against `45dfe0db` by reading the actual code. Handoff (`handoff-design-code-review.md`, head `aeb6e67`) line numbers were **not** trusted.

---

## 1. Executive summary

| Item | Decision (one line) |
|---|---|
| **13** | Add the 5 Orchestrators as AppCore **constructor params** using their **existing** `@Singleton @Inject constructor`s (zero new DI code); delete the `by lazy` block (AppCore.kt:254–292); unblock the two positional test factories by having them build the 5 Orchestrators inline in dependency order — the exact pattern they already use for the 6 controllers. The 5-way effect-routing cascade is **kept as-is** (already per-domain split at R-19 P2-2; further split fails YAGNI). |
| **15 (TSC)** | **Strangler-fig composition split**: keep `TokenStreamCoordinator` as the public facade with a **byte-identical constructor + API**, extract 4 `internal` collaborators in the same package — `TokenFrameGuard` (epoch/generation/ownership), `ReconnectPolicy` (backoff ladder), `TokenStateDispatcher` (reducer→ChatState bridging + effect translation), `StreamLifecycleSupervisor` (max-1 lifecycle + watchdog + reconnect scheduling). All four share the ONE `bundleCommitLock` monitor; JVM `synchronized` reentrancy preserves today's single-lock atomicity. **No `@Inject constructor`** — `ControllerModule.provideTokenStreamCoordinator` stays the binding; its body is wiring logic a constructor cannot host. |
| **15 (ChatScaffold)** | Extract three `@Composable` remember-factories following the **existing** `rememberChatTopBarState` precedent (ChatTopBar.kt:195): `rememberChatDerivedState` (the ~20 cross-slice derived values, per-field `State` granularity), `rememberChatChromeState` (overlay flags + drawer + snackbar + image picker), and `ChatNavigationEffects` (all LaunchedEffect/LifecycleEventEffect/BackHandler blocks + the sub-agent navigate callback). **`ChatOverlayHost` already exists** (extracted in §L5a) — the handoff target list is partially stale. |

**Boundary headline (details §2/§6)**: the task directive's claims — *"TokenStreamCoordinator 是 slim/standard SSE stream 处理的核心 (SSE_LEGACY vs SSE_SLIM)"* and *"ChatScaffold UI 层的 slim 分支 (isSlimActive) 须保持"* — are **stale/wrong at this head**. TokenStreamCoordinator has **zero** internal slim/legacy branch points and ChatScaffold has **zero** runtime `isSlimActive` reads. Neither item 13 nor item 15 touches the slim/standard boundary; §6 proves it.

---

## 2. slim/standard boundary investigation (mandatory pre-work)

### 2.1 The mode-flag stack (carried from batch1 §2.1, spot-verified at `45dfe0db`)

```
HostConfig._slim (per-profile user toggle 省流模式)
        │  (configure succeeds → ServerCompatProfile.updateSlimapi)
        ▼
ServerCompatProfile.slimConnection (@Volatile; = "最近一次成功 configure 后的 live mode")
        │
        ├─► ServerCompatProfile.tokenStreamEnabled — DERIVED: get() = slimConnection
        │       (ServerCompatProfile.kt:121, per batch1 §2.1; the token-stream gate)
        ├─► ConnectionGateway supports* / usesSlimStatusFanOut (≡ slimConnection)
        ├─► OpenCodeRepository forwarders (usesSlimStatusFanOut etc.)
        └─► ConnectionState.isSlimActive (ConnectionState.kt:152) — UI mirror, written by
                ConnectionViewModel.kt:214/228 and ConnectionHealthProbe.kt:444/609;
                consumed by SessionsScreen.kt:365 (`slimActive =` param) and
                ServerStatusIconButton.kt:102/122 (blue-vs-green status dot tint)
```

A secondary flag `slimPerSessionStatusEndpointAvailable` (default **false**, StreamingModule.kt:129) independently gates the per-session status fan-out (batch1 item 17 — inert seam preserved).

### 2.2 Where the boundary actually runs, per layer (verified this batch)

| Layer | Boundary mechanism | Evidence |
|---|---|---|
| data/gateway | per-method `if (slimConnection)` inside gateways | batch1 §2.2 row 1 (unchanged) |
| service SSE **status/digest** stream | `SseEventRouter` (SseEventRouter.kt:28-33) holds THREE handlers `[shared, legacy, slim]` and routes by `supports(type)`; status writes funnel through `SseDispatchHost.applyStatusViaAuthority` whose `origin` is `EntryOrigin.SSE_SLIM` (digest relay, SessionSyncCoordinator.kt:267) vs `SSE_LEGACY` (LegacySseHandler.kt:155; StatusAggregatorImpl.kt:469); the enum lives at AuthorityState.kt:197-198 with the lex-monotonic guard in AuthorityReducer.kt:222 | read this batch |
| ui (status/interactions) | `repository.usesSlimStatusFanOut` gates + nullable `routeToken` fork in OrchestratorViewModel | batch1 §2.2 rows 3-4 (unchanged) |
| **token stream (item 15 surface)** | **NO in-class branch.** The slim/standard decision is made **upstream at the two `open` call sites**: `shouldOpenTokenStream(serverCompatProfile.tokenStreamEnabled, …)` in RefreshOrchestrator.kt:170-176 and ChatViewModel.kt:164-170 — and `tokenStreamEnabled ≡ slimConnection`. The coordinator's transport is injected (`streamProvider` :99 / `streamConnectionProvider` :138); `ControllerModule.provideTokenStreamCoordinator` (ControllerModule.kt:317-334) always builds a slimapi `TokenStreamClient`, which is only ever exercised when the upstream gate passed. The only mode-adjacent gate *inside* the class is the debug toggle `sseDisabled` (:136, entry gate :429-432) — orthogonal to slim/standard (REST-only debug mode). | verified by full read of TokenStreamCoordinator.kt (1435 lines): zero `slim` / `slimConnection` / `SSE_SLIM` / `SSE_LEGACY` / `isSlimActive` symbols in code (only comment mentions at :31, :427) |
| **ChatScaffold (item 15 surface)** | **NO runtime slim branch.** Zero `isSlimActive` / `slimConnection` / `slim` symbol reads in ChatScaffold.kt (4 occurrences of "slim" are all comments: :1148, :1155, :1366, :152 §-refs). The chat UI is mode-agnostic; slim/standard divergence reaches it only as *data* (question/permission `routeToken` fields rendered opaquely, StatusSlot inputs). | verified by full read of ChatScaffold.kt (1398 lines) |

### 2.3 Item ↔ boundary interaction summary

- **Item 13**: pure DI ownership change inside the `ui` orchestration layer. The orchestrators it moves (`SessionOpener`/`RefreshOrchestrator`/`SendOrchestrator`/`DraftSessionOrchestrator`/`CommandOrchestrator`) contain **no slim branches** (verified: `RefreshOrchestrator`'s only mode touch is reading `serverCompatProfile.tokenStreamEnabled` at :170 as a *constructor-injected* dependency — unchanged by who constructs the class). Zero boundary interaction.
- **Item 15 (TSC)**: the class sits *below* the boundary (mode-agnostic engine); the slim gate (`shouldOpenTokenStream`) lives at its callers and is **not touched**. The split must preserve the constructor injection of `streamProvider`/`streamConnectionProvider` verbatim so the upstream gate keeps sole authority over whether a stream exists.
- **Item 15 (ChatScaffold)**: pure composable code-motion in a mode-agnostic file. The only slim-adjacent behavior is rendering `routeToken`-carrying questions/permissions opaquely (:1151-1164) — moved verbatim into `StatusSlot` wiring that stays in the scaffold body.

**Conclusion**: the task framing's boundary claims are stale due to head drift (the SSE_LEGACY/SSE_SLIM routing lives in `SseEventRouter`/`SseDispatchHost`/`SessionSyncCoordinator` — a *different* subsystem — and `isSlimActive` is consumed only by SessionsScreen/ServerStatusIconButton). Neither item crosses the boundary. §6 gives the preservation proof.

---

## 3. Item 13 — DI Wave2.2 migration

### 3.1 Decision: AppCore constructor injection via the existing `@Inject constructor`s

The 5 orchestrators **already** carry `@Singleton internal class X @Inject constructor` (SessionOpener.kt:21, RefreshOrchestrator.kt:39, SendOrchestrator.kt:32, DraftSessionOrchestrator.kt:32, CommandOrchestrator.kt:26), and **every** constructor dependency is already Hilt-bound today:

| Dep | Binding |
|---|---|
| `SharedStateStore`, `SharedEffectBus` | `@Singleton @Inject constructor` (SharedStateStore.kt:54-55, SharedEffectBus.kt:53) |
| `OpenCodeRepository` (Refresh/Send take the concrete) | existing `@Singleton` |
| `SessionRepository`, `InteractionRepository` | `RepositoryInterfaceModule` `@Binds` (RepositoryInterfaceModule.kt:24-35) |
| `SettingsManager`, `HostProfileStore`, `ServerCompatProfile` | existing singletons |
| `@UiApplicationScope CoroutineScope` | UiApplicationScopeModule |
| `@Named("currentProfileId")` | ControllerModule.kt:88-93 |
| 6 controllers + `TokenStreamCoordinator` | ControllerModule `@Provides` (:95-473) |

∴ **No new module, no `@Provides`, no interface changes.** Add 5 params to `AppCore`'s `@Inject constructor`; Hilt resolves the acyclic graph Refresh → Send → Draft → Command (Draft depends on Send+Refresh, DraftSessionOrchestrator.kt:41-42; Command depends on Draft+SessionOpener, CommandOrchestrator.kt:35-36) automatically.

**On `internal` + `@Inject constructor`**: ControllerModule.kt:46-53 claims Dagger "requires a genuinely public Kotlin class" — that is **folklore, not fact**: Kotlin `internal` classes compile to public bytecode with unmangled class names, and Dagger's generated Java factories live in the same Gradle module, so constructor injection of an `internal` class works. The 5 controllers used `@Provides` for consistency/safety in R-19; the orchestrators need no such accommodation. **Contingency** (expected unused): if KSP rejects any orchestrator, add a 5-function `OrchestratorModule` `@Provides` mirroring the constructors — do NOT relax `internal` visibility.

**Param placement & visibility** (append after `slimFanOutRetryScheduler`, AppCore.kt:222, keeping the batch-1 param untouched):

```kotlin
private val sessionOpener: SessionOpener,              // AppCore-internal use only (:399, :395 via command? no—:399 only + CommandOrchestrator gets its own)
internal val refreshOrchestrator: RefreshOrchestrator, // internal: AppCoreOrchestration.kt:253/:260 extensions
private val sendOrchestrator: SendOrchestrator,        // AppCore-internal (:381)
internal val draftSessionOrchestrator: DraftSessionOrchestrator, // internal: AppCoreOrchestration.kt:266
private val commandOrchestrator: CommandOrchestrator,  // AppCore-internal (:395)
```

Visibility verified by grep: `refreshOrchestrator`/`draftSessionOrchestrator` are reached from `AppCoreOrchestration.kt` extensions (:252-266) → must stay `internal`; the other three are only read inside AppCore.kt → `private`.

**Eager vs lazy**: construction becomes eager (Hilt builds them with AppCore). Verified safe: **none of the 5 orchestrators has an `init {}` block or constructor side effects** (grep `init {` over the 5 files: zero hits) — they are stateless shells over injected singletons. Behavior identical.

### 3.2 Test-factory blocker resolution — extend the two factories, no Hilt-in-test

The TODO(Wave2.2) blocker (AppCore.kt:254-258) names exactly two factories. Both already hand-build the full controller graph inline "with the SAME wiring the production ControllerModule uses" (MainViewModelTestBase.kt:167-174 kdoc). **Chosen resolution (a): construct the 5 orchestrators inline in dependency order, immediately before the `AppCore(...)` call, from locals the factory already has** (`store`, `repository` (mock, satisfies `SessionRepository`/`InteractionRepository` — mockk of the concrete class covers both interface params since `OpenCodeRepository implements` them), `settingsManager`, `effectBus`, `appScope`, `fpProvider`, `sessionSwitcher`, `connectionCoordinator`, `sessionSyncCoordinator`, `foregroundCatchUpController`, `hostProfileStore`, `ServerCompatProfile()`, `tokenStreamCoordinator`, `composerController`):

- `MainViewModelTestBase.createCore` (MainViewModelTestBase.kt:162-328; `AppCore(` call :273-316): build `sessionOpener → refreshOrchestrator → sendOrchestrator → draftSessionOrchestrator → commandOrchestrator` at ~:272, append 5 args to the `AppCore(...)` call.
- `ForkSessionTest.createCore` (ForkSessionTest.kt:107-227; `AppCore(` call :189-226): same 5 constructions + 5 args.

**Rejected alternatives**: (b) Hilt test rule / Robolectric+Hilt — the unit suite is deliberately mockk-based with no Hilt container; introducing one for two factories is a net regression in test speed and complexity. (c) A test-only builder/`@TestInstallIn` — indirection without payoff: the factories' value is precisely that they mirror production wiring explicitly; a builder would hide drift (the R-19 P2-5 comment at MainViewModelTestBase.kt:167-174 documents this as intentional). (d) Kotlin default args on the new AppCore params (`= SessionOpener(store, ...)`) — constructor self-reference defaults are impossible; dead end.

Blast radius: every domain VM test extends `MainViewModelTestBase` (ChatViewModelTest, SessionViewModelTest, ConnectionViewModelTest, HostViewModelTest, ComposerViewModelTest, OrchestratorViewModel*Test, AppCoreDispatcherTest, AppCoreOrchestrationTest, ChatScaffoldSaveableTest, …) — but they all consume `createCore()` opaquely; only the two factory bodies change.

### 3.3 `by lazy` deletion list (exact)

| File:line | Delete |
|---|---|
| AppCore.kt:224-259 | the §Wave2.1-split-l2 kdoc block including `TODO(Wave2.2)` — replace with a short comment noting Wave2.2 completion |
| AppCore.kt:260 | `private val sessionOpener by lazy { … }` |
| AppCore.kt:261-268 | `internal val refreshOrchestrator by lazy { … }` |
| AppCore.kt:269-274 | `private val sendOrchestrator by lazy { … }` |
| AppCore.kt:275-281 | `internal val draftSessionOrchestrator by lazy { … }` |
| AppCore.kt:282-292 | `private val commandOrchestrator by lazy { … }` (the repository-passed-twice comment at :283-286 moves to CommandOrchestrator's ctor kdoc or is dropped — CommandOrchestrator.kt:29-30 already declares the two narrow seams by type, self-documenting) |
| AppCore.kt:605-606 | stale comment "orchestrators are created via `lazy`" in `dispatchEffect` |

### 3.4 Effect-routing cascade: **keep, do not split further**

The handoff's "按跨域用例拆分 effect routing" (item 13 row) is **declined**, and "split effect routing" is interpreted as *completed by this migration* (orchestrator routing already lives outside AppCore — AppCore is the thin router, AppCore.kt:358-360):

- The former 23-branch monolith was **already split** into 5 per-domain dispatchers returning `Boolean`, cascaded via short-circuit `||` with an unhandled-effect guard (R-19 P2-2: `dispatchEffect` :604-613; `dispatchForegroundCatchUpEffect` :646, `dispatchSessionEffect` :676, `dispatchHostEffect` :872, `dispatchConnectionEffect` :994, `dispatchSessionSyncEffect` :1097; `assertExactlyOneHandled` :632).
- The dispatchers are pure routing (each branch = one call to the controller/orchestrator the matching VM method uses). Extracting them into 5 dispatcher classes would create 5 one-method objects sharing AppCore's entire field set — an abstraction that adds files and indirection but no testability (they are already `internal` and directly exercised by `AppCoreDispatcherTest`, 1006 lines) and no decoupling (they would need the same 15+ deps).
- YAGNI verdict: the cognitive-ceiling problem the P2-2 split solved does not reappear at this granularity. Revisit only if a dispatcher family crosses ~15 branches.

### 3.5 SlimFanOutRetryScheduler compatibility

The batch-1 param (AppCore.kt:198-222, `@Provides` in `SlimFanOutRetrySchedulerModule`, service/streaming/StreamingModule.kt:~80-110) is **untouched** — same position-adjacent placement, same relaxed-mock wiring in both factories (MainViewModelTestBase.kt:313-315, ForkSessionTest.kt:225). The `dispatchSessionSyncEffect` branches routing `RequestPollerBackoff`/`ResetPollerBackoff` (:1129-1145) are unchanged. New orchestrator params append **after** it.

### 3.6 Ordered migration steps (each `check.sh`-green)

1. **AppCore**: add the 5 ctor params (placement/visibility per §3.1); delete the lazy block + rewrite the kdoc (:224-259) + fix the :605-606 comment. Build **breaks** at the two test factories — expected.
2. **Factories**: extend `MainViewModelTestBase.createCore` and `ForkSessionTest.createCore` per §3.2 (build orchestrators bottom-up, append args).
3. `./scripts/check.sh` — compile + full unit suite. LSP diagnostics must be clean after step 1/2 edits.
4. **Targeted**: `./gradlew testDebugUnitTest --tests "*AppCoreDispatcherTest*" --tests "*AppCoreOrchestrationTest*" --tests "*ForkSessionTest*"`.
5. **Hilt proof**: the production graph is validated by `assembleDebug` (Hilt's compile-time graph validation fails the build if any orchestrator dep is unbound); no runtime smoke needed (no behavior change).

Commit as ONE commit (steps 1-2 are mutually dependent; splitting leaves a red tree).

---

## 4. Item 15 — TokenStreamCoordinator split

### 4.1 Design principles (fixed before component mapping)

1. **Facade compatibility is the risk-killer.** 7 construction sites exist outside the class: production `ControllerModule.provideTokenStreamCoordinator` (:345-372) and tests `TokenStreamCoordinatorTest.kt:91`, `TokenStreamCoordinatorIdempotencyTest`, `TokenStreamCoordinatorBundleIdentityTest.kt:35`, `SlimV2WireRegressionTest.kt:382/459/514`, `B2RouteWiringSequenceTest.kt:154`, `MainViewModelTestBase.kt:290-297`, `ForkSessionTest.kt:206-211`, plus `mockk<TokenStreamCoordinator>()` in ConnectionCoordinatorConcurrentTest.kt:477/553/668. Keeping the class name, constructor signature (19 params, defaults intact), and full API surface means **zero caller/test migration**; the split is then pure internal reorganization.
2. **One monitor, reentrant.** Today virtually every mutation happens inside `synchronized(bundleCommitLock)` (:312, :331, :453, :555, :597, :630, :635, :647, :666, :725, :1216, :1384) — the same `Any` shared with `OpenCodeRepository.configure`'s `@Synchronized` monitor (:139-140, wired as `repository` at ControllerModule.kt:352). The four components each receive the **same lock instance**; JVM `synchronized` is reentrant, so a facade/supervisor/dispatcher outer block calling a component's own `synchronized(lock)` method preserves today's single-acquisition atomicity exactly. Atomics (`AtomicReference`/`AtomicLong`/`ConcurrentHashMap`) are **retained** for the reads that happen *outside* the lock (e.g. `reconnectRequested.get()` at :1158 inside the collector, `currentLifecycle` CAS at :1323-1334).
3. **Captured-token threading stays verbatim.** The §B4 contract — `capturedRouteInstance` threaded as function params through runStream → dispatchEpochFrame → bridge/handleEffect (:505-520 kdoc), never a shared field — is preserved by keeping method signatures identical within the components.
4. **Companion constants stay on the facade** (:1416-1434: `TOKEN_HEARTBEAT_MS`, `TOKEN_WATCHDOG_MS`, `TOKEN_WATCHDOG_POLL_MS`, `OPEN_DEBOUNCE_MS`, `INITIAL_BACKOFF_MS`, `MAX_BACKOFF_MS`, `BACKOFF_MULTIPLIER`) — tests reference them; components receive the resolved values via the facade's existing ctor params.

### 4.2 Component boundaries (current line-range → target)

| Component (new file, `ui/controller/sse/`) | Responsibility | Current code moved | State owned |
|---|---|---|---|
| **`TokenFrameGuard.kt`** | Epoch + generation + ownership bookkeeping: the "is this frame/clear stale?" authority | `epochBySid`/`genBySid`/`ownerByPartId` decls (:291-305); `beginSession` (:629-632), `beginStreamIncarnation` (:634-639), `onPartOwned` (:646-652), `filterClearByGeneration` (:665-681), `ownedPartsForSid` (:615-620), `epochOf`/`genOf`/`bumpEpochForTest` (:586-599); the epoch-check slice of `dispatchEpochFrame` (:727-734) exposed as `isEpochCurrent(sid, epoch)`; `removeSid(sid)` (the `ownerByPartId.entries.removeIf`/`genBySid` cleanup from `close` :572) | 3 CHMs. All methods `synchronized(lock)` |
| **`ReconnectPolicy.kt`** | Backoff ladder math + attempt counters (pure, smallest, extracted first) | `attemptBySid` (:309), `nextBackoffMs` (:1311-1314), attempt resets (:739, :1165) exposed as `resetAttempts(sid)`; `nextDelayMs(sid)` (getAndIncrement + compute, from :1279-1280); `clearSid(sid)` (:578) | 1 CHM. No lock needed internally (CHM + single-threaded premise), but called under lock anyway |
| **`TokenStateDispatcher.kt`** | Reducer → ChatState bridging + effect translation + revision-hook invocation | `reducerStateBySid` (:307); `dispatchBound` (:311-323); `dispatchTokenStreamClear` (:326-344); `dispatchEpochFrame` post-guard body (:735-917: watchdog-reset callout → supervisor callback, resync sid-rewrite, dedup hook :790-828, reduce :829-830, removal hooks :847-882, `bridgePartToChatState` :924-970, `handleEffect` :982-1041 incl. deferred-effects pattern :724/:916) | 1 CHM. Deps: slices, guard, 5 revision hooks, `triggerSinceFetch`, lock, `currentBundleProvider`, `requestReconnect: (String) -> Unit` (→ supervisor sentinel), `onAnyFrame: () -> Unit` (→ supervisor `lastFrameAt` reset + `policy.resetAttempts`) |
| **`StreamLifecycleSupervisor.kt`** | Max-1 lifecycle ownership: open/close/debounce, run loop, watchdog, reconnect scheduling, the §MF-1 sentinel, lifecycle-bundle binding | `currentSid`/`currentDirectory` (:254-256), `StreamLifecycle` + `currentLifecycle` (:262-272), `lifecycleRouteInstance` (:273-289), `lastFrameAt` (:352), `reconnectRequested` (:354-394 incl. the unconditional-set/clear §gate-r2 contract), `open` (:419-544), `close`'s job/sid portion (:554-568), `currentStreamJobSnapshot` (:583), sentinel test seams (:607-612), `runStream` (:1066-1194 incl. watchdog :1102-1146 + collector sentinel check :1158-1160), `onWatchdogTimeout` (:1206-1245), `onStreamFailure` (:1257-1260), `scheduleReconnect` (:1278-1309), `bindCurrentLifecycleBundle`/`isCurrentLifecycleBundle` (:1323-1347), `launchStreamLifecycle` (:1383-1407 incl. LAZY-start §MF-1 fix), `cancelCurrentStream` (:1409-1414) | 4 atomics + lifecycle ref. Deps: scope, guard, policy, dispatcher-callback, `streamProvider`/`streamConnectionProvider`, `sseDisabled`, `clearSessionRevisions` hook (sid-switch reclaim :484-487, close reclaim :577), tunables, clock, lock, `currentBundleProvider` |
| **`TokenStreamCoordinator.kt` (facade, ~250 lines)** | Composition + API delegation + kdoc | ctor (:96-244 verbatim) builds guard → policy → dispatcher → supervisor; delegates: `open`/`close` → supervisor (facade `close` wraps supervisor.close + `guard.removeSid` + `dispatcher.removeSid` + `policy.clearSid` in ONE outer `synchronized(bundleCommitLock)` — reentrant, atomicity preserved); `dispatchEpochFrame` → dispatcher; `dispatchTokenStreamClear` → dispatcher; `beginSession`/`onPartOwned`/`filterClearByGeneration` → guard; 7 test hooks → guard/supervisor | none of its own |

**The supervisor ↔ dispatcher cycle** (dispatcher sets the reconnect sentinel; supervisor calls dispatcher per frame) is broken by construction order: build `dispatcher` first with `requestReconnect = { sid -> supervisor.markReconnectRequested(sid) }` closing over a `lateinit var supervisor`; supervisor receives `dispatchFrame = dispatcher::dispatchEpochFrame`. First use is the first `open()` call, strictly after construction completes — `lateinit` is safe on the main-confined scope; document the wiring in the facade kdoc. (Rejected: a 5th "sentinel holder" component — one atomic with two users does not earn a class.)

**Sentinel ownership stays in the supervisor**: the §MF-1 gate-r2 contract (unconditional set/clear; set by `handleEffect`, checked at :1158, cleared at open/close/runStream-start/catch) is a lifecycle-unwind protocol, not frame guarding. `TokenStateDispatcher` never reads it — only writes via `requestReconnect`.

### 4.3 Hilt / `@Inject constructor` decision: **keep `ControllerModule.@Provides`, no `@Inject`**

- `provideTokenStreamCoordinator` (:281-373) is not dependency assembly — it is **wiring logic**: installs `repository.onBundlePublished` under `synchronized(repository)` with a baseline publish (:302-316), builds `streamConnectionProvider` with generation/fingerprint drift checks (:317-334), and constructs the production revision hooks via `tokenStreamProductionHooks` (:339-343, factory at :602-678). A constructor cannot host this.
- The 4 components are **not** Hilt-provided either: they share per-instance mutable state (the 5 CHMs/atomics) and the single monitor; making them independent singletons would force a shared-state object into the graph (worse) and break the tests' fresh-instance-per-test pattern. Composition inside the facade keeps the unit-test surface (construct facade OR a component directly) intact.
- The provider body is **unchanged** (facade ctor identical) — ControllerModule.kt is not in this lane's write domain except optional kdoc touch-ups.

### 4.4 Public facade / callers / tests

- Callers unchanged: `ChatViewModel.kt:170` (busy-open via `core.tokenStreamCoordinator`), `RefreshOrchestrator.kt:176` (effect-load), `ConnectionCoordinator.kt:171` (nullable param, `close` hooks), `AppCore.kt:139/914/924` (EvictSession close + `dispatchTokenStreamClear`).
- The `shouldOpenTokenStream(tokenStreamEnabled, …)` slim gate at both open call sites is **not touched** (§2.2 — this is the boundary preservation).
- All TSC tests compile unmodified and must stay green (§9).

### 4.5 Ordered steps (each `check.sh`-green; lane-internal commits)

1. **Extract `ReconnectPolicy`** (purest, zero deps) + delegate from facade; run `*TokenStream*` tests.
2. **Extract `TokenFrameGuard`**; facade guard methods delegate; run `*TokenStream*` tests.
3. **Extract `TokenStateDispatcher`** (needs guard); facade `dispatchEpochFrame`/`dispatchTokenStreamClear` delegate; run `*TokenStream*` + `*SlimV2Wire*` + `*B2RouteWiring*` tests.
4. **Extract `StreamLifecycleSupervisor`** (needs all three + the `lateinit` wiring); facade `open`/`close` delegate; full suite.
5. **Facade cleanup**: kdoc rewrite (component map + the one-monitor/reentrancy invariant + the supervisor↔dispatcher wiring note); delete dead private code; `./scripts/check.sh`.
6. **New component tests** (§9) may land with steps 1-4 or as a final step.

Each step is a pure code-motion that keeps the whole suite green; if a step can't go green it is split smaller, not squashed forward.

---

## 5. Item 15 — ChatScaffold split

### 5.1 Head-drift correction

The handoff target list (`ChatChromeState`/`ChatNavigationEffects`/`ChatDerivedState`/`ChatOverlayHost`) predates the **§L5a split that already shipped**: `ChatOverlayHost.kt`, `ChatDrawerHost.kt`, and `rememberChatTopBarState` (ChatTopBar.kt:195) already exist — ChatScaffold.kt:898-907 and :1305-1385 consume them. This design therefore extracts the **remaining three**, and keeps the file's already-established pattern (State handles + remember-factories over god-composable inline code).

### 5.2 Extraction targets (current line-range → target)

**`ChatDerivedState.kt`** — `@Composable internal fun rememberChatDerivedState(...): ChatDerivedState`, a holder whose fields are **individual `State<T>` properties** (NOT one bundled `derivedStateOf` — preserves today's per-field recompute granularity, §5.3):

| Field(s) | Current lines |
|---|---|
| `routeOwnedContent`, `onParameterizedRoute`, `renderedMessages/PartsByMessage/StreamingTexts/StreamingReasoning`, `chromeSessionId` | :231-258 |
| `sessionsById`, `curSession`, `effectiveBusy`, `curCutoff`, `curRevertMessageId`, `curSessionStatus` | :454-483 |
| `computedContextUsage` / `cachedContextUsageState` (+ the verbatim write-through at :537-539 — kept because `rememberChatTopBarState` consumes the same handle, :798) | :531-539 |
| `visibleAgents`, `effectiveAgent`, `effectiveModel` | :556-600 |
| `currentSessionIsRunning`, `isCurrentSessionSending`, `currentActivity`, `matchingQuestions`, `pendingQuestion`, `pendingPermission` | :601-660 |
| `curHostProfile`, `recentSessionsForDrawer` | :776, :889-897 |

Inputs: the 11 slice `State` handles (already kept as handles at :203-228 precisely for this pattern), `routeSessionId`, `routeInstance`. **The 11 `collectAsStateWithLifecycle` subscriptions stay in ChatScaffold** — `chatBodyContent` (:927-1209) and the `ChatOverlayHost` wiring (:1305-1385) read raw slices directly; moving subscriptions in would force the derived holder to re-expose everything (widening, not narrowing).

**`ChatChromeState.kt`** — `class ChatChromeState` + `@Composable internal fun rememberChatChromeState(composerVM): ChatChromeState` (state-holder class; the rememberSaveable/remember calls live inside the factory so slot positionality is preserved):

| Member | Current lines |
|---|---|
| `showAgentPicker` / `showModelPicker` / `showSessionPicker` / `pendingWorkdirPick` (`rememberSaveable`) | :345-347, :365 |
| `errorDetail` / `showTodoDialog` / `showContextDialog` / `showForceAbortConfirm` (`remember`) | :348, :354-357 |
| `drawerState`, `openDrawerAction`, `closeDrawerAction` | :407-422 |
| `snackbarHostState` | :374 |
| `imagePicker` + `onAddImages` | :426-435 |

**`ChatNavigationEffects.kt`** — `@Composable internal fun ChatNavigationEffects(...)` (renders nothing; pure effect host) + `rememberOnOpenSubAgentNavigate`:

| Block | Current lines |
|---|---|
| checkpoint-consume `LaunchedEffect(chromeSessionId, routeSavedStateHandle)` | :281-288 |
| `onOpenSubAgentNavigate` callback factory | :314-330 |
| reconcile state machine: `reconcileState` + `LaunchedEffect(chromeSessionId)` + `LifecycleEventEffect(ON_PAUSE/ON_RESUME)` | :502-530 |
| parent-session `BackHandler(enabled = parent != null)` | :668-679 |
| drawer `BackHandler(enabled = drawerState.isOpen)` — **must stay composed AFTER the parent handler** (LIFO contract documented :703-714); both live inside this one composable in this order, so the contract is preserved by construction | :712-714 |
| UiEvent snackbar collection, stale-notice snackbar, compacting auto-clear | :718-766 |

**Stays in ChatScaffold** (~750 lines after extraction): the 11 subscriptions, `isWide`/sidebar derivations (:385-398), `topBarState`/`topBarActions` (:788-858 — already extracted logic, just wiring), `chatBodyContent` lambda (:927-1209), the sidebar/drawer branch (:1216-1285), the force-abort dialog (:1288-1302), the `ChatOverlayHost` wiring (:1305-1385), and the `reconcileState` **value** if any body code reads it (it doesn't — effects only; fully moves).

### 5.3 Form + recomposition-safety argument

- **Form**: remember-factory composables + a state-holder class for chrome (the standard Compose pattern already used at `rememberChatTopBarState`). NOT separate render composables (the extracted code renders nothing except effects) and NOT Hilt classes (this is composition-local UI state — DI would be category-error).
- **No new subscriptions, no new keys**: every `remember(...)`/`derivedStateOf` keeps its exact current key list verbatim (e.g. `effectiveAgent` keeps `remember(chat.pendingAgent, curSession, renderedMessages, visibleAgents, onParameterizedRoute)` :559). Recompute scope per field is unchanged.
- **Snapshot-tracking contract preserved**: the derived factory takes `State` handles (not values) so `.value` reads happen inside the calculation lambda — the same CORRECTNESS note that `rememberChatTopBarState` documents (ChatScaffold.kt:781-787). Individual `State` fields mean a change to `pendingPermissions` recomposes readers of `pendingPermission` only — identical to today's per-local invalidation.
- **Saveable slot-positionality**: the 4 `rememberSaveable` flags move as a block into `rememberChatChromeState`, keeping their relative order; `ChatScaffoldSaveableTest` (which drives `showSessionPicker` through `StateRestorationTester.emulateSaveAndRestore`, ChatScaffoldSaveableTest.kt:44-80) is the regression net.
- **No `rememberSaveableStateHolder` change**: `chatBodySaveableHolder` (:1216) and its two `SaveableStateProvider("chatBody")` sites stay in the scaffold body.

### 5.4 Ordered steps (each `check.sh`-green)

1. **Extract `rememberChatDerivedState`** (largest, pure read-derivation); scaffold locals become `derivedState.xxx.value` reads (or `by` delegates). Run `ChatScaffoldSaveableTest` + compile.
2. **Extract `rememberChatChromeState`**; rewire overlay flags + drawer actions + image picker. Run `ChatScaffoldSaveableTest` (pins flag positionality).
3. **Extract `ChatNavigationEffects`** + `rememberOnOpenSubAgentNavigate`; verify BackHandler order inside the new composable (parent → drawer). Full unit suite.
4. Facade kdoc rewrite (component map + recomposition contract) + `./scripts/check.sh`.

---

## 6. slim/standard boundary impact analysis (the critical proof)

**The boundary runs where §2.2 places it — nowhere in this batch's write set.** Item-by-item:

**Item 13 (AppCore DI)**:
- *Before*: Hilt constructs AppCore → AppCore lazily hand-builds 5 orchestrators from its injected singletons. *After*: Hilt constructs the 5 orchestrators (from the **same** singletons) → injects them into AppCore. The object graph at runtime is **identical** — same instances, same acyclic order (orchestrators have no init side effects, §3.1).
- No slim-flag read moves, appears, or disappears. `RefreshOrchestrator`'s `serverCompatProfile.tokenStreamEnabled` read (:170) is the same constructor-injected singleton either way. ∴ slim-mode behavior byte-identical; standard-mode behavior byte-identical.

**Item 15 (TSC split)**:
- The slim/standard decision for the token stream is made **exclusively upstream** by `shouldOpenTokenStream(serverCompatProfile.tokenStreamEnabled, …)` at RefreshOrchestrator.kt:170-176 and ChatViewModel.kt:164-170 (`tokenStreamEnabled ≡ slimConnection`, §2.1). Both call sites are **outside the split's write domain** and unchanged.
- Inside the class there is no mode branch to preserve (verified: zero `slim`/`SSE_SLIM`/`SSE_LEGACY` symbols in code). What must survive instead are the *transport-injection seams*: `streamProvider`/`streamConnectionProvider` ctor params move verbatim into `StreamLifecycleSupervisor`; the production provider lambda (ControllerModule.kt:317-334, always-slimapi `TokenStreamClient`) is untouched. The debug `sseDisabled` entry gate moves verbatim into `supervisor.open` (entry position preserved: before any state mutation, per :422-432 contract).
- ∴ in slim mode the token stream opens/closes/reconnects exactly as today (same open path, same lifecycle); in standard mode `open` is never called (upstream gate) — and if it were, behavior is unchanged because the engine is mode-agnostic.

**Item 15 (ChatScaffold split)**:
- ChatScaffold contains no runtime slim branch (§2.2); the split is code-motion of mode-agnostic derivation/effect blocks. The only slim-adjacent dataflow — `routeToken` plumbed opaquely from `matchingQuestions`/`pendingPermission` into `orchestratorVM.respondPermission/replyQuestion/rejectQuestion` (:1146-1165) — stays in the retained scaffold body (StatusSlot wiring), untouched.

**Stale-framing statement (integrity note)**: the task directive's premise that TokenStreamCoordinator hosts the SSE_LEGACY/SSE_SLIM fork and that ChatScaffold has `isSlimActive` runtime branches is **false at `45dfe0db`** (likely a conflation of the token stream with the status/digest SSE system, whose fork does live in `SseEventRouter`/`LegacySseHandler`/`SlimSseHandler` + `EntryOrigin`). This design does not "preserve" those branches — it documents that they were never here, and preserves the actual boundary mechanism (the upstream `tokenStreamEnabled` open gate + the injected transport seam). Reviewers: verify with `grep -n "slim\|SSE_" app/src/main/java/cn/vectory/ocdroid/ui/controller/sse/TokenStreamCoordinator.kt` (comments only) and `grep -n "isSlimActive" app/src/main/java/cn/vectory/ocdroid/ui/chat/ChatScaffold.kt` (zero hits).

---

## 7. Abstraction-layering justification (user constraint 1)

| Split | Single responsibility | No concrete-impl leak | Testability |
|---|---|---|---|
| 13 — orchestrators as ctor params | AppCore: composition + effect routing + ~6 cross-domain entries; orchestrators: one use-case cluster each (already true — Wave2.1 did the *code* split; Wave2.2 fixes the *ownership*) | No new interface introduced and none leaked: orchestrators keep depending on narrow seams (`SessionRepository`/`InteractionRepository` where sufficient); the `repository`-twice oddity at AppCore.kt:283-288 disappears from AppCore entirely (it becomes CommandOrchestrator's own ctor detail) | AppCore tests construct orchestrators with the same mocks — no mock surface growth; orchestrator classes remain directly constructible for focused tests |
| 15 — TSC components | Guard = staleness judgments; Policy = backoff math; Dispatcher = reducer→UI bridging; Supervisor = job/watchdog/reconnect lifecycle; Facade = composition + API | Components are `internal` to `ui.controller.sse`; callers keep depending on the facade; Hilt still hands out the facade only — no component type escapes the package | Each component becomes independently constructible (guard/policy need no coroutines; dispatcher needs no stream; supervisor accepts a fake dispatcher callback) — the existing whole-engine tests keep pinning end-to-end behavior |
| 15 — ChatScaffold extraction | DerivedState = cross-slice read derivations; ChromeState = ephemeral overlay/chrome UI state; NavigationEffects = side-effect blocks; Scaffold = layout + wiring | No new public surface: all three are `internal` in `ui.chat`; VMs are still injected only at the scaffold root | `rememberChatDerivedState` becomes unit-testable in isolation (Robolectric compose test with State fakes) if desired — but the primary gate is the existing `ChatScaffoldSaveableTest` staying green |

Complexity earnings check: TSC gains 4 files but sheds a 1435-line interlock into named units **without** changing any interlock semantics (same lock, same atomics, same threading of captured tokens) — the abstraction earns its keep because the current file's correctness docs (§MF-1, §B4, bgpt MF-2/3) are already organized along exactly these responsibility lines. ChatScaffold sheds ~450 lines into the file's own pre-established §L5a pattern. Neither introduces a speculative layer.

---

## 8. Implementation ordering & write-domain analysis

**Three lanes, zero overlapping files — full parallelism is safe:**

| Lane | Production files | Test files |
|---|---|---|
| **13 (AppCore DI)** | AppCore.kt | MainViewModelTestBase.kt, ForkSessionTest.kt |
| **15a (TSC split)** | TokenStreamCoordinator.kt + 4 new files in `ui/controller/sse/` (TokenFrameGuard.kt, ReconnectPolicy.kt, TokenStateDispatcher.kt, StreamLifecycleSupervisor.kt; possibly OwnerTag → TokenStreamTypes.kt) | new component tests; **no edits** to existing TSC tests |
| **15b (ChatScaffold split)** | ChatScaffold.kt + 3 new files in `ui/chat/` (ChatDerivedState.kt, ChatChromeState.kt, ChatNavigationEffects.kt) | **no edits** to ChatScaffoldSaveableTest (it must stay green unmodified) |

Deliberate non-overlaps:
- **AppCore.kt** is lane-13-only. Lane 15a keeps the `TokenStreamCoordinator` type + ctor identical, so AppCore.kt:139/914/924 and `MainViewModelTestBase.kt:290-297` / `ForkSessionTest.kt:206-211` (which lane 13 also touches for the AppCore call) need no TSC changes — the lanes touch *different regions* of the two shared test files... correction: lane 13 edits MainViewModelTestBase.kt/ForkSessionTest.kt (AppCore arg list) and lane 15a does **not** edit them at all (facade ctor unchanged). Clean.
- **ControllerModule.kt** untouched by all lanes (provider body identical; orchestrators need no providers).
- **RefreshOrchestrator.kt/ChatViewModel.kt** (token-stream open call sites) untouched by all lanes.

Suggested landing order if serialized: **13 → 15a → 15b** (13 is smallest and unblocks the TODO; 15a is highest-risk and benefits from landing alone). If parallel: three lanes, any merge order; no rebase hazard beyond the two shared test files where only lane 13 writes.

---

## 9. Test plan

### 9.1 Regression safety net (must stay green, unmodified unless noted)

| Test | Pins | Lane |
|---|---|---|
| `AppCoreDispatcherTest` (1006 lines) | the 5-domain effect cascade incl. `RequestPollerBackoff`/`ResetPollerBackoff` → SlimFanOutRetryScheduler (:1129-1145) | 13 |
| `AppCoreOrchestrationTest` | cross-domain entries (`sendMessage` draft-vs-existing, `executeCommand`, extensions at AppCoreOrchestration.kt:252-266) | 13 |
| All 6 domain VM test files + `*PassThroughTest` via `MainViewModelTestBase.createCore` | VM↔core wiring | 13 (factory edit only) |
| `ForkSessionTest` | fork flow + factory | 13 (factory edit only) |
| `TokenStreamCoordinatorTest` (837 lines) | engine: open/debounce/epoch drop/watchdog/reconnect/backoff/idempotency | 15a |
| `TokenStreamCoordinatorIdempotencyTest` (604 lines) | open idempotent guard + §B4 route-token re-entry | 15a |
| `TokenStreamCoordinatorBundleIdentityTest` (59 lines) | bundle-bound lifecycle guards | 15a |
| `SlimV2WireRegressionTest` (:382/459/514) | dedup/revision-ledger wiring through the engine | 15a |
| `B2RouteWiringSequenceTest` (:154) | end-to-end resync→cleanup bridge | 15a |
| `ConnectionCoordinatorConcurrentTest` (:477/553/668) | mockk facade — close() teardown paths | 15a |
| `ChatScaffoldSaveableTest` (257 lines) | 4 `rememberSaveable` flags' slot positionality + 7-VM smoke | 15b |

### 9.2 New tests

- **Lane 15a** (new file(s) under `app/src/test/.../ui/controller/sse/`):
  - `TokenFrameGuardTest`: `filterClearByGeneration` allow/drop/no-tag arms (:665-681 semantics); `onPartOwned` stale-gen drop; `beginStreamIncarnation` monotonicity; `removeSid` cleanup.
  - `ReconnectPolicyTest`: ladder 1s→2s→…→30s cap (`nextBackoffMs` :1311-1314), `resetAttempts` rebase, per-sid isolation.
  - `StreamLifecycleSupervisorTest` (drive with fake dispatcher callback): max-1 supersede (`launchStreamLifecycle` getAndSet+cancel, :1383-1407); stale-sentinel recovery (§MF-1 gate r2 — set foreign sentinel via seam, open new sid, Reconnect still fires); watchdog-fires-before-first-frame (bgpt MF-2); reconnect-after-delay sid re-check (:1301).
  - `TokenStateDispatcherTest`: dedup-false drops frame before reducer (:790-828); resync null-sid rewrite (:766-771); deferred `TriggerSinceFetch` runs after lock release (:724/:916); removal-hook order (reducer overlay clear → hook → effect, :832-846 contract).
- **Lane 13**: no new behavior → no new tests required. Compile-time Hilt graph validation is the proof; the existing suite is the net.
- **Lane 15b**: no new tests required (pure code-motion; `ChatScaffoldSaveableTest` is the positionality net). Optional follow-up: a focused `rememberChatDerivedState` unit test (not required for this batch).

---

## 10. Risks (honest)

1. **TSC happens-before preservation (highest risk).** The engine's correctness rests on: single-monitor serialization (`bundleCommitLock` shared with `OpenCodeRepository.configure`), atomics read outside the lock, the §MF-1 sentinel's unconditional set/clear, the LAZY-start + getAndSet-before-`start()` ordering (:1383-1407), and CAS bundle binding (:1323-1334). Splitting across 4 classes must not introduce a path that mutates state **outside** the lock (e.g. a component method forgetting `synchronized`), nor split a today-atomic compound op across lock acquisitions. Mitigations: (a) facade `close` keeps one outer `synchronized` wrapping the 4 component cleanups (reentrancy makes it atomic, §4.1-2); (b) move code **verbatim** per §4.2 — no "improvements" in the same commit; (c) the idempotency/bundle-identity/route-wiring tests are the exact regression net for these interlocks and must stay green at every step.
2. **The supervisor↔dispatcher `lateinit` wiring** (§4.2) is a construction-order smell. Bounded: single-threaded main scope, first dereference at first `open()`. Pinned by `StreamLifecycleSupervisorTest`.
3. **internal `@Inject constructor` Hilt risk (lane 13).** Assessed as non-issue (§3.1: bytecode-public, same module) but unproven in this codebase — nothing currently requests an `internal` class from the graph. If KSP rejects it, the fallback is a 5-function `@Provides` module (documented in §3.1) — a 30-minute detour, not a redesign.
4. **AppCore test-factory blast radius.** A broken `createCore` fails ~10 test files at once. Acceptable: the failure mode is loud and total (nothing silently passes), and the fix is mechanical.
5. **Eager-vs-lazy construction delta.** Orchestrators are now built at AppCore construction. Verified no init side effects (§3.1); residual risk: a future orchestrator gaining an init block must respect this — noted in the rewritten AppCore kdoc.
6. **ChatScaffold slot-positionality / recomposition drift.** Moving `rememberSaveable`s changes the slot table shape; if order changes, saved state restores into the wrong slot (silently). Net: `ChatScaffoldSaveableTest` + the rule "flags move as one ordered block". Recomposition widening is mitigated by per-field `State` granularity and verbatim remember keys (§5.3); no compose-metrics gate is required for a pure code-motion, but reviewers should diff remember key lists.
7. **`cachedContextUsage` write-during-composition** (ChatScaffold.kt:537-539) is a pre-existing smell that must move **verbatim** into the derived-state factory (it feeds `rememberChatTopBarState`). Do not "fix" it in this batch — logged as follow-up F2.

---

## 11. Out-of-scope / follow-ups

**Explicitly NOT in this batch:**
- **AppCore further God-object split** beyond DI migration: the slice accessors (:300-312), write helpers (:318-329), init-block side-effect chains (:422-583), and the 5 domain dispatchers stay. AppCore remains the composition root + effect sink by design (R-17 batch3d kdoc :47-79).
- **Effect-cascade extraction** into dispatcher classes — declined, §3.4.
- **ControllerModule.kt** provider-body restructuring (TSC provider stays as-is; the orphaned `OkHttpClientFactory`/`SslConfigFactory` note at :284-291 is a separate follow-up already documented there).
- `ConnectionCoordinator.kt` (1051 lines) and `ChatViewModel.kt` (956 lines) — next god-class candidates (handoff Top-5 rows 4-5), separate batch.

**Follow-ups discovered (log, do not implement):**
- **F1**: `ControllerModule.kt:46-53`'s "internal classes can't use `@Inject constructor`" claim is folklore (§3.1). If lane 13 proves constructor injection works for the internal orchestrators, correct that kdoc in a comment-only pass (or fold into lane 13's commit).
- **F2**: ChatScaffold.kt:537-539 writes `cachedContextUsage` during composition (smell); move verbatim now, redesign later.
- **F3**: After the TSC split, `TokenStreamCoordinator`'s kdoc (the §Stage-D1/D2 scoping essay, :29-95) should be rewritten as a component map — included in lane 15a step 5, but the deeper "D1 engine vs D2 wiring" documentation debt (which D2 items are now done) is a docs follow-up.
- **F4** (carried from batch1): the slim fan-out seam still has no production entry trigger (batch1 §5.1 Fact 2 / F3) — unchanged by this batch; item 13's `dispatchSessionSyncEffect` keeps routing to the inert scheduler.
- **F5**: `RefreshOrchestrator`/`SendOrchestrator` take the concrete `OpenCodeRepository` (RefreshOrchestrator.kt:44, SendOrchestrator.kt:34) while their siblings use narrow seams — a narrowing candidate for a future batch (not required for Wave2.2; Hilt provides either).

---

## 12. Verification plan (per-lane gates)

1. **Mandatory, all lanes**: `./scripts/check.sh` green (compile + `testDebugUnitTest`); LSP diagnostics clean after each edit; each §3.6/§4.5/§5.4 step lands green or is split smaller.
2. **Lane 13 targeted**: `--tests "*AppCoreDispatcherTest*" --tests "*AppCoreOrchestrationTest*" --tests "*ForkSessionTest*" --tests "*ViewModelTest*"`; grep proof: no `by lazy` remains in AppCore.kt; `SessionOpener(`/`RefreshOrchestrator(` etc. appear only in the two test factories.
3. **Lane 15a targeted**: `--tests "*TokenStream*" --tests "*SlimV2Wire*" --tests "*B2RouteWiring*" --tests "*ConnectionCoordinatorConcurrent*"`; grep proof: `TokenStreamCoordinator.kt` under ~300 lines; the §2.2 boundary greps (comments-only `slim` in the sse package facade).
4. **Lane 15b targeted**: `--tests "*ChatScaffoldSaveableTest*"`; diff review of remember key lists (§10-6).
5. **Boundary re-verification (reviewers, §6)**: the two greps quoted in §6's integrity note must show comments-only/zero hits **after** the splits as well.
6. **Batch-level**: final `./scripts/check.sh` on the integration commit; `--full` (lint + coverage) before the next release tag. No emulator/instrumented runs required (no behavior change reachable by UI; device-safety policy).
