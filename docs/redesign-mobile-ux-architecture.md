# 移动端 UX 与架构重构报告

> 状态：评审完成，待排期。基于 v0.7.1；行号引用基于当前 main，实施前需 diff 确认。
> 评审日期：2026-07-11。
> 方法：四路并行只读评审 —— oracle（UX/UI 架构）、gpter（代码架构/耦合/状态）、librarian（竞品与 M3 规范研究）、observer（真机视觉，6 屏走查）。竞品对照移动版 ChatGPT / Google Gemini / PocketShell 等移动 SSH 终端，以及 Android Material 3 权威规范。

---

## 0. 执行摘要（一句话诊断）

> **问题不是缺功能，而是把桌面形态的「标签页 + 面板 + 嵌套手风琴 + 隐藏手势 + 对话框」整套搬上了手机，却没有给三层上下文（Host → 工作目录 → 会话）一个稳定、可预测的移动信息层级。** 用户必须记住「每个能力归哪个图标 / 手势 / 对话框 / 屏幕」——这是高位置不确定性的根源。

三个上下文层级当前被**无关的控件**承载：
- Host 切换藏在 DNS 图标 + 对话框里
- 工作目录切换只能通过 Sessions 屏幕间接完成
- 会话切换有**三套互相竞争的机制**（浏览器式标签 + 水平滑动 + 列表）
- 文件是全屏 overlay 而非目的地
- VCS 埋在 Settings 里
- model / agent 选择藏在「上下文用量环」背后
- 会话 diff 钉在对话时间线尾部
- **revert / fork 整条链路已实现，但零 UI 入口**

最高价值的结构改动：让 **Host → Workspace → Session** 成为显式层级，给 **Chat / Sessions / Workspace / Settings** 四个稳定的移动端目的地。一旦这三个 scope 不再混在一起，其余一切都会更容易安放。

---

## 1. 当前导航 / IA 模型（实际机制）

### 1.1 顶层目的地

只有三个名义屏幕（`MainActivity.kt:66-78`）：Chat / Sessions / Settings。**没有 `NavHost`、没有路由回退栈、没有底部导航、没有抽屉、没有导航栏/导航轨。** 导航是一个自定义整数 `navPage`（持久化按索引，限制 `0..2`，`OrchestratorViewModel.kt:57-65`），通过 `AnimatedContent` 渲染（`MainActivity.kt:283-389`）。

这是一个**扁平目的地切换器**，不是真正的导航图。

| 跳转 | 机制 | 文件 |
|---|---|---|
| Chat → Sessions | 顶栏汉堡 | `ChatTopBar.kt:231-245` |
| Sessions → Chat | 会话图标或选中会话 | `SessionsScreen.kt:154-178` |
| Chat → Settings | DNS 图标 → 服务端对话框 → **无标签的 Settings 图标** | `ChatTopBar.kt:386-428` |
| Settings → Chat | 返回键 | `SettingsScreen.kt:173-181` |
| Sessions → Settings | **无直达**，必须先回 Chat | — |
| Files | 非顶层，全屏 overlay | `MainActivity.kt:392-415` |
| VCS | Settings 子段（只读，无 diff） | `SettingsScreen.kt:307-322` |

系统返回键从任何非 Chat 页都回到 Chat（`MainActivity.kt:269-274`），阻断正常导航历史（如 Files → Sessions）。

### 1.2 会话导航：三套机制并存

1. 水平标签条 + 关闭按钮（`ChatSessionTabStrip.kt:128-162`）
2. 会话页之间水平滑动（`ChatScreen.kt:399-414, 605-655`）
3. Sessions 屏幕/列表（`SessionsScreen.kt:154-158`）

标签条还会随垂直滚动方向动态隐藏（`ChatTopBar.kt:444-462`，手势判定在 `ChatMessageContent.kt:283-349`）。这是桌面浏览器标签 + 移动翻页 + 独立会话管理器的混合体。

标签条仅 36dp 高，关闭目标 24dp（`ChatSessionTabStrip.kt:304-399`）——**低于 48dp 触控规范**，且紧贴选中目标，极易误关。

### 1.3 overlay 清单（独立管理的模态布尔状态）

Chat 屏内部承载的浮层：上下文下拉、Todo 对话框、上下文用量 sheet、Agent 对话框、Server 对话框、Model 对话框、停止确认、错误详情、思考/压缩胶囊、重试卡、连接中胶囊、问题卡、Snackbar、跳转最新 FAB、全屏 Files overlay、TOFU 对话框、文件夹内容 sheet。

其他屏：目录选择 sheet、归档/断开对话框、缓存对话框、模型管理对话框、多个 host 配置对话框。

**独立模态状态的数量本身就是证据**——导航模型通过本地布尔承载了本该由「目的地 + 结构化 sheet」承担的东西。

---

## 2. UX/UI 问题清单（优先级排序）

严重度：5 = 阻塞核心可用性；1 = 优化。

### P5-1　没有稳定的移动导航层级
三个名义屏幕但无持久导航控件。Sessions 在汉堡后、Settings 在服务端对话框后、Files 是 overlay、VCS 在 Settings 内。
→ 用户必须记忆每个功能的位置；跨功能工作（chat → diff → file → session → host）需反复经 Chat 中转。
**方向**：M3 自适应导航壳（紧凑=NavigationBar，中等=NavigationRail，展开=轨 + 列表-详情）。建议目的地：**Chat / Sessions / Workspace / Settings**（Workspace 含 Files + Changes）。

