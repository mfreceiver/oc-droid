# 新会话移交提示词（复制到新会话第一条用户消息）

---

你是 ocdroid 仓库的 workflow manager。请**立即加载并执行**体验优先路线，不要重新从契约调研开场。

## 项目与版本

- 仓库：`/home/mar/personal_projects/ocdroid`，分支 `main`
- 已发版：**v0.11.10** commit `69724a2`（slimapi rev F 消费已上线）
- 文档：`2af8a8c` 路由/rev F 对齐；**体验优先方案**在  
  **`docs/0.11-ux-first-joint-plan.md`**（**权威**）
- slimapi 仓：`/home/mar/personal_projects/oc-slimapi`，**v0.3.0**，wire **1**
- 给 slimapi 的协作稿：`docs/ocmar/plans/2026-07-21-slimapi-collab-prompt.md`
- 旧 roadmap（契约向，优先级已被覆盖）：`docs/0.11-joint-roadmap.md`

## 用户指导思想（不可偏离）

1. **尽快完善 App 体验**  
2. **避免「展开失败」**  
3. **提升省流效果**  
4. **改善网络质量较差时的体验**  

Wire 保持 1；不抢跑去 placeholder / cursor / replay / wire v2。

## 已完成（禁止重做）

- placeholder message-level 替换、三头、reconfigured、coalesce（v0.11.10）
- T2 focus gate、T3 死锁测、UI 高级堆叠、upload poll-wait（0.11.8–0.11.9）
- T1 prepare→commit：**NO-GO**，勿重启

## 已知代码缺口（优先修）

| 问题 | 位置 |
|---|---|
| G6 **413 只 `ids.take(size/2)`，后半丢失** | `OpenCodeRepository.expandBatchInternal` ~2304 |
| IOException/超时 → `Failed(null)` **无重试** | 同文件 catch |
| 503 退避**忽略 Retry-After** | 同文件 503 分支 |
| `Complete=false` 按 directory **整页替换**，可能删 prior | `SessionSyncCoordinator.applySlimColdStartSnapshot` ~3224 |
| 笼统「展开失败」 | `PartExpandState` / `ChatViewModel` / UI 文案 |

## 本会话应执行的顺序

### Phase A — 实现（建议 fixer，用户曾指定 fixer-zlm 时可继续）

1. **O-A 展开恢复状态机**（最高优先）  
   - 413 两半合并  
   - 瞬态 503/IO/timeout 有界重试 + Retry-After  
   - 200 errors[] 仅重试失败 mid  
   - 404 thin_route_not_found **每 host 缓存**后直走单条  
   - 同 mid 单飞  
   - 终态分类（Loaded / Retrying / Offline / not_found / too_large）  
2. **O-B UI**：禁止已知 code 仍显示笼统「展开失败」；保留 skeleton  
3. **O-C Complete=false** 保留 prior sessions + 单测  
4. **O-D** 可选：TrafficCounting 关联 expand operationId  
5. `./scripts/check.sh` 必须绿  

### Phase B — 验证

- MockWebServer 故障矩阵（方案 §3 P0-D）  
- 真机 spot-check：展开、断网、重连（用户确认后再 emulator/真机）  

### Phase C — 发版

- 用户明确要求时再 `release.sh patch`（建议 v0.11.11）  
- 未要求则 **不 commit/不 push**（除非用户说 commit）  

### 与 slimapi

- 不阻塞在对方回复；客户端可独立修 413/重试/Complete  
- 需要对方的：Retry-After、fixtures、G6 指标、部署身份  
- 协作话术见 `docs/ocmar/plans/2026-07-21-slimapi-collab-prompt.md`  

## 硬规则（AGENTS.md）

- 改 Kotlin 后必须 `./scripts/check.sh`  
- 发版只走 `./scripts/release.sh`  
- 真机插桩测试默认禁；模拟器 `emulator.sh` 用前 status、用完 stop  
- UI overlay 走 `docs/ui-style-spec.md`  

## 验收指标（摘录）

- 健康 mid 展开 100% Loaded  
- 1–2 次瞬态失败后最终 Loaded  
- 完成后 Loading 数 = 0  
- 413 所有 ids 有结局  
- Complete=false 误删 prior = 0  
- 未展开无 full 请求  
- 同 mid 并发展开 ≤1  

## 第一句动作建议

1. 读 `docs/0.11-ux-first-joint-plan.md`  
2. 读 `OpenCodeRepository.expandBatchInternal` 413/503/IO 分支  
3. 读 `applySlimColdStartSnapshot` Complete/Ready 分支  
4. 建 todo：O-A → O-B → O-C → check.sh  
5. 派 fixer 实现 O-A（413 两半 + 瞬态重试），不要先写长文档  

## 禁止

- 重新做契约调研当主线  
- 重开 T1  
- 无数据上 reasoning 省略服务端大改  
- 把 health schema UI、去 placeholder 当 P0  

---

**开始：按体验优先方案推进 O-A 展开恢复修复。**
