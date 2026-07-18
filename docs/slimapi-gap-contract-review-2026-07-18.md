# slimapi 缺口契约草案 — 三专家复审汇总（2026-07-18）

> 草案：`docs/slimapi-gap-contract-v1-draft.md`  
> 评审：rev-gpt / rev-grok / rev-opus（同提示词，独立）  
> 结论：**三评一致 = 有条件通过**；完成必须修改清单后方可编码。

---

## 0. 快速决策

| 总立场 | rev-gpt | rev-grok | rev-opus |
|---|---|---|---|
| 通过条件 | 有条件通过 | 有条件通过 | 有条件通过 |
| 最大阻塞 | G1 生命周期、G5 可执行性、G7 安全表述、G8 | G1 sticky/clear、G5 dirty、G3 省流诚实、G8 | **G1 A-only 不成立**、abort 误报、pretty cause 脱敏、`/global/event` 前提 |

### 合成拍板（建议采纳）

| # | 议题 | 合成决定 |
|---|---|---|
| 1 | G1 A vs B | **A+B 或 A+session-less 兜底**；不可 A-only（opus 硬证据：sessionID optional） |
| 2 | G1 abort | **排除 `MessageAbortedError`** 等主动中止，禁止误亮 banner |
| 3 | G1 sticky | **跨 debounce sticky + 明确 clear**；error 到达时立即 flush |
| 4 | G7 | **v1 = soft**（有 directory 才校验）；strict 另议；**不宣称 soft=安全隔离** |
| 5 | G6 envelope | **接受 `{items,errors}`**；items **MUST 按 ids 定序**；建议始终 200+errors（部分失败） |
| 6 | G3-B | **v1 不做独立探针**；G3-A 须诚实「text 仍全量」；G3-B 可 deferred 占位 |
| 7 | G9 focus | **v1 不做服务端 focus**；客户端本地过滤 |
| 8 | G8 | **升 P0**（流式 cap） |
| 9 | shell | 分歧：gpt/grok 倾向显式 403；opus 倾向 ops+可选 flag。**合成：默认 deny-list 配置项（默认开启屏蔽 shell/PTY 路径），可运维关闭** |
| 10 | 落地顺序 | **先验证 session.error 是否上 `/global/event`（G1 go/no-go）→ G2 → G8 → G1 → G7-soft → G3/G4/G5 文档 → G6** |

### 修订后 P0/P1/P2

| 级 | 项 |
|---|---|
| **P0 实现** | G2 404/503 · **G8 流式 cap** · G1 error（含分类/脱敏/session-less）· G7-soft |
| **P0 文档** | G4 透传矩阵 · G5 可执行状态机 · 统一错误码表 · G3 探针契约（诚实表述） |
| **P1** | G6 multi-mid full |
| **P2 / 不做 v1** | G9 服务端 focus · G10 until · G11 around · G12 delta · G13 sequence · G3-B（除非压测） |

---

## 1. 三评对照（G1–G13）

| 项 | 共识 | 主要分歧 | 合成动作 |
|---|---|---|---|
| G1 | 必做；现网 DROP 确认 | A-only vs A+B；sticky 多久 | 重写：A+B 或 session-less 兜底；sticky+clear；排除 abort；脱敏 |
| G2 | 必做 404/503 | 错误 body 形状 | 结构化 code；re-raise HTTPException；allowlist→400 |
| G3 | 收敛 skeleton limit=1 | 是否 G3-B；header 是否要 | 不做 G3-B v1；改「数 KB」表述；可选头可删 |
| G4 | 文档必做 | shell 处理 | 完整透传矩阵；写路径禁止自动重试 MUST |
| G5 | 必做可执行状态机 | dirty 定义细节 | 伪代码+决策表；resync reason 全覆盖 |
| G6 | P1 做 | 全失败 HTTP 码 | envelope；定序；建议 200+errors |
| G7 | soft v1 | gpt 偏 strict 目标 | soft 入 v1；文档不夸大安全 |
| G8 | **升 P0** | — | read_with_cap 边读边 413 |
| G9 | **v1 不做** | — | 客户端过滤 |
| G10–13 | P2 | — | 保持 |

---

## 2. 必须修改清单（编码前文档必须先改）

来自三评 §6 交集：

1. **G1 重写**：session-less 错误；abort 过滤；pretty cause 脱敏；sticky+clear；error 时立即 flush；**先验证 `/global/event` 是否含 session.error**  
2. **G2**：404/400/503 分支表 + 结构化 body；禁止 Exception 吞 allowlist 400  
3. **G5**：可执行状态机（dirty 定义、无 updatedAt、resync reasons、去重、非 focus 策略）  
4. **G3**：删除「body≤数 KB」空承诺；注明 text 全量；G3-B v1 不做  
5. **G8→P0**：边读边 cap，非先缓冲  
6. **G7-soft**：query 校验源写死；header/query 冲突→400  
7. **G9 v1 不做**  
8. **G6**：MUST 定序；ids 校验码；部分失败语义写死  
9. **G4**：超时表、版本门闩说明、prompt_async 禁止自动重试、shell 策略  
10. **统一错误码表**  

---

## 3. 完整复审原文索引

| 评审 | session |
|---|---|
| rev-gpt | `ses_08b49109cffef7QkYnt6iA98fy` |
| rev-grok | `ses_08b490415ffePDUwDQNfw6qKTw` |
| rev-opus | `ses_08b49036dffe9XrKdDXzomW44c` |

全文已由评审产出；关键硬证据：

- **opus**：`session.error.sessionID` 为 optional；plugin/skill 可无 sid；abort 发 `MessageAbortedError`；message 可能是 `Cause.pretty`  
- **grok/gpt**：`sessions.py` Exception→503；`full/{mid}` 后置 32MiB；hub DROP session.error；skeleton 保留 text  

---

## 4. 下一步建议

1. 按本文 §2 修订 `slimapi-gap-contract-v1-draft.md` → **v1-rc**  
2. 在 oc-slimapi 做 **G1 前提验证**（global event 是否含 session.error）  
3. 通过后再开实现 PR（顺序见 §0 落地顺序）  