### P5-2　Host / 工作目录 / 会话三个上下文被混淆
host=DNS 对话框，workdir=Sessions 分组，session=标签/滑动/列表，互不相关。当前上下文没有表达成一个连贯层级（`ChatTopBar.kt:309-338` 仅显示 workdir basename 副标题）。
→ 用户容易在错误项目发消息，或误解切 host 是否连带切了项目/会话。
**方向**：在 app bar 显式呈现上下文 —— 第一行 Host chip、第二行 Workdir chip、会话标题旁置。点 chip 开一个统一底部 sheet（最近 host/项目 + 搜索 + 切换/连接/管理）。切 host 应可见地重置/恢复对应项目与会话 scope。

### P5-3　桌面浏览器标签不适合作为手机会话主导航
36dp 标签条 + 24dp 关闭目标 + 滑动 + 滚动隐藏 + 手势歧义（与横向可交互内容冲突）。
**方向**：仿 ChatGPT/Gemini 移动版 —— app bar 放当前会话标题 + 一个「历史」按钮，会话选择器做成带搜索/最近/未读/按项目分组的全高 sheet 或抽屉。展开宽度下列表持久化为列表-详情的列表侧。关闭/归档走行溢出菜单，不用 24dp X。

### P5-4　变更/审查碎片化分散在 Chat 与 Settings
会话 diff 钉在反向时间线尾部（`ChatMessageContent.kt:589-606`），审查需三级嵌套展开（整卡 → 文件行 → 嵌套滚动 patch，`SessionDiffCard.kt:162-275, 311-332`）；而项目 VCS 状态埋在 Settings 且**故意无 diff 入口**（`SettingsScreen.kt:307-322`）。
→ 审查是核心职责之一，却没有持久位置。反向布局内嵌垂直滚动还带来手势难题。
**方向**：建立 **Workspace → Changes** 一等界面：变更文件列表 + 增删汇总 + 文件级 diff 屏 + 会话归因 + 审查/批准/回退操作。Chat 里只保留「N files changed」摘要并深链到 Changes。

### P5-5　revert / edit-from-here / fork 已实现但不可达
`ChatViewModel.editFromMessage()`（`ChatViewModel.kt:165-198`）做了服务端 revert；`SessionViewModel.forkSession()`（`SessionViewModel.kt:208`）存在。全局搜索无任何 composable 调用它们。字符串 `chat_edit_from_here` 零 Kotlin 引用。
→ power-user 工作流无法发现/使用；失误代价更高（无可见回滚分支操作）。
**方向**：给用户消息行加溢出菜单 —— Edit & rerun from here / Fork / Copy / Revert to this point。显式确认并说明对消息与文件的影响。**不要**仅靠长按。

### P5-6　决策面互相竞争且与聊天竞争
顶部中心可能同时出现：思考/压缩胶囊、重试卡、连接中胶囊（`ChatScreen.kt:700-741`）。问题卡在底部可变大并 overlay 而非压缩时间线，并**禁用整个 composer**（`ChatInputBar.kt:64-90`）。
→ 重要信息可能重叠遮挡消息；无统一「agent 需要关注」队列。
**方向**：单一优先级**活动/状态槽** —— (1) 需动作的权限/问题 (2) 重试/错误 (3) 运行/压缩 (4) 连接态。同一时间只渲染一个顶部状态面；决策请求用底部 sheet 或 composer 正上方的稳定「需动作」卡 + 队列指示。

### P5-7　权限路由在 UI 层非会话作用域
问题已按当前会话过滤（`ChatScreen.kt:416-422`），但权限没有 —— UI 渲染全局 `pendingPermissions.firstOrNull()`（`ChatScreen.kt:827-833`）。
→ 后台会话的权限可能弹在别的会话前面；用户可能在上下文不足时授权。
**方向**：内联卡片按当前会话过滤；跨会话待决项进全局「活动/收件箱」徽标。每张权限卡必须显示 host/项目/会话/工具/目标/风险范围。

### P4-1　主要每提示选择藏在「上下文用量环」背后
agent/model/context-usage/todo 全部塞在一个无标签环后（`ChatSessionTabStrip.kt:405-515`），各自再开 `AlertDialog`（`ChatTopBar.kt:499-687`）。上下文压力是诊断指标，model/agent 是任务定义控件——不应同层级。
→ 用户看不出下一个提示由哪个 agent/model 处理；改一次需 3 次点击 + 认识一个符号控件。
**方向**：composer 上方放紧凑 **Agent / Model chip**，各开可搜索 `ModalBottomSheet`；上下文用量降为二级状态指示（会话信息 sheet 内）。

### P4-2　composer `+` 语义误导且不完整
`+` 立即开图片选择器（`ChatInputBar.kt:167-197`）；命令只能靠手打 `/`；**无文件引用入口**。
→ `+` 暗示菜单却只有一个动作；用户无法在同一组合流程中发现命令或引用工作区文件。
**方向**：`+` 开 M3 底部 sheet —— 图片 / 引用工作区文件 / 浏览项目 / 命令 /（后续）粘贴文本。`/` 与 `@` 仍提供键盘优先自动补全。文件选择插入**可见可删的引用 chip**，而非逼用户走无关的文件预览 overlay。

