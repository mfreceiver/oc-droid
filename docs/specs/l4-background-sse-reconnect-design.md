# L4 Background SSE Drop Recovery Implementation Plan

> **For agentic workers**: execute this plan module-by-module with fresh fixer-zlm sessions. A worker receives only its module, the shared contracts in §3, and direct dependencies. Do not modify files outside the declared write scope.
>
> **Status**: approved by the user after rev-ogpt design review. This document is the authoritative implementation contract for background main-SSE transport loss and foreground recovery.
>
> **Scope precedence**: for reconnect ownership, transport liveness, retry, foreground recovery, and Service destruction, this document supersedes the corresponding reconnect clauses in [`l4-sse-lifecycle-design.md`](l4-sse-lifecycle-design.md) and [`slimapi-v2-adapt-traffic-plan.md`](slimapi-v2-adapt-traffic-plan.md).
>
> **Execution model**: implement M0-M8 by dependency wave with fresh fixer-zlm sessions. Modules in the same wave may run in parallel only when the write scopes below remain disjoint. Do not commit unless explicitly requested.

**Goal**: retain a healthy main SSE during `BackgroundGrace`, and recover a genuinely dropped transport exactly once on foreground without stale ownership, duplicate collectors, permanent retry exhaustion, or misuse of L5 recovery state.

**Architecture**: a process-level transport runtime is the only liveness authority, while a single reconnect supervisor owns foreground recovery. Ownership remains the bootstrap-exclusion mechanism (`null → Starting → Ready`), and the coordinator prepares a legal L3 bootstrap state but never competes as a second reconnect decision maker.

**Tech stack**: Kotlin, coroutines/`StateFlow`, Hilt/Dagger, MockK/JUnit, coroutine virtual time, Android Service lifecycle.

## Global constraints

- Background main SSE remains best-effort; this plan does not add a persistent FGS guarantee.
- `StreamingOwnershipGate.Ready` always means a current live transport.
- Global `SseLifecyclePolicy.recoveryNeeded` remains L5-exclusive.
- Only `SseReconnectSupervisor` may call low-level launcher recovery in production.
- All Kotlin/resource changes must pass `./scripts/check.sh`; instrumentation, if required, uses an idle emulator only.
- No release, signing, version, commit, or upload operation is part of this plan.
- The current working tree contains the rejected fix-9 recovery seam; M2 and M7 explicitly remove it. Its green compilation is not design acceptance.

## 0. Approved product decisions

1. A healthy main SSE may remain alive during `BackgroundGrace` so the app can receive decision notifications.
2. Background delivery is **best effort**. Android background Service/process survival is not guaranteed; this project does not add a persistent FGS solely to guarantee 15-minute notification delivery.
3. Moderate overlap between SSE-derived state and foreground REST reconciliation is accepted. L3 reload scheduling remains the traffic-rate authority.
4. A dropped socket or destroyed Service must recover reliably on the next foreground. While the app remains foreground, recovery retries indefinitely with a five-minute maximum interval.
5. An SSE-only drop does not mean the REST/server connection is unavailable. UI may project `isSseConnected=false` or a reconnecting phase while REST health remains connected.
6. Background grace performs no question/permission REST polling. A retained SSE may best-effort publish local question/permission notifications that require a user decision; background idle notifications remain suppressed. `NoSourceTerminal` is fully silent.

## 1. Problem and rejected approaches

### 1.1 Required behavior

```text
BackgroundGrace + healthy socket
  → keep the current collector and ownership; foreground is a no-op.

BackgroundGrace + socket/Service loss
  → record a transport drop, release live ownership, wait while background,
  → foreground prepares a legal bootstrap state,
  → exactly one bootstrap reconnects,
  → first valid frame commits Live + Ready + L1.
```

The recovery must also survive a rejected bootstrap, identity replacement, a foreground/background race, and unexpected Service destruction.

### 1.2 Rejected approaches

