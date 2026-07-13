# 未读机制重构：从消息事件驱动到 session 生命周期驱动 (ocdroid)

> 状态：**spec（实现契约 + 评审依据）**。
> 来源：三个 explorer 并行调查 + oracle 复核 + gpter 两轮终审（第二轮 approve-with-changes）+ 用户语义重定义 + 用户最终指令。
> 用户最终指令：`接受冷重启丢失窗口（但启动时仍有效存在的question应当显示），并在一次commit里完成。`
> 范围：一次原子 commit 完成未读语义迁移 + ping-pong 修复 + question tree 聚合 + 全生命周期清理。

---

## 1. 背景与问题（已诊断确认）

### 1.1 用户报告的两个故障
- **故障 1**：chat 页面某 tab 有未读提示，点进去看完切到其他 tab，未读提示还在。
- **故障 2**：列表 A、B 都有未读，点 A 回列表 B 还在；点 B 再回列表，A 的提示又出现（ping-pong）。

### 1.2 根因
- `SessionSwitcher.kt:226-230` 的 `previousWasBusyAndCleared` 判定 + `L396-400` 重标记写入：离开 busy 且 tempCleared 的 session 时无条件重新标为未读。多 busy session 来回切换必然 ping-pong，用户永远无法清零。
- `tempClearedUnread` 字段（`AppStateSlices.kt:360`）的唯一用途就是支撑这个重标记。

### 1.3 关联缺陷（gpter 终审确认）
- `message.created` 分支的未读标记是死代码（`SessionSyncCoordinator.kt:569-575` 注释自认 server 1.17.11+ 不再发）。
- `message.updated`（现代新消息来源）对非当前 session 直接 `return`（`L660`），不标未读。
- 现状导致：未读标记实际只在「离开 busy session」时由重标记机制产生，既漏报（真实新消息不标）又误报（ping-pong）。

---

## 2. 新未读语义（权威定义）

经用户重定义，未读不再绑定消息事件，而是绑定 **session 生命周期**：

1. **未读 = root session 从 busy 进入 idle**（任务完成、有结果可看，在 idle 转换时刻标记）。
2. **question 标记 = question 创建时标记**（已有机制，保留不动）。
3. **busy 的会话没有让用户读取的必要**（不标未读）。
4. **子 agent 无需用户干预、不提倡直接查看进展** → 子 agent 的 busy→idle **不标未读**；但**子 agent 的 question 应在主 agent（root）层提示**。
5. **解除未读**：归档 / 移除 workdir / 关闭 tab / 打开 session。
6. **解除 question**：问题被解决（本机或其他设备均算）。

### 2.1 冷重启丢失窗口（用户决策）
- **接受**：完全离线期间发生并完成的 run，没有服务端事件历史，无法精确恢复「刚 idle」的未读。此不可观测窗口是设计约束，不引入持久化未读状态。
- **约束（question 不丢失）**：启动时仍有效存在的 question 必须显示。question 走 REST `LoadPendingQuestions` 权威拉取（`loadPendingQuestionsAllWorkdirs`，已存在），本就不依赖 SSE 事件，冷重启不丢失——本条是声明性约束，确认 question 机制不受冷重启窗口影响，无需新增持久化。
- **重连兜底（仅在线）**：断线期间完成的 run，若本地「已知 busy」、重连时 REST `/session/status` 快照缺失该 id（idle 通过条目缺失表达），则推断为完成并补标未读。**仅对本地已知 busy 的 session**，**首次快照不得批量生产未读**。

---

## 3. 设计