### P4-3　Settings 是杂物间
一页垂直滚动混了：连接与流量 / 外观 / 工作目录·VCS / 模型管理 / 调试 / 危险区 / 关于（`SettingsScreen.kt:184-263`）。host 管理用布尔分支替换整页内容而非导航（`SettingsScreen.kt:151-166`）。
→ 全局偏好、项目状态、运行时诊断、破坏性管理混在一起；项目功能难找。
**方向**：Settings 严格全局化 —— Hosts & 安全 / 外观 / 模型·provider / 通知 / 存储·缓存 / 诊断·关于。工作目录与 VCS 移到 Workspace。用正常子路由或列表-详情，而非条件替换整屏。

### P4-4　交互方式不一致且缺乏提示
混用 tap / 水平滑动 / 长按 / 关闭 X / 可展开卡 / 下拉 / 对话框 / sheet / 全屏 overlay。例：长按 workdir=断开（`SessionsScreen.kt:261-285`）、长按 session=归档（`SessionsScreen.kt:202-209`）、长按文件=分享（`FileBrowserPane.kt:22-37`）、双击图片=缩放（`FilePreviewPane.kt:341-356`）、滑动会话=切换会话。
→ 学会「长按归档会话」无助于发现「长按分享文件」；隐藏手势对破坏性/重要操作尤其糟。
**方向**：tap=导航/选择，溢出菜单=二级/破坏性行操作，显式 chip/button=状态变更操作，手势只作可选加速器。**绝不**让长按成为唯一可发现路径。

### P3-1　M3 采用不完整，留有可访问性与自适应缺口
已做对：M3 TopAppBar/卡片/列表项/Snackbar 广泛使用；Android 12+ 动态色（`Theme.kt:185-193`）；edge-to-edge（`MainActivity.kt:109`）；集中 shape token（`Shape.kt:28-34`）；部分流程正确用 `ModalBottomSheet`（`DirectoryPicker.kt:103-106`）。
偏差：无 NavigationBar/NavigationRail/自适应导航套件；选择目录滥用 `AlertDialog` 而非可搜索 sheet；无 predictive-back（靠分层 `BackHandler`）；24/28/40dp 触控目标过小；横屏按物理方向而非 WindowSizeClass（`MainActivity.kt:245-249`）；Files 无搜索；`FilesScreen.kt:78-85` 注释声称清后代 semantics，但 `.semantics{}` 并非 `clearAndSetSemantics`——**注释与代码不符**。
**方向**：采用 M3 自适应导航、48dp 触控目标、长列表用模态 sheet、会话/文件/模型/host 选择器加搜索、路由感知 predictive back。

### 真机多屏视觉评审（observer，6 张已连接真机截图）

> 截图存于 `tmp/Screenshot_2026-07-11-11-49..11-50-*.jpg`。整体判定：**约 30% 移动原生 / 70% 桌面移植**——有移动骨架（edge-to-edge、手势导航、底部 composer、单列竖屏），但披着桌面工具的皮。

**逐屏证据**：

| 屏 | 内容 | 关键视觉问题 |
|---|---|---|
| **S1** 聊天 | 顶栏汉堡+截断标题+同步圈+面板图标；会话标签条；思考卡 + agent 文字墙消息 | agent 消息是**无差别的文字墙**：无头像、无气泡、无发送者标签、无视觉分组；用户 vs agent vs system 消息混作一团；思考卡是唯一视觉断点。文本边距 ~12dp 偏挤 |
| **S2** 聊天+浮层 | 一个白卡浮层叠在标签条上，显示 `12% 125/1000`、`1/6`、`orchestrator`、`GLM-5.2` | ⚠️ **浮层直接压住标签条**，遮挡首个标签及其 × 关闭目标，无 scrim、无关闭手势、无重定位——这是把桌面 tooltip/popover 套上了手机。对应 P4-1（model/agent 藏在浮层后）+ P5-3/标签条 |
| **S3** 服务端模态 | 居中卡片：连接状态、认证方式、uptime、断开按钮；下方 GLM-5.2 用量 27% + 重置倒计时 | 一个卡片塞了状态+认证+uptime+用量+重置——桌面「状态仪表盘」思维，而非移动渐进式披露。`断开连接` 是红字纯文本，缺 M3 破坏性按钮处理 |
| **S4** 设置 | 连接管理卡 + 外观（主题 3 胶囊 + 字号圆点滑块 + 界面缩放圆点滑块）+ 工作目录 + 模型管理 | 主题选择器是**自定义 3 胶囊**（非 M3 `SegmentedButton`）；字号/缩放是**自定义圆点滑块**（非 M3 `Slider`）。部分像 M3、部分自定义 → 视觉不一致 |
| **S5** 会话列表 | 近期会话 5 项 + 已连接项目 3 项 + 右上 `+` 加项目 | 彩色方块图标非 M3 `ListItem` 图标槽；列表项无 Card 包裹、无 surface 区分；加项目按钮在右上角硬够区 |
| **S6** 文件浏览器 | 扁平列表：文件夹 + 文件，忽略项带「已忽略」灰标 | 无面包屑、无返回上级、**无搜索**、无预览、无多选；标题栏无关闭/返回，仅系统返回；纯 `ls` 渲染成列表 |

**跨屏综合（视觉层对架构层问题的印证）**：

