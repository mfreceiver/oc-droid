# 功能移除评估：向 opencode web/TUI 极简状态机靠拢

> **状态**：决策材料（不替用户做决定）。供用户选择激进/中度/保守档。
> **基础**：[`state-machine-simplification-v5.1-lean.md`](../archive/state-machine-simplification-v5.1-lean.md)（Phase A 整合，已归档）+ opencode 设计调研 + ocdroid 功能全貌调研。
> **核心问题**：v5-lean/v5.1 已砍 ~50-58% 状态机行数。**还能更激进吗？** 本文档穷举 ocdroid 当前所有功能/保证，对照 opencode web/TUI 范式，逐项给决策材料。

---

## 0. 评估框架

### 0.1 opencode 的"极简"基准

opencode（web/TUI）的状态机基线：
- **server 侧**：仅 1 个 4 态 FSM（`Runner`: Idle/Running/Shell/ShellThenRun）
- **客户端 TUI**：零显式 FSM，用 SolidJS store + SSE while-loop + 事件折叠（`handleEvent()` switch）
- **核心代码量**：TUI ~1500 行 vs ocdroid ~12000 行

### 0.2 opencode "4 个不存在问题"

ocdroid 的 6 个核心问题中，**4 个在 opencode 根本不存在**（架构前提不同）：

| # | ocdroid 问题 | opencode 为何不存在 |
|---|---|---|
| 1 | 后台 SSE + 通知 | 桌面/TUI 客户端，服务器零感知前后台/FCM |
| 2 | optimistic claim | 同步 POST prompt 路径无空窗（busy 在 LLM 流前同步发射） |
| 3 | multi-host 并发 | 单 baseUrl，单服务器连接 |
| 4 | OwnershipGate 仲裁 | server 纯 fanout，无连接级 ownership |

### 0.3 opencode "零 FSM 范式"

opencode 用「事件流 → reactive store 折叠」取代「状态枚举 + 迁移表」：
- SSE 流 → batch events → update store → reactive UI
- 无 polling、无 mutation 后 refetch、无 FSM

**向 web 版靠拢的本质**：用 reducer-over-events 替代专门 FSM，删除移动端特有保证。

### 0.4 三档预告

| 档 | 定位 | 状态机减到 | 用户体验代价 |
|---|---|---|---|
| **保守档** | = v5.1 本体（基线） | ~7 FSM / ~5000-6000 行 | 后台/通知丢失（v5-lean 已确认） |
| **中度档** | v5.1 + 砍诊断/调试/移动端增值 | ~5-6 FSM / ~4000-4500 行 | + 丢失流量统计/调试日志/mTLS UI |
| **激进档** | 向 opencode web 范式 maximal 靠拢 | ~2-3 FSM / ~2500-3000 行 | + 丢失 TOFU/token 聚合/多 agent UI/host profile 厚层 |

详见 §5。

---

## 1. 穷举功能决策表

> 来源：ocdroid 功能全貌调研（exp-1，~90 项）+ 保留项实现确认（exp-2，6 项深核）。
> 「向 web 靠拢」：高=opencode 完全没有此概念；中=opencode 有等价但实现不同；低=核心功能两者都有。

### 1.1 核心状态机/连接层（v5-lean/v5.1 已覆盖 + 本评估深核项）

