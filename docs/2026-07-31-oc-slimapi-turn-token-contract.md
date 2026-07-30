# oc-slimapi × ocdroid 跨项目协作契约：turn token 强 fence（实施前规格）

> **状态**：**实施前规格（pre-implementation spec）**——双方对齐后再开工。本文档是 oc-slimapi 与 ocdroid **双方共同阅读**的独立、完整契约。
> **日期**：2026-07-31。
> **bundle**：B-ocdroid-sm-20260730（批次 2 — 文档）。
> **权威来源**：方案 v3 §5（`docs/2026-07-30-ocdroid-state-machine-improvement-plan.md` §5.1–5.4）+ §3.1（两层 fence）。本文档把 v3 的紧凑叙述**扩写为面向双方的实施规格**，并对 v3 紧凑表述中存在的顺序歧义给出**精确定义**（见 §4.4、§B）。
> **读者**：① oc-slimapi 维护者（Python 中继层，负责发送字段 / commit point / 持久化 / 共享 registry）；② ocdroid 维护者（Kotlin 客户端，消费字段做因果 fence）。
> **范围**：**本文档只描述契约，不改任何代码。** ocdroid 侧消费解析**已就绪**（commit `0d572d2`，merged to main），只等 slimapi 发字段。
> **不可改约束**：上游 opencode 服务端**不可改**；所有 fence 由 slimapi（派生因果标识）+ ocdroid（消费归约）两侧完成，不依赖上游改动。

---

## 0. TL;DR（一分钟版）

| 项 | 结论 |
|---|---|
| 谁发字段 | **oc-slimapi** 在转发的 `session.digest`（携带 status）事件里附加 `turnIncarnation` + `turn` 两个字段。 |
| 谁消费 | **ocdroid** `SessionSyncCoordinator.handleSessionDigest` 解析为 `ServerRound(inc,turn)`（**已实现**，`0d572d2`）。 |
| turn 何时 +1 | slimapi **成功把 forward 请求送上上游传输层的那一刻**（请求已发出、在 await 响应之前）。见 §4。 |
| 连接级失败 | **不 +1**（轮次根本没开始）。 |
| 非 2xx 响应 | turn 已在送出时 +1（见 §4.3）→ 该 turn 为**计数的空轮次（hole）**，**不回退、不 decrement**。 |
| incarnation | slimapi 生命周期 epoch，**单独持久化**，跨 restart 单调；restart→bump。 |
| 缺字段怎么办 | **字段全部可选**。缺失→ocdroid 降级到 Tier-2 启发式确认门 + watchdog，系统正常工作（§9）。双方可分阶段上线。 |
| ocdroid 就绪度 | 解析 + 降级**已就绪并 merged**；不需要再改 ocdroid 代码即可联调（slimapi 一发字段即生效）。 |

---

## 1. 动机：为何需要服务端因果 fence

### 1.1 现状（legacy SSE 无服务端因果标识）

ocdroid 当前的状态权威来自上游 SSE 帧流（`session.status` / `session.digest` / `session.error`）与 REST 轮询（`GET /session/status`）。上游协议**不携带任何服务端因果/代际标识**：每一帧只有 `{sessionID, status}`，没有「这是哪一次执行轮次产生的事件」。

### 1.2 启发式确认门的局限（Tier-2，非因果）

ocdroid 在 P0-B 已实现 **Tier-2 启发式确认门**（confirmation gate）来挡住「同 identity 内、跨轮次的 stale status 帧覆盖」：

- POST 成功 → optimistic busy（claim）；
- incoming `IDLE` 且 claim 未被 server echo → **DROP + arm watchdog**；
- watchdog 在 `OPTIMISTIC_CONFIRM_TIMEOUT（~5s）` 后做一次 REST reconcile 自愈（非无限 DROP）。

**问题**：这是一个**启发式 + 超时自愈**机制，**不是因果精确**的 fence。它的因果判断依赖「optimistic claim 是否被 echo」+「5s watchdog」，本质是用 wall-clock 超时兜底，而不是数学上可比较的因果序。在 slimapi restart、跨通道反序、retry 期 stop 等场景，启发式会留下窗口或依赖超时自愈而非精确裁决（详见方案 v3 §1.1 反馈 #1「retry/退避期 stop 消失」）。

### 1.3 slimapi turn token = Tier-1 数学可证 fence

oc-slimapi 是位于 ocdroid 与上游之间的 **Python 中继层**，可观察所有 POST `/session/{sid}/prompt` 与 `/abort` 流量。因此 slimapi **天然是「轮次起点」的权威观察者**——它能在 forward 上游时派生一个**服务端侧的因果标识** `(turnIncarnation, turn)`，附加在转发的 status 事件上。

ocdroid 收到带 `(inc, turn)` 的事件后，用 `ServerRound(inc, turn)` 做 **lexicographic 严格单调比较**：

- `(inc,turn) < cur.serverRound`（lex）→ **DROP**（旧帧不复活）；
- `inc` advance（新 epoch）→ 重置该 scope 的 serverRound（restart 不冻结）；
- `inc < knownIncarnation[scope]` → **DROP**（旧 incarnation 帧不复活）。

这是**数学可证的因果 fence**（Tier-1），不依赖 wall-clock、不依赖超时自愈。它把「retry 期 stop 消失」从「5s watchdog 超时自愈」升级为「逐帧精确裁决」。

> **定位**：Tier-1（slimapi turn，强、因果精确）与 Tier-2（legacy 启发式 + watchdog，弱、超时自愈）**并存且分层**。slimapi 发字段时走 Tier-1；不发（或字段缺失）时自动降级 Tier-2。两者不冲突（§9）。

---

## 2. 术语与计数空间（双方共用）