- **Bare `releaseNow(identity)`**: unsafe by itself. The launcher can start an `ACTION_BOOTSTRAP` while the coordinator is not in legal L3, producing a superseded handoff and a leaked Starting owner.
- **Stale Ready ownership**: a dead collector cannot remain `Ready`. It causes false health success, suppresses required bootstrap, and violates `Ready = live transport`.
- **Global `SseLifecyclePolicy.recoveryNeeded` as a reconnect flag**: forbidden. That state belongs to L5 data reconciliation and may only be cleared by L5 completion.
- **Coordinator and launcher as two independent foreground reconnect triggers**: forbidden. It creates duplicate-path ordering and deduplication ambiguity.
- **Finite retry count followed by permanent stop**: forbidden. A foreground user must not remain permanently disconnected after transient rejection.

## 2. Architecture and ownership

The design separates transport truth, Service/bootstrap ownership, lifecycle orchestration, and reconnect decisions.

| Component | Single responsibility |
|---|---|
| `SseTransportRuntimeStore` | Process-level transport truth and drop-ticket ownership |
| `StreamingOwnershipGate` | Exclusive Service/bootstrap ownership; `Ready` means a live transport |
| `StreamingLifecycleCoordinator` | L1/L2/L3/BackgroundGrace/terminal transitions and source handoffs |
| `DefaultSseReconnectSupervisor` | The only foreground reconnect decision maker, single-flight, retry watchdog |

### 2.1 Global invariants

- **I1 — transport truth**: only `SseTransportRuntimeStore` determines whether main SSE is Connecting, Live, Retrying, Dropped, or Stopped.
- **I2 — Ready is live**: `StreamingOwnershipGate.Ready` may exist only while runtime state is `Live` for the same identity.
- **I3 — one reconnect owner**: only `SseReconnectSupervisor` may invoke low-level `StreamingServiceLauncher.ensureStarted(identity)` for foreground recovery.
- **I4 — same drop demand**: a rejected recovery attempt restores the same `TransportDropTicket`; it does not generate a new drop ID or clear demand.
- **I5 — success acknowledgement**: a drop ticket is cleared only after a current attempt receives a valid frame and the coordinator/ownership commit succeeds.
- **I6 — intentional stop is not a drop**: user close, terminal teardown, reconfigure teardown, and explicit `StopSse` publish `Stopped`, never `Dropped`.
- **I7 — generation safety**: an older attempt/drop token cannot mutate newer identity or attempt state.
- **I8 — REST and SSE health are separate**: runtime SSE loss cannot alone write server-unreachable REST state.

## 3. Contract-first types

Create `service/streaming/SseTransportRuntime.kt`:

```kotlin
sealed interface SseTransportState {
    data object Stopped : SseTransportState
    data class Connecting(val attempt: TransportAttemptToken) : SseTransportState
    data class Live(val attempt: TransportAttemptToken) : SseTransportState
    data class Retrying(val attempt: TransportAttemptToken) : SseTransportState
    data class Dropped(val ticket: TransportDropTicket) : SseTransportState
}

data class TransportAttemptToken(
    val attemptId: Long,
    val identity: ConnectionIdentity,
    val recoveryTicket: TransportDropTicket?,
)

data class TransportDropTicket(
    val dropId: Long,
    val identity: ConnectionIdentity,
    val reason: TransportDropReason,
)

enum class TransportDropReason {
    BACKGROUND_RECONNECT_REFUSED,
    RETRY_EXHAUSTED,
    SERVICE_DESTROYED,
    OWNER_MISSING,
}
```

State semantics:

- `Stopped`: no collector is intended; supervisor must not recover automatically.
- `Connecting`: collector launched, no current valid frame received.
- `Live`: at least one current-identity valid frame received.
- `Retrying`: a previously live foreground transport is retrying internally.
- `Dropped`: collector exited and no internal retry remains; supervisor owns recovery.

`SseTransportRuntimeStore` is `@Singleton @Inject constructor()`. It exposes the current state and a Boolean projection where only `Live` maps to `true`. Attempt IDs and drop IDs are monotonic. Every mutation validates identity and attempt token before committing.

The contract is fixed as follows; downstream modules must not invent aliases or module-local wrappers:

```kotlin
@Singleton
class SseTransportRuntimeStore @Inject constructor() {
    val state: StateFlow<SseTransportState>
    val sseConnectedFlow: StateFlow<Boolean>

    /** Returns null when another identity owns a non-Stopped runtime state. */
    fun beginAttempt(identity: ConnectionIdentity): TransportAttemptToken?

    fun markRetrying(attempt: TransportAttemptToken): Boolean

    /** Marks transport liveness; retains recoveryTicket until full commit ack. */
    fun markLive(attempt: TransportAttemptToken): Boolean

    /**
     * Publishes Dropped for the current attempt. If attempt.recoveryTicket is
     * non-null, the same ticket is restored; otherwise a new monotonic dropId
     * is allocated. Returns null for stale/foreign attempts.
     */
    fun publishDropped(
        attempt: TransportAttemptToken,
        reason: TransportDropReason,
    ): TransportDropTicket?

    /** Valid only for current Live attempt after coordinator + ownership commit. */
    fun acknowledgeRecovery(attempt: TransportAttemptToken): Boolean

    /** Intentional teardown; rejected for stale/foreign attempts. */
    fun markStopped(attempt: TransportAttemptToken): Boolean

    fun currentAttempt(identity: ConnectionIdentity): TransportAttemptToken?
    fun currentDropTicket(identity: ConnectionIdentity): TransportDropTicket?
}
```

Transition rules:

- `beginAttempt()` captures a matching current `Dropped.ticket` into `recoveryTicket`; it does not clear the ticket.
- `markLive()` projects SSE connected immediately after a valid frame, but the recovery ticket remains attached to the attempt.
- `acknowledgeRecovery()` is the only operation that removes a recovery ticket, and only after runtime Live + coordinator commit + ownership Ready.
- `publishDropped()` on a recovery attempt restores the exact existing ticket, including drop ID and original identity.
- all stale-attempt operations return `false`/`null` and leave state unchanged.

Create `service/streaming/SseReconnectContracts.kt`:

```kotlin
interface UnexpectedTransportDropHandler {
    /** Implementer releases ownership before calling runtime.publishDropped. */
    fun onUnexpectedDrop(
        attempt: TransportAttemptToken,
        reason: TransportDropReason,
    )
}

enum class ForegroundTransportStartReason {
    DROPPED_TRANSPORT,
    HEALTH_CONFIRMED,
}

sealed interface ForegroundTransportStartPreparation {
    data object Ready : ForegroundTransportStartPreparation
    data object SupersededIdentity : ForegroundTransportStartPreparation
    data object NotEligible : ForegroundTransportStartPreparation
}

interface ForegroundTransportStartPreparer {
    suspend fun prepareForegroundTransportStart(
        identity: ConnectionIdentity,
        dropId: Long?,
        reason: ForegroundTransportStartReason,
    ): ForegroundTransportStartPreparation
}

interface SseReconnectSupervisor {
    fun start()
    fun requestReconcile()

    suspend fun ensureConnected(
        identity: ConnectionIdentity,
        trigger: SseReconnectTrigger,
    ): OwnershipStartResult
}

enum class SseReconnectTrigger {
    DROPPED_TRANSPORT,
    HEALTH_CONFIRMED,
    EXPLICIT_RECONCILE,
}
```

Later modules must consume these shared types rather than invent module-local trigger enums or drop callbacks. `UnexpectedTransportDropHandler` exists specifically to guarantee the observable ordering `ownership released → Dropped published`; the owner may not call `runtime.publishDropped()` directly.

## 4. End-to-end flows

### 4.1 Healthy socket: foreground no-op

Required proof:

```text
BackgroundGrace
+ runtime Live(current identity)
+ ownership Ready(current identity)
+ app foreground
→ coordinator BackgroundGrace → L1
→ supervisor returns Ready
→ launcher calls = 0
→ StartSse commands = 0
→ transport generation unchanged
```

Runtime Live and ownership Ready must both match the current full `ConnectionIdentity`. Layer or connection phase alone is insufficient.

### 4.2 Background drop: foreground recovery