| 功能 | 当前作用 | opencode 有? | 移除代价（用户感知） | 向web靠拢 | 移除后状态机影响 | 建议 |
|---|---|---|---|---|---|---|
| **token stream 聚合**（TokenStreamCoordinator 1900行 + TokenStreamReducer 416行） | SSE 帧实时聚合成内存 overlay，含 epoch/generation/dedupPartRevision/bundle stamp 4 层防闪烁守卫 + watchdog | 部分（TUI 直接消费原始 delta，无聚合层） | **高**：失去防闪烁；流量增大（原始 delta 闪烁 + 流量大）；4 层守卫消失后 stale 帧污染 | 中 | 删 ~2316 行 + 4 个守卫 FSM；但需替换为"直接消费原始 delta"的薄层 | **精简**（见 §2.1） |
| **TOFU 证书钉扎**（6 文件 ~1735 行：HealthProbe/BootstrapCoordinator/TofuPinStore/TofuTrust/TofuRepository/SslConfig） | SSH 风格首次信任 + SPKI 变更检测；仅 server pinning（非 mTLS）；persistent + session 两层 | **无**（web 客户端用系统 CA store；TUI 直连 localhost 不需） | **中**：失去 MITM 防护；自签证书场景需退化到系统 CA | 高 | 删 TOFU 状态机（TrustPending/决策流）；ConnectionBootstrapCoordinator 大幅瘦身 | **可移除**（见 §2.2） |
| **AuthorityReducer 纯函数**（1092 行 + 测试 2931 行） | session busy/idle/retry 唯一写入者；纯 `(State, Op)->State`；detekt 强制单一写入点 | 有（SessionStatus 3 态投影 + reactive store） | 低（架构正确，本就该保留） | 低 | 无（保留）；可借鉴 opencode 3 态投影简化 | **保留**（v5-lean 决策不变） |
| **历史持久化** | **已无**（Room/SQLite 全删，纯内存 LRU） | TUI 无本地持久化（靠 server） | 无（已是 opencode 范式） | — | 无 | **保留现状** |
| **ProcessStatusPoller slim fan-out**（640 行双职责） | (a) bulk polling 给 CC (b) slim fan-out 扇出到 slimapi 订阅者 | 无（TUI 无 polling，靠 SSE） | 中：删 slim fan-out → slimapi 订阅者收不到 status | 高 | 替换为定时刷新须覆盖 fan-out | **精简**（v5.1 已记） |
| **OwnershipGate**（664 行，max-1 所有权） | 仲裁哪个 ConnectionIdentity 持有 SSE；两阶段 Starting/Ready + attemptId 超时 | 无（server 纯 fanout） | 低（**调研证实无 foreground+background 同时持有场景**，gate 主要防重连窗口双发） | 高 | v5.1 已定：精简到 ~200-300 行纯客户端 | **精简**（v5.1 已定） |
| **ConnectionBootstrapCoordinator**（240 行 TOFU 状态机共享单例） | HealthProbe 与 SessionStreamingService 间共享 TOFU 状态机 | 无 | 中（随 TOFU 一起处理） | 高 | 随 TOFU 决策 | **随 TOFU** |

### 1.2 后台/通知/FGS（v5-lean 已定删除，本评估复核）

| 功能 | 当前作用 | opencode 有? | 移除代价 | 向web靠拢 | 状态机影响 | 建议 |
|---|---|---|---|---|---|---|
| **FGS（SessionStreamingService 1466行）** | FOREGROUND_SERVICE_DATA_SYNC 保持后台 SSE | 无 | 高（v5-lean 已确认接受） | 高 | 删 ~800 行 + 解锁 L2/L4/L5 | **移除**（v5-lean 已定） |
| **5 个通知渠道**（decisions/idle/errors/session_status/session_status_min） | 权限/完成/错误/FGS 持续/静默 FGS | 无 | 高（无任何通知） | 高 | 删 NotificationChannels + 渠道 FSM | **移除**（v5-lean 已定） |
| **后台 30s 轮询**（AppLifecycleMonitor 390-400） | 后台探测 pending question/permission + 新鲜度 | 无 | 中（后台不收任何更新） | 高 | 删后台轮询 FSM | **移除**（随 FGS） |
| **通知去重持久化**（NotificationDedupStore） | 进程死亡后恢复已通知 IDLE 去重 | 无 | 低（可接受重复通知） | 高 | 删去重 store | **移除**（随通知） |
| **后台草稿持久化**（AppLifecycleMonitor:372） | 进后台 flush 未发送草稿 | 无 | 低（草稿仅内存） | 高 | 删 flush 逻辑 | **可移除** |
| **后台新鲜度探测**（AppLifecycleMonitor:465-500） | 后台探测新消息触发 catch-up | 无 | 低（回前台手动刷新） | 高 | 删探测 FSM | **可移除** |
| **通知点击深度链接**（MainActivity EXTRA_SESSION_ID） | 点通知跳转会话 | 无 | 中（随通知一起消失） | 高 | 删 deep link | **随通知** |
| **POST_NOTIFICATIONS 权限**（API 33+） | 运行时通知权限请求 | 无 | 中（随通知） | 高 | 删权限请求 UI | **随通知** |