| 术语 | 含义 | 权威方 |
|---|---|---|
| **`ServerRound(incarnation, turn)`** | 服务端权威**执行轮次身份**，lexicographic 比较（先比 incarnation，再比 turn）。**仅服务端源设置**。 | slimapi 派生、ocdroid 消费 |
| **`turnIncarnation`（incarnation / inc）** | slimapi **生命周期 epoch**：restart 跳变（bump），**per-scope**（不同 server group / slimapi instance 不可比）。 | slimapi（持久化） |
| **`turn`** | **per-`(serverGroupFp, sid)` 单调计数**：每个真正开始的执行轮次 +1。 | slimapi（registry） |
| **`ScopeKey`** | 计数空间边界 = `(serverGroupFp, endpointFp, slimapiInstanceFp?)`。incarnation high-water 按 ScopeKey 维度。 | 双方一致（§6） |
| **`serverGroupFp`** | 服务器组指纹（host/profile 身份）。ocdroid 现有概念。 | ocdroid 定义算法（§6） |
| **optimistic claim / `clientSeq`** | ocdroid **客户端乐观轮次**的独立计数空间。**从不与 server turn 比较**（M1 根因：计数空间分离）。 | ocdroid |

> **关键不变量（M1）**：`ServerRound.turn`（服务端空间）与 `OptimisticClaim.clientSeq`（客户端空间）是**两个永不比较的独立计数空间**。Tier-1 fence 只比较 `ServerRound`；optimistic 仅用 `serverEchoed` 标志与服务端 echo 关联（解 cross-channel reorder），而非数值比较。

---

## 3. 字段契约

### 3.1 哪些消息附加字段

slimapi 在**转发给 ocdroid 的实时 status 事件**上附加两个可选字段。**主交付通道 = `session.digest` SSE 事件**（digest 携带 `status`，是 ocdroid 今日解析 `(inc,turn)` 的唯一位置）。

> ocdroid 解析点（已实现，`0d572d2`，`SessionSyncCoordinator.kt:1018-1034`）：从 digest 的 **`properties` 对象**内读取 `turnIncarnation` 与 `turn`（与 `archived` / `deleted` / `lastError` 同层），**不**在事件顶层。slimapi 必须把字段放进 digest 的 `properties` 内，否则 ocdroid 解析不到（降级 Tier-2）。

### 3.2 字段语义

| 字段 | 类型 | 语义 | 缺失语义 |
|---|---|---|---|
| `turnIncarnation` | integer (≥0) | slimapi 生命周期 epoch。**per-scope**（按 ScopeKey）。跨 restart 单调（持久化）或 restart bump。 | 视为「无 incarnation 信息」→ 该事件不参与 inc fence（降级 Tier-2） |
| `turn` | integer (≥0) | per-`(serverGroupFp, sid)` 单调计数，**事件 ingest 时快照**进事件（§7.4）。 | 视为「无 turn 信息」→ 不参与 turn fence（降级 Tier-2） |

**配对规则**：`turnIncarnation` 与 `turn` **必须同时出现或同时缺失**。ocdroid 仅当**两者都非 null** 时构造 `ServerRound(inc, turn)`；任一缺失 → `serverRound = null` → 该事件走 Tier-2 确认门（`SessionSyncCoordinator.kt:1022-1028`）。

### 3.3 JSON 示例（session.digest，字段在 properties 内）

```jsonc
{
  "type": "session.digest",
  "data": {
    "sessionID": "01HQ...abc",
    "status": "busy",
    "properties": {
      "turnIncarnation": 7,
      "turn": 3,
      "lastError": null,
      "archived": 0,
      "deleted": false
    }
  }
}
```

- `turnIncarnation: 7` → 当前 slimapi 生命周期 epoch = 7；
- `turn: 3` → 该 `(serverGroupFp, sid)` 的第 3 个执行轮次；
- ocdroid 构造 `ServerRound(incarnation=7, turn=3)`，与该 scope 已知 serverRound 做 lex 比较。

**turn advance 示例（同 incarnation 内正常递增）**：
```
digest(busy,  inc=7, turn=3)   ← 第 3 轮开始
digest(busy,  inc=7, turn=3)   ← 同轮重复帧（lex 相等 → tie-break，§8.2）
digest(idle,  inc=7, turn=3)   ← 第 3 轮结束
[prompt forward → turn 4]
digest(busy,  inc=7, turn=4)   ← 第 4 轮开始（lex > 3，应用）
```

**incarnation bump 示例（slimapi restart）**：
```
digest(busy,  inc=7, turn=3)
[slimapi restart → inc bump 7→8]
digest(idle,  inc=8, turn=0)   ← inc advance → 重置该 scope serverRound（不冻结）
digest(busy,  inc=8, turn=1)
digest(busy,  inc=7, turn=3)   ← 旧 inc=7 迟到 → inc < known(8) → DROP（不复活）
```

### 3.4 类型与边界规范

- 两字段均为 **JSON integer**，≥ 0，64-bit 范围内（ocdroid 侧 `Long`）。
- **不可**用字符串、浮点、null-意为-0 等歧义编码。`null` 表示「显式无值」（与 absent 同义，均降级）。
- slimapi **不应**在 `session.error`、`message.part.*` 等非 status 事件上附加这两个字段（ocdroid 不在那里解析；附加无害但无契约意义）。

---

## 4. turn increment commit point（**关键**）

> 本节是契约的技术核心，也是评审重点。v3 §5.2 紧凑地把「before await」与「非 2xx 不 increment」并列，存在顺序歧义；本节给出**可实现的精确定义**并显式闭合该歧义（§4.3–4.4）。

### 4.1 commit point 定义

**`turn[(serverGroupFp, sid)] += 1` 发生在：slimapi 成功把 forward 请求送上上游传输层的那一刻**——即上游连接已建立、请求行/头/体已发出（request is on the wire），**在 `await` 上游完整响应之前**。

涵盖两类 forward：
- `POST /session/{sid}/prompt`（用户发消息 → 新执行轮次）；
- `POST /session/{sid}/abort`（用户中止 → 见 §4.5，abort 也 increment）。

