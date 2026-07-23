# Token Stream — 手动烟雾测试（Manual Smoke Test）

> **⚠️ 前提**：阅读并理解 [`docs/token-stream-dev-plan.md`](./token-stream-dev-plan.md) §3（v3 权威契约）和 §9（完成总结），熟悉客户端 token-stream 集成的设计、状态模型与生命周期。

---

## 0. 总则

本文描述 **5 个衰退场景**（regression scenario），覆盖 token-stream 集成的核心行为面。每个场景定义精确的 **Preconditions（前置条件）**、**Steps（执行步骤）** 与 **Expected（预期结果）**，以便在本地模拟器或真机上对 ocdroid client 进行手动验收测试。

---

## 1. 环境准备（通用 Preconditions）

所有场景共享下列先决条件，在每一步**之前**确认满足：

| # | 条件 | 命令/检查 |
|---|---|---|
| 1.1 | 模拟器未占用（共享资源纪律，见 `docs/emulator-debug.md` §1） | `./scripts/emulator.sh status` → 显示「未运行」或等效 |
| 1.2 | 模拟器已启动（headless 或窗口化均可） | `./scripts/emulator.sh start`（若 1.1 为「未运行」） |
| 1.3 | 构建环境已导出 | `source ./scripts/env.sh` 或已写入 `~/.zshrc` |
| 1.4 | 应用已安装并首个 Profile 已建立 | `./gradlew installDebug` 或等效；首次启动时按 UI 引导配置一个 host 条目（指向本地 slimapi sidecar） |
| 1.5 | slimapi sidecar 已运行（本机 stunnel 直连或远程） | 验证 `GET http://{base}/slimapi/health` 返回 200 且 `features.tokenStream == true` |
| 1.6 | 正有一个活跃的生成会话（至少一条用户消息已发送且 assistant 正在流式回复） | `ChatScreen` 可见，消息卡片下方有活跃 token stream（白色闪烁光标 / "正在思考…" 提示） |

---

## 2. 场景

---

### 场景 1：busy-open 生成中

**观察点**：用户打开一个正在被生成中的会话，确认 token stream 实时逐 token 动画，且 partId 在 MessageCard 中有稳定的 list key。

| # | 步骤 | 期望 |
|---|---|---|
| 2.1.1 | 在任一已有生成中的会话（Precondition 1.6），按 Back 离开 `ChatScreen`。 | — |
| 2.1.2 | 从 `SessionList` 中再点该会话行进入 `ChatScreen`。 | 消息窗口即刻显示该会话的历史骨架列表（无白色闪烁光标），**不**弹出新 spinner/loading。 |
| 2.1.3 | 观察消息卡片区域。 | 该消息的最后一条 assistant 消息的 text part，其 **前段** 为已生成的**完整文本**，**尾段** 正在逐 token 追加（白色闪烁光标 / "正在思考…"）。每个 token 的字符在原位刷入，无全量替换。 |
| 2.1.4 | 检查 MessageCard 的 kotlin UI 布局。 | partId（`MessageCard.kt:469` 的 `streamingPartTexts[part.id]`）有稳定值，即使骨架 /since 合并时也不变。 |

---

### 场景 2：watchdog 半开（杀 sidecar）

**观察点**：token stream 连接在 45s 无帧后被 watchdog 强制断开，触发 overlay 清除 + 权威 `/since` 重拉 + reocnnect 退避重连。

| # | 步骤 | 期望 |
|---|---|---|
| 2.2.1 | 进入一个活跃生成的会话（Precondition 1.6）。 | token stream 动画正常。 |
| 2.2.2 | 杀掉 slimapi sidecar 进程（模拟侧car 崩溃 / 网络断连）。 | — |
| 2.2.3 | 等待 ≤45s。 | **watchdog 触发**：overlay 文字消失（`streamingPartTexts` 与 `streamOwned` 被 `ClearTokenStreamState` 清空），UI 恢复 skeleton 假象。 |
| 2.2.4 | sidecar 恢复（重启 stunnel / 拉起 sidecar 进程），观察 `ChatScreen`。 | ≤若干秒后，客户端探测 `/slimapi/ready` 回报 upstream 可达，自动触发**权威 catch-up**（`GET /slimapi/messages/{sid}/since/{ts}`），消息列表刷新为新快照（`authoritative=true`）。旧 overlay 不复活。 |
| 2.2.5 | sidecar 恢复后，检查新发送的 prompt 是否仍可正常触发 token stream。 | 用户发新消息，assistant 照常流式回复。 |