### 1.3 连接/host/profile 管理（移动端厚层）

| 功能 | 当前作用 | opencode 有? | 移除代价 | 向web靠拢 | 状态机影响 | 建议 |
|---|---|---|---|---|---|---|
| **多 host profile 管理**（HostProfilesManagerScreen 444行 + HostProfileStore） | 管理多服务器配置（增删改查 + 切换） | **无**（单 baseUrl） | **高**：多服务器用户失去切换能力 | 高 | 删 host profile map + 切换 FSM | **激进档移除/中度档精简** |
| **mTLS 客户端证书**（CertImportSlot + PKCS12） | 双向 TLS 客户端证书导入 | 无 | 中（少数 mTLS 服务器用户受影响） | 高 | 删 mTLS 配置流 | **激进档移除** |
| **CA 证书导入**（CaStage） | 私有 CA 导入 | 无（用系统 CA） | 低（TOFU 覆盖大部分场景） | 高 | 删 CA 导入流 | **激进档移除** |
| **Basic Auth** | 用户名+密码认证 | 部分（TUI 配置文件） | 低 | 中 | 无 | **保留** |
| **slimapi 开关**（per-profile） | 启用/禁用 slimapi 协议 | 无（opencode 直连） | 低（默认开即可） | 中 | 无 | **可移除**（激进档） |
| **强制刷新/重连按钮** | 手动触发重连 | 无 | 低 | 中 | 无 | **保留**（用户兜底） |
| **流量统计**（TrafficTracker + TrafficSection） | 上下行字节数 + 重置 | 无 | 低（纯诊断） | 高 | 无 | **激进档移除** |
| **服务器状态指示**（呼吸灯） | 连接状态可视化 | 部分（TUI 有状态显示） | 低 | 低 | 无 | **保留** |

### 1.4 UI/设置/移动端增值

| 功能 | 当前作用 | opencode 有? | 移除代价 | 向web靠拢 | 状态机影响 | 建议 |
|---|---|---|---|---|---|---|
| **UI 字体缩放滑块**（0.85x-1.3x） | 仅文字缩放 | 无 | 低 | 高 | 无 | **激进档移除** |
| **UI 内容缩放滑块** | 整体密度 | 无 | 低 | 高 | 无 | **激进档移除** |
| **语言选择**（系统/中/英） | i18n | 跟随系统/浏览器 | 低 | 中 | 无 | **可移除**（跟随系统） |
| **调试日志查看器**（DebugLogSection 506行） | 实时日志 + 级别过滤 | 无 | 低（开发工具） | 高 | 无 | **激进档移除** |
| **调试详细日志开关** | 启用详细诊断 | 无 | 低 | 高 | 无 | **激进档移除** |
| **调试卡片身份覆盖** | 聊天卡片显示身份信息 | 无 | 低 | 高 | 无 | **激进档移除** |
| **SSE 禁用开关（调试）** | 强制 REST-only 降级 | 无 | 低 | 高 | 影响 SSE 降级路径 | **激进档移除** |
| **许可证信息页** | MIT + 第三方依赖 | 有（合规） | 低 | 低 | 无 | **保留**（合规） |
| **关于页面**（版本号） | 版本显示 | 有 | 无 | 低 | 无 | **保留** |
| **崩溃日志持久化**（CrashLogger） | 崩溃写文件 | 无 | 低 | 高 | 无 | **激进档移除** |
| **调试日志环形缓冲区**（3000 条） | 内存日志 | 无 | 低 | 高 | 无 | **激进档移除** |
| **屏幕方向锁定**（手机竖屏） | 锁定 portrait | 无 | 低 | 中 | 无 | **保留**（移动端必需） |
| **Edge-to-Edge** | 边到边显示 | 无 | 无 | 中 | 无 | **保留** |
| **宽屏双栏适配**（平板） | Expanded 双栏 | 无 | 低 | 中 | 无 | **保留** |
| **主题模式**（亮/暗/跟随） | 主题切换 | 有 | 无 | 低 | 无 | **保留** |
| **FileProvider 文件分享** | content URI 分享 | 无 | 低 | 中 | 无 | **保留** |
| **onTrimMemory 图片缓存释放** | 内存压力释放 markdown 缓存 | 无 | 无 | 中 | 无 | **保留** |
| **测试 Intent 注入**（debug build） | am start 注入凭据 | 无 | 无（仅 debug） | 中 | 无 | **保留**（开发用） |