```
slimapi 收到 POST /session/{sid}/prompt（来自 ocdroid）
  │
  ├─ ① 准备上游 forward（解析、鉴权、构造上游请求）
  │
  ├─ ②【commit point】成功把请求送上上游传输层
  │      ├─ turn[(serverGroupFp,sid)] += 1   ← 在此刻 increment
  │      ├─ 记录 newTurn = turn（用于 stamp 本轮所有事件）
  │      └─ 此后 relay 的本 (serverGroupFp,sid) status 事件都 stamp newTurn
  │
  ├─ ③ await 上游响应（在此期间上游可能经 SSE 回 busy → stamp newTurn）
  │
  └─ ④ 拿到响应 / 连接结果（决定如何回 ocdroid，见 §4.2-4.3）
```

### 4.2 连接级失败 → **不 increment**

若 §4.1 的 ①–② 失败——**无法建立上游连接 / 无法发出请求**（DNS、TCP/TLS 失败、send error、上游不可达）——则 **commit point 从未到达，turn 不 +1**。

> **send error 的边界（post-send 重置归类）**：send error 指**请求未完整发出**的失败（发送中途断开）。若请求**已完整到达上游传输层**（commit point 已过）后连接被重置 / 上游崩溃 / 无 HTTP 响应，则属 §4.3 的 **hole**（turn 已 increment），不回退。判定标准：commit point（§4.1 ②）是否到达——到达则 hole，未到达则不 increment。

理由：轮次根本没开始，没有因果实体可标识。ocdroid 收到 slimapi 的错误响应，**不写 optimistic busy**（POST 失败无回滚对象，方案 v3 §10），双方状态一致：turn 没动、ocdroid 没占 claim。

### 4.3 上游非 2xx 响应 → **commit 已发生，该 turn 为计数空轮次（hole），不回退**

这是 v3 紧凑表述「非 2xx → 不 increment」需要**精确化**的地方：

- 非 2xx 是**响应状态**，只能在 §4.1 的 ③ await **之后**才知道；
- 但 increment 已在 ②（送出请求时）发生，且**可能已有事件被 stamp 了 newTurn**（例如上游在返回非 2xx 前已经经 SSE 回过 busy）；
- 因此**不能**在得知非 2xx 后「撤销 increment」——那会破坏 monotonicity，并让已 stamp 的事件变成孤儿（turn 号对不上）。

**契约定义**：非 2xx → increment **已经提交**，该 turn 是一个**计数的空轮次（hole）**：没有成功 generation，但 turn 号已占用。**禁止 decrement / 回退**（v3「允许 turn 空洞，禁 unsafe decrement」）。下一个成功 forward 从 `turn+1` 继续。

**安全性**：
- monotonicity 保持（hole 不破坏单调）；
- ocdroid 的 lex 比较**天然处理 hole**（没有事件 stamp 该 turn，或事件 stamp 了但代表「本轮失败」——两种情况 lex 比较都正确）；
- 不需要任何 decrement 逻辑（最简、无竞态）。

> **与 v3 §5.2 的关系（闭合说明，见 §B）**：v3 把「连接错 / 非 2xx」并列写作「不 increment」。本契约**拆分**二者：连接级失败（pre-dispatch）不 increment；应用级非 2xx（post-dispatch）**已 increment，产生 hole**。两者都 decrement-free。这一拆分是**支持「事件在响应前就 stamp turn」所必需的**（见 §4.4），是把 v3 紧凑叙述落地为无竞态实现的关键精确化。

### 4.4 为何 increment 必须在 await 前（不能等 2xx 再 increment）

Tier-1 fence 的全部价值在于**精确裁决在途轮次期间到达的事件**。典型场景（cross-channel reorder）：

```
t0  ocdroid POST /prompt
t1  slimapi forward 上游（② commit, turn→3）
t2  上游经 SSE 回 busy      ← slimapi stamp inc=7,turn=3 转发给 ocdroid
t3  ocdroid 收到 busy(7,3)  ← Tier-1 精确知道这是第 3 轮的 busy
t4  上游 HTTP 200 回到 slimapi（await 完成）
```

若 increment 推迟到 t4（拿到 2xx 后），则 t2 的 busy 事件**无法被 stamp 正确的 turn**（此时 turn 还没 +1）——fence 在最关键的「在途事件」窗口失效。因此 **increment 必须在 forward 送出时（②）**，这也决定了 §4.3「非 2xx 产生 hole」是唯一自洽选择。

### 4.5 abort 的 turn 语义

`POST /session/{sid}/abort` 的 forward **同样在 commit point increment turn**（与 prompt 同）。理由：

- abort 产生**终态 status 事件**（busy→idle 或 busy→error），需要被 fence 在被中止轮次的事件**之后**；
- 若 abort 不 increment，则中止后的 idle 与被中止的 busy **同 turn**，只能靠非因果的单调钟 tie-break 裁决（弱）；
- abort increment → 中止后的终态事件 stamp 更高 turn → lex 严格大于被中止轮次的事件 → **stale busy 不复活**（强、因果精确）。

```
digest(busy, inc=7, turn=3)    ← 第 3 轮
[abort forward → turn→4]
digest(idle, inc=7, turn=4)    ← abort 终态（lex > 3，应用，清 claim）
digest(busy, inc=7, turn=3)    ← 旧第 3 轮迟到 stale busy → lex 3<4 → DROP（不复活）✓
```

> **联调确认点**：abort increment 后，被中止轮次的迟到 busy/idle 帧必须被 DROP（上方示例）。这是 §11 验收场景之一。

### 4.6 turn 计数的不变量（可证明）