1. **全应用无底部导航**（S1-S6 均无）——这是移动 app 第一标志物的缺失。3 个顶层目的地靠汉堡 + 返回栈，是 2012 年 Android 模式，M3 已弃用为主导航。**单加一条底部 NavigationBar 就能从「桌面移植」推向「移动原生」**。
2. **会话标签条是 IDE/浏览器标签栏**（S1/S2）——截断标签（`新...`、`op...`）、无图标、~16dp × 关闭目标、需横滑。印证 P5-3。
3. **model/agent 信息以浮层 popover 形态压住交互区**（S2）——印证 P4-1（藏在上下文用量环后）且产生新的遮挡冲突。
4. **聊天转录是单色文字流**（S1）——移动聊天 app 用气泡/头像/卡片，这里用桌面终端的等宽流式。印证「工具输出与 AI 对话混同一流」反模式。
5. **M3 组件纪律不均**（S1/S6）——会话标签条、文件列表项、图标用了自定义实现而非 M3 标准件。（⚠️ 更正：S4 的主题选择器与字号/缩放滑块经核实 `SettingsSections.kt:28-31,183-252` **已是 M3** `SegmentedButton`/`Slider`；observer S4 误读为自定义控件，此条不成立。）
6. **文件浏览器缺所有移动文件管理必备件**（S6）——无搜索/面包屑/返回/预览。印证 P5-1（Files 非目的地）。

**视觉层 8 大问题速查**：

| # | 屏 | 问题 | M3 对齐修法 |
|---|---|---|---|
| V1 | 全部 | 无底部导航 | 加 M3 `NavigationBar`（Chat/Sessions/Settings，后续 Workspace）|
| V2 | S1/S2 | agent 消息无差别文字墙 | 每条消息用 M3 `Card`/`ElevatedCard` + `ListItem` 式头像+发送者+正文，按 agent/user 配色 |
| V3 | S2 | 浮层压住标签条 | 换 M3 `ModalBottomSheet`（底部锚定）或 `DropdownMenu`（锚定触发图标），绝不浮在交互元素上 |
| V4 | S1 | 标签条非 M3 不可读 | 换 `ScrollableTabRow`+全标签，或顶栏触发的会话切换 sheet/dropdown |
| V5 | S1/S4 | 版本号当标题 + 无标签图标 | 标题=会话名（省略号截断）；状态用带 `contentDescription` 的 `Badge`+`IconButton` 或标签 chip |
| V6 | S6 | 文件浏览器裸列表 | 加 `TopAppBar` 导航图标 + `SearchBar` + 面包屑 chip + `ListItem`（overline 类型+headline 名+supporting 大小/日期）|
| V7 | S4 | ~~自定义控件非 M3~~ | ⚠️ **撤回**：经核实 `SettingsSections.kt:28-31,183-252` 主题已是 `SingleChoiceSegmentedButtonRow`、字号/缩放已是 M3 `Slider`。observer 视觉误读。该项无需改动。 |
| V8 | S1 | composer 无富文本能力 + 无附件预览 | `+`→`FilledTonalButton` 带标签；输入下方加常用动作 `AssistChip` 行；附件预览卡在输入上方 |

---

## 3. 代码架构问题清单（优先级排序）

> 架构定性：**MVVM 表示外壳 + 应用级共享状态 store + 命令式 coordinator + 事件总线编排 + 部分 reducer 式状态变换**。可运行，但不是边界稳定、可独立推理的最终架构——是迁移中间态。边界只靠约定维持：`AppCore` + `SharedStateStore` + `ControllerEffect` + `appScope` 共同形成事实上的全局运行时。

严重度：5 = 阻塞长期演进。

### A5-1　data 层反向依赖 UI，形成真实依赖环
`CacheRepository.kt:6-10` import 了 `ui.CachedSessionWindow`、`ui.chat.Entry`、`GapFillState`、`GapMarker`、`withGaps`（定义在 `AppStateSlices.kt:340-360`）。
→ 形成环：`ui → data.cache.CacheRepository → ui.chat.*`。调整聊天渲染或 gap UI 会迫使 data/cache 变化。
**方向**：建立独立 domain/cache 契约（`CachedConversationWindow` / `ConversationGap` / `CachedMessageLayout`）；data/cache 只处理 domain model；UI 在 selector 里做 `withGaps()` 投影。

### A5-2　AppCore + SharedStateStore 是事实全局 service locator
任何持有 `AppCore` 的类可访问几乎所有 repository/controller/scope/state writer（`AppCore.kt:78-143, 146-179`）；`ChatViewModel`/`OrchestratorViewModel` 仍注入整个 core（`ChatViewModel.kt:37-55`、`OrchestratorViewModel.kt:45-55`）并暴露 repository（`ChatViewModel.kt:53-55`）。
→ 所谓领域边界无法由编译器约束。
**方向**：废除 VM 对整个 AppCore 的注入，按 feature 提供 query/read 接口 + intent sink + use case + scoped state holder。AppCore 最终仅保留进程级 supervisor，不暴露内部依赖。

### A5-3　跨 slice 状态转换非事务化
`StateFlow.update` 防单 slice 丢更新，但不提供跨 slice 事务。例：materialize draft session 连续写 session/chat/unread/composer（`AppCoreOrchestration.kt:296-337`）；archive 当前 session 先写 session list 再清 chat（`SessionSyncCoordinator.kt:438-453`）。注释要求 caller 用 `Main.immediate`（`AppStateSlices.kt:402-404`）但 store 不验证。
→ 多个合法单 slice 中间态组合可能是非法 app state；`appScope` dispatcher 一变就暴露。
**方向**：对需跨域原子性的操作定义 transaction/reducer（`AppAction.SessionMaterialized` / `SessionArchived` / `HostChanged`），由单线程 actor 一次产出新聚合 state 再执行 effects。