### 1.5 会话/消息核心功能（opencode 等价，基本不动）

| 功能 | 当前作用 | opencode 有? | 移除代价 | 向web靠拢 | 状态机影响 | 建议 |
|---|---|---|---|---|---|---|
| **会话 CRUD**（创建/打开/重命名/归档/复制ID） | 会话管理 | 有 | 高（核心） | 低 | 无 | **保留** |
| **多 agent/model + 切换 UI** | agent/model 选择 | 有 | 高（核心） | 低 | 无 | **保留** |
| **子会话/子 agent 树**（SessionTree） | parentId 层级 | 有 | 中 | 低 | 无 | **保留** |
| **消息类型渲染**（text/reasoning/tool/patch/file/step/image） | 7 种 part 类型 | 有 | 高（核心） | 低 | 无 | **保留** |
| **流式输出**（SSE token） | 实时打字 | 有 | 高（核心） | 低 | 见 token stream | **保留**（精简聚合层） |
| **折叠/展开省略内容** | 展开 skeleton 省略 | 有 | 中 | 低 | 无 | **保留** |
| **Markdown + 代码高亮** | 格式化渲染 | 有 | 高（核心） | 低 | 无 | **保留** |
| **文件路径可点击导航** | 跳转 Files/预览 | 有 | 中 | 低 | 无 | **保留** |
| **子 agent 会话可点击** | task tool 子会话 | 有 | 中 | 低 | 无 | **保留** |
| **待办事项列表**（todos） | todo 渲染 | 有 | 中 | 低 | 无 | **保留** |
| **消息复制** | 长按复制 | 有 | 低 | 低 | 无 | **保留** |
| **token 用量显示** | token 计数 | 有 | 低 | 低 | 无 | **保留** |
| **撤回/revert** | 回退文件状态 | 有 | 中 | 低 | 无 | **保留** |
| **未读/待处理标记** | 卡片状态指示 | 有 | 低 | 低 | 无 | **保留** |
| **按项目分组** | workdir 分组 | 部分 | 低 | 中 | 无 | **保留** |
| **FilesScreen/GitScreen** | 文件浏览/Git diff | 有 | 中 | 低 | 无 | **保留**（可简化） |
| **会话刷新**（Refresh + ON_RESUME） | 手动/自动刷新 | 有 | 无 | 低 | 无 | **保留** |

### 1.6 持久化（移动端特有 + 已无）