1. **per-scope monotonic（不减）**：`turn[(serverGroupFp,sid)]` 在其生命周期内**只增不减**；失败不回退，无 decrement。
2. **per-(serverGroupFp,sid) 独立**：不同 sid 的 turn 互不影响；同 sid 在不同 serverGroupFp 下也是不同计数（ScopeKey 隔离）。
3. **hole 允许**：turn 序列可出现空洞（如 1,2,3,5——4 是非 2xx hole）；ocdroid 不假设连续。
4. **单次 forward 恰好 +1**：一次 forward（prompt 或 abort）对应恰好一次 increment（成功送出时）；不会 +0（除非连接级失败）也不会 +2。
5. **incarnation advance 不自动重置 turn 计数器（二者解耦）**：inc bump 与 turn 计数器是两个独立机制——bump incarnation **不会**触发 turn 归零。turn 计数器在**单次 incarnation 内**单调不减（失败不回退，无 decrement）。「重置」只发生在 ocdroid 侧（见 §5.4、§8.2：ocdroid 见 inc advance → 清空该 scope 的 **已知 serverRound 比较基线**）。

> 不变量 5 的澄清（防误读）：slimapi 的 turn 计数器与 incarnation 是解耦的——bump incarnation 不会归零 turn。**跨 incarnation（restart）turn 计数器是否持久化是 §7.3 的策略选项**（持久化继续累加 / 不持久化 restart 后归零——**两种都正确**，因为 incarnation bump 会使 ocdroid 侧重置 serverRound 基线，旧 turn 自然过期）。ocdroid 侧「重置」指的是**清空客户端记录的 serverRound 比较基线**（新 epoch，旧基线不可比），**不是**要求 slimapi 把 turn 计数器归零。

---

## 5. incarnation 持久化与 restart 语义

### 5.1 incarnation = 生命周期 epoch

`turnIncarnation` 标识 slimapi 的「一代生命周期」。同一次 slimapi 进程生命周期内 incarnation 恒定；**restart（进程重启 / 容器重建 / worker 重生）→ incarnation bump**（跳变到一个更大的值）。

作用：让 ocdroid 区分「这是同一个 slimapi 实例的后续事件」vs「这是一个新 slimapi 实例的事件」。新 incarnation = 新 epoch = 旧 epoch 的事件**全部过期**（DROP，不复活）。

### 5.2 持久化要求（跨 restart 单调）

- incarnation **必须单独持久化**（独立于 turn registry 的存储），写在 slimapi 启动时可恢复的持久层（文件 / KV / DB）。
- 每次启动：读取上次持久化的 incarnation，**bump**（如 `inc = persisted + 1`，或基于单调时钟/部署版本派生），写回持久层，作为本生命周期的 incarnation。
- **保证**：跨 restart **单调不减**。即 restart 后的 incarnation **严格大于** restart 前。这是「旧 inc 帧 DROP」正确性的前提。

### 5.3 restart bump 策略（二选一，需明确）

slimapi 选其一并在 §10 对齐时声明：

- **策略 A（推荐）：`inc = persisted_last + 1`**。简单、保证严格递增、与 turn 计数器解耦。每次启动 bump 1。
- **策略 B：基于单调时钟/部署 epoch 派生**（如 `floor(boot_time_ms / 1000)` 或部署版本号）。需保证**严格大于任何历史 incarnation**（时钟回拨风险需规避）。

> 本契约**推荐策略 A**（最小惊讶、无时钟依赖）。若 slimapi 采用策略 B，必须在 §10 声明并论证单调性保证。

### 5.4 多 worker / 容器重建下的恢复

slimapi 可能多 worker / 容器化部署（proxy + global_hub，§7）。incarnation 持久化必须满足：

- **共享同一持久层**：所有 worker/容器从**同一个** incarnation 持久源读取/恢复（不能每个 worker 各自计数，否则 incarnation 不一致）。
- **并发启动原子性**：incarnation 的 read-bump-write 必须是**原子的**（文件锁 / CAS / 单 canonical writer），保证任意时刻只有一个 worker 持有给定 incarnation。若 proxy + global_hub 并发启动都读到 `persisted=7`、各自 bump 到 `8`，则两 worker 共享同一 incarnation → ocdroid 无法区分 → 跨 worker 的 stale 帧不被 incarnation fence 挡住。**推荐**：只有 proxy（单一 writer）在启动时 bump incarnation，global_hub 从 proxy 同步。
- **重建即 bump**：容器/worker 重建 = 新生命周期 = bump incarnation（从持久层读 last + 1）。
- **实例指纹**：ocdroid 的 `ScopeKey` 含 `slimapiInstanceFp?`（可选）。若部署多实例且 ocdroid 能区分实例，incarnation 按 `(serverGroupFp, slimapiInstanceFp)` 维度独立；若 ocdroid 无法区分实例，则 slimapi 侧必须保证全局唯一 incarnation（单实例语义）。**实例指纹来源需在 §10 对齐**。

**ocdroid 侧行为**（已就绪，§8.2）：见到 `inc > knownIncarnations[scope]` → **bump high-water + 重置该 scope 所有 entry 的 serverRound 为 null**（不冻结：reset 后该 scope 回到「无 serverRound 基线」状态，新事件正常建立基线，§1.2 的 restart 冻结问题被消除）。

---

## 6. serverGroupFp 来源（scope 身份对齐）

turn 与 incarnation 都 **per-`(serverGroupFp, sid)`**。因此双方必须对 `serverGroupFp` 的取值**完全一致**，否则同一逻辑会话会被算成两个 scope，fence 失效。

### 6.1 ocdroid client 现有算法（权威）

ocdroid 已有的 `serverGroupFp` 计算规则（`HostProfile.kt` / `EffectiveConnectionConfigResolver.kt:133-154`）：

```
serverGroupFp =
  profile.serverGroupFp.ifBlank { profile.id }      // 已保存 profile：用其 group 字段，空则用 profile id
  ?: "manual:<base_url>"                            // 手动连接（无 profile）：manual:<url>
```