```text
Live + Ready in BackgroundGrace
→ owner detects flow exception, normal completion, onClosed/onFailure, or heartbeat timeout
→ runtime Live → Retrying
→ reconnect gate rejects background retry
→ UI SSE projection false
→ ownership releaseNow(current identity)
→ runtime Retrying → Dropped(ticket)
→ supervisor observes foreground + matching Dropped
→ preparer atomically cancels grace timer/pending handoff and normalizes layer to L3
→ supervisor calls launcher exactly once
→ normal Service bootstrap registers Starting
→ bootstrap emits coordinator StartSse
→ owner creates Connecting(recoveryTicket=ticket)
→ first valid frame marks Live (ticket retained)
→ coordinator Ready commit enters L1
→ ownership markReady
→ runtime acknowledgeRecovery(attempt) clears ticket
```

Publishing `Dropped` occurs only after liveness is false and Ready ownership has been released. Consumers must never observe `Dropped + Ready` for the same identity.

### 4.3 Rejected recovery

```text
Dropped(ticket=7)
→ attempt Connecting(recoveryTicket=7)
→ activation Rejected
→ runtime returns to Dropped(ticket=7)
→ foreground retry watchdog delays
→ later attempt reuses ticket=7
→ first valid frame + coordinator commit
→ Live and ticket cleared
```

Retry schedule:

```text
0s, 2s, 10s, 30s, 60s, 120s, then every 300s
```

- At most one retry job/attempt per identity/drop ID.
- Moving background cancels the delay/attempt schedule but preserves `Dropped`.
- Next foreground retries immediately.
- Identity replacement cancels the old ticket.
- `SseDisabled` pauses; setting change or explicit reconcile resumes.
- `StaleIdentity` terminates that old ticket.
- Bootstrap/platform/ack failures retain demand and continue capped retry.

### 4.4 Service destruction

`SessionStreamingService` owns:

```kotlin
private enum class ShutdownDisposition {
    UNEXPECTED,
    INTENTIONAL,
}
```

Before normal `stopSelf()` paths, set `INTENTIONAL`: no-source terminal, user close, lifecycle timeout, reconfigure rollback, and deliberate source teardown.

`onDestroy()` ordering:

```text
owner.cancelForShutdown() and capture active attempt
→ ownership releaseNow(identity)
→ INTENTIONAL: runtime markStopped
→ UNEXPECTED: runtime publish Dropped(SERVICE_DESTROYED)
→ cancel controller/scope
```

Process death loses the in-memory ticket. A fresh process recovers through normal health-confirmed bootstrap; drop tickets are not persisted.

## 5. Drop detection and clock rules

A drop may be established by:

- thrown SSE/flow exception;
- unexpected normal flow completion;
- EventSource `onClosed` or `onFailure`;
- post-first-frame heartbeat timeout;
- background reconnect refusal after the collector broke;
- unexpected Service destruction.

Intentional `StopSse`, terminal teardown, reconfigure teardown, and user close publish `Stopped`.

Do not infer a dead transport from absence of business events: an SSE may be legitimately idle. Preserve the first-frame timeout and heartbeat watchdog. Watchdog elapsed time must use a monotonic source (`System.nanoTime()` or Android elapsed realtime), never wall clock.

## 6. Supervisor and UI routing

`DefaultSseReconnectSupervisor` observes foreground, runtime state, identity, and ownership. It owns one single-flight per identity/drop ID and is the only production caller of launcher recovery.

Required production search invariant after M5:

```text
ensureStarted(
```

may occur only in:

- the `StreamingServiceLauncher` interface/implementation;
- `DefaultSseReconnectSupervisor`.

`ConnectionHealthProbe`, `ConnectionCoordinator.startSSE()`, and foreground catch-up route transport recovery through `SseReconnectSupervisor`. `ForceReconnect` retains health/reconcile meaning but no longer directly owns SSE startup.

UI semantics:

- runtime `Live` → `isSseConnected=true`;
- all other runtime states → `isSseConnected=false`;
- SSE-only loss may show reconnecting while REST `isConnected` remains true;
- only REST/identity bootstrap failure marks server unreachable.

## 7. Module plan and file ownership

### Shared module execution protocol

Every M0-M8 worker follows this sequence; a worker may not declare completion based only on compilation.