| 功能 | 当前作用 | opencode 有? | 移除代价 | 向web靠拢 | 状态机影响 | 建议 |
|---|---|---|---|---|---|---|
| **会话列表持久化**（SessionPrefs） | 进程死亡恢复列表 | 有（server 侧） | 中（冷启动空白） | 中 | 无 | **保留**（移动端必需） |
| **消息窗口内存缓存**（CachedSessionWindow） | 切换会话不重载 | 无（TUI 单会话） | 中（切换慢） | 中 | 无 | **保留** |
| **草稿文本持久化** | 进程死亡恢复草稿 | 有（TUI 草稿） | 低 | 低 | 无 | **保留** |
| **每会话 agent/model 持久化** | 记住最后选择 | 有 | 低 | 低 | 无 | **保留** |
| **禁用模型集合持久化** | 记住禁用列表 | 部分 | 低 | 中 | 无 | **保留** |
| **host profile 持久化**（加密） | 服务器配置 | 无 | 高（随 host profile） | 高 | 无 | **随 host profile** |
| **TOFU 证书 pin 持久化** | 已信任证书 | 无 | 中（随 TOFU） | 高 | 无 | **随 TOFU** |
| **mTLS 证书持久化** | 客户端证书 | 无 | 中（随 mTLS） | 高 | 无 | **随 mTLS** |
| **流量统计持久化** | 累计流量 | 无 | 低 | 高 | 无 | **激进档移除** |

---

## 2. v5-lean 标"保留"项的再评估【本评估核心】

v5-lean 标注 4 项"保留（不可精简）"。本节逐条重新评估**是否也可移除/精简**。

### 2.1 token stream 聚合 — 可精简（非整体移除）

**v5-lean 决策**：保留（核心流量优化 + 防闪烁）。

**再评估证据**：
- opencode TUI **直接消费原始 `Text.Delta` 事件**，无聚合层、无防闪烁守卫。TUI 用 SolidJS store 的细粒度更新天然抗闪烁（每个 delta 触发独立 reactive 更新，而非批量重绘）。
- ocdroid 的 4 层守卫（epoch/generation/dedupPartRevision/bundle stamp）+ watchdog = ~2316 行，是为应对**多连接/重连/代次 fencing** 的复杂场景。
- 若 v5.1 已删除后台 SSE + 简化 OwnershipGate + 单 host，多连接场景大幅减少 → 4 层守卫中至少 generation + bundle stamp 可删。

**结论**：
- ❌ **不可整体移除**（失去实时打字能力，退化为 REST 轮询）
- ✅ **可深度精简**：保留基础聚合（SSE 帧 → 内存 overlay），删除 4 层守卫中的 2-3 层（epoch 保留防 stale，generation/bundle stamp 在单连接下冗余）。预期 2316 行 → ~800-1000 行。
- 🔬 **激进档可选**：完全模仿 opencode，直接消费原始 delta（用 Compose 的细粒度 recomposition 替代聚合层）。代价：流量增大、需验证 Compose 抗闪烁能力。**需原型验证，非确定性决策**。

### 2.2 TOFU 证书钉扎 — 可移除（激进档）

**v5-lean 决策**：保留（mtls 功能依赖）。

**再评估证据**：
- 调研证实 TOFU 是**仅 server pinning（单向）**，非 mTLS。mTLS 客户端证书是独立功能（HostProfile.clientCertConfig），不经过 TOFU 路径。**v5-lean "mtls 功能依赖"理由不成立**。
- opencode web 客户端（浏览器）用**系统 CA store**，无 TOFU。TUI 直连 localhost 也不需。
- TOFU 分散在 6 个文件 ~1735 行，是连接 bootstrap 的主要复杂度来源。

**结论**：
- ✅ **可移除**（激进档）：退化到系统 CA store。代价：失去自签证书场景的 MITM 防护；自签证书用户需改用 mTLS 客户端证书或系统 CA 导入。
- ⚠️ **中度档可精简**：保留 TOFU 但合并 6 文件 → 2-3 文件，~1735 行 → ~600-800 行。
- **决策点**：用户是否有自签证书 + 无 mTLS 的部署场景？若有，TOFU 必须保留。

### 2.3 AuthorityReducer 纯函数 — 保留（不变）

**v5-lean 决策**：保留。

**再评估**：已是纯函数 + detekt 强制单一写入点 + 测试覆盖 2931 行。这是**正确的架构**，opencode 也用类似模式（SessionStatus 3 态投影）。唯一可借鉴：用 opencode 的 3 态（idle/busy/retry）投影简化 UI 层耦合。**保留，不变。**

