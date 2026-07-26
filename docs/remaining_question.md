# 尚未解决的问题

> 本文件记录 remaining-waves（v0.0.1）发版后遗留的技术问题。
> 按优先级和模块分类，供后续迭代处理。

---

## 变更日志

| 日期 | 变更 | 详情 |
|---|---|---|
| 2026-07-26 | Cat1 round 1-3 | P11 C3 全部 3 个残余竞态窗口已修复（dual-reviewer PASS: rev-glm 9.5 + rev-gpt 9.8） |
| 2026-07-26 | Cat4 NIT 清理 | 4.1 onSelectSession 死回调删除；4.2 kdoc 更新；4.3 lastNavPage 写点迁移完成（遗留 API setLastNavPage 保留 + TODO 标记，不阻塞） |
| 2026-07-26 | Cat6 文档更新 | 更新 gate 文件 + 本文件 + state.json |

---

## 1. P11 C3 并发原子性残余 ~~（已接受，低风险）~~ → **RESOLVED**

**来源**: Wave 3 P11 闭合门控 round 6（rev-bgpt BLOCK 8.4 → 用户接受 residual → Cat1 修复）  
**修复验证**: rev-glm PASS 9.5 + rev-gpt PASS 9.8（3 rounds）

### 1.1 PartFullTextReceived / PartDeltaReceived 无 BundleStamp — RESOLVED

- **位置**: `SharedConversationSseHandler.kt`
- ~~**问题**: 这两个 SSE leading-edge action 直接 dispatch 到 store，未经过 `dispatchBound()` 锁保护，也没有 `BundleStamp`~~
- ~~**影响**: `configure()` 发布新 bundle 后，旧 SSE 事件理论上仍可写入 `streamingPartTexts` / `streamOwned` / `partsByMessage`~~
- ~~**建议修复**: 为这两个 action 加 `BundleStamp` 字段，dispatch 改走 `dispatchBundleBound()` 或 coordinator 统一入口~~
- **修复**: 两个 action 已携带 `BundleStamp`，通过 `dispatchBundleBound()` coordinator 统一入口分发

### 1.2 Coordinator 内部 state 在锁外变更 — RESOLVED

- **位置**: `TokenStreamCoordinator.dispatchEpochFrame()` / `bridgePartToChatState()` / `handleEffect()`
- ~~**问题**: `reducerStateBySid` / `ownerByPartId` 的 check（`isBundleCurrentForCommit`）和 mutation（赋值）不在同一个 `synchronized(bundleCommitLock)` 块内~~
- ~~**影响**: 旧 bundle 的迟到帧理论上可在 check 和 mutation 之间穿插，污染 coordinator 内部状态~~
- ~~**建议修复**: 将 reducer 结果提交和 ownership 更新包入 `synchronized(bundleCommitLock)`；或用 `ReducerSlot(bundle, state)` CAS 替代直接 map 写入~~
- **修复**: check + mutation 均在 `synchronized(bundleCommitLock)` 内。附加修复：`onWatchdogTimeout` TOCTOU 关闭（`filterClearByGeneration` + `dispatchBound` 同一锁内）；`triggerSinceFetch` 推迟到锁外；`beginSession`/`beginStreamIncarnation` 包裹在 `synchronized(bundleCommitLock)` 中

### 1.3 dispatchBound() 入口未验证 stamp — RESOLVED

- **位置**: `TokenStreamCoordinator.dispatchBound()`
- ~~**问题**: helper 仅 `synchronized(bundleCommitLock) { store.dispatch(action) }`，不验证 action 的 `BundleStamp` 与当前 bundle 一致~~
- ~~**影响**: 未来新增调用方可能传入 stale stamp；目前依赖 reducer StoreState stamp 兜底~~
- ~~**建议修复**: `dispatchBound(boundBundle: ClientBundle, action)` 在锁内验证 `currentBundleProvider() === boundBundle` + `action.matchesBundle(boundBundle)`~~
- **修复**: `dispatchBound()` 现在在 `synchronized(bundleCommitLock)` 内验证 `BundleStamp` 与当前 bundle 一致，stale stamp 在分发时即被拒绝

---

## 2. 类型安全残余（IN PROGRESS — Cat2）

### 2.1 TokenStreamConnection.bundle / StreamLifecycle.bundle 为 Any?

- **位置**: `TokenStreamCoordinator.kt`
- **问题**: `bundle: Any?` 允许 null / 非 ClientBundle 类型；运行时靠 `as? ClientBundle` 检查
- **建议修复**: 改为 `bundle: ClientBundle`（非 nullable），从类型层面消除 fallback
- **状态**: 待修复（Cat2）

### 2.2 ResolvedEndpoint metadata 冗余字段

- **位置**: `EffectiveConnectionConfigResolver.kt`
- **问题**: `baseUrl` / `endpointFp` / `bundleGeneration` 是独立字段，可构造与 `bundle` 不一致的对象
- **建议修复**: 改为从 `bundle` 派生（`val baseUrl get() = bundle.hostSnapshot.baseUrl`）
- **状态**: 待修复（Cat2）

### 2.3 初始 generation-0 bundle 的 BundlePublished 初始化

