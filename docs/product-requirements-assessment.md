# 产品需求评估 · 移动端重构方向

> 基于 exp-5（Files 现状）、exp-6（oc web workdir）、exp-7（SSE/流量/刷新）、exp-8（会话切换/通知）四份代码级审计。
> 本文是**评估与方向**，非最终执行方案。需求收齐 + 关键决策拍板后，再产出分阶段执行方案。

---

## 0. 当前实现的关键事实（决策依据）

**数据/同步层（exp-7）：**
- SSE：无 keepalive、无 `Last-Event-ID` 续传、无 catch-up。指数退避重连 1s→30s，无 jitter 无上限。
- 生命周期：**几乎无**。唯一生命周期挂钩是 `MainActivity` 的 `repeatOnLifecycle(STARTED){ testConnection() }`，且带 **30s 节流**。无 `ProcessLifecycleOwner`、无 `ON_STOP/ON_START` 钩子。后台不取消 SSE（`viewModelScope` 存活），但**半开 socket 检测滞后**，快速回前台（<30s）**完全不重新同步**。
- **缓存层：完全没有**。无 Room、无 DataStore、无 OkHttp HTTP Cache、无图片磁盘缓存。进程死即全丢，冷启动全量重拉。
- 流量三大热点：
  1. **流式期间三路冗余拉消息**——SSE `message.updated` + 2s busy 轮询 + 发送后双刷新（0ms+1200ms），而 `message.part.updated` 增量已能流式渲染。轮询与 `message.updated` 全量拉取基本冗余。
  2. 前台每次（≥30s）`testConnection` 全量重拉 sessions+agents+providers+questions+commands+当前消息，无 ETag/HTTP 缓存。
  3. 图片：data-URI base64 **每次重组都重新 decode**（无缓存）；HTTPS 图片顺序预取、无并行、无磁盘缓存。
- 手动刷新：**仅一处**——聊天顶栏状态点 → 服务器对话框 → "刷新"按钮。无 pull-to-refresh。staleness 时用户只能这么恢复或杀进程重开。

**会话切换（exp-8）：**
- 状态层 **~70% 就绪**：`openSessionIds` MRU 列表（≤8、持久化）、`unreadSessions`、`closeSession`/`selectSession` 已接好。
- 现状是 **下拉菜单**（点开才见，280×360dp），不是常驻 tab 条。
- 导航是 swipe-only `HorizontalPager`（无底部 nav、无返回栈）。

**通知（exp-8）：**
- **零基础设施**。无 NotificationManager/Channel/权限/Service/WorkManager/生命周期 hook。SSE payload 已带 sessionId/permissionId/questionId，但"事件→系统通知"是 100% 绿地。

**文件功能（exp-5/6）：** 见 `files-redesign-staging.md`。

---

## 1. 逐项评估

### 重要项

#### #1 + #6 高性能与原生 / 避免套壳
- **可行性**：✅ 高。全 Compose，不引入 WebView 套壳。
- **策略**：聊天 markdown 已定 mikepenz（`v2-redesign-plan.md`）；Files 页 markdown 也换原生（`files-redesign-staging` 偏好已锁）。
- **困难**：超大文档性能（mikepenz 比 WebView 重渲染略慢）；代码高亮靠 `dev.snipme:highlights` 补足。
- **代价**：中。

#### #2 移动端最大化效率（屏幕适配 + 操作便捷）
- **可行性**：✅ 但**需求过宽**——需拆成具体项才能评估。
- **现状基础**：已有 `PhoneLayout` / `TabletLayout` / `LandscapeSplitLayout` 三套布局（exp-8）。
- **待具体化**（这是本项的主要"待明确"）：屏幕适配是否指字号随系统缩放、横竖屏切换、平板分栏比例？操作便捷是否指单手可达（发送键位置）、手势返回、减少切页层级、长按菜单？**请补具体痛点清单**。

#### #3 近期会话标签页切换（仿 oc web tab strip）
- **可行性**：✅✅ 很高，**70% 已就绪**。
- **策略**：把 `ChatTopBar` 里 `SessionDropdownRow` 的下拉菜单（行 417–507）换成常驻横向 `LazyRow` tab 条，复用现有 `openSessions`/`unreadSessions`/`onSelectSession`/`onCloseSession` 管线。
- **困难**：① tab 条占垂直空间（与 #8 去 tab、#2 最大化显示 有张力——见 §2）；② title 槽 `widthIn(max=240dp)` 在 TopAppBar 内，tab 条宜放**第二行**。
- **代价**：低（纯呈现层）。