### 2.4 历史持久化 — 已无（不变）

确认正确，无 Room/SQLite，纯内存 LRU。**已是 opencode 范式。**

### 2.5 本评估新增的"可移除保留项"

调研发现 v5-lean 未单独提及但可评估的项：
- **TokenStreamReducer**（416 行独立纯 reducer）：随 token stream 聚合决策
- **SlimStatusFanOut**（ProcessStatusPoller 注入依赖）：随 Poller 精简
- **ConnectionBootstrapCoordinator**（240 行 TOFU 共享单例）：随 TOFU 决策
- **OptimisticClaimWatchdogCoordinator**：v5-lean 之后已从 Poller 分离，v5.1 已定删除

---

## 3. opencode "零 FSM 范式"的可迁移性评估

opencode 用「事件折叠」替代 FSM。ocdroid 能迁移多少？

### 3.1 高价值可迁移（减 FSM）

| ocdroid FSM | opencode 等价 | 迁移路径 | 预期收益 |
|---|---|---|---|
| 会话列表更新 FSM（ProcessStatusPoller 部分） | `handleEvent()` switch + `setStore()` | `Flow<Event>.scan(reducer)` + StateFlow | 砍 polling FSM |
| 状态同步 FSM（BannerHysteresis 等 UI reducer） | reactive store 折叠 | 合并到 AuthorityReducer 的 event 分支 | 砍 1-2 UI FSM |
| SSE 连接 FSM（ServiceSseConnectionOwner 重连代次） | while-loop + 指数退避 | v5.1 已规划简化 | 砍重连 FSM |

### 3.2 不可迁移（问题域不同）

- **后台/通知 FSM**：opencode 无此问题，不可借鉴
- **OwnershipGate**：opencode 无连接级 ownership，ocdroid 需自研纯客户端版
- **multi-host FSM**：opencode 单 baseUrl

### 3.3 迁移收益估算

若激进采纳事件折叠：会话列表 + 状态同步 + UI reducer 可合并为 1-2 个 reducer，**再砍 2-3 个 FSM / ~1500-2000 行**。

---

## 4. slimapi 联动依赖

哪些移除**需要 slimapi 配合**？（基于 Phase A Plan A/B 发现）

| 移除项 | 是否需 slimapi 改动 | 说明 |
|---|---|---|
| Plan A replay 恢复（断线全量快照） | ✅ **需**：加回 `GET /slimapi/sessions/status`（加性、不 bump wire 版本） | slimapi v2 契约已删此端点 |
| Plan B replay（V2 事件） | ❌ 不需（ocdroid 改走 `POST /sync/history`） | 但需扩 SseEventBridge |
| 删 ProcessStatusPoller slim fan-out | ⚠️ 视方案 | 若 slimapi 订阅者依赖 fan-out，需 slimapi 侧改用 SSE 推送替代 |
| 删 token stream 聚合（激进档） | ❌ 不需 | ocdroid 客户端直接消费 `/slimapi/sessions/{sid}/stream`（slimapi 仍聚合） |
| 删 TOFU | ❌ 不需 | 纯客户端 |
| 删 multi-host | ❌ 不需 | 纯客户端 |
| `/session/{sid}/event` 加入 is_sse 集 | ✅ **需**（1 行）：若启用长连 replay SSE | proxy.py:170 |

**结论**：只有 **replay 恢复路径**强依赖 slimapi 改动（Plan A）。其余移除均为纯客户端。

---

## 5. 三档打包方案

### 5.1 保守档（= v5.1 基线）

**定位**：执行 v5.1 已规划的精简，不额外移除功能。

**移除内容**：
- FGS + 后台 SSE（SessionStreamingService ~800 行）
- 通知系统（5 渠道 + 通知 FSM）
- optimistic claim（Watchdog 209 行）
- StreamingLifecycleCoordinator L2/L3/L4 分支（~1000 行）
- StreamingServiceLauncher（282 行）
- BootstrapJobHolder（34 行）
- SessionStreamingController（480 行）
- 精简：ServiceSseConnectionOwner / TokenStreamCoordinator / AuthorityReducer / AuthorityState / OwnershipGate

