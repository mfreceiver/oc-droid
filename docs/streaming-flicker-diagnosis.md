# 流式输出全屏闪烁 — 诊断与修复方案

> 状态：根因锁定，待确认实验 + 正式修复。
> 调研日期：2026-07。行号引用基于 v0.6.3，实施前需 diff 确认。

---

## 1. 现象

流式输出期间，全屏文字周期性闪烁：约 **0.99s 正常显示 + 0.01s 整屏"空无一物"**（瞬间空白），周期性出现。

## 2. 调研结果：根因

### 2.1 流式渲染链路（极简）

```
SSE "message.part.updated"
  → SessionSyncCoordinator.handleEvent                      (SessionSyncCoordinator.kt:652)
  → deltaBuffer/fullTextBuffer 100ms 合并窗口 (DELTA_COALESCE_MS=100)  (:1134)
  → flushCoalesceBufferForPart → ChatState.streamingPartTexts          (:1364)
  → chatFlow emit → collectAsStateWithLifecycle()                       (ChatMessageContent.kt:88)
  → contentVersion (含 streamingPartTexts.hashCode())                   (ChatMessageContent.kt:214)
  → reversedEntries 重建                                                (ChatMessageContent.kt:470)
  → LazyColumn items(key=message.id)                                   (ChatMessageContent.kt:549)
  → MessageRow → PartView → TextPart                                   (ChatTextParts.kt:150)
```

### 2.2 🥇 Top1 根因（最可能，与时间特征高度吻合）

**占位符 Part 两阶段 mutation 的中间态，导致整条消息被 `filterNot` 瞬时剔除。**

时序竞争在 `SessionSyncCoordinator.kt:692-766`：处理一个新 Part 时，**先**调 `applyPartCreatedPlaceholder`（第一次 `mutateChat`，写入一个 `text=null` 的占位符 Part），**然后**才把首帧 delta 写进 `streamingPartTexts`（第二次 `mutateChat`）。这两次 mutation 之间的瞬间，Compose 快照可能捕获到一个中间态：

- `partsByMessage[msgId]` 已有占位符 Part（`text=null`）
- 但 `streamingPartTexts` 里**还没有**这个 partId

此时 `ChatMessageContent.kt:482-484` 的过滤条件命中：

```kotlin
!msg.isUser && !isStreamingMsg           // partId 不在 streamingPartTexts → false
  && msg.error?.message.isNullOrBlank()
  && isEffectivelyRenderableEmpty(msgParts)  // 占位符 text=null → true
```

→ **整条 assistant 消息被 `filterNot` → LazyColumn 该 item 消失 → 可见区空白一帧**。

**为什么周期性 ~1s**：assistant 每生成一个新 Part（text→tool→text 交替），就触发一次这个两阶段窗口，每 ~1s 闪一次。与"0.99s 正常 / 0.01s 空白"高度吻合。

### 2.3 🥈 Top2 候选

**`contentVersion` LaunchedEffect 每 100ms 重启 → `scrollToItem(0)` 打断 layout。**
`ChatMessageContent.kt:408-445`：每次 flush（100ms）`streamingPartTexts.hashCode()` 变 → `contentVersion` 变 → `LaunchedEffect` 重启 → `scrollToItem(0)`。约 10% 概率正好落在 LazyColumn 慢 layout 中间，scroll 位置瞬跳空白。

### 2.4 🥉 Top3 候选

**TextPart 首帧 blank guard**：`ChatTextParts.kt:172` `if (text.isBlank()) return`——占位符已到但首 token 未到时 TextPart 不渲染，偶发空白帧。

## 3. 处理思路

### 3.1 确认实验（先做，最省时）

在 `ChatMessageContent.kt:482` 拓宽 `isStreamingMsg` 判定，额外包含"`session` 正在运行 且 `msgParts` 里有 text part"（不绑定 `streamingPartTexts`）：

```kotlin
// 伪代码：原 isStreamingMsg = msgParts.any { it.id in streamingPartTexts }
// 拓宽为：session 运行中(msgParts 含 text) 也算 streaming，避免占位符阶段被剔除
val isStreamingMsg = msgParts.any { it.id in streamingPartTexts }
    || (sessionIsRunning && msgParts.any { it.isText })
```

**改完如果闪烁消失 → Top1 根因确凿**，再做正式修复。如果不消失，再查 Top2（在 `LaunchedEffect(contentVersion)` 加 `frameTimeMillis` 日志确认 `scrollToItem(0)` 是否打断 layout）。

### 3.2 正式修复（Top1 确认后）

**两阶段 mutation 原子化**：把 `applyPartCreatedPlaceholder` 和首帧 delta/fullText 写入合并到**同一个** `slices.mutateChat` 调用中——创建占位符的同时就把 `streamingPartTexts[partId]` 初始化（key 存在即可，哪怕值为 `""`），这样 `msgParts.any { it.id in streamingPartTexts }` 始终为 true，filter 不会误删。

```kotlin
// 修改思路：把两次 mutateChat 合并为一次原子 CAS
slices.mutateChat { c ->
    val (withPlaceholder, partId) = c.applyPartCreatedPlaceholder(...)
    withPlaceholder.applyPartDeltaLeadingEdge(partId, firstDelta, ...)
}
```

### 3.3 备选修复（Top2 / Top3）

| 根因 | 修复 |
|---|---|
| Top2 | 流式期间跳过程序式 `scrollToItem(0)`，靠 LazyColumn reverseLayout 天然跟底；或 `contentVersion` 改 `snapshotFlow` + `debounce(50ms)` |
| Top3 | `text.isBlank()` 时不直接 return，渲染最小占位 Box（1dp 透明）防 size 抖动 |

### 3.4 风险提示

此修复触及 `SessionSyncCoordinator` 的核心流式 mutation 逻辑（两阶段合并），**风险偏高**。建议：
- 先做确认实验（最小改动验证根因）。
- 正式修复走 oracle 评审 + 局部验证，不要直接在主线猛改。
- 合并 mutation 时注意保留原有顺序语义（placeholder 必须先于 delta 落盘，只是放进同一个 snapshot）。

## 4. 待办事项

- [ ] **确认实验**：拓宽 `isStreamingMsg`（ChatMessageContent.kt:482），跑流式输出观察闪烁是否消失。
- [ ] 若消失（Top1 确认）→ **正式修复**：原子化 `SessionSyncCoordinator` 的 placeholder + 首帧 delta 两次 mutateChat。
- [ ] 若不消失 → 查 Top2（scrollToItem 打断 layout），加日志定位。
- [ ] 回归验证：长时间流式输出无闪烁；占位符→流式→完成的状态转换无回归。
- [ ] 单测：若有纯函数可抽（如消息是否应被过滤的判定），补 JVM 测试覆盖"占位符阶段不被误删"。

## 5. 验证

- 手动：发一条会触发多 Part（text→tool→text）的 prompt，观察长流式期间是否还有周期性全屏空白。
- 模拟器/真机均可（不涉及后台）。
- 关注：闪烁消失 + 流式文本正常 + 滚动到底行为正常 + 无新引入的布局抖动。