### A4-1　SessionSyncCoordinator 是 SSE god-controller（1612 行）
同时管：SSE 事件解析/dispatch（`402-1042` 巨型 when）、多 slice fold、unread 策略、archive 策略、流式 coalesce（`1059-1132`）、cache append、重连对账、pending-question 扇出、未知事件诊断、副作用翻译（`212-253` 双订阅 effect bus）。
→ 新增事件须改解析/dispatch/transform/副作用/测试/cache。17+ 事件共处一巨 when，跨分支共享可变上下文。
**方向**：建立 typed pipeline —— `RawSseEvent → SseDecoder<Event> → EventHandler<Event> → StateMutation+Effect → reducer/effect runner`。每 handler 独立文件、显式声明读写 slice；registry 只管类型映射与未知事件策略。

### A4-2　effect bus 隐藏控制流和生命周期
`SharedEffectBus`（`SharedEffectBus.kt:13-32`）被 AppCore 和 SessionSyncCoordinator **分别收集**。处理顺序依赖各 collector 调度；`SharedFlow` 广播意味着所有 collector 都看到 effect，而非「恰好一个 handler」。`assertExactlyOneHandled`（`AppCore.kt:350-369`）只能确认级联是否命中，不能检测另一 collector 是否也执行了语义处理。
**方向**：commands 用单消费者 typed command dispatcher；domain events 可广播；UI events 单独保留；不让一种 SharedFlow 同时承担 command mailbox 和 observable event stream。

### A4-3　ConnectionCoordinator 混多状态机与用例（811 行）
health probe/retry/throttle、TOFU 信任状态机、初始数据 fan-out、slash 命令目录、SSE lifecycle、stale-host generation guard、cache 维护清扫、连接态变更（`187-435, 479-540, 569-603, 655-806`）。
→ 不是单一「连接协调器」，是至少 4 个用例 + 2 个状态机的组合。
**方向**：拆 `ConnectionSupervisor`（只拥有连接状态机+child job）/ `TlsTrustCoordinator` / `InitialSyncUseCase` / `SseConnection` / `CommandCatalogLoader`。

### A4-4　ChatState 与 SessionListState 是 god-slices
`ChatState`（`AppStateSlices.kt:198-290`）混了 authoritative messages + 流式 overlay + 分页 + 压缩 + gap markers + UI refresh nonce + delta buffering 与调度镜像。`SessionListState`（`299-316`）混了 session 目录 + statuses + 展开 UI 态 + 权限/问题 + children/directories/open tabs + todos/diffs。
→ 新增 file/revert/add-menu 极易继续往这两个 slice 塞字段。
**方向**：至少拆为 `ConversationState`/`StreamingState`/`HistoryPagingState`/`SessionCatalogState`/`SessionActivityState`/`PendingRequestsState`/`SessionArtifactsState`。不只拆文件，写权限与 reducer 也要分。

### A4-5　Repository 是大型可变 facade（918 行）
持有可变 host 配置 + 多 OkHttp client + 多 Retrofit API + SSE + TOFU + 模型 API + 大量 endpoint facade（`OpenCodeRepository.kt:87-175, 202-215`）。host switch 是全局可变重配置，而非不可变 host-scoped client——这也是大量 generation guard 的根源。
**方向**：创建不可变 `OpenCodeClient(hostConfig)` 含 `SessionsApi/MessagesApi/FilesApi/.../SseStream`；切 host 通过替换 client identity，不原地重建 singleton。异步请求携带 client/host key，自然可判 stale。

### A3-1　⚠️ 真实 bug：revert 派生逻辑依赖当前加载窗口
`filterBeforeRevert`（`AppStateDerived.kt:111-129`）当 `revertMessageId` 不在当前分页窗口时**直接返回全部消息**。缓存 hydration、latest-tail load、分页期间可能**暴露本应隐藏的回退后消息**。
**方向**：将 revert cutoff 作为服务端/domain cursor 元数据处理，而非仅依赖当前 message list 查找时间；至少持久化 `revertCreatedAt`，无法解析时进入明确的 unavailable/needs-fetch 态。**暴露 revert UI 前必修。**

### A3-2　generation guard 正确但分散脆弱
host generation、serverGroupFp、sessionId 二/三次重检防 stale（`ConnectionCoordinator.kt:126-150, 489-535, 691-744`；`SessionSyncCoordinator.kt:165-181`；`AppCore.kt:447-510`）。每加一个 suspend point 都需人工补 guard；注释已记录多次漏检修复。
**方向**：统一 `RequestContext(hostKey, sessionId, generation)` + guarded commit API，或 `flatMapLatest`/scoped child job 在 host/session 变化时取消整个任务树。

### A3-3　scope ownership 不一致
UI load 用 appScope（`ChatViewModel.kt:75-109, 120-131`）、abort 用 appScope（`203-218`）、compact/edit 用 viewModelScope（`146-200`）。`AppCore.cleanup()`（`656-666`）取消 Hilt 提供的共享 application scope——若被其他 singleton 共享，影响范围超 AppCore 所有权。
**方向**：定义 scope 层级（process / connection-host / active-session / screen）；对象只能取消自创 child scope。

