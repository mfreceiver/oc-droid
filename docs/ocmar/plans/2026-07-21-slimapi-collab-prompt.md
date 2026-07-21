# 给 oc-slimapi 的协作提示（可整段转发）

> 用途：转交 slimapi 项目组；对齐「体验优先」后的联合工作。  
> 详细方案：ocdroid `docs/0.11-ux-first-joint-plan.md`  
> 基线：ocdroid **v0.11.10** 已发；你们 **v0.3.0 / rev F** 已部署；Wire **1**

---

## 请 slimapi 阅读并回复

### 背景

契约 rev F 双方已对齐落地。我们不再以「等客户端发版 / 文档对齐」为主里程碑。  
**下一阶段指导思想（产品侧强制）：**

1. 尽快消灭用户可感知的 **「展开失败」** 死胡同  
2. **提升省流**（实测字节，而非口号）  
3. **弱网**下优先保留缓存、减少闪烁与请求风暴  

### 我们需要你们确认的默认决策（U1–U6）

请勾选同意 / 改提案：

| ID | 内容 | 我们的默认 |
|---|---|---|
| U1 | 瞬态 code：`503`/`upstream_unavailable`/传输超时 **可重试**；`message_not_found`/`message_too_large` **终态**；客户端自动尝试 ≤4、总预算 ~10s；优先 `Retry-After` | ☐ 同意 / ☐ 改：___ |
| U2 | 聚合 `413 response_too_large`：客户端 **必须处理两半 ids**（当前 ocdroid 只处理前半，将修） | ☐ |
| U3 | mid 级网络失败是否改为「成功 items 保留 + errors[] 可重试」 | ☐ 先看指标再定 / ☐ 现在就改契约 |
| U4 | reasoning 省略 + hasFull/omitted 联合窗口 | ☐ 测字节后再立项 / ☐ 现在设计 |
| U5 | too_large 在 v1 为终态 | ☐ |
| U6 | 双方共用 2–3 个代表会话 fixture 测展开成功率与 skeleton/full 字节比 | ☐ |

另：去 placeholder、roots 默认、health 三键当 feature discovery、伪造 start 分页——**本周期默认不做**。

### 请 slimapi 近期交付（支撑体验，非新大协议）

1. **G6 可观测**：ids 分布、items/errors 计数、413/503 计数、latency、骨架输出字节、`Retry-After` 次数  
2. 瞬态 **503 尽量带 `Retry-After`**（不止 transform_busy）  
3. **gzip** 与 thin 路径字节比粗分（尤其 reasoning vs text vs tool）  
4. **Fixtures**：  
   - 含 `thin_placeholder_*` 的消息  
   - 同 timestamp 不同 mid（F-1 `/since`）  
   - discovery 变更 / ready false→true（reconfigured）  
5. **部署身份**：运行中 tag/commit + health `sidecar.version`（避免 editable 漂移）  
6. 保持：**失败 discovery 不发 reconfigured**；reconfigured 与 resync 不同因双发  

### ocdroid 近期会做（知情即可）

- 修 413 只半批、IO/超时自动重试、部分成功只重试失败 mid、G6 404 能力缓存  
- 展开 UI 精确终态（非笼统「展开失败」）  
- `Complete=false` 不删未返回 prior sessions  
- 弱网保留缓存 + stale 指示  
- 流量按逻辑操作归因  
- F-1 运行时确认  
- 目标下一 patch（如 v0.11.11）  

### 联调失败时请按此模板回传

```
client: ocdroid v0.11.x / <sha>
server: slimapi <tag/commit> / health sidecar.version=
endpoint: <url> / mTLS?
path: /slimapi/messages/.../full?ids=
status: 
headers: Retry-After= ; X-Discovery-Ready=
body.code / envelope.errors:
sse: event= data=
client traffic: op=expand attempt= n=
```

### 请回复格式

1. U1–U6 勾选结果  
2. S-A–S-F 能否在何时给出  
3. 对 `docs/0.11-ux-first-joint-plan.md` 的异议条款（若有）  
4. 是否同意：本周期主里程碑 = **展开可靠 + 弱网保留 + 字节基线**，而非协议大改  

---

完整方案路径（ocdroid 仓）：

`docs/0.11-ux-first-joint-plan.md`
