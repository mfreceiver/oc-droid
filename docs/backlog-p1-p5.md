# Backlog: P1–P5 (2026-06-28)

> 优先级排序：流量/稳定性 > 开发效率 > 体验完善

---

## P1 🔴 卡片视觉修正

**方案**: `docs/ui-card-optimization-plan.md`（199 行完整 spec）

**范围**:
- A1: Explored 加前导 icon `TravelExplore`（备选 `ManageSearch`/`Search`）
- A2: ReasoningCard `IconButton`→裸 `Icon`（降行高 48→28dp），加 `Psychology` icon（备选 `Lightbulb`/`AutoAwesome`）
- A3: PatchCard 表头 `IconButton`→裸 `Icon`（降行高，Edit icon 保留）
- B: 新增内联错误卡（danger border + `ErrorOutline` + 文本可复制）
- C: 隐藏系统消息（role 过滤 + transcript 标记兜底）
- D1-D5: SubAgentCard 外壳一致化（透明 + 8/4 padding + labelSmall）+ 前导 `AccountTree` + tone 着色 + 去 "waiting" 副行

**影响**: `ChatMessageContent.kt`(~200行) + `Message.kt` + `MainViewModel.kt`

**待确认**: 3 个 icon 终选（Explored/Reasoning/SubAgent）

---

## P2 🔴 协议类型代码生成

**目标**: 从 opencode-server `openapi.json` 自动生成 Kotlin `@Serializable` 类

**现状问题**: `Part` 序列化使用手工 `PartStateSerializer`（180 行），与 web 的 Part interface 已产生字段漂移（缺 `toolReason`/`toolInput`/`toolOutput`/`status` 等）。每次 server 协议升级需手工同步。

**方案**:
1. 定位 opencode-server 的 OpenAPI spec（`openapi.json` 或 Swagger endpoint）
2. 选 Kotlin codegen 工具（`openapi-generator` kotlin 模板 或 `kotlinx-serialization` + 自定义生成器）
3. 生成 `Message`, `Part`, `Session`, `SSEEvent` 等类型的 `@Serializable` data class
4. 替换现有的手工序列化代码
5. 加 CI 脚本：`server openapi.json` → Kotlin types，检测漂移

**ROI**: 最高——消灭与 web 的最大漂移源，省掉每次 protocol upgrade 的手工同步

---

## P3 🟡 模块化 ToolRegistry 重构

**目标**: `ChatMessageContent.kt` 1812 行 → ~12 个独立 composable 文件

**拆分目标**:
| 当前（单文件） | 目标（独立文件） |
|---|---|
| `ToolCard` + 展开 body | `ui/chat/cards/ToolCard.kt` |
| `PatchCard` + `MultiFilePatchAccordion` | `ui/chat/cards/PatchCard.kt` |
| `ReasoningCard` | `ui/chat/cards/ReasoningCard.kt` |
| `SubAgentCard` | `ui/chat/cards/SubAgentCard.kt` |
| `ErrorCard`（新增） | `ui/chat/cards/ErrorCard.kt` |
| `FileCard` + 2 列网格 | `ui/chat/cards/FileCard.kt` |
| `ToolCallsRow` | `ui/chat/cards/ToolCallsRow.kt` |
| `TextPart` + `UserTextPart` | `ui/chat/cards/TextPart.kt` |
| `QuestionCardView` | `ui/chat/cards/QuestionCard.kt` |
| `ChatPermissionCard` | `ui/chat/cards/PermissionCard.kt` |
| `MessageRow` + `MessageList` 入口 | `ui/chat/MessageList.kt` |
| 分类逻辑 | `ToolCardClassifier.kt`（已独立 ✅） |

**收益**: 编译时间缩短、IDE 导航直达、并行开发无冲突。不改行为。

---

## P4 🟡 SVG icon 批量转 VectorDrawable

**目标**: 106 个 SVG icon → Android `VectorDrawable`

**方案**:
1. 从 opencode-web 提取 SVG icon 清单（`oc-ref/packages/session-ui/icons/`）
2. 脚本批量转换：`vd-tool` 或 Android Studio Vector Asset Import CLI
3. 输出到 `app/src/main/res/drawable/`
4. 替换现有 Compose Icon 引用为 `painterResource(R.drawable.xxx)`

**收益**: 统一 icon 语言（与 web 完全一致的图形），消除 Material Icons 依赖的语义偏差

---

## P5 🟢 i18n + tone + 完善

**分项**:
- **i18n 补齐**: `strings.xml` 缺漏项（中英双语），特别是错误消息、tunnel 状态
- **agent tone 着色**: 移植 web `agentTones` + `tone()` hash 函数（`ui-card-optimization-plan.md` §D3）
- **错误卡完善**: `MessageError` 卡片渲染（`ui-card-optimization-plan.md` §B，P1 已含）
- **Composer 微调**: 发送按钮稳定 + stop 降权

---

## 依赖关系

```
P1 (卡片视觉)  ─── 独立，不依赖其他
P2 (协议生成)  ─── 独立，但影响 P1 的 Part 字段可用性
P3 (模块拆分)  ─── 建议在 P1 之后做（避免合并冲突）
P4 (icon 转换)  ─── 独立，可脚本化
P5 (i18n+完善)  ─── 独立小块
```

## 建议执行顺序

```
P2 → P1 → P3 → P4 → P5
 ↑     ↑
 │     └─ 卡片修正（P2 完成后 Part 字段齐全，错误卡可直接用 MessageError.data）
 └─────── 协议类型（最高 ROI，先做减少后续漂移）
```