### A3-4　ViewModel 边界泄漏
`ChatViewModel` 暴露多领域 flow + repository（`41-55`）；`SessionViewModel` 构造器十项依赖并直接持 sibling controller（`48-63`）。UI 易选「最方便的 VM」而非正确领域入口。
**方向**：每屏用 screen-level state holder/facade，下调 domain use cases；不让一个 domain VM 暴露全应用 flow。

---

## 4. 竞品对照与可迁移模式

| 来源 | 可迁移模式 | 解决的痛点 |
|---|---|---|
| 移动版 ChatGPT | 会话列表=侧抽屉（搜索/最近/固定）；长按消息弹操作（复制/重新生成/换模型）；浮动底部 composer 随键盘升；每条回复显示模型名 | 会话切换 3 击→1 滑；操作分散；看不出当前 agent/model |
| Google Gemini | `+`→底部 sheet（顶部轮播相机/图库/文件 + 下方工具）；AMOLED + 动态色；模型选择器移入顶栏；"Power Up"一键注入上下文 | 附件与命令无统一入口；顶栏杂乱；提示常缺上下文 |
| PocketShell / Blink / Termius（移动终端类比） | 终端/对话双 tab；面包屑 + 状态点；键盘上方常驻特殊键行（Esc/Tab/Ctrl）；键盘收起时的命令 chip 行；统一对话 composer 与原始终端输入分离；窗格间水平滑动 | shell I/O 与 AI 消息混杂；手机无法输 Ctrl/Tab/Esc；常用命令要反复切键盘 |
| Android M3 权威 | 紧凑宽（<600dp）用 `NavigationBar`（3-5 项），`NavigationSuiteScaffold` 自适应；上下文功能用 `ModalBottomSheet`；slash 用 `SearchBar`；新会话用 `ExtendedFAB`；长列表操作用 `SwipeToDismissBox`/溢出 | 无稳定导航；选择器滥用 AlertDialog；命令无可发现入口 |

**M3 对本项目「上下文功能该放哪」的判定**：
- 模型/agent → TopAppBar 下拉或抽屉（频繁切换时）
- diff → ModalBottomSheet（半屏）/全屏 Dialog（长 diff）
- 文件引用/附加 → composer `+` 触发的 ModalBottomSheet
- slash 命令 → composer 内联 SearchBar / `ModalBottomSheet`
- 权限/确认 → AlertDialog（简单）/ ModalBottomSheet（含 diff）
- 消息二级操作 → DropdownMenu / SwipeToDismissBox（轻量回退）
- 设置 → 抽屉底部或顶栏齿轮

**必须避免的反模式（桌面 cargo-cult）**：
1. 浮动 composer 覆盖最后一条消息（不做内容 padding 交换）
2. 桌面式顶部水平标签栏导航
3. diff 并排视图（360dp 屏每列 <240px 不可读）→ 统一 diff + 内联高亮
4. 3+ 层嵌套设置 → 扁平 + 底部 sheet 单层子设置
5. 工具输出与 AI 对话混同一流 → 双紧凑 tab（对话 / 工具日志）
6. 键盘快捷键作唯一命令入口 → `/` 弹出 + 可见 chip 行
7. iPhone 手势搬运 → 用 `BackHandler` + predictive back + M3 组件
8. 模态 overlay 阻塞会话视图 → 权限预览用 ModalBottomSheet（背景可见）
9. 「新会话」占一个底部导航位 → 空态 ExtendedFAB / 顶栏操作
10. 复用 web 并排只读 diff → 移动优先统一 diff

---

## 5. 目标信息架构（IA）

### 5.1 上下文层级（显式化）
```
Host
└── Workspace / 工作目录
    ├── Sessions
    │   └── Conversation
    ├── Files
    └── Changes
```
Settings 是全局的，**不应含项目状态**。

### 5.2 顶层目的地（紧凑手机，4 项 NavigationBar）
1. **Chat**（默认）
2. **Sessions**
3. **Workspace**（二级分段：Files / Changes）
4. **Settings**

VCS 不作第五个顶层 tab，归 Workspace。中等宽度用 NavigationRail；展开宽度用列表-详情（Sessions：列表左/会话右；Workspace：文件/变更列表左/预览/diff 右）——按 WindowSizeClass 而非物理方向。

### 5.3 Chat 屏（仿 ChatGPT/Gemini 移动版）
- **app bar**：历史/会话按钮 + 会话标题 + 紧凑 host/workdir 上下文 chip + 会话操作溢出。移除紧凑屏的持久浏览器标签与关闭按钮。
- **对话**：全宽可读助手回复；用户提示视觉区分；工具活动折叠成紧凑分组；「N files changed」摘要深链到 Workspace→Changes；消息溢出菜单（copy/edit/fork/revert）。
- **composer**：`[+] [输入][发送]` 上方 `[Agent chip][Model chip][上下文]`。`+` 开附件/引用/命令 sheet；agent/model 发送前可见；问题/权限作 composer 正上方的单一「需动作」面。

### 5.4 Sessions
搜索 / 最近 / 需动作 / 按工作区分组 / 运行·重试状态 / 行溢出（归档/重命名/fork）/ 清晰「新会话」FAB。选中回 Chat；展开屏更新相邻详情面板。

### 5.5 Workspace
- **Files**：可搜索文件树/列表 / 面包屑 / 状态过滤 / 预览作目的地或详情面板 / 可见分享溢出。
- **Changes**：变更文件列表 / 按状态·会话过滤 / 统一-分屏 diff 模式 / 文件导航 / rollback/revert 操作（带上下文）。Chat 的 diff 卡只作入口摘要。

