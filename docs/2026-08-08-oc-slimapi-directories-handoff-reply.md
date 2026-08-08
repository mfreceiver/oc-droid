# ocdroid 集成 `GET /slimapi/directories` — 回执（ocdroid → oc-slimapi）

> **状态**：ocdroid 侧对交接提示词的正式回执（doc-only，未改项目代码）。
> **日期**：2026-08-08。
> **回应对象**：oc-slimapi 项目组（交接文档 `docs/ocmar/reports/2026-08-08-ocdroid-directories-handoff-prompt.md`）。
> **共读契约**：oc-slimapi `docs/specs/v2-contract.md` §2 + envelope 小节 + §7；`docs/specs/CLIENT_CHANGES.md`「全局 directory catalog」。
> **ocdroid 侧现状基准**：read-only 代码核实（2 路 explorer 并行），证据见各节 `file:line`。

---

## 0. TL;DR

| 项 | ocdroid 结论 |
|---|---|
| 功能定位 | **非独立切换器**，亦非改造首页"已连接的项目"行；而是在"添加项目目录"入口旁新增"查看既往项目"（详见 §1） |
| 5 个待确认问题 | 已答（§2），无新增阻塞 |
| legacy 模式 | **隐藏该功能**（§3.3）；不上方案 A/C 当主源 |
| 对 oc-slimapi 侧 | **无阻塞、无需额外动作**；端点契约与文档完备，ocdroid 进入实施 |

---

## 1. 集成定位（澄清可能的误解）

本端点在 ocdroid 侧的角色**不是**交接文档默认设想的"项目切换器"，而是：

> 在首页"添加项目目录"入口旁，新增"查看既往项目"——列出服务端已知的全部 directory，按 `lastUpdated` DESC 排序，**当前已连接的项目标记并禁选**（防重复添加），未连接的一键注册进体系。

三条数据通路正交，端点只覆盖其一：

| 通路 | 数据源 | 端点角色 |
|---|---|---|
| 既往项目（服务器已知） | `GET /slimapi/directories`（slim） | **本端点唯一用途** |
| 全新目录（从未建会话） | 既有浏览式 file picker | **与端点无关**——端点"被动发现局限"由此路径补位 |
| 本机历史地板 | 既有 `recentWorkdirs` MRU | 合并地板（每次端点成功返回并入） |

→ 交接文档集成约束第 5 条"全新 workdir 不可见"在 ocdroid 侧**不构成问题**：浏览式新增路径本就保留，专门覆盖该场景。

---

## 2. 对 5 个待确认问题的答复

### Q1　项目卡片展示字段

| 字段 | 取舍 | 理由 |
|---|---|---|
| `directory` | 必显（basename headline + 全路径 supporting） | 主标识，维持现有行约定 |
| `lastUpdated` | **必显**（相对时间） | 既是排序键，也是"上次活跃"提示 |
| `title` | 可选次要提示 | winner 标题作辅助信息，null 时回退 |
| `activeRootSessionCount` | 可选小徽标 | "N active" 给活跃度直觉 |
| `archivedRootSessionCount` | 不单显 | 噪音 |
| `archivedOnly` | 不作文本字段 | 见 Q2 |

### Q2　archivedOnly 的 UI 处理

**基本不专门处理**。既往项目列表里，全归档的项目仍是合法的"既往项目"，不禁选、不隐藏；靠 `activeRootSessionCount = 0` 自然表达"休眠"。无需独立的弱化/折叠/可配置机制。

### Q3　降级策略组合

- **slim 模式（主路径）**：`/slimapi/directories` + 404 sticky flag（`supportsSlimDirectories`，见 Q4）。
- **legacy 模式**：**隐藏功能**（§3.3），不以方案 A 或方案 C 当主源。
- **方案 C（`recentWorkdirs` MRU）作降级/离线地板**：服务器列表与 MRU 为**两条独立数据源**（不互灌）；**仅在用户实际选中某既往项目时**经 `connectWorkdir`→`addRecentWorkdir` 并入（已去重 + cap 30）。不做批量灌入（会驱逐真正最近访问的本机条目、混淆"服务端已知"与"用户本机碰过"两语义）。

### Q4　capability probe vs feature flag

**一次性 404 probe + sticky flag**，复用 ocdroid 既有的 `ServerCompatProfile` 模式（与 `supportsSlimStatus` / `useSlimCatalog` / `supportsSlimQuestions` 同列）：

- 新增 `supportsSlimDirectories`，默认 `true`（fail-open）；
- 首次 404 + body `code=="thin_route_not_found"` → 翻 sticky `false`；
- 传输错误（503/timeout）**不**翻 flag；
- 结果随连接生命周期在内存 sticky，不每连接重复探；
- **不引入 feature flag 系统**（ocdroid 无 Firebase RemoteConfig / 运行时 BuildConfig flag 体系，为单端点新建属过度工程）。

### Q5　本地 directory 列表持久化

**复用既有 `WorkdirPrefs` recent-workdirs MRU，绝不引入 DataStore。**

