# OpenCode Android Client 设计 Spec — 冷静科技感（Quiet Tech）

本文件是 OpenCode Android Client 的视觉设计语言规格。它和 iOS client 的 `docs/design.md` 是**同一套设计语言的两个平台实例**——Android 严格对齐 iOS 的 Quiet Tech，差异只在平台实现手段（Jetpack Compose / Material 3 vs SwiftUI），不在视觉决策。

方向一句话：像 Raycast、Vercel、Arc 那类现代开发者工具——深色为主、低饱和、靠精准间距和细描边而非装饰显贵。不喧哗，"贵"都来自克制、一致和恰到好处的留白。

## 设计原则

1. **深色是主场，浅色是平替。** 开发者默认深色审美。深色优先调到完美，浅色用同一套 token 映射。
2. **单一识别色：电蓝 `#3B82F6`。** 整个 app 只有一个色相承担"可交互/品牌"语义——发送、选中、链接、用户消息色条、可点的工具卡元素。金色 `#D9A621` 仅作唯一次级强调，只用在"AI 正在工作"瞬时态。除这两色外全部走中性灰阶；Material 的 `error`/绿/橙一律不作装饰用（红只保留给 stop / 删除等破坏性语义）。
3. **关掉 Material You 动态取色。** `OpenCodeTheme` 不使用 `dynamicColor`——品牌色在所有设备上固定为电蓝，不跟随用户壁纸，和 iOS"固定 primary blue、不跟系统 accent"的决策一致。
4. **语义靠形态和位置，不靠颜色堆叠。** 用**卡片形态**区分功能类别，颜色只在极少数可交互处点缀。
5. **颜色不建立层级，字号和留白建立层级。**
6. **无边框优先。** 信息类容器去描边、只留极淡底色；只有"请你操作"的卡片才用一条左侧色条作功能信号。
7. **稳定按钮不换位。** 用户会靠肌肉记忆点击 composer 的发送和麦克风。发送按钮和麦克风按钮永远占据各自竖排的底部槽位；stop / retry 这类临时按钮只能出现在它们上方，不能把主按钮顶到别的位置。

## 色板与 Material 映射

Quiet Tech 的 token 定义在 `ui/theme/Color.kt`，并在 `ui/theme/Theme.kt` 里映射到 Material 3 的 `ColorScheme`，这样 View 层优先用 `MaterialTheme.colorScheme.*`，深浅两套自动切换。

### 深色模式（主场）

| 用途 | Color.kt token | Hex | Material colorScheme 槽位 |
|---|---|---|---|
| 主背景（近黑冷调） | `BgDark` | `#0B0C0E` | `background` / `surface` |
| 信息卡 / surface 底 | `SurfaceDark` | `#1A1D21` | `surfaceVariant` / `surfaceContainer*` |
| composer 输入底 | `ComposerDark` | `#141619` | `surfaceContainerLow` |
| 主文字 | `OnSurfaceDark` | `#E6E8EB` | `onSurface` / `onBackground` |
| 次级文字（时间戳、状态、tool 名） | `OnSurfaceVariantDark` | `#9BA1A8` | `onSurfaceVariant` |
| 极细分隔线 | `OutlineDark` | `#2A2E33` | `outline` / `outlineVariant` |
| 唯一识别色 | `BrandPrimary` | `#3B82F6` | `primary` / `secondary` |
| AI 工作中 | `BrandGold` | `#D9A621` | `tertiary` |

### 浅色模式（平替）

| 用途 | token | Hex |
|---|---|---|
| 主背景 | `BgLight` | `#FFFFFF` |
| 信息卡 / surface 底 | `SurfaceLight` | `#F0F1F3` |
| composer 输入底 | `ComposerLight` | `#F4F5F6` |
| 主文字 | `OnSurfaceLight` | `#15171A` |
| 次级文字 | `OnSurfaceVariantLight` | `#60656B` |
| 分隔线 | `OutlineLight` | `#E2E4E7` |
| 识别色（与深色同值，鲜艳电蓝） | `BrandPrimaryLight` | `#3B82F6` |