- 已保存连接 profile：`serverGroupFp` 字段（用户可在设置里分组），**空则回退到 profile.id**（默认「未分组」= 自成一组）；
- 手动连接（无 profile）：`"manual:" + baseUrl`。

ocdroid 内部经 `@Named("currentServerGroupFp")` provider 注入，`currentServerGroupFp()` 返回当前 host 的 fp（`AppCore.kt:145`）。

### 6.2 推荐方案（二选一，需对齐）

| 方案 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| **A（首选）：header 透传** | ocdroid 在每个发给 slimapi 的请求上加 header（建议名 `X-Ocdroid-Server-Group-Fp: <fp>`），slimapi 直接读 header 作 serverGroupFp。 | 双方**零歧义**（ocdroid 是 fp 的定义方）；slimapi 无需复刻算法；profile 改动自动同步。 | 需 ocdroid 加一个 header（小改动，但**超出本文档 doc-only scope**，见 §10.2 标注）。 |
| **B（备选）：slimapi 自算** | slimapi 按请求的 host/profile 配置，用与 §6.1 **完全一致**的算法计算 serverGroupFp。 | ocdroid 零改动。 | slimapi 必须严格复刻 §6.1（含 `ifBlank{id}` 与 `manual:<url>` 回退）；profile/group 配置变更时双方需同步；易漂移。 |

> **契约推荐方案 A（header 透传）**，因为 serverGroupFp 是 ocdroid 的概念，由定义方透传最不易错。但 header 透传需要 ocdroid 侧加 header（属代码改动）——**本轮 doc-only 不实现**，留作 §10.2 的 ocdroid 待办项；在 header 透传上线前，slimapi 可先用方案 B 自算过渡。

### 6.3 一致性硬要求

无论选 A 还是 B：**对同一个逻辑连接，slimapi 算出的 serverGroupFp 必须与 ocdroid 的 `currentServerGroupFp()` 逐字节相等**。联调时必须验证（§11 场景「跨通道反序」隐含此校验：若 fp 不一致，turn 会被算到错误 scope，fence 静默失效）。

---

## 7. 共享 registry（M7）

slimapi 内部维护 turn 计数与 incarnation 的 registry。约束：

### 7.1 proxy ↔ global_hub 共享

slimapi 若由多个组件组成（如 proxy 转发层 + global_hub 事件聚合层），二者必须**共享同一个** `(serverGroupFp, sid) → turn` registry：

- proxy 在 commit point increment turn（§4.1）；
- global_hub 在**事件 ingest 时**读取该 registry 给事件 stamp turn（§7.4）；
- 两者操作的是**同一份**计数状态（共享内存 / 共享持久层 / 经内部 RPC 同步），不能各自维护副本。

### 7.2 并发安全

- turn increment 与 event stamp 可能**并发**（proxy forward 的同时 global_hub 在 ingest 上游 SSE）；
- registry 的 read（stamp）与 write（increment）必须是**线程/协程安全**的（原子读-modify-写，或等价的锁/actor）；
- **不变量（单调可见性）**：stamp 读到的 turn 是 registry 当前已提交的最大值（单调 acquire 语义）。关键 happens-before 链：`forward send（commit point increment）→ 上游接收 → 上游产生事件 → slimapi ingest 该事件并 stamp turn`。因此**该 forward 的 increment 必须在其触发的任何上游事件被 stamp 之前对 stamp 可见**（forward send → ingest stamp 之间，stamp 看到的 turn ≥ 该 forward 的 increment 值；不会因并发读到旧值）。

### 7.3 incarnation 独立持久化

- incarnation 的持久化（§5）与 turn registry 的存储**分开**；
- turn registry 可以选择不持久化（restart 后 turn 从 0 重新累加也可，因为 incarnation 已 bump——ocdroid 见 inc advance 会重置基线，旧 turn 自然过期）；**但 incarnation 必须持久化且单调**；
- 若 turn registry 持久化，restart 后 turn 继续累加（推荐，减少 hole）；若不持久化，turn 归零——**两种都正确**，因为 incarnation 的 bump 保证了 ocdroid 侧基线重置。

### 7.4 事件 ingest 时快照 turn（非 flush 时读当前）

> 这是 v3 M7 的明确要求，区别于「flush 时读当前 turn」。

slimapi 在**转发 status 事件给 ocdroid 时（ingest 上游事件的那一刻）**，从 registry 读取当前 `(serverGroupFp,sid)` 的 turn，**快照**进事件的 `properties.turn`。

**禁止**「先把事件缓冲，flush 时才读 registry 当前 turn」——因为 flush 时 turn 可能已被**后续** forward increment，导致事件被 stamp 成错误的（更大的）turn，破坏因果对应。

```
正确：上游 busy 事件到达 → 立即读 registry turn=3 → stamp turn=3 → 转发
错误：上游 busy 事件到达 → 缓冲 → (期间 prompt forward, turn→4) → flush 时读 turn=4 → stamp turn=4（错！这是第3轮的busy）
```

incarnation 同理：事件 ingest 时快照当前 incarnation（同一次生命周期内恒定，但快照语义保持一致）。

---

## 8. ocdroid 消费侧（**已就绪**）

> 本节描述 ocdroid 侧如何消费 `(inc, turn)`。**全部已实现并 merged to main**（commit `0d572d2`，P0-B），无需再改即可联调。

### 8.1 解析（SessionSyncCoordinator）

`SessionSyncCoordinator.handleSessionDigest`（`SessionSyncCoordinator.kt:1000-1034`）：

```kotlin
// §P0-B ITEM 3: forward-compat slim serverRound parsing — extract
// (turnIncarnation, turn) from props if present; null when absent
// (current behavior, falls through Tier-2 confirmation gate).
val inc  = (props?.get("turnIncarnation") as? JsonPrimitive)?.longOrNull
val turn = (props?.get("turn")            as? JsonPrimitive)?.longOrNull
val round = if (inc != null && turn != null) {
    ServerRound(inc, turn)
} else {
    null
}
applyStatusViaAuthority(sid, status, origin = EntryOrigin.SSE_SLIM, serverRound = round)
```

