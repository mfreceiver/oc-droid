# UI 卡片优化方案

> 日期：2026-06-28
> 基于当前 ChatMessageContent.kt（1812 行，单文件卡片渲染中心）和 opencode-web v1.17.11（oc-ref/packages/session-ui）的对比分析。

---

## 背景目标

1. **统一视觉风格**：所有工具卡片向 Shell 工具卡的紧凑样式对齐——透明无填充、窄行高（≈28dp）、宽度上限一致。
2. **补齐缺失元素**：Explored/思考虑加前导 icon，所有卡片统一使用裸 Icon 替代 IconButton（去掉 Material3 默认 48dp 触摸区撑高）。
3. **隐藏系统消息**：对齐 web——非 user/assistant role 的消息不渲染（或降级为极简提示条）。
4. **对齐 web subagent 呈现**：参考 web 的 per-agent-tone 着色和纯芯片样式。

---

## A. 卡片视觉修正（3 项用户点名）

### A1. Explored（ContextToolGroup，行 1710）

| 项目 | 现状 | 方案 |
|---|---|---|
| icon | 无 | 行首加 `Icons.Default.TravelExplore`（14dp，onSurfaceVariant），置于 spinner / 文本之前 |
| 高度 | 好（`vertical=4dp`，裸 Icon） | 不动 |
| 宽度 | `280.dp` | 不动 |

备选 icon：`ManageSearch` / `Search`。与 web 的 `magnifying-glass-menu` 语义一致。

### A2. 思考（ReasoningCard，行 865）

| 项目 | 现状 | 方案 |
|---|---|---|
| icon | 无 | 行首加 `Icons.Default.Psychology`（14dp，onSurfaceVariant） |
| 高度 | **偏高**（约 48dp） | 展开箭头 `IconButton{Icon}` → 裸 `Icon`（与 Shell 一致，行高降至 ~28dp） |
| 宽度 | `280.dp` | 单独加窄：`Modifier.widthIn(max = 220.dp)`，因为思考内容比工具更辅助性 |

备选 icon：`Lightbulb` / `AutoAwesome`。

**高度根因**：`IconButton` 默认最小 48dp 触摸目标（Material3 规范），即便 icn 只设了 `size(20.dp)`，整行被撑到 48dp 以上。Shell/BasicTool 和 Explored/ContextToolGroup 用裸 `Icon`（行 1621、1766），行高仅 28dp 左右。此改动将 ReasoningCard 行高降至与 Shell 一致。

### A3. 文件编辑（PatchCard，行 1404）

| 项目 | 现状 | 方案 |
|---|---|---|
| icon | 已有 `Icons.Default.Edit`（14dp） | 保留 |
| 高度 | **偏高**（约 48dp） | 表头展开箭头 `IconButton{Icon}`（行 1489）→ 裸 `Icon` |
| 宽度 | `280.dp` | 不动 |

**注意**：展开区域中的 `OpenInNew` IconButton（行 1513）是展开 body 内的操作按钮，不撑高折叠态行高，无需改动。同理多层展开 `MultiFilePatchAccordion` 不含 IconButton 头，不动。

---

## B. 内联错误卡（新增）

| 项目 | 说明 |
|---|---|
| 现状 | `MessageError`（Message.kt:60-70）完全不渲染；错误只在 `ChatScreen.kt:200-211` 的 Snackbar 闪现 |
| 方案 | 在 `MessageRow`（~230）中检测 `message.info.error`，插一张危险色卡片 |
| 样式 | 与 Shell 同尺寸透明外壳，但 `border = BorderStroke(1.dp, oc.stateDangerFg)` |
| 内容 | `Icons.Default.ErrorOutline`（14dp，dangerFg） + 错误文本（`labelSmall`）+ 可复制 |
| 对齐 Web | web 使用 `<Card variant="error">` + `circle-ban-sign` icon + tool error title + 复制的 error body（`tool-error-card.tsx:21`） |

接入位置：`MessageRow` 返回时在 `Column` 末尾追加：
```kotlin
message.info.error?.let { err ->
    ErrorCard(text = err, modifier = Modifier.widthIn(max = MAX_CARD_WIDTH))
}
```

---

## C. 隐藏系统消息（C1 完全隐藏）

### 泄漏路径

服务端可能通过两种方式下推 subagent 结束后的整段 transcript 到 Android：
1. **一条 `role="system"` 的顶层消息**——Android 没有任何 role 过滤（`Message.kt:72-73` 只有 `isUser`/`isAssistant` 访问器），被当成助手气泡满宽 markdown 渲染。**最可能路径。**
2. **一条 `type:"text"` part 注入到父消息中**——经 `PartView`（行 411）→ `TextPart`（行 759）满宽渲染。

### 方案

**主修复（针对路径 1）：** `role` 过滤
- `Message.kt` 加 `isSystem` / `isToolRole` 等访问器。
- `visibleMessages`（`MainViewModel.kt:264-268`）在返回前过滤掉 `role != "user" && role != "assistant"` 的消息。

**兜底（针对路径 2）：** 检测 injected transcript
- `PartView`（行 411 `part.isText` 分支）中加判断：若文本含 `<task_result>` XML 标记（`parseTaskXml` 已在行 1207 识别同类标记），则跳过渲染，不交给 `TextPart`。
- 也可直接过滤含 `<task_result>` 开头的 `part.text` 内容。

**效果**：对齐 web。web 的 `message-part.tsx` 只在 `PART_MAPPING` 注册 `text`/`reasoning`/`compaction`/`tool`，`SessionMessageSystem` 等非 user/assistant 消息被丢弃。

---