> `ToolWritePatchBackground` / `...Dark` 是历史上 write/patch 卡的特殊蓝底，Quiet Tech 下工具卡统一用中性 surface，这两个色已不再被 UI 引用，保留只为向后兼容，后续可清理。

整屏在任何时刻最多出现一处彩色（accent 或 gold），其余全灰阶——这是"冷静"的硬约束。

## 卡片三态语言

按功能分三类形态，这是 Quiet Tech 的核心，取代"多色同形的彩虹卡片"：

1. **信息卡片**（`ToolCard` / `PatchCard` / `ReasoningCard`）：中性 `surfaceVariant` 底，12dp 圆角，无重描边。**但它们是可交互的**（点开看 input/output、跳文件预览），所以卡片**内部的可操作元素**——工具图标、工具名、可跳转的文件路径、展开 chevron、OpenInNew 图标——用 `colorScheme.primary` 电蓝着色作为"可点"暗示；卡身保持中性。纯展示文字（tool reason、output 预览）走 `onSurfaceVariant` 灰。**不要把整张卡灰掉**——那样读起来像禁用控件（iOS 上踩过这个坑）。
2. **操作卡片**（`ChatPermissionCard` / question card）：中性 surface + 左侧一条 3dp 电蓝色条（`colorScheme.primary`）作"请你操作"的功能信号，配纯文字按钮（TextButton）——Allow 类电蓝、Reject 灰，不用绿/蓝/红实底按钮。
3. **状态行**（turn activity、elapsed 计时）：不是卡片，纯 `onSurfaceVariant` 文字。

圆角统一：信息/操作卡片 12dp，sheet 16dp，inline tag 6dp。

## 消息区

- **用户消息**（`TextPart` when `isUser`）：3dp 电蓝左色条 + muted 蓝底（`primary.copy(alpha = 0.10f)`），12dp 圆角。和操作卡片、选中行同构——"左色条"语言贯穿全 app。
- **AI 回复**：无容器，全宽纯文本 / markdown。

工具/patch 卡片在手机上走 `run.chunked(2)` **两列网格**（信息密度优先；与 iPhone 一致，不改单列）。

## Composer（`ChatInputBar`）

- F3 后与 iOS 一致采用 **voice rail + text review field** 两行结构，而不是把 mic 塞在单个输入 pill 内。语音是手机 steer 的主输入模态；文本框承担转写审阅、轻量修正和 fallback typing。
- **Voice rail** 位于文本框上方：左侧 transport、中央 waveform/status、右侧轻量恢复动作。录音中 waveform 消费 `VoiceFlowMicrophone.audioLevel` 的真实 0..1 smoothed mic level；转写和 retry 中显示 generating waveform。
- 左侧 transport：空闲为 `Tap to speak` mic；录音中变 stop，点击是正常结束采集并进入转写；preserved-audio 状态变 `Retry this segment`，点击重新识别同一段已保存 PCM。转写中 transport disabled，避免把等待恢复误读为重新录音。
- 右侧轻量动作：转写等待显示 `Stop transcription wait`，调用 `abortPreservingAudio()` 并保留音频；preserved-audio 状态显示 `Discard audio`，用于放弃缓存并退出恢复状态。retry 是唯一主恢复动作，discard 是退出动作，不能出现两个 retry 入口。
- **Text review field**：下方单个圆角 pill，底色 `surfaceVariant`，无边框 `BasicTextField`，`heightIn(min = 66.dp, max = 132.dp)`。语音转写和 retry partial transcript 写入文本框，用户可审阅和修正；普通打字保持系统默认编辑行为。
- **send 始终存在**：实底电蓝 36dp 圆角方块固定在 text review field 右侧。session busy 时仍可发送，因为服务端 `prompt_async` 支持排队；speech transcribing/retrying 时禁用，避免发送半成品转写。
- **agent interrupt 降权**：agent running 用 composer 附近 quiet status row 表达，例如 `Agent running · Transcribing`。`Interrupt agent` 放入 `⋯` overflow menu，作为低频 escape hatch，不再占据主输入区红色 stop 按钮，也不和语音 stop-wait 共用同一 glyph。