- 字段在 digest 的 **`properties`** 内读取（§3.1）；
- **两者都非 null** → `ServerRound(inc, turn)`；
- **任一缺失** → `null` → 走 Tier-2 确认门（降级，§9）。

### 8.2 authority reducer fence 规则（Tier-1）

解析得到的 `ServerRound` 经 `ApplyEvent(serverRound=…, scopeKey=…)` 进入纯 reducer（`AuthorityReducer`），按方案 v3 §3.1 执行：

| 条件 | 动作 |
|---|---|
| `serverRound == null` | 走 Tier-2 确认门（legacy/optimistic/REST，§9） |
| `(inc,turn) < cur.serverRound`（lex 严格小于） | **DROP**（旧帧不复活） |
| `op.serverRound != null && cur.serverRound == null` | **建立基线**（apply，**无 lex 检查**）。注意：若基线是被 Tier-2 事件清空的（而非 §8.4 REST / incarnation advance），此路径**不防 stale 帧**——见 §9 混用窗口。 |
| `inc > knownIncarnations[scope]` | **bump high-water + 重置该 scope 所有 entry 的 serverRound = null**（restart 不冻结），再应用本事件 |
| `inc < knownIncarnations[scope]` | **DROP**（旧 incarnation 帧不复活，B6） |
| `inc == knownIncarnations[scope]`，turn 内单调比较 | lex 决定 apply / DROP |
| `(inc,turn) == cur.serverRound`（等值） | **单调钟 tie-break**（`connectionMonotonicMs < prev` → DROP；`>=` → 覆盖，B9） |

> **lex guard 的前置条件（reducer 实现，`AuthorityReducer.applyEvent`）**：第 245 行 lex guard 仅当 `op.serverRound != null && prev?.serverRound != null` 时执行。`prev.serverRound == null`（基线被 Tier-2 事件/REST 清空）时 lex guard **整个跳过**，Tier-1 帧直接 apply。这是 §9「混用窗口」与 §8.4「REST 清基线窗口」的代码根因。

数据结构（`AuthorityState.kt`）：
```kotlin
data class ServerRound(val incarnation: Long, val turn: Long) : Comparable<ServerRound> {
    override fun compareTo(other) = compareValuesBy(this, other, { it.incarnation }, { it.turn })  // lex
}
data class ScopeKey(val serverGroupFp: String, val endpointFp: String, val slimapiInstanceFp: String? = null)
// AuthorityState.knownIncarnations: Map<ScopeKey, Long>   // §B6 per-scope high-water
```

### 8.3 计数空间分离（M1）

`ServerRound.turn`（服务端）与 `OptimisticClaim.clientSeq`（客户端乐观）**永不比较**。cross-channel reorder（server busy 先于 HTTP success 到达）由 `optimisticClaim.serverEchoed` 标志解决（echo 时置位，非数值比较）。这是 Tier-1 与 Tier-2 协作但不混淆的关键。

### 8.4 REST status 通道的 turn 处置（重要边界）

ocdroid 的 **REST `/session/status` 批量快照**（`ApplySnapshot`）路径**当前清除**被覆盖 sid 的 `serverRound`（方案 v3 §3.1 B9：REST 权威 snapshot 在其 covered scope 内清 serverRound，因 REST 是 epoch-terminal 权威、不保留旧 slim turn）。

**契约含义**：
- **turn 的主交付通道 = `session.digest` SSE 事件**（§3.1）；
- REST 批量 status **不需要**携带 turn（ocdroid 会清掉该 scope 的 serverRound，等下一次 slim digest 重建基线）；
- 若未来希望 REST status 也参与 turn fence，需要改 ocdroid 的 `ApplySnapshot`（**超出本文档 scope**，列为 §12 开放问题）。

> **fence 窗口提示（与 §9 同根因）**：REST 清 serverRound 后，在下次 slim digest 重建基线前，若一个迟到的 stale slim digest（有 turn）到达，因 `prev.serverRound == null` 会绕过 lex guard（§8.2）被 apply。这是与 §9 混用窗口**同一 reducer 路径**的不同触发源（REST 而非 Tier-2 SSE）。REST 是 epoch-terminal 权威，正常时序下其后的 digest 应更新；此窗口仅在**跨通道严重反序**（REST 后仍有旧 slim digest 迟到）时出现，属窄窗口。

---

## 9. 向后兼容（字段可选，分阶段上线）

`(turnIncarnation, turn)` **全部可选**。这是双方可以独立、分阶段上线的根本保证：

| slimapi 状态 | ocdroid 状态 | 行为 |
|---|---|---|
| 不发字段 | 已就绪（解析 + 降级） | ocdroid 解析为 null → **全量降级 Tier-2**（确认门 + watchdog），系统正常工作（= 今日行为）。 |
| 发字段 | 已就绪 | 带 turn 的事件走 Tier-1；不带的事件走 Tier-2（逐事件混用——**安全前提**见下方框，非无条件安全）。 |
| 发字段 | 旧版 ocdroid（不解析） | 旧版忽略未知字段 → 行为不变（Tier-2）。slimapi 发字段对旧版**完全无害**。 |

**分阶段上线建议**：
1. slimapi 先上线字段发送（无风险：旧 ocdroid 忽略，新 ocdroid 启用 Tier-1）；
2. 观察 Tier-1 fence 生效（旧帧 DROP、restart 不冻结）；
3. 无需协调「同时切换」——字段可选，天然灰度。