## D. SubAgentCard 对齐 web（学习其呈现）

### 现状 vs web 对比

| 维度 | Web（message-part.tsx:1797） | Android 现状（行 997-1109） |
|---|---|---|
| 外壳 | 同一 BasicTool 透明壳，与其他工具一致 | **独自用** `oc.layer02` 填充背景，padding `12/8`，字号 `labelMedium`——比其他卡片高/重/大一截 |
| 前导 icon | `task` icon | 无（有 spinner/error 指示器，无工具图标） |
| agent 名颜色 | **按 agent 类型上色**：`agentTones`（ask/build/docs/plan 各定色），其余按名字 hash→12 色调色板 | 全部 `accentText` 纯色 |
| spinner 颜色 | 同 agent tone 色 | `onSurfaceVariant` 默认 |
| 副标题 | description（后台加 "(background)"） | 同 |
| 打开子会话 | `square-arrow-top-right` icon | 已实现（行 1089-1094），用 `ChevronRight` |
| hideDetails | `hideDetails=true`，纯芯片 | 已实现（无展开 body），但有额外 "waiting for sub-agent…" 副行（行 1099-1106） |

### 方案

**D1. 外壳一致化**（主要高度差来源）
- 背景色：`Color.Transparent`（去掉 `oc.layer02`），border 用 `oc.borderBase`
- padding：`horizontal = 8.dp, vertical = 4.dp`（现为 12/8）
- 字号：`labelSmall`（现为 `labelMedium`）
- 外层 `padding(vertical = 1.dp)`（现为 2dp）

**D2. 加前导 icon**
- `Icons.Default.AccountTree`（类比 web 的 `task` icon，表示任务/子 agent）
- 备选：`SmartToy` / `Workspaces` / `Assignment`。需确认。

**D3. 移植 per-agent tone 着色**
参考 web 的 `agentTones` + `tone()` 颜色散列函数（`message-part.tsx:320-346`）：

```kotlin
// Kotlin 移植
private val agentTones = mapOf(
    "ask" to // 某个强调色,
    "build" to // 另一个色,
    "docs" to // 第三个色,
    "plan" to // 第四个色,
)

private val colorPalette = listOf(
    // 8-12 个 distinct 颜色
)

private fun agentTone(agentName: String): Color {
    val lower = agentName.lowercase()
    agentTones[lower]?.let { return it }
    val hash = lower.fold(0) { acc, c -> acc * 31 + c.code } ushr 0
    return colorPalette[hash % colorPalette.size]
}
```

- 给 `@name` Text（行 1056）和 `CircularProgressIndicator`（行 1041）用 `agentTone` 着色。
- 若无 agent 类型信息则沿用 `accentText` 作为 fallback。

**D4. 整理右上角操作 icon**
- 保持右侧 `ChevronRight`（或替换为 `OpenInNew`，对齐 web 的 `square-arrow-top-right`）。
- 与 Shell 卡片的展开箭头 `ChevronRight` 一致即可。

**D5. 去掉 "waiting for sub-agent…" 副行**（行 1099-1106）
- web 无此逻辑；等待状态只表现为 spinner。
- 若需要可保留但改为和 subtitle 同色的 `labelSmall`。

---

## 影响范围

| 范围 | 文件 | 改动规模 | 操作 |
|---|---|---|---|
| A + B + D 渲染 | `app/.../ui/chat/ChatMessageContent.kt` | ~200 行 diff（全部在卡片 composable 内） | @designer |
| C 数据过滤 | `app/.../data/model/Message.kt` + | ~40 行（加 2 个 accessor + 1 处过滤） | @fixer |
| | `app/.../ui/MainViewModel.kt` | ~10 行（`visibleMessages` 加 filter） | |
| | `app/.../ui/chat/ChatMessageContent.kt`（PartView） | ~10 行（加 transcript 检测） | |

**依赖关系**：A / B / D 与 C 无写作用域重叠，可并行执行。

---

## 实施顺序

```
┌─────────────────────────────────────────────┐
│  Step 0: 确认本方 + "开始"                      │
├─────────────────────────────────────────────┤
│  Step 1 (并行)                                │
│  @fixer: C 数据过滤（Message.kt + ViewModel）    │
│  @designer: A + B + D 渲染（ChatMessageContent） │
├─────────────────────────────────────────────┤
│  Step 2: ./gradlew assembleDebug 验证编译     │
├─────────────────────────────────────────────┤
│  Step 3: 观察运行效果，调整icon/色值细节         │
└─────────────────────────────────────────────┘
```

---

## 确认清单（待用户回复"开始"）

- [x] A1 Explored icon: `TravelExplore`（备选 `ManageSearch` / `Search`）——待最终确认
- [x] A2 思考 icon: `Psychology`（备选 `Lightbulb` / `AutoAwesome`）——待最终确认
- [x] A3 文件编辑：仅换箭头 `IconButton`→裸 `Icon`（已有 Edit icon 保留）
- [x] B 内联错误卡：danger border + `ErrorOutline` + 错误文本 + 可复制
- [x] C1 系统消息完全隐藏（role 过滤 + transcript 标记兜底）
- [x] D1 SubAgentCard 外壳一致化（透明 + 8/4 + labelSmall）
- [x] D2 SubAgentCard 前导 icon: `AccountTree`（备选 `SmartToy` / `Workspaces`）——待最终确认
- [x] D3 tone 着色：移植 web agentTones + hash palette——可选（最费工，可降级为单色）
- [x] D4 操作 icon 保持 `ChevronRight` 或改 `OpenInNew`——确认
- [x] D5 去掉 "waiting for sub-agent…" 副行——确认