**状态机规模**：~7 FSM / ~5000-6000 行（-50% 到 -58%）

**用户体验代价**：
- ❌ 无后台 SSE / 无通知（用户必须 app 在前台才收更新）
- ❌ POST 后 ~250ms 才显示 busy
- ❌ 会话列表 30s 定时刷新（非实时）
- ❌ 单 host 连接

**适合**：接受 v5-lean 产品决策的用户。最小风险，最高可落地性。

### 5.2 中度档（v5.1 + 砍诊断/调试/移动端增值）

**定位**：在保守档基础上，砍掉纯移动端增值功能（诊断/调试/流量统计），保留核心连接安全（TOFU/mTLS）。

**额外移除（相对保守档）**：
- 调试日志查看器 + 详细日志开关 + 卡片身份覆盖 + SSE 禁用开关（4 项，~600 行）
- 流量统计（TrafficTracker + TrafficSection + 持久化，~200 行）
- 崩溃日志持久化（CrashLogger，~100 行）
- 调试日志环形缓冲区（DebugLog 3000 条，~150 行）
- 后台草稿持久化 + 后台新鲜度探测（随 FGS，~100 行）
- 通知去重持久化（随通知，~80 行）
- 语言选择（改为跟随系统，~40 行）

**状态机规模**：~6 FSM / ~4000-4500 行（额外 -500 到 -1000 行，主要是诊断/调试清理）

**用户体验代价**：
- 保守档全部代价 +
- ❌ 无调试日志查看（开发诊断退回 logcat）
- ❌ 无流量统计
- ❌ 无崩溃日志（退回系统崩溃报告）

**适合**：愿意为"更接近 opencode 极简"牺牲开发诊断工具的用户。诊断功能移除不影响终端用户。

### 5.3 激进档（向 opencode web 范式 maximal 靠拢）

**定位**：maximal 向 opencode web/TUI 范式靠拢。砍掉所有移动端特有保证 + 事件折叠迁移。

**额外移除（相对中度档）**：
- **TOFU 证书钉扎**（~1735 行，退化为系统 CA store）+ ConnectionBootstrapCoordinator
- **多 host profile 管理**（HostProfilesManagerScreen + HostProfileStore，~600 行，改为单 URL 输入）
- **mTLS 客户端证书 + CA 导入**（~400 行）
- **token stream 4 层守卫**（精简到仅 epoch，generation/bundle stamp/dedupPartRevision 删除，~1500 行）或**完全模仿 opencode 直接消费 delta**（需原型验证）
- **UI 字体/内容缩放滑块**（~150 行）
- **slimapi 开关**（默认开）
- **事件折叠迁移**：会话列表 + 状态同步 FSM → `Flow.scan` reducer（再砍 2-3 FSM / ~1500-2000 行）

**状态机规模**：~2-3 FSM / ~2500-3000 行（-75% 到 -80%，接近 opencode TUI 量级）

**用户体验代价**：
- 中度档全部代价 +
- ❌ 无 TOFU MITM 防护（自签证书需系统 CA 导入或 mTLS）
- ❌ 单 host（多服务器用户失去切换，需重装/重配）
- ❌ 无 mTLS 客户端证书 UI（mTLS 服务器不可用）
- ⚠️ token stream 防闪烁减弱（若完全模仿 opencode，需验证 Compose 抗闪烁）
- ⚠️ 无 UI 缩放（无障碍场景受限）

**适合**：追求极致精简、愿意接受移动端退化为"web 版打包成 app"的用户。**高风险**：token stream 直接消费 delta 需原型验证；TOFU 移除需确认无自签证书场景。

### 5.4 三档对比总表