> **逐事件混用——有条件安全（重要）**：字段在 digest 事件级可选，ocdroid 对**每个事件**独立判断有无 `ServerRound`。但「混用」**并非无条件安全**——存在一个 fence 窗口，源于 reducer 实现：
>
> - **窗口**：若一个无 turn 的 Tier-2 事件把某 entry 的 `serverRound` 基线清空（`prev.serverRound → null`），随后到达一个**有 turn 但 turn 较低**的 Tier-1 帧，则 reducer 的 lex guard（`AuthorityReducer.applyEvent` 第 245 行，需 `prev.serverRound != null`）**被跳过**、确认门（第 263 行，需 `op.serverRound == null`）也**被跳过** → 该低 turn 帧直接 apply，**stale busy 复活**。这比纯 Tier-2 更弱（纯 Tier-2 下该帧至少走确认门）。
>
> | 时序 | 事件 | prev.serverRound | 结果 |
> |---|---|---|---|
> | 1 | `digest(busy, inc=7, turn=3)`（Tier-1） | null→(7,3) | 建立基线 |
> | 2 | `digest(idle, 无 turn)`（Tier-2，无 claim） | (7,3)→**null** | 应用，**基线被清空** |
> | 3 | `digest(busy, inc=7, turn=2)`（Tier-1 stale，2<3） | null（lex guard 跳过） | **apply，stale busy 复活** ❌ |
>
> - **安全前提（slimapi 侧约束）**：**slimapi 在同一 incarnation 内对所有 status 事件一致地携带 turn（不混用）**。只要同一 incarnation 内 turn 携带一致，事件 2 不会出现（要么全带 turn 走 Tier-1，要么全不带走 Tier-2），窗口不可达。
> - **推荐升级路径 = restart（inc bump）**：slimapi 升级（从「不发」到「发」字段）应通过 **restart** 完成，使 pre-restart 的无 turn 帧（旧 incarnation）被 incarnation fence 视为旧 epoch（`inc < known → DROP`），不与 post-restart 的有 turn 帧混入同一 incarnation。
> - **彻底闭合（OUT OF DOC-ONLY SCOPE）**：要消除此窗口需改 ocdroid reducer（如 Tier-2 事件不清已建立的 serverRound，或对基线被清空后的首个 Tier-1 帧记录 high-turn 水位）。**此为 ocdroid 代码改动 → 超出本文档 doc-only scope，escalate to omni**。当前契约以「doc 措辞收敛 + slimapi 不混用约束」为主。
>
> **结论**：字段缺失 → **全量降级 Tier-2 是安全的**（系统正常工作，= 今日行为）；逐事件混用 → **有上述窄窗口，需 slimapi 不在同 incarnation 内混用**。分阶段上线走 restart（inc bump）即可规避。

---

## 10. 双方职责清单（实施前对齐）

### 10.1 oc-slimapi 侧

| # | 职责 | 契约节 |
|---|---|---|
| S1 | 在转发的 `session.digest`（带 status）事件 `properties` 内附加 `turnIncarnation` + `turn`（同时出现/缺失） | §3 |
| S2 | 实现 turn commit point：forward prompt/abort **送出上游时**（await 前）`turn += 1` | §4.1 |
| S3 | 连接级失败（pre-dispatch）→ **不 increment** | §4.2 |
| S4 | 非 2xx（post-dispatch）→ **已 increment，产生 hole，不 decrement** | §4.3 |
| S5 | incarnation **单独持久化**，restart bump，跨 restart 单调；声明策略 A/B | §5.2-5.3 |
| S6 | 多 worker/容器共享同一 incarnation 持久源；声明实例指纹方案 | §5.4 |
| S7 | serverGroupFp：选方案 A（header 透传）或 B（自算），**声明并与 §6.1 对齐** | §6 |
| S8 | proxy ↔ global_hub 共享 `(serverGroupFp,sid)→turn` registry，并发安全 | §7.1-7.2 |
| S9 | 事件 **ingest 时快照** turn/inc（非 flush 时读当前） | §7.4 |
| S10 | 联调：配合 §11 验收场景（尤其 restart 不冻结、旧帧 DROP、abort fencing） | §11 |

### 10.2 ocdroid 侧

| # | 职责 | 状态 | 契约节 |
|---|---|---|---|
| C1 | `SessionSyncCoordinator` 解析 `(turnIncarnation, turn)` → `ServerRound` | **✅ 已就绪**（`0d572d2`） | §8.1 |
| C2 | authority reducer Tier-1 fence（lex DROP / inc advance 重置 / 低 inc DROP / 等值 tie-break） | **✅ 已就绪**（P0-B） | §8.2 |
| C3 | 缺失降级 Tier-2 确认门 + watchdog | **✅ 已就绪**（P0-B） | §9 |
| C4 | 联调验证 §11 场景 | ⏳ 待 slimapi 发字段 | §11 |
| C5 | （可选，方案 A）加 `X-Ocdroid-Server-Group-Fp` header 透传 serverGroupFp | 🔵 **本轮 doc-only 不实现**；留作后续小改 | §6.2 |

> **本轮 doc-only 边界**：ocdroid 侧 C1–C3 已就绪，**不需要任何代码改动即可联调**。C5（header 透传）是可选增强，属代码改动，**不在本轮 scope**——若评审/联调要求改 ocdroid 代码，**STOP 报 omni**（超出 doc-only scope）。

### 10.3 联调触发条件

- slimapi 完成 S1–S9（至少 S1 + S2 + S5 + S9 的最小可用集：发字段 + commit point + incarnation 持久化 + ingest 快照）；
- ocdroid 无需改动（C1–C3 已就绪）；
- 双方对齐 serverGroupFp 方案（S7）与 incarnation 策略（S5）后即可开始 §11 联调。

---

## 11. 验收 / 联调场景

每个场景给出「slimapi 行为 → ocdroid 期望」。ocdroid 期望侧已有对应单测（P0-B `AuthorityReducer` 穷举矩阵），联调验证端到端。