- **位置**: repository 构造 / AppCore wiring
- **问题**: 初始 bundle（generation=0）可能没有通过 callback 发布 `BundlePublished` 到 StoreState；测试中手工补发，生产路径未明确
- **建议修复**: repository 构造完成后或 AppCore wiring 时显式发布初始 stamp
- **状态**: 待修复（Cat2）

---

## 3. 测试覆盖残余

### 3.1 B5-C3 / B6-C2 connectedTest（需模拟器）

- **来源**: Wave 2B B5 PASS 9.6 residual
- **缺失**:
  - 真实 `NavController` pop-restore 生命周期测试
  - `ScrollCheckpoint` Android Parcel 字节级 round-trip
  - 进程死亡 / 恢复 / deep-link child / 多层 child / config change 端到端
- **建议**: 编写 `connectedDebugAndroidTest`（需 `./scripts/emulator.sh start`）

### 3.2 T3.3-C4 模拟器集成测试

- **来源**: Wave 3 T3.3-C4
- **缺失**: mTLS / TOFU / REST / SSE / token-stream / command-mutation 端到端 connectedTest
- **建议**: 同上，编写 androidTest 覆盖真实网络栈

---

## 4. 代码清理（NIT 级）— RESOLVED

### 4.1 ChatTopBarActions.onSelectSession 死回调 — RESOLVED

- **位置**: `ChatTopBar.kt`
- ~~**问题**: `onSelectSession` 无调用方；SessionPicker 已由 `ChatOverlayHost` 直接调 `navigateToChat()`~~
- **修复**: 已删除该回调 + ChatScaffold 对应 wiring + 测试引用

### 4.2 ChatScaffold.onNavigateToSessions kdoc 过时 — RESOLVED

- **位置**: `ChatScaffold.kt`
- ~~**问题**: kdoc 写 "no longer used directly"，但实际已被 `ChatEmptyState` 使用~~
- **修复**: 已更新 kdoc（函数级 + 参数级），准确描述 `ChatEmptyState` 接线

### 4.3 NavState.lastNavPage @Deprecated — RESOLVED（主要写点已迁移）

- **位置**: `NavState.kt`
- ~~**问题**: 标注 `@Deprecated` 但仍有活跃写点（`SettingsManager` / `OrchestratorVM`）~~
- **修复**: 10 处 `mutateNav`/`mutateState` 写点中 9 处已移除 `lastNavPage` 写入（仅保留 `lastRoute`）。`setLastNavPage` 遗留 API 保留 + TODO 标记。`setLastRoute` 守卫简化（仅检查 `lastRoute`）。字段自身保留（兼容已有读取方）

---

## 5. release.sh 非标准 tag 解析（IN PROGRESS — Cat5）

- **位置**: `scripts/release.sh:77-80`
- **问题**: 最新 tag `v0.0.0-ci-smoke` 导致 `BASE="0.0.0-ci-smoke"` → `PATCH="0-ci-smoke"` → `$((PATCH+1))` 把 `ci` 当算术变量 → `ci: unbound variable`
- **当前状态**: v0.0.1 通过手动构建 + 手动 tag 绕过。Cat5 正在修复
- **建议修复**: 在 `IFS='.' read` 后对 MAJOR/MINOR/PATCH 做数字校验，非数字时 fallback 或报错；或过滤掉非 semver tag（如 `*-ci-*`）

---

## 6. 文档残余

### 6.1 P11 C3 residual gate 文件 — RESOLVED

- **位置**: `.ocmar/workflows/remaining-waves/gate-Wave3-P11-20260726073323.md`
- ~~**状态**: 已写入，含 C3 residual 文档~~
- **修复**: 已更新 gate 状态为 RESOLVED，C3 残余全部关闭，添加 dual-reviewer 验证记录

### 6.2 v4 整合方案中的 Wave 4/5

- **来源**: `docs/current/2026-07-25-integrated-remaining-work-plan.md`
- **Wave 4**: docs / 测试加固（未开始）
- **Wave 5**: follow-up（可延期，不阻塞）
- **建议**: 按需安排

---

## 优先级排序

| 优先级 | 项 | 模块 | 预计工时 | 状态 |
|---|---|---|---|---|
| P1 | ~~1.1 PartFullTextReceived/PartDeltaReceived BundleStamp~~ | ~~P11 C3~~ | ~~2h~~ | **RESOLVED** |
| P1 | ~~1.2 Coordinator 内部 state 入锁~~ | ~~P11 C3~~ | ~~3h~~ | **RESOLVED** |
| P2 | ~~1.3 dispatchBound 入口验证~~ | ~~P11 C3~~ | ~~1h~~ | **RESOLVED** |
| P2 | 2.1 bundle 类型改 ClientBundle | 类型安全 | 1h | IN PROGRESS (Cat2) |
| P2 | 5. release.sh tag 解析修复 | 工具链 | 0.5h | IN PROGRESS (Cat5) |
| P3 | 3.1/3.2 connectedTest | 测试覆盖 | 4h（需模拟器） | 待定 |
| P3 | 2.2/2.3 ResolvedEndpoint / 初始 stamp | 类型安全 | 1h | IN PROGRESS (Cat2) |
| P4 | ~~4.1/4.2/4.3 代码清理~~ | ~~NIT~~ | ~~1h~~ | **RESOLVED** |
| P4 | 6.2 Wave 4/5 | 文档/加固 | 按需 | 未开始 |