## Toolbar（`ChatTopBar`）

- **模型选择器**：从实底蓝胶囊改为**描边 chip**——透明底 + 1dp 电蓝边（primary @30% alpha）+ 电蓝文字。蓝作 accent 不作大块填充。
- 其余图标按钮（session 列表、rename、settings）：朴素 glyph，`onSurfaceVariant` 灰；突出的"新建"动作可用电蓝。去掉图标背后的填充圆。
- **Context ring**：track 用 `onSurfaceVariant` 低 alpha，进度用电蓝（不是紫），高占用阈值才切金/红（保留原阈值逻辑）。

## Session 列表（`SessionList` / `SwipeRevealRow`）

- Session List 按 **Active / Archived** 两个分区展示。Active 是当前工作集；Archived 是同屏折叠抽屉，不跳到 Settings 或另一个页面。
- **选中行**：3dp 电蓝左色条 baked 进 12dp 圆角的选中底（`primary.copy(alpha = 0.08f)`），整体 clip 在圆角内——色条不会戳出圆角、不与展开 chevron / 缩进冲突。
- 未选中行透明（去掉交替条纹底，取更干净的 Quiet Tech 观感）。
- Active 行 leading swipe 为 Archive；Archived 行 leading swipe 为 Restore；trailing swipe 始终为 Delete。所有 action 使用图标在上、文字在下的克制按钮语言。
- Archived rows 仍可点击查看，但标题和状态使用弱一级中性色，不画当前选中 accent，避免读起来像当前工作项。
- Session 分页使用显式 `Load more sessions` 行，不再用底部自动 sentinel；这样 Archived 默认折叠时不会为了不可见历史自动连续分页。
- 保留：展开折叠、depth 缩进、busy 标题色、状态文字。

## Settings（`SettingsSections`）

- **Theme**（LIGHT / DARK / SYSTEM）：分段控件（`SingleChoiceSegmentedButtonRow`），选中段电蓝。
- Switch / toggle accent 用 `colorScheme.primary` 电蓝。

## 不做清单

- 不重新设计信息架构（手机 = Chat/Files/Settings 底部 tab；平板 = 三栏）。
- 不引入语法高亮（代码保持纯文本——和现状一致）。
- 不做 diff viewer / session 摘要预览（功能现状没有，设计不画）。
- 不引入 Material You 动态取色。

## 与 iOS 的对齐说明

本 spec 的每条视觉决策都来自 iOS client 已落地并验证的实现（见 iOS `docs/design.md`）。Android 侧通过 Material 3 `ColorScheme` 映射达到同样的观感；平台手段不同，视觉语言一致。两个 app 因此有家族感。

---

# 工具卡渲染重做（已实现 — 对齐 iOS）

这一节就在上面 Quiet Tech 语言内，不引入任何新视觉质感——保持现有干净图标、细分隔、中性卡身。要改的是**信息组织方式**，不是皮肤：谁在说话怎么区分、工具结果怎么呈现。落点全在 `ui/chat/ChatMessageContent.kt`，分类逻辑抽到 `ui/chat/ToolCardClassifier.kt`（纯逻辑、可单测）。

之前的现状问题：(a) AI 回复和用户消息区分太弱，看不出谁在说话；(b) 所有工具一律走"通用展开式 ToolCard + 扳手图标"，patch 单独做成导航卡，工具卡彼此没有语义区分，读起来是一堆同质灰卡。

四个具体改动：(1) 说话区分；(2) 文件操作渲染成 2 列文件卡网格；(3) 其余工具合并成可展开的 "N tool calls" 行；(4) 文件夹读取展开成内容卡。

## 一、说话区分（不要头像，但要标题）