- [ ] Record the starting baseline with `git rev-parse HEAD` and the declared write scope.
- [ ] Add the focused failing test named by the module's acceptance criteria.
- [ ] Run the module's focused command and capture the expected failure before implementation.
- [ ] Implement only the declared interfaces/behavior; do not introduce permissive production defaults to keep an intermediate tree green.
- [ ] Re-run the focused command and capture PASS.
- [ ] Run `git diff --check -- <declared paths>` and `git diff --stat -- <declared paths>`.
- [ ] Report changed files, acceptance criteria proved, focused test output, and any dependency/interface mismatch. Do not commit.

Focused test commands:

```bash
# M0
source ./scripts/env.sh && ./gradlew :app:testDebugUnitTest \
  --tests 'cn.vectory.ocdroid.service.streaming.SseTransportRuntimeStoreTest'

# M1A/M4 combined lane
source ./scripts/env.sh && ./gradlew :app:testDebugUnitTest \
  --tests 'cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwnerTest' \
  --tests 'cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwnerSseConnectedTest' \
  --tests 'cn.vectory.ocdroid.service.SessionStreamingServiceShutdownDispositionTest'

# M1B
source ./scripts/env.sh && ./gradlew :app:testDebugUnitTest \
  --tests 'cn.vectory.ocdroid.data.api.SSEClientTest'

# M2
source ./scripts/env.sh && ./gradlew :app:testDebugUnitTest \
  --tests 'cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinatorTest'

# M3
source ./scripts/env.sh && ./gradlew :app:testDebugUnitTest \
  --tests 'cn.vectory.ocdroid.service.streaming.DefaultSseReconnectSupervisorTest'

# M5/M6 combined lane
source ./scripts/env.sh && ./gradlew :app:testDebugUnitTest \
  --tests 'cn.vectory.ocdroid.ui.controller.ConnectionCoordinatorTest' \
  --tests 'cn.vectory.ocdroid.ui.controller.ConnectionCoordinatorConcurrentTest'

# M7
source ./scripts/env.sh && ./gradlew :app:testDebugUnitTest \
  --tests 'cn.vectory.ocdroid.service.lifecycle.SseLifecyclePolicyTest'

# M8
source ./scripts/env.sh && ./gradlew :app:testDebugUnitTest \
  --tests 'cn.vectory.ocdroid.service.streaming.SseBackgroundDropRecoveryIntegrationTest'
```

If an exact test class above does not yet exist, the owning module creates it at the file path named below. Do not silently substitute a broader unrelated test class.

### M0 — Contract-first runtime and reconnect interfaces

**Files**

- Create `app/src/main/java/cn/vectory/ocdroid/service/streaming/SseTransportRuntime.kt`
- Create `app/src/main/java/cn/vectory/ocdroid/service/streaming/SseReconnectContracts.kt`
- Create `app/src/test/java/cn/vectory/ocdroid/service/streaming/SseTransportRuntimeStoreTest.kt`

**Produces**: all contracts in §3. No dependencies.

**Acceptance**

- `M0-C1`: attempt/drop IDs are monotonic.
- `M0-C2`: stale attempt tokens cannot overwrite newer state.
- `M0-C3`: Rejected recovery restores the same drop ticket.
- `M0-C4`: Live is the only `sseConnected=true` state, but does not clear a recovery ticket before commit.
- `M0-C5`: identity mismatch cannot mutate state.
- `M0-C6`: only `acknowledgeRecovery(current Live attempt)` clears a recovery ticket.

### M1A — Owner transport integration

**Files**

- Modify `app/src/main/java/cn/vectory/ocdroid/service/streaming/ServiceSseConnectionOwner.kt`
- Modify `app/src/test/java/cn/vectory/ocdroid/service/streaming/ServiceSseConnectionOwnerTest.kt`
- Modify `app/src/test/java/cn/vectory/ocdroid/service/streaming/ServiceSseConnectionOwnerSseConnectedTest.kt`

**Consumes**: `SseTransportRuntimeStore`, `UnexpectedTransportDropHandler` from M0.

**Behavior**