| # | 场景 | slimapi 行为 | ocdroid 期望 |
|---|---|---|---|
| V1 | **restart 不冻结** | slimapi restart → inc 7→8；restart 后发 idle/busy 带 inc=8 | 见 inc advance → 重置该 scope serverRound 基线 → 新事件正常应用（UI 不卡 busy） |
| V2 | **旧帧不复活（inc）** | restart 后，一个 inc=7 的迟到 stale 帧到达 | `inc=7 < known=8` → **DROP**（旧 busy 不复活） |
| V3 | **旧帧不复活（turn）** | 同 inc 内，turn=3 之后到达 turn=2 的 stale 帧 | lex `2 < 3` → **DROP** |
| V4 | **跨通道反序** | POST 后 server busy（经 SSE，stamp turn=3）先于 HTTP 200 到达 | busy(inc,3) 经 Tier-1 应用；HTTP 200 的 optimistic 经 `serverEchoed` 协调（非数值比较） |
| V5 | **abort fencing** | abort forward → turn→4；abort 终态 idle 带 turn=4；之后旧 turn=3 的 stale busy 到达 | idle(4) 应用；stale busy(3) → lex DROP（不复活） |
| V6 | **连接级失败不 increment** | forward 时上游不可达（连接失败）→ 回错 | turn 不变；ocdroid 收错不写 optimistic；下一次成功 forward turn 仍是原值+1（无 hole） |
| V7 | **非 2xx 产生 hole** | forward 送出（turn→4）→ 上游回 409/500 | turn=4 已占用（hole）；下一次成功 forward turn→5；ocdroid lex 比较正常（hole 不破坏单调） |
| V8 | **降级回退（字段缺失）** | slimapi 不发字段（或部分事件不带） | ocdroid 解析 null → 降级 Tier-2 确认门 + watchdog，系统正常（= 今日行为） |
| V9 | **逐事件混用** | 升级窗口内交替发「带 turn」「不带 turn」帧 | 逐帧独立裁决（带→Tier-1，不带→Tier-2），fence 不破坏 |
| V10 | **ingest 快照正确性** | busy 事件到达时 turn=3，期间 prompt forward（turn→4），但 busy 已 stamp 3 | ocdroid 收到 busy(3)——正确归属第 3 轮（若 flush 时才读会错成 4） |
| V11 | **serverGroupFp 一致性** | 双方对同一连接算出相同 fp（方案 A 或 B） | turn 归入正确 scope；fence 生效（若 fp 不一致，fence 静默失效——此场景用于校验对齐） |

---

## 12. 开放问题 / 待定（需双方在 §10 对齐时决定）

| # | 问题 | 选项 | 建议 |
|---|---|---|---|
| O1 | serverGroupFp 来源 | A=header 透传 / B=slimapi 自算 | A（但需 ocdroid 小改，本轮不实现，过渡用 B） |
| O2 | incarnation bump 策略 | A=`last+1` / B=时钟/版本派生 | A |
| O3 | slimapi 实例指纹 | 单实例语义 / 多实例按 `(serverGroupFp, slimapiInstanceFp)` | 视部署拓扑定；ocdroid `ScopeKey.slimapiInstanceFp` 已可选支持 |
| O4 | turn registry 是否持久化 | 持久化（少 hole）/ 不持久化（restart 归零，inc 保证正确） | 持久化（推荐，减少 hole） |
| O5 | REST `/session/status` 是否携带 turn | 否（当前 ocdroid 清 serverRound）/ 是（需改 ApplySnapshot） | 否（保持现状，turn 主走 digest） |

---

## 附录 A：字段速查表

```
session.digest 事件 (SSE, slimapi → ocdroid)
└─ data.properties
   ├─ turnIncarnation : integer (可选, ≥0, per-scope 生命周期 epoch)
   ├─ turn            : integer (可选, ≥0, per-(serverGroupFp,sid) 单调)
   ├─ status          : string  ("busy"|"idle"|"error"...)  [既有]
   ├─ archived / deleted / lastError                        [既有]
   └─ ...

配对规则：turnIncarnation 与 turn 必须同时出现或同时缺失。
缺失语义：ocdroid 解析为 serverRound=null → 降级 Tier-2 确认门 + watchdog。
```

## 附录 B：与方案 v3 §5 的差异 / 精确化说明

本契约以方案 v3 §5 为权威来源，但在以下点做了**面向实施的精确化**（不改变设计意图，只消除紧凑表述的歧义）：

1. **commit point 的「before await vs 非 2xx 不 increment」歧义**（§4.3-4.4）：v3 把「连接错 / 非 2xx」并列作「不 increment」。本契约**拆分**：连接级失败（pre-dispatch）不 increment；应用级非 2xx（post-dispatch）**已 increment，产生 hole**。理由：increment 必须在 forward 送出时发生以支持「在途事件 stamp turn」（§4.4），因此非 2xx 在响应阶段才得知时 increment 已不可撤销，唯一自洽选择是 hole + 禁 decrement。两者均 decrement-free，monotonicity 保持。
2. **字段位置**（§3.1）：v3 §5.2 的 JSON 示例把字段画在事件顶层；本契约按 ocdroid 实际解析（`0d572d2`）明确字段在 digest 的 **`properties`** 对象内。
3. **REST status 的 turn 处置**（§8.4）：v3 未展开 REST 批量 status 与 turn 的关系；本契约明确 REST ApplySnapshot **当前清除** serverRound，turn 主走 digest 通道。
4. **turn 跨 incarnation 不归零**（§4.6 不变量 5）：明确 slimapi turn 计数器跨 incarnation 持续累加；「重置」发生在 ocdroid 侧（基线），非 slimapi 侧（计数器）。

> 上述精确化均**不改变 v3 的设计意图**（强 fence + 计数空间分离 + 字段可选降级），是把紧凑叙述落地为可实施、无竞态规格的必要细化。若任一精确化与 slimapi 实施约束冲突，在 §10 对齐时提出修订。

---

*契约结束。下一步：双方按 §10 对齐 O1–O5 开放问题 → slimapi 实施 S1–S9 → 按 §11 联调验收。*