### 5.6 Host / 工作目录切换
点 app bar 上下文 chip 开一个底部 sheet：当前 host / 当前 workdir / 最近 workdir / 切 host / 连接项目 / 管理 host。配置编辑仍留 Settings。

### 5.7 待决收件箱
活动徽标挂在 Sessions 或 Chat app bar（初期不作第五目的地）。sheet 含跨会话：权限请求 / 问题 / 重试·错误 / 未读完成运行。内联卡只显示当前会话请求。

---

## 6. 目标代码架构

**不建议推倒重写。** 可保留资产：immutable data class、StateFlow、纯 `applyXxx` transform、Hilt、Retrofit/OkHttp 层、现有 controller 单测、typed ControllerEffect 思想。

### 6.1 目标分层
```
app
├─ feature-chat     (ChatScreen / ChatViewModel / ChatUiState / ChatIntent)
├─ feature-sessions
├─ feature-files
├─ feature-settings
├─ domain           (model / usecase / repository interfaces)
├─ data-network
├─ data-cache
└─ core-runtime     (HostSession / SseSupervisor / AppEventRouter)
```
初期不必立即拆 Gradle module，先按 package + interface 建方向，之后再物理模块化强制依赖。

### 6.2 每 feature 局部 UDF
`Intent → ViewModel/use case → Result/Event → reducer → UiState`。跨域操作由明确 use case 承担（`SwitchHost` / `MaterializeDraftAndSend` / `ArchiveSession` / `ReconnectAndInitialSync`），不让任意 controller 直接拿完整 `SliceFlows`。

### 6.3 SSE 模型
`SseSupervisor → Flow<DomainEvent>`；`DomainEventHandler<T> → EventOutcome(mutations, effects)`；单一 reducer/actor 提交 state + 启动 effects。关键是把 decode / domain decision / state mutation / effect execution 分开，而非把 when 换成 Map。

### 6.4 Host/client 生命周期
不可变 host-scoped client：`HostRuntime(hostKey, client, scope, sseSupervisor)`。切 host 取消旧 `HostRuntime.scope` 并建新 runtime —— 大量 generation guard 变成结构化取消，generation 仅作最后一道提交验证。

### 6.5 ViewModel 策略
screen 只注入对应 VM；VM 不公开 repository；不公开无关领域 flow；跨屏共享数据来自 domain store/query 接口而非共享 VM；process singleton 不直接持 UI state 类型。

---

## 7. 重构路线图（分阶段）

### 阶段 0　紧急修复（不改架构）
- **修 revert 窗口泄漏 bug**（`AppStateDerived.kt:111-129`）—— 持久化 `revertCreatedAt`，暴露 revert UI 前必修（A3-1）
- **修 Files semantics 注释/代码不符**（`FilesScreen.kt:78-85`）+ TalkBack 验证（P3-1）
- 触控目标 24/28/40dp → ≥48dp（P3-1）
- 顶栏去版本号；未连接去红点角标（observer）

### 阶段 1　快速见效（UI 局部，低架构风险）
对应 §2 的 Quick wins：
1. agent/model 升为 composer chip（P4-1）
2. composer `+` 改底部 sheet 菜单（图片/文件引用/命令）（P4-2，方案 A：文件路径作 text-part 注入，零协议风险）
3. 消息行溢出动作（edit-from-here / fork / copy / revert）+ 确认框（P5-5）
4. 权限卡按当前会话过滤 + 显示项目/会话上下文（P5-7）
5. 单一顶部状态槽，禁止连接/重试/思考同位竞争（P5-6）
6. 长按专属动作改行溢出菜单（P4-4）
7. Sessions/Files/模型/host 选择器加搜索（P3-1）
8. Settings 可见入口（移出 DNS 对话框）（P4-3 局部）
9. 会话 diff 摘要可深链 + 标注「审查变更」（P5-4 局部）
10. VCS 视觉上移出 Settings（P5-4 局部）

### 阶段 2　结构化导航
1. 用真实路由图替换 `navPage`/`AnimatedContent`（P5-1）
2. 引入自适应 NavigationBar/NavigationRail（P5-1）
3. 一等 Workspace 目的地（Files + Changes）（P5-4）
4. 统一 host/workdir 上下文选择器（P5-2）
5. 全屏 Files overlay 改正常可导航目的地/详情面板
6. 会话作用域决策面 + 跨会话活动收件箱（P5-6/P5-7）
7. Settings 全局子路由化，移除项目/VCS 状态（P4-3）
8. 路由感知 + predictive back（P3-1）
9. WindowSizeClass 列表-详情替换方向判断（P3-1）
10. 紧凑会话标签/滑动在 Sessions 选择器成熟后移除（P5-3）

### 阶段 3　架构治理（与阶段 2 可部分并行）
1. 拆 data→ui 反向依赖环：cache 契约下沉 domain（A5-1）
2. 废除 VM 注入整个 AppCore；feature 化 query/intent/use case（A5-2）
3. 跨 slice 事务化 reducer（A5-3）
4. SessionSyncCoordinator 拆 typed SSE pipeline（A4-1）
5. effect bus 拆 command dispatcher / domain event / UI event（A4-2）
6. ConnectionCoordinator 拆 supervisor + use cases（A4-3）
7. ChatState/SessionListState 拆分 slice + 写权限（A4-4）
8. Repository 改不可变 host-scoped client（A4-5）
9. 统一 RequestContext + 结构化取消取代手散 generation guard（A3-2）
10. scope 层级化（A3-3）