---

### 场景 3：session.deleted / 404

**观察点**：活跃的正在生成会话被服务端标记 deleted 或返回 404，token stream 立即关闭，overlay 清除，不阻塞。

| # | 步骤 | 期望 |
|---|---|---|
| 2.3.1 | 进入一个活跃生成的会话（Precondition 1.6）。 | token stream 动画正常。 |
| 2.3.2 | 通过服务端管理 UI / 直接 API 删除/归档该 session（模拟 `session.deleted` digest）或使 `/slimapi/sessions/{sid}/status` 返回 404。 | — |
| 2.3.3 | 观察 `ChatScreen`。 | token stream **立即终止**，overlay 文字消失（`ClearTokenStreamState(sid)` 被调用）。 |
| 2.3.4 | 尝试在该会话上发新消息。 | button 禁用（UI 知道 session 已终态），或点击后 Snack bar 报"会话已删除"。 |

---

### 场景 4：快速换 session

**观察点**：max-1 前台 stream 契约：开新 session 时旧 session 的 stream 关闭不残留。

| # | 步骤 | 期望 |
|---|---|---|
| 2.4.1 | 进入一个活跃生成的会话（Precondition 1.6）。 | token stream 动画正常。 |
| 2.4.2 | 从 `SessionList` 选择另一个（非当前）会话行，点击进入。 | 旧 session 的 token stream **立即关闭**（overlay 文字消失），新 session 的 skeleton 列表出现（若新 session 也有未完成的流式部分，将在下半拍重新开流）。 |
| 2.4.3 | 快速连续点切 3~5 个不同的会话。 | 每次切换，旧 session 流立即关，新 session 骨架出，风控无双开（≥2 并发流）。 |
| 2.4.4 | 切回→之前刚切走的某个会话（该会话仍在生成中）。 | 同场景 2.1.x，该会话的 skeleton 完整，尾段恢复逐 token 追加（**如果**该会话服务端仍在生成；若已生成完，则不再开流）。 |

---

### 场景 5：features.tokenStream = false

**观察点**：capability 门控：当 slimapi 侧未开启 token stream feature 时，客户端零开 token 流，完全退化为 legacy 行为。

| # | 步骤 | 期望 |
|---|---|---|
| 2.5.1 | 确认 slimapi sidecar 的 `/slimapi/health` 中 `features.tokenStream == false`（或缺失该键）。 | — |
| 2.5.2 | 进入一个~曾经~有活跃 token stream 的会话，验证该会话仍有历史 token 显示，但**不再有**新的 token 动画。 | 消息卡片里已完成的 token 文字仍可见，但新 prompt → assistant 回复**不走 token 流**（退化为 legacy `message.part.*` SSE path）。 |
| 2.5.3 | 检查 client 侧的 capability 降级日志。 | `DebugLog` / adb logcat 可见类似 `TokenStreamCoordinator: open skipped — features.tokenStream=false` 的条目。 |

---

## 3. 清理

测试完成后，**必做**以下步骤归还模拟器资源：

```bash
./scripts/emulator.sh stop      # 关模拟器，清理环境
```

---

## 4. 对照指针

- 场景 1 ~ 5 的预期行为详细解释见 [`docs/token-stream-dev-plan.md`](./token-stream-dev-plan.md) §9（完成总结）和 §3（v3 权威契约）。
- 底层实现对应 `TokenStreamCoordinator.kt`（watchdog/epoch/clear/generation-guard）和 `AppCoreOrchestration.kt`（`loadMessagesForEffect` busy-open 门控）。