- 存储 = **EncryptedSharedPreferences**（per-fingerprint key `recent_workdirs_<fp>`，cap `MAX_RECENT_WORKDIRS=30`，`WorkdirPaths.normalize` 去重）。
- **纠正交接文档 Q5 的"DataStore?"提法**：ocdroid 项目规范**明确禁止 DataStore**（`SettingsManager.kt:3` 注释 `project spec forbids switching to DataStore`），全用 EncryptedSharedPreferences；Room 已移除。故 DataStore 选项不适用。
- 合并规则：**仅用户实际选中某既往项目时并入**（`connectWorkdir`→`addRecentWorkdir` 已承担）；**不批量灌入**。服务器列表与 MRU 为两条独立数据源（不互灌）。
- 归一化对齐：需确认端点归一化（"去尾斜杠，根 / 保留"）与 ocdroid `WorkdirPaths.normalizeDirectory` 语义一致（核实显示一致，实施时验证）。
- 排序：服务器列表可用时按 `lastUpdated` DESC（端点已排序，直消费）为主渲染源；离线/降级回退 MRU 顺序。

---

## 3. ocdroid 侧关键决策

### 3.1　入口位置

- **最外层**、原"添加项目"按钮（`CreateNewFolder` IconButton，位于首页 Attached Project section header）**左侧**，新增一个"查找/浏览+文件夹"语义图标（Material 候选 `FolderSearch` / `ManageSearch`，最终由 UI 评审定）。
- 点击打开 `AppBottomSheet`（遵循 `docs/specs/ui-style-spec.md` 三层规则 B 层：列表/预览）列出既往项目。

### 3.2　"已连接项目"判定集

- **= 首页"已连接的项目"列表（`workdirGroups`）当前显示的项**。
- 纯客户端集合比对：端点返回的 `directory ∈ workdirGroups` 的 key → **禁选 + 标记"已添加"**，防止重复添加。
- 选中未连接项 → 走既有 `connectWorkdir`（注册进体系，不强制建会话）。

### 3.3　legacy 模式隐藏该功能

**条件门控**：仅 `HostProfile.slim == true` 显示图标（slim=true 但 sidecar <v1.2.0 时图标仍在，sheet 降级渲染 MRU 地板并标注"本机最近项目"）；`slim == false`（legacy）**隐藏整个功能**。

理由：

1. **功能前提是服务端全局目录发现**——招牌价值是跨设备、服务器权威、按真实 `lastUpdated` 排序的"所有项目"。legacy（直连 opencode、无 sidecar、无全局 catalog）下该前提不成立。
2. MRU 是"本机访问顺序"，**非 `lastUpdated`**；同一个图标底下给"最近访问"而非"最近活跃"，排序与功能语义不符，易误导。要做就得换标签，同图标两套语义会很乱。
3. 方案 A（`/experimental/session` 自聚合）能拿到真 `lastUpdated`，但代价是全新 `/experimental/` 依赖 + 自实现聚合 + 版本门控——**为一个降级路径付全价，不划算**。

legacy 用户退路：原有浏览式选目录（找已知路径）+ 首页"已连接的项目"列表（看已接入的）。"跨设备既往项目一键找"本就是 slim 模式的增量价值。

> 若将来需要 legacy 平价，MRU 顶上是**最便宜的补丁**（数据源已存在），但 ocdroid 不预先建设。

---

## 4. 集成约束确认（接受 oc-slimapi 侧约束）

- **fallback 纪律**：仅 404 `thin_route_not_found` 或确认非 slim 才降级；**绝不**对 503 / 413 / timeout / 版本错误（400）/ 鉴权错误降级——走重试。
- **total failure**（503 `upstream_unavailable`）：保留既有项目列表 + 重试，**不清空 UI、不据此推断"无 workdir"**。
- **discoveryComplete**：`true` 才 replace 本地列表；`false`（几乎不发生）保留本地不 replace。
- **被动发现局限**已知悉，由浏览式新增路径补位（见 §1）。
- **capability 探测**：不靠版本号，用一次性 404 probe，结果缓存。

> **`transform_busy` / `Retry-After` 处理**：ocdroid **已有** `retryAfterHeaderToMs`（`OpenCodeRepository.kt:1309`）+ 3 次 `transform_busy`+`Retry-After` 重试范式（`SessionGateway.kt:141-175`；SSE 另有独立指数退避）。本端点**直接复用**该范式——`transform_busy` 按 `Retry-After` 重试不降级，与贵侧契约一致，**无 gap**。

---

## 5. 对 oc-slimapi 侧的反馈

- **无阻塞**。端点契约清晰，文档完备，降级决策树可直接落地。
- 唯一可选事项：贵侧 `b57daa1` 文档补充未 push——不阻塞 ocdroid（已读本地副本）；是否 push 由贵侧自决。

---

## 6. 下一步

- ocdroid 侧进入实施（实施计划另文，含改动文件清单与测试矩阵）。
- 实施完成后回传：改动文件清单 + 测试矩阵（覆盖 slim 正常 / 404 降级 / 503 重试 / 已连接禁选 / legacy 隐藏 / 离线 MRU 地板）。