- accepted connect begins a runtime attempt;
- first/current valid frame marks Live;
- post-Live failure marks Retrying;
- background reconnect refusal calls drop handler exactly once;
- recovery Rejected restores the original drop ticket;
- intentional disconnect marks Stopped;
- remove owner dependency on `SseLifecyclePolicy` and remove `markRecoveryNeededAndExit`.

**Acceptance**

- `M1A-C1`: background drop callback fires exactly once.
- `M1A-C2`: foreground internal retry does not publish Dropped.
- `M1A-C3`: exception and unexpected normal completion share one drop path.
- `M1A-C4`: stale transport generation cannot mark Live.

### M1B — SSEClient heartbeat/half-open hardening

**Files**

- Modify `app/src/main/java/cn/vectory/ocdroid/data/api/SSEClient.kt`
- Modify `app/src/test/java/cn/vectory/ocdroid/data/api/SSEClientTest.kt`

**Dependencies**: none. The Flow failure contract remains unchanged.

**Acceptance**

- `M1B-C1`: continuous heartbeat prevents timeout.
- `M1B-C2`: 30 seconds without heartbeat after readiness closes the source.
- `M1B-C3`: wall-clock jumps do not change deadline behavior.
- `M1B-C4`: first-frame timeout remains owner-controlled.

### M2 — Coordinator foreground preparation

**Files**

- Modify `app/src/main/java/cn/vectory/ocdroid/service/lifecycle/StreamingLifecycleCoordinator.kt`
- Modify `app/src/test/java/cn/vectory/ocdroid/service/lifecycle/StreamingLifecycleCoordinatorTest.kt`

**Implements**: `ForegroundTransportStartPreparer` from M0.

**Consumes**: `SseTransportRuntimeStore`.

**Behavior**

- remove fix-9 `consumeRecoveryNeeded` and direct foreground `StartSse` paths;
- `BackgroundGrace + exact Live` enters L1 without reconnect;
- Dropped/Retrying/Connecting never write false L1 or direct StartSse;
- `prepareForegroundTransportStart` validates foreground, identity, and optional drop ID, cancels timers/pending handoff, advances policy foreground exactly once, and normalizes eligible dead-transport layers to L3;
- L2Idle rejects `DROPPED_TRANSPORT` because its SSE was intentionally stopped.

**Acceptance**

- `M2-C1`: alive foreground emits zero StartSse.
- `M2-C2`: dropped foreground emits zero direct StartSse.
- `M2-C3`: successful preparation leaves exactly L3.
- `M2-C4`: stale identity/drop ID changes nothing.
- `M2-C5`: rejected bootstrap remains L3.

### M3 — Default reconnect supervisor

**Files**

- Create `app/src/main/java/cn/vectory/ocdroid/service/streaming/DefaultSseReconnectSupervisor.kt`
- Create `app/src/test/java/cn/vectory/ocdroid/service/streaming/DefaultSseReconnectSupervisorTest.kt`

**Consumes**: M0 contracts, `StreamingServiceLauncher`, `StreamingOwnershipGate`, `ConnectionIdentityStore`, `AppLifecycleMonitor`.

**Behavior**

- observe foreground + Dropped;
- single-flight all `ensureConnected` calls;
- prepare coordinator before calling launcher;
- run §4.3 retry schedule;
- cancel on background/identity replacement, retaining demand;
- Live + Ready is a no-op;
- Live + missing owner creates `OWNER_MISSING` recovery.

**Acceptance**

- `M3-C1`: 100 concurrent calls invoke launcher once.
- `M3-C2`: Rejected attempts retry at virtual-time intervals.
- `M3-C3`: background invokes launcher zero times; next foreground retries immediately.
- `M3-C4`: stale drop ticket does not retry.
- `M3-C5`: success cancels watchdog; interval caps at 300 seconds.

### M4 — Service wiring and destruction classification

**Files**

- Modify `app/src/main/java/cn/vectory/ocdroid/service/SessionStreamingService.kt`
- Create `app/src/test/java/cn/vectory/ocdroid/service/SessionStreamingServiceShutdownDispositionTest.kt`

**Depends on**: M1A owner constructor/drop-handler contract.

**Behavior**