### 3.1 未读生产（唯一新路径）
在 SSE `"session.status"` 分支（`SessionSyncCoordinator.kt:489-567`），**在 `applySessionStatus` 写新状态之前**快照旧状态：
```
val oldBusy = statusesSnapshot[sessionId]?.isBusy == true
val nowIdle = statusEvent.status.isIdle
val session = sessionsById[sessionId]   // 覆盖 sessions+directorySessions+childSessions 去重
val isKnownRoot = session != null && session.parentId == null
if (oldBusy && nowIdle && isKnownRoot && sessionId != currentSessionId) {
    markSessionUnread(sessionId)
}
```
- 复用现有 `markSessionUnread`（`L1111-1114`）+ `applyMarkSessionUnread` 纯函数（`L1664-1669`，自带 currentSessionId 短路）。
- 时序安全：SSE 处理在 `Main.immediate` 串行，快照→写入→判定之间无挂起点，无 TOCTOU。
- REST 批量加载走 `mergeStatusSnapshot`（`SessionListActions.kt:359`），不经此分支，不误标。

### 3.2 REST 重连兜底（3.1 的补充）
在 `mergeStatusSnapshot`（`SessionListActions.kt:377-387`）合并前，对「本地 `localBefore[id].isBusy && restSnapshot 不含 id」的 session，推断为完成候选，按 root/非当前规则调用 markSessionUnread。**首次加载（`localBefore` 全空）不触发**。

### 3.3 删除 ping-pong 重标记 + tempClearedUnread
- `SessionSwitcher.kt:226-230`：删除 `previousWasBusyAndCleared`（保留 `previousSessionId`，cache capture 仍用它）。
- `SessionSwitcher.kt:391-408` Step 8：改为单纯 `unreadSessions - sessionId` + 写 `lastViewedTime`，不再重标 outgoing。
- `SessionSyncCoordinator.kt:524-536`：删除 idle 时 `dropTempCleared` 分支。
- `SessionSyncCoordinator.kt:1671-1676`：删除 `dropTempCleared` 纯函数。
- `AppStateSlices.kt:358-362`：删除 `tempClearedUnread` 字段。
- `AppAction.kt:198-202`：`HostStatePurged` 删除 `tempClearedUnread = emptySet()` 行。
- 清理所有测试 fixture 中的 `tempClearedUnread`。

### 3.4 message.created 分支（保留 + 去未读）
- 保留整个分支（前向兼容 + 时间戳 bump + 当前 session reload）。
- 删除 `L603-607` 的 `markSessionUnread`。
- 更新 KDoc 与 DebugLog：明确「未读唯一由 session lifecycle completion 生产；message.* 不定义未读」。
- `applyMarkSessionUnread` 的 KDoc 从「message.created 专用」改为「lifecycle completion」。

### 3.5 未读清除生命周期补全
| 触发 | 位置 | 行为 |
|---|---|---|
| 打开 session | `SessionSwitcher.switchTo` Step 8 | `unreadSessions - id`（保留） |
| 归档（跨设备 SSE） | `AppAction.SessionArchived → reduce()` | 原子清 subtree unread + question |
| 归档（本地） | `SessionMutationActions.launchSetSessionArchived` | 与 SSE 归档共享同一 subtree 清理语义 |
| 删除 session | `SessionMutationActions.launchDeleteSession` | 成功后清 subtree unread + question |
| 关闭 tab | `SessionViewModel.closeSession` | `unreadSessions - id`（保留） |
| 移除 workdir | `SettingsViewModel.disconnectWorkdir` | 补：清该 workdir 下所有 session 的 unread |
| host 切换（异组） | `AppAction.HostStatePurged` | 清空全部（保留） |

- **subtree 工具升级**：现有 `sessionSubtreeIds`（`SessionMutationActions.kt:169`）是 private 且只覆盖 `sessions`。升级为共享 internal 纯函数，覆盖 `sessions + directorySessions + childSessions` 去重遍历。
- **归档清 question 的语义**：是「从本客户端展示域移除」，**非**「服务端 question 标 resolved」。权威 `loadPendingQuestionsAllWorkdirs` reconcile 若重新返回该 question，需配合 active-session 过滤（归档 session 不在 active 列表）防止 ghost 复发。

### 3.6 子 agent question 聚合到 root（UI）
- **共享 tree/root 推导**：新增 internal 纯函数 `rootIdOf(sessionId, sessionsById)` / `treeIds(rootId, sessionsById)`，集中维护，避免 tab/Picker/Sessions/AppShell/ChatScaffold 各自实现。
- **顶部 tab `?`**（`ChatSessionTabStrip.kt:301-312`）：按 root 聚合——root 自身或任意后代有 pending question 即显示。
- **Sessions / Picker**（`SessionsScreen` / `SessionPickerSheet.kt:198-220`）：root 行显示后代 question。`SessionsScreen` 已有 `rootHasPending` 可复用对齐。
- **底部 badge**（`crossSessionPendingCount`，`ViewModelSupport.kt:247-252`）：
  - **question 改 tree-aware**：统计不属于「当前 root 子树」的 question。
  - **permission 保持精确 session 规则**（不套用 tree 聚合，避免隐藏子 agent 权限请求）。
- **QuestionCardView**（`ChatScaffold.kt:242-245`）：从「`sessionId == currentSessionId`」改为「当前 root tree 范围」的 question 队列；保留真实 `QuestionRequest.sessionId/id` 用于回答（不重写成 root id）。当前 session 可能是 child，需先向上解析 root 再取整棵树。多 question 需稳定队列顺序。
- 派生逻辑放 ViewModel/domain 层共享投影，UI `remember/derivedStateOf` 消费；不写回权威状态。

---

## 4. 不在范围（YAGNI）

- **持久化未读状态**：接受冷重启丢失窗口（用户决策 2.1），不持久化 `unreadSessions` / `lastViewedTime`。
- **`lastViewedTime` 作语义兜底**：保持「写」以备未来，但不读取、不作为未读判定依据（`time.updated > lastViewedTime` 不能证明 busy→idle）。
- **`message.part.*` 标未读**：流式续写（message 已创建后用户离开期间新增 token）不提示，接受。
- **`session.deleted` SSE handler**：仓库无此事件，删除清理走本地成功回调；不臆造事件。
- **冷启动精确恢复离线完成的 run**：无服务端事件历史，接受丢失。

---

## 5. 关键风险与应对

| 风险 | 应对 |
|---|---|
| 未知 session 被误判为 root（`parentId==null` 在不存在时也为 true） | 判定改为「已知且 parentId==null」；未知 session 不标（保守，宁可漏） |
| server 某些完成路径不发 idle SSE | 接受丢失（用户决策）；3.2 重连兜底覆盖在线断线场景 |
| 归档两路径清理不一致 | 3.5 统一两路径共享 subtree 清理语义 |
| tab 显示 `?` 但 root 打开后无 QuestionCard | 3.6 QuestionCard 同步改 tree 范围 |
| 归档 question 被 reconcile 重新加回 | active-session 过滤防 ghost 复发 |
| 多 surface 聚合语义漂移 | 3.6 集中共享 tree 推导纯函数 |

---

## 6. 验证

- `./scripts/check.sh --full`（compile + 单测 + lint + 覆盖率）必过。
- 测试矩阵（详见 plan）：
  - session.status 转换矩阵（unknown→idle / idle→idle / busy→busy / root busy→idle 非当前→标 / root busy→idle 当前→不标 / child busy→idle→不标）
  - metadata 时序（busy→metadata(root)→idle 标；busy→metadata(child)→idle 不标）
  - REST 重连（首次快照不批量标；已知 busy+REST 缺失→补标 root 非当前）
  - question 聚合（root 自身 / 多层 child / 当前 tree 内 QuestionCard / tree 外 badge 计数 / permission 不受影响）
  - 生命周期清理（归档本地/SSE 两路径清 subtree；删除清；disconnectWorkdir 清；host purge 清）
  - message.created（非当前不标未读；当前仍 reload；时间戳 bump 单调）

---

## 7. 执行约束

- **一次原子 commit**（用户指令）：新 producer 启用 + 旧 producer/重标记删除 + 生命周期清理 + UI 聚合 + 测试 全部在同一 commit。开发期可分步改，提交不拆。
- **git 基线**：`b1f66d79c41f58e28b95b51e483bd6cd4ae0f985`（`main`）。
- **不 commit** 除非用户显式要求（ocmar 纪律 + AGENTS.md）。