#### #4 按需加载减流量
- **可行性**：✅ 高，目标清晰（exp-7 已定位三大热点）。
- **策略**（按收益排序）：
  1. **砍掉 2s busy 轮询**——`message.part.updated` 增量已覆盖流式渲染。
  2. **SSE `message.updated` 改 debounce/throttle**（如 500ms 合并），而非每事件全量拉。
  3. 去掉发送后 1200ms 双刷新。
  4. **OkHttp `Cache` + ETag/`If-None-Match`**——sessions/agents/providers 等低频数据命中缓存。
  5. **Coil 替换 `HttpImageHolder`**——磁盘缓存 + 并行预取 + 内存淘汰。
  6. data-URI base64 **按 part id 缓存** decode 结果，避免每次重组重 decode。
  7. 非当前会话消息**懒加载**（仅当前会话拉消息）。
- **困难**：① ETag 支持依赖服务端是否返回 ETag（需验证 oc server 行为）；② Coil 接入是中等工程；③ 去轮询后流式鲁棒性要靠 SSE 增量保证（需验证 `message.part.updated` 覆盖所有场景）。
- **代价**：中。**最高杠杆**是前三项（几乎零成本砍掉大部分冗余流量）。

#### #5 后台返回数据最新
- **可行性**：✅ 高。
- **策略**：引入 `ProcessLifecycleObserver`：
  - `ON_STOP`（进后台）→ 断开 SSE（省流、避免半开 socket）。
  - `ON_START`（回前台）→ 重连 SSE + **catch-up**（重拉当前会话消息；标记其他 open 会话 unread）。
  - 去掉/绕过 ON_START 的 30s 节流盲点（后台回来一律 force 同步）。
- **困难**：① oc SSE **无 Last-Event-ID 续传**，catch-up 只能暴力重拉（无法精确补差）；② "其他 open 会话"在后台期间若收到 `message.updated` 会丢——只能回前台后整体标 unread 促用户点开重拉。**这是 oc 协议层面的限制**，非客户端可单独解决。
- **代价**：低-中。
- **与 #4 的张力**：见 §2。

#### #7 后台通知（错误/决策，不可靠即可，不接 FCM）
- **可行性**：✅（绿地，最小可行清晰）。
- **策略（最小可行）**：
  1. `POST_NOTIFICATIONS` 权限（Android 13+）+ Manifest。
  2. `OpenCodeApp.onCreate` 建 Channel（错误/决策两通道）。
  3. `ProcessLifecycleObserver` 翻"是否后台"标志。
  4. SSE 分支（`permission.asked`/`question.asked`/error）在**后台时**调 `NotificationManagerCompat.notify()`，前台时仍走 in-chat 卡片。
  5. 通知 tap → deep link 到目标会话（payload 已带 sessionId）。
- **困难/张力（核心决策点）**：用户要"不可靠即满足，不接 FCM"。但**真正后台投递需要进程存活**。两条路：
  - **A. 不上前台服务**：通知仅在进程存活于"最近任务"期间（后台几分钟内）可投递；进程被回收/划掉后失效。**符合"不可靠即满足"，零额外通知栏噪音。**
  - **B. 前台服务（dataSync）**：SSE 由 Service 持有，后台长期可靠；代价是**常驻通知栏条目**（系统要求前台服务必须有可见通知）。
  - #5 也有同样的"后台感知"诉求。**A 与 B 的选择同时决定 #5 的后台可靠性上限**（见 §2 决策 D1）。

### 不重要/去除项

#### #8 去掉对话页"会话/变更"tab
- **现状校对**：**本项目 Android 端当前并无 oc web 那种"会话/变更"双 tab**——它用 `HorizontalPager`（Chat/Sessions/Files/Settings），变更查看本就不在聊天页内。所以此项主要是**"确保不引入"该 tab**，并把变更/workdir 文件浏览收敛到**独立入口**（即 Files 页 + 会话变更入口）。
- **可行性**：✅。本质是**约束**而非开发项。
- **与文件功能的关系**：变更查看与 workdir 文件浏览的独立入口，正是 `files-redesign-staging.md` 的范畴。两者一并规划。
- **待明确**：会话级 diff（"当前 session 的变更"）独立入口放哪——是 Files 页加一个 tab，还是聊天页顶栏加按钮跳转？