- drop handler receives `(attempt, reason)`, releases ownership, then calls `runtime.publishDropped(attempt, reason)`;
- remove owner `SseLifecyclePolicy` reconnect wiring;
- implement intentional/unexpected disposition;
- unexpected destruction publishes `SERVICE_DESTROYED`; intentional teardown publishes Stopped.

**Acceptance**

- `M4-C1`: onDestroy never leaves Ready ownership.
- `M4-C2`: unexpected destruction is observable by supervisor.
- `M4-C3`: StopSelf/no-source/user-close cannot auto-revive.
- `M4-C4`: recreated Service can register Starting normally.

### M5 — UI/health reconnect routing

**Files**

- Modify `app/src/main/java/cn/vectory/ocdroid/ui/controller/ConnectionHealthProbe.kt`
- Modify `app/src/main/java/cn/vectory/ocdroid/ui/controller/ConnectionCoordinator.kt`
- Modify `app/src/main/java/cn/vectory/ocdroid/di/ControllerModule.kt`
- Modify `app/src/test/java/cn/vectory/ocdroid/ui/controller/ConnectionCoordinatorTest.kt`
- Modify `app/src/test/java/cn/vectory/ocdroid/ui/controller/ConnectionCoordinatorConcurrentTest.kt`

**Consumes**: `SseReconnectSupervisor`.

**Behavior**

- remove direct UI/health launcher dependency;
- health success and `startSSE()` call supervisor;
- transport-ready Connected settles only after supervisor Ready;
- SSE loss preserves REST degraded-connected semantics.

**Acceptance**

- `M5-C1`: production `ensureStarted(` search satisfies §6.
- `M5-C2`: SSE drop does not mark REST unreachable.
- `M5-C3`: concurrent UI/health requests collapse in supervisor.

### M6 — DI integration

**Files**

- Create `app/src/main/java/cn/vectory/ocdroid/di/SseReconnectModule.kt`
- Create `app/src/test/java/cn/vectory/ocdroid/di/SseReconnectModuleTest.kt`

**Depends on**: M2, M3, M5.

**Bindings**

- `StreamingLifecycleCoordinator` as `ForegroundTransportStartPreparer`;
- `DefaultSseReconnectSupervisor` as `SseReconnectSupervisor`;
- eager application initialization calls supervisor `start()`.

**Compile-coupling rule**: M5 and M6 are separate design responsibilities but one execution lane. The same fixer-zlm implements M5 then M6 before running `./scripts/check.sh`, so no intermediate tree requests an unbound `SseReconnectSupervisor` interface.

### M7 — Remove rejected fix-9 recovery seam

**Files**

- Modify `app/src/main/java/cn/vectory/ocdroid/service/lifecycle/SseLifecyclePolicy.kt`
- Create `app/src/test/java/cn/vectory/ocdroid/service/lifecycle/SseLifecyclePolicyTest.kt`
- Modify KDoc in `app/src/main/java/cn/vectory/ocdroid/service/lifecycle/SseLifecyclePolicy.kt`

**Depends on**: M1A, M2, M4 removed all old callers.

**Behavior**

- remove `consumeRecoveryNeeded`;
- remove transport reconnect use of global `markRecoveryNeeded`;
- restore `recoveryNeeded` to L5-exclusive ownership;
- remove `TRANSPORT_LOST` when no L5 data-recovery meaning remains.

### M8 — End-to-end recovery gate

**Files**

- Create `app/src/test/java/cn/vectory/ocdroid/service/streaming/SseBackgroundDropRecoveryIntegrationTest.kt`
- Add emulator-only androidTest only if real Service recreation cannot be proven on JVM.

**Depends on**: M0-M7 merged.

**Acceptance**: all traces in §8 pass and `./scripts/check.sh` succeeds. Android instrumentation runs only on an idle emulator, never a physical device.

## 8. Parallel execution waves

```text
Wave 0:
  M0 contract-first

Dependency-free optimization:
  M1B may run in parallel with M0.

Wave 1 after M0 (parallel, disjoint write scopes):
  Lane A: M1A Owner + M4 Service wiring/destruction
  M2 Coordinator
  M3 Supervisor

Wave 2 after all Wave 1 lanes are reconciled:
  Lane D: M5 UI/health routing + M6 DI integration

Wave 3:
  M7 fix-9 cleanup        (after M1A + M2 + M4 remove callers)

Wave 4:
  M8 end-to-end gate      (after M0-M7 merge)
```