### 建议顺序
1. 阶段 0（紧急 bug）
2. 阶段 1（快速见效，解锁已实现但不可达能力）
3. 阶段 2 第 1-3 步（导航壳 + Workspace）—— 一旦 Host→Workspace→Session 显式化，其余都更好安放
4. 阶段 3 与阶段 2 后续并行

---

## 8. 即将到来的三大功能：架构风险与前置工作

### 8.1 文件浏览 / 审查状态持久化 —— 风险：高
当前 `FileState` 很薄（`AppStateSlices.kt:149-154`），但 UI 多处直接依赖 repository（`FilesViewModel`、`FilePreviewPane`、`MarkdownWebPreviewPane`、`SessionsScreen`）。若加多 tab/路径历史/选择/编辑/diff/revert/upload，`FileState` 会快速膨胀且 network call 继续散入 Composable。
**前置**：先确定 file feature 的**唯一 state owner**（当前 `OrchestratorViewModel` 与 `FilesViewModel` 双 owner）。
**建议**：变更审查落地为 Workspace→Changes 一等地（阶段 2），而非继续往时间线尾部嵌套。选中文件 + 展开态 + 滚动位置持久化挂 `SettingsManager`（仿 `getDraftText`）。

### 8.2 编辑器 Add 菜单 —— 风险：中高
若只是 UI popup 风险低；但若含 attach image/file / browse server file / run command / insert mention / provider 操作，将横跨 composer/files/sessions/settings。
**前置**：add-menu 产出 typed `ComposerAddAction`，由 composer feature 解释；**不要在 AppCore 增加更多菜单分支**（`sendMessage` 已是跨域 orchestration，`AppCoreOrchestration.kt:267-337`）。
**文件引用方案 A（已与用户确认）**：文件路径作 `PartInput(type=text)` 注入（约定标记如 `File: path`），零协议风险，不动 `OpenCodeApi.PartInput` schema。后续方案 B（加 path 字段）需先验证服务端消费。

### 8.3 Revert 暴露 —— 风险：高
现有 revert/edit 流程（`ChatViewModel.kt:165-200`、`AppStateDerived.kt:78-130`）在成功路径连续执行多跨域写 + reload；session diff 又存在 `SessionListState`（`313-316`）。
**前置必修**：
- 修 `filterBeforeRevert` 窗口泄漏（A3-1）——否则暴露 revert 后消息会成数据正确性事故
- 建立 `RevertConversation` use case，revert metadata 变 domain state
- 定义完整成功/失败/取消 outcome
- 加显式确认 + 影响说明（移除其后所有消息 + 输入框恢复该消息内容）

---

## 附录 A　评审来源
- **oracle**（UX/UI 架构）：通读 `ui/chat/*`、`ui/sessions/*`、`ui/settings/*`、`ui/files/*`、`MainActivity.kt`、导航/overlay 清单
- **gpter**（代码架构）：通读 `SessionSyncCoordinator.kt`(1588)、`ConnectionCoordinator.kt`(811)、`AppCore.kt`(672)、`AppCoreOrchestration.kt`(650)、`AppStateSlices.kt`(430)、`AppStateDerived.kt`(339)、`ChatViewModel.kt`(250) 等
- **librarian**（竞品/M3）：移动版 ChatGPT/Gemini、PocketShell/Blink/Termius、Android M3 官方规范，均附 URL
- **observer**（视觉）：6 张已连接真机截图（`tmp/Screenshot_2026-07-11-11-49..11-50-*.jpg`，S1-S6）多屏走查；判定「约 30% 移动原生 / 70% 桌面移植」

## 附录 B　关键证据索引（file:line）
- 自定义整数导航：`MainActivity.kt:239-255, 283-389`；`OrchestratorViewModel.kt:57-65`
- 三套会话切换：`ChatSessionTabStrip.kt:128-162, 304-399`；`ChatScreen.kt:399-414, 605-655`；`ChatTopBar.kt:444-462`
- agent/model 藏环后：`ChatSessionTabStrip.kt:405-515`；`ChatTopBar.kt:499-687`
- diff 钉时间线：`ChatMessageContent.kt:589-606`；`SessionDiffCard.kt:162-275`
- revert/fork 不可达：`ChatViewModel.kt:165-198`；`SessionViewModel.kt:208`
- 权限未会话作用域：`ChatScreen.kt:827-833`
- overlay 竞争：`ChatScreen.kt:687-770, 816-834`
- data→ui 环：`CacheRepository.kt:6-10`；`AppStateSlices.kt:340-360`
- AppCore god-locator：`AppCore.kt:78-143`；`ChatViewModel.kt:37-55, 53-55`
- 非事务跨 slice：`AppCoreOrchestration.kt:296-337`；`SessionSyncCoordinator.kt:438-453`
- SSE god-controller：`SessionSyncCoordinator.kt:402-1042, 1059-1132`
- effect bus 双收集：`SharedEffectBus.kt:13-32`；`AppCore.kt:350-369`
- ConnectionCoordinator 过宽：`ConnectionCoordinator.kt:187-435, 569-603, 655-806`
- god-slices：`AppStateSlices.kt:198-290, 299-316`
- Repository 可变 facade：`OpenCodeRepository.kt:87-175`
- revert 窗口泄漏 bug：`AppStateDerived.kt:111-129`