#### #9 字体自定义（低优先）
- **可行性**：✅。预留配置空间即可。
- **策略（本期）**：在 `SettingsManager` 预留 4 个键（`fontCJK`/`fontLatin`/`markdownFontCJK`/`markdownFontLatin`），默认系统字体。`OpenCodeTheme` 读这些键组装 `FontFamily`（缺省=`FontFamily.Default`）。本期**只建脚手架**，不做选择 UI、不打包字体。
- **后续**：在系统字体 vs 打包字体间选择的 UI + 打包字体资源。
- **代价**：本期低（脚手架）；后续中（打包字体 + picker）。

---

## 2. 冲突与张力（需协调）

| 冲突 | 说明 | 协调方向 |
|---|---|---|
| **#4 省流 vs #5/#7 后台感知** | 后台持续 SSE 才能感知事件，但持续 SSE 耗流。 | 标准模式：`ON_STOP` 断 SSE，`ON_START` 重连+catch-up。后台通知仅"进程存活期"best-effort。靠 D1 决策定上限。 |
| **#3 tab 条占空间 vs #2/#8 最大化显示** | 常驻 tab 条吃垂直像素。 | tab 条做**单行紧凑**（仅标题+未读点+close），子 agent 时让位面包屑；去变更 tab 已腾出空间。 |
| **#5 catch-up 精度 vs oc 协议** | oc SSE 无续传，后台期间事件无法精确补差。 | 接受"回前台整体重拉/标 unread"近似；非客户端可单独解决。 |

---

## 3. 困难清单（按硬度）

| 难度 | 项 |
|---|---|
| 🔴 协议层 | #5 catch-up 精度受限于 oc SSE 无 `Last-Event-ID`；非客户端可单独解决，只能近似。 |
| 🟡 中等工程 | #4 接 Coil + OkHttp Cache；#7 通知绿地；#5 ProcessLifecycle 重构。 |
| 🟢 低 | #3 tab 条（呈现层）；#9 字体脚手架；#8 约束项；#4 前三项（砍轮询/debounce/去双刷新）。 |

---

## 4. 待明确事项（需你拍板）

> 见随附的问题清单。核心是 D1（后台策略，决定 #5/#7 上限）。

- **D1 后台策略**：不上前台服务（best-effort，进程死即失效）vs 前台服务（可靠，常驻通知栏条目）？
- **D2 process-death 即开**：要不要上 Room 快照（会话+消息本地持久化）让杀进程后秒开？还是接受冷启动全量重拉？
- **D3 #2 具体化**：移动端效率请补具体痛点（屏幕适配？操作便捷？指明屏幕/操作）。
- **D4 #3 tab 行为**：复用现有 `openSessionIds`（≤8 MRU）；close-X 是否要？长按菜单（archive/fork/delete）本期要不要？
- **D5 #9 字体**：本期只建配置脚手架（默认系统字体），picker 与打包字体延后——确认？
- **D6 #8 变更入口位置**：会话级 diff 独立入口放 Files 页 tab，还是聊天顶栏按钮跳转？
- **D7 范围与归档**：以上确认后，是合并进 `v2-redesign-plan.md`，还是另开 `mobile-architecture-plan.md`（含 SSE/缓存/通知/标签页）？

---

## 5. 建议的执行分组（待 D1–D7 定后细化）

- **P0 数据地基**（解 #4/#5 根因，高杠杆）：去 2s 轮询 + `message.updated` debounce + 去发送双刷新 + `ProcessLifecycleObserver`（ON_STOP 断/ON_START 连+catch-up）。
- **P0 缓存**：OkHttp Cache + Coil 图片缓存 + data-URI decode 缓存。（解 #4 流量 + 冷启动速度）
- **P1 呈现层**：tab 条（#3）+ 去/不引入变更 tab（#8）+ 字体脚手架（#9）。
- **P1 通知**：最小可行 best-effort（#7，按 D1）。
- **P2**：字体 picker/打包字体；Room 快照（按 D2）；#2 具体项（按 D3）。
- **并行**：文件功能重构（见 `files-redesign-staging.md`，待其范围定）。