| 维度 | 保守档 | 中度档 | 激进档 |
|---|---|---|---|
| 状态机数 | ~7 | ~6 | ~2-3 |
| 核心行数 | ~5000-6000 | ~4000-4500 | ~2500-3000 |
| 削减率 | -50~58% | -62~67% | -75~80% |
| 后台/通知 | ❌ | ❌ | ❌ |
| 调试/诊断 | ✅ 保留 | ❌ 移除 | ❌ 移除 |
| TOFU | ✅ 保留 | ✅ 保留（可精简） | ❌ 移除 |
| multi-host | ❌（v5.1 单host） | ❌ | ❌ + 单 URL |
| token 聚合 | ✅ 保留（v5.1 精简） | ✅ 保留 | ⚠️ 深度精简/移除守卫 |
| 事件折叠迁移 | ❌ | ❌ | ✅ |
| slimapi 依赖 | Plan A/B | Plan A/B | Plan A/B（无额外） |
| 落地风险 | 中（L1 高） | 中 | **高**（token 需原型） |
| 用户体验损失 | 中 | 中 | **高** |

---

## 6. 决策点（需用户拍板）

### 6.1 关键抉择

| # | 决策点 | 选项 | 影响 |
|---|---|---|---|
| 1 | **档位选择** | 保守 / 中度 / 激进 | 决定整体移除范围 |
| 2 | **Plan A vs Plan B（replay）** | A（slimapi 加端点）/ B（POST /sync/history） | slimapi 是否需改 |
| 3 | **TOFU 去留**（仅激进档） | 移除（系统 CA）/ 精简（合并文件）/ 保留 | 是否有自签证书场景 |
| 4 | **token stream 激进度**（仅激进档） | 深度精简守卫 / 完全模仿 opencode 直接消费 delta | 需原型验证后者 |
| 5 | **multi-host 去留**（激进档） | 移除（单 URL）/ 保留单 host 选择 | 多服务器用户 |
| 6 | **事件折叠迁移**（激进档） | 采纳（Flow.scan）/ 不采纳 | 再砍 2-3 FSM |
| 7 | **OwnershipGate 精简程度** | ~200 行 / ~300 行 | 并发持有场景确认 |

### 6.2 建议优先确认

1. **档位选择**（#1）——决定后续所有评估的边界
2. **Plan A/B**（#2）——决定 slimapi 是否需协作（影响跨项目协调）
3. **TOFU + token stream**（#3,#4）——仅激进档需答，但有高风险原型验证需求

### 6.3 不替用户做的决定

本评估**仅提供决策材料**。所有"建议"列均为客观证据归纳，最终去留由用户基于以下权衡决定：
- 向 opencode web 范式靠拢的程度（产品定位）
- 可接受的用户体验损失边界
- 跨项目协作意愿（slimapi 改动）
- 原型验证投入意愿（token stream 激进档）

---

## 7. 相关文档

- `docs/archive/state-machine-simplification-v5.1-lean.md` — Phase A 整合文档（v5-lean 修订版）
- `docs/archive/state-machine-simplification-v5-lean.md` — v5-lean 原稿（历史）
- `.omni-orch/reports/v5lean-feasibility-ocdroid.md` — ocdroid 可行性调研
- `.omni-orch/reports/opencode-fsm-study-summary.md` / `opencode-fsm-study-full.md` — opencode 设计调研
- `/home/mar/personal_projects/oc-slimapi/.omni-orch/reports/v5lean-feasibility-slimapi.md` — slimapi 影响评估（跨项目只读）

---

## 附录 A：功能全貌调研来源

本评估的功能清单基于 2 个并行 explorer 只读调研：
- **exp-1**（ses_03cecc8a1ffe9E1zCEYLoLXJE5）：枚举 ocdroid 全量产品功能（~90 项，8 大类）
- **exp-2**（ses_03ceca222ffeI2qOoRvBvHue9c）：确认 v5-lean 保留项实现现状（6 项深核 + 5 项额外发现）

关键代码位置见各决策表"当前作用"列引用。完整调研产物存于对应 session。