- **用户消息**：保留现有的**蓝色左竖条**（3dp accent 左 bar + `primary.copy(alpha=0.10f)` muted 底，12dp 圆角），由 `TextPart(isUser=true)` 渲染。
- **OpenCode 回复**：**不要头像/圆形图标**，在回复顶部加一个 **"OpenCode" 文字标题**（`labelMedium` + SemiBold + `primary` 电蓝，`testTag("assistant.header")`），让人一眼知道这是 AI 在说话；并**保留现有的"模型小字"**——每条 assistant 回复末尾那一行 `providerId/modelId` 的小灰字，位置和现状一样、不动。回复正文无容器、无左 bar。
- iOS 在 user 和 assistant 两侧末尾都放模型小字；Android 现状只 assistant 末尾有——本次保持 assistant 有即可，user 那行（fork 菜单已带 model 小字）不动。

核心是用**不同的 visual style**（标题 + 模型小字 vs 蓝左竖条）把两边分开，不靠头像。

## 二、文件卡（2 列网格）

**文件操作工具**渲染成新的 `FileCard` composable：左侧 `Icons.Default.Description`（doc 图标，电蓝 accent），中间该文件的 monospace basename，右侧 `ChevronRight`。一个工具一张卡，按 **2 列网格**排列——Android **不能**在 `LazyColumn` 里嵌 `LazyVGrid`，所以沿用现有的 `chunked(2) + Row` 手动两列手法（与 iPhone 2-up 一致）。

判断依据封装在 `ToolCardClassifier.isFileOperation(part)`：patch（**Android 特有要求**：须带可导航文件路径 `filePathsForNavigationFiltered.isNotEmpty()`，否则无路径的 patch 会掉出网格落到无处），或 `tool ∈ {apply_patch, edit_file, write_file, read_file}`（含 `patch/edit/write/read` 历史别名，lowercase 前缀匹配）。

basename/displayPath 优先级照 iOS：`metadata.path` → `state.pathFromInput` → `filePathsForNavigation.first`，取最后一段。点击复用现有打开文件逻辑（`onFileClick`）。`testTag("toolcard.file.<basename>")`。

## 三、合并成 "N tool calls"

**其余所有工具（bash / 测试 / grep / glob / list / webfetch / task …）合并成一行** `ToolCallsRow`，文案 **"N tool calls"**（N = `otherParts.size`）——刻意抽象、不暴露具体类型。点 chevron 展开后逐条复用 `ToolCard` 的展开主体（tool 名 + reason + input/output）。收起是默认态。`testTag("toolcard.toolcalls")`。

一个 run 产出"文件卡网格（若有 fileParts）+ 一个 N tool calls 行（若有 otherParts）"。分类用 `ToolCardClassifier.split(run) -> Pair<fileParts, otherParts>`。排列是**版式优先的近时间序**：相邻文件卡聚成网格，相邻非文件工具聚成一行，不分组、不加分区标题。

## 四、文件夹卡

当 `ToolCardClassifier.isDirectoryRead(part)`（服务端在 read 输出里嵌 `<type>directory</type>`）时，`FileCard` 切换成 folder 分支：图标改 `Icons.Default.Folder`（电蓝），点击弹一个 `ModalBottomSheet` 列出 `parseDirectoryEntries(part.toolOutput)` 的 entries（子目录排前，folder/file 图标区分）。**不调 API**——output 里已有 `<entries>…</entries>` 内容，直接解析渲染（在文件预览里打开文件夹本来就打不开）。`testTag("toolcard.folder.<basename>"、"toolcard.folder.entry.<name>"、"toolcard.folder.sheet.<basename>")`。

## todo 抽离

`todowrite` 工具展开时**只显示 todo、隐藏 input/output**（对齐 iOS）。todo 渲染抽成独立的 `TodoListInline` composable（对齐 iOS `TodoListInlineView`），其余工具若也带 todos 同样复用它。

## 不做 / 边界

- 不引入像素风、不分组、不加分区标题。
- 文件卡保留 doc 图标、2 列网格；AI 回复不要头像但要 "OpenCode" 标题；模型小字保留不动。
- 模型选择器不动，不要右上角用户头像。
- 两形态都用现有 Quiet Tech 中性卡身（`surfaceVariant` 底、无描边、12dp 圆角），彩色只落在图标和 chevron 上——全屏至多一处蓝。