Do not assign overlapping write scopes concurrently. In particular, only one worker may own `SessionStreamingService.kt`, `StreamingLifecycleCoordinator.kt`, `ServiceSseConnectionOwner.kt`, or an owner/coordinator test file at a time.

Execution-lane rationale:

- M1A changes the owner constructor/drop callback and M4 wires that constructor into the Service. They are one compile-coupled lane; no permissive production default is allowed merely to keep an intermediate wave green.
- M5 introduces production consumers of `SseReconnectSupervisor`, while M6 binds and eagerly starts the implementation. They are one Hilt-compile-coupled lane.
- Design criteria remain labeled M1A/M4 and M5/M6 so reviewers can approve one responsibility while rejecting its neighbor, but the shared zlm must finish both before declaring its lane complete.

## 9. Criterion ownership matrix

| Criterion | Requirement | Owner | Dependencies | Verification |
|---|---|---|---|---|
| M0-C1..C6 | Runtime token, ticket, identity, projection and acknowledgement semantics | M0 | — | `SseTransportRuntimeStoreTest` passes |
| M1A-C1..C4 | Owner publishes exact transport transitions | M1A | M0 | focused owner tests pass |
| M1B-C1..C4 | Heartbeat/half-open monotonic watchdog | M1B | — | focused SSEClient tests pass |
| M2-C1..C5 | Coordinator preparation and alive no-op | M2 | M0 | L4 coordinator tests pass with real runtime/policy |
| M3-C1..C5 | Single-flight and persistent foreground retry | M3 | M0 + M2 contract | virtual-time supervisor tests pass |
| M4-C1..C4 | Service destruction classification | M4 | M1A | Service seam tests pass |
| M5-C1..C3 | Single reconnect route and UI semantics | M5 | M0/M3 contract | UI/controller tests + production search pass |
| M6 | DI bindings and eager start | M6 | M2/M3/M5 | Hilt compile/test passes |
| M7 | Global recovery restored to L5 ownership | M7 | M1A/M2/M4 | policy tests + zero `consumeRecoveryNeeded` search |
| M8-A | Drop → foreground reconnect | M8 | M0-M7 | integration trace A passes |
| M8-B | Rejected → same-ticket retry | M8 | M0-M7 | integration trace B passes |
| M8-C | Alive → foreground no-op | M8 | M0-M7 | integration trace C passes |
| M8-D | Unexpected Service destroy → recreate | M8 | M0-M7 | integration trace D passes |
| M8-E | Background during retry preserves ticket | M8 | M0-M7 | integration trace E passes |

## 10. Verification and completion gate

Per module:

1. Write/adjust a failing focused test first.
2. Implement only that module's contract.
3. Run the focused test.
4. Run the narrowest relevant compile/test task.
5. Record `git diff --stat` for review; do not commit.

At wave boundaries:

- reconcile every worker result and write-scope;
- run `./scripts/check.sh` after all wave changes are present;
- request rev-ogpt review of the wave against this contract before starting dependent waves.

Final completion requires:

```text
./scripts/check.sh
→ BUILD SUCCESSFUL
→ 0 test failures
```

If Service recreation needs instrumentation, first run `./scripts/emulator.sh status`; use an idle emulator only and stop it afterward. Never install/run debug instrumentation on a physical device without explicit user instruction.

## 11. Residual risk and non-goals

- Best-effort background SSE cannot survive OS process death. A new process uses health-confirmed bootstrap; the drop ticket is intentionally not persisted.
- Guaranteed 15-minute notifications require a compliant FGS with battery and persistent-notification costs; this is explicitly outside this reconnect redesign.
- This design does not change L3 digest throttling. It neither implements nor relaxes the receive-only REST fence in the parent L4/R2 contract; that remains separate L4 work.
- Allowing local question/permission decision notifications from a retained SSE does not authorize any background REST effect or poller.
- This design does not use global L5 recovery flags for transport reconnect.
